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
    fun calc(playtime: Boolean = true) = ProfileCalculator(store, HOUR, { _, r -> if (r >= 1200) 4 else if (r >= 1000) 3 else 1 }, playtime)
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
    check("divisions from peak ratings", p.season!![ProfileStat.PLAYER_DIVISION] == 3L && p.season!![ProfileStat.TEAM_DIVISION] == 4L && p.previous.single().second[ProfileStat.PLAYER_DIVISION] == 0L)
    check("counters per season, lifetime = sum", p.season!![ProfileStat.ORES_MINED] == 8L && p.previous.single().second[ProfileStat.ORES_MINED] == 10L && p.lifetime[ProfileStat.ORES_MINED] == 19L)
    check("dimensions per season", p.season!![ProfileStat.DIMENSIONS_EXPLORED] == 1L && p.lifetime[ProfileStat.DIMENSIONS_EXPLORED] == 1L)
    check("team membership (history)", p.lifetime[ProfileStat.TEAM_JOINED] == 1L)
    check("playtime hours (Lifesteal-only server)", p.lifetime[ProfileStat.PLAYTIME_HOURS] == 7L)
    check("playtime never read where it's Survival's", w.calc(playtime = false).player(A, false).lifetime[ProfileStat.PLAYTIME_HOURS] == 0L)
    check("offline profile: hearts and record", w.store.hearts(A) == 12 && w.store.hasHeartRecord(B) && !w.store.hasHeartRecord(D))
    val pc = w.calc().player(C, includePrevious = false)
    check("victim-only player", pc.lifetime[ProfileStat.PVP_DEATHS] == 1L && pc.lifetime[ProfileStat.PVP_KILLS] == 0L)
    // Alt link: A and B shared a connection -> their fights count for nothing.
    w.db.execute("INSERT INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, 'h', 0), (?, 'h', 0)", A.toString(), B.toString())
    check("alt link removes A-B fights", w.calc().player(A, false).lifetime[ProfileStat.PVP_KILLS] == 1L && w.store.linkedPairs().size == 1)
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
