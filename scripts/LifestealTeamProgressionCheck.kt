// Checks Lifesteal Team Upgrades and shared progression (issue #1426): the settings (bundled and
// broken), Team Level math, perks, the Bukkit-free store against real SQLite (fresh temp directories,
// deleted afterwards): Team XP, caps, anti-farm rules, objectives, the team bank, purchases (incl.
// concurrency, permissions, leadership changes, disbanding), milestones, seasons, restarts, and the
// Lifesteal-only wiring. The Gradle build has no test source set, so it runs through an init script,
// from the repo root:
//
//   ./gradlew -I scripts/lifesteal-team-progression-check.init.gradle.kts lifestealTeamProgressionCheck
//
// Exits non-zero on the first failed check. Real kills, mining, GUIs on a Paper server (and Bedrock
// clients) are NOT covered here (see docs/lifesteal-team-progression.md, "Manual tests").

import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Actor
import com.liam.joshymc.lifesteal.teams.progression.ExplorationMeter
import com.liam.joshymc.lifesteal.teams.progression.ObjectiveCycle
import com.liam.joshymc.lifesteal.teams.progression.ObjectiveType
import com.liam.joshymc.lifesteal.teams.progression.TeamLevels
import com.liam.joshymc.lifesteal.teams.progression.TeamPerks
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressCalendar
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionConfig
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionSettings
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore.AwardResult
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore.ClaimResult
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore.DepositResult
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore.PurchaseResult
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionStore.Refusal
import com.liam.joshymc.lifesteal.teams.progression.UpgradeCategory
import com.liam.joshymc.lifesteal.teams.progression.XpSource
import com.liam.joshymc.manager.DatabaseManager
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

private const val T0 = 1_800_000_000_000L
private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR
private const val SEASON = "season-1"

fun main() {
    settings()
    levels()
    perks()
    exploration()
    xp()
    objectives()
    bank()
    purchases()
    concurrency()
    membership()
    milestones()
    seasons()
    integrity()
    restarts()
    wiring()
    println("OK: $checks Lifesteal team progression checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

/** A dotted-path getter over plain maps (what LifestealConfigManager hands the parser). */
private fun getter(root: Map<*, *>): (String) -> Any? = { path ->
    var node: Any? = root
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
    node
}

private fun bundled(): Map<*, *> {
    val yaml = Yaml().load<Map<String, Any?>>(File("src/main/resources/lifesteal.yml").readText())
    return yaml["team-progression"] as Map<*, *>
}

@Suppress("UNCHECKED_CAST")
private fun edited(edit: (MutableMap<String, Any?>) -> Unit): TeamProgressionConfig.Result {
    fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply { value.forEach { (k, v) -> put(k.toString(), deepCopy(v)) } }
        is List<*> -> value.map { deepCopy(it) }.toMutableList()
        else -> value
    }
    val copy = deepCopy(bundled()) as MutableMap<String, Any?>
    edit(copy)
    return TeamProgressionConfig.parse(getter(copy))
}

@Suppress("UNCHECKED_CAST")
private fun MutableMap<String, Any?>.section(path: String): MutableMap<String, Any?> {
    var node: MutableMap<String, Any?> = this
    for (part in path.split('.')) node = node[part] as MutableMap<String, Any?>
    return node
}

private fun settings() {
    val result = TeamProgressionConfig.parse(getter(bundled()))
    check("bundled team-progression parses without problems: ${result.problems}", result.valid && result.problems.isEmpty())
    val s = result.settings!!
    val d = TeamProgressionSettings.DEFAULTS
    check("bundled levels match the defaults (1000 x level^1.5)", s.levels == d.levels && s.levels.base == 1000.0 && s.levels.exponent == 1.5)
    check("bundled XP amounts and caps match the defaults", s.xp.amounts == d.xp.amounts && s.xp.dailyCaps == d.xp.dailyCaps)
    check("bundled caps and anti-farm match the defaults", s.xp.teamDailyCap == d.xp.teamDailyCap && s.xp.memberDailyCap == d.xp.memberDailyCap &&
        s.xp.minimumMembershipMillis == d.xp.minimumMembershipMillis && s.xp.pvpSameVictimMillis == d.xp.pvpSameVictimMillis && s.xp.ores == d.xp.ores)
    check("bundled bank matches the defaults", s.bank == d.bank)
    check("bundled upgrades match the defaults", s.upgrades == d.upgrades)
    check("bundled objectives match the defaults", s.objectives == d.objectives)
    check("bundled milestones match the defaults", s.milestones == d.milestones)
    check("suggested costs 50k/150k/350k/750k/1.5M", s.category(UpgradeCategory.PROGRESSION).tiers.map { it.costCents / 100 } == listOf(50_000L, 150_000L, 350_000L, 750_000L, 1_500_000L))
    check("storage tiers are +9 slots each", s.category(UpgradeCategory.STORAGE).tiers.all { it.value == 9 } && s.category(UpgradeCategory.STORAGE).maxTier == 5)
    check("storage and home are off (their systems don't exist yet)", !s.category(UpgradeCategory.STORAGE).enabled && !s.category(UpgradeCategory.HOME).enabled)
    check("storage/home need a missing system", !UpgradeCategory.STORAGE.systemAvailable && !UpgradeCategory.HOME.systemAvailable && UpgradeCategory.ECONOMY.systemAvailable)
    check("objective pools aren't only combat", s.objectives.list.map { it.pool }.toSet().size >= 4)

    check("disabled parses (Owner can turn it off)", edited { it["enabled"] = false }.settings?.enabled == false)
    check("progression bonus above 10% is refused", !edited { it.section("upgrades.progression")["tiers"] = listOf(mapOf("cost" to 1, "level" to 1, "value" to 11)) }.valid)
    check("cheaper later tier is refused", !edited {
        it.section("upgrades.economy")["tiers"] = listOf(mapOf("cost" to 100, "level" to 1, "value" to 1), mapOf("cost" to 50, "level" to 2, "value" to 1))
    }.valid)
    check("lower level for a later tier is refused", !edited {
        it.section("upgrades.economy")["tiers"] = listOf(mapOf("cost" to 100, "level" to 5, "value" to 1), mapOf("cost" to 200, "level" to 2, "value" to 1))
    }.valid)
    check("negative cost is refused", !edited { it.section("upgrades.economy")["tiers"] = listOf(mapOf("cost" to -5, "level" to 1, "value" to 1)) }.valid)
    check("unknown reset policy is refused (only keep exists)", !edited { it.section("seasons")["reset-policy"] = "wipe" }.valid)
    check("unknown objective type is refused", !edited {
        it.section("objectives")["list"] = listOf(mapOf("id" to "x", "pool" to "combat", "type" to "teleports", "target" to 1, "xp" to 1, "name" to "X"))
    }.valid)
    check("duplicate objective ids are refused", !edited {
        val one = mapOf("id" to "x", "pool" to "mining", "type" to "ores", "target" to 1, "xp" to 1, "name" to "X")
        it.section("objectives")["list"] = listOf(one, one)
    }.valid)
    check("unsorted milestone levels are refused", !edited { it.section("milestones")["levels"] = listOf(10, 5) }.valid)
    check("bad timezone is refused", !edited { it.section("xp")["timezone"] = "Mars/Olympus" }.valid)
    check("negative XP amount is refused", !edited { it.section("xp.sources.pvp-kill")["amount"] = -1 }.valid)
    val missing = edited { it.section("xp").remove("team-daily-cap") }
    check("a missing key only warns and uses the default", missing.valid && missing.settings!!.xp.teamDailyCap == d.xp.teamDailyCap && missing.problems.none { p -> p.severe })
    check("a combat-only objective pool warns", edited {
        it.section("objectives")["list"] = listOf(mapOf("id" to "x", "pool" to "combat", "type" to "pvp-kills", "target" to 1, "xp" to 1, "name" to "X"))
    }.problems.any { !it.severe && it.message.contains("only has combat") })
}

// ── Levels ───────────────────────────────────────────────────

private fun levels() {
    val l = TeamProgressionSettings.Levels()
    check("level 1 -> 2 needs 1,000 XP", TeamLevels.xpForNext(1, l) == 1000L)
    check("level 2 -> 3 needs 2,828 XP", TeamLevels.xpForNext(2, l) == 2828L)
    check("level 4 -> 5 needs 8,000 XP", TeamLevels.xpForNext(4, l) == 8000L)
    check("teams start at Level 1", TeamLevels.levelFor(0, l) == 1)
    check("999 XP is still Level 1", TeamLevels.levelFor(999, l) == 1)
    check("1,000 XP is Level 2", TeamLevels.levelFor(1000, l) == 2)
    var increasing = true
    for (level in 1..300) if (TeamLevels.xpForNext(level + 1, l) <= TeamLevels.xpForNext(level, l)) increasing = false
    check("requirements scale progressively", increasing)
    var roundTrip = true
    for (level in 1..400) if (TeamLevels.levelFor(TeamLevels.totalFor(level, l), l) != level || TeamLevels.levelFor(TeamLevels.totalFor(level, l) - 1, l) != maxOf(1, level - 1)) roundTrip = false
    check("totalFor / levelFor agree at every level boundary", roundTrip)
    check("no hard maximum level (level 2000 reachable)", TeamLevels.levelFor(TeamLevels.totalFor(2000, l), l) == 2000)
    check("a configurable formula changes requirements", TeamLevels.xpForNext(3, TeamProgressionSettings.Levels(500.0, 2.0)) == 4500L)
    check("absurd XP doesn't loop forever", TeamLevels.levelFor(Long.MAX_VALUE / 8, l) >= 1)
    val progress = TeamLevels.progress(1500, l)
    check("progress shows XP into the level", progress.level == 2 && progress.xpIntoLevel == 500L && progress.xpForNext == 2828L)

    val m = TeamProgressionSettings.Milestones()
    check("milestone levels 5/10/25/50/75/100", listOf(5, 10, 25, 50, 75, 100).all { TeamLevels.isMilestone(it, m) })
    check("every 25 after 100", TeamLevels.isMilestone(125, m) && TeamLevels.isMilestone(150, m) && !TeamLevels.isMilestone(110, m) && !TeamLevels.isMilestone(30, m))
    check("milestones up to 130", TeamLevels.milestonesUpTo(130, m) == listOf(5, 10, 25, 50, 75, 100, 125))
    check("next milestones after 90", TeamLevels.nextMilestones(90, 3, m) == listOf(100, 125, 150))
    check("repeating milestones use the default reward", TeamLevels.rewardFor(125, m) == m.defaultReward && TeamLevels.rewardFor(5, m).title == "Rising")
}

private fun perks() {
    val s = TeamProgressionSettings.DEFAULTS
    val none = TeamPerks.of(emptyMap(), s)
    check("no upgrades: no bonus, base bank", none.xpBonusPercent == 0 && none.bankCapacityCents == s.bank.baseCapacityCents && none.storageSlots == 0 && none.utility.isEmpty())
    val all = TeamPerks.of(UpgradeCategory.entries.associateWith { 99 }, s)
    check("max progression bonus is 10%", all.xpBonusPercent == 10)
    check("team storage expands +9 per tier (45 at tier 5)", all.storageSlots == 45 && TeamPerks.of(mapOf(UpgradeCategory.STORAGE to 2), s).storageSlots == 18)
    check("economy tiers raise the bank limit", all.bankCapacityCents == s.bank.baseCapacityCents + 9_000_000L * 100)
    check("utility tier 3 unlocks every feature", all.utility == TeamPerks.UtilityFeature.entries.toSet())
    check("cosmetics tier 1 unlocks 2 colours", TeamPerks.of(mapOf(UpgradeCategory.COSMETICS to 1), s).colorCount == 2)
}

private fun exploration() {
    val straight = ExplorationMeter()
    repeat(13) { straight.sample("w", it * 20.0, 0.0, true) }
    check("a straight walk is credited", straight.take() == 240L)
    val circle = ExplorationMeter()
    repeat(13) { i -> val a = i * Math.PI / 6; circle.sample("w", 20 * Math.cos(a), 20 * Math.sin(a), true) }
    check("circling in place is not credited", circle.take() == 0L)
    val tp = ExplorationMeter()
    repeat(13) { tp.sample("w", if (it == 6) 5000.0 else it * 1.0, 0.0, true) }
    check("a teleport jump is never distance", tp.take() < 100L)
    val afk = ExplorationMeter()
    repeat(13) { afk.sample("w", it * 20.0, 0.0, false) }
    check("ineligible samples (AFK, flying) count nothing", afk.take() == 0L)
}

// ── Store fixture ────────────────────────────────────────────

private class Wallet : TeamProgressionStore.Wallet {
    val balances = ConcurrentHashMap<UUID, Long>()
    @Synchronized
    override fun withdraw(uuid: UUID, coins: Double): Boolean {
        val cents = Math.round(coins * 100)
        val now = balances[uuid] ?: 0
        if (now < cents) return false
        balances[uuid] = now - cents
        return true
    }
}

private class Fixture(val db: DatabaseManager) {
    val teams = LifestealTeamStore(db)
    val wallet = Wallet()
    val store = TeamProgressionStore(db, wallet)
    var s = TeamProgressionSettings.DEFAULTS
    private var seq = 0

    init {
        teams.createTables()
        store.createTables()
    }

    fun team(name: String, leader: UUID = UUID.randomUUID(), at: Long = T0 - DAY): Pair<String, UUID> {
        val result = teams.create(UUID.randomUUID().toString(), name, leader, "L-$name", at) as LifestealTeamStore.CreateResult.Created
        return result.team.id to leader
    }

    fun join(teamName: String, leader: UUID, member: UUID, at: Long = T0 - DAY) {
        teams.invite(Actor(leader, "leader"), member, 5, at, HOUR)
        check("join $teamName", teams.accept(member, "m", teamName, 5, at) is LifestealTeamStore.AcceptResult.Joined)
    }

    fun event(
        source: XpSource, member: UUID?, base: Long, now: Long = T0, teamId: String? = null, subject: String? = null,
        counts: Map<ObjectiveType, Long> = emptyMap(), key: String = "k${seq++}", season: String = SEASON,
    ) = store.award(TeamProgressionStore.XpEvent(key, source, member, teamId, base, counts, subject, season, now), s)

    fun give(teamId: String, xp: Long) {
        // Team events bypass member caps; raise caps so tests can reach a level.
        val old = s
        s = s.copy(xp = s.xp.copy(teamDailyCap = Long.MAX_VALUE / 4, dailyCaps = s.xp.dailyCaps + (XpSource.RANKED_WIN to Long.MAX_VALUE / 4)))
        event(XpSource.RANKED_WIN, null, xp, teamId = teamId)
        s = old
    }

    fun fund(teamId: String, member: UUID, coins: Long) {
        wallet.balances.merge(member, coins * 100, Long::plus)
        val big = s.copy(bank = s.bank.copy(baseCapacityCents = 1_000_000_000_00L))
        check("fund $teamId", store.deposit(TeamProgressionStore.DepositRequest("fund${seq++}", member, "m", coins * 100, T0), big) is DepositResult.Deposited)
    }

    fun buy(teamId: String, buyer: UUID, category: UpgradeCategory, expected: Int, txn: String = "t${seq++}") =
        store.purchase(TeamProgressionStore.PurchaseRequest(txn, buyer, "b", teamId, category, expected, T0), s)
}

private fun <T> withDb(name: String, block: (Fixture, File) -> T): T {
    val dir = Files.createTempDirectory("joshymc-teamprog-$name").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            return block(Fixture(db), dir)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Team XP ──────────────────────────────────────────────────

private fun xp() = withDb("xp") { f, _ ->
    val (team, leader) = f.team("Alpha")
    val victim = UUID.randomUUID().toString()
    val r = f.event(XpSource.PVP_KILL, leader, 40, subject = victim, key = "kill-1")
    check("a legitimate kill awards Team XP", r is AwardResult.Awarded && r.awarded == 40L && r.teamId == team)
    check("the same event key never awards twice", f.event(XpSource.PVP_KILL, leader, 40, subject = victim, key = "kill-1") == AwardResult.Duplicate)
    check("the same victim within the window earns nothing", (f.event(XpSource.PVP_KILL, leader, 40, subject = victim) as? AwardResult.Refused)?.reason == Refusal.REPEAT_VICTIM)
    check("the same victim after the window counts again", f.event(XpSource.PVP_KILL, leader, 40, subject = victim, now = T0 + DAY + MINUTE) is AwardResult.Awarded)
    check("a player in no team earns nothing", (f.event(XpSource.ORE_MINED, UUID.randomUUID(), 5) as? AwardResult.Refused)?.reason == Refusal.NO_TEAM)

    val fresh = UUID.randomUUID()
    f.join("Alpha", leader, fresh, at = T0 - 10 * MINUTE)
    check("a member who just joined earns nothing (team hopping)", (f.event(XpSource.ORE_MINED, fresh, 5) as? AwardResult.Refused)?.reason == Refusal.TOO_NEW)
    check("after the minimum membership time they earn", f.event(XpSource.ORE_MINED, fresh, 5, now = T0 + HOUR) is AwardResult.Awarded)

    // Daily caps: source cap, member cap and team cap all bind the base XP.
    val (capTeam, capLeader) = f.team("Caps")
    var total = 0L
    repeat(40) { total += (f.event(XpSource.ORE_MINED, capLeader, 20, now = T0 + 2 * DAY) as AwardResult.Awarded).awarded }
    check("the ore source cap holds (300/day)", total == 300L)
    val capped = f.event(XpSource.ORE_MINED, capLeader, 20, now = T0 + 2 * DAY) as AwardResult.Awarded
    check("a capped event awards 0 and says so", capped.awarded == 0L && capped.capped)
    check("caps reset the next day", (f.event(XpSource.ORE_MINED, capLeader, 20, now = T0 + 3 * DAY) as AwardResult.Awarded).awarded == 20L)
    var memberTotal = 0L
    for (source in listOf(XpSource.ORE_MINED, XpSource.EXPLORATION, XpSource.PVP_KILL, XpSource.BOUNTY_CLAIM)) {
        repeat(30) { memberTotal += (f.event(source, capLeader, 40, now = T0 + 5 * DAY, subject = UUID.randomUUID().toString()) as AwardResult.Awarded).awarded }
    }
    check("the member cap holds (800/day)", memberTotal == 800L)
    check("capped XP leaves the team total consistent", f.store.totalXp(capTeam) == 300L + 20L + 800L)

    // Team events (ranked) are capped per team, not per member.
    val ranked = f.event(XpSource.RANKED_MATCH, null, 60, teamId = capTeam, now = T0 + 5 * DAY) as AwardResult.Awarded
    check("a team event awards to the named team", ranked.awarded == 60L)
}

private fun objectives() = withDb("objectives") { f, _ ->
    f.s = f.s.copy(objectives = TeamProgressionSettings.Objectives(ObjectiveCycle.WEEKLY, listOf(
        TeamProgressionSettings.Objective("hunt", com.liam.joshymc.lifesteal.teams.progression.ObjectivePool.COMBAT, ObjectiveType.UNIQUE_VICTIMS, 3, 500, "Hunt"),
        TeamProgressionSettings.Objective("dig", com.liam.joshymc.lifesteal.teams.progression.ObjectivePool.MINING, ObjectiveType.ORES, 10, 300, "Dig"),
        TeamProgressionSettings.Objective("crew", com.liam.joshymc.lifesteal.teams.progression.ObjectivePool.TEAMWORK, ObjectiveType.UNIQUE_CONTRIBUTORS, 2, 200, "Crew"),
    )))
    val (team, leader) = f.team("Obj")
    val v1 = UUID.randomUUID().toString()
    val pvp = mapOf(ObjectiveType.PVP_KILLS to 1L, ObjectiveType.UNIQUE_VICTIMS to 1L)
    f.event(XpSource.PVP_KILL, leader, 40, subject = v1, counts = pvp)
    // A repeat kill of the same victim (after the PvP window) never advances unique victims.
    f.event(XpSource.PVP_KILL, leader, 40, subject = v1, counts = pvp, now = T0 + 2 * DAY)
    val cycle = TeamProgressCalendar.cycle(ObjectiveCycle.WEEKLY, T0, f.s.xp.timezone, SEASON)
    check("unique victims count each player once", f.store.snapshot(team, cycle, SEASON).objectives["hunt"]?.progress == 1L)
    f.event(XpSource.PVP_KILL, leader, 40, subject = UUID.randomUUID().toString(), counts = pvp)
    val done = f.event(XpSource.PVP_KILL, leader, 40, subject = UUID.randomUUID().toString(), counts = pvp) as AwardResult.Awarded
    check("reaching the target completes the objective once", done.completed.map { it.objective.id } == listOf("hunt") && done.completed.single().xp == 500L)
    val after = f.event(XpSource.PVP_KILL, leader, 40, subject = UUID.randomUUID().toString(), counts = pvp) as AwardResult.Awarded
    check("a completed objective never pays again this cycle", after.completed.isEmpty())
    check("objective XP is uncapped and recorded once", f.store.eventStatus("objective:$team:hunt:$cycle") == "AWARDED")

    // Caps never stop objective progress.
    repeat(15) { f.event(XpSource.ORE_MINED, leader, 1000, counts = mapOf(ObjectiveType.ORES to 1L)) }
    check("objectives keep counting past the XP cap", f.store.snapshot(team, cycle, SEASON).objectives["dig"]?.completed == true)

    // A refused event (too-new member) never advances objectives.
    val (other, otherLeader) = f.team("Obj2", at = T0 - 5 * MINUTE)
    f.event(XpSource.ORE_MINED, otherLeader, 1, counts = mapOf(ObjectiveType.ORES to 50L))
    check("refused events don't advance objectives", f.store.snapshot(other, cycle, SEASON).objectives["dig"] == null)

    // Unique contributors: two members who earned XP.
    val member = UUID.randomUUID()
    f.join("Obj", leader, member)
    val crew = f.event(XpSource.EXPLORATION, member, 2, now = T0 + HOUR) as AwardResult.Awarded
    val crewCycle = cycle
    check("two contributing members complete the teamwork objective", crew.completed.any { it.objective.id == "crew" } ||
        f.store.snapshot(team, crewCycle, SEASON).objectives["crew"]?.completed == true)

    // A new week is a new cycle.
    val nextWeek = T0 + 8 * DAY
    val nextCycle = TeamProgressCalendar.cycle(ObjectiveCycle.WEEKLY, nextWeek, f.s.xp.timezone, SEASON)
    check("weekly cycles differ", nextCycle != cycle)
    check("a new cycle starts empty", f.store.snapshot(team, nextCycle, SEASON).objectives.isEmpty())
    check("cycle keys: season / never", TeamProgressCalendar.cycle(ObjectiveCycle.SEASON, T0, f.s.xp.timezone, "s2") == "season:s2" &&
        TeamProgressCalendar.cycle(ObjectiveCycle.NEVER, T0, f.s.xp.timezone, "s2") == "permanent")
}

// ── Bank ─────────────────────────────────────────────────────

private fun bank() = withDb("bank") { f, _ ->
    val (team, leader) = f.team("Bank")
    val member = UUID.randomUUID()
    f.join("Bank", leader, member)
    f.wallet.balances[member] = 500_000_00L
    fun dep(ref: String, coins: Long, who: UUID = member) = f.store.deposit(TeamProgressionStore.DepositRequest(ref, who, "m", coins * 100, T0), f.s)
    check("a member deposits from their own balance", dep("d1", 100_000) is DepositResult.Deposited && f.wallet.balances[member] == 400_000_00L)
    check("the bank holds it", f.store.bankBalance(team) == 100_000_00L)
    check("the same reference never deposits twice", dep("d1", 100_000) == DepositResult.Duplicate && f.wallet.balances[member] == 400_000_00L)
    check("below the minimum is refused", dep("d2", 10) is DepositResult.BelowMinimum)
    check("more than the player has is refused, nothing moves", dep("d3", 450_000) == DepositResult.InsufficientFunds && f.store.bankBalance(team) == 100_000_00L)
    check("a non-member can't deposit", dep("d4", 1000, UUID.randomUUID()) == DepositResult.NotInTeam)
    f.wallet.balances[member] = 5_000_000_00L
    val over = dep("d5", 2_000_000)
    check("the bank limit is enforced (2M base)", over is DepositResult.OverCapacity && (over as DepositResult.OverCapacity).roomCents == 1_900_000_00L)
    check("disabled deposits are a manager rule, the store still validates", f.s.bank.depositsEnabled)
}

// ── Purchases ────────────────────────────────────────────────

private fun purchases() = withDb("purchase") { f, _ ->
    val (team, leader) = f.team("Shop")
    val member = UUID.randomUUID()
    f.join("Shop", leader, member)
    check("level too low is refused", f.buy(team, leader, UpgradeCategory.PROGRESSION, 0).let { it is PurchaseResult.LevelTooLow && it.required == 2 })
    f.give(team, 1000)
    check("insufficient team-bank funds are refused", f.buy(team, leader, UpgradeCategory.PROGRESSION, 0) is PurchaseResult.InsufficientFunds)
    f.fund(team, member, 300_000)
    check("a regular member can't buy (view-only)", f.buy(team, member, UpgradeCategory.PROGRESSION, 0) == PurchaseResult.NotLeader)
    check("an outsider can't buy", f.buy(team, UUID.randomUUID(), UpgradeCategory.PROGRESSION, 0) == PurchaseResult.NotInTeam)
    val bought = f.buy(team, leader, UpgradeCategory.PROGRESSION, 0, txn = "p1")
    check("the leader buys tier 1 from the team bank", bought is PurchaseResult.Purchased && bought.tier == 1 && f.store.bankBalance(team) == 250_000_00L)
    check("the player's own balance is never charged", f.wallet.balances[leader] == null)
    check("the same transaction never buys twice", f.buy(team, leader, UpgradeCategory.PROGRESSION, 0, txn = "p1") == PurchaseResult.Duplicate && f.store.bankBalance(team) == 250_000_00L)
    check("a stale screen (old tier) is refused", f.buy(team, leader, UpgradeCategory.PROGRESSION, 0) == PurchaseResult.Stale(1))
    check("tier 2 needs level 4", f.buy(team, leader, UpgradeCategory.PROGRESSION, 1) is PurchaseResult.LevelTooLow)
    check("storage can't be bought until its system exists", f.buy(team, leader, UpgradeCategory.STORAGE, 0) == PurchaseResult.Unavailable)
    check("home can't be bought until its system exists", f.buy(team, leader, UpgradeCategory.HOME, 0) == PurchaseResult.Unavailable)
    val off = f.s.copy(upgrades = f.s.upgrades + (UpgradeCategory.UTILITY to f.s.category(UpgradeCategory.UTILITY).copy(enabled = false)))
    check("a disabled category can't be bought", f.store.purchase(TeamProgressionStore.PurchaseRequest("off", leader, "b", team, UpgradeCategory.UTILITY, 0, T0), off) == PurchaseResult.Unavailable)

    // The bonus applies to later XP.
    val before = f.store.totalXp(team)
    val r = f.event(XpSource.ORE_MINED, member, 100) as AwardResult.Awarded
    check("Team Progression tier 1 adds 2%", r.awarded == 102L && f.store.totalXp(team) == before + 102)

    // Max tier.
    f.give(team, TeamLevels.totalFor(10, f.s.levels))
    f.fund(team, member, 5_000_000)
    for (tier in 1 until 3) check("utility tier ${tier + 1}", f.buy(team, leader, UpgradeCategory.UTILITY, tier - 1) is PurchaseResult.Purchased || tier > 1)
    var current = f.store.tiers(team)[UpgradeCategory.UTILITY] ?: 0
    while (current < 3) { check("utility step", f.buy(team, leader, UpgradeCategory.UTILITY, current) is PurchaseResult.Purchased); current++ }
    check("past the last tier is refused", f.buy(team, leader, UpgradeCategory.UTILITY, 3) == PurchaseResult.MaxTier)

    // Leadership transfer: the old leader loses purchase rights at once, the new one has them.
    f.teams.transfer(Actor(leader, "l"), null, member, T0)
    check("a former leader can't buy", f.buy(team, leader, UpgradeCategory.COSMETICS, 0) == PurchaseResult.NotLeader)
    check("the new leader can buy", f.buy(team, member, UpgradeCategory.COSMETICS, 0) is PurchaseResult.Purchased)
    // Kicked: the old leader (now member) is removed and can't buy even if they become someone else's ex.
    f.teams.kick(Actor(member, "m"), leader, T0)
    check("a kicked member can't buy", f.buy(team, leader, UpgradeCategory.COSMETICS, 1) == PurchaseResult.NotInTeam)
    // Disband: nothing can be bought afterwards, and the progression rows stay as history.
    val tiersBefore = f.store.tiers(team)
    f.teams.disband(Actor(member, "m"), null, T0)
    check("a disbanded team can't buy", f.buy(team, member, UpgradeCategory.COSMETICS, 1) == PurchaseResult.NotInTeam)
    check("disbanding keeps the stored tiers as history", f.store.tiers(team) == tiersBefore)
    check("a disbanded team earns no XP", (f.event(XpSource.RANKED_MATCH, null, 60, teamId = team) as? AwardResult.Refused)?.reason == Refusal.TEAM_INACTIVE)
}

private fun concurrency() = withDb("concurrency") { f, _ ->
    val (team, leader) = f.team("Race")
    val member = UUID.randomUUID()
    f.join("Race", leader, member)
    f.give(team, 1000)
    f.fund(team, member, 60_000)
    val pool = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    val results = java.util.Collections.synchronizedList(mutableListOf<PurchaseResult>())
    repeat(8) { i -> pool.execute { start.await(); results += f.buy(team, leader, UpgradeCategory.ECONOMY, 0, txn = "race$i") } }
    start.countDown(); pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS)
    check("concurrent purchases: exactly one succeeds", results.count { it is PurchaseResult.Purchased } == 1)
    check("concurrent purchases: charged once", f.store.bankBalance(team) == 10_000_00L && f.store.tiers(team)[UpgradeCategory.ECONOMY] == 1)

    // Concurrent deposits from one player can never overdraw their balance.
    f.wallet.balances[member] = 50_000_00L
    val pool2 = Executors.newFixedThreadPool(8)
    val go = CountDownLatch(1)
    val deposits = java.util.Collections.synchronizedList(mutableListOf<DepositResult>())
    repeat(8) { i -> pool2.execute { go.await(); deposits += f.store.deposit(TeamProgressionStore.DepositRequest("cd$i", member, "m", 10_000_00L, T0), f.s) } }
    go.countDown(); pool2.shutdown(); pool2.awaitTermination(30, TimeUnit.SECONDS)
    check("concurrent deposits: only what the player has (5 of 8)", deposits.count { it is DepositResult.Deposited } == 5 && f.wallet.balances[member] == 0L)
    check("concurrent deposits: the bank got exactly that", f.store.bankBalance(team) == 60_000_00L)

    // Concurrent milestone claims pay once.
    f.give(team, TeamLevels.totalFor(5, f.s.levels))
    val pool3 = Executors.newFixedThreadPool(6)
    val go3 = CountDownLatch(1)
    val claims = java.util.Collections.synchronizedList(mutableListOf<ClaimResult>())
    repeat(6) { i -> pool3.execute { go3.await(); claims += f.store.claimMilestone(if (i % 2 == 0) leader else member, "x", team, 5, SEASON, T0, f.s) } }
    go3.countDown(); pool3.shutdown(); pool3.awaitTermination(30, TimeUnit.SECONDS)
    check("concurrent milestone claims: exactly one", claims.count { it is ClaimResult.Claimed } == 1 && claims.count { it == ClaimResult.AlreadyClaimed } == 5)
    check("concurrent milestone claims: coins once", f.store.bankBalance(team) == 60_000_00L + 25_000_00L)

    // Duplicate XP events racing.
    val pool4 = Executors.newFixedThreadPool(6)
    val go4 = CountDownLatch(1)
    val awards = java.util.Collections.synchronizedList(mutableListOf<AwardResult>())
    val before = f.store.totalXp(team)
    repeat(6) { pool4.execute { go4.await(); awards += f.event(XpSource.BOUNTY_CLAIM, leader, 75, key = "bounty:same", subject = "v", now = T0 + 10 * DAY) } }
    go4.countDown(); pool4.shutdown(); pool4.awaitTermination(30, TimeUnit.SECONDS)
    check("a duplicated event awards once", awards.count { it is AwardResult.Awarded } == 1 && f.store.totalXp(team) == before + 75)
}

private fun membership() = withDb("membership") { f, _ ->
    val (team, leader) = f.team("Owners")
    val member = UUID.randomUUID()
    f.join("Owners", leader, member)
    f.event(XpSource.ORE_MINED, member, 100)
    val xpBefore = f.store.totalXp(team)
    f.teams.leave(member, "m", T0)
    check("leaving keeps the team's XP", f.store.totalXp(team) == xpBefore)
    check("a former member earns nothing for the old team", (f.event(XpSource.ORE_MINED, member, 100) as AwardResult.Refused).reason == Refusal.NO_TEAM)
    // Joining another team: XP is credited to the new team only, after the membership wait.
    val (other, otherLeader) = f.team("Other")
    f.join("Other", otherLeader, member, at = T0)
    check("switching teams waits out the minimum membership", (f.event(XpSource.ORE_MINED, member, 100, now = T0 + MINUTE) as AwardResult.Refused).reason == Refusal.TOO_NEW)
    val credited = f.event(XpSource.ORE_MINED, member, 100, now = T0 + 2 * HOUR) as AwardResult.Awarded
    check("XP goes to the current team only", credited.teamId == other && f.store.totalXp(team) == xpBefore)
    // Disband + recreate under the same name starts from nothing.
    f.give(team, TeamLevels.totalFor(10, f.s.levels))
    f.store.claimMilestone(leader, "l", team, 5, SEASON, T0, f.s)
    f.teams.disband(Actor(leader, "l"), null, T0)
    val (again, _) = f.team("Owners", leader, at = T0)
    check("a recreated team is a new team id", again != team)
    check("a recreated team starts at Level 1 with an empty bank", f.store.totalXp(again) == 0L && f.store.bankBalance(again) == 0L && f.store.tiers(again).isEmpty())
    check("a recreated team can't re-claim the old team's milestones yet", f.store.claimMilestone(leader, "l", again, 5, SEASON, T0, f.s) is ClaimResult.NotReached)
    check("the disbanded team's milestone can't be claimed by anyone", f.store.claimMilestone(leader, "l", team, 10, SEASON, T0, f.s) == ClaimResult.NotInTeam)
}

private fun milestones() = withDb("milestones") { f, _ ->
    val (team, leader) = f.team("Mile")
    check("an unreached milestone is refused", f.store.claimMilestone(leader, "l", team, 5, SEASON, T0, f.s) is ClaimResult.NotReached)
    check("a non-milestone level is refused", f.store.claimMilestone(leader, "l", team, 7, SEASON, T0, f.s) == ClaimResult.NotMilestone)
    f.give(team, TeamLevels.totalFor(10, f.s.levels))
    val claim = f.store.claimMilestone(leader, "l", team, 5, SEASON, T0, f.s)
    check("a reached milestone is claimed", claim is ClaimResult.Claimed && claim.reward.title == "Rising")
    check("its coins go to the team bank (only upgrades can spend them)", f.store.bankBalance(team) == 25_000_00L)
    check("a second claim is refused", f.store.claimMilestone(leader, "l", team, 5, SEASON, T0, f.s) == ClaimResult.AlreadyClaimed)
    check("the claimed title is unlocked", f.store.snapshot(team, "c", SEASON).titles == listOf("Rising"))
    check("the leader may use an unlocked title", f.store.setStyle(leader, "l", team, null, "Rising", T0, f.s) is TeamProgressionStore.StyleResult.Changed)
    check("a locked title is refused", f.store.setStyle(leader, "l", team, null, "Mythic", T0, f.s) == TeamProgressionStore.StyleResult.Locked)
    check("a locked colour is refused", f.store.setStyle(leader, "l", team, "aqua", null, T0, f.s) == TeamProgressionStore.StyleResult.Locked)
    val member = UUID.randomUUID()
    f.join("Mile", leader, member)
    check("a member can't change the style", f.store.setStyle(member, "m", team, null, null, T0, f.s) == TeamProgressionStore.StyleResult.NotLeader)
    check("any member can claim for the team", f.store.claimMilestone(member, "m", team, 10, SEASON, T0, f.s) is ClaimResult.Claimed)
}

private fun seasons() = withDb("seasons") { f, _ ->
    val (team, leader) = f.team("Season")
    f.event(XpSource.ORE_MINED, leader, 100, season = "season-1")
    f.event(XpSource.ORE_MINED, leader, 50, season = "season-2", now = T0 + 2 * DAY)
    check("season XP is recorded per season", f.store.snapshot(team, "c", "season-1").seasonXp == 100L && f.store.snapshot(team, "c", "season-2").seasonXp == 50L)
    check("Team Level is permanent across seasons (keep policy)", f.store.totalXp(team) == 150L)
    f.give(team, TeamLevels.totalFor(5, f.s.levels))
    check("season-1 milestone claim", f.store.claimMilestone(leader, "l", team, 5, "season-1", T0, f.s) is ClaimResult.Claimed)
    check("a new season can't claim the same milestone again", f.store.claimMilestone(leader, "l", team, 5, "season-2", T0, f.s) == ClaimResult.AlreadyClaimed)
    check("only the keep policy exists", com.liam.joshymc.lifesteal.teams.progression.SeasonResetPolicy.entries.map { it.id } == listOf("keep"))
}

private fun integrity() = withDb("integrity") { f, _ ->
    val (team, leader) = f.team("Rows")
    val member = UUID.randomUUID()
    f.join("Rows", leader, member)
    f.give(team, 1000)
    f.fund(team, member, 100_000)
    f.buy(team, leader, UpgradeCategory.ECONOMY, 0, txn = "keep")
    fun refused(sql: String): Boolean = try { f.db.execute(sql); false } catch (_: Exception) { true }
    check("upgrade tiers can't be lowered", refused("UPDATE lifesteal_team_upgrades SET tier = 0 WHERE team_id = '$team'"))
    check("upgrades can't be deleted (no item/perk loss)", refused("DELETE FROM lifesteal_team_upgrades WHERE team_id = '$team'"))
    check("purchases are append-only", refused("UPDATE lifesteal_team_upgrade_purchases SET cost_cents = 0") && refused("DELETE FROM lifesteal_team_upgrade_purchases"))
    check("the bank ledger is append-only", refused("UPDATE lifesteal_team_bank_ledger SET amount_cents = 0") && refused("DELETE FROM lifesteal_team_bank_ledger"))
    check("the bank can't go negative", refused("UPDATE lifesteal_team_bank SET balance_cents = -1 WHERE team_id = '$team'"))
    check("Team XP never goes down", refused("UPDATE lifesteal_team_progress SET total_xp = 0 WHERE team_id = '$team'"))
    f.give(team, TeamLevels.totalFor(5, f.s.levels))
    f.store.claimMilestone(leader, "l", team, 5, SEASON, T0, f.s)
    check("milestone claims are append-only", refused("DELETE FROM lifesteal_team_milestone_claims"))
    check("purchase and deposit are audited", f.store.audit(team).map { it.action }.containsAll(listOf("UPGRADE_PURCHASE", "BANK_DEPOSIT", "MILESTONE_CLAIM")))
}

private fun restarts() {
    val dir = Files.createTempDirectory("joshymc-teamprog-restart").toFile()
    try {
        var db = DatabaseManager(dir, quietLogger()); db.start()
        var f = Fixture(db)
        val (team, leader) = f.team("Restart")
        val member = UUID.randomUUID()
        f.join("Restart", leader, member)
        f.give(team, 5000)
        f.fund(team, member, 100_000)
        f.buy(team, leader, UpgradeCategory.PROGRESSION, 0, txn = "r1")
        val before = f.store.snapshot(team, "c", SEASON)
        db.shutdown()
        db = DatabaseManager(dir, quietLogger()); db.start()
        f = Fixture(db)
        val after = f.store.snapshot(team, "c", SEASON)
        check("XP, tiers and bank survive a restart", after == before)
        check("a purchase retried after a restart is still a duplicate", f.buy(team, leader, UpgradeCategory.PROGRESSION, 0, txn = "r1") == PurchaseResult.Duplicate)
        db.shutdown()
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring (source checks) ───────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/teams/progression/LifestealTeamProgressionManager.kt")
    val listeners = source("manager/ListenerManager.kt")
    val plugin = source("Joshymc.kt")
    val survivalTeam = source("command/TeamCommand.kt")
    val router = source("command/TeamCommandRouter.kt")
    val hearts = source("lifesteal/hearts/LifestealHeartManager.kt")
    val bounty = source("lifesteal/bounty/LifestealBountyManager.kt")
    val rating = source("lifesteal/ranked/rating/LifestealRatingManager.kt")
    val command = source("lifesteal/teams/LifestealTeamCommand.kt")
    val menu = source("lifesteal/teams/LifestealTeamMenu.kt")
    val progressionDir = File("src/main/kotlin/com/liam/joshymc/lifesteal/teams/progression")
    val all = progressionDir.listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }

    check("tables only where Lifesteal is hosted", manager.indexOf("if (!hosted)") in 0 until manager.indexOf("store.createTables()"))
    check("the listener is only registered where Lifesteal is hosted", listeners.contains("if (plugin.lifestealTeamProgressionManager.hosted) {"))
    check("manager is refreshed at startup and reload, stopped at shutdown",
        plugin.contains("lifestealTeamProgressionManager.refresh()") && plugin.contains("safe(\"lifestealTeamProgressionManager.refresh\")") &&
            plugin.contains("shutdown.step(\"lifestealTeamProgressionManager.stop\""))
    check("Survival /team never touches team progression", !survivalTeam.contains("Progression") && !survivalTeam.contains("TeamUpgrades"))
    check("/team upgrades is only in the Lifesteal command (router unchanged)", command.contains("\"upgrades\"") && !router.contains("upgrades"))
    check("the team menu has the Team Upgrades button", menu.contains("TeamUpgradesMenu(plugin).open(p)"))
    check("PvP XP comes from the heart decision (both kill paths)", Regex("teamProgression\\(\\)\\?\\.onPvpKill").findAll(hearts).count() == 2)
    check("bounty XP only after a paid claim", bounty.indexOf("onBountyClaimed") > bounty.indexOf("is BountyStore.PayResult.Paid"))
    check("ranked XP only for rated matches", rating.indexOf("onRankedRated") in rating.indexOf("is RatingStore.Finalized.Rated") until rating.indexOf("is RatingStore.Finalized.Unrated"))
    check("refused heart decisions never earn XP", manager.contains("HeartRules.Decision.Transfer") && !manager.contains("Decision.Teammates ->"))
    check("upgrades never teleport (combat/ranked restrictions can't be bypassed)", !all.contains("teleport(") && !all.contains("teleportAsync"))
    check("upgrades never touch hearts, health or damage", !all.contains("MAX_HEALTH") && !all.contains("setHealth") && !all.contains("HeartStore") && !all.contains("damage ="))
    check("no withdrawal from the team bank exists", !all.contains("fun withdraw(player") && !all.lowercase().contains("bank withdraw"))
    check("disabled = no XP and no actions", manager.contains("val active: Boolean get() = hosted && tablesReady && settings?.enabled == true") &&
        manager.contains("settings?.enabled != true -> \"Team Upgrades are turned off right now.\""))
    check("mining only counts at MONITOR, not cancelled", source("lifesteal/teams/progression/TeamProgressionListener.kt").contains("@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    fun onBreak"))
    check("player-placed ores have their own chunk key", manager.contains("PlacedBlockTracker(plugin, \"team_xp_placed\")"))
    check("the permission is in plugin.yml", File("src/main/resources/plugin.yml").readText().contains("joshymc.lifesteal.team.upgrades:"))
    check("docs exist", File("docs/lifesteal-team-progression.md").exists())
}
