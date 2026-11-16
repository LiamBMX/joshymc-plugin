// Checks the shared commands and the command blacklist of the dedicated Lifesteal server
// (issue #1573). The Gradle build has no test source set, so it runs through an init script,
// from the repo root:
//
//   ./gradlew -I scripts/lifesteal-shared-command-check.init.gradle.kts lifestealSharedCommandCheck
//
// Exits non-zero on the first failed check. Never loads a Bukkit class (Paper targets Java 25,
// this runs on the Java 21 toolchain): YAML through snakeyaml, wiring by source text.

import com.liam.joshymc.gamemode.DedicatedServer
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeIsolation
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.LifestealSharedCommands
import com.liam.joshymc.gamemode.LifestealSharedCommands.CompetitiveState
import com.liam.joshymc.gamemode.LifestealSharedCommands.GodDecision
import com.liam.joshymc.gamemode.LifestealSharedCommands.Guard
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.gamemode.SurvivalSelfPerks
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun resource(name: String): Map<String, Any?> {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(name)
        ?: run { System.err.println("FAIL: $name not on the classpath"); exitProcess(1) }
    return InputStreamReader(stream, Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private val SURVIVAL = ServerGamemode.SURVIVAL
private val LIFESTEAL = ServerGamemode.LIFESTEAL
private val DEDICATED_LIFESTEAL = setOf(LIFESTEAL)
private val MIXED = setOf(SURVIVAL, LIFESTEAL)
private val SURVIVAL_ONLY = setOf(SURVIVAL)

/** The gate's decision ([com.liam.joshymc.gamemode.GamemodeLockManager.canUse]) for a JoshyMC command. */
private fun canUse(name: String, current: ServerGamemode, hosted: Set<ServerGamemode>, locked: Set<ServerGamemode>): Boolean {
    val scope = GamemodeAssignments.COMMANDS[name] ?: return true
    if (GamemodeIsolation.allowed(scope, current, locked)) return true
    return scope == GamemodeScope.SURVIVAL && LifestealSharedCommands.sharedHere(name, current, hosted)
}

fun main() {
    val pluginYml = resource("plugin.yml")
    @Suppress("UNCHECKED_CAST")
    val commands = pluginYml["commands"] as Map<String, Any?>
    val names = commands.keys.map { it.lowercase() }.toSet()
    val aliasOwner = HashMap<String, String>()
    for ((name, body) in commands) {
        ((body as? Map<*, *>)?.get("aliases") as? List<*>)?.forEach { aliasOwner[it.toString().lowercase()] = name.lowercase() }
    }
    val shared = LifestealSharedCommands.SHARED.keys
    val blacklist = LifestealSharedCommands.BLACKLIST.keys
    val dedicatedLocks = DedicatedServer.isolatedGamemodes(LIFESTEAL, emptyList())

    // ── The table itself ───────────────────────────────────
    check("a dedicated Lifesteal server isolates Survival", dedicatedLocks == setOf(SURVIVAL))
    for (name in shared) {
        check("/$name exists in plugin.yml", name in names)
        check("/$name keeps its SURVIVAL scope (Survival stays locked)", GamemodeAssignments.COMMANDS[name] == GamemodeScope.SURVIVAL)
        check("/$name is not blacklisted", name !in blacklist)
    }
    for (name in blacklist) check("blacklisted /$name exists in plugin.yml", name in names)
    check("nothing blacklisted is shared", shared.intersect(blacklist).isEmpty())
    check("the issue's blacklist is covered", setOf("fly", "god", "heal", "feed", "repair", "back").all { it in blacklist })
    check("/fix is /repair's alias", aliasOwner["fix"] == "repair")
    check("/near is not a JoshyMC command", "near" !in names && "near" !in aliasOwner && "near" in LifestealSharedCommands.BLACKLIST_NOT_JOSHYMC)
    check("/fix is documented", "fix" in LifestealSharedCommands.BLACKLIST_NOT_JOSHYMC)
    check("/sell is shared", "sell" in shared)
    check("/sell is guarded", LifestealSharedCommands.guardOf("SELL") == Guard.COMPETITIVE)
    for (name in listOf("pay", "ah", "overflow", "trade", "home", "sethome")) {
        check("/$name is guarded in combat and ranked", LifestealSharedCommands.guardOf(name) == Guard.COMPETITIVE)
    }

    // ── A. Dedicated Lifesteal: shared commands work while Survival stays locked ──
    for ((name, scope) in GamemodeAssignments.COMMANDS) {
        val onLifesteal = canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks)
        val expected = scope != GamemodeScope.SURVIVAL || name in shared
        check("/$name on dedicated Lifesteal = ${if (expected) "works" else "refused"}", onLifesteal == expected)
        // Survival is still locked there: the plain isolation rule is unchanged.
        if (scope == GamemodeScope.SURVIVAL) check("/$name: Survival is still locked", !GamemodeIsolation.allowed(scope, LIFESTEAL, dedicatedLocks))
    }
    check("/sell works on dedicated Lifesteal", canUse("sell", LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    for ((alias, owner) in aliasOwner) {
        if (owner in shared) check("alias /$alias works like /$owner", canUse(owner, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    }
    check("/bal is /balance", aliasOwner["bal"] == "balance")
    check("/auctionhouse is /ah", aliasOwner["auctionhouse"] == "ah")

    // Mixed server (Survival server with Lifesteal worlds, Survival locked by hand): its economy is
    // Survival's, so nothing is shared into its Lifesteal worlds.
    for (name in shared) {
        check("/$name stays refused in a mixed server's Lifesteal worlds", !canUse(name, LIFESTEAL, MIXED, setOf(SURVIVAL)))
        check("/$name not shared on a Survival-only server", !LifestealSharedCommands.sharedHere(name, LIFESTEAL, SURVIVAL_ONLY))
        check("/$name not shared for Survival senders", !LifestealSharedCommands.sharedHere(name, SURVIVAL, DEDICATED_LIFESTEAL))
    }

    // ── B. Blacklist ──────────────────────────────────────
    for (name in listOf("fly", "repair", "back")) {
        check("/$name is SURVIVAL", GamemodeAssignments.COMMANDS[name] == GamemodeScope.SURVIVAL)
        check("/$name refused on dedicated Lifesteal", !canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
        check("/$name can't be shared", !LifestealSharedCommands.sharedHere(name, LIFESTEAL, DEDICATED_LIFESTEAL))
    }
    check("/fix (alias of /repair) refused on dedicated Lifesteal", !canUse(aliasOwner["fix"]!!, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    check("self /heal and /feed refused in Lifesteal for everyone",
        SurvivalSelfPerks.decide(LIFESTEAL, selfUse = true) == SurvivalSelfPerks.Decision.SURVIVAL_ONLY)
    check("staff /heal <other> in Lifesteal still allowed by the rule", SurvivalSelfPerks.decide(LIFESTEAL, selfUse = false) == SurvivalSelfPerks.Decision.ALLOWED)
    check("non-staff self /god refused in Lifesteal",
        LifestealSharedCommands.godSelfUse(LIFESTEAL, selfUse = true, enabling = true, isStaff = false) == GodDecision.STAFF_ONLY)
    check("staff self /god allowed in Lifesteal",
        LifestealSharedCommands.godSelfUse(LIFESTEAL, selfUse = true, enabling = true, isStaff = true) == GodDecision.ALLOWED)
    check("turning /god off is never refused",
        LifestealSharedCommands.godSelfUse(LIFESTEAL, selfUse = true, enabling = false, isStaff = false) == GodDecision.ALLOWED)
    check("/god <other> follows joshymc.god.others",
        LifestealSharedCommands.godSelfUse(LIFESTEAL, selfUse = false, enabling = true, isStaff = false) == GodDecision.ALLOWED)
    check("/god unchanged on Survival",
        LifestealSharedCommands.godSelfUse(SURVIVAL, selfUse = true, enabling = true, isStaff = false) == GodDecision.ALLOWED)
    // Purchased ranks: Lifesteal ranks can never carry the blacklisted nodes.
    for (node in listOf("joshymc.fly", "joshymc.god", "joshymc.heal", "joshymc.feed", "joshymc.repair", "joshymc.repair.hand",
        "joshymc.repair.all", "joshymc.back", "joshymc.god.others", "joshymc.heal.others", "joshymc.fly.others", "*", "joshymc.*")) {
        check("a Lifesteal rank can't grant $node", LifestealRankRules.forbiddenNodeReason(node) != null)
    }
    check("blacklist message names the command", LifestealSharedCommands.blacklistMessage("Fly") == "/fly is disabled on Lifesteal.")

    // ── C. Competitive safety ─────────────────────────────
    val free = CompetitiveState(combatTagged = false, ranked = false)
    val tagged = CompetitiveState(combatTagged = true, ranked = false)
    val ranked = CompetitiveState(combatTagged = false, ranked = true)
    for ((name, guard) in LifestealSharedCommands.SHARED) {
        check("/$name allowed out of combat", LifestealSharedCommands.competitiveRefusal(guard, free, name) == null)
        if (guard == Guard.COMPETITIVE) {
            check("/$name refused while combat-tagged", LifestealSharedCommands.competitiveRefusal(guard, tagged, name) != null)
            check("/$name refused in a ranked match", LifestealSharedCommands.competitiveRefusal(guard, ranked, name) != null)
        }
        // The ranked listener also refuses every typed shared command while a player is held.
        check("/$name typed during a ranked match is refused", !RankedMatchRules.commandAllowed("/$name", name))
    }
    check("combat refusal comes first", LifestealSharedCommands.competitiveRefusal(Guard.COMPETITIVE, CompetitiveState(true, true), "pay")!!.contains("combat"))

    // ── Wiring ────────────────────────────────────────────
    val lockManager = source("gamemode/GamemodeLockManager.kt")
    val gate = lockManager.substringAfter("class GatedCommand")
    check("canUse falls back to the shared rule", "return isSharedHere(sender, command)" in lockManager)
    check("only JoshyMC's own commands are shared", "command !is PluginCommand || command.plugin !== plugin" in lockManager)
    check("sharing reads this server's hosted gamemodes", "plugin.networkIdentityManager.hostedGamemodes" in lockManager)
    check("the gate refuses a locked command before anything else",
        Regex("""if \(!manager\.canUse\(sender, command\)\) \{\s+manager\.deny""").containsMatchIn(gate))
    check("the gate applies the competitive guard before the executor",
        gate.indexOf("manager.sharedGuardRefusal(sender, command, label)") in 0 until gate.indexOf("executor.onCommand(sender, command, label, args)"))
    check("the guard reads the combat tag", "plugin.combatManager.isTagged(player)" in lockManager)
    check("the guard reads ranked holds, spectators and pending restores",
        "ranked.holds(player) || ranked.isSpectator(player.uniqueId) || ranked.hasPendingRestore(player.uniqueId)" in lockManager)
    check("a failing guard check refuses", "catch (_: Exception) { true }" in lockManager)
    check("the gate still never grants permissions", "hasPermission" !in gate)
    check("blacklisted commands get the Lifesteal message", "LifestealSharedCommands.blacklistMessage(command.name)" in lockManager)
    val essentials = source("command/EssentialsCommands.kt")
    check("/god applies the Lifesteal staff rule", "LifestealSharedCommands.godSelfUse(" in essentials && "GOD_STAFF_ONLY_MESSAGE" in essentials)
    check("/god staff check uses the staff rank", "plugin.staffRankManager.rankOf(p) != null" in essentials)
    check("/god keeps the ranked refusal", "RankedIntegrity.StaffAction.GOD" in essentials)
    check("/heal and /feed keep the #1521 refusal", "SurvivalSelfPerks.decide(plugin.gamemodeLockManager.gamemodeOf(target), selfUse)" in essentials)
    val combat = source("listener/CombatListener.kt")
    for (name in listOf("home", "fly", "heal", "feed", "god", "repair", "fix", "back", "spawn", "tpa", "kit")) {
        check("combat still blocks typed /$name", "\"$name\"" in combat.substringAfter("BLOCKED_COMBAT_COMMANDS").substringBefore(")"))
    }
    check("homes still go through the combat teleport checks", "TeleportChecks.checkAndApply(sender, plugin)" in source("command/HomeCommand.kt"))
    check("trade still refuses combat-tagged players", "plugin.combatManager.isTagged(sender)" in source("manager/TradeManager.kt"))

    // ── D. Regression ─────────────────────────────────────
    for ((name, scope) in GamemodeAssignments.COMMANDS) {
        // Survival server: unchanged (a lock never restricts its own gamemode).
        check("/$name unchanged on Survival", canUse(name, SURVIVAL, SURVIVAL_ONLY, setOf(SURVIVAL)) == GamemodeIsolation.allowed(scope, SURVIVAL, setOf(SURVIVAL)))
        if (scope == GamemodeScope.LIFESTEAL) check("Lifesteal-native /$name works on dedicated Lifesteal", canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
        if (scope == GamemodeScope.NETWORK) check("NETWORK /$name works on dedicated Lifesteal", canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    }
    for (name in GamemodeAssignments.UNASSIGNED.keys) check("unassigned /$name still works", canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    // Staff commands stay restricted where they were (eco, invsee keep their scope and permissions).
    check("/eco is not shared", "eco" !in shared && !canUse("eco", LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    check("/phome (staff) is not shared", "phome" !in shared)
    val commandManager = source("manager/CommandManager.kt")
    for (name in shared) {
        val permission = (commands[name] as? Map<*, *>)?.get("permission")
        check("/$name keeps a permission check", permission != null || "\"$name\" to \"joshymc." in commandManager || name in setOf("home", "sethome", "delhome", "worth"))
    }
    check("god keeps its permission", "\"god\" to \"joshymc.god\"" in commandManager)
    check("fly keeps its permission", "\"fly\" to \"joshymc.fly\"" in commandManager)

    println("Lifesteal shared command checks passed ($checks checks).")
}
