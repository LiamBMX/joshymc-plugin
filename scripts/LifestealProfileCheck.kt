// Checks the Lifesteal profiles, statistics, achievements and leaderboards (issue #1429): which PvP
// deaths count (legitimate outcomes, same-victim cooldown, alt accounts, streaks), season windows
// (current, previous, all time, transitions), leaderboard ranking and paging, the achievement
// config (bundled file, forbidden rewards, tiers, divisions, seasonal rules), progress bars and
// sorting, and against a real SQLite database with the real heart and progression tables: offline
// profiles, seasonal vs lifetime numbers, activity counters (only up, per season), one completion
// per tier and period, one winner among concurrent claims, reward snapshots and delivery
// references, crash recovery and restart persistence, settings validation, GUI layout and the
// wiring (Lifesteal only, Survival untouched). The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-profile-check.init.gradle.kts lifestealProfileCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (menus, real kills, block breaks)
// is NOT covered here; see docs/lifesteal-profiles.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.profile.AchievementCategory
import com.liam.joshymc.lifesteal.profile.AchievementDef
import com.liam.joshymc.lifesteal.profile.AchievementParser
import com.liam.joshymc.lifesteal.profile.AchievementPeriod
import com.liam.joshymc.lifesteal.profile.AchievementReward
import com.liam.joshymc.lifesteal.profile.AchievementRules
import com.liam.joshymc.lifesteal.profile.BoardCategory
import com.liam.joshymc.lifesteal.profile.Leaderboards
import com.liam.joshymc.lifesteal.profile.ProfileCalculator
import com.liam.joshymc.lifesteal.profile.ProfileConfig
import com.liam.joshymc.lifesteal.profile.ProfileMenuLayout
import com.liam.joshymc.lifesteal.profile.ProfileStat
import com.liam.joshymc.lifesteal.profile.ProfileStore
import com.liam.joshymc.lifesteal.profile.PvpCounting
import com.liam.joshymc.lifesteal.profile.PvpRow
import com.liam.joshymc.lifesteal.profile.PvpSource
import com.liam.joshymc.lifesteal.profile.TeamDivisionRule
import com.liam.joshymc.lifesteal.profile.CoinEligibility
import com.liam.joshymc.lifesteal.profile.LinkedAccounts
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.profile.SeasonRow
import com.liam.joshymc.lifesteal.profile.SeasonWindow
import com.liam.joshymc.lifesteal.profile.Seasons
import com.liam.joshymc.lifesteal.profile.StatValues
import com.liam.joshymc.lifesteal.progression.ProgressionStore
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.tutorial.TutorialRules
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

private const val MIN = 60_000L
private const val HOUR = 60 * MIN
private const val DAY = 24 * HOUR

private val A = UUID.fromString("00000000-0000-0000-0000-00000000000a")
private val B = UUID.fromString("00000000-0000-0000-0000-00000000000b")
private val C = UUID.fromString("00000000-0000-0000-0000-00000000000c")
private val D = UUID.fromString("00000000-0000-0000-0000-00000000000d")

private val catalog = object : PurchaseRewards.Catalog {
    override fun crateExists(id: String) = id == "vote"
    override fun moneyVoucherExists(id: String) = false
    override fun chatTagVoucherExists(id: String) = id == "joshymc"
}

private fun ctx(coins: Boolean = true, divisions: Boolean = true, playtime: Boolean = true) = AchievementParser.Context(
    catalog, coins,
    if (divisions) ({ id: String -> listOf("bronze", "silver", "gold", "platinum", "diamond", "master", "grandmaster").indexOf(id).takeIf { it >= 0 }?.plus(1) }) else null,
    playtime,
)

fun main() {
    pvp()
    seasons()
    boards()
    config()
    rules()
    database()
    concurrency()
    remediation()
    layout()
    wiring()
    println("Lifesteal profile checks passed ($checks checks).")
    exitProcess(0)
}

private fun pvp() {
    val cd = HOUR
    val rows = listOf(
        PvpRow(A, B, "TRANSFERRED", 0),
        PvpRow(A, B, "TRANSFERRED", 10 * MIN),          // same victim inside the cooldown: death counts, kill doesn't
        PvpRow(A, C, "VICTIM_AT_MINIMUM", 20 * MIN),    // legitimate, no heart moved
        PvpRow(A, D, "TEAMMATES", 30 * MIN),            // never counts
        PvpRow(A, D, "GRACE_PERIOD", 31 * MIN),
        PvpRow(A, D, "COOLDOWN", 32 * MIN),
        PvpRow(A, D, "NOT_LAUNCHED", 33 * MIN),
        PvpRow(A, D, "ALLIES", 34 * MIN),
        PvpRow(A, D, "UNAVAILABLE", 35 * MIN),
        PvpRow(A, B, "KILLER_AT_MAXIMUM", 61 * MIN),    // past the cooldown: counts
        PvpRow(B, A, "TRANSFERRED", 70 * MIN),          // A dies: streak ends
        PvpRow(A, C, "TRANSFERRED", 200 * MIN),
    )
    val totals = PvpCounting.derive(rows, cd, emptySet())
    val a = totals[A]!!
    check("kills: legitimate only, same victim once per cooldown", a.kills == 4L)
    check("deaths: every legitimate death", a.deaths == 1L && totals[B]!!.deaths == 3L && totals[C]!!.deaths == 2L)
    check("refused outcomes never count (teammates, allies, grace, cooldown, launch, unavailable)", totals[D] == null)
    check("unique victims", a.uniqueVictims == 2L)
    check("best streak 3, then reset by a PvP death", a.bestStreak == 3L && a.currentStreak == 1L)
    check("victim kills count for them", totals[B]!!.kills == 1L)
    check("cooldown 0 counts every legitimate kill", PvpCounting.derive(rows, 0, emptySet())[A]!!.kills == 5L)
    val linked = setOf(PvpCounting.pair(B, A))
    val alt = PvpCounting.derive(rows, cd, linked)
    check("alt accounts (shared connection) count for nothing, both ways", alt[A]!!.kills == 2L && alt[A]!!.deaths == 0L && alt[B] == null)
    check("pair is unordered", PvpCounting.pair(A, B) == PvpCounting.pair(B, A))
    check("self kills ignored", PvpCounting.derive(listOf(PvpRow(A, A, "TRANSFERRED", 0)), cd, emptySet()).isEmpty())
    check("repeated processing of one death is impossible upstream; derive never dedupes by guess",
        PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0), PvpRow(A, B, "TRANSFERRED", 0)), cd, emptySet())[A]!!.kills == 1L)
    val window = SeasonWindow("season-1", 60 * MIN, 150 * MIN, false)
    val inside = PvpCounting.inWindow(rows, window)
    check("window: start inclusive, end exclusive", inside.first().at == 61 * MIN && inside.all { it.at in (60 * MIN) until (150 * MIN) })
    check("K/D text", PvpCounting.kdText(3, 0) == "3.00" && PvpCounting.kdText(5, 2) == "2.50")
    check("K/D needs minimum participation", PvpCounting.kdScaled(3, 1, 20) == null && PvpCounting.kdScaled(30, 10, 20) == 300L && PvpCounting.kdScaled(20, 0, 20) == 2000L)
}

private fun seasons() {
    val windows = Seasons.windows(listOf(SeasonRow("season-2", 1000, "ACTIVE"), SeasonRow("season-1", 100, "COMPLETED")))
    check("windows sorted by launch", windows.map { it.id } == listOf("season-1", "season-2"))
    check("a finished season ends at the next launch", windows[0].end == 1000L && !windows[0].active)
    check("the active season never ends", windows[1].end == null && windows[1].active)
    check("current/previous", Seasons.current(windows)?.id == "season-2" && Seasons.previous(windows).map { it.id } == listOf("season-1"))
    check("no season: preseason counters", Seasons.counterSeason(null) == Seasons.PRESEASON && Seasons.counterSeason(windows[1]) == "season-2")
    check("no seasons at all", Seasons.current(Seasons.windows(emptyList())) == null)
}

private fun boards() {
    val ranked = Leaderboards.rank(mapOf(A to 10, B to 20, C to 10, D to 0))
    check("ranking: highest first, ties share a place, zero left out", ranked.map { it.position } == listOf(1, 2, 2) && ranked[0].uuid == B && ranked.none { it.uuid == D })
    check("ties ordered by uuid (stable)", ranked[1].uuid == A && ranked[2].uuid == C)
    check("minimum 0 keeps zeros", Leaderboards.rank(mapOf(D to 0), minimumValue = 0).size == 1)
    check("own placement", Leaderboards.positionOf(ranked, C)?.position == 2 && Leaderboards.positionOf(ranked, D) == null)
    val many = Leaderboards.rank((1..25).associate { UUID.randomUUID() to it.toLong() })
    check("paging", Leaderboards.pages(25, 10) == 3 && Leaderboards.page(many, 2, 10).size == 5 && Leaderboards.page(many, 9, 10).size == 5 && Leaderboards.pages(0, 10) == 1)
    check("playtime is all-time only, Elo boards are season only (the existing Ranked boards)",
        !BoardCategory.PLAYTIME.seasonal && BoardCategory.PLAYER_ELO.ranked && !BoardCategory.PLAYER_ELO.allTime && BoardCategory.TEAM_ELO.ranked)
}

@Suppress("UNCHECKED_CAST")
private fun config() {
    val root = File("src/main/resources/lifesteal-profiles.yml").inputStream().use { Yaml().load<Map<String, Any?>>(it) }
    val parsed = ProfileConfig.parse(root)
    check("bundled config parses without problems", parsed.problems.isEmpty())
    check("bundled config enabled", parsed.settings.enabled && parsed.settings.achievementsEnabled && parsed.settings.boardsEnabled)
    check("bundled leaderboards", parsed.settings.categories == BoardCategory.entries.toList() && parsed.settings.kdMinimumFights == 20)
    val bad = ProfileConfig.parse(mapOf("pvp" to mapOf("same-victim-cooldown-minutes" to -5), "leaderboards" to mapOf("categories" to listOf("kills", "nope"))))
    check("bad values: reported, default used", bad.problems.size == 2 && bad.settings.pairCooldownMillis == HOUR)
    check("disabled", !ProfileConfig.parse(mapOf("enabled" to false)).settings.enabled)

    val section = root["achievements"] as Map<String, Any?>
    val result = AchievementParser.parse(section, ctx())
    check("bundled achievements all load", result.problems.isEmpty() && result.achievements.size == section.size)
    check("every category has an achievement", AchievementCategory.entries.all { c -> result.achievements.any { it.category == c } })
    check("single and tiered achievements", result.achievements.any { it.single } && result.achievements.any { it.tiers.size == 4 })
    check("seasonal achievements exist", result.achievements.any { it.period == AchievementPeriod.SEASONAL })
    check("division tiers resolved to indexes", result.achievements.first { it.id == "climber" }.tiers.map { it.target } == listOf(4L, 5L, 6L))
    check("no bundled achievement rewards dying", result.achievements.none { it.stat == ProfileStat.PVP_DEATHS })

    val noCoins = AchievementParser.parse(section, ctx(coins = false, playtime = false))
    check("Survival economy: coin rewards left out, achievements kept", noCoins.achievements.none { d -> d.tiers.any { t -> t.rewards.any { it is AchievementReward.Store && it.spec.kind == PurchaseRewards.Kind.COINS } } } &&
        noCoins.achievements.any { it.id == "slayer" })
    check("Survival playtime: playtime achievements refused", noCoins.achievements.none { it.stat == ProfileStat.PLAYTIME_HOURS } && noCoins.problems.any { it.contains("veteran") })
    val noDivisions = AchievementParser.parse(section, ctx(divisions = false))
    check("divisions off: division achievements refused, others load", noDivisions.achievements.none { it.stat == ProfileStat.PLAYER_DIVISION } && noDivisions.achievements.any { it.id == "slayer" })

    fun one(map: Map<String, Any?>) = AchievementParser.parse(mapOf("x" to map), ctx())
    val base = mapOf("name" to "X", "category" to "combat", "stat" to "pvp-kills", "tiers" to listOf(mapOf("target" to 1)))
    check("minimal achievement", one(base).achievements.size == 1)
    for (kind in listOf("rank scout", "rankvoucher scout 1", "creditvoucher 5 1", "moneyvoucher a 1", "hearts 1", "give diamond")) {
        check("reward '$kind' refused", one(base + ("tiers" to listOf(mapOf("target" to 1, "rewards" to listOf(kind))))).achievements.isEmpty())
    }
    val allowed = one(base + ("tiers" to listOf(mapOf("target" to 1, "rewards" to listOf("coins 5", "credits 2", "tokens 3", "cratekey vote 1", "title &cX")))))
    if (allowed.problems.isNotEmpty()) System.err.println(allowed.problems)
    check("allowed rewards", allowed.achievements.singleOrNull()?.tiers?.single()?.rewards?.size == 5)
    val more = one(base + ("tiers" to listOf(mapOf("target" to 1, "rewards" to listOf("chattagvoucher joshymc 1", "border red")))))
    check("chat tag voucher and border rewards", more.achievements.singleOrNull()?.tiers?.single()?.rewards?.size == 2)
    check("unknown crate refused", one(base + ("tiers" to listOf(mapOf("target" to 1, "rewards" to listOf("cratekey nope 1"))))).achievements.isEmpty())
    check("unknown border refused", one(base + ("tiers" to listOf(mapOf("target" to 1, "rewards" to listOf("border rainbow"))))).achievements.isEmpty())
    check("pvp-deaths refused", one(base + ("stat" to "pvp-deaths")).achievements.isEmpty())
    check("seasonal playtime refused", one(base + ("stat" to "playtime-hours") + ("type" to "seasonal")).achievements.isEmpty())
    check("seasonal team-joined refused", one(base + ("stat" to "team-joined") + ("type" to "seasonal")).achievements.isEmpty())
    check("targets must increase", one(base + ("tiers" to listOf(mapOf("target" to 5), mapOf("target" to 5)))).achievements.isEmpty())
    check("target >= 1", one(base + ("tiers" to listOf(mapOf("target" to 0)))).achievements.isEmpty())
    check("bad id refused", AchievementParser.parse(mapOf("Bad Id!" to base), ctx()).achievements.isEmpty())
    check("unknown category/stat refused", one(base + ("category" to "x")).achievements.isEmpty() && one(base + ("stat" to "x")).achievements.isEmpty())
    check("hidden visibility", one(base + ("visibility" to "hidden")).achievements.single().visibility.id == "hidden")
    check("disabled achievement left out", one(base + ("enabled" to false)).achievements.isEmpty())
    check("a broken achievement doesn't remove the others", AchievementParser.parse(mapOf("good" to base, "bad" to base + ("stat" to "?")), ctx()).achievements.map { it.id } == listOf("good"))
}

private fun def(id: String, stat: ProfileStat, period: AchievementPeriod, vararg targets: Long): AchievementDef =
    AchievementParser.parse(
        mapOf(id to mapOf("name" to id, "category" to "combat", "stat" to stat.id, "type" to period.id, "tiers" to targets.map { mapOf("target" to it, "rewards" to listOf("credits 1")) })),
        ctx(),
    ).achievements.single()

private fun rules() {
    val slayer = def("slayer", ProfileStat.PVP_KILLS, AchievementPeriod.PERMANENT, 10, 50, 100)
    val season = def("season", ProfileStat.PVP_KILLS, AchievementPeriod.SEASONAL, 5)
    val life = StatValues(mapOf(ProfileStat.PVP_KILLS to 60L))
    val now = StatValues(mapOf(ProfileStat.PVP_KILLS to 6L))
    val reached = AchievementRules.newlyReached(listOf(slayer, season), now, life, "season-1", emptySet())
    check("a jump records every reached tier", reached.map { it.first.id to it.second.number } == listOf("slayer" to 1, "slayer" to 2, "season" to 1))
    val recorded = setOf(AchievementRules.Key("slayer", 1, Seasons.PERMANENT), AchievementRules.Key("slayer", 2, Seasons.PERMANENT), AchievementRules.Key("season", 1, "season-1"))
    check("recorded tiers never again", AchievementRules.newlyReached(listOf(slayer, season), now, life, "season-1", recorded).isEmpty())
    check("a new season starts seasonal achievements over (old rows kept)", AchievementRules.newlyReached(listOf(season), now, life, "season-2", recorded).single().second.number == 1)
    check("no season: seasonal can't progress", AchievementRules.newlyReached(listOf(season), null, life, null, emptySet()).isEmpty())
    check("seasonal uses season numbers, permanent lifetime", AchievementRules.value(season, now, life) == 6L && AchievementRules.value(slayer, now, life) == 60L)
    check("bar never full before the target", AchievementRules.bar(99, 100).contains("□") && AchievementRules.bar(99, 100).endsWith("99%") && AchievementRules.bar(100, 100).endsWith("100%") && !AchievementRules.bar(100, 100).contains("□"))
    check("bar empty", AchievementRules.bar(0, 10).startsWith("□"))
    check("shown tier = first unclaimed", AchievementRules.shownTier(slayer, mapOf(1 to "CLAIMED")).number == 2 && AchievementRules.shownTier(slayer, mapOf(1 to "CLAIMED", 2 to "CLAIMED", 3 to "CLAIMED")).number == 3)
    check("states", AchievementRules.state(slayer, slayer.tiers[0], 0, null) == AchievementRules.State.LOCKED &&
        AchievementRules.state(slayer, slayer.tiers[0], 5, null) == AchievementRules.State.IN_PROGRESS &&
        AchievementRules.state(slayer, slayer.tiers[0], 5, "COMPLETED") == AchievementRules.State.CLAIMABLE &&
        AchievementRules.state(slayer, slayer.tiers[0], 5, "CLAIMED") == AchievementRules.State.CLAIMED)
    val rows = listOf(
        AchievementRules.Row(slayer, slayer.tiers[0], 9, AchievementRules.State.IN_PROGRESS),
        AchievementRules.Row(season, season.tiers[0], 5, AchievementRules.State.CLAIMABLE),
    )
    check("sort: claimable first", AchievementRules.sort(rows, AchievementRules.Sort.CLAIMABLE_FIRST).first().def.id == "season")
    check("sort cycles", AchievementRules.Sort.entries.all { s -> generateSequence(s) { it.next() }.take(AchievementRules.Sort.entries.size + 1).last() == s })
    val key = AchievementRules.Key("slayer", 2, Seasons.PERMANENT)
    check("key text round trip", AchievementRules.parseKey(AchievementRules.keyText(key)) == key && AchievementRules.parseKey("a:b") == null)
    val refs = (0..4).map { AchievementRules.rewardReference(key, it) }
    check("delivery references: unique per reward, valid store references", refs.toSet().size == 5 && refs.all { PurchaseRewards.parseReference(it) is PurchaseRewards.Result.Ok })
    check("seasonal claims get their own references", AchievementRules.rewardReference(AchievementRules.Key("s", 1, "season-1"), 0) != AchievementRules.rewardReference(AchievementRules.Key("s", 1, "season-2"), 0))
    val rewards = slayer.tiers[0].rewards + AchievementReward.Title("&cSlayer") + AchievementReward.Border("red")
    check("reward snapshot round trip", ProfileStore.decodeRewards(ProfileStore.encodeRewards(rewards)) == rewards)
    check("snapshot can't smuggle a rank", ProfileStore.decodeRewards("rank|scout|1\nrankvoucher|scout|1").isEmpty())
}

private class World(val dir: File) {
    val db = DatabaseManager(dir, quietLogger()).also { it.start() }
    val store = ProfileStore(db)
    init {
        HeartStore(db).createTables()
        ProgressionStore(db).createTables()
        db.createTable("CREATE TABLE IF NOT EXISTS lifesteal_bounty_payouts (id TEXT PRIMARY KEY, killer TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.createTable("CREATE TABLE IF NOT EXISTS lifesteal_ranked_rating_history (id INTEGER PRIMARY KEY AUTOINCREMENT, match_id TEXT, season_id TEXT, rating_type TEXT, subject_id TEXT, team_id TEXT, new INTEGER, outcome TEXT)")
        db.createTable("CREATE TABLE IF NOT EXISTS player_connections (uuid TEXT, connection_hash TEXT, last_seen INTEGER)")
        db.createTable("CREATE TABLE IF NOT EXISTS playtime (uuid TEXT PRIMARY KEY, total_seconds INTEGER DEFAULT 0, last_join INTEGER)")
        db.createTable("CREATE TABLE IF NOT EXISTS lifesteal_team_membership_history (id INTEGER PRIMARY KEY AUTOINCREMENT, team_id TEXT, uuid TEXT, joined_at INTEGER, left_at INTEGER, reason TEXT)")
        store.createTables()
    }
    private var tx = 0
    fun kill(killer: UUID, victim: UUID, at: Long, outcome: String = "TRANSFERRED") =
        db.execute("INSERT INTO lifesteal_heart_transactions (id, dedupe_key, type, outcome, killer, victim, created_at) VALUES (?, ?, 'PVP', ?, ?, ?, ?)",
            "t${++tx}", "pvp:x:${tx}", outcome, killer.toString(), victim.toString(), at)
    fun hearts(uuid: UUID, hearts: Int) = db.execute("INSERT OR REPLACE INTO lifesteal_hearts (uuid, hearts, created_at, updated_at) VALUES (?, ?, 0, 0)", uuid.toString(), hearts)
    fun calc(playtime: Boolean = true, team: TeamDivisionRule = TeamDivisionRule.DEFAULT, rules: PvpCounting.Rules = PvpCounting.Rules.plain(HOUR)) =
        ProfileCalculator(store, rules, { _, r -> if (r >= 1200) 4 else if (r >= 1000) 3 else 1 }, playtime, team)
    fun kill(killer: UUID, victim: UUID, at: Long, outcome: String, key: String) =
        db.execute("INSERT INTO lifesteal_heart_transactions (id, dedupe_key, type, outcome, killer, victim, created_at) VALUES (?, ?, 'PVP', ?, ?, ?, ?)",
            "t${++tx}", key, outcome, killer.toString(), victim.toString(), at)
    fun active(uuid: UUID, millis: Long) = db.execute("INSERT OR REPLACE INTO lifesteal_heart_playtime (uuid, active_millis, updated_at) VALUES (?, ?, 0)", uuid.toString(), millis)
    fun history(match: String, player: UUID, team: String, outcome: String, teamRating: Int, season: String = "season-2") {
        db.execute("INSERT INTO lifesteal_ranked_rating_history (match_id, season_id, rating_type, subject_id, team_id, new, outcome) VALUES (?, ?, 'PLAYER_ELO', ?, ?, 1000, ?)", match, season, player.toString(), team, outcome)
        db.execute("INSERT OR IGNORE INTO lifesteal_ranked_rating_history (match_id, season_id, rating_type, subject_id, team_id, new, outcome) VALUES (?, ?, 'TEAM_ELO', ?, ?, ?, ?)", match, season, team, team, teamRating, outcome)
    }
}

private fun database() {
    val dir = Files.createTempDirectory("lsprofile").toFile()
    var w = World(dir)
    w.hearts(A, 12); w.hearts(B, 8)
    // Season 1 [1d, 10d), season 2 from 10d.
    w.db.execute("INSERT INTO lifesteal_seasons (season_id, launch_at, status, created_at, created_by, source, updated_at) VALUES ('season-1', ?, 'COMPLETED', 0, 'x', 'x', 0)", DAY)
    check("one active season", ProgressionStore(w.db).createSeason("season-2", 10 * DAY, "console", "test", 0))
    w.kill(A, B, 0)                        // preseason: lifetime only
    w.kill(A, B, 2 * DAY)                  // season 1
    w.kill(A, C, 3 * DAY)
    w.kill(B, A, 11 * DAY)                 // season 2
    w.kill(A, B, 12 * DAY)
    w.kill(A, B, 12 * DAY + MIN)           // inside cooldown
    w.kill(A, C, 13 * DAY, "TEAMMATES")    // refused
    w.db.execute("INSERT INTO lifesteal_bounty_payouts (id, killer, created_at) VALUES ('p1', ?, ?), ('p2', ?, ?)", A.toString(), 2 * DAY, A.toString(), 12 * DAY)
    w.db.execute("INSERT INTO lifesteal_ranked_rating_history (match_id, season_id, rating_type, subject_id, team_id, new, outcome) VALUES ('m1', 'season-2', 'PLAYER_ELO', ?, 't1', 1010, 'WIN'), ('m1', 'season-2', 'TEAM_ELO', 't1', 't1', 1250, 'WIN'), ('m2', 'season-2', 'PLAYER_ELO', ?, 't1', 990, 'LOSS')", A.toString(), A.toString())
    w.db.execute("INSERT INTO playtime (uuid, total_seconds) VALUES (?, ?)", A.toString(), 7 * 3600 + 59)
    w.active(A, 3 * HOUR + 59 * MIN)
    w.db.execute("INSERT INTO lifesteal_team_membership_history (team_id, uuid, joined_at, left_at, reason) VALUES ('t1', ?, 0, 1, 'LEFT')", A.toString())
    w.store.addCounters(A, "season-2", mapOf("ores-mined" to 5, "fish-caught" to 2), 0)
    w.store.addCounters(A, "season-2", mapOf("ores-mined" to 3), 1)
    w.store.addCounters(A, "season-1", mapOf("ores-mined" to 10), 0)
    w.store.addCounters(A, Seasons.PRESEASON, mapOf("ores-mined" to 1), 0)
    check("dimension once per season", w.store.recordDimension(A, "season-2", "NETHER", 0) && !w.store.recordDimension(A, "season-2", "NETHER", 1) && w.store.recordDimension(A, "season-1", "NETHER", 0))

    val p = w.calc().player(A, includePrevious = true)
    check("current season = season-2", p.current?.id == "season-2")
    check("season kills (cooldown, refused outcomes)", p.season!![ProfileStat.PVP_KILLS] == 1L && p.season!![ProfileStat.PVP_DEATHS] == 1L)
    check("lifetime kills (preseason included, cooldown is global)", p.lifetime[ProfileStat.PVP_KILLS] == 4L)
    check("previous season kept apart", p.previous.single().first.id == "season-1" && p.previous.single().second[ProfileStat.PVP_KILLS] == 2L && p.previous.single().second[ProfileStat.UNIQUE_VICTIMS] == 2L)
    check("bounties per season", p.season!![ProfileStat.BOUNTY_CLAIMS] == 1L && p.lifetime[ProfileStat.BOUNTY_CLAIMS] == 2L && p.previous.single().second[ProfileStat.BOUNTY_CLAIMS] == 1L)
    check("ranked from the rating history", p.season!![ProfileStat.RANKED_MATCHES] == 2L && p.season!![ProfileStat.RANKED_WINS] == 1L && p.lifetime[ProfileStat.RANKED_WINS] == 1L)
    check("divisions from peak ratings", p.season!![ProfileStat.PLAYER_DIVISION] == 3L && p.previous.single().second[ProfileStat.PLAYER_DIVISION] == 0L)
    check("team division: two matches are not enough participation by default (issue #1538)", p.season!![ProfileStat.TEAM_DIVISION] == 0L)
    check("team division with a lower participation rule", w.calc(team = TeamDivisionRule(2, 1)).player(A, false).season!![ProfileStat.TEAM_DIVISION] == 4L)
    check("counters per season, lifetime = sum", p.season!![ProfileStat.ORES_MINED] == 8L && p.previous.single().second[ProfileStat.ORES_MINED] == 10L && p.lifetime[ProfileStat.ORES_MINED] == 19L)
    check("dimensions per season", p.season!![ProfileStat.DIMENSIONS_EXPLORED] == 1L && p.lifetime[ProfileStat.DIMENSIONS_EXPLORED] == 1L)
    check("team membership (history)", p.lifetime[ProfileStat.TEAM_JOINED] == 1L)
    check("playtime hours = active Lifesteal playtime, not wall-clock (issue #1538)", p.lifetime[ProfileStat.PLAYTIME_HOURS] == 3L)
    check("playtime never read where it's Survival's", w.calc(playtime = false).player(A, false).lifetime[ProfileStat.PLAYTIME_HOURS] == 0L)
    check("offline profile: hearts and record", w.store.hearts(A) == 12 && w.store.hasHeartRecord(B) && !w.store.hasHeartRecord(D))
    val pc = w.calc().player(C, includePrevious = false)
    check("victim-only player", pc.lifetime[ProfileStat.PVP_DEATHS] == 1L && pc.lifetime[ProfileStat.PVP_KILLS] == 0L)
    // Alt link: A and B shared a connection -> their fights count for nothing.
    w.db.execute("INSERT INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, 'h', 0), (?, 'h', 0)", A.toString(), B.toString())
    check("alt link removes A-B fights", w.calc().player(A, false).lifetime[ProfileStat.PVP_KILLS] == 1L && w.store.linkedPairs().size == 1)
    check("linked accounts: one linear read agrees with the self-join", w.store.connectionHashes().linked(A, B) && w.store.connectionHashes(A).linked(B, A) && !w.store.connectionHashes().linked(A, C))
    check("board with linked accounts = board with linked pairs",
        w.calc().allPvp(null, w.store.pvpRows(), w.store.connectionHashes()) == w.calc().allPvp(null, w.store.pvpRows(), w.store.linkedPairs()))
    w.db.execute("DELETE FROM player_connections")

    check("counters only go up (trigger)", throws { w.db.execute("UPDATE lifesteal_profile_counters SET value = 0 WHERE uuid = ?", A.toString()) })
    check("negative counter refused", throws { w.db.execute("INSERT INTO lifesteal_profile_counters VALUES (?, 'x', 'y', -1, 0)", D.toString()) })
    w.store.addCounters(A, "season-2", mapOf("fish-caught" to 0, "mobs-killed" to -3), 2)
    check("zero/negative deltas ignored", w.store.counters(A, "season-2")[A]!!["mobs-killed"] == null && w.store.counters(A, "season-2")[A]!!["fish-caught"] == 2L)

    // Leaderboard sources
    val rows = w.store.pvpRows()
    val all = w.calc().allPvp(null, rows, w.store.linkedPairs())
    check("board: all players from one read", Leaderboards.rank(all.mapValues { it.value.kills }).first().uuid == A)
    check("board: season window", w.calc().allPvp(Seasons.current(Seasons.windows(w.store.seasons())), rows, emptySet())[A]!!.kills == 1L)
    check("board: ores", w.calc().all(ProfileStat.ORES_MINED, null, null)[A] == 19L)

    // Achievements
    val k1 = AchievementRules.Key("slayer", 1, Seasons.PERMANENT)
    check("complete once", w.store.complete(A, k1, 10, 100) && !w.store.complete(A, k1, 12, 200))
    check("completion kept its value", w.store.achievement(A, k1)!!.valueAtCompletion == 10L)
    val ks = AchievementRules.Key("season", 1, "season-2")
    check("seasonal per period", w.store.complete(A, ks, 5, 100) && w.store.complete(A, ks.copy(period = "season-3"), 5, 100))
    val snapshot = ProfileStore.encodeRewards(listOf(AchievementReward.Title("&cSlayer"), AchievementReward.Border("red")))
    check("claim not completed refused", w.store.beginClaim(A, AchievementRules.Key("nope", 1, Seasons.PERMANENT), snapshot, 1) == ProfileStore.ClaimStart.NotCompleted)
    check("claim starts once", w.store.beginClaim(A, k1, snapshot, 300) is ProfileStore.ClaimStart.Started)
    check("second claim sees it in progress", w.store.beginClaim(A, k1, snapshot, 301) == ProfileStore.ClaimStart.InProgress)
    check("unfinished claims listed (crash recovery)", w.store.unfinishedClaims().single().key == k1 && w.store.unfinishedClaims().single().rewards == snapshot)

    // Restart: close and reopen the same database; nothing is lost or duplicated.
    w.db.shutdown()
    w = World(dir)
    check("restart: claim still CLAIMING with its snapshot", w.store.achievement(A, k1)!!.status == "CLAIMING" && w.store.unfinishedClaims().single().rewards == snapshot)
    check("restart: counters kept", w.store.counters(A, null)[A]!!["ores-mined"] == 19L)
    check("finish once", w.store.finishClaim(A, k1, 400) && !w.store.finishClaim(A, k1, 401))
    check("claimed stays claimed", w.store.beginClaim(A, k1, snapshot, 500) == ProfileStore.ClaimStart.AlreadyClaimed && w.store.unfinishedClaims().isEmpty())
    check("rows never move back", throws { w.db.execute("UPDATE lifesteal_achievements SET status = 'COMPLETED' WHERE uuid = ?", A.toString()) })
    check("rows never deleted", throws { w.db.execute("DELETE FROM lifesteal_achievements") })
    check("completion facts can't be edited", throws { w.db.execute("UPDATE lifesteal_achievements SET value_at_completion = 999 WHERE uuid = ? AND status = 'COMPLETED'", A.toString()) })
    check("audit trail", w.store.recentAudit(A, 50).count { it.contains("COMPLETED") } >= 3 && w.store.recentAudit(A, 50).any { it.contains("CLAIMED") })
    check("completed counts per window", w.store.completedCounts(null)[A] == 3L && w.store.completedCounts(SeasonWindow("x", 0, 150, false))[A] == 3L && w.store.completedCounts(SeasonWindow("x", 150, null, true))[A] == null)

    // Settings: the title must come from a claimed row that holds a title.
    val titleOk = { row: ProfileStore.AchievementRow -> ProfileStore.decodeRewards(row.rewards).any { it is AchievementReward.Title } }
    val borders = setOf("gray", "red")
    check("title from a claimed tier", w.store.saveSettings(A, ProfileStore.Settings(title = AchievementRules.keyText(k1), border = "red", featured = listOf(AchievementRules.keyText(k1))), titleOk, { it in borders }, 1))
    check("settings read back", w.store.settings(A).title == AchievementRules.keyText(k1) && w.store.settings(A).border == "red" && w.store.settings(A).featured.size == 1)
    check("unclaimed title refused", !w.store.saveSettings(A, ProfileStore.Settings(title = AchievementRules.keyText(ks)), titleOk, { true }, 2))
    check("someone else's title refused", !w.store.saveSettings(B, ProfileStore.Settings(title = AchievementRules.keyText(k1)), titleOk, { true }, 2))
    check("locked border refused", !w.store.saveSettings(A, ProfileStore.Settings(border = "blue"), titleOk, { it in borders }, 2))
    check("featuring an unearned achievement refused", !w.store.saveSettings(B, ProfileStore.Settings(featured = listOf(AchievementRules.keyText(k1))), titleOk, { true }, 2))
    check("too many featured refused", !w.store.saveSettings(A, ProfileStore.Settings(featured = listOf("a:1:p", "b:1:p", "c:1:p", "d:1:p")), titleOk, { true }, 2))
    check("refused change left the old settings", w.store.settings(A).border == "red")
    check("private flag", w.store.saveSettings(A, w.store.settings(A).copy(private = true), titleOk, { it in borders }, 3) && w.store.settings(A).private)
    check("defaults for a new player", w.store.settings(D) == ProfileStore.Settings())
    w.db.shutdown()

    // Missing source tables (a system that never ran here) read as empty, never fail.
    val empty = Files.createTempDirectory("lsprofile-empty").toFile()
    val db = DatabaseManager(empty, quietLogger()).also { it.start() }
    val s = ProfileStore(db).also { it.createTables() }
    val calc = ProfileCalculator(s, HOUR, { _, _ -> null }, true)
    check("no source tables: empty profile", calc.player(A, true).lifetime.values.values.all { it == 0L } && s.seasons().isEmpty() && s.pvpRows().isEmpty() && s.hearts(A) == null)
    db.shutdown()
}

private fun concurrency() {
    val dir = Files.createTempDirectory("lsprofile-race").toFile()
    val w = World(dir)
    val keys = (1..20).map { AchievementRules.Key("a$it", 1, Seasons.PERMANENT) }
    keys.forEach { w.store.complete(A, it, 1, 0) }
    val pool = Executors.newFixedThreadPool(8)
    val started = AtomicInteger()
    val latch = CountDownLatch(keys.size * 8)
    for (key in keys) repeat(8) {
        pool.execute {
            try { if (w.store.beginClaim(A, key, "credits|1|1", 1) is ProfileStore.ClaimStart.Started) started.incrementAndGet() } finally { latch.countDown() }
        }
    }
    latch.await(60, TimeUnit.SECONDS)
    pool.shutdown()
    check("concurrent claims: exactly one winner per tier", started.get() == keys.size)
    val completes = AtomicInteger()
    val pool2 = Executors.newFixedThreadPool(8)
    val latch2 = CountDownLatch(40)
    repeat(40) { pool2.execute { try { if (w.store.complete(B, AchievementRules.Key("race", 1, Seasons.PERMANENT), 1, 0)) completes.incrementAndGet() } finally { latch2.countDown() } } }
    latch2.await(60, TimeUnit.SECONDS)
    pool2.shutdown()
    check("concurrent completion: recorded once", completes.get() == 1)
    val pool3 = Executors.newFixedThreadPool(8)
    val latch3 = CountDownLatch(100)
    repeat(100) { pool3.execute { try { w.store.addCounters(C, "season-2", mapOf("ores-mined" to 1), 0) } finally { latch3.countDown() } } }
    latch3.await(60, TimeUnit.SECONDS)
    pool3.shutdown()
    check("concurrent counter writes add up exactly", w.store.counters(C, "season-2")[C]!!["ores-mined"] == 100L)
    w.db.shutdown()
}

// ── Issue #1538: progression remediation (audit #1533 P2-01..P2-06, privacy, banned players) ──
private fun remediation() {
    val E = UUID.fromString("00000000-0000-0000-0000-00000000000e")
    val rules = PvpCounting.Rules(HOUR, DAY, 10)

    // PvP sources: only actual kills earn kills (P2-06).
    check("source from the recorded dedupe key", PvpSource.of("pvp:boot:x:1") == PvpSource.ORDINARY && PvpSource.of("combatnpc:s1") == PvpSource.COMBAT_NPC &&
        PvpSource.of("rankedforfeit:m1:x") == PvpSource.RANKED_FORFEIT && PvpSource.of("admin:x") == PvpSource.UNKNOWN && PvpSource.of(null) == PvpSource.UNKNOWN)
    val ordinary = PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0)), rules, emptySet())
    check("23 ordinary PvP kill counts", ordinary[A]!!.kills == 1L && ordinary[B]!!.deaths == 1L)
    // Ranked match kills and staff TEST match kills are recorded by the heart system with the ordinary pvp: key.
    val ranked = PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0, PvpSource.of("pvp:b:${B}:10")), PvpRow(A, C, "VICTIM_LOST_ONLY", MIN, PvpSource.of("pvp:b:${C}:11"))), rules, emptySet())
    check("24/25 ranked and TEST match actual kills count", ranked[A]!!.kills == 2L && ranked[A]!!.uniqueVictims == 2L && ranked[A]!!.bestStreak == 2L)
    val forfeit = PvpCounting.derive(listOf(
        PvpRow(A, C, "TRANSFERRED", 0),
        PvpRow(A, B, "TRANSFERRED", 2 * HOUR, PvpSource.RANKED_FORFEIT),
        PvpRow(A, D, "TRANSFERRED", 4 * HOUR, PvpSource.RANKED_FORFEIT),
    ), rules, emptySet())
    check("26 ranked forfeit: no kill, no unique victim, no streak step", forfeit[A]!!.kills == 1L && forfeit[A]!!.uniqueVictims == 1L && forfeit[A]!!.bestStreak == 1L && forfeit[A]!!.currentStreak == 1L)
    check("26 ranked forfeit: still the disconnecting player's death", forfeit[B]!!.deaths == 1L && forfeit[D]!!.deaths == 1L && forfeit[B]!!.kills == 0L)
    check("26 forfeits don't feed Season Contender/Slayer (seasonal pvp-kills)", PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0, PvpSource.RANKED_FORFEIT)), rules, emptySet())[A] == null)
    check("27 combat NPC player kill counts", PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0, PvpSource.COMBAT_NPC)), rules, emptySet())[A]!!.kills == 1L)
    check("a kill is never inferred from an unknown source", PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0, PvpSource.UNKNOWN)), rules, emptySet())[A]?.kills ?: 0L == 0L)

    // No-loss kills (P2-03): longer same-victim cooldown and a daily cap.
    val atMin = PvpCounting.derive(listOf(
        PvpRow(A, B, "VICTIM_AT_MINIMUM", 0),
        PvpRow(A, B, "VICTIM_AT_MINIMUM", 2 * HOUR),   // past the 60 min cooldown, inside the 24h no-loss one
        PvpRow(A, B, "KILLER_AT_MAXIMUM", 5 * HOUR),
        PvpRow(A, B, "VICTIM_AT_MINIMUM", DAY + MIN),  // past 24h: counts again
    ), rules, emptySet())
    check("28 victim at minimum: one counted no-loss kill per pair per 24h", atMin[A]!!.kills == 2L && atMin[A]!!.suppressedNoLossKills == 2L)
    check("28 the victim's deaths still count", atMin[B]!!.deaths == 4L)
    val lostOnly = PvpCounting.derive(listOf(PvpRow(A, B, "VICTIM_LOST_ONLY", 0), PvpRow(A, B, "VICTIM_LOST_ONLY", 2 * HOUR), PvpRow(A, B, "TRANSFERRED", 4 * HOUR)), rules, emptySet())
    check("29 victim-only heart loss is a real kill (heart PR compatibility): only the plain cooldown", lostOnly[A]!!.kills == 3L && lostOnly[A]!!.suppressedNoLossKills == 0L)
    check("29 a no-loss kill and a loss kill of the same victim keep the 60 min cooldown",
        PvpCounting.derive(listOf(PvpRow(A, B, "VICTIM_AT_MINIMUM", 0), PvpRow(A, B, "TRANSFERRED", 30 * MIN), PvpRow(A, B, "TRANSFERRED", 61 * MIN)), rules, emptySet())[A]!!.kills == 2L)
    val victims = (1..14).map { UUID.nameUUIDFromBytes("coop$it".toByteArray()) }
    val farm = PvpCounting.derive(victims.mapIndexed { i, v -> PvpRow(A, v, "VICTIM_AT_MINIMUM", i * MIN) } + PvpRow(A, B, "TRANSFERRED", 20 * MIN), rules, emptySet())
    check("31 cooperative farming: at most 10 counted no-loss kills per killer per 24h, real kills unaffected", farm[A]!!.kills == 11L && farm[A]!!.suppressedNoLossKills == 4L)
    val nextDay = PvpCounting.derive(victims.mapIndexed { i, v -> PvpRow(A, v, "VICTIM_AT_MINIMUM", i * 3 * HOUR) }, rules, emptySet())
    check("31 the daily cap is a rolling 24h window", nextDay[A]!!.kills == 14L && nextDay[A]!!.suppressedNoLossKills == 0L)
    check("31 suppressed kills are evidence only: no unique victim, no streak", farm[A]!!.uniqueVictims == 11L && farm[A]!!.bestStreak == 11L)
    check("32 shared-connection exclusion still applies", PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0)), rules, setOf(PvpCounting.pair(A, B))).isEmpty())
    check("plain rules keep the pre-#1538 behaviour", PvpCounting.derive(listOf(PvpRow(A, B, "VICTIM_AT_MINIMUM", 0), PvpRow(A, B, "VICTIM_AT_MINIMUM", 2 * HOUR)), HOUR, emptySet())[A]!!.kills == 2L)
    val streak = PvpCounting.derive(listOf(PvpRow(A, B, "TRANSFERRED", 0), PvpRow(A, C, "TRANSFERRED", MIN), PvpRow(A, D, "TRANSFERRED", 2 * MIN, PvpSource.RANKED_FORFEIT), PvpRow(B, A, "TRANSFERRED", 3 * MIN), PvpRow(A, D, "TRANSFERRED", 4 * MIN)), rules, emptySet())
    check("33 kill streak: forfeits don't extend it, a counted death ends it", streak[A]!!.bestStreak == 2L && streak[A]!!.currentStreak == 1L)
    check("linked accounts by hash sets", LinkedAccounts(mapOf(A to setOf("h1", "h2"), B to setOf("h2"), C to setOf("h3"))).let { it.linked(A, B) && it.linked(B, A) && !it.linked(A, C) && !it.linked(A, D) })

    val dir = Files.createTempDirectory("lsprofile-1538").toFile()
    var w = World(dir)
    w.hearts(A, 10); w.hearts(B, 10)
    check("one active season", ProgressionStore(w.db).createSeason("season-2", 0, "console", "test", 0))

    // Database: recorded sources (P2-06), chunked reads (P2-04).
    w.kill(A, B, 10 * MIN, "TRANSFERRED", "pvp:boot:$B:1")
    w.kill(A, C, 20 * MIN, "TRANSFERRED", "combatnpc:session-1")
    w.kill(A, D, 30 * MIN, "TRANSFERRED", "rankedforfeit:match-1:$D")
    w.kill(A, D, 40 * MIN, "TRANSFERRED", "pvp:boot:$D:2")    // a real kill of the same victim later
    val rows = w.store.pvpRows()
    check("pvpRows carries the recorded source", rows.map { it.source } == listOf(PvpSource.ORDINARY, PvpSource.COMBAT_NPC, PvpSource.RANKED_FORFEIT, PvpSource.ORDINARY))
    val pa = w.calc(rules = rules).player(A, false)
    check("profile: forfeit isn't a kill, combat NPC and later real kill are", pa.lifetime[ProfileStat.PVP_KILLS] == 3L && pa.lifetime[ProfileStat.UNIQUE_VICTIMS] == 3L)
    check("profile: the forfeiting player keeps the death", w.calc(rules = rules).player(D, false).lifetime[ProfileStat.PVP_DEATHS] == 2L)
    check("30 duplicate death event: one row per dedupe key (UNIQUE upstream)", throws { w.kill(A, B, 11 * MIN, "TRANSFERRED", "pvp:boot:$B:1") })

    val many = (1..1_234).map { UUID.nameUUIDFromBytes("p$it".toByteArray()) }
    w.db.transaction { for ((i, v) in many.withIndex()) w.kill(many[(i + 1) % many.size], v, DAY + (many.size - i) * 1_000L, if (i % 3 == 0) "VICTIM_AT_MINIMUM" else "TRANSFERRED", "pvp:big:$i") }
    val chunked = w.store.pvpRows(chunk = 97)
    val whole = w.store.pvpRows(chunk = 100_000)
    check("46 large history: chunked read = one read, oldest first", chunked == whole && chunked.size == 1_238 && chunked.zipWithNext().all { (a, b) -> a.at <= b.at })
    val connections = (1..300).map { UUID.nameUUIDFromBytes("alt$it".toByteArray()) }
    w.db.transaction { for (u in connections) w.db.execute("INSERT INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, 'shared', 0)", u.toString()) }
    w.db.execute("INSERT INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, 'own', 0)", A.toString())
    val started = System.nanoTime()
    val linked = w.store.connectionHashes()
    check("47 many accounts on one connection: linear read, every pair linked", linked.accounts == 301 && linked.linked(connections[0], connections[299]) && !linked.linked(A, connections[0]))
    check("47 per-player read only returns the accounts sharing a hash", w.store.connectionHashes(connections[5]).accounts == 300 && w.store.connectionHashes(A).accounts == 1)
    val calc = w.calc(rules = rules)
    val first = calc.allPvp(null, chunked, linked)
    val again = w.calc(rules = rules).allPvp(null, w.store.pvpRows(), w.store.connectionHashes())
    check("48 repeated rebuilds give the same boards", first == again && calc.all(ProfileStat.RANKED_WINS, null, null) == calc.all(ProfileStat.RANKED_WINS, null, null))
    check("50 optimized aggregation = the per-player profile numbers", many.take(25).all { u -> (first[u]?.kills ?: 0L) == w.calc(rules = rules).player(u, false).lifetime[ProfileStat.PVP_KILLS] })
    check("46 large board built quickly", (System.nanoTime() - started) / 1_000_000L < 20_000L)
    // 49: a reader and a writer at once: the chunked read never blocks writes for the whole history.
    val pool = Executors.newFixedThreadPool(2)
    val writes = AtomicInteger()
    val done = CountDownLatch(2)
    pool.execute { try { repeat(200) { i -> w.kill(A, E, 2 * DAY + i, "TRANSFERRED", "pvp:race:$i"); writes.incrementAndGet() } } finally { done.countDown() } }
    pool.execute { try { repeat(5) { w.store.pvpRows(chunk = 50) } } finally { done.countDown() } }
    check("49 concurrent writes and chunked reads both finish", done.await(60, TimeUnit.SECONDS) && writes.get() == 200)
    pool.shutdown()
    check("49 everything written is read afterwards", w.store.pvpRows(chunk = 50).size == 1_438)

    // Veteran (P2-01): active Lifesteal playtime, AFK excluded.
    w.db.execute("INSERT INTO playtime (uuid, total_seconds) VALUES (?, ?)", B.toString(), 600 * 3600)
    check("2 AFK/wall-clock playtime doesn't count", w.calc().player(B, false).lifetime[ProfileStat.PLAYTIME_HOURS] == 0L)
    w.active(B, 10 * HOUR + 30 * MIN)
    val veteran = AchievementDef("veteran", "Veteran", "", AchievementCategory.PROGRESSION, ProfileStat.PLAYTIME_HOURS, AchievementPeriod.PERMANENT,
        com.liam.joshymc.lifesteal.profile.AchievementVisibility.VISIBLE, "CLOCK", false,
        listOf(com.liam.joshymc.lifesteal.profile.AchievementTier(1, null, 10, emptyList()), com.liam.joshymc.lifesteal.profile.AchievementTier(2, null, 50, emptyList())))
    val vb = w.calc().player(B, false)
    check("1 active playtime advances Veteran", vb.lifetime[ProfileStat.PLAYTIME_HOURS] == 10L &&
        AchievementRules.newlyReached(listOf(veteran), vb.season, vb.lifetime, "season-2", emptySet()).map { it.second.number } == listOf(1))
    val vetKey = AchievementRules.Key("veteran", 2, Seasons.PERMANENT)
    check("4 a tier completed under the old measure stays complete", w.store.complete(B, vetKey, 60, 0))

    // Team Climber (P2-02).
    w.db.execute("INSERT INTO lifesteal_team_membership_history (team_id, uuid, joined_at, left_at, reason) VALUES ('hi', ?, 0, NULL, NULL)", C.toString())
    fun teamDiv(u: UUID) = w.calc().player(u, false).season!![ProfileStat.TEAM_DIVISION]
    check("5 joining a high-ranked team without playing", teamDiv(C) == 0L)
    w.history("c1", C, "hi", "WIN", 1300)
    check("6 one (won) match with a high-ranked team", teamDiv(C) == 0L)
    for (i in 2..5) w.history("c$i", C, "hi", "LOSS", 1290)
    check("7/8 five rated matches with a win for that team: division counts", teamDiv(C) == 4L)
    for (i in 1..5) w.history("d$i", D, "hi", "LOSS", 1300)
    check("8 five matches but no win: no division", teamDiv(D) == 0L)
    for (i in 1..3) w.history("e$i", E, "t-a", "WIN", 1300)
    for (i in 1..3) w.history("f$i", E, "t-b", "WIN", 1300)
    check("9 team switching: matches for different teams never add up", teamDiv(E) == 0L)
    check("10/11 cancelled, unrated and TEST matches have no committed history row, so they never count", w.calc().all(ProfileStat.TEAM_DIVISION, null, null).keys == setOf(C))
    val tcKey = AchievementRules.Key("team-climber", 1, Seasons.PERMANENT)
    check("12 an already earned Team Climber tier is kept", w.store.complete(D, tcKey, 4, 0) && w.store.achievement(D, tcKey)!!.status == "COMPLETED" && teamDiv(D) == 0L)
    check("team division rule parsed from config", ProfileConfig.parse(mapOf("achievements-settings" to mapOf("team-division" to mapOf("minimum-matches" to 8, "minimum-wins" to 2)))).settings.teamDivisionRule == TeamDivisionRule(8, 2))
    check("team division rule: wins above matches refused", ProfileConfig.parse(mapOf("achievements-settings" to mapOf("team-division" to mapOf("minimum-matches" to 1, "minimum-wins" to 2)))).let { it.problems.isNotEmpty() && it.settings.teamDivisionRule == TeamDivisionRule.DEFAULT })

    // Coin eligibility (P2-05).
    val req = com.liam.joshymc.lifesteal.profile.ProfileSettings.DEFAULT_COIN_ELIGIBILITY
    check("bundled default: 2h active + 24h", req.playtimeMillis == 2 * HOUR && req.accountAgeMillis == DAY && ProfileConfig.parse(emptyMap()).settings.coinEligibility == req)
    val now = 10 * DAY
    check("13 account younger than 24h waits", CoinEligibility.check(5 * HOUR, now - 23 * HOUR, now, req).let { !it.eligible && it.accountAgeMissingMillis == HOUR && it.playtimeMissingMillis == 0L })
    check("14 account older than 24h", CoinEligibility.check(5 * HOUR, now - 25 * HOUR, now, req).eligible)
    check("15 under 2h active playtime waits", CoinEligibility.check(HOUR, now - 5 * DAY, now, req).let { !it.eligible && it.playtimeMissingMillis == HOUR })
    check("16 over 2h active playtime", CoinEligibility.check(2 * HOUR + 1, now - 5 * DAY, now, req).eligible)
    check("never joined Lifesteal (no heart record) counts as joining now", !CoinEligibility.check(10 * HOUR, null, now, req).eligible)
    check("a first join in the future never makes an account look older", !CoinEligibility.check(10 * HOUR, now + DAY, now, req).eligible)
    check("eligibility off", CoinEligibility.check(0, null, now, null).eligible && ProfileConfig.parse(mapOf("achievements-settings" to mapOf("coin-eligibility" to mapOf("enabled" to false)))).settings.coinEligibility == null)
    val facts = w.store.coinEligibilityFacts(B)
    check("facts from the heart system's own records (read only)", facts.first == 10 * HOUR + 30 * MIN && facts.second == 0L && w.store.coinEligibilityFacts(D) == (0L to null))
    val coins = AchievementReward.Store(PurchaseRewards.Spec(PurchaseRewards.Kind.COINS, "1000", 1))
    check("19 mixed coin + title tier waits as a whole; cosmetic-only tiers never wait; coins left out on a mixed server never wait",
        CoinEligibility.paysCoins(listOf(coins, AchievementReward.Title("&eVeteran")), true) && !CoinEligibility.paysCoins(listOf(AchievementReward.Title("x"), AchievementReward.Border("red")), true) &&
            !CoinEligibility.paysCoins(listOf(coins), false))
    val earlyKey = AchievementRules.Key("first-blood", 1, Seasons.PERMANENT)
    check("17 completion is never blocked by eligibility", w.store.complete(E, earlyKey, 1, 0) && w.store.achievement(E, earlyKey)!!.status == "COMPLETED")
    val manager = source("lifesteal/profile/LifestealProfileManager.kt")
    val gate = manager.indexOf("CoinEligibility.check(active, firstJoin, now, req)")
    check("18/20 the claim checks eligibility before the conditional COMPLETED -> CLAIMING update (the row stays claimable)",
        gate > 0 && gate < manager.indexOf("store.beginClaim(uuid, key, snapshot, now)") && manager.contains("val start = if (ineligible != null || checkFailed) null else"))
    check("21/22 recovery of CLAIMING rows is unchanged (same references, never re-gated or re-paid)",
        manager.substringAfter("fun recoverClaims()").substringBefore("// ── Profile settings").let { !it.contains("CoinEligibility") && it.contains("deliver(row.uuid, name, row.key, row.rewards.orEmpty())") })
    check("18 deferred claim after eligibility goes through the normal claim", w.store.beginClaim(E, earlyKey, ProfileStore.encodeRewards(listOf(coins)), 1) is ProfileStore.ClaimStart.Started &&
        w.store.beginClaim(E, earlyKey, ProfileStore.encodeRewards(listOf(coins)), 2) == ProfileStore.ClaimStart.InProgress)

    // Restart: everything above is still there (3).
    w.db.shutdown()
    w = World(dir)
    check("3 restart keeps active playtime progress", w.calc().player(B, false).lifetime[ProfileStat.PLAYTIME_HOURS] == 10L)
    check("4 restart keeps completed tiers", w.store.achievement(B, vetKey)!!.status == "COMPLETED" && w.store.achievement(D, tcKey) != null)

    // Banned players on the Elo board (the profile boards filter their value maps the same way).
    w.db.createTable("CREATE TABLE IF NOT EXISTS lifesteal_ranked_ratings (season_id TEXT, rating_type TEXT, subject_id TEXT, rating INTEGER, wins INTEGER DEFAULT 0, losses INTEGER DEFAULT 0, draws INTEGER DEFAULT 0, matches INTEGER DEFAULT 0, display_name TEXT, created_at INTEGER DEFAULT 0, updated_at INTEGER DEFAULT 0)")
    val players = (1..25).map { UUID.nameUUIDFromBytes("elo$it".toByteArray()) }
    w.db.transaction { for ((i, u) in players.withIndex()) w.db.execute("INSERT INTO lifesteal_ranked_ratings (season_id, rating_type, subject_id, rating, matches) VALUES ('s', 'PLAYER_ELO', ?, ?, 5)", u.toString(), 2000 - i) }
    val elo = com.liam.joshymc.lifesteal.ranked.stats.RankedStatsStore(w.db)
    val bannedSet = setOf(players[0].toString(), players[10].toString(), players[24].toString())
    val p0 = elo.page("s", com.liam.joshymc.lifesteal.ranked.rating.RatingType.PLAYER_ELO, 1, 0, 10, bannedSet)
    check("41 active ban hides the player", p0.total == 22 && p0.entries.none { it.subjectId in bannedSet } && p0.entries.first().subjectId == players[1].toString() && p0.entries.first().position == 1)
    val p2 = elo.page("s", com.liam.joshymc.lifesteal.ranked.rating.RatingType.PLAYER_ELO, 1, 2, 10, bannedSet)
    check("44 pagination stays correct around hidden players", p2.entries.size == 2 && p2.entries.map { it.position } == listOf(21, 22) && p0.pages == 3)
    check("41 banned player has no place; the others close up", elo.position("s", com.liam.joshymc.lifesteal.ranked.rating.RatingType.PLAYER_ELO, players[0].toString(), 1, bannedSet) == null &&
        elo.position("s", com.liam.joshymc.lifesteal.ranked.rating.RatingType.PLAYER_ELO, players[11].toString(), 1, bannedSet) == 10)
    check("42/43 an expired or lifted ban (empty set) restores them in order", elo.page("s", com.liam.joshymc.lifesteal.ranked.rating.RatingType.PLAYER_ELO, 1, 0, 10).entries.first().subjectId == players[0].toString() &&
        elo.position("s", com.liam.joshymc.lifesteal.ranked.rating.RatingType.PLAYER_ELO, players[11].toString(), 1) == 12)
    check("45 hiding never touches stored statistics", w.db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_ranked_ratings") { it.getInt("n") } == 25 &&
        Leaderboards.rank(mapOf(A to 5L, B to 9L).filterKeys { it != B }).single().let { it.uuid == A && it.position == 1 })
    val punish = source("manager/PunishmentManager.kt")
    check("ban lookup: one batched query with the same rules as isBannedIn, cached and dropped on every change",
        punish.contains("fun activelyBannedIn(gamemode: ServerGamemode): Set<UUID>") && punish.contains("type IN (\$BAN_TYPES) AND active = 1 AND (expires_at IS NULL OR expires_at > ?) AND (scope IS NULL OR scope = ?)") &&
            Regex("invalidateBanned\\(\\)").findAll(punish).count() >= 6)
    check("profile boards and the Player Elo board both leave banned players out",
        manager.contains("values.filterKeys { it !in banned }") && source("lifesteal/ranked/stats/LifestealRankedStatsManager.kt").contains("activelyBannedIn(com.liam.joshymc.gamemode.ServerGamemode.LIFESTEAL)"))
    w.db.shutdown()

    // Privacy (Bukkit paths; checked in the source).
    val ifVisible = manager.substringAfter("fun ifVisible(").substringBefore("/** Main thread: queues")
    check("34/35/38/39 privacy rule: self, console and the bypass permission always see; the stored private flag decides",
        ifVisible.contains("viewer.uniqueId == target") && ifVisible.contains("PERMISSION_PRIVATE_BYPASS") && ifVisible.contains("store.settings(target).private") && ifVisible.contains("allowPrivate"))
    val rankedCmd = source("lifesteal/ranked/queue/RankedCommand.kt")
    check("36/37 /ranked stats|history <player> honor private profiles", Regex("ifVisible\\(player, uuid, shown\\) \\{ statsView\\.(stats|history)").findAll(rankedCmd).count() == 2)
    val rankedMenu = source("lifesteal/ranked/stats/RankedStatsMenu.kt")
    check("GUI navigation (leaderboard clicks, history buttons) goes through the same check",
        rankedMenu.substringAfter("fun openPlayerStats(").substringBefore("private fun renderPlayerStats").contains("ifVisible(viewer, target") &&
            rankedMenu.substringAfter("fun openPlayerHistory(").substringBefore("fun openTeamHistory").contains("ifVisible(viewer, target"))
    check("40 public leaderboards stay visible (no privacy filter on boards)", !manager.substringAfter("fun rebuildBoards(").substringBefore("private fun bannedPlayers").contains("private"))
    check("privacy toggle explains that leaderboards stay visible", source("lifesteal/profile/ProfileMenus.kt").contains("Public leaderboard rankings stay visible."))
    check("profile code never modifies heart storage", !source("lifesteal/profile/ProfileStore.kt").contains("INSERT INTO lifesteal_heart") && !manager.contains("lifestealHeartManager"))
}

private fun layout() {
    val buttons = ProfileMenuLayout.profileButtons()
    check("profile buttons distinct and inside the chest", buttons.toSet().size == buttons.size && buttons.all { it in 0 until ProfileMenuLayout.PROFILE_SIZE })
    check("border never covers a button", ProfileMenuLayout.borderSlots(ProfileMenuLayout.PROFILE_SIZE).none { it in buttons })
    val main = ProfileMenuLayout.CATEGORY_SLOTS + listOf(ProfileMenuLayout.SUMMARY, ProfileMenuLayout.MAIN_BACK, ProfileMenuLayout.MAIN_CLOSE)
    check("main menu fits", main.toSet().size == main.size && main.all { it < ProfileMenuLayout.MAIN_SIZE } && ProfileMenuLayout.CATEGORY_SLOTS.size == AchievementCategory.entries.size)
    val list = listOf(ProfileMenuLayout.LIST_BACK, ProfileMenuLayout.LIST_PREV, ProfileMenuLayout.LIST_CLOSE, ProfileMenuLayout.LIST_NEXT, ProfileMenuLayout.LIST_SORT)
    check("list controls below the entries", list.toSet().size == list.size && list.all { it in ProfileMenuLayout.PAGE_SIZE until ProfileMenuLayout.LIST_SIZE })
    val board = ProfileMenuLayout.BOARD_SLOTS + ProfileMenuLayout.BOARD_CATEGORY_SLOTS + listOf(ProfileMenuLayout.BOARD_HEADER, ProfileMenuLayout.BOARD_BACK, ProfileMenuLayout.BOARD_PREV,
        ProfileMenuLayout.BOARD_PERIOD, ProfileMenuLayout.BOARD_CLOSE, ProfileMenuLayout.BOARD_SELF, ProfileMenuLayout.BOARD_NEXT)
    check("leaderboard slots distinct and inside the chest", board.toSet().size == board.size && board.all { it in 0 until 54 })
    check("every category fits the selector", BoardCategory.entries.size <= ProfileMenuLayout.BOARD_CATEGORY_SLOTS.size)
    val custom = listOf(ProfileMenuLayout.CUSTOM_TITLE, ProfileMenuLayout.CUSTOM_BORDER, ProfileMenuLayout.CUSTOM_FEATURED, ProfileMenuLayout.CUSTOM_PRIVACY, ProfileMenuLayout.CUSTOM_BACK, ProfileMenuLayout.CUSTOM_CLOSE)
    check("customize menu fits", custom.toSet().size == custom.size && custom.all { it < ProfileMenuLayout.CUSTOM_SIZE })
    val menus = source("lifesteal/profile/ProfileMenus.kt")
    check("menus: claims go through the manager (database decides)", menus.contains("manager.claim(pl, key)") && !menus.contains("store.beginClaim"))
    check("menus: settings re-validated by the manager", menus.contains("manager.saveSettings(player, next)") && !menus.contains("store.saveSettings"))
    check("menus: heads through PlayerHeadManager (GuiStyle.skull), never owningPlayer", menus.contains("GuiStyle.skull(") && !menus.contains("owningPlayer"))
    check("menus: titles short", listOf("Lifesteal Profile", "Achievements", "Lifesteal Leaderboards", "Customize Profile", "Feature Achievements").all { it.length <= 28 })
}

private fun wiring() {
    for (c in listOf("profile", "stats", "achievements", "lstop")) {
        check("/$c is LIFESTEAL scoped", GamemodeAssignments.COMMANDS[c] == GamemodeScope.LIFESTEAL)
    }
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    for (c in listOf("profile", "stats", "achievements", "lstop")) check("plugin.yml declares /$c", pluginYml.contains("\n  $c:\n"))
    check("permissions declared", listOf("use", "others", "private.bypass", "admin").all { pluginYml.contains("joshymc.lifesteal.profile.$it:") } &&
        pluginYml.contains("joshymc.lifesteal.achievements.use:") && pluginYml.contains("joshymc.lifesteal.leaderboards.use:") &&
        Regex("joshymc\\.lifesteal\\.profile\\.admin:\\n[^\\n]*\\n    default: op").containsMatchIn(pluginYml))
    val commands = source("manager/CommandManager.kt")
    check("commands only where Lifesteal is hosted, released elsewhere", commands.contains("if (plugin.lifestealProfileManager.hosted) {") &&
        commands.contains("for (name in listOf(\"profile\", \"stats\", \"achievements\", \"lstop\")) unregisterCommand(name)"))
    check("listener only where Lifesteal is hosted", source("manager/ListenerManager.kt").contains("if (plugin.lifestealProfileManager.hosted) {\n            pm.registerEvents(com.liam.joshymc.lifesteal.profile.LifestealProfileListener"))
    val joshymc = source("Joshymc.kt")
    check("lifecycle: refresh on start and reload, stop at shutdown", joshymc.contains("lifestealProfileManager.refresh()") && joshymc.contains("safe(\"lifestealProfileManager.refresh\")") &&
        joshymc.contains("shutdown.step(\"lifestealProfileManager.stop\", { ::lifestealProfileManager.isInitialized })"))
    val manager = source("lifesteal/profile/LifestealProfileManager.kt")
    check("manager: Lifesteal-only economy/playtime", manager.contains("economyBelongsTo(ServerGamemode.LIFESTEAL"))
    check("manager: rewards through the existing delivery queue", manager.contains("plugin.purchaseFulfillmentManager.enqueue(") && !manager.contains("economyManager.deposit") && !manager.contains("creditsManager"))
    check("manager: never touches hearts", !manager.contains("lifestealHeartManager") && !source("lifesteal/profile/ProfileStore.kt").contains("UPDATE lifesteal_hearts"))
    check("manager: Elo boards reuse the Ranked leaderboard", source("lifesteal/profile/ProfileMenus.kt").contains("lifestealRankedStatsManager.loadLeaderboard("))
    val listener = source("lifesteal/profile/LifestealProfileListener.kt")
    check("listener: no PvP counting (comes from heart history)", !listener.contains("PlayerDeathEvent"))
    check("listener: Lifesteal worlds only", listener.contains("manager.isLifesteal(player.world)"))
    check("listener: own placed-block markers", listener.contains("PlacedBlockTracker(plugin, \"lifesteal_profile_placed\")"))
    check("store: writes only its own tables", Regex("(INSERT INTO|UPDATE|DELETE FROM) (\\w+)").findAll(source("lifesteal/profile/ProfileStore.kt")).map { it.groupValues[2] }
        .filter { it !in setOf("SET", "OF", "ON") }.all { it.startsWith("lifesteal_profile_") || it.startsWith("lifesteal_achievement") })
    check("Survival quests are not Lifesteal statistics", ProfileStat.entries.none { it.id.contains("quest") })
    check("economy: only a Lifesteal-only server's balances/playtime are Lifesteal's",
        TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.LIFESTEAL)) &&
            !TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, setOf(ServerGamemode.SURVIVAL, ServerGamemode.LIFESTEAL)))
    check("delivery wording for achievement rewards", source("manager/PurchaseFulfillmentManager.kt").contains("ACHIEVEMENT_SOURCE_PREFIX) -> \"achievement reward\""))
}
