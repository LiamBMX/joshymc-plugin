// Checks Ranked leaderboards, divisions and competitive statistics (issue #1367): division config
// validation (thresholds, overlaps, ordering, separate team thresholds, the lowest division),
// division lookup / progress / promotion and demotion, statistics (wins, losses, win rate, streaks,
// peak/lowest, duplicates), paging, menu layouts, and against a real SQLite database with the real
// match, rating and team stores: player and team leaderboard order, Elo ties, pagination, positions,
// minimum-match eligibility, offline and Bedrock players, disbanded and recreated teams, team
// membership and leadership changes, cancelled / test / invalid / unrated / pending matches, match
// history paging, season separation, the consistency check and database failures; plus the wiring
// (Lifesteal only, read-only, notices only after commit, cache dropped on commit). The Gradle build
// has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-ranked-stats-check.init.gradle.kts lifestealRankedStatsCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (the menus on Java and Bedrock,
// chat output, real matches) is NOT covered here; see docs/lifesteal-ranked-stats.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
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
import com.liam.joshymc.lifesteal.ranked.rating.RatingStore
import com.liam.joshymc.lifesteal.ranked.rating.RatingStore.Finalized
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.lifesteal.ranked.stats.DivisionConfig
import com.liam.joshymc.lifesteal.ranked.stats.DivisionTable
import com.liam.joshymc.lifesteal.ranked.stats.RankedMenuLayout
import com.liam.joshymc.lifesteal.ranked.stats.RankedStatistics
import com.liam.joshymc.lifesteal.ranked.stats.RankedStatsStore
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
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

private fun throws(block: () -> Unit): Boolean = try { block(); false } catch (_: Exception) { true }
private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private val P = EloRating.Params(initialPlayer = 1000, initialTeam = 1000, minimum = 0, maximum = 5000, kFactor = 32)
private val POLICY = RatingStore.Policy(P, rateChallenges = false, sameOpponentLimit = 0, sameOpponentWindowMillis = 24 * 3_600_000L)
private val POLICY_CHALLENGES = POLICY.copy(rateChallenges = true)

/** Floodgate (Bedrock) ids are version-0 UUIDs built from the XUID; Java ids are version 4. */
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f4ac2d1e3b")
private val JAVA = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7")

private fun p(team: Int, i: Int) = UUID(7000L + team, i.toLong())
private fun players(team: Int, n: Int) = List(n) { p(team, it) }

fun main() {
    divisions()
    leaderboardConfig()
    statistics()
    layout()
    database()
    wiring()
    println("Ranked statistics checks passed ($checks checks).")
}

// ── Divisions ────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun bundled(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun node(m: Map<String, Any?>, path: String): Any? = path.split('.').fold<String, Any?>(m) { n, key -> (n as? Map<*, *>)?.get(key) }

private fun parseDivisions(m: Map<String, Any?>, eloMinimum: Int = 0) =
    DivisionConfig.parse({ node(m, it) }, { (node(m, it) as? Map<*, *>)?.keys?.map { k -> k.toString() } }, eloMinimum)

@Suppress("UNCHECKED_CAST")
private fun withTiers(edit: (MutableMap<String, MutableMap<String, Any?>>) -> Unit): Map<String, Any?> {
    val m = bundled().toMutableMap()
    val ranked = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
    val divisions = (ranked["divisions"] as Map<String, Any?>).toMutableMap()
    val tiers = LinkedHashMap<String, MutableMap<String, Any?>>()
    for ((k, v) in divisions["tiers"] as Map<String, Any?>) tiers[k] = (v as Map<String, Any?>).toMutableMap()
    edit(tiers)
    divisions["tiers"] = tiers
    ranked["divisions"] = divisions
    m["ranked-team-wars"] = ranked
    return m
}

private fun severe(m: Map<String, Any?>, eloMinimum: Int = 0) = parseDivisions(m, eloMinimum).let { it.table == null && it.status == DivisionConfig.Status.INVALID && it.problems.any { p -> p.severe } }

@Suppress("UNCHECKED_CAST")
private fun divisions() {
    val result = parseDivisions(bundled())
    check("bundled divisions: ready, no problems", result.status == DivisionConfig.Status.READY && result.problems.isEmpty())
    val table = result.table!!
    check("bundled divisions: the issue's proposed seven, lowest first",
        table.divisions.map { it.id } == listOf("bronze", "silver", "gold", "platinum", "diamond", "master", "grandmaster") &&
            table.divisions.map { it.playerMinimum } == listOf(0, 800, 1000, 1200, 1400, 1600, 1800))
    check("bundled divisions: team thresholds default to the player ones", table.divisions.all { it.teamMinimum == it.playerMinimum })
    check("bundled divisions: every one has a name, colour, icon and description", table.divisions.all { it.displayName.isNotBlank() && it.color.isNotBlank() && it.icon != null && it.description.isNotBlank() })

    check("issue example: Player Elo 1450 = Diamond", table.of(RatingType.PLAYER_ELO, 1450).id == "diamond")
    check("issue example: Team Elo 1100 = Gold", table.of(RatingType.TEAM_ELO, 1100).id == "gold")
    check("exact threshold belongs to the division it starts", table.of(RatingType.PLAYER_ELO, 1000).id == "gold" && table.of(RatingType.PLAYER_ELO, 999).id == "silver")
    check("minimum rating (0) = Bronze", table.of(RatingType.PLAYER_ELO, 0).id == "bronze")
    check("a rating below every threshold is still the lowest division", table.of(RatingType.PLAYER_ELO, -50).id == "bronze")
    check("maximum rating = Grandmaster", table.of(RatingType.PLAYER_ELO, 5000).id == "grandmaster")
    val pr = table.progress(RatingType.PLAYER_ELO, 1120)
    check("issue example: Gold 1120 -> Platinum at 1200, 80 remaining", pr.current.id == "gold" && pr.next?.id == "platinum" && pr.required == 1200 && pr.remaining == 80)
    val top = table.progress(RatingType.PLAYER_ELO, 2400)
    check("highest division: no next division, nothing remaining", top.current.id == "grandmaster" && top.next == null && top.required == null && top.remaining == null)
    check("promotion: 1390 -> 1405 is Platinum -> Diamond", (table.change(RatingType.PLAYER_ELO, 1390, 1405) as? DivisionTable.Change.Promotion)?.let { it.from.id == "platinum" && it.to.id == "diamond" } == true)
    check("demotion: 1010 -> 990 is Gold -> Silver", (table.change(RatingType.PLAYER_ELO, 1010, 990) as? DivisionTable.Change.Demotion)?.let { it.from.id == "gold" && it.to.id == "silver" } == true)
    check("no change inside a division: no notice", table.change(RatingType.PLAYER_ELO, 1010, 1190) == null && table.change(RatingType.PLAYER_ELO, 1100, 1100) == null)
    check("promotion across two divisions names the new one", (table.change(RatingType.PLAYER_ELO, 990, 1250) as? DivisionTable.Change.Promotion)?.to?.id == "platinum")

    // Separate team thresholds.
    val split = parseDivisions(withTiers { t -> t.values.forEachIndexed { i, d -> d["team-minimum-elo"] = (d["minimum-elo"] as Int) + if (i == 0) 0 else 50 } })
    check("separate team thresholds are read", split.status == DivisionConfig.Status.READY && split.table!!.divisions[2].teamMinimum == 1050)
    check("player and team are classified independently with their own thresholds",
        split.table!!.of(RatingType.PLAYER_ELO, 1020).id == "gold" && split.table!!.of(RatingType.TEAM_ELO, 1020).id == "silver")
    check("team change uses team thresholds", split.table!!.change(RatingType.TEAM_ELO, 1020, 1060) is DivisionTable.Change.Promotion && split.table!!.change(RatingType.PLAYER_ELO, 1020, 1060) == null)

    // Invalid configuration.
    check("two divisions with the same threshold are severe (ambiguous)", severe(withTiers { it["silver"]!!["minimum-elo"] = 1000 }))
    check("team thresholds out of order are severe", severe(withTiers { it["gold"]!!["team-minimum-elo"] = 700 }))
    check("lowest division above the Elo minimum is severe", severe(withTiers { it["bronze"]!!["minimum-elo"] = 100 }))
    check("...but fine when the Elo minimum is raised to match", parseDivisions(withTiers { it["bronze"]!!["minimum-elo"] = 100 }, eloMinimum = 100).status == DivisionConfig.Status.READY)
    check("negative threshold is severe", severe(withTiers { it["bronze"]!!["minimum-elo"] = -1 }))
    check("threshold above 100000 is severe", severe(withTiers { it["grandmaster"]!!["minimum-elo"] = 200_000 }))
    check("non-number threshold is severe", severe(withTiers { it["gold"]!!["minimum-elo"] = "a lot" }))
    check("missing threshold is severe", severe(withTiers { it["gold"]!!.remove("minimum-elo") }))
    check("missing name is severe", severe(withTiers { it["gold"]!!.remove("display-name") }))
    check("duplicate name is severe", severe(withTiers { it["silver"]!!["display-name"] = "Gold" }))
    check("legacy colour codes in a name are severe", severe(withTiers { it["gold"]!!["display-name"] = "&6Gold" }))
    check("unknown colour is severe", severe(withTiers { it["gold"]!!["color"] = "goldish" }))
    check("hex colour is accepted", parseDivisions(withTiers { it["gold"]!!["color"] = "#FFAA00" }).status == DivisionConfig.Status.READY)
    check("bad icon name is severe", severe(withTiers { it["gold"]!!["icon"] = "gold ingot!" }))
    check("bad division id is severe", severe(withTiers { t -> t["Gold Tier"] = t.remove("gold")!! }))
    check("too many divisions is severe", severe(withTiers { t -> for (i in 0 until 10) t["extra$i"] = mutableMapOf("display-name" to "Extra $i", "color" to "white", "minimum-elo" to 3000 + i) }))
    check("no divisions is severe", severe(withTiers { it.clear() }))
    val unsorted = parseDivisions(withTiers { t -> val g = t.remove("bronze")!!; t["bronze"] = g })
    check("tiers listed out of order: used in threshold order with a warning", unsorted.status == DivisionConfig.Status.READY && unsorted.table!!.divisions.first().id == "bronze" && unsorted.problems.single().severe.not())
    val disabled = bundled().toMutableMap().also { m ->
        val ranked = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        ranked["divisions"] = (ranked["divisions"] as Map<String, Any?>).toMutableMap().apply { put("enabled", false) }
        m["ranked-team-wars"] = ranked
    }
    check("enabled: false turns divisions off without errors", parseDivisions(disabled).let { it.status == DivisionConfig.Status.DISABLED && it.table == null && it.problems.none { p -> p.severe } })
    check("enabled: 'yes' is severe", parseDivisions(bundled().toMutableMap().also { m ->
        val ranked = (m["ranked-team-wars"] as Map<String, Any?>).toMutableMap()
        ranked["divisions"] = (ranked["divisions"] as Map<String, Any?>).toMutableMap().apply { put("enabled", "yes") }
        m["ranked-team-wars"] = ranked
    }).status == DivisionConfig.Status.INVALID)
    check("missing section: INVALID here (the loader falls back to the bundled file)", parseDivisions(emptyMap()).status == DivisionConfig.Status.INVALID)
    check("an invalid table is refused by DivisionTable itself", throws { DivisionTable(table.divisions.reversed()) } && throws { DivisionTable(emptyList()) })
    check("thresholds live only in lifesteal.yml (no hard-coded 1800 in the division code)", !source("lifesteal/ranked/stats/RankedDivisions.kt").contains("1800"))
}

private fun leaderboardConfig() {
    val ok = DivisionConfig.parseLeaderboards({ node(bundled(), it) })
    check("bundled leaderboards: 5 player / 5 team matches, 30s cache", ok.settings == DivisionConfig.Leaderboards(5, 5, 30) && ok.problems.isEmpty())
    fun with(key: String, value: Any?) = DivisionConfig.parseLeaderboards({ path -> if (path == "ranked-team-wars.leaderboards.$key") value else node(bundled(), path) })
    check("negative minimum is severe", with("minimum-player-matches", -1).settings == null)
    check("non-number minimum is severe", with("minimum-team-matches", "five").settings == null)
    check("cache above 600s is severe", with("cache-seconds", 601).settings == null)
    check("minimum 0 (everyone rated is listed) is allowed", with("minimum-player-matches", 0).settings?.minimumPlayerMatches == 0)
    check("missing section: unavailable", DivisionConfig.parseLeaderboards({ null }).settings == null)
}

// ── Statistics ───────────────────────────────────────────────

private fun pt(id: String, prev: Int, delta: Int, outcome: RatingOutcome?) = RankedStatistics.Point(id, prev, delta, prev + delta, outcome, id.hashCode().toLong())

private fun statistics() {
    val empty = RankedStatistics.summarize(emptyList(), 1000)
    check("no history: initial rating, no matches, '-' win rate, not rated",
        empty.current == 1000 && empty.peak == 1000 && empty.lowest == 1000 && empty.matches == 0 && empty.winPercent == "-" && !empty.rated && empty.lastMatchAt == null)
    val s = RankedStatistics.summarize(listOf(
        pt("m1", 1000, 16, RatingOutcome.WIN), pt("m2", 1016, 15, RatingOutcome.WIN), pt("m3", 1031, -17, RatingOutcome.LOSS),
        pt("m4", 1014, 16, RatingOutcome.WIN), pt("m5", 1030, 14, RatingOutcome.WIN), pt("m6", 1044, 13, RatingOutcome.WIN),
        pt("m7", 1057, -18, RatingOutcome.LOSS), pt("m8", 1039, -16, RatingOutcome.LOSS),
    ), 1000)
    check("matches / wins / losses", s.matches == 8 && s.wins == 5 && s.losses == 3 && s.draws == 0)
    check("win percentage 62.5%", s.winPercent == "62.5%")
    check("current Elo = last committed value", s.current == 1023)
    check("peak 1057, lowest 1000 (the starting rating counts)", s.peak == 1057 && s.lowest == 1000)
    check("Elo gained / lost", s.gained == 16 + 15 + 16 + 14 + 13 && s.lost == 17 + 18 + 16)
    check("current streak 0 after a loss, highest streak 3", s.currentStreak == 0 && s.bestStreak == 3)
    val streak = RankedStatistics.summarize(listOf(pt("a", 1000, -16, RatingOutcome.LOSS), pt("b", 984, 16, RatingOutcome.WIN), pt("c", 1000, 16, RatingOutcome.WIN)), 1000)
    check("current streak counts wins up to the latest match", streak.currentStreak == 2 && streak.bestStreak == 2)
    check("lowest Elo after a loss", streak.lowest == 984)
    val draw = RankedStatistics.summarize(listOf(pt("a", 1000, 16, RatingOutcome.WIN), pt("b", 1016, 0, RatingOutcome.DRAW)), 1000)
    check("a draw (if one is ever recorded) counts as played and ends the streak", draw.draws == 1 && draw.matches == 2 && draw.currentStreak == 0 && draw.winPercent == "50.0%")
    val dup = RankedStatistics.summarize(listOf(pt("a", 1000, 16, RatingOutcome.WIN), pt("a", 1000, 16, RatingOutcome.WIN)), 1000)
    check("the same match is never counted twice", dup.matches == 1 && dup.wins == 1 && dup.gained == 16)
    val unknown = RankedStatistics.summarize(listOf(pt("a", 1000, 16, null)), 1000)
    check("an unreadable outcome isn't counted (never guessed)", unknown.matches == 0 && unknown.current == 1016)
    check("win percentage rounding", RankedStatistics.winPercent(1, 3) == "33.3%" && RankedStatistics.winPercent(2, 3) == "66.7%" && RankedStatistics.winPercent(3, 3) == "100.0%" && RankedStatistics.winPercent(0, 0) == "-")
    check("pages: empty board still has one page", RankedStatistics.pages(0, 28) == 1 && RankedStatistics.pages(28, 28) == 1 && RankedStatistics.pages(29, 28) == 2)
    check("page clamp", RankedStatistics.clampPage(5, 30, 28) == 1 && RankedStatistics.clampPage(-3, 30, 28) == 0)
    check("durations", RankedStatistics.duration(65_000) == "1m 5s" && RankedStatistics.duration(9_400) == "9s")
}

// ── Menus ────────────────────────────────────────────────────

private fun layout() {
    val main = RankedMenuLayout.MAIN_SLOTS
    check("main menu: 45 slots, every button in range", RankedMenuLayout.MAIN_SIZE == 45 && main.all { it in 0 until 45 })
    check("main menu: no two buttons share a slot (queue, challenges, accept/decline keep theirs)", main.toSet().size == main.size)
    check("main menu: the issue #1363 queue slots are unchanged",
        RankedMenuLayout.QUEUE_SIZES == listOf(10, 11, 12, 13) && RankedMenuLayout.STATUS == 15 && RankedMenuLayout.CHALLENGES == 16 &&
            RankedMenuLayout.ACCEPT == 19 && RankedMenuLayout.DECLINE == 20 && RankedMenuLayout.LEAVE == 24 && RankedMenuLayout.RATING == 4)
    check("main menu: Close in the bottom centre", RankedMenuLayout.closeSlot(45) == 40 && 40 in main)
    check("main menu: seven statistics buttons in one row",
        listOf(RankedMenuLayout.PLAYER_STATS, RankedMenuLayout.TEAM_STATS, RankedMenuLayout.HISTORY, RankedMenuLayout.SEASON, RankedMenuLayout.DIVISIONS, RankedMenuLayout.TOP_PLAYERS, RankedMenuLayout.TOP_TEAMS) == (28..34).toList())
    val list = RankedMenuLayout.LIST_SLOTS
    val bottom = (45 until 54).toSet()
    check("list menus: 28 entries per page, distinct, not on the header or bottom row", list.size == 28 && RankedMenuLayout.PER_PAGE == 28 && list.toSet().size == 28 &&
        list.none { it in bottom || it == RankedMenuLayout.LIST_HEADER || it % 9 == 0 || it % 9 == 8 })
    check("list menus: entries in reading order (rank #1 top left)", list == list.sorted() && list.first() == 10)
    check("list menus: Back / Previous / Close / Next on the bottom row", listOf(RankedMenuLayout.backSlot(54), RankedMenuLayout.prevSlot(54), RankedMenuLayout.closeSlot(54), RankedMenuLayout.nextSlot(54)) == listOf(45, 47, 49, 51))
    check("stats row of seven centred", RankedMenuLayout.centeredRow(2, 7) == (19..25).toList() && RankedMenuLayout.centeredRow(2, 6) == (19..24).toList())
    check("divisions: seven fit one row", RankedMenuLayout.divisionSlots(7) == (19..25).toList())
    val most = RankedMenuLayout.divisionSlots(DivisionConfig.MAX_DIVISIONS)
    check("divisions: the configured maximum fits the 45-slot menu above the bottom row", most.size == DivisionConfig.MAX_DIVISIONS && most.toSet().size == most.size && most.all { it in 9 until 36 })
}

// ── Database ─────────────────────────────────────────────────

/** Plays a whole match through the real match store: created, active, won by [winner], completed, restored. */
private fun play(
    s: RankedStore, season: String, teamA: Pair<String, String>, teamB: Pair<String, String>, a: List<UUID>, b: List<UUID>,
    winner: Side?, endedAt: Long, kind: MatchKind = MatchKind.PUBLIC,
): String {
    val id = RankedMatchRules.newMatchId()
    val parts = a.map { MatchParticipant(it, Side.A, teamA.first) } + b.map { MatchParticipant(it, Side.B, teamB.first) }
    s.insertMatch(
        RankedStore.NewMatch(id, season, "duel", teamA.first, teamA.second, teamB.first, teamB.second, a.size, kind == MatchKind.TEST, "check", endedAt - 1000, kind, null),
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

private fun database() {
    val dir = Files.createTempDirectory("ranked-stats-check").toFile()
    val alpha = "a1a1a1a1-0000-4000-8000-000000000001" to "Alpha"
    val bravo = "b2b2b2b2-0000-4000-8000-000000000002" to "Bravo"
    val charlie = "c3c3c3c3-0000-4000-8000-000000000003" to "Charlie"
    val delta = "d4d4d4d4-0000-4000-8000-000000000004" to "Delta"
    val a = listOf(BEDROCK, JAVA)
    val b = players(2, 2)
    val c = players(3, 2)
    val d = players(4, 2)
    try {
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            try {
                val ranked = RankedStore(db).also { it.createTables() }
                ranked.createArena(RankedArena("duel", "Duel Pit", "lifesteal", null, enabled = true), 1)
                val ratings = RatingStore(db).also { it.createTables() }
                val teams = LifestealTeamStore(db).also { it.createTables() }
                for ((team, roster) in listOf(alpha to a, bravo to b, charlie to c, delta to d)) {
                    check("team ${team.second} created", teams.create(team.first, team.second, roster[0], "lead-${team.second}", 1) is LifestealTeamStore.CreateResult.Created)
                }
                val stats = RankedStatsStore(db)
                stats.createIndexes()
                stats.createIndexes() // idempotent
                fun rate(id: String, policy: RatingStore.Policy = POLICY) = ratings.finalize(id, policy, System.currentTimeMillis())

                check("empty board: no entries, one page, nobody placed",
                    stats.page("season-1", RatingType.PLAYER_ELO, 0, 0, 10).let { it.entries.isEmpty() && it.pages == 1 && it.total == 0 } &&
                        stats.position("season-1", RatingType.PLAYER_ELO, JAVA.toString(), 0) == null)

                // Rated matches: Alpha W, W, L; Bravo W, L; Charlie L, L, W; Delta W.
                val m1 = play(ranked, "season-1", alpha, bravo, a, b, Side.A, 10_000_000); check("m1 rated", rate(m1) is Finalized.Rated)
                val m2 = play(ranked, "season-1", alpha, charlie, a, c, Side.A, 11_000_000); check("m2 rated", rate(m2) is Finalized.Rated)
                val m3 = play(ranked, "season-1", bravo, alpha, b, a, Side.A, 12_000_000); check("m3 rated", rate(m3) is Finalized.Rated)
                val m4 = play(ranked, "season-1", delta, charlie, d, c, Side.A, 13_000_000); check("m4 rated", rate(m4) is Finalized.Rated)
                val m5 = play(ranked, "season-1", charlie, bravo, c, b, Side.A, 14_000_000); check("m5 rated", rate(m5) is Finalized.Rated)

                // Not results: a staff test match, a cancelled match, an unrated challenge, a pending one.
                val test = play(ranked, "season-1", alpha, bravo, a, b, Side.B, 15_000_000, MatchKind.TEST)
                check("test match: unrated", rate(test) is Finalized.Unrated)
                val cancelled = play(ranked, "season-1", alpha, bravo, a, b, null, 16_000_000)
                check("cancelled match: unrated", rate(cancelled) is Finalized.Unrated)
                val challenge = play(ranked, "season-1", alpha, delta, a, d, Side.B, 17_000_000, MatchKind.CHALLENGE)
                check("challenge (challenges unrated): unrated", rate(challenge) is Finalized.Unrated)
                val pending = play(ranked, "season-1", charlie, delta, c, d, Side.B, 18_000_000)

                // ── Player leaderboard ──
                val board = stats.page("season-1", RatingType.PLAYER_ELO, 0, 0, 100)
                check("player board: everyone rated is listed with minimum 0 (8 players)", board.total == 8 && board.entries.size == 8)
                check("player board: rating DESC", board.entries.zipWithNext().all { (x, y) -> x.rating >= y.rating })
                check("player board: positions 1..n", board.entries.map { it.position } == (1..8).toList())
                val ties = board.entries.groupBy { Triple(it.rating, it.wins, it.updatedAt) }.values.filter { it.size > 1 }
                check("Elo ties exist (teammates share every match) and are broken by subject id, deterministically",
                    ties.isNotEmpty() && ties.all { g -> g.map { it.subjectId } == g.map { it.subjectId }.sorted() })
                check("the same query gives the same order every time", stats.page("season-1", RatingType.PLAYER_ELO, 0, 0, 100).entries == board.entries)
                val full = board.entries.map { it.subjectId }
                val paged = (0 until 4).flatMap { stats.page("season-1", RatingType.PLAYER_ELO, 0, it, 2).entries.map { e -> e.subjectId } }
                check("pagination: pages of 2 add up to the full board, no gaps or repeats", paged == full)
                val last = stats.page("season-1", RatingType.PLAYER_ELO, 0, 99, 3)
                check("pagination: a page past the end is clamped to the last page", last.page == 2 && last.pages == 3 && last.entries.size == 2 && last.entries.first().position == 7)
                check("positions match the board for every player (Bedrock and Java alike)",
                    board.entries.all { stats.position("season-1", RatingType.PLAYER_ELO, it.subjectId, 0) == it.position } &&
                        full.contains(BEDROCK.toString()) && full.contains(JAVA.toString()))
                check("players are keyed by UUID; names are resolved at display time (renames keep the place)", board.entries.all { it.name == null })

                // Eligibility: Delta players have 1 rated match (m4), everyone else 2-3.
                val min3 = stats.page("season-1", RatingType.PLAYER_ELO, 3, 0, 100)
                check("minimum 3 rated matches: only players with 3 listed (Alpha, Bravo, Charlie)", min3.entries.map { it.subjectId }.toSet() == (a + b + c).map { it.toString() }.toSet())
                check("minimum 2: Delta (1 match) isn't listed, and has no place", stats.page("season-1", RatingType.PLAYER_ELO, 2, 0, 100).entries.none { it.subjectId == d[0].toString() } &&
                    stats.position("season-1", RatingType.PLAYER_ELO, d[0].toString(), 2) == null)
                check("...but their Elo is kept and readable", stats.entry("season-1", RatingType.PLAYER_ELO, d[0].toString())?.let { it.matches == 1 && it.rating > 1000 } == true)
                check("a player with no rated match is never listed, even at minimum 0", stats.position("season-1", RatingType.PLAYER_ELO, UUID(1, 1).toString(), 0) == null)

                // ── Team leaderboard ──
                val tb = stats.page("season-1", RatingType.TEAM_ELO, 0, 0, 100)
                check("team board: four teams, rating DESC, current names", tb.total == 4 && tb.entries.zipWithNext().all { (x, y) -> x.rating >= y.rating } && tb.entries.map { it.name }.toSet() == setOf("Alpha", "Bravo", "Charlie", "Delta"))
                check("team board: current leader is read from the team", tb.entries.first { it.subjectId == alpha.first }.leader == a[0])
                check("team and player boards are independent (different subjects, own rows)", tb.entries.none { e -> board.entries.any { it.subjectId == e.subjectId } })
                check("team positions match the board", tb.entries.all { stats.position("season-1", RatingType.TEAM_ELO, it.subjectId, 0) == it.position })

                // ── Statistics from history ──
                val alphaPts = stats.points(RatingType.PLAYER_ELO, JAVA.toString(), "season-1")
                val alphaSum = RankedStatistics.summarize(alphaPts, 1000)
                check("player stats: only rated matches count (test, cancelled, unrated challenge and pending excluded)", alphaPts.map { it.matchId } == listOf(m1, m2, m3))
                check("player stats: W 2 / L 1, 66.7%, streak 0 (best 2)", alphaSum.wins == 2 && alphaSum.losses == 1 && alphaSum.winPercent == "66.7%" && alphaSum.currentStreak == 0 && alphaSum.bestStreak == 2)
                val row = stats.entry("season-1", RatingType.PLAYER_ELO, JAVA.toString())!!
                check("player stats agree with the rating row (current, wins, losses, matches)", alphaSum.current == row.rating && alphaSum.wins == row.wins && alphaSum.losses == row.losses && alphaSum.matches == row.matches)
                check("peak Elo is the highest committed value", alphaSum.peak == alphaPts.maxOf { it.new } && alphaSum.peak > alphaSum.current)
                check("Bedrock and Java teammates have identical stats (UUID-keyed)", RankedStatistics.summarize(stats.points(RatingType.PLAYER_ELO, BEDROCK.toString(), "season-1"), 1000) == alphaSum)
                val charlieTeam = RankedStatistics.summarize(stats.points(RatingType.TEAM_ELO, charlie.first, "season-1"), 1000)
                check("team stats: Charlie L, L, W -> streak 1, best 1, W 1 / L 2", charlieTeam.wins == 1 && charlieTeam.losses == 2 && charlieTeam.currentStreak == 1 && charlieTeam.bestStreak == 1)
                check("team stats agree with the Team Elo row", charlieTeam.current == stats.entry("season-1", RatingType.TEAM_ELO, charlie.first)!!.rating)

                // ── Match history ──
                val hist = stats.playerHistory(JAVA, 0, 10)
                check("history: rated matches and the unrated challenge, newest first; no test or cancelled match",
                    hist.lines.map { it.matchId } == listOf(challenge, m3, m2, m1) && hist.total == 4)
                val ch = hist.lines.first()
                check("history: the unrated challenge is marked unrated, with its reason and no Elo change", ch.rated == RankedStatsStore.RatedState.UNRATED && ch.kind == "CHALLENGE" && ch.unratedReason != null && ch.playerDelta == null && ch.teamDelta == null)
                val h3 = hist.lines[1]
                check("history: a loss shows both Elo changes from the player's side", h3.outcome == RatingOutcome.LOSS && h3.playerDelta!! < 0 && h3.teamDelta!! < 0 && h3.opponentTeamName == "Bravo" && h3.ownTeamName == "Alpha")
                check("history: arena name, size and duration from the match record", h3.arena == "Duel Pit" && h3.size == 2 && h3.durationMillis == 800L)
                val hPaged = stats.playerHistory(JAVA, 1, 3)
                check("history pagination: page 2 of 3-per-page holds the oldest", hPaged.page == 1 && hPaged.pages == 2 && hPaged.lines.map { it.matchId } == listOf(m1))
                check("history: a completed match without a committed rating is 'pending' (not counted in stats)",
                    stats.playerHistory(c[0], 0, 10).lines.first().let { it.matchId == pending && it.rated == RankedStatsStore.RatedState.PENDING && it.playerDelta == null })
                check("team history by stable id", stats.teamHistory(alpha.first, 0, 10).lines.map { it.matchId } == listOf(challenge, m3, m2, m1))

                // A rated challenge (when challenges are rated) is counted by its classification.
                val ratedChallenge = play(ranked, "season-1", alpha, delta, a, d, Side.A, 19_000_000, MatchKind.CHALLENGE)
                check("challenge with rate-challenges on: rated", rate(ratedChallenge, POLICY_CHALLENGES) is Finalized.Rated)
                check("...and counted in the stats", stats.points(RatingType.PLAYER_ELO, JAVA.toString(), "season-1").last().matchId == ratedChallenge)
                check("duplicate rating of the same match: nothing counted twice", rate(ratedChallenge, POLICY_CHALLENGES) is Finalized.AlreadyProcessed &&
                    stats.points(RatingType.PLAYER_ELO, JAVA.toString(), "season-1").count { it.matchId == ratedChallenge } == 1 && stats.playerHistory(JAVA, 0, 50).lines.count { it.matchId == ratedChallenge } == 1)

                // Invalid records never appear: mark the pending match's result INVALID as the rating service would.
                db.execute("INSERT INTO lifesteal_ranked_rating_results (match_id, transaction_id, status, reason, attempts, created_at, processed_at) VALUES (?, ?, 'INVALID', 'check', 1, 0, 0)", pending, "elo:$pending")
                check("an INVALID match is excluded from history", stats.playerHistory(c[0], 0, 10).lines.none { it.matchId == pending })

                // ── Membership, leadership, disbanding, recreation ──
                val beforeTeam = stats.entry("season-1", RatingType.TEAM_ELO, alpha.first)!!.rating
                val beforeJava = stats.entry("season-1", RatingType.PLAYER_ELO, JAVA.toString())!!.rating
                val newbie = UUID(9, 9)
                check("a new member joins Alpha", teams.invite(LifestealTeamStore.Actor(a[0], "lead"), newbie, 5, 100, 60_000) !is LifestealTeamStore.InviteResult.NotInTeam &&
                    teams.accept(newbie, "newbie", "Alpha", 5, 101) !is LifestealTeamStore.AcceptResult.NoInvite)
                check("...and inherits no rating", stats.entry("season-1", RatingType.PLAYER_ELO, newbie.toString()) == null)
                teams.transfer(LifestealTeamStore.Actor(a[0], "lead"), null, newbie, 102)
                check("leadership transfer: Team Elo unchanged, new leader shown", stats.entry("season-1", RatingType.TEAM_ELO, alpha.first)!!.let { it.rating == beforeTeam && it.leader == newbie })
                teams.leave(JAVA, "java", 103)
                check("a member leaving keeps their Player Elo; the team keeps its Team Elo",
                    stats.entry("season-1", RatingType.PLAYER_ELO, JAVA.toString())!!.rating == beforeJava && stats.entry("season-1", RatingType.TEAM_ELO, alpha.first)!!.rating == beforeTeam)
                check("Team Elo is never derived from members (the row is keyed by team id)", stats.entry("season-1", RatingType.TEAM_ELO, alpha.first)!!.subjectId == alpha.first)

                check("Delta disbanded", teams.disband(LifestealTeamStore.Actor(null, "staff", admin = true), delta.first, 104) !is LifestealTeamStore.DisbandResult.TeamGone)
                val afterDisband = stats.page("season-1", RatingType.TEAM_ELO, 0, 0, 100)
                check("disbanded team: excluded from the team board and has no place", afterDisband.entries.none { it.subjectId == delta.first } && afterDisband.total == 3 &&
                    stats.position("season-1", RatingType.TEAM_ELO, delta.first, 0) == null)
                check("disbanded team: its history and rating rows are kept", stats.points(RatingType.TEAM_ELO, delta.first, "season-1").isNotEmpty() &&
                    stats.teamHistory(delta.first, 0, 10).total > 0 && stats.teamStatus(delta.first) == "DISBANDED" &&
                    (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_ranked_ratings WHERE subject_id = ?", delta.first) { it.getInt("n") } ?: 0) == 1)
                check("disbanding never touches the members' Player Elo", stats.entry("season-1", RatingType.PLAYER_ELO, d[0].toString()) != null)
                val reborn = "e5e5e5e5-0000-4000-8000-000000000005"
                check("a new team may take the old name", teams.create(reborn, "Delta", UUID(8, 8), "reborn", 105) is LifestealTeamStore.CreateResult.Created)
                check("...and does not inherit the disbanded team's Elo or place", stats.entry("season-1", RatingType.TEAM_ELO, reborn) == null &&
                    stats.position("season-1", RatingType.TEAM_ELO, reborn, 0) == null && stats.points(RatingType.TEAM_ELO, reborn, "season-1").isEmpty())

                // ── Seasons ──
                val s2 = play(ranked, "season-2", bravo, charlie, b, c, Side.A, 30_000_000)
                check("season-2 match rated", rate(s2) is Finalized.Rated)
                check("season boards are separate", stats.page("season-2", RatingType.PLAYER_ELO, 0, 0, 100).total == 4 && stats.page("season-1", RatingType.PLAYER_ELO, 0, 0, 100).total == 8)
                check("season-2 starts from the initial rating; season-1 stays as it was", stats.entry("season-2", RatingType.PLAYER_ELO, b[0].toString())!!.rating == 1016 &&
                    stats.points(RatingType.PLAYER_ELO, b[0].toString(), "season-1").size == 3)
                check("seasons a subject played are kept", stats.seasonsOf(RatingType.PLAYER_ELO, b[0].toString()) == listOf("season-1", "season-2"))

                // ── Consistency ──
                check("consistency: a consistent season reports nothing", stats.consistency("season-1").isEmpty() && stats.consistency("season-2").isEmpty())
                check("statistics reads never write (row counts unchanged by every read above)",
                    (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_ranked_rating_history") { it.getInt("n") } ?: 0) == 6 * 5 + 6 + 6)
                db.execute("UPDATE lifesteal_ranked_ratings SET wins = wins + 5 WHERE season_id = 'season-1' AND subject_id = ?", JAVA.toString())
                val issues = stats.consistency("season-1")
                check("consistency: a counter that disagrees with history is reported", issues.size == 1 && issues[0].contains(JAVA.toString()))
                check("consistency check never repairs (the row stays as it is)", stats.entry("season-1", RatingType.PLAYER_ELO, JAVA.toString())!!.wins == row.wins + 1 + 5)
            } finally { db.shutdown() }
        }
        // Database failures: a closed database throws instead of answering with made-up numbers.
        DatabaseManager(dir, quietLogger()).let { db ->
            db.start()
            val stats = RankedStatsStore(db)
            check("restart: the board is read from the database again (nothing kept in memory)", stats.page("season-1", RatingType.PLAYER_ELO, 0, 0, 100).total == 8)
            db.shutdown()
            check("database closed: board read throws (the manager reports it, never fabricates)", throws { stats.page("season-1", RatingType.PLAYER_ELO, 0, 0, 10) })
            check("database closed: stats read throws", throws { stats.points(RatingType.PLAYER_ELO, JAVA.toString(), "season-1") })
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/ranked/stats/LifestealRankedStatsManager.kt")
    val store = source("lifesteal/ranked/stats/RankedStatsStore.kt")
    val menu = source("lifesteal/ranked/stats/RankedStatsMenu.kt")
    val queueMenu = source("lifesteal/ranked/queue/RankedQueueMenu.kt")
    val command = source("lifesteal/ranked/queue/RankedCommand.kt")
    val rating = source("lifesteal/ranked/rating/LifestealRatingManager.kt")
    val config = source("lifesteal/LifestealConfigManager.kt")
    val joshymc = source("Joshymc.kt")
    check("Survival isolation: stats only run where the rating service is hosted (Lifesteal)",
        manager.contains("val hosted: Boolean get() = plugin.lifestealRatingManager.hosted") && manager.indexOf("if (!hosted || !plugin.lifestealRatingManager.ready) return") in 0 until manager.indexOf("store.createIndexes()"))
    check("Survival isolation: /ranked stays the one LIFESTEAL-scoped command (no new command)", GamemodeAssignments.COMMANDS["ranked"] == GamemodeScope.LIFESTEAL &&
        !File("src/main/resources/plugin.yml").readText().contains(Regex("(?m)^  (rankedtop|rankedstats|rankedleaderboard|divisions):")) &&
        GamemodeAssignments.COMMANDS["leaderboard"] != GamemodeScope.LIFESTEAL)
    check("Survival isolation: divisions/leaderboards aren't read where Lifesteal isn't hosted", config.indexOf("divisions = DivisionConfig.Result(null, DivisionConfig.Status.DISABLED") in 0 until config.indexOf("plugin.configFile(CONFIG_FILE)"))
    check("Survival isolation: statistics code never touches Survival teams, economy, inventories or leaderboards",
        File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/stats").listFiles()!!.none { f ->
            val t = f.readText()
            t.contains("plugin.teamManager") || t.contains("economyManager") || t.contains("leaderboardManager") || t.contains(".inventory.") || t.contains("setItem(0,")
        })
    check("the statistics store is read-only (no INSERT/UPDATE/DELETE, no table of its own)",
        !Regex("\\b(INSERT|UPDATE|DELETE|CREATE TABLE)\\b").containsMatchIn(store) && store.contains("CREATE INDEX IF NOT EXISTS"))
    check("no statistics file writes ratings or computes Elo",
        File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/stats").listFiles()!!.none { f -> val t = f.readText(); t.contains("finalize(") || t.contains("EloRating.") || t.contains("/ 400.0") })
    check("no command awards or sets ratings, divisions or statistics", !source("lifesteal/ranked/stats/RankedStatsAdminCommand.kt").contains("\"set\"") && !source("lifesteal/ranked/stats/RankedStatsAdminCommand.kt").contains("\"award\""))
    check("no rewards, season resets or rollovers were added",
        File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/stats").listFiles()!!.none { f -> val t = f.readText(); t.contains("fun reset") || t.contains("fun reward") || t.contains("fun rollover") || t.contains("deposit(") })
    check("promotion/demotion lines are part of the once-only, committed-only result notice", rating.contains("plugin.lifestealRankedStatsManager.changeLines(n.row.previous, n.row.new") &&
        rating.indexOf("private fun sendNotice") < rating.indexOf("changeLines(") && rating.contains("if (!store.claimNotice(row.id)) return@mapNotNull null"))
    check("committed ratings drop every cached leaderboard page", rating.contains("plugin.lifestealRankedStatsManager.onRatingsCommitted()") &&
        rating.indexOf("is RatingStore.Finalized.Rated ->") < rating.indexOf("onRatingsCommitted()") && manager.contains("gen == generation.get()"))
    check("bounded leaderboard cache", manager.contains("size > PAGE_CACHE_SIZE"))
    check("menus never read the database themselves (loads run async in the manager)",
        !menu.contains("RankedStatsStore(") && !menu.contains("databaseManager") && manager.contains("runTaskAsynchronously"))
    check("menus re-check permission and Lifesteal on every open", menu.contains("private fun guard(player: Player)") && menu.contains("LifestealRankedQueueManager.PERMISSION_USE"))
    check("menus show others' statistics only with stats.others", menu.contains("LifestealRatingManager.PERMISSION_STATS_OTHERS"))
    check("menus use no new resource pack assets (no item models / custom icons)", !menu.contains("setItemModel") && !menu.contains("GuiIcon") && !queueMenu.contains("setItemModel"))
    check("main menu keeps queue, challenges and confirmations, and adds the statistics row",
        queueMenu.contains("queue.accept(p, token)") && queueMenu.contains("openChallenges(p)") && queueMenu.contains("statsRow(gui, player, team)") && queueMenu.contains("RankedMenuLayout.MAIN_SIZE"))
    check("/ranked top|teamstats|divisions|season|history are subcommands", command.contains("\"top\", \"leaderboard\", \"lb\" -> top(") && command.contains("\"teamstats\", \"team\" -> teamStats(") &&
        command.contains("\"divisions\", \"division\" -> statsView.divisions(") && command.contains("\"season\" -> statsView.season(") && command.contains("\"history\" -> history("))
    check("other players are resolved safely", command.contains("plugin.playerResolver.resolve(name)") && !File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked/stats").listFiles()!!.any { it.readText().contains("getOfflinePlayer(name") })
    check("manager wired: created, refreshed after the rating service, reloaded, stopped",
        joshymc.contains("lifestealRankedStatsManager = com.liam.joshymc.lifesteal.ranked.stats.LifestealRankedStatsManager(this)") &&
            joshymc.indexOf("lifestealRatingManager.refresh()") < joshymc.indexOf("lifestealRankedStatsManager.refresh()") &&
            joshymc.contains("safe(\"lifestealRankedStatsManager.refresh\")") && joshymc.contains("shutdown.step(\"lifestealRankedStatsManager.stop\""))
    check("admin diagnostics are under the existing admin-gated /lifesteal admin ranked", source("lifesteal/ranked/LifestealRankedCommand.kt").contains("\"stats\" -> statsAdmin.execute(sender, a)"))
    check("docs exist", File("docs/lifesteal-ranked-stats.md").readText().contains("Manual tests"))
}
