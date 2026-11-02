// Checks Lifesteal team PvP (issue #1348): the friendly-fire settings, the Bukkit-free team combat
// rules and recent-teammate memory, the team check inside heart transfers against a real SQLite
// database (fresh temp directories, deleted afterwards), and the Lifesteal-only wiring. The Gradle
// build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-team-combat-check.init.gradle.kts lifestealTeamCombatCheck
//
// Exits non-zero on the first failed check. Real hits, projectiles, potions and explosions on a
// Paper server (and Bedrock clients) are NOT covered here (see docs/lifesteal-teams.md, "Manual tests").

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartRules.Decision
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.hearts.HeartStore.PvpResult
import com.liam.joshymc.lifesteal.teams.TeamCombatMemory
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.DamageDecision
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.HeartCheck
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.Policy
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.Relation
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
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private val LIMITS = HeartRules.Limits(starting = 10, minimum = 1, maximum = 20)
private const val COOLDOWN = 30 * 60_000L
private const val T0 = 1_800_000_000_000L
private const val GRACE = 15_000L

/** A Java (v4) and a Floodgate-style Bedrock UUID: both are plain UUIDs to teams and hearts. */
private val JAVA = UUID.randomUUID()
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6")
private val ENEMY = UUID.randomUUID()
private val LONER = UUID.randomUUID()

private val OFF = Policy(friendlyFire = false, teammateHeartTransfers = false)
private val ON = Policy(friendlyFire = true, teammateHeartTransfers = false)
private val ON_WITH_HEARTS = Policy(friendlyFire = true, teammateHeartTransfers = true)

fun main() {
    settings()
    relations()
    damage()
    hearts()
    memory()
    store()
    wiring()
    println("OK: $checks Lifesteal team combat checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

private fun bundledLifestealYml(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun parseWith(map: Map<String, Any?>) =
    LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) } }

@Suppress("UNCHECKED_CAST")
private fun withFriendlyFire(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
    val map = bundledLifestealYml().toMutableMap()
    val teams = (map["teams"] as Map<String, Any?>).toMutableMap()
    val ff = (teams["friendly-fire"] as Map<String, Any?>).toMutableMap()
    edit(ff)
    teams["friendly-fire"] = ff
    map["teams"] = teams
    return map
}

private fun settings() {
    val parsed = parseWith(bundledLifestealYml())
    val teams = parsed.settings!!.teams
    check("bundled file has no problems", parsed.problems.isEmpty())
    check("friendly fire off by default", !teams.friendlyFire)
    check("teammate heart transfers off by default", !teams.teammateHeartTransfers)
    check("defaults match the data class", teams == LifestealSettings.Teams())
    check("heart limits unchanged", parsed.settings!!.hearts == LifestealSettings.Hearts(10, 20, 1))
    check("30-minute cooldown unchanged", parsed.settings!!.pvp.sameVictimCooldownMinutes == 30)

    check("friendly fire can be turned on", parseWith(withFriendlyFire { it["enabled"] = true }).settings?.teams?.friendlyFire == true)
    val both = parseWith(withFriendlyFire { it["enabled"] = true; it["heart-transfers"] = true })
    check("teammate heart transfers can be turned on with friendly fire", both.settings?.teams?.teammateHeartTransfers == true && both.problems.isEmpty())
    check("friendly fire must be a boolean", parseWith(withFriendlyFire { it["enabled"] = "off" }).settings == null)
    check("heart-transfers must be a boolean", parseWith(withFriendlyFire { it["heart-transfers"] = 1 }).settings == null)
    val pointless = parseWith(withFriendlyFire { it["heart-transfers"] = true })
    check("heart-transfers without friendly fire loads with a warning", pointless.settings != null &&
        pointless.problems.size == 1 && !pointless.problems[0].severe && "no effect" in pointless.problems[0].message)
    @Suppress("UNCHECKED_CAST")
    val withoutSection = bundledLifestealYml().toMutableMap().also { map ->
        map["teams"] = (map["teams"] as Map<String, Any?>).toMutableMap().apply { remove("friendly-fire") }
    }
    val old = parseWith(withoutSection)
    check("a file from before issue #1348 loads with the safe defaults", old.settings?.teams?.friendlyFire == false && old.settings?.teams?.teammateHeartTransfers == false)
    check("that is one warning", old.problems.size == 1 && !old.problems[0].severe && "friendly-fire.enabled" in old.problems[0].message)
}

// ── Relations ────────────────────────────────────────────────

private fun relation(
    a: UUID, b: UUID,
    current: Map<UUID, String>,
    recent: Map<UUID, List<String>> = emptyMap(),
    available: Boolean = true,
) = TeamCombatRules.relation(a, b, available, { current[it] }, { recent[it].orEmpty() })

private fun relations() {
    val teams = mapOf(JAVA to "wolves", BEDROCK to "wolves", ENEMY to "bears")
    check("same team (Java + Bedrock)", relation(JAVA, BEDROCK, teams) == Relation.TEAMMATES)
    check("same team is symmetric", relation(BEDROCK, JAVA, teams) == Relation.TEAMMATES)
    check("different teams", relation(JAVA, ENEMY, teams) == Relation.NOT_TEAMMATES)
    check("one without a team", relation(JAVA, LONER, teams) == Relation.NOT_TEAMMATES)
    check("both without a team", relation(LONER, UUID.randomUUID(), teams) == Relation.NOT_TEAMMATES)
    check("self is never a teammate", relation(JAVA, JAVA, teams) == Relation.NOT_TEAMMATES)
    check("unavailable membership is unknown, not 'different teams'", relation(JAVA, ENEMY, teams, available = false) == Relation.UNKNOWN)
    check("unavailable membership is unknown even for teammates", relation(JAVA, BEDROCK, teams, available = false) == Relation.UNKNOWN)

    // Recently joined: membership counts as soon as the cache has it.
    val joined = teams + (LONER to "wolves")
    check("recently joined player is a teammate", relation(JAVA, LONER, joined) == Relation.TEAMMATES)
    // Recently removed: leaving, kicks, disband.
    val left = teams - BEDROCK
    check("player who just left is a recent teammate", relation(JAVA, BEDROCK, left, mapOf(BEDROCK to listOf("wolves"))) == Relation.RECENT_TEAMMATES)
    check("...from either side", relation(BEDROCK, JAVA, left, mapOf(BEDROCK to listOf("wolves"))) == Relation.RECENT_TEAMMATES)
    check("player who left and joined another team is still a recent teammate",
        relation(JAVA, BEDROCK, left + (BEDROCK to "bears"), mapOf(BEDROCK to listOf("wolves"))) == Relation.RECENT_TEAMMATES)
    val disbanded = mapOf(ENEMY to "bears")
    check("disbanded team: both were members, both recent", relation(JAVA, BEDROCK, disbanded, mapOf(JAVA to listOf("wolves"), BEDROCK to listOf("wolves"))) == Relation.RECENT_TEAMMATES)
    check("someone else's old team doesn't make strangers teammates",
        relation(JAVA, ENEMY, teams, mapOf(LONER to listOf("bears"))) == Relation.NOT_TEAMMATES)
    check("different former teams aren't a relation", relation(JAVA, LONER, emptyMap(), mapOf(JAVA to listOf("wolves"), LONER to listOf("bears"))) == Relation.NOT_TEAMMATES)
    // Leadership transfers don't change membership: same team before and after.
    check("leadership transfer keeps teammates", relation(JAVA, BEDROCK, teams) == Relation.TEAMMATES)
}

// ── Damage ───────────────────────────────────────────────────

private fun damage() {
    fun d(attacker: UUID?, relation: Relation, policy: Policy?, victim: UUID = BEDROCK) = TeamCombatRules.damage(attacker, victim, relation, policy)

    // Melee and projectiles look the same here: the listener resolves the credited player first.
    check("same-team melee blocked with friendly fire off", d(JAVA, Relation.TEAMMATES, OFF) == DamageDecision.BLOCK_FRIENDLY_FIRE)
    check("teammate projectile (shooter credited) blocked", d(JAVA, Relation.TEAMMATES, OFF) == DamageDecision.BLOCK_FRIENDLY_FIRE)
    check("recent teammate's in-flight arrow blocked", d(JAVA, Relation.RECENT_TEAMMATES, OFF) == DamageDecision.BLOCK_FRIENDLY_FIRE)
    check("enemy melee/projectile allowed", d(ENEMY, Relation.NOT_TEAMMATES, OFF) == DamageDecision.ALLOW)
    check("player without a team allowed", d(LONER, Relation.NOT_TEAMMATES, OFF) == DamageDecision.ALLOW)
    check("friendly fire on: teammates can hurt each other", d(JAVA, Relation.TEAMMATES, ON) == DamageDecision.ALLOW)
    check("environment (no attacker) is never blocked", d(null, Relation.TEAMMATES, OFF) == DamageDecision.ALLOW)
    check("own TNT/arrow is never blocked", d(BEDROCK, Relation.NOT_TEAMMATES, OFF) == DamageDecision.ALLOW)
    check("unknown membership: no teammate is invented", d(JAVA, Relation.UNKNOWN, OFF) == DamageDecision.ALLOW)
    check("unreadable settings cancel nothing", d(JAVA, Relation.TEAMMATES, null) == DamageDecision.ALLOW)

    // Potions: harmful ones held back from teammates, helpful ones still land.
    check("harmful splash on teammate held back", TeamCombatRules.blocksPotion(true, Relation.TEAMMATES, OFF))
    check("healing splash on teammate still lands", !TeamCombatRules.blocksPotion(false, Relation.TEAMMATES, OFF))
    check("harmful splash on enemy lands", !TeamCombatRules.blocksPotion(true, Relation.NOT_TEAMMATES, OFF))
    check("harmful splash on teammate lands with friendly fire on", !TeamCombatRules.blocksPotion(true, Relation.TEAMMATES, ON))
    check("harmful cloud on recent teammate held back", TeamCombatRules.blocksPotion(true, Relation.RECENT_TEAMMATES, OFF))

    // Team changes during combat: every membership change has a refusal message.
    for (change in TeamCombatRules.TeamChange.entries) {
        check("combat refusal for $change", "combat" in TeamCombatRules.combatMessage(change))
    }
}

// ── Heart eligibility ────────────────────────────────────────

private fun hearts() {
    check("enemies: eligible", TeamCombatRules.heartCheck(Relation.NOT_TEAMMATES, OFF) == HeartCheck.ELIGIBLE)
    check("teammates, friendly fire off: refused", TeamCombatRules.heartCheck(Relation.TEAMMATES, OFF) == HeartCheck.TEAMMATES)
    check("teammates, friendly fire on, no heart transfers: refused", TeamCombatRules.heartCheck(Relation.TEAMMATES, ON) == HeartCheck.TEAMMATES)
    check("teammates, friendly fire on + heart transfers: eligible", TeamCombatRules.heartCheck(Relation.TEAMMATES, ON_WITH_HEARTS) == HeartCheck.ELIGIBLE)
    check("heart-transfers alone (friendly fire off) changes nothing", TeamCombatRules.heartCheck(Relation.TEAMMATES, Policy(false, true)) == HeartCheck.TEAMMATES)
    check("kill right after /team leave: refused", TeamCombatRules.heartCheck(Relation.RECENT_TEAMMATES, OFF) == HeartCheck.TEAMMATES)
    check("unknown membership: refused (database failure is no bypass)", TeamCombatRules.heartCheck(Relation.UNKNOWN, OFF) == HeartCheck.UNKNOWN)
    check("unknown membership refused even with friendly fire on", TeamCombatRules.heartCheck(Relation.UNKNOWN, ON_WITH_HEARTS) == HeartCheck.UNKNOWN)
    check("unreadable settings: refused", TeamCombatRules.heartCheck(Relation.NOT_TEAMMATES, null) == HeartCheck.UNKNOWN)

    // The team check comes first in the heart rules and never moves a heart.
    check("teammate decision", HeartRules.decide(10, 10, 0, LIMITS, team = HeartCheck.TEAMMATES) == Decision.Teammates)
    check("unknown decision", HeartRules.decide(10, 10, 0, LIMITS, team = HeartCheck.UNKNOWN) == Decision.TeamUnknown)
    check("eligible = the old rules", HeartRules.decide(10, 10, 0, LIMITS, team = HeartCheck.ELIGIBLE) == Decision.Transfer(9, 11))
    check("victim with exactly 1 heart keeps it", HeartRules.decide(1, 10, 0, LIMITS) == Decision.VictimAtMinimum)
    check("killer with 20 hearts gains nothing", HeartRules.decide(10, 20, 0, LIMITS) == Decision.KillerAtMaximum)
    check("cooldown still enforced", HeartRules.decide(10, 10, 1_000, LIMITS) is Decision.Cooldown)
    with(HeartRules) {
        check("team refusals move no heart", !Decision.Teammates.changesHearts && !Decision.TeamUnknown.changesHearts)
    }
    for (v in -2..25) for (k in -2..25) for (team in HeartCheck.entries) {
        val d = HeartRules.decide(v, k, 0, LIMITS, team = team)
        if (d is Decision.Transfer) check("transfer $v/$k/$team stays in limits", team == HeartCheck.ELIGIBLE && d.victimAfter >= 1 && d.killerAfter <= 20)
    }
}

// ── Recent-teammate memory ───────────────────────────────────

private fun memory() {
    val memory = TeamCombatMemory(perPlayer = 3)
    check("empty", memory.recent(JAVA, T0).isEmpty())
    memory.record(JAVA, "wolves", T0, GRACE)
    check("remembered inside the window", memory.recent(JAVA, T0 + GRACE - 1) == listOf("wolves"))
    check("forgotten at the end of the window", memory.recent(JAVA, T0 + GRACE).isEmpty())
    memory.record(JAVA, "wolves", T0 + 5_000, GRACE)
    check("leaving the same team again restarts it, no duplicate", memory.recent(JAVA, T0 + 6_000) == listOf("wolves"))
    for (i in 1..10) memory.record(BEDROCK, "team$i", T0, GRACE)
    check("bounded per player", memory.recent(BEDROCK, T0).size == 3 && memory.recent(BEDROCK, T0).last() == "team10")
    check("zero grace remembers nothing", TeamCombatMemory().also { it.record(JAVA, "x", T0, 0) }.recent(JAVA, T0).isEmpty())
    memory.sweep(T0 + GRACE + 10_000)
    check("sweep drops every expired entry (no stale players kept)", memory.size == 0)
    // Rapid disconnects: the memory is not tied to the session, so quitting and rejoining keeps it.
    memory.record(ENEMY, "bears", T0, GRACE)
    check("survives a disconnect (nothing removes it on quit)", memory.recent(ENEMY, T0 + 1_000) == listOf("bears"))
    memory.clear()
    check("clear empties it (reload)", memory.size == 0)

    // Disband during combat: every member gets an entry, so the old teammates stay protected.
    val m = TeamCombatMemory()
    for (member in listOf(JAVA, BEDROCK, LONER)) m.record(member, "wolves", T0, GRACE)
    val relationAfterDisband = TeamCombatRules.relation(JAVA, BEDROCK, true, { null }, { m.recent(it, T0 + 2_000) })
    check("disbanded mid-fight: still protected for the combat-tag time", relationAfterDisband == Relation.RECENT_TEAMMATES)
    val later = TeamCombatRules.relation(JAVA, BEDROCK, true, { null }, { m.recent(it, T0 + GRACE + 1) })
    check("after the window they are ordinary players", later == Relation.NOT_TEAMMATES)
}

// ── Heart store with the team check ──────────────────────────

private fun <T> withDb(name: String, block: (DatabaseManager, File) -> T): T {
    val dir = Files.createTempDirectory("joshymc-teamcombat-$name").toFile()
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        return block(db, dir)
    } finally {
        db.shutdown()
        dir.deleteRecursively()
    }
}

private fun pvp(killer: UUID, victim: UUID, now: Long, team: HeartCheck = HeartCheck.ELIGIBLE, key: String = "k:${UUID.randomUUID()}") =
    HeartStore.PvpRequest(key, killer, victim, now, COOLDOWN, LIMITS, team = team)

private fun store() {
    withDb("store") { db, dir ->
        val store = HeartStore(db)
        store.createTables()
        for (p in listOf(JAVA, BEDROCK, ENEMY)) store.ensure(p, 10, T0)

        val mate = store.transferPvp(pvp(JAVA, BEDROCK, T0, HeartCheck.TEAMMATES, key = "teammate-death"))
        check("teammate kill: evaluated, no heart moved", mate is PvpResult.Done && mate.decision == Decision.Teammates && mate.victimAfter == 10 && mate.killerAfter == 10)
        check("teammate kill: hearts unchanged in the database", store.load(JAVA)!!.hearts == 10 && store.load(BEDROCK)!!.hearts == 10)
        check("teammate kill: audited as a rejection", store.audit(BEDROCK).any { it.operation == "PVP_REJECTED" && it.reason == "TEAMMATES" })
        check("teammate kill: no cooldown started", store.lastTransferAt(JAVA, BEDROCK) == null)
        check("same death again is a duplicate (no double processing)", store.transferPvp(pvp(JAVA, BEDROCK, T0, HeartCheck.ELIGIBLE, key = "teammate-death")) == PvpResult.Duplicate)

        val unknown = store.transferPvp(pvp(JAVA, ENEMY, T0, HeartCheck.UNKNOWN))
        check("unknown membership: no heart moved", unknown is PvpResult.Done && unknown.decision == Decision.TeamUnknown && store.load(ENEMY)!!.hearts == 10)
        check("unknown membership: audited", store.audit(ENEMY).any { it.reason == "TEAM_UNKNOWN" })

        val steal = store.transferPvp(pvp(ENEMY, JAVA, T0 + 1_000))
        check("enemy kill still steals a heart", steal is PvpResult.Done && steal.decision == Decision.Transfer(9, 11))
        // Team switching can't clear the anti-farming cooldown: it lives on the (killer, victim) pair.
        val again = store.transferPvp(pvp(ENEMY, JAVA, T0 + 60_000))
        check("cooldown survives team changes", again is PvpResult.Done && again.decision is Decision.Cooldown)
        val teammateNow = store.transferPvp(pvp(ENEMY, JAVA, T0 + 120_000, HeartCheck.TEAMMATES))
        check("joining the victim's team blocks it, without touching the cooldown", teammateNow is PvpResult.Done &&
            teammateNow.decision == Decision.Teammates && store.lastTransferAt(ENEMY, JAVA) == T0 + 1_000)
        val afterCooldown = store.transferPvp(pvp(ENEMY, JAVA, T0 + 1_000 + COOLDOWN))
        check("after 30 minutes (not teammates) it steals again", afterCooldown is PvpResult.Done && afterCooldown.decision is Decision.Transfer)

        // Friendly fire on + heart transfers on: teammates follow the ordinary rules, limits included.
        val oneHeart = UUID.randomUUID()
        store.ensure(oneHeart, 1, T0)
        val last = store.transferPvp(pvp(BEDROCK, oneHeart, T0, HeartCheck.ELIGIBLE))
        check("victim at exactly 1 heart keeps it", last is PvpResult.Done && last.decision == Decision.VictimAtMinimum && store.load(oneHeart)!!.hearts == 1)
        val full = UUID.randomUUID()
        store.ensure(full, 20, T0)
        val max = store.transferPvp(pvp(full, BEDROCK, T0, HeartCheck.ELIGIBLE))
        check("killer at 20 hearts gains nothing", max is PvpResult.Done && max.decision == Decision.KillerAtMaximum && store.load(full)!!.hearts == 20 && store.load(BEDROCK)!!.hearts == 10)

        // Database failure: a closed database refuses the transfer instead of half-applying it.
        val closed = DatabaseManager(dir, quietLogger()).also { it.start(); it.shutdown() }
        check("closed database refuses the transfer", runCatching { HeartStore(closed).transferPvp(pvp(ENEMY, BEDROCK, T0 + 5 * COOLDOWN)) }.isFailure)
        check("...and nothing changed", store.load(BEDROCK)!!.hearts == 10)
    }
}

// ── Wiring and isolation ─────────────────────────────────────

private fun wiring() {
    val listener = source("lifesteal/teams/LifestealTeamCombatListener.kt")
    val listeners = source("manager/ListenerManager.kt")
    val manager = source("lifesteal/teams/LifestealTeamManager.kt")
    val hearts = source("lifesteal/hearts/LifestealHeartManager.kt")
    val heartListener = source("lifesteal/hearts/LifestealHeartListener.kt")
    val combatListener = source("listener/CombatListener.kt")
    val command = source("lifesteal/teams/LifestealTeamCommand.kt")
    val menu = source("lifesteal/teams/LifestealTeamMenu.kt")
    val rules = source("lifesteal/teams/TeamCombatRules.kt")
    val survivalTeams = source("manager/TeamManager.kt")
    val yml = resourceText("lifesteal.yml")

    // Lifesteal only.
    check("listener registered only where Lifesteal runs", Regex("""if \(plugin\.lifestealTeamManager\.hosted\) \{[^}]*LifestealTeamCombatListener\(plugin\)""").containsMatchIn(listeners))
    check("every damage handler needs a Lifesteal victim", listener.split("inLifesteal(").size - 1 >= 5)
    check("Lifesteal is decided by the world's gamemode, not a server name", "gamemodeOf(entity.world) == ServerGamemode.LIFESTEAL" in listener && "server-id" !in listener)
    check("Survival team system never reads Lifesteal team PvP", "TeamCombat" !in survivalTeams && "friendlyFire" !in survivalTeams)
    check("no packet interception", "packetevents" !in listener.lowercase() && "ProtocolLib" !in listener)

    // Damage handling.
    check("damage judged at HIGHEST, cancelled hits skipped", "@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)\n    fun onDamage(event: EntityDamageEvent)" in listener)
    check("attacker = the credited player (same as heart attribution)", "event.damageSource.causingEntity as? Player" in listener && "event.damageSource.causingEntity as? Player" in hearts)
    check("projectile shooter and TNT igniter fallbacks", "is Projectile -> source.shooter as? Player" in listener && "is TNTPrimed -> source.source as? Player" in listener)
    check("only the decision cancels (no broad cancellation)", listener.split("event.isCancelled = true").size - 1 == 2)
    check("splash potions: only teammates' intensity zeroed", "event.setIntensity(target, 0.0)" in listener)
    check("lingering clouds: only teammates removed", "event.affectedEntities.removeIf" in listener)
    check("harmful = the effect category", "PotionEffectTypeCategory.HARMFUL" in listener)
    check("no database access per hit", "store." !in listener && "databaseManager" !in listener)

    // Combat tags: CombatListener tags only hits that went through.
    check("combat tags only for uncancelled hits", "@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    fun onDamageTag" in combatListener)
    check("grace window = the existing combat-tag duration", "plugin.combatManager.tagDurationMillis" in manager && "TAG_DURATION" !in rules)
    for (change in listOf("ACCEPT", "LEAVE", "KICK", "DISBAND")) {
        check("$change refused while combat-tagged", "combatRefusal(TeamCombatRules.TeamChange.$change" in manager)
    }
    check("kick checks both leader and target", "TeamChange.KICK, listOf(player.uniqueId, target)" in manager)
    check("disband checks every member", "TeamChange.DISBAND, team.members.map { it.uuid }" in manager)
    check("combat state comes from CombatManager", "plugin.combatManager.isTagged(it)" in manager)

    // Membership bookkeeping.
    check("removed members are remembered", manager.split("combatMemory.record(").size - 1 == 2)
    check("memory swept every second", "combatMemory.sweep(now)" in manager)
    check("memory cleared on reload/stop", "combatMemory.clear()" in manager)
    check("membership by UUID from the cache (no names, prefixes or scoreboards)", "{ byPlayer[it] }" in manager && "scoreboard" !in manager.lowercase())
    check("failed load is never 'different teams'", "return hosted && (!config.enabled || active)" in manager)

    // Hearts.
    check("heart transfer asks the team rules at death time", "team = plugin.lifestealTeamManager.heartCheck(killer.uniqueId, victim.uniqueId)" in hearts)
    check("only real deaths count", "@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)\n    fun onDeath(event: PlayerDeathEvent)" in heartListener)
    check("team refusals are told to both players", "HeartMessages.KILLER_TEAMMATE" in hearts && "HeartMessages.VICTIM_TEAMMATE" in hearts)

    // Display and admin.
    check("/team info shows friendly fire", "line(sender, \"Friendly fire\", teams.friendlyFireSummary())" in command)
    check("team menu shows friendly fire", "teams.friendlyFireSummary()" in menu)
    check("policy read live from settings (follows reloads)", "fun combatPolicy(): TeamCombatRules.Policy?" in manager && "settings?.takeIf { hosted }" in manager)
    check("admin diagnostics behind the admin permission", "\"pvp\" ->" in command && "if (!teams.canAdmin(sender)) return Msg.noPermission(sender)" in command)
    check("no command changes the policy", "friendlyFire =" !in command && "friendlyFire =" !in menu)
    check("lifesteal.yml documents both keys", "friendly-fire:\n" in yml && "    enabled: false\n" in yml && "    heart-transfers: false\n" in yml)
}
