// Checks the Ranked Team Wars Player Elo and Team Elo (issue #1365): the expected-score formula,
// rounding, bounds, invalid inputs, wins/losses/draws, average-based Player Elo for 2v2-5v5, Team
// Elo from the teams' own ratings, match eligibility (cancelled, test, challenge, invalid records,
// same-opponent limit), and against a real SQLite database: initial ratings, one atomic finalize per
// match (duplicates, simulated database failure mid-transaction with rollback and retry, restart),
// rating history, the protection triggers, season isolation, team membership changes, disbanded and
// renamed teams, offline and Bedrock players, result notices, lifesteal.yml settings and the wiring
// (Lifesteal only, rated after COMPLETED, no rating writes elsewhere). The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-ranked-elo-check.init.gradle.kts lifestealRankedEloCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (chat output, the menu on Java
// and Bedrock, real matches) is NOT covered here; see docs/lifesteal-ranked-elo.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.Point
import com.liam.joshymc.lifesteal.ranked.RankedArena
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.ranked.rating.EloRating
import com.liam.joshymc.lifesteal.ranked.rating.RatingOutcome
import com.liam.joshymc.lifesteal.ranked.rating.RatingRules
import com.liam.joshymc.lifesteal.ranked.rating.RatingStore
import com.liam.joshymc.lifesteal.ranked.rating.RatingStore.Finalized
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.SqliteDatabase
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.sql.ResultSet
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.abs
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun throws(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }
private fun close(a: Double, b: Double) = abs(a - b) < 1e-9
private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private val P = EloRating.Params(initialPlayer = 1000, initialTeam = 1000, minimum = 0, maximum = 5000, kFactor = 32)
private val POLICY = RatingStore.Policy(P, rateChallenges = false, sameOpponentLimit = 0, sameOpponentWindowMillis = 24 * 3_600_000L)

/** Floodgate (Bedrock) ids are version-0 UUIDs built from the XUID; Java ids are version 4. */
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f4ac2d1e3b")
private val JAVA = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")

private fun p(team: Int, i: Int) = UUID(5000L + team, i.toLong())
private fun players(team: Int, n: Int) = List(n) { p(team, it) }

fun main() {
    formula()
    teamMatches()
    rules()
    config()
    database()
    rosterRule()
    antiFarming()
    wiring()
    println("Ranked Elo checks passed ($checks checks).")
}

// ── Formula ──────────────────────────────────────────────────

private fun formula() {
    check("expected: equal ratings = 0.5", close(EloRating.expected(1000.0, 1000.0), 0.5))
    check("expected: +100 = 0.6400649998", abs(EloRating.expected(1100.0, 1000.0) - 0.6400649998028851) < 1e-12)
    check("expected: +400 = 10/11", close(EloRating.expected(1400.0, 1000.0), 10.0 / 11.0))
    check("expected: both sides add up to 1", close(EloRating.expected(1234.0, 987.0) + EloRating.expected(987.0, 1234.0), 1.0))
    check("expected: extreme gaps stay finite inside 0..1", EloRating.expected(100_000.0, 0.0) in 0.0..1.0 && EloRating.expected(0.0, 100_000.0) in 0.0..1.0 && EloRating.expected(0.0, 5000.0) > 0.0)
    check("extreme gap: the favourite's win is worth 0, the underdog's 32", EloRating.apply(5000, 5000.0, 0.0, RatingOutcome.WIN, P).delta == 0 && EloRating.apply(0, 0.0, 5000.0, RatingOutcome.WIN, P).delta == 32)
    check("expected: NaN refused", throws { EloRating.expected(Double.NaN, 1000.0) })
    check("expected: Infinity refused", throws { EloRating.expected(1000.0, Double.POSITIVE_INFINITY) })

    check("rounding: 15.5 -> 16", EloRating.roundHalfAway(15.5) == 16)
    check("rounding: -15.5 -> -16 (symmetric)", EloRating.roundHalfAway(-15.5) == -16)
    check("rounding: 11.518 -> 12, -11.518 -> -12", EloRating.roundHalfAway(11.518) == 12 && EloRating.roundHalfAway(-11.518) == -12)
    check("rounding: 0.4999 -> 0", EloRating.roundHalfAway(0.4999) == 0 && EloRating.roundHalfAway(-0.4999) == 0)
    check("rounding: NaN refused", throws { EloRating.roundHalfAway(Double.NaN) })

    val win = EloRating.apply(1000, 1000.0, 1000.0, RatingOutcome.WIN, P)
    val loss = EloRating.apply(1000, 1000.0, 1000.0, RatingOutcome.LOSS, P)
    check("equal win: +16 -> 1016", win.delta == 16 && win.after == 1016 && win.before == 1000 && close(win.expected, 0.5) && win.kFactor == 32)
    check("equal loss: -16 -> 984", loss.delta == -16 && loss.after == 984)
    check("favourite win: +12", EloRating.apply(1100, 1100.0, 1000.0, RatingOutcome.WIN, P).delta == 12)
    check("favourite loss: -20", EloRating.apply(1100, 1100.0, 1000.0, RatingOutcome.LOSS, P).delta == -20)
    check("underdog win: +20", EloRating.apply(1000, 1000.0, 1100.0, RatingOutcome.WIN, P).delta == 20)
    check("draw between equals: 0", EloRating.apply(1000, 1000.0, 1000.0, RatingOutcome.DRAW, P).delta == 0)
    check("draw as the favourite: -4", EloRating.apply(1100, 1100.0, 1000.0, RatingOutcome.DRAW, P).delta == -4)
    check("draw as the underdog: +4", EloRating.apply(1000, 1000.0, 1100.0, RatingOutcome.DRAW, P).delta == 4)
    check("K factor scales the change", EloRating.apply(1000, 1000.0, 1000.0, RatingOutcome.WIN, P.copy(kFactor = 40)).delta == 20)

    val floor = EloRating.apply(5, 5.0, 5.0, RatingOutcome.LOSS, P)
    check("minimum bound: 5 - 16 stops at 0 and stores -5", floor.after == 0 && floor.delta == -5)
    val ceil = EloRating.apply(4990, 4990.0, 4990.0, RatingOutcome.WIN, P)
    check("maximum bound: 4990 + 16 stops at 5000 and stores +10", ceil.after == 5000 && ceil.delta == 10)
    check("a custom minimum is respected", EloRating.apply(110, 110.0, 110.0, RatingOutcome.LOSS, P.copy(minimum = 100)).after == 100)
    check("a stored rating outside 0..100000 is refused", throws { EloRating.apply(-1, 0.0, 0.0, RatingOutcome.WIN, P) } && throws { EloRating.apply(100_001, 0.0, 0.0, RatingOutcome.WIN, P) })
    check("deterministic: the same inputs give the same change", (1..20).map { EloRating.apply(1337, 1290.5, 1402.25, RatingOutcome.WIN, P) }.toSet().size == 1)

    check("params: minimum must be below maximum", throws { EloRating.Params(1000, 1000, 5000, 5000, 32) })
    check("params: initial rating inside the bounds", throws { EloRating.Params(6000, 1000, 0, 5000, 32) } && throws { EloRating.Params(1000, -1, 0, 5000, 32) })
    check("params: K 1..200", throws { EloRating.Params(1000, 1000, 0, 5000, 0) } && throws { EloRating.Params(1000, 1000, 0, 5000, 201) })
    check("params: maximum <= 100000 (no overflow)", throws { EloRating.Params(1000, 1000, 0, 200_000, 32) })
    check("params: initial rating per type", P.initial(RatingType.PLAYER_ELO) == 1000 && EloRating.Params(1200, 900, 0, 5000, 32).initial(RatingType.TEAM_ELO) == 900)
}

// ── Team matches ─────────────────────────────────────────────

private fun teamMatches() {
    // The issue's example: 1000/1100/1200 (avg 1100) vs 900/1000/1100 (avg 1000).
    val a3 = mapOf(p(1, 0) to 1000, p(1, 1) to 1100, p(1, 2) to 1200)
    val b3 = mapOf(p(2, 0) to 900, p(2, 1) to 1000, p(2, 2) to 1100)
    val r3 = EloRating.players(a3, b3, RatingOutcome.WIN, P)
    check("3v3: averages 1100 vs 1000", r3.values.filter { it.outcome == RatingOutcome.WIN }.all { close(it.ownStrength, 1100.0) && close(it.opponentStrength, 1000.0) })
    check("3v3: every winner +12 from their own rating", a3.all { (u, r) -> r3[u]!!.delta == 12 && r3[u]!!.after == r + 12 })
    check("3v3: every loser -12 from their own rating", b3.all { (u, r) -> r3[u]!!.delta == -12 && r3[u]!!.after == r - 12 })
    val upset = EloRating.players(a3, b3, RatingOutcome.LOSS, P)
    check("3v3 upset: the favourites lose 20, the underdogs gain 20", a3.keys.all { upset[it]!!.delta == -20 } && b3.keys.all { upset[it]!!.delta == 20 })

    val r2 = EloRating.players(mapOf(p(1, 0) to 1000, p(1, 1) to 1000), mapOf(p(2, 0) to 1000, p(2, 1) to 1000), RatingOutcome.WIN, P)
    check("2v2 equals: +16 / -16", r2.values.count { it.delta == 16 } == 2 && r2.values.count { it.delta == -16 } == 2)
    val r4 = EloRating.players(players(1, 4).associateWith { 1200 }, players(2, 4).associateWith { 1000 }, RatingOutcome.WIN, P)
    check("4v4: 1200 vs 1000 (E 0.7597): +8 / -8", r4.size == 8 && players(1, 4).all { r4[it]!!.delta == 8 } && players(2, 4).all { r4[it]!!.delta == -8 })
    val a5 = players(1, 5).zip(listOf(1000, 1050, 1100, 1150, 1200)).toMap()
    val r5 = EloRating.players(a5, players(2, 5).associateWith { 1000 }, RatingOutcome.WIN, P)
    check("5v5: average 1100 vs 1000: +12 / -12", r5.size == 10 && a5.keys.all { r5[it]!!.delta == 12 } && players(2, 5).all { r5[it]!!.delta == -12 })
    check("different averages change the result", EloRating.players(mapOf(p(1, 0) to 1300, p(1, 1) to 1300), mapOf(p(2, 0) to 1000, p(2, 1) to 1000), RatingOutcome.WIN, P)[p(1, 0)]!!.delta == 5)
    val clamp = EloRating.players(mapOf(p(1, 0) to 4995, p(1, 1) to 1000), mapOf(p(2, 0) to 3000, p(2, 1) to 3000), RatingOutcome.WIN, P)
    check("one side: same raw change, only the bounds clamp differs", clamp[p(1, 0)]!!.after == 5000 && clamp[p(1, 1)]!!.delta > 5)
    check("a player on both sides is refused", throws { EloRating.players(mapOf(p(1, 0) to 1000), mapOf(p(1, 0) to 1000), RatingOutcome.WIN, P) })
    check("an empty side is refused", throws { EloRating.players(emptyMap(), mapOf(p(2, 0) to 1000), RatingOutcome.WIN, P) })
    val mixed = EloRating.players(mapOf(BEDROCK to 1000, JAVA to 1000), mapOf(p(2, 0) to 1000, p(2, 1) to 1000), RatingOutcome.WIN, P)
    check("mixed Java + Bedrock (Floodgate) team: same rules", mixed[BEDROCK]!!.delta == 16 && mixed[JAVA]!!.delta == 16)

    val (ta, tb) = EloRating.teams(1000, 1000, RatingOutcome.WIN, P)
    check("Team Elo equals: +16 / -16", ta.delta == 16 && tb.delta == -16 && ta.after == 1016 && tb.after == 984)
    val (fa, fb) = EloRating.teams(1100, 1000, RatingOutcome.LOSS, P)
    check("Team Elo from the teams' own ratings (favourite loses 20)", fa.delta == -20 && fb.delta == 20 && close(fa.ownStrength, 1100.0))
}

// ── Eligibility ──────────────────────────────────────────────

private fun record(kind: MatchKind = MatchKind.PUBLIC, state: MatchState = MatchState.COMPLETED, outcome: String? = "WIN", winner: Side? = Side.A, winnerTeam: String? = "ta", season: String = "season-1", size: Int = 2, test: Boolean = false, teamB: String = "tb") =
    RankedStore.MatchRecord("rtw-0000000000aa", season, "duel", "ta", "A", teamB, "B", size, test, state, "queue:x", 0, 1, 2, winner, winnerTeam, outcome, null, "COMPLETE", kind, null)

private fun parts(size: Int = 2, teamA: String = "ta", teamB: String = "tb") =
    players(1, size).map { RankedStore.ParticipantRecord(it, Side.A, teamA, com.liam.joshymc.lifesteal.ranked.ParticipantStatus.ACTIVE, null, false) } +
        players(2, size).map { RankedStore.ParticipantRecord(it, Side.B, teamB, com.liam.joshymc.lifesteal.ranked.ParticipantStatus.DEFEATED, null, false) }

private fun rules() {
    val ok = RatingRules.decide(record(), parts(), rateChallenges = false, sameOpponentLimit = 0, ratedBetweenPair = 0)
    check("public matchmaking match: rated", ok is RatingRules.Verdict.Rated && ok.winner == Side.A && ok.sideA.size == 2)
    check("classification: PUBLIC = RANKED_MATCHMAKING, CHALLENGE = DIRECT_TEAM_CHALLENGE, TEST = ADMIN_TEST_MATCH",
        RatingRules.Classification.of(MatchKind.PUBLIC, false) == RatingRules.Classification.RANKED_MATCHMAKING &&
            RatingRules.Classification.of(MatchKind.CHALLENGE, false) == RatingRules.Classification.DIRECT_TEAM_CHALLENGE &&
            RatingRules.Classification.of(MatchKind.TEST, false) == RatingRules.Classification.ADMIN_TEST_MATCH &&
            RatingRules.Classification.of(MatchKind.PUBLIC, true) == RatingRules.Classification.ADMIN_TEST_MATCH)
    check("cancelled: unrated", RatingRules.decide(record(state = MatchState.CANCELLED, outcome = "CANCELLED", winner = null), parts(), false, 0, 0) is RatingRules.Verdict.Unrated)
    check("still running: not ready (nothing recorded)", RatingRules.decide(record(state = MatchState.ACTIVE, outcome = null), parts(), false, 0, 0) is RatingRules.Verdict.NotReady)
    check("finishing (not committed yet): not ready", RatingRules.decide(record(state = MatchState.FINISHING), parts(), false, 0, 0) is RatingRules.Verdict.NotReady)
    check("admin test match: unrated", RatingRules.decide(record(kind = MatchKind.TEST, test = true), parts(), true, 0, 0) is RatingRules.Verdict.Unrated)
    check("legacy test flag alone: unrated", RatingRules.decide(record(test = true), parts(), true, 0, 0) is RatingRules.Verdict.Unrated)
    check("direct challenge: unrated by default", RatingRules.decide(record(kind = MatchKind.CHALLENGE), parts(), false, 0, 0) is RatingRules.Verdict.Unrated)
    check("direct challenge: rated only when rate-challenges is on", RatingRules.decide(record(kind = MatchKind.CHALLENGE), parts(), true, 0, 0) is RatingRules.Verdict.Rated)
    check("no winner: invalid", RatingRules.decide(record(winner = null), parts(), false, 0, 0) is RatingRules.Verdict.Invalid)
    check("winner team doesn't match the side: invalid", RatingRules.decide(record(winnerTeam = "tb"), parts(), false, 0, 0) is RatingRules.Verdict.Invalid)
    check("completed without WIN outcome: invalid", RatingRules.decide(record(outcome = "CANCELLED"), parts(), false, 0, 0) is RatingRules.Verdict.Invalid)
    check("season 'none' (no active season): unrated", RatingRules.decide(record(season = "none"), parts(), false, 0, 0) is RatingRules.Verdict.Unrated)
    check("blank season: unrated", RatingRules.decide(record(season = ""), parts(), false, 0, 0) is RatingRules.Verdict.Unrated)
    check("same team on both sides: invalid", RatingRules.decide(record(teamB = "ta"), parts(teamB = "ta"), false, 0, 0) is RatingRules.Verdict.Invalid)
    check("missing participant: invalid", RatingRules.decide(record(), parts().drop(1), false, 0, 0) is RatingRules.Verdict.Invalid)
    check("participant for the wrong team: invalid", RatingRules.decide(record(), parts(teamA = "other"), false, 0, 0) is RatingRules.Verdict.Invalid)
    check("5v5 with 10 participants: rated", RatingRules.decide(record(size = 5), parts(5), false, 0, 0) is RatingRules.Verdict.Rated)
    check("same-opponent limit reached: unrated", RatingRules.decide(record(), parts(), false, 3, 3) is RatingRules.Verdict.Unrated)
    check("same-opponent limit not reached: rated", RatingRules.decide(record(), parts(), false, 3, 2) is RatingRules.Verdict.Rated)
    check("same-opponent limit 0 = off", RatingRules.decide(record(), parts(), false, 0, 99) is RatingRules.Verdict.Rated)
    check("no draw is ever produced from a match record (a rated match always has a winner and a loser)",
        source("lifesteal/ranked/rating/RatingStore.kt").contains("val outcomeA = if (v.winner == Side.A) RatingOutcome.WIN else RatingOutcome.LOSS"))
}

// ── lifesteal.yml ────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun config() {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    val map: Map<String, Any?> = Yaml().load(text)
    fun parse(m: Map<String, Any?>) = LifestealConfigParser.parse { path -> path.split('.').fold<String, Any?>(m) { node, key -> (node as? Map<*, *>)?.get(key) } }
    val bundled = parse(map)
    check("bundled lifesteal.yml has no problems", bundled.problems.isEmpty())
    val elo = bundled.settings!!.rankedTeamWars.elo
    check("Elo defaults: 1000/1000, 0-5000, K 32, challenges unrated, 3 per 24h", elo == LifestealSettings.RankedElo(1000, 1000, 0, 5000, 32, false, 3, 24))
    fun withElo(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
        val m = map.toMutableMap()
        val ranked = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        val section = (ranked["elo"] as Map<String, Any?>).toMutableMap()
        edit(section)
        ranked["elo"] = section
        m["ranked-team-wars"] = ranked
        return m
    }
    fun severe(edit: (MutableMap<String, Any?>) -> Unit) = parse(withElo(edit)).let { it.settings == null && it.problems.any { p -> p.severe } }
    check("minimum >= maximum is severe", severe { it["minimum-rating"] = 5000 })
    check("initial player rating outside the bounds is severe", severe { it["initial-player-rating"] = 6000 })
    check("initial team rating outside the bounds is severe", severe { it["initial-team-rating"] = -5 })
    check("K 0 is severe", severe { it["k-factor"] = 0 })
    check("K 'big' is severe", severe { it["k-factor"] = "big" })
    check("maximum above 100000 is severe", severe { it["maximum-rating"] = 1_000_000 })
    check("rate-challenges 'yes' is severe", severe { it["rate-challenges"] = "yes" })
    check("rate-challenges true is read", parse(withElo { it["rate-challenges"] = true }).settings?.rankedTeamWars?.elo?.rateChallenges == true)
    val old = map.toMutableMap()
    old["ranked-team-wars"] = (map["ranked-team-wars"] as Map<String, Any?>).toMutableMap().apply { remove("elo") }
    val parsedOld = parse(old)
    check("a lifesteal.yml from before #1365 loads with the Elo defaults and one warning", parsedOld.settings?.rankedTeamWars?.elo == elo && parsedOld.problems.size == 1 && parsedOld.problems.none { it.severe })
    check("the approved ranked/heart rules are untouched", bundled.settings!!.rankedTeamWars.teamSizes == listOf(2, 3, 4, 5) && bundled.settings!!.hearts.minimum == 1 && bundled.settings!!.hearts.maximum == 20)
    check("Params.of reads the settings", EloRating.Params.of(elo) == P)
}

// ── Database ─────────────────────────────────────────────────

/** Plays a whole match through the real match store: created, active, won by [winner], completed, restored. */
private fun play(
    s: RankedStore, season: String, teamA: Pair<String, String>, teamB: Pair<String, String>, a: List<UUID>, b: List<UUID>,
    winner: Side?, endedAt: Long, kind: MatchKind = MatchKind.PUBLIC, arena: String = "duel",
): String {
    val id = RankedMatchRules.newMatchId()
    val parts = a.map { MatchParticipant(it, Side.A, teamA.first) } + b.map { MatchParticipant(it, Side.B, teamB.first) }
    s.insertMatch(
        RankedStore.NewMatch(id, season, arena, teamA.first, teamA.second, teamB.first, teamB.second, a.size, kind == MatchKind.TEST, "check", endedAt - 1000, kind, null),
        parts, parts.map { RankedStore.SnapshotData(it.uuid, List(41) { null }, 0, "lifesteal", Point(0.0, 64.0, 0.0)) },
    )
    s.moveState(id, MatchState.PREPARING, MatchState.COUNTDOWN, endedAt - 900)
    s.moveState(id, MatchState.COUNTDOWN, MatchState.ACTIVE, endedAt - 800)
    if (winner == null) s.cancel(id, "both teams were out at the same time", endedAt)
    else {
        s.finish(id, winner, if (winner == Side.A) teamA.first else teamB.first, endedAt - 1)
        s.complete(id, endedAt)
    }
    for (u in a + b) s.markRestored(s.snapshotId(id, u), "check", endedAt)
    return id
}

private fun count(db: DatabaseManager, sql: String, vararg args: Any?): Int = db.queryFirst(sql, *args) { it.getInt(1) } ?: -1

/** A database that fails the [failAt]-th rating UPDATE (to prove a mid-transaction failure rolls everything back). */
private class FailingDb(private val real: DatabaseManager, private val failAt: Int) : SqliteDatabase {
    var updates = 0
    override fun execute(sql: String, vararg params: Any?) = real.execute(sql, *params)
    override fun executeUpdate(sql: String, vararg params: Any?): Int {
        if (sql.contains("UPDATE lifesteal_ranked_ratings SET rating") && ++updates == failAt) throw java.sql.SQLException("simulated disk error")
        return real.executeUpdate(sql, *params)
    }
    override fun <T> query(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): List<T> = real.query(sql, *params, mapper = mapper)
    override fun <T> queryFirst(sql: String, vararg params: Any?, mapper: (ResultSet) -> T): T? = real.queryFirst(sql, *params, mapper = mapper)
    override fun transaction(block: () -> Unit) = real.transaction(block)
    override fun createTable(sql: String) = real.createTable(sql)
}

private fun database() {
    val dir = Files.createTempDirectory("ranked-elo-check").toFile()
    val teamA = "a1a1a1a1-0000-4000-8000-000000000001" to "Alpha"
    val teamB = "b2b2b2b2-0000-4000-8000-000000000002" to "Bravo"
    val teamC = "c3c3c3c3-0000-4000-8000-000000000003" to "Charlie"
    lateinit var firstMatch: String
    try {
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val ranked = RankedStore(db)
                ranked.createTables()
                ranked.createArena(RankedArena("duel", "Duel", "lifesteal", null, enabled = true), 1)
                val ratings = RatingStore(db)
                ratings.createTables()
                ratings.createTables() // idempotent

                val a = listOf(BEDROCK, JAVA)
                val b = players(2, 2)
                check("initial: no rating row before a rated match", ratings.rating("season-1", RatingType.PLAYER_ELO, JAVA.toString()) == null && ratings.rating("season-1", RatingType.TEAM_ELO, teamA.first) == null)

                firstMatch = play(ranked, "season-1", teamA, teamB, a, b, Side.A, 10_000_000)
                check("before rating: listed as completed without a result", ratings.unprocessedCompleted(5, 50) == listOf(firstMatch))
                val r = ratings.finalize(firstMatch, POLICY, 10_000_100)
                check("rated", r is Finalized.Rated)
                r as Finalized.Rated
                check("winners start at 1000 and gain 16 (Bedrock + Java)", a.all { r.players[it]!!.before == 1000 && r.players[it]!!.after == 1016 })
                check("losers lose 16", b.all { r.players[it]!!.after == 984 })
                check("Team Elo 1016 / 984", r.teams[teamA.first]!!.after == 1016 && r.teams[teamB.first]!!.after == 984)
                check("stored Player Elo (offline players are rated the same: nothing needs them online)",
                    ratings.rating("season-1", RatingType.PLAYER_ELO, BEDROCK.toString())?.rating == 1016 && ratings.rating("season-1", RatingType.PLAYER_ELO, b[0].toString())?.rating == 984)
                val rowA = ratings.rating("season-1", RatingType.TEAM_ELO, teamA.first)!!
                check("stored Team Elo with counters and team name", rowA.rating == 1016 && rowA.wins == 1 && rowA.losses == 0 && rowA.matches == 1 && rowA.displayName == "Alpha" && rowA.lastMatchId == firstMatch)
                val hist = ratings.historyOfMatch(firstMatch)
                check("history: one row per player and team", hist.size == 6 && hist.count { it.type == RatingType.TEAM_ELO } == 2)
                val hJava = hist.first { it.subjectId == JAVA.toString() }
                check("history details: transaction id, season, previous/delta/new, outcome, expected, K, team, side",
                    hJava.transactionId == "elo:$firstMatch" && hJava.seasonId == "season-1" && hJava.previous == 1000 && hJava.delta == 16 && hJava.new == 1016 &&
                        hJava.outcome == RatingOutcome.WIN && close(hJava.expected, 0.5) && hJava.kFactor == 32 && hJava.teamId == teamA.first && hJava.side == Side.A && hJava.at == 10_000_100L)
                check("result row RATED", ratings.result(firstMatch)?.status == RatingStore.ResultStatus.RATED && ratings.result(firstMatch)?.transactionId == "elo:$firstMatch")
                check("no longer listed as unrated", ratings.unprocessedCompleted(5, 50).isEmpty())

                // Duplicate completion events.
                check("duplicate finalize: already processed", ratings.finalize(firstMatch, POLICY, 10_000_200) is Finalized.AlreadyProcessed)
                check("duplicate finalize: no second history row", count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history WHERE match_id = ?", firstMatch) == 6)
                check("duplicate finalize: ratings unchanged", ratings.rating("season-1", RatingType.PLAYER_ELO, JAVA.toString())?.rating == 1016 && ratings.rating("season-1", RatingType.PLAYER_ELO, JAVA.toString())?.matches == 1)

                // Protection triggers.
                check("trigger: a rating can't be overwritten directly", throws { db.execute("UPDATE lifesteal_ranked_ratings SET rating = 4999 WHERE subject_id = ?", JAVA.toString()) })
                check("trigger: ...not even with a forged last match id", throws { db.execute("UPDATE lifesteal_ranked_ratings SET rating = 4999, last_match_id = ? WHERE subject_id = ?", firstMatch, JAVA.toString()) })
                check("trigger: ratings can't be deleted", throws { db.execute("DELETE FROM lifesteal_ranked_ratings") })
                check("trigger: history can't be edited", throws { db.execute("UPDATE lifesteal_ranked_rating_history SET delta = 99 WHERE match_id = ?", firstMatch) })
                check("trigger: history can't be deleted", throws { db.execute("DELETE FROM lifesteal_ranked_rating_history") })
                check("trigger: the rating survived every attempt", ratings.rating("season-1", RatingType.PLAYER_ELO, JAVA.toString())?.rating == 1016)

                // Result notices (offline players see theirs on join, once).
                val notices = ratings.unnotified(JAVA, 5)
                check("notice waiting for the player", notices.size == 1 && notices[0].matchId == firstMatch)
                check("notice claimed once", ratings.claimNotice(notices[0].id) && !ratings.claimNotice(notices[0].id) && ratings.unnotified(JAVA, 5).isEmpty())
                ratings.unclaimNotice(notices[0].id)
                check("an unclaimed notice is shown next time", ratings.unnotified(JAVA, 5).size == 1)
                check("the team's change is found for the notice", ratings.teamChange(firstMatch, teamA.first)?.new == 1016)

                // Second match: uses the stored ratings, Bravo now the underdog.
                val second = play(ranked, "season-1", teamB, teamA, b, a, Side.A, 11_000_000)
                val r2 = ratings.finalize(second, POLICY, 11_000_100) as Finalized.Rated
                // Bravo (984) beats Alpha (1016): E = 1/(1+10^(32/400)) = 0.45398 -> +17.47 -> +17.
                check("second match: underdog team gains 17", r2.teams[teamB.first]!!.delta == 17 && r2.teams[teamB.first]!!.after == 1001 && r2.teams[teamA.first]!!.after == 999)
                check("second match: players use their stored ratings", b.all { r2.players[it]!!.before == 984 && r2.players[it]!!.after == 1001 } && r2.players[JAVA]!!.after == 999)
                val jRow = ratings.rating("season-1", RatingType.PLAYER_ELO, JAVA.toString())!!
                check("player counters: 1 win, 1 loss", jRow.wins == 1 && jRow.losses == 1 && jRow.matches == 2)
                check("history newest first", ratings.history(RatingType.PLAYER_ELO, JAVA.toString(), "season-1", 10).map { it.matchId } == listOf(second, firstMatch))

                // Team membership change: Java now plays for Charlie. Player Elo follows the player;
                // Charlie starts at the initial Team Elo; Alpha's Team Elo doesn't move.
                val alphaBefore = ratings.rating("season-1", RatingType.TEAM_ELO, teamA.first)!!.rating
                val third = play(ranked, "season-1", teamC, teamB, listOf(JAVA, p(3, 1)), b, Side.A, 12_000_000)
                val r3 = ratings.finalize(third, POLICY, 12_000_100) as Finalized.Rated
                check("membership change: the player's own Elo is kept", r3.players[JAVA]!!.before == 999)
                check("membership change: the new team starts at the initial Team Elo", r3.teams[teamC.first]!!.before == 1000)
                check("membership change: the old team's Elo is untouched", ratings.rating("season-1", RatingType.TEAM_ELO, teamA.first)!!.rating == alphaBefore)
                check("Team Elo isn't derived from members' Elo (members average 999.5, team starts at 1000)", r3.teams[teamC.first]!!.before == 1000 && close(r3.players[JAVA]!!.ownStrength, 999.5))

                // A disbanded team whose name is reused: the new team has a new id, so it starts fresh.
                val newAlpha = "d4d4d4d4-0000-4000-8000-000000000004" to "Alpha"
                val fourth = play(ranked, "season-1", newAlpha, teamC, players(4, 2), listOf(JAVA, p(3, 1)), Side.B, 13_000_000)
                val r4 = ratings.finalize(fourth, POLICY, 13_000_100) as Finalized.Rated
                check("reused name: new team id starts at the initial rating", r4.teams[newAlpha.first]!!.before == 1000)
                check("reused name: the disbanded team's history and rating stay", ratings.rating("season-1", RatingType.TEAM_ELO, teamA.first)!!.rating == alphaBefore && ratings.history(RatingType.TEAM_ELO, teamA.first, null, 10).size == 2)

                // Season isolation.
                val s2 = play(ranked, "season-2", teamA, teamB, a, b, Side.A, 14_000_000)
                val rs2 = ratings.finalize(s2, POLICY, 14_000_100) as Finalized.Rated
                check("season 2 starts from the initial ratings", rs2.players[JAVA]!!.before == 1000 && rs2.teams[teamA.first]!!.before == 1000)
                check("season 1 rows are unchanged by season 2", ratings.rating("season-1", RatingType.TEAM_ELO, teamA.first)!!.rating == alphaBefore && ratings.rating("season-2", RatingType.TEAM_ELO, teamA.first)!!.rating == 1016)
                check("per-season history", ratings.history(RatingType.PLAYER_ELO, JAVA.toString(), "season-2", 10).size == 1 && ratings.history(RatingType.PLAYER_ELO, JAVA.toString(), null, 10).size == 5)
                check("every season's row of a subject is listed", ratings.ratingsOf(RatingType.TEAM_ELO, teamA.first).map { it.seasonId } == listOf("season-1", "season-2"))

                // Unrated kinds and cancelled matches change nothing.
                fun unchanged(id: String, label: String) {
                    val before = count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history")
                    val rr = ratings.finalize(id, POLICY, 20_000_000)
                    check("$label: unrated", rr is Finalized.Unrated)
                    check("$label: no history", count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history") == before && count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history WHERE match_id = ?", id) == 0)
                    check("$label: recorded UNRATED once", ratings.result(id)?.status == RatingStore.ResultStatus.UNRATED && ratings.finalize(id, POLICY.copy(rateChallenges = true), 20_000_001) is Finalized.AlreadyProcessed)
                }
                unchanged(play(ranked, "season-1", teamA, teamB, a, b, Side.A, 15_000_000, MatchKind.TEST), "admin test match")
                unchanged(play(ranked, "season-1", teamA, teamB, a, b, Side.B, 15_100_000, MatchKind.CHALLENGE), "direct challenge")
                unchanged(play(ranked, "season-1", teamA, teamB, a, b, null, 15_200_000), "cancelled match")
                val test = (ratings.finalize(play(ranked, "season-1", teamA, teamB, a, b, Side.A, 15_300_000, MatchKind.TEST), POLICY, 1) as Finalized.Unrated)
                check("test matches are flagged so nobody is told about Elo", test.test)
                val rated = play(ranked, "season-1", teamA, teamB, a, b, Side.B, 15_400_000, MatchKind.CHALLENGE)
                check("direct challenge with rate-challenges on: rated", ratings.finalize(rated, POLICY.copy(rateChallenges = true), 15_400_100) is Finalized.Rated)
                check("cancelled matches are never listed for rating", ratings.unprocessedCompleted(5, 50).isEmpty())

                // Same-opponent limit (rematch farming): 2 rated per window between Charlie and Bravo
                // (one already played above), the third is unrated, a later one after the window is rated.
                val limit = POLICY.copy(sameOpponentLimit = 2, sameOpponentWindowMillis = 3_600_000L)
                val m1 = play(ranked, "season-1", teamC, teamB, listOf(p(3, 2), p(3, 3)), players(2, 2), Side.A, 12_500_000)
                check("rematch 2 inside the window: rated", ratings.finalize(m1, limit, 12_500_100) is Finalized.Rated)
                val m2 = play(ranked, "season-1", teamB, teamC, players(2, 2), listOf(p(3, 2), p(3, 3)), Side.A, 12_600_000)
                val m2r = ratings.finalize(m2, limit, 12_600_100)
                check("rematch 3 inside the window (either side order): unrated", m2r is Finalized.Unrated && (m2r as Finalized.Unrated).reason.contains("same-opponent"))
                val m3 = play(ranked, "season-1", teamC, teamB, listOf(p(3, 2), p(3, 3)), players(2, 2), Side.A, 12_000_000 + 2 * 3_600_000L)
                check("after the window: rated again", ratings.finalize(m3, limit, 12_000_000 + 2 * 3_600_000L) is Finalized.Rated)

                // Database failure mid-transaction: nothing is written, the match stays retryable.
                val failing = play(ranked, "season-1", teamA, teamB, a, b, Side.A, 30_000_000)
                val ratingsBefore = db.query("SELECT season_id || rating_type || subject_id || ':' || rating || ':' || matches FROM lifesteal_ranked_ratings ORDER BY 1") { it.getString(1) }
                val historyBefore = count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history")
                val broken = RatingStore(FailingDb(db, failAt = 4))
                check("simulated failure throws", throws { broken.finalize(failing, POLICY, 30_000_100) })
                check("rollback: no rating changed (no partial update)", db.query("SELECT season_id || rating_type || subject_id || ':' || rating || ':' || matches FROM lifesteal_ranked_ratings ORDER BY 1") { it.getString(1) } == ratingsBefore)
                check("rollback: no history row", count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history") == historyBefore)
                check("rollback: no result row", ratings.result(failing) == null)
                ratings.recordFailure(failing, "simulated disk error", 30_000_200)
                check("failure recorded as FAILED and retryable", ratings.result(failing)?.status == RatingStore.ResultStatus.FAILED && ratings.unprocessedCompleted(5, 50) == listOf(failing))
                ratings.recordFailure(failing, "again", 30_000_300)
                check("attempts counted", ratings.result(failing)?.attempts == 2)
                check("after too many attempts it waits for staff", ratings.unprocessedCompleted(2, 50).isEmpty() && ratings.unratedCompleted(10).contains(failing))
                check("retry: rated exactly once", ratings.finalize(failing, POLICY, 30_000_400) is Finalized.Rated && ratings.result(failing)?.status == RatingStore.ResultStatus.RATED)
                ratings.recordFailure(failing, "late failure report", 30_000_500)
                check("a late failure report never downgrades a RATED result", ratings.result(failing)?.status == RatingStore.ResultStatus.RATED)
                check("status counts", ratings.countByStatus()[RatingStore.ResultStatus.RATED]!! >= 8)

                // An inconsistent record is reported, never rated, never retried.
                val bad = play(ranked, "season-1", teamA, teamB, a, b, Side.A, 31_000_000)
                db.execute("UPDATE lifesteal_ranked_matches SET winner_team_id = ? WHERE match_id = ?", teamB.first, bad)
                check("invalid record: INVALID, nothing written", ratings.finalize(bad, POLICY, 31_000_100) is Finalized.Invalid && ratings.historyOfMatch(bad).isEmpty() && ratings.result(bad)?.status == RatingStore.ResultStatus.INVALID)
                check("invalid record: listed for staff, not retried", ratings.results(RatingStore.ResultStatus.INVALID, 10).any { it.matchId == bad } && ratings.unprocessedCompleted(5, 50).isEmpty())
                check("a missing match: nothing written", ratings.finalize("rtw-ffffffffffff", POLICY, 1) is Finalized.Missing && ratings.result("rtw-ffffffffffff") == null)

                // Crash between completion and rating: a completed match without a result is found by the sweep.
                val crashed = play(ranked, "season-1", teamA, teamB, a, b, Side.B, 32_000_000)
                check("crash before rating: the sweep finds it", ratings.unprocessedCompleted(5, 50) == listOf(crashed))
            } finally { db.shutdown() }
        }
        // Restart: everything committed is still there, nothing is rated twice.
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val ratings = RatingStore(db)
                ratings.createTables()
                check("restart: ratings kept", ratings.rating("season-2", RatingType.PLAYER_ELO, JAVA.toString())?.rating == 1016)
                check("restart: a rated match stays rated", ratings.finalize(firstMatch, POLICY, 40_000_000) is Finalized.AlreadyProcessed)
                val pending = ratings.unprocessedCompleted(5, 50)
                check("restart: the crashed match is still waiting", pending.size == 1)
                check("restart: rated once afterwards", ratings.finalize(pending[0], POLICY, 40_000_100) is Finalized.Rated && ratings.finalize(pending[0], POLICY, 40_000_200) is Finalized.AlreadyProcessed)
                check("restart: no duplicate history", count(db, "SELECT COUNT(*) FROM (SELECT match_id, rating_type, subject_id FROM lifesteal_ranked_rating_history GROUP BY 1, 2, 3 HAVING COUNT(*) > 1)") == 0)
            } finally { db.shutdown() }
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Issue #1499: roster-overlap rule ─────────────────────────

private fun rosterRule() {
    val a = players(1, 5)
    val b = players(2, 5)
    val x = players(8, 5)
    val y = players(9, 5)
    fun same(ca: List<UUID>, cb: List<UUID>, pa: List<UUID>, pb: List<UUID>) = RatingRules.sameOpponents(ca, cb, pa, pb)
    check("#1499 roster: identical 2v2 = same opponents", same(a.take(2), b.take(2), a.take(2), b.take(2)))
    check("#1499 roster: sides swapped = same opponents", same(b.take(2), a.take(2), a.take(2), b.take(2)))
    check("#1499 roster: 2v2 with one player replaced (3 of 4 the same) = same opponents", same(a.take(2), listOf(b[0], y[0]), a.take(2), b.take(2)))
    check("#1499 roster: 2v2 with one player of each side replaced (one incidental opponent pair) = NOT the same", !same(listOf(a[0], x[0]), listOf(b[0], y[0]), a.take(2), b.take(2)))
    check("#1499 roster: one shared player only = NOT the same", !same(listOf(a[0], x[0]), y.take(2), a.take(2), b.take(2)))
    check("#1499 roster: teammates who now play each other = NOT the same opponents", !same(listOf(a[0], x[0]), listOf(a[1], y[0]), a.take(2), b.take(2)))
    check("#1499 roster: 3v3 keeping two of each side = same opponents", same(listOf(a[0], a[1], x[0]), listOf(b[0], b[1], y[0]), a.take(3), b.take(3)))
    check("#1499 roster: 3v3 keeping one of one side = NOT the same", !same(listOf(a[0], a[1], a[2]), listOf(b[0], y[0], y[1]), a.take(3), b.take(3)))
    check("#1499 roster: 4v4 keeping two of each side = same opponents", same(a.take(2) + x.take(2), b.take(2) + y.take(2), a.take(4), b.take(4)))
    check("#1499 roster: 5v5 keeping three of each side = same opponents", same(a.take(3) + x.take(2), b.take(3) + y.take(2), a, b))
    check("#1499 roster: 5v5 keeping two of each side = NOT the same", !same(a.take(2) + x.take(3), b.take(2) + y.take(3), a, b))
    check("#1499 roster: unrelated teams = NOT the same", !same(x.take(2), y.take(2), a.take(2), b.take(2)))
    check("#1499 roster: a 2v2 cut from an earlier 5v5 (3 of 4 met) = same opponents", same(a.take(2), listOf(b[0], y[0]), a, b))
    check("#1499 roster: deterministic and symmetric in the current sides", (1..10).all { same(a.take(2), listOf(b[0], y[0]), a.take(2), b.take(2)) && same(listOf(b[0], y[0]), a.take(2), a.take(2), b.take(2)) })

    check("#1499 decide: the roster limit makes the match unrated", (RatingRules.decide(record(), parts(), false, 3, 0, 3) as? RatingRules.Verdict.Unrated)?.limit == RatingRules.Limit.REPEATED_ROSTER)
    check("#1499 decide: below the roster limit stays rated", RatingRules.decide(record(), parts(), false, 3, 0, 2) is RatingRules.Verdict.Rated)
    check("#1499 decide: the team limit is checked too (either limit)", (RatingRules.decide(record(), parts(), false, 3, 3, 0) as? RatingRules.Verdict.Unrated)?.limit == RatingRules.Limit.TEAM_PAIR)
    check("#1499 decide: limit 0 turns both off", RatingRules.decide(record(), parts(), false, 0, 9, 9) is RatingRules.Verdict.Rated)
    check("#1499 decide: TEST stays unrated (not a limit)", (RatingRules.decide(record(kind = MatchKind.TEST, test = true), parts(), true, 3, 0, 0) as? RatingRules.Verdict.Unrated)?.limit == null)
    check("#1499 decide: challenges keep their setting", RatingRules.decide(record(kind = MatchKind.CHALLENGE), parts(), false, 3, 0, 0) is RatingRules.Verdict.Unrated &&
        RatingRules.decide(record(kind = MatchKind.CHALLENGE), parts(), true, 3, 0, 0) is RatingRules.Verdict.Rated &&
        (RatingRules.decide(record(kind = MatchKind.CHALLENGE), parts(), true, 3, 0, 3) as? RatingRules.Verdict.Unrated)?.limit == RatingRules.Limit.REPEATED_ROSTER)
    check("#1499 decide: an invalid record is still invalid (limits never hide it)", RatingRules.decide(record(winner = null), parts(), false, 3, 9, 9) is RatingRules.Verdict.Invalid)
}

// ── Issue #1499: team recreation / repeated opponents against a real database ──

private fun antiFarming() {
    val dir = Files.createTempDirectory("ranked-elo-farming").toFile()
    val policy = POLICY.copy(sameOpponentLimit = 3, sameOpponentWindowMillis = 24 * 3_600_000L)
    val x = "aaaa0000-0000-4000-8000-000000000001" to "Xeno"
    val y = "bbbb0000-0000-4000-8000-000000000002" to "Yak"
    val y2 = "bbbb0000-0000-4000-8000-000000000003" to "Yak"      // Y disbanded and recreated with the same name
    val y3 = "bbbb0000-0000-4000-8000-000000000004" to "Yeti"     // and again under a new name
    val z = "cccc0000-0000-4000-8000-000000000005" to "Zebra"
    val xs = players(11, 2)
    val ys = players(12, 2)
    val other = players(13, 2)
    val h = 3_600_000L
    lateinit var recreated: String
    try {
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val ranked = RankedStore(db)
                ranked.createTables()
                val ratings = RatingStore(db)
                ratings.createTables()
                fun history() = count(db, "SELECT COUNT(*) FROM lifesteal_ranked_rating_history")
                fun elo(u: UUID) = ratings.rating("season-1", RatingType.PLAYER_ELO, u.toString())?.rating

                val first = (1..3).map { i ->
                    val id = play(ranked, "season-1", x, y, xs, ys, if (i % 2 == 0) Side.B else Side.A, 100 * h + i * h)
                    ratings.finalize(id, policy, 100 * h + i * h + 1)
                }
                check("#1499 1st, 2nd and 3rd rated match between the same opponents award Elo", first.all { it is Finalized.Rated })
                val historyAfter3 = history()
                val xBefore = elo(xs[0])
                val teamXBefore = ratings.rating("season-1", RatingType.TEAM_ELO, x.first)!!.rating

                val fourth = play(ranked, "season-1", x, y, xs, ys, Side.A, 104 * h)
                val r4 = ratings.finalize(fourth, policy, 104 * h + 1)
                check("#1499 4th match against the same team inside 24h: unrated (team limit)", r4 is Finalized.Unrated && (r4 as Finalized.Unrated).limit == RatingRules.Limit.TEAM_PAIR)

                // The exploit: Y disbands, the same players make Y2 and keep playing X.
                recreated = play(ranked, "season-1", x, y2, xs, ys, Side.A, 105 * h)
                val r5 = ratings.finalize(recreated, policy, 105 * h + 1)
                check("#1499 team recreation does not reset the limit: unrated by the roster limit", r5 is Finalized.Unrated && (r5 as Finalized.Unrated).limit == RatingRules.Limit.REPEATED_ROSTER)
                r5 as Finalized.Unrated
                check("#1499 the earlier rated matches all count (3) and the old team id is reported", r5.earlierCount == 3 && r5.earlierOtherTeams == setOf(y.first))
                check("#1499 both teams get the same verdict (participants of both sides)", r5.participants.toSet() == (xs + ys).toSet() && r5.sides.values.toSet() == setOf(Side.A, Side.B))
                check("#1499 unrated: no Player Elo change", elo(xs[0]) == xBefore && elo(ys[0]) != null)
                check("#1499 unrated: no Team Elo change (old team, and the new team gets no rating row)", ratings.rating("season-1", RatingType.TEAM_ELO, x.first)!!.rating == teamXBefore && ratings.rating("season-1", RatingType.TEAM_ELO, y2.first) == null)
                check("#1499 unrated: no history rows written", history() == historyAfter3)
                check("#1499 unrated is final: never rated on a retry", ratings.finalize(recreated, policy, 105 * h + 2) is Finalized.AlreadyProcessed && ratings.result(recreated)?.status == RatingStore.ResultStatus.UNRATED)

                val renamed = play(ranked, "season-1", y3, x, ys, xs, Side.A, 106 * h)
                check("#1499 renaming (and sides swapped) doesn't reset it either", (ratings.finalize(renamed, policy, 106 * h + 1) as? Finalized.Unrated)?.limit == RatingRules.Limit.REPEATED_ROSTER)

                val partial = play(ranked, "season-1", x, y2, xs, listOf(ys[0], other[0]), Side.B, 106 * h + 1000)
                check("#1499 partial roster change (3 of 4 the same): unrated by the overlap rule", (ratings.finalize(partial, policy, 106 * h + 2000) as? Finalized.Unrated)?.limit == RatingRules.Limit.REPEATED_ROSTER)

                val incidental = play(ranked, "season-1", x, y2, listOf(xs[0], other[1]), listOf(ys[0], players(14, 1)[0]), Side.A, 106 * h + 3000)
                check("#1499 one incidental opponent pair (2 of 4 changed): still rated", ratings.finalize(incidental, policy, 106 * h + 4000) is Finalized.Rated)

                val unrelated = play(ranked, "season-1", z, y2, players(15, 2), players(16, 2), Side.A, 107 * h)
                check("#1499 unrelated teams earn Elo", ratings.finalize(unrelated, policy, 107 * h + 1) is Finalized.Rated)
                val xVsZ = play(ranked, "season-1", x, z, xs, players(15, 2), Side.A, 107 * h + 1000)
                check("#1499 X against a different opponent still earns Elo", ratings.finalize(xVsZ, policy, 107 * h + 2000) is Finalized.Rated)

                val test = play(ranked, "season-1", x, y2, xs, ys, Side.A, 107 * h + 3000, MatchKind.TEST)
                check("#1499 TEST matches stay unrated without a limit verdict", (ratings.finalize(test, policy, 107 * h + 4000) as? Finalized.Unrated)?.let { it.test && it.limit == null } == true)

                // The window: matches 1..3 ended at 101..103h; after 24h of them the rosters may be rated again.
                val later = play(ranked, "season-1", x, y2, xs, ys, Side.A, 128 * h)
                check("#1499 after the window the same opponents are rated again", ratings.finalize(later, policy, 128 * h + 1) is Finalized.Rated)
                check("#1499 unrated matches never count toward the limit (only RATED ones do)",
                    ratings.repeatedRosterMatches("season-1", ranked.participants(later), 104 * h, later).isEmpty())
                val fresh = play(ranked, "season-1", "dddd0000-0000-4000-8000-000000000006" to "Delta", "eeee0000-0000-4000-8000-000000000007" to "Echo", players(17, 2), players(18, 2), Side.A, 129 * h)
                val rf = ratings.finalize(fresh, policy, 129 * h + 1) as Finalized.Rated
                check("#1499 the Elo formula is unchanged (new equals still +16 / -16)", rf.teams.values.map { it.delta }.toSet() == setOf(16, -16) && rf.players.values.map { it.delta }.toSet() == setOf(16, -16))
                check("#1499 no duplicate rating rows", count(db, "SELECT COUNT(*) FROM (SELECT match_id, rating_type, subject_id FROM lifesteal_ranked_rating_history GROUP BY 1, 2, 3 HAVING COUNT(*) > 1)") == 0)
                check("#1499 history of the disbanded team is intact", ratings.history(RatingType.TEAM_ELO, y.first, null, 10).size == 3)
            } finally { db.shutdown() }
        }
        // Restart: the participant history is persistent, so the limit still applies.
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val ranked = RankedStore(db)
                ranked.createTables()
                val ratings = RatingStore(db)
                ratings.createTables()
                val again = play(ranked, "season-1", x, y3, xs, ys, Side.A, 128 * h + 5000)
                val r = ratings.finalize(again, policy, 128 * h + 6000)
                check("#1499 opponent history survives a restart (only one rated rematch since the window reopened, so rated)", r is Finalized.Rated)
                val counted = ratings.repeatedRosterMatches("season-1", ranked.participants(again), 104 * h, "none").map { it.matchId }
                check("#1499 after a restart the earlier rated rosters are still found (and unrated ones aren't)", counted.size == 2 && recreated !in counted)
            } finally { db.shutdown() }
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/ranked/rating/LifestealRatingManager.kt")
    val ranked = source("lifesteal/ranked/LifestealRankedManager.kt")
    val command = source("lifesteal/ranked/queue/RankedCommand.kt")
    val menu = source("lifesteal/ranked/queue/RankedQueueMenu.kt")
    val joshymc = source("Joshymc.kt")
    check("Survival isolation: no tables, task or reads unless Lifesteal is hosted",
        manager.contains("ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes") &&
            manager.indexOf("if (!hosted) return") in 0 until manager.indexOf("store.createTables()"))
    check("Survival isolation: /ranked stays LIFESTEAL-scoped (no new command)", GamemodeAssignments.COMMANDS["ranked"] == GamemodeScope.LIFESTEAL)
    check("Survival isolation: the rating code never touches Survival teams, economy, inventories or leaderboards",
        File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/rating").listFiles()!!.none { f ->
            val t = f.readText()
            t.contains("plugin.teamManager") || t.contains("economyManager") || t.contains("leaderboardManager") || t.contains(".inventory")
        })
    check("rated only after the COMPLETED record is written, never at shutdown", ranked.contains("if (ok && !shutdown) plugin.lifestealRatingManager.onMatchCompleted(match.id)"))
    check("rating runs on the database thread", manager.contains("plugin.databaseManager.async(\"ranked rating \$matchId\")"))
    check("one rating task per match", manager.contains("if (!inFlight.add(matchId)) return false"))
    check("startup/periodic sweep for missed and failed ratings", manager.contains("store.unprocessedCompleted(MAX_ATTEMPTS, SWEEP_BATCH)"))
    check("bounded profile cache, evicted on quit", manager.contains("size > PROFILE_CACHE_SIZE") && manager.contains("profiles.remove(player.uniqueId)"))
    val ratingWriters = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
        .filter { it.readText().contains(Regex("(INSERT|UPDATE)[^\"]*lifesteal_ranked_ratings\\b")) }.map { it.name }.toList()
    check("only RatingStore writes ratings", ratingWriters == listOf("RatingStore.kt"))
    check("only EloRating computes Elo (no expected-score math elsewhere)",
        File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "EloRating.kt" }.none { it.readText().contains("/ 400.0") })
    check("no command or method sets a rating", !manager.contains("fun setRating") && !source("lifesteal/ranked/rating/RatingAdminCommand.kt").contains("\"set\""))
    check("/ranked stats|rating|history are subcommands of the existing /ranked", command.contains("\"stats\" -> stats(") && command.contains("\"rating\" -> stats(") && command.contains("\"history\" -> history("))
    check("other players need joshymc.lifesteal.ranked.stats.others and are resolved safely", command.contains("PERMISSION_STATS_OTHERS") && command.contains("plugin.playerResolver.resolve(name)"))
    check("the menu shows the rating from the cache (never computes it)", menu.contains("ratings.cachedProfile(player.uniqueId)") && !menu.contains("EloRating"))
    check("manager wired: created, refreshed, reloaded, stopped", joshymc.contains("lifestealRatingManager = com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager(this)") &&
        joshymc.contains("lifestealRatingManager.refresh()") && joshymc.contains("safe(\"lifestealRatingManager.refresh\")") && joshymc.contains("shutdown.step(\"lifestealRatingManager.stop\""))
    val yml = File("src/main/resources/plugin.yml").readText()
    check("plugin.yml: stats.others permission and usage", yml.contains("joshymc.lifesteal.ranked.stats.others:") && yml.contains("stats [player]|rating|history [player]"))
    check("no ranked rewards, divisions, leaderboards or resets were added",
        File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/rating").listFiles()!!.none { f -> val t = f.readText().lowercase(); t.contains("division") || t.contains("reward") || t.contains("fun reset") })
}
