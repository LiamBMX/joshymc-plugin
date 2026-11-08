// Checks the Lifesteal Daily & Weekly Quests (issue #1425): the bundled lifesteal-quests.yml (pool
// sizes, reward ranges, allowed reward kinds, team alternatives) and the parser (disabling, invalid
// settings, broken templates), the America/New_York cycles (midnight, Monday weeks, both DST changes,
// reset times inside a DST gap), quest selection (unique ids/objectives/groups, team eligibility,
// weights, underfill), reroll pricing, the PvP farming rules (cooldown, hourly cap, idle victims,
// shared connections, won fights, assists, damage caps, the bounty eligibility it reuses), the
// progress display, the menu layout, and against a real SQLite database: assignment (idempotent and
// concurrent), progress saves (never lower, capped, completion once), claims (once, concurrent,
// expired cycles, payout rows), rerolls (free, paid, insufficient Credits, limits, stale menus,
// claimed/completed quests, no repeats, rollback of Credits on failure, free rerolls of team quests
// after leaving a team), restart persistence, unique victims, the alt lookup, Survival isolation and
// the wiring. The Gradle build has no test source set, so it runs through an init script:
//
//   ./gradlew -I scripts/lifesteal-quest-check.init.gradle.kts lifestealQuestCheck
//
// Exits non-zero on the first failed check. Live Paper behaviour (menus, real kills, mining) is NOT
// covered here; see docs/lifesteal-quests.md, "Manual tests".

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.lifesteal.bounty.BountyRules
import com.liam.joshymc.lifesteal.quests.LifestealQuestLayout
import com.liam.joshymc.lifesteal.quests.LifestealQuestStore
import com.liam.joshymc.lifesteal.quests.LifestealQuestStore.ClaimResult
import com.liam.joshymc.lifesteal.quests.LifestealQuestStore.RerollResult
import com.liam.joshymc.lifesteal.quests.QuestAntiAbuse
import com.liam.joshymc.lifesteal.quests.QuestCategory
import com.liam.joshymc.lifesteal.quests.QuestConfig
import com.liam.joshymc.lifesteal.quests.QuestCycles
import com.liam.joshymc.lifesteal.quests.QuestObjective
import com.liam.joshymc.lifesteal.quests.QuestPeriod
import com.liam.joshymc.lifesteal.quests.QuestProgress
import com.liam.joshymc.lifesteal.quests.QuestPvpRules
import com.liam.joshymc.lifesteal.quests.QuestRewardKinds
import com.liam.joshymc.lifesteal.quests.QuestSelection
import com.liam.joshymc.lifesteal.quests.QuestSettings
import com.liam.joshymc.lifesteal.quests.QuestTemplate
import com.liam.joshymc.lifesteal.quests.RerollRules
import com.liam.joshymc.lifesteal.quests.RerollSettings
import com.liam.joshymc.lifesteal.quests.TeamRequirement
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.milestone.CombatRules
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.random.Random
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

private val NY: ZoneId = ZoneId.of("America/New_York")
private fun ny(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, s: Int = 0): Long = ZonedDateTime.of(y, mo, d, h, mi, s, 0, NY).toInstant().toEpochMilli()
private const val HOUR = 3_600_000L

private val CATALOG = object : PurchaseRewards.Catalog {
    override fun crateExists(id: String) = id == "vote"
    override fun moneyVoucherExists(id: String) = id == "money"
    override fun chatTagVoucherExists(id: String) = id == "mapleborn"
}

/** A [QuestConfig.Source] over a SnakeYAML map. */
private class MapSource(val root: Map<*, *>) : QuestConfig.Source {
    private fun node(path: String): Any? {
        var cur: Any? = root
        for (part in path.split('.')) cur = (cur as? Map<*, *>)?.get(part) ?: return null
        return cur
    }
    override fun get(path: String): Any? = node(path)
    override fun keys(path: String): List<String>? = (node(path) as? Map<*, *>)?.keys?.map { it.toString() }
}

@Suppress("UNCHECKED_CAST")
private fun bundled(): MutableMap<String, Any?> =
    Yaml().load<Map<String, Any?>>(File("src/main/resources/lifesteal-quests.yml").readText()).toMutableMap()

@Suppress("UNCHECKED_CAST")
private fun deepCopy(m: Map<String, Any?>): MutableMap<String, Any?> =
    m.mapValues { (_, v) -> if (v is Map<*, *>) deepCopy(v as Map<String, Any?>) else v }.toMutableMap()

@Suppress("UNCHECKED_CAST")
private fun MutableMap<String, Any?>.at(path: String): MutableMap<String, Any?> {
    var cur = this
    for (part in path.split('.')) cur = cur[part] as MutableMap<String, Any?>
    return cur
}

private fun parse(m: Map<String, Any?>) = QuestConfig.parse(MapSource(m), CATALOG)

fun main() {
    config()
    cycles()
    selection()
    rules()
    layout()
    database()
    concurrency()
    wiring()
    println("Lifesteal quest checks passed ($checks checks).")
    exitProcess(0)
}

// ── Configuration ───────────────────────────────────────────

private lateinit var SETTINGS: QuestSettings

private fun config() {
    val result = parse(bundled())
    result.problems.filter { it.severe }.forEach { System.err.println("severe: ${it.message}") }
    check("bundled lifesteal-quests.yml parses with no severe problem", result.settings != null)
    val s = result.settings!!
    SETTINGS = s
    check("bundled file has no skipped quest", result.problems.none { "skipped" in it.message })
    check("enabled by default", s.enabled)
    check("America/New_York", s.zone == NY)
    check("daily reset midnight", s.dailyResetTime == LocalTime.MIDNIGHT)
    check("weekly reset Monday midnight", s.weeklyResetDay == DayOfWeek.MONDAY && s.weeklyResetTime == LocalTime.MIDNIGHT)
    check("3 daily and 3 weekly quests", s.questsPerCycle[QuestPeriod.DAILY] == 3 && s.questsPerCycle[QuestPeriod.WEEKLY] == 3)
    val daily = s.pool(QuestPeriod.DAILY).filter { it.enabled }
    val weekly = s.pool(QuestPeriod.WEEKLY).filter { it.enabled }
    check("at least 30 daily templates (${daily.size})", daily.size >= 30)
    check("at least 20 weekly templates (${weekly.size})", weekly.size >= 20)
    val all = daily + weekly
    check("ids unique", all.map { it.id }.toSet().size == all.size)
    check("every category is used in both pools", QuestPeriod.entries.all { p -> QuestCategory.entries.all { c -> s.pool(p).any { it.category == c } } })
    check("every objective is used", QuestObjective.entries.all { o -> all.any { it.objective == o } })
    check("only allowed reward kinds", all.all { t -> t.rewards.all { it.kind in QuestRewardKinds.ALLOWED } })
    check("no rank / heart / voucher reward kinds allowed",
        PurchaseRewards.Kind.RANK !in QuestRewardKinds.ALLOWED && PurchaseRewards.Kind.RANK_VOUCHER !in QuestRewardKinds.ALLOWED &&
            PurchaseRewards.Kind.CREDIT_VOUCHER !in QuestRewardKinds.ALLOWED && PurchaseRewards.Kind.MONEY_VOUCHER !in QuestRewardKinds.ALLOWED)
    fun coins(t: QuestTemplate) = t.rewards.filter { it.kind == PurchaseRewards.Kind.COINS }.sumOf { it.param.toDouble() }
    check("daily coin rewards 10,000-25,000", daily.all { coins(it) in 10_000.0..25_000.0 })
    check("weekly coin rewards 50,000-150,000", weekly.all { coins(it) in 50_000.0..150_000.0 })
    check("teamwork quests need a team", all.filter { it.objective.needsTeam }.all { it.team == TeamRequirement.REQUIRED })
    for (p in QuestPeriod.entries) {
        val solo = s.pool(p).filter { it.team.allows(false) && it.enabled }
        check("${p.id}: players without a team have enough quests (${solo.size})", QuestSelection.pick(solo, 3, false).size == 3)
        check("${p.id}: there are solo-only alternatives", s.pool(p).any { it.team == TeamRequirement.NONE })
        check("${p.id}: team players have enough quests", QuestSelection.pick(s.pool(p), 3, true).size == 3)
        check("${p.id}: ranked participation never required", s.pool(p).none { "ranked" in it.id || "ranked" in it.description.lowercase() })
    }
    check("gather quests have a match list", all.filter { it.objective == QuestObjective.GATHER_BLOCK }.all { it.match.isNotEmpty() })
    check("pvp damage target in hearts", QuestObjective.PVP_DAMAGE.scale == 20L && s.template(QuestPeriod.DAILY, "brawler")!!.storedTarget == 400L)
    check("rerolls: 1 free daily, Credits after, weekly paid",
        s.rerolls[QuestPeriod.DAILY] == RerollSettings(1, 25.0, 3) && s.rerolls[QuestPeriod.WEEKLY]!!.freePerCycle == 0 && s.rerolls[QuestPeriod.WEEKLY]!!.creditCost > 0)
    check("shared connections blocked by default", s.antiAbuse.pvpBlockSharedConnections)
    check("spawner mobs don't count by default", "SPAWNER" !in s.antiAbuse.mobSpawnReasons && "SPAWNER_EGG" !in s.antiAbuse.mobSpawnReasons)

    // Disabling keeps a valid configuration (stored data is untouched; the manager refuses new progress and claims).
    val off = bundled().also { it["enabled"] = false }
    check("enabled: false parses as disabled", parse(off).settings?.enabled == false)

    // Severe mistakes pause the system instead of guessing.
    for ((path, value) in listOf("timezone" to "Mars/Olympus", "daily.reset-time" to "25:00", "weekly.reset-day" to "FUNDAY",
        "rerolls.daily.credit-cost" to -5, "daily.quests-per-cycle" to 9, "anti-abuse.team-radius" to "far", "enabled" to "yes")) {
        val m = deepCopy(bundled())
        val parts = path.split('.')
        val parent = if (parts.size == 1) m else m.at(parts.dropLast(1).joinToString("."))
        parent[parts.last()] = value
        check("invalid $path pauses the quests", parse(m).settings == null)
    }
    val noPools = bundled().also { it.remove("pools") }
    check("no pools section is severe", parse(noPools).settings == null)
    val missing = bundled().also { it.remove("anti-abuse") }
    val r2 = parse(missing)
    check("a missing section only warns and uses defaults", r2.settings != null && r2.settings!!.antiAbuse == QuestAntiAbuse())

    // Broken templates are skipped one by one.
    fun withTemplate(t: Map<String, Any?>): QuestConfig.Result {
        val m = deepCopy(bundled())
        m.at("pools.daily")["broken_one"] = t
        return parse(m)
    }
    val base = mapOf("name" to "x", "category" to "survival", "objective" to "mine_ore", "target" to 5, "rewards" to listOf("coins 10000"))
    check("a valid extra template is accepted", withTemplate(base).settings!!.template(QuestPeriod.DAILY, "broken_one") != null)
    val crate = withTemplate(base + ("rewards" to listOf("cratekey vote 1", "chattagvoucher mapleborn 1", "credits 5")))
    check("crate keys, chat tags and credits are valid rewards", crate.settings!!.template(QuestPeriod.DAILY, "broken_one")!!.rewards.size == 3)
    for ((label, t) in listOf(
        "rank reward" to base + ("rewards" to listOf("rank vip")),
        "rank voucher reward" to base + ("rewards" to listOf("rankvoucher vip 1")),
        "unknown crate" to base + ("rewards" to listOf("cratekey nope 1")),
        "unknown chat tag" to base + ("rewards" to listOf("chattagvoucher nope 1")),
        "negative coins" to base + ("rewards" to listOf("coins -5")),
        "extra word" to base + ("rewards" to listOf("coins 5 ref")),
        "no rewards" to base + ("rewards" to emptyList<String>()),
        "unknown objective" to base + ("objective" to "fly_around"),
        "category mismatch" to base + ("category" to "combat"),
        "zero target" to base + ("target" to 0),
        "gather without match" to base + ("objective" to "gather_block"),
        "match on pvp" to base + ("category" to "combat") + ("objective" to "player_kill") + ("match" to listOf("ZOMBIE")),
        "teamwork without team" to base + ("category" to "teamwork") + ("objective" to "team_assist") + ("team" to "any"),
        "bad weight" to base + ("weight" to 0),
        "bad team" to base + ("team" to "maybe"),
    )) {
        val r = withTemplate(t)
        check("$label: template skipped, quests keep running", r.settings != null && r.settings!!.template(QuestPeriod.DAILY, "broken_one") == null &&
            r.problems.any { "broken_one skipped" in it.message && !it.severe })
    }
    val dup = deepCopy(bundled())
    dup.at("pools.weekly")["first_blood"] = base
    check("an id used in both pools is severe", parse(dup).settings == null)
}

// ── Cycles ──────────────────────────────────────────────────

private fun cycles() {
    val mid = LocalTime.MIDNIGHT
    val beforeMidnight = QuestCycles.daily(ny(2026, 11, 7, 23, 59, 59), NY, mid)
    val atMidnight = QuestCycles.daily(ny(2026, 11, 8, 0, 0, 0), NY, mid)
    check("23:59:59 New York is still the previous day", beforeMidnight.id == "daily:2026-11-07")
    check("00:00 New York starts the new day", atMidnight.id == "daily:2026-11-08")
    check("daily end = next start", beforeMidnight.endMillis == atMidnight.startMillis)
    check("New York midnight is 05:00 UTC in November", atMidnight.startMillis == java.time.Instant.parse("2026-11-08T05:00:00Z").toEpochMilli())
    check("UTC midnight is not the reset", QuestCycles.daily(java.time.Instant.parse("2026-11-08T02:00:00Z").toEpochMilli(), NY, mid).id == "daily:2026-11-07")
    val fallBack = QuestCycles.daily(ny(2026, 11, 1, 12), NY, mid)
    check("DST fall back day lasts 25h", fallBack.endMillis - fallBack.startMillis == 25 * HOUR)
    val spring = QuestCycles.daily(ny(2026, 3, 8, 12), NY, mid)
    check("DST spring forward day lasts 23h", spring.endMillis - spring.startMillis == 23 * HOUR)
    val gap = QuestCycles.daily(ny(2026, 3, 8, 12), NY, LocalTime.of(2, 30))
    check("a reset inside the DST gap happens right after it", gap.startMillis == ny(2026, 3, 8, 3, 30))
    val ordinary = QuestCycles.daily(ny(2026, 6, 10, 12), NY, mid)
    check("an ordinary day is 24h", ordinary.endMillis - ordinary.startMillis == 24 * HOUR && ordinary.contains(ny(2026, 6, 10, 12)))
    check("remaining time", ordinary.remainingMillis(ny(2026, 6, 10, 18)) == 6 * HOUR && ordinary.remainingMillis(ordinary.endMillis + 5) == 0L)

    val sunday = QuestCycles.weekly(ny(2026, 11, 8, 23, 59, 59), NY, DayOfWeek.MONDAY, mid)
    val monday = QuestCycles.weekly(ny(2026, 11, 9, 0, 0, 0), NY, DayOfWeek.MONDAY, mid)
    check("Sunday night is still last week", sunday.id == "weekly:2026-11-02")
    check("Monday 00:00 New York starts the week", monday.id == "weekly:2026-11-09")
    check("weekly end = next start", sunday.endMillis == monday.startMillis)
    check("an ordinary week is 168h", sunday.endMillis - sunday.startMillis == 168 * HOUR)
    val fallWeek = QuestCycles.weekly(ny(2026, 10, 28, 12), NY, DayOfWeek.MONDAY, mid)
    check("the week containing the fall-back (Sun Nov 1) is 169h", fallWeek.id == "weekly:2026-10-26" && fallWeek.endMillis - fallWeek.startMillis == 169 * HOUR)
    check("weekly ends on the next Monday", monday.endMillis == ny(2026, 11, 16, 0))
    check("mid-week stays in the week", QuestCycles.weekly(ny(2026, 11, 12, 15), NY, DayOfWeek.MONDAY, mid).id == "weekly:2026-11-09")
    check("formatRemaining", QuestCycles.formatRemaining(5 * HOUR + 12 * 60_000L) == "5h 12m" && QuestCycles.formatRemaining(50 * HOUR) == "2d 2h" && QuestCycles.formatRemaining(0) == "0s")
    check("current() follows the settings", QuestCycles.current(QuestPeriod.WEEKLY, ny(2026, 11, 12, 15), SETTINGS).id == "weekly:2026-11-09")
}

// ── Selection ───────────────────────────────────────────────

private fun template(id: String, o: QuestObjective, team: TeamRequirement = TeamRequirement.ANY, weight: Int = 10, group: String? = null, enabled: Boolean = true, period: QuestPeriod = QuestPeriod.DAILY) =
    QuestTemplate(id, period, id, "Do {target}", o.category, o, 5, emptySet(), listOf(PurchaseRewards.Spec(PurchaseRewards.Kind.COINS, "10000", 1)),
        if (o.needsTeam) TeamRequirement.REQUIRED else team, enabled, weight, group)

private fun selection() {
    val random = Random(1425)
    for (p in QuestPeriod.entries) {
        val pool = SETTINGS.pool(p)
        repeat(300) {
            for (team in listOf(true, false)) {
                val picks = QuestSelection.pick(pool, 3, team, random = random)
                check("${p.id}: 3 picks", picks.size == 3)
                check("${p.id}: unique ids", picks.map { it.id }.toSet().size == 3)
                check("${p.id}: unique objectives", picks.map { it.objective }.toSet().size == 3)
                val groups = picks.mapNotNull { it.group }
                check("${p.id}: unique groups", groups.toSet().size == groups.size)
                check("${p.id}: team eligibility (team=$team)", picks.all { it.team.allows(team) })
            }
        }
    }
    val small = listOf(template("a", QuestObjective.MINE_ORE), template("b", QuestObjective.KILL_MOB))
    check("underfilled pool gives fewer quests, never repeats", QuestSelection.pick(small, 3, false).map { it.id }.sorted() == listOf("a", "b"))
    val sameObjective = listOf(template("a", QuestObjective.MINE_ORE), template("b", QuestObjective.MINE_ORE), template("c", QuestObjective.KILL_MOB))
    check("never two quests with the same objective", QuestSelection.pick(sameObjective, 3, false).size == 2)
    val grouped = listOf(template("a", QuestObjective.MINE_ORE, group = "g"), template("b", QuestObjective.KILL_MOB, group = "g"))
    check("never two quests of one group", QuestSelection.pick(grouped, 2, false).size == 1)
    val disabled = listOf(template("a", QuestObjective.MINE_ORE, enabled = false), template("b", QuestObjective.KILL_MOB))
    check("disabled quests are never picked", QuestSelection.pick(disabled, 2, false).map { it.id } == listOf("b"))
    val teamOnly = listOf(template("t", QuestObjective.TEAM_ASSIST), template("solo", QuestObjective.PLAYER_KILL, team = TeamRequirement.NONE))
    check("no team: only the solo alternative", QuestSelection.pick(teamOnly, 2, false).map { it.id } == listOf("solo"))
    check("team: only the team quest", QuestSelection.pick(teamOnly, 2, true).map { it.id } == listOf("t"))
    val taken = QuestSelection.Taken(ids = setOf("a"), objectives = setOf(QuestObjective.KILL_MOB))
    val three = listOf(template("a", QuestObjective.MINE_ORE), template("b", QuestObjective.KILL_MOB), template("c", QuestObjective.EXPLORE))
    check("taken ids and objectives are excluded", QuestSelection.pick(three, 3, false, taken).map { it.id } == listOf("c"))
    val weighted = listOf(template("rare", QuestObjective.MINE_ORE, weight = 1), template("common", QuestObjective.KILL_MOB, weight = 99))
    val r = Random(7)
    val rare = (0 until 5000).count { QuestSelection.pick(weighted, 1, false, random = r).first().id == "rare" }
    check("weights are respected (rare picked $rare/5000)", rare in 10..120)
}

// ── Rules ───────────────────────────────────────────────────

private fun rules() {
    val daily = RerollSettings(1, 25.0, 2)
    check("first reroll free", RerollRules.price(daily, 0, 0, false, true) == RerollRules.Price.Free)
    check("then Credits", RerollRules.price(daily, 1, 0, false, true) == RerollRules.Price.Credits(25.0))
    check("paid limit", RerollRules.price(daily, 1, 2, false, true) == RerollRules.Price.LimitReached)
    check("ineligible team quest rerolls free", RerollRules.price(daily, 1, 2, true, true) == RerollRules.Price.Free)
    check("ineligible free only when enabled", RerollRules.price(daily, 1, 2, true, false) == RerollRules.Price.LimitReached)
    check("zero cost = free", RerollRules.price(RerollSettings(0, 0.0, 1), 0, 0, false, true) == RerollRules.Price.Free)
    check("no paid rerolls configured", RerollRules.price(RerollSettings(0, 75.0, 0), 0, 0, false, true) == RerollRules.Price.LimitReached)

    val a = QuestAntiAbuse(pvpSameVictimCooldownMillis = 30 * 60_000L, pvpMaxKillsPerHour = 3)
    val now = 10 * HOUR
    check("ordinary kill counts", QuestPvpRules.kill(false, false, null, now, a, 0) == CombatRules.PvpDecision.COUNT)
    check("same victim within the cooldown doesn't", QuestPvpRules.kill(false, false, now - 60_000L, now, a, 0) == CombatRules.PvpDecision.COOLDOWN)
    check("same victim after the cooldown does", QuestPvpRules.kill(false, false, now - 31 * 60_000L, now, a, 0) == CombatRules.PvpDecision.COUNT)
    check("hourly cap", QuestPvpRules.kill(false, false, null, now, a, 3) == CombatRules.PvpDecision.HOURLY_CAP)
    check("idle victim", QuestPvpRules.kill(false, true, null, now, a, 0) == CombatRules.PvpDecision.VICTIM_IDLE)
    check("teammates", QuestPvpRules.kill(true, false, null, now, a, 0) == CombatRules.PvpDecision.TEAMMATE)
    check("shared connection refused", QuestPvpRules.connection(true, true) == CombatRules.PvpDecision.SHARED_CONNECTION)
    check("failed lookup refused", QuestPvpRules.connection(null, true) == CombatRules.PvpDecision.UNKNOWN_CONNECTION)
    check("no shared connection counts", QuestPvpRules.connection(false, true) == CombatRules.PvpDecision.COUNT)
    check("shared allowed when the check is off", QuestPvpRules.connection(true, false) == CombatRules.PvpDecision.COUNT)
    check("won fight inside the window", QuestPvpRules.victory(now - 10_000L, now, 30_000L))
    check("no won fight when the victim never hit back", !QuestPvpRules.victory(null, now, 30_000L))
    check("no won fight outside the window", !QuestPvpRules.victory(now - 31_000L, now, 30_000L))
    check("assist window", QuestPvpRules.assist(now - 5_000L, now, 15_000L) && !QuestPvpRules.assist(now - 16_000L, now, 15_000L) && !QuestPvpRules.assist(null, now, 15_000L))
    check("damage cap per victim", QuestPvpRules.damageAllowance(0, 40) == 800L && QuestPvpRules.damageAllowance(790, 40) == 10L && QuestPvpRules.damageAllowance(900, 40) == 0L)
    check("no damage cap", QuestPvpRules.damageAllowance(1_000_000, 0) == Long.MAX_VALUE)

    // The environment rules are the bounty claim's: a kill that can't claim a bounty can't advance a quest.
    val killer = UUID.randomUUID()
    val victim = UUID.randomUUID()
    fun d(f: (BountyRules.Death) -> BountyRules.Death) = BountyRules.eligibility(f(BountyRules.Death(victim, killer)))
    check("ordinary Lifesteal kill eligible", d { it } is BountyRules.Eligibility.Eligible)
    check("teammates refused", d { it.copy(team = BountyRules.Team.TEAMMATES) } is BountyRules.Eligibility.Refused)
    check("recent teammates refused", d { it.copy(team = BountyRules.Team.RECENT_TEAMMATES) } is BountyRules.Eligibility.Refused)
    check("unknown teams refused", d { it.copy(team = BountyRules.Team.UNKNOWN) } is BountyRules.Eligibility.Refused)
    check("Ranked refused", d { it.copy(inRankedMatch = true) } is BountyRules.Eligibility.Refused && d { it.copy(inRankedArena = true) } is BountyRules.Eligibility.Refused)
    check("events and arenas refused", d { it.copy(inEvent = true) } is BountyRules.Eligibility.Refused && d { it.copy(inArena = true) } is BountyRules.Eligibility.Refused)
    check("NPC and non-Lifesteal refused", d { it.copy(killerIsNpc = true) } is BountyRules.Eligibility.Refused && d { it.copy(victimInLifesteal = false) } is BountyRules.Eligibility.Refused)
    check("self refused", BountyRules.eligibility(BountyRules.Death(victim, victim)) is BountyRules.Eligibility.Refused)
    check("allies are treated as teammates", "TeamCombatRules.Relation.ALLIES -> BountyRules.Team.TEAMMATES" in source("lifesteal/bounty/LifestealBountyManager.kt"))

    check("never 100% before complete", QuestProgress.percent(399, 400) == 99 && QuestProgress.percent(400, 400) == 100 && QuestProgress.percent(0, 400) == 0)
    check("bar never full before complete", QuestProgress.bar(399, 400).endsWith("&8|") && QuestProgress.bar(400, 400) == "&a" + "|".repeat(20) + "&8")
    check("display in configured units", QuestProgress.displayed(399, 20) == 19L && QuestProgress.displayed(400, 20) == 20L)
    check("team requirement", TeamRequirement.ANY.allows(false) && TeamRequirement.ANY.allows(true) && TeamRequirement.REQUIRED.allows(true) &&
        !TeamRequirement.REQUIRED.allows(false) && TeamRequirement.NONE.allows(false) && !TeamRequirement.NONE.allows(true))
    check("reward snapshot round trip", LifestealQuestStore.decodeRewards(LifestealQuestStore.encodeRewards(listOf(
        PurchaseRewards.Spec(PurchaseRewards.Kind.COINS, "15000", 1), PurchaseRewards.Spec(PurchaseRewards.Kind.CRATE_KEY, "vote", 2),
    ))) == listOf(PurchaseRewards.Spec(PurchaseRewards.Kind.COINS, "15000", 1), PurchaseRewards.Spec(PurchaseRewards.Kind.CRATE_KEY, "vote", 2)))
}

// ── Menu layout and GUI safety ──────────────────────────────

private fun layout() {
    val L = LifestealQuestLayout
    check("all menu slots distinct", L.ALL.toSet().size == L.ALL.size)
    check("all menu slots inside the menu", L.ALL.all { it in 0 until L.SIZE })
    check("3 daily and 3 weekly quest slots with a reroll button each",
        L.DAILY_QUESTS.size == 3 && L.WEEKLY_QUESTS.size == 3 && L.DAILY_REROLLS.size == 3 && L.WEEKLY_REROLLS.size == 3)
    check("reroll buttons sit right below their quest", L.DAILY_QUESTS.zip(L.DAILY_REROLLS).all { (q, r) -> r == q + 9 } && L.WEEKLY_QUESTS.zip(L.WEEKLY_REROLLS).all { (q, r) -> r == q + 9 })
    check("Close at the standard slot", L.CLOSE == L.SIZE - 5)
    check("confirm slots distinct and inside", setOf(L.CONFIRM_YES, L.CONFIRM_NO, L.CONFIRM_PREVIEW).size == 3 && listOf(L.CONFIRM_YES, L.CONFIRM_NO, L.CONFIRM_PREVIEW).all { it in 0 until L.CONFIRM_SIZE })

    val menu = source("lifesteal/quests/LifestealQuestMenu.kt")
    check("menu uses the shared CustomGui (GuiManager cancels clicks, 200ms cooldown)", "CustomGui(" in menu && "plugin.guiManager.open" in menu)
    check("GuiManager cancels clicks on menu slots and blocks shift/number/double clicks",
        source("gui/GuiManager.kt").let { "event.isCancelled = true" in it && "isShiftClick" in it && "NUMBER_KEY" in it && "DOUBLE_CLICK" in it })
    check("claim passes only the row id (re-checked in the store)", "manager.claim(player, assignmentId)" in menu)
    check("confirm passes the quest and price it showed", "manager.reroll(p, assignmentId, questId, price)" in menu)
    check("confirm is single-use", "if (used) return@setItem" in menu)
    check("menu never touches balances or rewards", "creditsManager.withdraw" !in menu && "economyManager" !in menu && "purchaseFulfillmentManager" !in menu)
    val manager = source("lifesteal/quests/LifestealQuestManager.kt")
    check("one quest action at a time per player", "busy.add(uuid)" in manager)
}

// ── Database ────────────────────────────────────────────────

private class World(val dir: File, wallet: LifestealQuestStore.Wallet? = null) {
    val db = DatabaseManager(dir, quietLogger()).also { it.start() }
    val store = LifestealQuestStore(db, wallet ?: LifestealQuestStore.Wallet { uuid, amount ->
        db.executeUpdate("UPDATE credits SET balance = balance - ? WHERE uuid = ? AND balance >= ?", amount, uuid.toString(), amount) == 1
    })
    init {
        db.createTable("CREATE TABLE IF NOT EXISTS credits (uuid TEXT PRIMARY KEY, balance REAL NOT NULL DEFAULT 0.0)")
        db.createTable("CREATE TABLE IF NOT EXISTS player_connections (uuid TEXT NOT NULL, connection_hash TEXT NOT NULL)")
        db.createTable("CREATE TABLE IF NOT EXISTS quest_cycle_progress (uuid TEXT, quest_id TEXT, progress INTEGER)")
        store.createTables()
    }
    fun credits(uuid: UUID, amount: Double) =
        db.execute("INSERT INTO credits (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance", uuid.toString(), amount)
    fun balance(uuid: UUID): Double = db.queryFirst("SELECT balance FROM credits WHERE uuid = ?", uuid.toString()) { it.getDouble(1) } ?: 0.0
    fun close() = db.shutdown()
}

private fun tempDir(): File = Files.createTempDirectory("lifesteal-quest-check").toFile().also { it.deleteOnExit() }

private fun database() {
    val dir = tempDir()
    var w = World(dir)
    val s = SETTINGS
    val p = UUID.randomUUID()
    val other = UUID.randomUUID()
    val now = ny(2026, 11, 8, 12)
    val daily = QuestCycles.current(QuestPeriod.DAILY, now, s)
    val weekly = QuestCycles.current(QuestPeriod.WEEKLY, now, s)
    w.db.execute("INSERT INTO quest_cycle_progress (uuid, quest_id, progress) VALUES (?, 'survival_q', 7)", p.toString())

    // Assignment.
    val board = w.store.ensureBoard(p, daily, 3, s.pool(QuestPeriod.DAILY), hasTeam = false, now = now, random = Random(1))
    check("3 daily quests assigned", board.size == 3 && board.map { it.slot } == listOf(0, 1, 2))
    check("assigned quests are distinct", board.map { it.questId }.toSet().size == 3 && board.map { it.objective }.toSet().size == 3)
    check("no team-only quest for a player without a team", board.none { it.team == TeamRequirement.REQUIRED })
    check("assignment is idempotent", w.store.ensureBoard(p, daily, 3, s.pool(QuestPeriod.DAILY), false, now).map { it.id } == board.map { it.id })
    val wBoard = w.store.ensureBoard(p, weekly, 3, s.pool(QuestPeriod.WEEKLY), hasTeam = true, now = now)
    check("weekly board independent of daily", wBoard.size == 3 && wBoard.all { it.period == QuestPeriod.WEEKLY } && wBoard.none { it.id in board.map { b -> b.id } })
    check("personal: another player gets their own board", w.store.ensureBoard(other, daily, 3, s.pool(QuestPeriod.DAILY), false, now).none { it.id in board.map { b -> b.id } })
    check("snapshot stores the template's target and rewards", board.all { a -> s.template(QuestPeriod.DAILY, a.questId)!!.let { it.storedTarget == a.target && it.rewards == a.rewards && it.describe() == a.description } })

    // Progress.
    val q = board[0]
    check("save below target", w.store.saveProgress(mapOf(q.id to q.target - 1), now).isEmpty() && w.store.get(q.id)!!.progress == q.target - 1)
    check("lower value never lowers progress", w.store.saveProgress(mapOf(q.id to 1L), now).isEmpty() && w.store.get(q.id)!!.progress == q.target - 1)
    check("direct lowering is refused by the trigger", throws { w.db.execute("UPDATE lifesteal_quest_assignments SET progress = 0 WHERE id = ?", q.id) })

    // Claim before completion.
    check("incomplete claim refused", w.store.claim(p, q.id, daily.id, 0, "P", now) == ClaimResult.NotComplete)
    check("in-memory progress can complete the claim, but not another player's", w.store.claim(other, q.id, daily.id, q.target, "O", now) == ClaimResult.NotFound)
    val completed = w.store.saveProgress(mapOf(q.id to q.target + 50), now)
    check("reaching the target completes once, capped", completed == listOf(q.id) && w.store.get(q.id)!!.progress == q.target && w.store.get(q.id)!!.state == LifestealQuestStore.State.COMPLETED)
    check("completion reported once", w.store.saveProgress(mapOf(q.id to q.target + 60), now).isEmpty())
    val claim = w.store.claim(p, q.id, daily.id, 0, "P", now)
    check("claim succeeds", claim is ClaimResult.Claimed)
    val payouts = (claim as ClaimResult.Claimed).payouts
    check("one payout row per reward", payouts.size == q.rewards.size && payouts.map { it.spec } == q.rewards)
    check("second claim refused", w.store.claim(p, q.id, daily.id, q.target, "P", now) == ClaimResult.AlreadyClaimed)
    check("claimed state is final", throws { w.db.execute("UPDATE lifesteal_quest_assignments SET state = 'ACTIVE' WHERE id = ?", q.id) })
    check("saves after a claim change nothing", w.store.saveProgress(mapOf(q.id to q.target * 5), now).isEmpty() && w.store.get(q.id)!!.state == LifestealQuestStore.State.CLAIMED)
    check("payouts wait for the delivery queue", w.store.unlinkedPayouts(p).map { it.id } == payouts.map { it.id })

    // Expired cycles never pay.
    val q2 = board[1]
    w.store.saveProgress(mapOf(q2.id to q2.target), now)
    val tomorrow = QuestCycles.current(QuestPeriod.DAILY, daily.endMillis + 1, s)
    check("completed quest of an ended cycle can't be claimed", w.store.claim(p, q2.id, tomorrow.id, q2.target, "P", daily.endMillis + 1) == ClaimResult.Expired)
    check("not even with a stale cycle id after the end", w.store.claim(p, q2.id, daily.id, q2.target, "P", daily.endMillis) == ClaimResult.Expired)
    check("a new cycle gives a fresh board", w.store.ensureBoard(p, tomorrow, 3, s.pool(QuestPeriod.DAILY), false, daily.endMillis + 1).let { b -> b.size == 3 && b.none { it.id in board.map { o -> o.id } } })

    // Rerolls.
    val rs = RerollSettings(1, 25.0, 1)
    fun req(a: LifestealQuestStore.Assignment, price: RerollRules.Price, expected: String = a.questId, team: Boolean = false, at: Long = now, pool: List<QuestTemplate> = s.pool(QuestPeriod.DAILY)) =
        LifestealQuestStore.RerollRequest(p, a.id, expected, QuestCycles.current(a.period, at, s), rs, true, team, price, pool, at, Random(3))
    val q3 = board[2]
    check("completed quest can't be rerolled", (w.store.reroll(req(q2, RerollRules.Price.Free)) as RerollResult.Refused).reason == RerollRules.Refusal.COMPLETED)
    check("claimed quest can't be rerolled", (w.store.reroll(req(q, RerollRules.Price.Free)) as RerollResult.Refused).reason == RerollRules.Refusal.CLAIMED)
    check("stale menu refused", (w.store.reroll(req(q3, RerollRules.Price.Free, expected = "something_else")) as RerollResult.Refused).reason == RerollRules.Refusal.STALE)
    check("wrong price refused (free shown as paid)", (w.store.reroll(req(q3, RerollRules.Price.Credits(25.0))) as RerollResult.Refused).reason == RerollRules.Refusal.PRICE_CHANGED)
    w.store.saveProgress(mapOf(q3.id to 1L), now)
    val first = w.store.reroll(req(q3, RerollRules.Price.Free))
    check("free reroll", first is RerollResult.Rerolled && first.price == RerollRules.Price.Free)
    val replacement = (first as RerollResult.Rerolled).replacement
    check("replacement is a different quest in the same slot, fresh progress", replacement.questId != q3.questId && replacement.slot == q3.slot && replacement.progress == 0L)
    check("replacement doesn't repeat an objective on the board", replacement.objective !in listOf(q.objective, q2.objective))
    check("old quest is final", w.store.get(q3.id)!!.state == LifestealQuestStore.State.REROLLED && throws { w.db.execute("UPDATE lifesteal_quest_assignments SET state = 'ACTIVE' WHERE id = ?", q3.id) })
    check("rerolled quest can't be rerolled again", (w.store.reroll(req(w.store.get(q3.id)!!, RerollRules.Price.Credits(25.0))) as RerollResult.Refused).reason == RerollRules.Refusal.STALE)
    check("usage counts the free reroll", w.store.rerollUsage(p, daily.id) == LifestealQuestStore.RerollUsage(1, 0))
    check("other quests untouched by the reroll", w.store.get(q.id)!!.state == LifestealQuestStore.State.CLAIMED && w.store.get(q2.id)!!.state == LifestealQuestStore.State.COMPLETED)
    check("rewards never duplicated by a reroll", w.store.unlinkedPayouts(p).size == payouts.size)

    w.credits(p, 10.0)
    val poor = w.store.reroll(req(replacement, RerollRules.Price.Credits(25.0)))
    check("insufficient Credits refused", (poor as RerollResult.Refused).reason == RerollRules.Refusal.INSUFFICIENT_CREDITS)
    check("nothing changed without Credits", w.balance(p) == 10.0 && w.store.get(replacement.id)!!.state == LifestealQuestStore.State.ACTIVE && w.store.rerollUsage(p, daily.id).paid == 0)
    check("free price shown after free used is refused", (w.store.reroll(req(replacement, RerollRules.Price.Free)) as RerollResult.Refused).reason == RerollRules.Refusal.PRICE_CHANGED)
    w.credits(p, 100.0)
    val paid = w.store.reroll(req(replacement, RerollRules.Price.Credits(25.0)))
    check("paid reroll", paid is RerollResult.Rerolled && w.balance(p) == 75.0)
    val third = (paid as RerollResult.Rerolled).replacement
    val held = w.store.heldIds(p, daily.id)
    check("no quest repeats in a cycle", held.size == 5 && third.questId !in listOf(q3.questId, replacement.questId))
    check("limit reached", (w.store.reroll(req(third, RerollRules.Price.Credits(25.0))) as RerollResult.Refused).reason == RerollRules.Refusal.LIMIT && w.balance(p) == 75.0)
    check("expired quest can't be rerolled", (w.store.reroll(req(third, RerollRules.Price.Free, at = daily.endMillis + 1)) as RerollResult.Refused).reason == RerollRules.Refusal.EXPIRED)
    check("reroll history stored with Credits", w.db.queryFirst("SELECT SUM(credits) FROM lifesteal_quest_rerolls WHERE uuid = ?", p.toString()) { it.getDouble(1) } == 25.0)

    // No replacement: everything else already held this cycle.
    val tiny = listOf(template("only_a", QuestObjective.MINE_ORE), template("only_b", QuestObjective.KILL_MOB))
    val tc = QuestCycles.current(QuestPeriod.DAILY, now, s)
    val u = UUID.randomUUID()
    val tb = w.store.ensureBoard(u, tc, 3, tiny, false, now)
    check("tiny pool underfills", tb.size == 2)
    val noRep = w.store.reroll(LifestealQuestStore.RerollRequest(u, tb[0].id, tb[0].questId, tc, rs, true, false, RerollRules.Price.Free, tiny, now))
    check("no replacement refused, nothing used", (noRep as RerollResult.Refused).reason == RerollRules.Refusal.NO_REPLACEMENT && w.store.rerollUsage(u, tc.id).free == 0)

    // A team-only quest after leaving the team rerolls for free, without using the free reroll.
    val teamPool = listOf(template("team_q", QuestObjective.TEAM_ASSIST), template("solo_q", QuestObjective.KILL_MOB), template("x_q", QuestObjective.EXPLORE))
    val t = UUID.randomUUID()
    val teamBoard = w.store.ensureBoard(t, tc, 1, teamPool.take(1), hasTeam = true, now = now)
    check("team player got the team quest", teamBoard.single().questId == "team_q")
    val freeIneligible = w.store.reroll(LifestealQuestStore.RerollRequest(t, teamBoard[0].id, "team_q", tc, RerollSettings(0, 25.0, 0), true, false, RerollRules.Price.Free, teamPool, now))
    check("ineligible team quest rerolled free into an eligible one", freeIneligible is RerollResult.Rerolled && (freeIneligible as RerollResult.Rerolled).replacement.team != TeamRequirement.REQUIRED)
    check("ineligible reroll doesn't use the free reroll", w.store.rerollUsage(t, tc.id) == LifestealQuestStore.RerollUsage(0, 0))

    // Credits are rolled back when something fails after the withdrawal.
    val failDir = tempDir()
    val failing = World(failDir, LifestealQuestStore.Wallet { _, _ -> error("wallet exploded after withdrawing") })
    val f = UUID.randomUUID()
    failing.credits(f, 100.0)
    val fb = failing.store.ensureBoard(f, tc, 1, s.pool(QuestPeriod.DAILY), false, now)
    check("a failing reroll throws and is rolled back", throws {
        failing.store.reroll(LifestealQuestStore.RerollRequest(f, fb[0].id, fb[0].questId, tc, RerollSettings(0, 25.0, 1), true, false, RerollRules.Price.Credits(25.0), s.pool(QuestPeriod.DAILY), now))
    })
    check("quest unchanged after the failed reroll", failing.store.get(fb[0].id)!!.state == LifestealQuestStore.State.ACTIVE && failing.store.board(f, tc.id).size == 1 && failing.store.rerollUsage(f, tc.id).paid == 0)
    val txDir = tempDir()
    lateinit var tx: World
    tx = World(txDir, LifestealQuestStore.Wallet { uuid, amount ->
        tx.db.executeUpdate("UPDATE credits SET balance = balance - ? WHERE uuid = ? AND balance >= ?", amount, uuid.toString(), amount)
        error("failed after the withdrawal")
    })
    tx.credits(f, 100.0)
    val txb = tx.store.ensureBoard(f, tc, 1, s.pool(QuestPeriod.DAILY), false, now)
    check("failure after withdrawal throws", throws {
        tx.store.reroll(LifestealQuestStore.RerollRequest(f, txb[0].id, txb[0].questId, tc, RerollSettings(0, 25.0, 1), true, false, RerollRules.Price.Credits(25.0), s.pool(QuestPeriod.DAILY), now))
    })
    check("withdrawn Credits rolled back", tx.balance(f) == 100.0)
    failing.close(); tx.close()

    // Anti-farming state.
    val victim = UUID.randomUUID()
    check("first unique victim counts", w.store.addUniqueVictim(wBoard[0].id, victim, now))
    check("same victim counts once per quest", !w.store.addUniqueVictim(wBoard[0].id, victim, now))
    check("same victim counts for another quest", w.store.addUniqueVictim(wBoard[1].id, victim, now))
    w.store.recordPvpKill(p, victim, now - 2 * HOUR)
    w.store.recordPvpKill(p, victim, now)
    check("pvp kills since", w.store.pvpKillsSince(now - HOUR).size == 1)
    w.store.prunePvpKills(now - HOUR)
    check("prune", w.store.pvpKillsSince(0).size == 1)
    w.db.execute("INSERT INTO player_connections (uuid, connection_hash) VALUES (?, 'h1'), (?, 'h1'), (?, 'h2')", p.toString(), victim.toString(), other.toString())
    check("shared connection found", w.store.sharesConnection(p, victim) == true && w.store.sharesConnection(p, other) == false)
    val bare = World(tempDir()).also { it.db.execute("DROP TABLE player_connections") }
    check("failed lookup = null (never counts)", bare.store.sharesConnection(p, victim) == null)
    bare.close()

    // Restart: everything is still there.
    val claimedIds = payouts.map { it.id }
    w.close()
    w = World(dir)
    check("board survives a restart", w.store.board(p, daily.id).map { it.id }.toSet() == setOf(q.id, q2.id, third.id))
    check("claim survives a restart", w.store.get(q.id)!!.state == LifestealQuestStore.State.CLAIMED && w.store.claim(p, q.id, daily.id, q.target, "P", now) == ClaimResult.AlreadyClaimed)
    check("unqueued payouts survive a restart", w.store.unlinkedPayouts(null).map { it.id }.containsAll(claimedIds))
    check("reroll usage survives a restart", w.store.rerollUsage(p, daily.id) == LifestealQuestStore.RerollUsage(1, 1))
    check("link once", w.store.link(claimedIds[0], 77) && !w.store.link(claimedIds[0], 78))
    check("linked payouts leave the queue", claimedIds[0] !in w.store.unlinkedPayouts(p).map { it.id })

    // Survival isolation.
    check("Survival quest progress untouched", w.db.queryFirst("SELECT progress FROM quest_cycle_progress WHERE uuid = ?", p.toString()) { it.getLong(1) } == 7L)
    check("every row is a Lifesteal row", w.db.queryFirst("SELECT COUNT(*) FROM lifesteal_quest_assignments WHERE gamemode != 'lifesteal'") { it.getInt(1) } == 0)
    val tables = w.db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE '%quest%'") { it.getString(1) }
    check("only lifesteal_quest_* tables added", tables.filter { it != "quest_cycle_progress" }.all { it.startsWith("lifesteal_quest_") })
    w.close()
}

private fun concurrency() {
    val w = World(tempDir())
    val s = SETTINGS
    val now = ny(2026, 11, 8, 12)
    val cycle = QuestCycles.current(QuestPeriod.DAILY, now, s)
    val p = UUID.randomUUID()

    // Two loads at once never assign twice.
    val pool = Executors.newFixedThreadPool(8)
    val go = CountDownLatch(1)
    val futures = (0 until 8).map { pool.submit<List<LifestealQuestStore.Assignment>> { go.await(); w.store.ensureBoard(p, cycle, 3, s.pool(QuestPeriod.DAILY), false, now) } }
    go.countDown()
    val boards = futures.map { it.get(60, TimeUnit.SECONDS) }
    check("concurrent loads: one board", boards.all { b -> b.map { it.id } == boards[0].map { it.id } } && w.store.board(p, cycle.id).size == 3)

    // Concurrent claims: exactly one wins, one set of payouts.
    val q = boards[0][0]
    w.store.saveProgress(mapOf(q.id to q.target), now)
    val wins = AtomicInteger()
    val go2 = CountDownLatch(1)
    val claims = (0 until 16).map { pool.submit { go2.await(); if (w.store.claim(p, q.id, cycle.id, q.target, "P", now) is ClaimResult.Claimed) wins.incrementAndGet() } }
    go2.countDown()
    claims.forEach { it.get(60, TimeUnit.SECONDS) }
    check("concurrent claims: exactly one", wins.get() == 1)
    check("concurrent claims: one set of payouts", w.db.queryFirst("SELECT COUNT(*) FROM lifesteal_quest_payouts WHERE assignment_id = ?", q.id) { it.getInt(1) } == q.rewards.size)

    // Concurrent paid rerolls of one quest: one charge.
    w.credits(p, 1000.0)
    val r = boards[0][1]
    val rerolled = AtomicInteger()
    val go3 = CountDownLatch(1)
    val rerolls = (0 until 16).map {
        pool.submit {
            go3.await()
            val res = w.store.reroll(LifestealQuestStore.RerollRequest(p, r.id, r.questId, cycle, RerollSettings(0, 25.0, 5), true, false, RerollRules.Price.Credits(25.0), s.pool(QuestPeriod.DAILY), now))
            if (res is RerollResult.Rerolled) rerolled.incrementAndGet()
        }
    }
    go3.countDown()
    rerolls.forEach { it.get(60, TimeUnit.SECONDS) }
    check("concurrent rerolls of one quest: one reroll, one charge", rerolled.get() == 1 && w.balance(p) == 975.0 && w.store.board(p, cycle.id).size == 3)
    pool.shutdown()
    w.close()
}

// ── Wiring ──────────────────────────────────────────────────

private fun wiring() {
    check("/quests is routed per gamemode (NETWORK)", GamemodeAssignments.COMMANDS["quests"] == GamemodeScope.NETWORK)
    check("/daily stays Survival", GamemodeAssignments.COMMANDS["daily"] == GamemodeScope.SURVIVAL)
    val router = source("command/QuestCommandRouter.kt")
    check("router sends Lifesteal senders to the Lifesteal quests", "gamemodeOf(sender) == ServerGamemode.LIFESTEAL" in router && "lifesteal.onCommand" in router)
    check("router keeps the Survival scope and permission", "isAllowed(GamemodeScope.SURVIVAL, sender)" in router && "\"joshymc.quests\"" in router)
    val commands = source("manager/CommandManager.kt")
    check("CommandManager uses the router", "QuestCommandRouter(" in commands && "QuestCommandRouter.COMMAND_PERMISSION" in commands)
    val yml = File("src/main/resources/plugin.yml").readText()
    check("permissions declared", "joshymc.lifesteal.quests.use:" in yml && "joshymc.lifesteal.quests.admin:" in yml)
    val listeners = source("manager/ListenerManager.kt")
    check("listener only where Lifesteal is hosted", Regex("""if \(plugin\.lifestealQuestManager\.hosted\) \{\s+pm\.registerEvents\(com\.liam\.joshymc\.lifesteal\.quests\.LifestealQuestListener""").containsMatchIn(listeners))
    val main = source("Joshymc.kt")
    check("started, reloaded and saved on shutdown", "lifestealQuestManager.refresh()" in main && "shutdown.step(\"lifestealQuestManager.stop\"" in main)
    val manager = source("lifesteal/quests/LifestealQuestManager.kt")
    check("only where the economy is Lifesteal's", "TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL" in manager && "processing && settings?.enabled == true" in manager)
    check("rewards go through the store-delivery queue", "purchaseFulfillmentManager.enqueue(" in manager && "reference = \"lsquest:" in manager)
    check("delivery messages say quest reward", "\"quest reward\"" in source("manager/PurchaseFulfillmentManager.kt"))
    val pkg = File("src/main/kotlin/com/liam/joshymc/lifesteal/quests").listFiles()!!.joinToString("\n") { it.readText() }
    check("no command-based rewards", "dispatchCommand" !in pkg && "performCommand" !in pkg)
    check("no heart changes from quests", "lifestealHeartManager" !in pkg && "HeartStore" !in pkg)
    check("never touches the Survival quest tables", Regex("""(FROM|INTO|UPDATE|JOIN)\s+quest_cycle_progress""").find(pkg) == null)
    check("Survival quests never touch Lifesteal quest tables", "lifesteal_quest" !in source("manager/QuestCycleManager.kt"))
    check("PvP eligibility reused from bounties", "plugin.lifestealBountyManager.pvpFacts(victim, attacker)" in manager && "BountyRules.eligibility(f)" in manager)
    check("bounty claims feed quests once paid", source("lifesteal/bounty/LifestealBountyManager.kt").split("plugin.lifestealQuestBountyClaim(").size == 3)
    check("multi-block breakers feed quests", listOf("listener/VeinminerListener.kt", "listener/TreeFellerListener.kt", "listener/DrillMiningListener.kt", "listener/enchant/ToolEnchantListener.kt")
        .all { "plugin.lifestealQuestBlockBreak(player," in source(it) })
    check("own placed-block markers", "PlacedBlockTracker(plugin, \"lifesteal_quest_placed\")" in manager)
    check("Survival milestone markers unchanged", "keyName: String = \"milestone_placed\"" in source("milestone/PlacedBlockTracker.kt"))
    val listener = source("lifesteal/quests/LifestealQuestListener.kt")
    check("progress handlers only read (MONITOR)", Regex("""priority = EventPriority\.(HIGH|HIGHEST|NORMAL|LOW)\b""").find(listener) == null)
    check("listener never cancels events", "isCancelled = true" !in listener && "setCancelled" !in listener)
    check("disabled = no new progress", "if (!running" in manager && Regex("""fun claim\(player: Player, assignmentId: Long\): ActionResult \{\s+unavailableReason""").containsMatchIn(manager))
    check("docs exist", File("docs/lifesteal-quests.md").exists())
}
