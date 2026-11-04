// Checks Ranked Team Wars matchmaking, queues and team challenges (issue #1363): the queue book
// (registration, duplicates, consent, cancellation, 2v2-5v5 matching in FIFO order, same-team and
// shared-player prevention, arena reservations, confirmation, decline/expiry/disconnect returns,
// exactly-once match start, start failures, restart = empty queue), challenges (expiry, spam
// limits, separate from the public pool), bounded cooldowns, the shared participant rules (combat
// tag, membership, Day 3 unlock, Java + Bedrock UUIDs), lifesteal.yml settings, the match record
// kind column and audit log against a real SQLite database, and the wiring (Lifesteal only,
// delegation to the existing match lifecycle, no inventory or heart changes). The Gradle build
// has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-ranked-queue-check.init.gradle.kts lifestealRankedQueueCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (clicks, titles, Bedrock menus,
// real disconnects, teleports into the arena) is NOT covered here; see
// docs/lifesteal-ranked-queue.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.progression.ProgressionRules.RankedStatus
import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.ranked.queue.BoundedCooldowns
import com.liam.joshymc.lifesteal.ranked.queue.Challenge
import com.liam.joshymc.lifesteal.ranked.queue.ChallengeState
import com.liam.joshymc.lifesteal.ranked.queue.EntryKind
import com.liam.joshymc.lifesteal.ranked.queue.FirstInFirstOut
import com.liam.joshymc.lifesteal.ranked.queue.OpponentSelector
import com.liam.joshymc.lifesteal.ranked.queue.ProposalState
import com.liam.joshymc.lifesteal.ranked.queue.QueueEntry
import com.liam.joshymc.lifesteal.ranked.queue.QueueState
import com.liam.joshymc.lifesteal.ranked.queue.RankedQueueBook
import com.liam.joshymc.lifesteal.ranked.queue.RankedQueueBook.AcceptResult
import com.liam.joshymc.lifesteal.ranked.queue.RankedQueueBook.ConsentResult
import com.liam.joshymc.lifesteal.ranked.queue.RankedQueueStore
import com.liam.joshymc.manager.DatabaseManager
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private const val SEASON = "season-1"
private const val CONFIRM = 30_000L
private const val CONSENT = 60_000L
private const val TIMEOUT = 30 * 60_000L

/** Floodgate (Bedrock) ids are version-0 UUIDs built from the XUID; Java ids are version 4. */
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f4ac2d1e3b")
private val JAVA = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")

private var ids = 0
private fun id(prefix: String) = "$prefix-" + (++ids).toString(16).padStart(10, '0')

/** Players of team [team] (a stable UUID per team and index). */
private fun p(team: Int, i: Int) = UUID(1000L + team, i.toLong())

private fun entry(team: Int, size: Int, now: Long = 0, players: List<UUID> = List(size) { p(team, it) }, kind: EntryKind = EntryKind.PUBLIC, challengeId: String? = null, season: String = SEASON) =
    QueueEntry(id("rq"), kind, "team-$team", "Team$team", size, players, players.first(), now, season, challengeId)

/** Registers and has everyone agree (WAITING). */
private fun queued(book: RankedQueueBook, team: Int, size: Int, now: Long = 0, players: List<UUID> = List(size) { p(team, it) }): QueueEntry {
    val e = entry(team, size, now, players)
    check("register team $team ${size}v$size", book.register(e, listOf(players.first()), now) == null)
    for (u in players.drop(1)) book.consent(u, now)
    check("team $team waiting", e.state == QueueState.WAITING)
    return e
}

fun main() {
    states()
    registration()
    consent()
    matching()
    confirmation()
    challenges()
    cooldowns()
    rules()
    settings()
    store()
    wiring()
    println("OK: $checks Ranked Team Wars matchmaking checks passed.")
}

// ── States ───────────────────────────────────────────────────

private fun states() {
    val path = listOf(QueueState.FORMING, QueueState.WAITING, QueueState.MATCH_FOUND, QueueState.CONFIRMING, QueueState.PREPARING, QueueState.MATCHED)
    for ((a, b) in path.zipWithNext()) check("$a -> $b allowed", a.canMoveTo(b))
    for (s in listOf(QueueState.FORMING, QueueState.WAITING, QueueState.MATCH_FOUND, QueueState.CONFIRMING, QueueState.PREPARING)) check("$s -> CANCELLED allowed", s.canMoveTo(QueueState.CANCELLED))
    check("a failed found match goes back to WAITING", QueueState.CONFIRMING.canMoveTo(QueueState.WAITING) && QueueState.PREPARING.canMoveTo(QueueState.WAITING))
    check("no skipping consent: FORMING -> CONFIRMING refused", !QueueState.FORMING.canMoveTo(QueueState.CONFIRMING))
    check("no match without confirmation: WAITING -> PREPARING refused", !QueueState.WAITING.canMoveTo(QueueState.PREPARING))
    check("terminal states go nowhere", QueueState.entries.filter { it.terminal }.all { t -> QueueState.entries.none { t.canMoveTo(it) } })
    check("tokens: ids are recognised, junk isn't", RankedQueueBook.validToken(RankedQueueBook.newId("rq")) && RankedQueueBook.validToken(RankedQueueBook.newId("rp")) && !RankedQueueBook.validToken("rq-xyz") && !RankedQueueBook.validToken("../../x"))
    check("sizes parse: 2v2, 3, 5V5", RankedQueueBook.parseSize("2v2") == 2 && RankedQueueBook.parseSize("3") == 3 && RankedQueueBook.parseSize("5V5") == 5)
    check("sizes parse: 2v3 and words refused", RankedQueueBook.parseSize("2v3") == null && RankedQueueBook.parseSize("two") == null)
}

// ── Registration ─────────────────────────────────────────────

private fun registration() {
    val book = RankedQueueBook()
    val e = entry(1, 2)
    check("register: ok", book.register(e, listOf(p(1, 0)), 0) == null)
    check("register: FORMING until teammates agree", e.state == QueueState.FORMING && e.pendingConsent == listOf(p(1, 1)))
    check("register: the leader who queued and plays counts as agreed", p(1, 0) in e.consented)
    check("index: by player and team", book.entryOf(p(1, 1)) === e && book.entryOfTeam("team-1") === e)
    check("a team can't enter two queues", book.register(entry(1, 3, players = List(3) { p(1, it + 10) }), emptyList(), 0) != null)
    check("a player can't be in two entries (even for another team)", book.register(entry(2, 2, players = listOf(p(2, 0), p(1, 1))), emptyList(), 0) != null)
    check("duplicate registration of the same entry refused", book.register(e, emptyList(), 0) != null)
    check("exactly the size is required", book.register(entry(3, 3, players = listOf(p(3, 0), p(3, 1))), emptyList(), 0) != null)
    check("the same player twice refused", book.register(QueueEntry(id("rq"), EntryKind.PUBLIC, "team-4", "T4", 2, listOf(p(4, 0), p(4, 0)), p(4, 0), 0, SEASON), emptyList(), 0) != null)
    check("a failed registration changes nothing", book.entryOfTeam("team-2") == null && book.entryOf(p(2, 0)) == null && book.allEntries().size == 1)
    // A five-member team queueing 2v2 only lists the two who play.
    val five = entry(5, 2, players = listOf(p(5, 3), p(5, 4)))
    check("a 5-member team can queue 2v2 with two of them", book.register(five, emptyList(), 0) == null && book.entryOf(p(5, 0)) == null && book.entryOf(p(5, 3)) === five)
    check("a leader who doesn't play isn't a participant", p(5, 3) == five.initiator && five.pendingConsent.size == 2)

    val cancelled = book.end(e.id, QueueState.CANCELLED, 1)
    check("cancel: ended", cancelled?.entry === e && e.state == QueueState.CANCELLED)
    check("cancel: every reservation released", book.entryOf(p(1, 0)) == null && book.entryOf(p(1, 1)) == null && book.entryOfTeam("team-1") == null)
    check("cancel: unrelated entries kept", book.entryOf(p(5, 3)) === five)
    check("cancel twice does nothing", book.end(e.id, QueueState.CANCELLED, 2) == null)
    check("after cancelling the team can register again", book.register(entry(1, 2), emptyList(), 3) == null)

    val small = RankedQueueBook(RankedQueueBook.Limits(maxEntries = 3))
    for (t in 0 until 3) small.register(entry(100 + t, 2), emptyList(), 0)
    check("the queue is bounded", small.register(entry(200, 2), emptyList(), 0) != null && small.allEntries().size == 3)
    check("restart = an empty book (nothing is resumed)", RankedQueueBook().isEmpty)
    val cleared = book.clear(10)
    check("shutdown/reload clears everything", book.isEmpty && cleared.all { it.state == QueueState.CANCELLED } && book.entryOf(p(5, 3)) == null)
}

// ── Consent ──────────────────────────────────────────────────

private fun consent() {
    val book = RankedQueueBook()
    val e = entry(1, 3)
    book.register(e, listOf(p(1, 0)), 0)
    check("consent: a stranger has nothing to agree to", book.consent(p(9, 9), 1) == ConsentResult.NotAsked)
    val first = book.consent(p(1, 1), 1)
    check("consent: recorded, not complete", first is ConsentResult.Recorded && !first.complete && e.state == QueueState.FORMING)
    check("consent: twice is a no-op", book.consent(p(1, 1), 2) == ConsentResult.Already)
    val last = book.consent(p(1, 2), 3)
    check("consent: everyone agreed -> WAITING", last is ConsentResult.Recorded && last.complete && e.state == QueueState.WAITING && e.waitingSince == 3L)
    check("consent: nothing more to agree to once waiting", book.consent(p(1, 2), 4) == ConsentResult.NotAsked)

    // A teammate who declines ends the whole roster (no one is forced to play).
    val d = entry(2, 2)
    book.register(d, listOf(p(2, 0)), 0)
    book.end(d.id, QueueState.CANCELLED, 5)
    check("decline while agreeing: the roster is dropped", book.entryOf(p(2, 0)) == null && book.entryOf(p(2, 1)) == null)

    // Nobody answers: it expires.
    val x = entry(3, 2, now = 0)
    book.register(x, listOf(p(3, 0)), 0)
    val exp = book.expire(CONSENT, CONSENT, TIMEOUT)
    check("consent timeout: expired and released", x.state == QueueState.EXPIRED && book.entryOf(p(3, 1)) == null && exp.ended.any { it.entry === x })
    check("a waiting entry doesn't expire at the consent timeout", e.state == QueueState.WAITING)
    book.expire(3 + TIMEOUT, CONSENT, TIMEOUT)
    check("queue timeout: a long-waiting entry expires (no unbounded waiting)", e.state == QueueState.EXPIRED && book.isEmpty)
}

// ── Matching ─────────────────────────────────────────────────

private fun matching() {
    for (size in 2..5) {
        val book = RankedQueueBook()
        val a = queued(book, 1, size, now = 10)
        val b = queued(book, 2, size, now = 20)
        val pair = FirstInFirstOut.select(book.waiting(size), book::compatible)
        check("${size}v$size: the two waiting teams are paired", pair?.first === a && pair.second === b)
        val proposal = book.propose(a, b, "arena", id("rp"), 30, CONFIRM)
        check("${size}v$size: proposal holds both rosters (${size * 2} players)", proposal != null && proposal.participants.size == size * 2 && a.state == QueueState.CONFIRMING && b.state == QueueState.CONFIRMING)
    }

    val book = RankedQueueBook()
    val two = queued(book, 1, 2, now = 1)
    val three = queued(book, 2, 3, now = 2)
    check("different sizes never pair", FirstInFirstOut.select(book.waiting(2), book::compatible) == null && FirstInFirstOut.select(book.waiting(3), book::compatible) == null)
    val oldest = queued(book, 3, 2, now = 3)
    queued(book, 4, 2, now = 4)
    val pair = FirstInFirstOut.select(book.waiting(2), book::compatible)
    check("FIFO: the longest-waiting team gets the longest-waiting opponent", pair?.first === two && pair.second === oldest)
    check("a team never matches itself", !book.compatible(two, two))
    val sameTeam = QueueEntry(id("rq"), EntryKind.PUBLIC, "team-1", "Team1", 2, listOf(p(1, 7), p(1, 8)), p(1, 7), 0, SEASON)
    check("two entries of one team are never compatible", !book.compatible(two, sameTeam))
    val shared = QueueEntry(id("rq"), EntryKind.PUBLIC, "team-9", "Team9", 2, listOf(p(9, 0), p(1, 0)), p(9, 0), 0, SEASON)
    check("entries sharing a player are never compatible", !book.compatible(two, shared))
    check("another season's entry is never compatible", !book.compatible(two, entry(8, 2, season = "season-2")))
    check("a challenge roster is never in the public pool", book.waiting(2).none { it.kind == EntryKind.CHALLENGE })

    // Arena reservation: a proposal holds its arena; the next pair can't take it.
    val p1 = book.propose(two, oldest, "arena-1", id("rp"), 5, CONFIRM)!!
    check("the arena is reserved by its proposal", book.reservedArenas() == setOf("arena-1"))
    val rest = book.waiting(2)
    check("the remaining team waits (no opponent left)", rest.size == 1 && FirstInFirstOut.select(rest, book::compatible) == null)
    val five = queued(book, 5, 2, now = 6)
    check("a reserved arena can't be proposed again", book.propose(rest[0], five, "arena-1", id("rp"), 7, CONFIRM) == null && rest[0].state == QueueState.WAITING)
    check("another free arena can", book.propose(rest[0], five, "arena-2", id("rp"), 7, CONFIRM) != null)
    check("entries already in a proposal aren't offered again", book.waiting(2).isEmpty() && book.propose(two, five, "arena-3", id("rp"), 8, CONFIRM) == null)
    check("proposals: one per player", book.proposalOf(p(1, 0))?.id == p1.id)
    check("waiting 3v3 untouched", three.state == QueueState.WAITING)

    // Selector seam: a different policy plugs in without touching the book.
    val newestFirst = OpponentSelector { waiting, ok -> waiting.reversed().zipWithNext().firstOrNull { (x, y) -> ok(x, y) } }
    val b2 = RankedQueueBook()
    val o = queued(b2, 1, 2, now = 1); queued(b2, 2, 2, now = 2); val n = queued(b2, 3, 2, now = 3)
    check("a custom OpponentSelector (future Elo matching) is honoured", newestFirst.select(b2.waiting(2), b2::compatible)?.first === n && FirstInFirstOut.select(b2.waiting(2), b2::compatible)?.first === o)
}

// ── Confirmation, decline, disconnect, start ─────────────────

private fun confirmation() {
    val book = RankedQueueBook()
    val a = queued(book, 1, 2, now = 1)
    val b = queued(book, 2, 2, now = 2)
    val prop = book.propose(a, b, "arena", id("rp"), 10, CONFIRM)!!
    check("accept: a stranger has nothing to accept", book.accept(p(9, 9), null, 11) == AcceptResult.NothingToAccept)
    check("accept: bound to the player's own proposal (a foreign token is stale)", book.accept(p(1, 0), "rp-ffffffffff", 11) == AcceptResult.Stale)
    check("accept: recorded", book.accept(p(1, 0), prop.id, 11) is AcceptResult.Accepted)
    check("accept: duplicate accept is a no-op", book.accept(p(1, 0), prop.id, 12) == AcceptResult.Already && prop.accepted.size == 1)
    check("start refused until everyone accepted", book.beginStart(prop.id, 12) == null && prop.state == ProposalState.CONFIRMING)
    for (u in listOf(p(1, 1), p(2, 0), p(2, 1))) book.accept(u, prop.id, 13)
    check("everyone accepted", prop.complete)
    check("start: CONFIRMING -> STARTING once", book.beginStart(prop.id, 14) === prop && a.state == QueueState.PREPARING)
    check("start: a second start is refused (no duplicate match)", book.beginStart(prop.id, 15) == null)
    check("accept after the start is stale", book.accept(p(1, 0), prop.id, 15) == AcceptResult.Stale)
    check("matched: handed over", book.matched(prop.id, 16) === prop && prop.state == ProposalState.STARTED)
    check("matched: entries and players released to the match manager", a.state == QueueState.MATCHED && b.state == QueueState.MATCHED && book.entryOf(p(1, 0)) == null && book.isEmpty)
    check("matched twice does nothing", book.matched(prop.id, 17) == null)

    // Decline: the decliner's entry ends; the opponent goes back to WAITING with its place.
    val c = queued(book, 3, 2, now = 100)
    val d = queued(book, 4, 2, now = 200)
    val pr = book.propose(c, d, "arena", id("rp"), 300, CONFIRM)!!
    book.accept(p(3, 0), pr.id, 301)
    val declined = book.end(d.id, QueueState.CANCELLED, 302)!!
    check("decline: the proposal is invalidated", declined.proposal === pr && pr.state == ProposalState.CANCELLED && book.proposal(pr.id) == null)
    check("decline: the other team is back in the queue with its original place", declined.returned === c && c.state == QueueState.WAITING && c.waitingSince == 100L && c.proposalId == null)
    check("decline: the old proposal can't be accepted any more", book.accept(p(3, 1), pr.id, 303) == AcceptResult.Stale)
    check("decline: the arena is free again", book.reservedArenas().isEmpty())

    // Expiry: only the team that didn't accept expires; its silent players are reported.
    val e = queued(book, 5, 2, now = 400)
    val pe = book.propose(c, e, "arena", id("rp"), 500, CONFIRM)!!
    for (u in c.participants) book.accept(u, pe.id, 501)
    book.accept(p(5, 0), pe.id, 501)
    check("an expired confirmation can't be used", book.accept(p(5, 1), pe.id, 500 + CONFIRM) == AcceptResult.Expired)
    val exp = book.expire(500 + CONFIRM, CONSENT, TIMEOUT)
    check("expiry: the silent player is reported (decline cooldown)", exp.ignoredBy == listOf(p(5, 1)))
    check("expiry: the team that didn't accept expires", e.state == QueueState.EXPIRED && book.entryOf(p(5, 0)) == null)
    check("expiry: the team that accepted waits again", c.state == QueueState.WAITING && c.waitingSince == 100L)

    // Disconnect while confirming = that entry ends (the manager also applies the cooldown).
    val f = queued(book, 6, 2, now = 600)
    val pf = book.propose(c, f, "arena", id("rp"), 700, CONFIRM)!!
    book.end(f.id, QueueState.CANCELLED, 701)
    check("disconnect while confirming: proposal gone, opponent waits", book.proposal(pf.id) == null && c.state == QueueState.WAITING)
    check("reconnect: nothing is resumed, the player can be queued again", book.entryOf(p(6, 0)) == null && book.register(entry(6, 2), emptyList(), 702) == null)

    // A start that fails for reasons outside the teams (arena gone): both wait again.
    val g = queued(book, 7, 2, now = 800)
    book.end(book.entryOfTeam("team-6")!!.id, QueueState.CANCELLED, 801)
    val pg = book.propose(c, g, "arena", id("rp"), 900, CONFIRM)!!
    for (u in pg.participants) book.accept(u, pg.id, 901)
    book.beginStart(pg.id, 902)
    val noArena = book.startFailed(pg.id, emptySet(), 903)!!
    check("missing arena at the start: nobody at fault, both back in the queue", noArena.returned.toSet() == setOf(c, g) && noArena.ended.isEmpty() && c.state == QueueState.WAITING)
    // A start that fails because of one team (combat tag, left the team): only that team ends.
    val ph = book.propose(c, g, "arena", id("rp"), 904, CONFIRM)!!
    for (u in ph.participants) book.accept(u, ph.id, 905)
    book.beginStart(ph.id, 906)
    val fault = book.startFailed(ph.id, setOf(g.id), 907)!!
    check("revalidation failure: the faulty team ends, the other keeps its place", fault.ended == listOf(g) && fault.returned == listOf(c) && c.waitingSince == 100L)
    check("no proposal or arena reservation left behind", book.allProposals().isEmpty() && book.reservedArenas().isEmpty())
}

// ── Challenges ───────────────────────────────────────────────

private fun challengeFor(book: RankedQueueBook, from: Int, to: Int, size: Int, now: Long, expires: Long = 120_000): Pair<Challenge, QueueEntry> {
    val cid = id("rc")
    val e = entry(from, size, now, kind = EntryKind.CHALLENGE, challengeId = cid)
    val c = Challenge(cid, "team-$from", "Team$from", "team-$to", "Team$to", size, e.id, now, now + expires)
    check("challenge $from -> $to registered", book.challenge(c, e, listOf(p(from, 0)), now) == null)
    return c to e
}

private fun challenges() {
    val book = RankedQueueBook()
    val (c, from) = challengeFor(book, 1, 2, 3, now = 0)
    check("challenge: pending and listed for the target", c.state == ChallengeState.PENDING && book.incomingChallenges("team-2") == listOf(c) && book.outgoingChallenge("team-1") === c)
    check("challenge: the challenger roster is FORMING, never in the public queue", from.state == QueueState.FORMING && book.waiting(3).isEmpty())
    check("a team can't challenge itself", run {
        val cid = id("rc"); val e = entry(3, 2, kind = EntryKind.CHALLENGE, challengeId = cid)
        book.challenge(Challenge(cid, "team-3", "Team3", "team-3", "Team3", 2, e.id, 0, 1000), e, emptyList(), 0) != null
    })
    check("spam: one open challenge per team", run {
        val cid = id("rc"); val e = entry(1, 2, players = listOf(p(1, 20), p(1, 21)), kind = EntryKind.CHALLENGE, challengeId = cid)
        book.challenge(Challenge(cid, "team-1", "Team1", "team-4", "Team4", 2, e.id, 0, 1000), e, emptyList(), 0) != null
    })
    // The challenged team answers with its own roster; both rosters agree; then a normal proposal.
    for (u in from.participants.drop(1)) book.consent(u, 1)
    check("challenger roster ready (waiting for the answer)", from.state == QueueState.WAITING && book.readyChallenges().isEmpty())
    val answer = entry(2, 3, now = 2, kind = EntryKind.CHALLENGE, challengeId = c.id)
    check("a roster of the wrong size can't answer", book.acceptChallenge(c.id, entry(2, 2, players = listOf(p(2, 30), p(2, 31)), kind = EntryKind.CHALLENGE, challengeId = c.id), emptyList(), 2) != null)
    check("the challenged team answers", book.acceptChallenge(c.id, answer, listOf(p(2, 0)), 2) == null && c.state == ChallengeState.ACCEPTED)
    check("answering twice refused", book.acceptChallenge(c.id, entry(2, 3, players = List(3) { p(2, it + 40) }, kind = EntryKind.CHALLENGE, challengeId = c.id), emptyList(), 3) != null)
    for (u in answer.participants.drop(1)) book.consent(u, 3)
    val ready = book.readyChallenges()
    check("both rosters agreed: ready to propose", ready.size == 1 && ready[0].first === from && ready[0].second === answer)
    val pc = book.propose(from, answer, "arena", id("rp"), 4, CONFIRM)
    check("challenge proposal is kind CHALLENGE (recorded apart from public matches)", pc != null && pc.kind == EntryKind.CHALLENGE && pc.challengeId == c.id)
    check("a challenge roster can't be paired with a public entry", run {
        val b2 = RankedQueueBook()
        val (_, ce) = challengeFor(b2, 5, 6, 2, now = 0)
        for (u in ce.participants.drop(1)) b2.consent(u, 0)
        val pub = queued(b2, 7, 2, now = 0)
        b2.propose(ce, pub, "arena", id("rp"), 1, CONFIRM) == null
    })
    // Decline of the found match ends the whole challenge (no public re-queue).
    val ended = book.end(answer.id, QueueState.CANCELLED, 5)!!
    check("declining a challenge match ends the challenge and both rosters", ended.challenge === c && c.state == ChallengeState.CANCELLED && ended.alsoEnded === from && book.isEmpty)

    // Expiry and decline of an unanswered challenge.
    val b3 = RankedQueueBook()
    val (c2, f2) = challengeFor(b3, 1, 2, 2, now = 0, expires = 10_000)
    check("challenge not expired early", b3.expire(9_999, 10 * CONSENT, TIMEOUT).ended.isEmpty() && c2.state == ChallengeState.PENDING)
    b3.expire(10_000, 10 * CONSENT, TIMEOUT)
    check("challenge expiry: challenge and roster released", c2.state == ChallengeState.EXPIRED && f2.state == QueueState.EXPIRED && b3.isEmpty)
    val (c3, f3) = challengeFor(b3, 3, 4, 2, now = 0)
    check("decline: challenger roster released", b3.declineChallenge(c3.id, 1) === c3 && c3.state == ChallengeState.DECLINED && f3.state == QueueState.CANCELLED && b3.isEmpty)
    check("answering an expired challenge refused", run {
        val (c4, _) = challengeFor(b3, 5, 6, 2, now = 0, expires = 100)
        b3.acceptChallenge(c4.id, entry(6, 2, kind = EntryKind.CHALLENGE, challengeId = c4.id), emptyList(), 100) != null
    })

    // Incoming limit per team (simultaneous challenges).
    val b4 = RankedQueueBook(RankedQueueBook.Limits(maxIncomingPerTeam = 2))
    challengeFor(b4, 10, 1, 2, now = 0)
    challengeFor(b4, 11, 1, 2, now = 0)
    check("spam: a team can't receive unlimited challenges", run {
        val cid = id("rc"); val e = entry(12, 2, kind = EntryKind.CHALLENGE, challengeId = cid)
        b4.challenge(Challenge(cid, "team-12", "Team12", "team-1", "Team1", 2, e.id, 0, 1000), e, emptyList(), 0) != null && b4.entryOfTeam("team-12") == null
    })
    // Faulty side at the start: the whole challenge ends.
    val b5 = RankedQueueBook()
    val (c5, f5) = challengeFor(b5, 1, 2, 2, now = 0)
    b5.consent(f5.participants[1], 0)
    val a5 = entry(2, 2, kind = EntryKind.CHALLENGE, challengeId = c5.id)
    b5.acceptChallenge(c5.id, a5, listOf(p(2, 0)), 0); b5.consent(p(2, 1), 0)
    val p5 = b5.propose(f5, a5, "arena", id("rp"), 1, CONFIRM)!!
    for (u in p5.participants) b5.accept(u, p5.id, 2)
    b5.beginStart(p5.id, 3)
    val failed = b5.startFailed(p5.id, setOf(a5.id), 4)!!
    check("challenge start failure with a faulty side ends the challenge", failed.challenge === c5 && failed.ended.size == 2 && b5.isEmpty)
}

// ── Cooldowns ────────────────────────────────────────────────

private fun cooldowns() {
    val c = BoundedCooldowns<String>(3)
    c.start("a", 0, 1000)
    check("cooldown: running", c.remaining("a", 500) == 500L)
    check("cooldown: over", c.remaining("a", 1000) == 0L)
    check("cooldown: zero length is no cooldown", run { c.start("z", 0, 0); c.remaining("z", 0) == 0L })
    for (k in listOf("b", "c", "d", "e")) c.start(k, 0, 10_000)
    check("cooldowns are bounded (oldest dropped)", c.size == 3 && c.remaining("b", 1) == 0L && c.remaining("e", 1) > 0)
    c.sweep(20_000)
    check("cooldown sweep clears expired keys", c.size == 0)
}

// ── Shared rules (participants, unlock, ids) ─────────────────

private fun facts(uuid: UUID, name: String = "P") = RankedMatchRules.ParticipantFacts(
    uuid = uuid, name = name, side = Side.A, member = true, online = true, inLifesteal = true, sessionLoaded = true, hearts = 10,
    inOtherMatch = false, pendingRestore = false, transferring = false, combatTagged = false, dead = false, survivalMode = true,
)

private fun rules() {
    val limits = 1..20
    check("eligible participant", RankedMatchRules.participantProblem(facts(JAVA), limits) == null)
    check("combat-tagged players can't queue or accept", RankedMatchRules.participantProblem(facts(JAVA).copy(combatTagged = true), limits) == "is in combat")
    check("non-members can't fill a team slot", RankedMatchRules.participantProblem(facts(JAVA).copy(member = false), limits)?.contains("member") == true)
    check("players already in a ranked match can't queue", RankedMatchRules.participantProblem(facts(JAVA).copy(inOtherMatch = true), limits)?.contains("already") == true)
    check("players switching servers can't queue", RankedMatchRules.participantProblem(facts(JAVA).copy(transferring = true), limits)?.contains("switching") == true)
    check("heart data must be initialized", RankedMatchRules.participantProblem(facts(JAVA).copy(sessionLoaded = false), limits) != null && RankedMatchRules.participantProblem(facts(JAVA).copy(hearts = null), limits) != null)
    check("1 and 20 hearts are fine (no elimination, normal limits)", RankedMatchRules.participantProblem(facts(JAVA).copy(hearts = 1), limits) == null && RankedMatchRules.participantProblem(facts(JAVA).copy(hearts = 20), limits) == null)
    check("Bedrock (Floodgate) players use the same rules", RankedMatchRules.participantProblem(facts(BEDROCK, ".Bedrock"), limits) == null)
    val book = RankedQueueBook()
    val mixed = queued(book, 1, 2, players = listOf(JAVA, BEDROCK))
    check("a mixed Java + Bedrock team queues by UUID", book.entryOf(BEDROCK) === mixed && book.entryOf(JAVA) === mixed && BEDROCK.version() == 0 && JAVA.version() == 4)

    // Day 3 unlock: the queue needs the progression's AVAILABLE (scheduled + this system ready).
    val launch = 1_700_000_000_000L
    val h = 3_600_000L
    val season = ProgressionRules.Season(ProgressionRules.FIRST_SEASON_ID, launch, 1)
    val schedule = ProgressionRules.Schedule(24, 72, 168)
    fun at(offset: Long) = ProgressionRules.evaluate(season, schedule, launch + offset, emptySet())
    check("Day 2: locked even with matchmaking ready", ProgressionRules.rankedStatus(at(71 * h), true) == RankedStatus.LOCKED)
    check("Day 3 (+72h): available once matchmaking is ready", ProgressionRules.rankedStatus(at(72 * h), true) == RankedStatus.AVAILABLE)
    check("Day 3 with matchmaking off: unlocked by schedule only", ProgressionRules.rankedStatus(at(72 * h), false) == RankedStatus.UNLOCKED_UNAVAILABLE)
}

// ── Settings ─────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun settings() {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    val map: Map<String, Any?> = Yaml().load(text)
    fun parse(m: Map<String, Any?>) = LifestealConfigParser.parse { path -> path.split('.').fold<String, Any?>(m) { node, key -> (node as? Map<*, *>)?.get(key) } }
    val bundled = parse(map)
    check("bundled lifesteal.yml has no problems", bundled.problems.isEmpty())
    val mm = bundled.settings!!.rankedTeamWars.matchmaking
    check("matchmaking defaults", mm.enabled && mm.consentSeconds == 60 && mm.confirmSeconds == 30 && mm.queueTimeoutMinutes == 30 && mm.requeueCooldownSeconds == 15 && mm.declineCooldownSeconds == 60 && mm.challengeExpireSeconds == 120 && mm.challengeCooldownSeconds == 60)
    fun withMatchmaking(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
        val m = map.toMutableMap()
        val ranked = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        val section = (ranked["matchmaking"] as Map<String, Any?>).toMutableMap()
        edit(section)
        ranked["matchmaking"] = section
        m["ranked-team-wars"] = ranked
        return m
    }
    check("confirm 5s refused", parse(withMatchmaking { it["confirm-seconds"] = 5 }).settings == null)
    check("confirm 121s refused", parse(withMatchmaking { it["confirm-seconds"] = 121 }).settings == null)
    check("enabled 'yes' refused", parse(withMatchmaking { it["enabled"] = "yes" }).settings == null)
    check("queue timeout 0 refused", parse(withMatchmaking { it["queue-timeout-minutes"] = 0 }).settings == null)
    check("disabled accepted", parse(withMatchmaking { it["enabled"] = false }).settings?.rankedTeamWars?.matchmaking?.enabled == false)
    check("confirm 45 accepted", parse(withMatchmaking { it["confirm-seconds"] = 45 }).settings?.rankedTeamWars?.matchmaking?.confirmSeconds == 45)
    val old = map.toMutableMap().also { m -> m["ranked-team-wars"] = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap().also { it.remove("matchmaking") } }
    val parsedOld = parse(old)
    check("a lifesteal.yml from before #1363 loads with defaults and one warning", parsedOld.settings?.rankedTeamWars?.matchmaking == mm && parsedOld.problems.size == 1 && parsedOld.problems.none { it.severe })
    check("the ranked gameplay settings are untouched", bundled.settings!!.rankedTeamWars.teamSizes == listOf(2, 3, 4, 5))
}

// ── Store (real SQLite) ──────────────────────────────────────

private fun store() {
    val dir = Files.createTempDirectory("ranked-queue-check").toFile()
    try {
        // A database from before #1363: match table without kind/queue_ref.
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                db.execute("CREATE TABLE lifesteal_ranked_matches (match_id TEXT PRIMARY KEY, season_id TEXT NOT NULL, arena_id TEXT NOT NULL, team_a_id TEXT NOT NULL, team_a_name TEXT NOT NULL, team_b_id TEXT NOT NULL, team_b_name TEXT NOT NULL, size INTEGER NOT NULL, test INTEGER NOT NULL DEFAULT 0, state TEXT NOT NULL, created_by TEXT NOT NULL, created_at INTEGER NOT NULL, started_at INTEGER, ended_at INTEGER, winner_side TEXT, winner_team_id TEXT, outcome TEXT, cancel_reason TEXT, restoration TEXT NOT NULL DEFAULT 'PENDING', version INTEGER NOT NULL DEFAULT 1)")
                db.execute("INSERT INTO lifesteal_ranked_matches (match_id, season_id, arena_id, team_a_id, team_a_name, team_b_id, team_b_name, size, test, state, created_by, created_at) VALUES ('rtw-000000000001', 's', 'duel', 'a', 'A', 'b', 'B', 2, 1, 'CANCELLED', 'console', 1)")
            } finally { db.shutdown() }
        }
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val s = RankedStore(db)
                s.createTables()
                s.createTables() // twice: the migration is idempotent
                check("migration: old rows read as TEST (they were staff test matches)", s.match("rtw-000000000001")?.kind == MatchKind.TEST)
                s.createArena(RankedArena("duel", "Duel", "lifesteal", null, enabled = true), 2)
                s.createArena(RankedArena("duel2", "Duel 2", "lifesteal", null, enabled = true), 2)
                val pa = listOf(MatchParticipant(UUID(1, 1), Side.A, "a"), MatchParticipant(UUID(2, 1), Side.B, "b"))
                fun snap(u: UUID) = RankedStore.SnapshotData(u, List(41) { null }, 0, "lifesteal", Point(0.0, 64.0, 0.0))
                s.insertMatch(RankedStore.NewMatch("rtw-000000000002", "season-1", "duel", "a", "A", "b", "B", 2, false, "queue:rp-0000000001", 3, MatchKind.PUBLIC, "rp-0000000001"), pa, pa.map { snap(it.uuid) })
                val pub = s.match("rtw-000000000002")!!
                check("public match recorded as PUBLIC with its proposal", pub.kind == MatchKind.PUBLIC && pub.queueRef == "rp-0000000001" && !pub.test)
                s.cancel("rtw-000000000002", "test", 4)
                val pb = listOf(MatchParticipant(UUID(1, 2), Side.A, "a"), MatchParticipant(UUID(2, 2), Side.B, "b"))
                s.insertMatch(RankedStore.NewMatch("rtw-000000000003", "season-1", "duel2", "a", "A", "b", "B", 2, false, "queue:rp-0000000002", 5, MatchKind.CHALLENGE, "rp-0000000002"), pb, pb.map { snap(it.uuid) })
                check("direct challenges are classified separately in the match record", s.match("rtw-000000000003")?.kind == MatchKind.CHALLENGE)
                check("records list keeps the kind", s.recentMatches(5).map { it.kind }.toSet() == setOf(MatchKind.TEST, MatchKind.PUBLIC, MatchKind.CHALLENGE))

                val q = RankedQueueStore(db)
                q.createTables()
                q.log(10, "QUEUED", "rq-0000000001", "a", BEDROCK, "2v2")
                q.log(11, "STARTED", "rp-0000000001", null, null, "match rtw-000000000002")
                val rows = q.recent(10)
                check("audit: newest first, UUIDs kept (Bedrock ids too)", rows.size == 2 && rows[0].kind == "STARTED" && rows[1].uuid == BEDROCK)
                check("audit: retention prunes old rows only", q.prune(11 + RankedQueueStore.RETENTION_DAYS * 24 * 3_600_000L) == 1 && q.recent(10).single().kind == "STARTED")
                check("the queue itself has no table (nothing to resume after a restart)", db.queryFirst("SELECT COUNT(*) AS n FROM sqlite_master WHERE name LIKE 'lifesteal_ranked_queue%' AND type = 'table'") { it.getInt("n") } == 1)
            } finally { db.shutdown() }
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring (source) ──────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/ranked/queue/LifestealRankedQueueManager.kt")
    val bookSrc = source("lifesteal/ranked/queue/RankedQueue.kt")
    val command = source("lifesteal/ranked/queue/RankedCommand.kt")
    val menu = source("lifesteal/ranked/queue/RankedQueueMenu.kt")
    val queueSources = listOf(manager, bookSrc, command, menu, source("lifesteal/ranked/queue/RankedQueueStore.kt"))
    val rankedManager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val rankedListener = source("lifesteal/ranked/LifestealRankedListener.kt")
    val commands = source("manager/CommandManager.kt")
    val main = source("Joshymc.kt")
    val plugin = File("src/main/resources/plugin.yml").readText()

    // Survival isolation.
    check("/ranked is a LIFESTEAL command", GamemodeAssignments.COMMANDS["ranked"] == GamemodeScope.LIFESTEAL)
    check("/ranked only registered where Lifesteal is hosted, released elsewhere", Regex("""if \(plugin\.lifestealRankedQueueManager\.hosted\) \{[\s\S]*?getCommand\("ranked"\)[\s\S]*?\} else \{\s*\n\s*unregisterCommand\("ranked"\)""").containsMatchIn(commands))
    check("manager does nothing where Lifesteal isn't hosted", Regex("""if \(!hosted\) \{\s*\n\s*settings = null\s*\n\s*sizes = emptyList\(\)\s*\n\s*playerAccess = false\s*\n\s*progression\.rankedTeamWarsSystem = null\s*\n\s*return""").containsMatchIn(manager))
    check("every action refuses off Lifesteal and outside Lifesteal worlds", manager.contains("if (!hosted) return \"Ranked Team Wars only exist on the Lifesteal server.\"") && manager.contains("!isLifesteal(player) -> \"Ranked Team Wars only work in Lifesteal.\""))
    check("Survival teams, arenas and combat never used", queueSources.none { it.contains("plugin.teamManager") || it.contains("arenaManager") || it.contains("tagPvp(") || it.contains("untag(") })
    check("permission joshymc.lifesteal.ranked.use, default true", Regex("""joshymc\.lifesteal\.ranked\.use:\s*\n\s*description: [^\n]+\n\s*default: true""").containsMatchIn(plugin) && Regex("""(?m)^  ranked:\s*\n(\s{4}.*\n)*?\s{4}permission: joshymc\.lifesteal\.ranked\.use""").containsMatchIn(plugin))

    // Lifecycle.
    check("refresh after the match manager on startup", main.indexOf("lifestealRankedQueueManager.refresh()") > main.indexOf("        lifestealRankedManager.refresh()"))
    check("refresh on reload", main.contains("safe(\"lifestealRankedQueueManager.refresh\") { lifestealRankedQueueManager.refresh() }"))
    check("shutdown step before the matches stop", main.contains("shutdown.step(\"lifestealRankedQueueManager.stop\", { ::lifestealRankedQueueManager.isInitialized })") && main.indexOf("\"lifestealRankedQueueManager.stop\"") < main.indexOf("\"lifestealRankedManager.stop\""))
    check("reload and shutdown cancel every pending entry (never resumed)", manager.contains("cancelAll(\"ranked queues were reset by a server reload\")") && manager.contains("cancelAll(\"the server is shutting down\")"))
    check("one matchmaking task, replaced on refresh", manager.contains("task?.cancel()") && manager.split("runTaskTimer(").size == 2)
    check("registers the ranked system with the progression (Day 3 unlock = AVAILABLE)", manager.contains("progression.rankedTeamWarsSystem = LifestealProgressionManager.RankedTeamWarsSystem { systemReady }"))
    check("queueing needs the season unlock", manager.contains("if (!plugin.lifestealProgressionManager.canEnterRankedQueue(now)) return LOCKED_MESSAGE"))
    check("disconnects end the entry (quit handled through the ranked listener)", rankedListener.contains("plugin.lifestealRankedQueueManager.onQuit(event.player)"))
    check("no Player references kept", queueSources.none { Regex("""(Map|Set|List)<[^>]*\bPlayer\b""").containsMatchIn(it) })

    // Authority and consent.
    check("only the team leader starts queues and challenges", manager.contains("if (team.leader != player.uniqueId) return \"Only your team leader can queue the team or send challenges.\""))
    check("only the leader answers challenges", manager.contains("if (own.leader != player.uniqueId) return null to \"Only your team leader can answer challenges.\""))
    check("non-members can't be picked", manager.contains("teams.teamOf(uuid)?.id != team.id -> \"\$name isn't in \${team.name}.\""))
    check("only the queuing leader counts as agreed; everyone else must accept", manager.contains("book.register(entry, listOf(player.uniqueId), now)") && manager.contains("book.challenge(challenge, entry, listOf(player.uniqueId), now)"))
    check("combat-tagged players can't accept a match", manager.contains("if (plugin.combatManager.isTagged(player)) return Msg.error(player, \"You can't accept while in combat."))
    check("team membership changes are revalidated", manager.contains("return \"\$name is no longer in \${team.name}\""))
    check("chat buttons carry the request id (no stale or spoofed answers)", manager.contains("\"/ranked accept \${p.id}\"") && manager.contains("\"/ranked accept \${entry.id}\"") && command.contains("RankedQueueBook.validToken(it)"))
    check("menu answers are bound to the request it showed", menu.contains("queue.accept(p, token)") && menu.contains("queue.decline(p, token)"))
    check("menu buttons re-check through the manager", menu.contains("queue.startProblem(p, size)") && menu.contains("queue.queueWith(p, pick.size, roster)"))
    check("menu uses the shared GUI framework", menu.contains("plugin.guiManager.open(player, gui)") && menu.contains("CustomGui(") && !menu.contains("Bukkit.createInventory"))
    check("decline/ignore/disconnect cooldown is bounded and temporary", manager.contains("BoundedCooldowns<UUID>(COOLDOWN_CAPACITY)") && !manager.contains("punishmentManager"))
    check("warnings: hearts at risk, gear preserved", manager.contains("Hearts are at risk: normal Lifesteal heart stealing applies") && manager.contains("your inventory and equipment are saved and given back after the match"))

    // Delegation to the existing match lifecycle; no inventory/heart/teleport code in the queue.
    check("matches are created only through the existing lifecycle", manager.contains("ranked.createQueuedMatch(a.teamId, b.teamId, p.size, arena.id, kind, a.participants, b.participants, p.id)") && rankedManager.contains("fun createQueuedMatch("))
    check("queued matches run the same validation/snapshot path as test matches", Regex("""fun createQueuedMatch\([\s\S]*?return startMatch\(""").containsMatchIn(rankedManager) && Regex("""fun createMatch\([\s\S]*?return startMatch\(""").containsMatchIn(rankedManager))
    check("the queue never touches inventories, positions, hearts or tags", queueSources.none { s ->
        listOf(".inventory", "setItem(Equipment", "teleport", "serializeAsBytes", "HeartStore", "lifestealHeartManager.adminChange", "transferPvp", "untag", ".health").any { s.contains(it) }
    })
    check("participants are validated by the match rules (same as a match start)", rankedManager.contains("return RankedMatchRules.participantProblem(facts(player, Side.A, team), limits.minimum..limits.maximum)"))
    check("arena revalidated at the start, never one reserved by another proposal", manager.contains("ranked.availability(it) == ArenaAvailability.AVAILABLE") && manager.contains("ranked.availableArenaFor(size, book.reservedArenas())"))
    check("challenges recorded as CHALLENGE matches", manager.contains("if (p.kind == EntryKind.CHALLENGE) MatchKind.CHALLENGE else MatchKind.PUBLIC"))
    check("no Elo, leaderboards or rewards", queueSources.none { Regex("""(fun|val|var|class) \w*(Elo|elo|Leaderboard|Reward)""").containsMatchIn(it) })
    check("no packet, Geyser or Floodgate code", queueSources.none { Regex("""import .*(geysermc|floodgate|protocollib|netty|PacketEvent)""", RegexOption.IGNORE_CASE).containsMatchIn(it) })
    check("matching reads memory only (the database is only the async audit log)", !manager.contains("store.log(") || Regex("""databaseManager\.async\("ranked queue audit"\) \{ store\.log\(""").containsMatchIn(manager))
}
