// Checks the staff TEST match safeguards of Ranked Team Wars (issue #1491, audit finding F-3): the
// staff confirmation (bound to the staff member and the exact plan, expiring, single use,
// invalidated by team/player changes), participant consent (every player, bound to the exact
// proposal, refusal/timeout/disconnect end it, created once), the permission + staff hierarchy +
// eligibility refusals, the consent warnings, that TEST matches stay unrated and keep the normal
// heart rules, the logging-only farming monitor for TEST matches against a real SQLite database,
// the audit wiring, and that player challenges and public matchmaking are unchanged. The Gradle
// build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-ranked-test-match-check.init.gradle.kts lifestealRankedTestMatchCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (clicking the buttons, real
// disconnects, teleports, inventory snapshots on a server) is NOT covered here; see
// docs/lifesteal-ranked-beta.md, section J.

import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.DefeatReason
import com.liam.joshymc.lifesteal.ranked.ParticipantStatus
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorRules
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorSettings
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorStore
import com.liam.joshymc.lifesteal.ranked.queue.Challenge
import com.liam.joshymc.lifesteal.ranked.queue.EntryKind
import com.liam.joshymc.lifesteal.ranked.queue.FirstInFirstOut
import com.liam.joshymc.lifesteal.ranked.queue.QueueEntry
import com.liam.joshymc.lifesteal.ranked.queue.QueueState
import com.liam.joshymc.lifesteal.ranked.queue.RankedQueueBook
import com.liam.joshymc.lifesteal.ranked.queue.TestMatchBook
import com.liam.joshymc.lifesteal.ranked.queue.TestMatchBook.AcceptResult
import com.liam.joshymc.lifesteal.ranked.queue.TestMatchBook.ConfirmResult
import com.liam.joshymc.lifesteal.ranked.queue.TestMatchPlan
import com.liam.joshymc.lifesteal.ranked.queue.TestMatchRules
import com.liam.joshymc.lifesteal.ranked.queue.TestProposalState
import com.liam.joshymc.lifesteal.ranked.rating.RatingRules
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.staff.StaffActor
import com.liam.joshymc.staff.StaffProtection
import com.liam.joshymc.staff.StaffRank
import com.liam.joshymc.staff.StaffTarget
import java.io.File
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

private val ADMIN = UUID(9L, 1L).toString()
private val OTHER_ADMIN = UUID(9L, 2L).toString()
private const val CONSENT = 60_000L
private const val CONFIRM = TestMatchRules.CONFIRM_MILLIS
private val LIMITS = HeartRules.Limits(10, 1, 20)

private fun p(team: Int, i: Int) = UUID(2000L + team, i.toLong())
private var ids = 0
private fun id(prefix: String) = "$prefix-" + (++ids).toString(16).padStart(10, '0')

private fun plan(size: Int = 2, a: List<UUID> = List(size) { p(1, it) }, b: List<UUID> = List(size) { p(2, it) }, arena: String? = null, teamB: String = "tb") =
    TestMatchPlan("ta", "Alpha", teamB, "Bravo", size, arena, a, b)

/** A book with one confirmed proposal for [plan]. */
private fun confirmed(book: TestMatchBook, plan: TestMatchPlan = plan(), now: Long = 0): com.liam.joshymc.lifesteal.ranked.queue.TestProposal {
    val c = book.request(ADMIN, plan, id("rx"), now, CONFIRM)
    val r = book.confirm(ADMIN, "Admin", c.id, plan, id("rt"), now + 1, CONSENT)
    return (r as ConfirmResult.Confirmed).proposal
}

fun main() {
    authorization()
    confirmation()
    consent()
    rules()
    hearts()
    monitoring()
    queueUnchanged()
    wiring()
    println("Lifesteal ranked test match checks passed ($checks checks).")
}

// 1, 15, 16: permission, hierarchy and eligibility.
private fun authorization() {
    val noProblem: (UUID) -> String? = { null }
    val name: (UUID) -> String = { it.toString().takeLast(4) }
    check("1. staff without the ranked admin node are refused", TestMatchRules.refusals(plan(), false, noProblem, noProblem, name).single().contains("permission"))
    check("an authorized staff member with eligible players is allowed", TestMatchRules.refusals(plan(), true, noProblem, noProblem, name).isEmpty())
    val protected = p(2, 1)
    val refusals = TestMatchRules.refusals(plan(), true, { if (it == protected) StaffProtection.Decision.HIGHER_RANK.message else null }, noProblem, name)
    check("15. a higher-ranked staff participant refuses the test match", refusals.size == 1 && refusals[0].contains("ranked above you"))
    check("15. the hierarchy rule refuses self, equal and higher staff, allows console and lower staff",
        StaffProtection.decide(StaffActor.Staff(StaffRank.ADMIN), StaffTarget(null, self = true)) == StaffProtection.Decision.SELF &&
            StaffProtection.decide(StaffActor.Staff(StaffRank.ADMIN), StaffTarget(StaffRank.ADMIN, false)) == StaffProtection.Decision.EQUAL_RANK &&
            StaffProtection.decide(StaffActor.Staff(StaffRank.ADMIN), StaffTarget(StaffRank.OWNER, false)) == StaffProtection.Decision.HIGHER_RANK &&
            StaffProtection.decide(StaffActor.Staff(StaffRank.ADMIN), StaffTarget(StaffRank.HELPER, false)).allowed &&
            StaffProtection.decide(StaffActor.Console, StaffTarget(StaffRank.OWNER, false)).allowed)
    check("consent never replaces permission: a refused staff member gets only the permission refusal", TestMatchRules.refusals(plan(), false, { "x" }, { "y" }, name).size == 1)
    val tagged = TestMatchRules.refusals(plan(), true, noProblem, { if (it == p(1, 0)) "is combat-tagged" else null }, name)
    check("16. an ineligible participant refuses the test match", tagged.size == 1 && tagged[0].contains("combat-tagged"))
    val facts = RankedMatchRules.ParticipantFacts(
        uuid = p(1, 0), name = "P", side = Side.A, member = true, online = true, inLifesteal = true, sessionLoaded = true, hearts = 10,
        inOtherMatch = false, pendingRestore = false, transferring = false, combatTagged = false, dead = false, survivalMode = true,
    )
    val range = LIMITS.minimum..LIMITS.maximum
    check("16. the normal ranked eligibility still applies",
        RankedMatchRules.participantProblem(facts, range) == null &&
            RankedMatchRules.participantProblem(facts.copy(combatTagged = true), range) != null &&
            RankedMatchRules.participantProblem(facts.copy(inOtherMatch = true), range) != null &&
            RankedMatchRules.participantProblem(facts.copy(pendingRestore = true), range) != null &&
            RankedMatchRules.participantProblem(facts.copy(online = false), range) != null &&
            RankedMatchRules.participantProblem(facts.copy(member = false), range) != null)
    check("a short team is refused (team-size validation)", TestMatchRules.refusals(plan(a = listOf(p(1, 0))), true, noProblem, noProblem, name).any { it.contains("1 of 2") })
    check("a team can't play itself", TestMatchRules.refusals(plan(teamB = "ta"), true, noProblem, noProblem, name).any { it.contains("itself") })
}

// 2-5, 10, 24: the staff confirmation.
private fun confirmation() {
    val book = TestMatchBook()
    val c = book.request(ADMIN, plan(), id("rx"), 0, CONFIRM)
    check("2. requesting only stores a confirmation: no player is asked yet", book.allProposals().isEmpty() && plan().participants.all { book.proposalOf(it) == null })
    check("tokens look like queue tokens", TestMatchBook.validToken(c.id) && !TestMatchBook.isProposalToken(c.id))
    check("10. another staff member can't confirm it", book.confirm(OTHER_ADMIN, "Other", c.id, plan(), id("rt"), 1, CONSENT) == ConfirmResult.NotYours)
    check("console can't confirm a player's confirmation", book.confirm(TestMatchRules.CONSOLE_ACTOR, "CONSOLE", c.id, plan(), id("rt"), 1, CONSENT) == ConfirmResult.NotYours)
    check("3. a confirmation expires", book.confirm(ADMIN, "Admin", c.id, plan(), id("rt"), CONFIRM, CONSENT) == ConfirmResult.Expired)
    check("4. an expired confirmation is spent", book.confirm(ADMIN, "Admin", c.id, plan(), id("rt"), 1, CONSENT) == ConfirmResult.NotFound)

    val c2 = book.request(ADMIN, plan(), id("rx"), 100, CONFIRM)
    val r = book.confirm(ADMIN, "Admin", c2.id, plan(), id("rt"), 200, CONSENT)
    check("a valid confirmation creates one proposal", r is ConfirmResult.Confirmed && book.allProposals().size == 1)
    check("4. a confirmation can't be reused", book.confirm(ADMIN, "Admin", c2.id, plan(), id("rt"), 201, CONSENT) == ConfirmResult.NotFound && book.allProposals().size == 1)
    check("participants of an open proposal can't be put in another", run {
        val c3 = book.request(ADMIN, plan(), id("rx"), 300, CONFIRM)
        book.confirm(ADMIN, "Admin", c3.id, plan(), id("rt"), 301, CONSENT) is ConfirmResult.Busy
    })

    val fresh = TestMatchBook()
    val c4 = fresh.request(ADMIN, plan(), id("rx"), 0, CONFIRM)
    check("5. a different player online invalidates it", fresh.confirm(ADMIN, "Admin", c4.id, plan(b = listOf(p(2, 0), p(2, 7))), id("rt"), 1, CONSENT) == ConfirmResult.Changed)
    check("5. a changed confirmation is spent", fresh.confirm(ADMIN, "Admin", c4.id, plan(), id("rt"), 2, CONSENT) == ConfirmResult.NotFound)
    val c5 = fresh.request(ADMIN, plan(), id("rx"), 0, CONFIRM)
    check("5. a team that no longer exists invalidates it", fresh.confirm(ADMIN, "Admin", c5.id, null, id("rt"), 1, CONSENT) == ConfirmResult.Changed)
    val c6 = fresh.request(ADMIN, plan(), id("rx"), 0, CONFIRM)
    check("5. a different arena or size or opponent invalidates it",
        !plan().sameAs(plan(arena = "pit")) && !plan().sameAs(plan(size = 3)) && !plan().sameAs(plan(teamB = "tc")) &&
            plan().sameAs(plan(a = listOf(p(1, 1), p(1, 0)))) &&
            fresh.confirm(ADMIN, "Admin", c6.id, plan(arena = "pit"), id("rt"), 1, CONSENT) == ConfirmResult.Changed)
    val older = fresh.request(ADMIN, plan(), id("rx"), 0, CONFIRM)
    val newer = fresh.request(ADMIN, plan(size = 3), id("rx"), 1, CONFIRM)
    check("a new request replaces the staff member's older confirmation (repeated commands can't stack)", fresh.confirmation(older.id) == null && fresh.confirmation(newer.id) != null)
}

// 6-10, 24: participant consent.
private fun consent() {
    val book = TestMatchBook()
    val prop = confirmed(book)
    val (a0, a1) = p(1, 0) to p(1, 1)
    val (b0, b1) = p(2, 0) to p(2, 1)
    check("10. accepting without the proposal token shows it again instead of accepting", book.accept(a0, null, 10) is AcceptResult.NeedsToken && prop.accepted.isEmpty())
    check("10. a stale or other token is refused", book.accept(a0, "rt-ffffffffff", 10) == AcceptResult.Stale && prop.accepted.isEmpty())
    check("a non-participant can't accept (leaders don't accept for their team)", book.accept(UUID(77L, 1L), prop.id, 10) == AcceptResult.Stale && book.accept(UUID(77L, 1L), null, 10) == AcceptResult.NothingToAccept && prop.accepted.isEmpty())
    check("each participant accepts for themselves", book.accept(a0, prop.id, 10) is AcceptResult.Accepted && prop.accepted == setOf(a0))
    check("accepting twice changes nothing", book.accept(a0, prop.id, 11) == AcceptResult.Already)
    book.accept(a1, prop.id, 12)
    book.accept(b0, prop.id, 13)
    check("6. the match can't start until every participant accepted", book.beginStart(prop.id, 14) == null && prop.state == TestProposalState.CONFIRMING)
    book.accept(b1, prop.id, 15)
    check("6. everyone accepted: it can start", book.beginStart(prop.id, 16)?.id == prop.id && prop.state == TestProposalState.STARTING)
    check("24. a repeated accept or start can't start it twice", book.beginStart(prop.id, 17) == null && book.accept(b1, prop.id, 17) == AcceptResult.Stale)
    check("started releases the players", book.started(prop.id) != null && prop.state == TestProposalState.STARTED && book.proposalOf(a0) == null)
    check("24. started can't happen twice", book.started(prop.id) == null)

    val declined = TestMatchBook()
    val d = confirmed(declined)
    declined.accept(p(1, 0), d.id, 1)
    check("7. one participant refusing ends it", declined.end(d.id, TestProposalState.DECLINED) != null && d.state == TestProposalState.DECLINED)
    check("7. a declined proposal can't start or be accepted", declined.beginStart(d.id, 2) == null && declined.accept(p(2, 0), d.id, 2) == AcceptResult.Stale && declined.proposalOf(p(1, 0)) == null)

    val timeout = TestMatchBook()
    val t = confirmed(timeout, now = 0)
    for (u in t.participants.drop(1)) timeout.accept(u, t.id, 5)
    check("8. accepting after the timeout is refused", timeout.accept(t.participants[0], t.id, 1 + CONSENT) == AcceptResult.Expired)
    check("8. it can't start once the time is up", timeout.beginStart(t.id, 1 + CONSENT) == null)
    val expired = timeout.expire(1 + CONSENT)
    check("8. one player timing out ends it", expired.map { it.id } == listOf(t.id) && t.state == TestProposalState.EXPIRED && timeout.proposalOf(t.participants[0]) == null)

    val quit = TestMatchBook()
    val q = confirmed(quit)
    check("9. a disconnected participant's proposal ends and nobody can accept it", quit.end(q.id, TestProposalState.CANCELLED) != null && quit.accept(p(1, 0), q.id, 2) == AcceptResult.Stale)
    check("consent can't be reused for a future match: a new proposal starts with nobody accepted", run {
        val again = confirmed(quit, now = 10)
        again.id != q.id && again.accepted.isEmpty() && quit.accept(p(1, 0), q.id, 11) == AcceptResult.Stale
    })
    check("restart/reload: everything ends", run {
        val b = TestMatchBook(); confirmed(b); b.request(ADMIN, plan(size = 3, a = List(3) { p(5, it) }, b = List(3) { p(6, it) }), id("rx"), 0, CONFIRM)
        b.clear().size == 1 && b.isEmpty
    })
    check("the books are capped", run {
        val b = TestMatchBook(maxProposals = 1)
        confirmed(b)
        val other = plan(a = listOf(p(7, 0), p(7, 1)), b = listOf(p(8, 0), p(8, 1)))
        val c = b.request(ADMIN, other, id("rx"), 0, CONFIRM)
        b.confirm(ADMIN, "Admin", c.id, other, id("rt"), 1, CONSENT) == ConfirmResult.Full
    })
}

// 11, 12: warnings and rating.
private fun rules() {
    val lines = TestMatchRules.consentLines(plan(), "Admin", LIMITS.minimum..LIMITS.maximum, 60)
    val all = lines.joinToString(" ")
    check("11. players are warned that permanent hearts are at risk", all.contains("PERMANENT HEARTS ARE AT RISK") && all.contains("1-20 hearts") && all.contains("last heart"))
    check("11. players are told TEST matches award no Elo", all.contains("unrated") && all.contains("no Elo"))
    check("11. players see both teams, the size and who set it up", all.contains("Alpha vs Bravo") && all.contains("2v2") && all.contains("Admin"))
    check("11. players are told everyone must accept and they may refuse", all.contains("accept for themselves") && all.contains("60s") && all.contains("say no"))
    val preview = TestMatchRules.previewLines(plan(), { "n" + it.leastSignificantBits }, 60).joinToString(" ")
    check("the staff preview shows teams, players, size and the heart risk", preview.contains("Alpha vs Bravo") && preview.contains("2v2") && preview.contains("n0") && preview.contains("hearts are at risk"))
    fun record(kind: MatchKind) = RankedStore.MatchRecord("rtw-0000000000aa", "season-1", "duel", "ta", "A", "tb", "B", 2, kind == MatchKind.TEST, State.COMPLETED, ADMIN, 0, 1, 2, Side.A, "ta", "WIN", null, "COMPLETE", kind, "rt-0000000001")
    val parts = listOf(p(1, 0), p(1, 1)).map { RankedStore.ParticipantRecord(it, Side.A, "ta", ParticipantStatus.ACTIVE, null, false) } +
        listOf(p(2, 0), p(2, 1)).map { RankedStore.ParticipantRecord(it, Side.B, "tb", ParticipantStatus.DEFEATED, null, false) }
    check("12. TEST matches stay unrated (even with rate-challenges on)", RatingRules.decide(record(MatchKind.TEST), parts, true, 0, 0) is RatingRules.Verdict.Unrated)
    check("12. a public match with the same record is still rated", RatingRules.decide(record(MatchKind.PUBLIC), parts, false, 0, 0) is RatingRules.Verdict.Rated)
}

private typealias State = MatchState

// 13, 14: normal heart transfers and cooldowns (the heart service doesn't know about match kinds).
private fun hearts() {
    val dir = Files.createTempDirectory("ranked-test-hearts").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val hs = HeartStore(db)
            hs.createTables()
            val killer = p(1, 0)
            val victim = p(2, 0)
            hs.ensure(killer, 10, 1)
            hs.ensure(victim, 10, 1)
            val cooldown = 30 * 60_000L
            val first = hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$victim:1", killer, victim, 1_000, cooldown, LIMITS))
            check("13. a TEST match kill moves a heart like any PvP kill", (first as HeartStore.PvpResult.Done).decision is HeartRules.Decision.Transfer && hs.load(victim)?.hearts == 9 && hs.load(killer)?.hearts == 11)
            val second = hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$victim:2", killer, victim, 2_000, cooldown, LIMITS))
            check("14. the heart cooldown still applies", (second as HeartStore.PvpResult.Done).decision is HeartRules.Decision.Cooldown && hs.load(victim)?.hearts == 9)
            check("14. the same death is never processed twice", hs.transferPvp(HeartStore.PvpRequest("pvp:boot:$victim:1", killer, victim, 9_000_000, cooldown, LIMITS)) == HeartStore.PvpResult.Duplicate)
            check("the final heart can't be lost", HeartRules.decide(LIMITS.minimum, 10, 0, LIMITS) == HeartRules.Decision.VictimAtMinimum)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
    val heartManager = source("lifesteal/hearts/LifestealHeartManager.kt")
    check("13. the heart service has no TEST exception (hearts stay at risk)", !heartManager.contains("MatchKind.TEST") && !Regex("""\.test\b""").containsMatchIn(heartManager))
}

// 17-19: TEST matches in the logging-only monitor.
private fun monitoring() {
    val dir = Files.createTempDirectory("ranked-test-monitor").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val s = RankedStore(db)
            s.createTables()
            val hs = HeartStore(db)
            hs.createTables()
            val ms = RankedMonitorStore(db)
            ms.createTables()
            val a = listOf(p(1, 0), p(1, 1))
            val b = listOf(p(2, 0), p(2, 1))
            for (u in a + b) hs.ensure(u, 10, 1)
            var n = 0
            fun snap(u: UUID) = RankedStore.SnapshotData(u, MutableList<String?>(41) { null }, 0, "lifesteal", Point(0.5, 64.0, 0.5))
            fun play(at: Long, kind: MatchKind): String {
                val id = "rtw-%012d".format(++n)
                s.insertMatch(
                    RankedStore.NewMatch(id, "season-1", "duel", "ta", "Alpha", "tb", "Bravo", 2, kind == MatchKind.TEST, if (kind == MatchKind.TEST) ADMIN else "queue:x", at, kind, null),
                    a.map { MatchParticipant(it, Side.A, "ta") } + b.map { MatchParticipant(it, Side.B, "tb") },
                    (a + b).map { snap(it) },
                )
                s.moveState(id, MatchState.PREPARING, MatchState.COUNTDOWN, at + 1)
                s.moveState(id, MatchState.COUNTDOWN, MatchState.ACTIVE, at + 2)
                for (u in b) s.defeat(id, u, DefeatReason.DEATH, at + 3)
                s.finish(id, Side.A, "ta", at + 4)
                s.complete(id, at + 5)
                for (u in a + b) s.markRestored(s.snapshotId(id, u), "check", at + 6)
                return id
            }
            val settings = RankedMonitorSettings.DEFAULTS
            fun facts(id: String, test: Boolean, transfers: List<Pair<UUID, UUID>> = emptyList()) =
                RankedMonitorRules.MatchFacts(id, "ta", "Alpha", "tb", "Bravo", a, b, emptyList(), transfers, test, if (test) "Admin ($ADMIN)" else null)
            val base = 10_000_000L
            var last = ""
            for (i in 0 until 3) last = play(base + i * 1000L, MatchKind.TEST)
            val before = RankedMonitorRules.evaluate(facts(last, true), ms.counts(facts(last, true), settings, base + 10_000), settings)
            check("17. three TEST matches stay below the default threshold", before.none { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP })
            last = play(base + 4000L, MatchKind.TEST)
            val f = facts(last, true)
            val findings = RankedMonitorRules.evaluate(f, ms.counts(f, settings, base + 10_000), settings)
            val team = findings.firstOrNull { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP }
            check("17. repeated TEST matches between the same teams are found", team != null && team.reason.contains("4 staff TEST matches"))
            check("17. the finding names the staff member who started it", team!!.initiator == "Admin ($ADMIN)" && team.reason.contains("started by Admin"))
            check("17. TEST findings are kept apart from public ones", team.subject.startsWith(RankedMonitorRules.TEST_SUBJECT_PREFIX))
            check("17. TEST matches don't inflate the public counts", ms.teamMatchCount("ta", "tb", 0) == 0 && ms.teamMatchCount("ta", "tb", 0, test = true) == 4)
            val pub = play(base + 5000L, MatchKind.PUBLIC)
            check("a public match still counts only public matches", RankedMonitorRules.evaluate(facts(pub, false), ms.counts(facts(pub, false), settings, base + 10_000), settings).none { it.kind == RankedMonitorRules.Kind.REPEATED_TEAM_MATCHUP })
            // Repeated heart transfers inside TEST matches.
            for (i in 0 until 3) hs.transferPvp(HeartStore.PvpRequest("pvp:boot:${b[0]}:${100 + i}", a[0], b[0], base + 20_000 + i * 31 * 60_000L, 30 * 60_000L, LIMITS))
            val tf = facts(last, true, listOf(a[0] to b[0]))
            val transfers = RankedMonitorRules.evaluate(tf, ms.counts(tf, settings, base + 20_000 + 3 * 31 * 60_000L), settings)
            check("17. suspicious repeated heart transfers in TEST matches are found", transfers.any { it.kind == RankedMonitorRules.Kind.REPEATED_HEART_TRANSFERS && it.initiator != null })
            check("18. a suspicious TEST match produces a review record", ms.record(team, last, base + 10_000, settings.windowMillis) && ms.reviews(a[0]).any { it.subject.startsWith("test:") && it.matchId == last })
            check("18. it is recorded once", !ms.record(team, last, base + 10_001, settings.windowMillis))
            val heartsSum = db.queryFirst("SELECT SUM(hearts) AS s FROM lifesteal_hearts") { it.getInt("s") }
            check("19. monitoring never punishes or changes hearts", heartsSum == 40 && db.queryFirst("SELECT 1 AS hit FROM sqlite_master WHERE type = 'table' AND name = 'punishments'") { true } == null)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// 21, 22: challenges and public matchmaking are unchanged.
private fun queueUnchanged() {
    val book = RankedQueueBook()
    fun entry(team: Int, kind: EntryKind = EntryKind.PUBLIC, challenge: String? = null) =
        QueueEntry(id("rq"), kind, "t$team", "T$team", 2, listOf(p(team, 0), p(team, 1)), p(team, 0), 0, "season-1", challenge)
    val e1 = entry(1)
    val e2 = entry(2)
    check("22. public entries still register and wait", book.register(e1, e1.participants, 0) == null && book.register(e2, e2.participants, 0) == null && e1.state == QueueState.WAITING)
    val pair = FirstInFirstOut.select(book.waiting(2), book::compatible)
    val prop = pair?.let { book.propose(it.first, it.second, "duel", id("rp"), 1, 30_000) }
    check("22. matchmaking still pairs and proposes", prop != null && prop.participants.size == 4)
    for (u in prop!!.participants) book.accept(u, prop.id, 2)
    check("22. a found match still starts once", book.beginStart(prop.id, 3) != null && book.beginStart(prop.id, 4) == null)
    val cid = id("rc")
    val from = entry(3, EntryKind.CHALLENGE, cid)
    val c = Challenge(cid, "t3", "T3", "t4", "T4", 2, from.id, 0, 120_000)
    check("21. a team challenge still registers", book.challenge(c, from, from.participants, 0) == null)
    val to = entry(4, EntryKind.CHALLENGE, cid)
    check("21. it can still be accepted and becomes ready", book.acceptChallenge(cid, to, to.participants, 1) == null && book.readyChallenges().size == 1)
    check("queue tokens are still valid, and test proposal tokens are accepted by /ranked", RankedQueueBook.validToken("rq-0123456789") && RankedQueueBook.validToken("rt-0123456789") && !RankedQueueBook.validToken("rx-0123456789"))
}

// 2, 20, 23 + wiring (source).
private fun wiring() {
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val command = source("lifesteal/ranked/LifestealRankedCommand.kt")
    val service = source("lifesteal/ranked/queue/RankedTestMatchService.kt")
    val queue = source("lifesteal/ranked/queue/LifestealRankedQueueManager.kt")
    val ranked = source("lifesteal/ranked/queue/RankedCommand.kt")
    val monitor = source("lifesteal/ranked/monitor/RankedFarmingMonitor.kt")
    check("2. the old direct test match path is gone", !manager.contains("fun createMatch(") && !command.contains("createMatch(") && !command.contains("CreateResult"))
    check("2. the command only requests and confirms", command.contains("tests.request(sender, teamA, teamB, size, a.getOrNull(3))") && command.contains("return tests.confirm(sender, a[1])"))
    check("TEST matches are created only by the service, after beginStart", Regex("createTestMatch\\(").findAll(service).count() == 1 &&
        service.indexOf("book.beginStart(") in 0 until service.indexOf("ranked.createTestMatch(") &&
        listOf("queue/LifestealRankedQueueManager.kt", "LifestealRankedCommand.kt", "LifestealRankedListener.kt").none { source("lifesteal/ranked/$it").contains("createTestMatch(") })
    check("createTestMatch runs the normal validation/snapshot path with exactly the accepted players",
        Regex("""fun createTestMatch\([\s\S]*?return startMatch\([\s\S]*?MatchKind\.TEST, proposalId, plan\.sideA, plan\.sideB""").containsMatchIn(manager) &&
            manager.contains("RankedMatchRules.validate(request)") && manager.contains("players.map { capture(it) }"))
    check("queued matches still can't be TEST", manager.contains("require(kind != MatchKind.TEST)"))
    check("15. every participant goes through the staff hierarchy rule", service.contains("plugin.staffRankManager.protectionProblem(sender, uuid)"))
    check("8. eligibility, hierarchy and the staff member are re-checked right before the match is created",
        service.indexOf("problems(staff, p.plan, p.id)") in 0 until service.indexOf("ranked.createTestMatch(") && service.contains("senderOf(p.actor)"))
    check("9. a disconnect ends the proposal", queue.contains("tests.onQuit(player)") && service.contains("\"\${player.name} disconnected\""))
    check("team changes, other matches and queues end a proposal on the next tick", service.contains("changed teams") && service.contains("is in another ranked match") && service.contains("joined a ranked queue"))
    check("test proposals keep running while matchmaking is closed to players", queue.substringAfter("private fun tick()").let { t -> t.indexOf("tests.tick(now") in 0 until t.indexOf("if (book.isEmpty) return") && t.indexOf("if (book.isEmpty) return") < t.indexOf("cancelAll(\"ranked matchmaking became unavailable\")") })
    check("/ranked accept|decline route test proposals first", queue.contains("if (tests.handles(player.uniqueId, token)) return tests.accept(player, token)") && queue.contains("if (tests.handles(player.uniqueId, token)) return tests.decline(player, token)"))
    check("players asked to a test match can answer while /ranked is closed", ranked.contains("answeringTest") && ranked.contains("queue.tests.busy(player.uniqueId)"))
    check("queue rosters refuse players asked to a test match", queue.contains("tests.busy(uuid) -> "))
    for (action in listOf("RANKED_TESTMATCH_REQUESTED", "RANKED_TESTMATCH_CONFIRMED", "RANKED_TESTMATCH_ACCEPTED", "RANKED_TESTMATCH_DECLINED", "RANKED_TESTMATCH_EXPIRED", "RANKED_TESTMATCH_CANCELLED", "RANKED_TESTMATCH_CREATED")) {
        check("20. $action is audited", service.contains("\"$action\""))
    }
    check("20. staff actions go through AdminAudit", Regex("AdminAudit\\.log\\(").findAll(service).count() >= 3)
    check("20. completion and cancellation of a TEST match are audited with the heart outcomes",
        manager.contains("if (match.test) auditTestEnd(match, \"RANKED_TESTMATCH_COMPLETED\"") && manager.contains("if (match.test) auditTestEnd(match, \"RANKED_TESTMATCH_MATCH_CANCELLED\"") &&
            manager.contains("hearts_moved=") && manager.contains("initiator=\${match.createdBy}"))
    check("17. the monitor no longer skips TEST matches", !monitor.contains("|| test) return") && manager.contains("test = match.test,") && manager.contains("monitor.onMatchEnded(facts, names)"))
    check("19. the monitor stays logging only", !monitor.contains("punishmentManager") && !monitor.contains("transferPvp") && !monitor.contains("commsManager"))
    val book = source("lifesteal/ranked/queue/RankedTestMatches.kt")
    check("23. the test match flow never touches inventories, snapshots or hearts itself",
        listOf(service, book).all { src -> listOf("restoreIfPending", "capture(", "Snapshot(", ".inventory", "transferPvp", "adminChange", "setItem").none { src.contains(it) } })
    check("23. inventory restoration is unchanged: every match still snapshots first and restores when it ends",
        manager.contains("restoreIfPending(player, \"match \${match.state.name.lowercase()}\")") &&
            manager.indexOf("players.map { capture(it) }") in 0 until manager.indexOf("return CreateResult.Started(match)"))
}
