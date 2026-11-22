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
import com.liam.joshymc.gamemode.LifestealSharedCommands.LifestealShopRules
import com.liam.joshymc.lifesteal.ranked.RankedIntegrity
import com.liam.joshymc.lifesteal.ranks.LifestealRankRules
import com.liam.joshymc.staff.StaffRank
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

/**
 * The gate's decision ([com.liam.joshymc.gamemode.GamemodeLockManager.canUse]) for a JoshyMC command.
 * [visible] = the sender holds the issue #1599 staff share's `visibleWith` node (staff); false = a regular player.
 */
private fun canUse(name: String, current: ServerGamemode, hosted: Set<ServerGamemode>, locked: Set<ServerGamemode>, visible: Boolean = true): Boolean {
    val scope = GamemodeAssignments.COMMANDS[name] ?: return true
    if (GamemodeIsolation.allowed(scope, current, locked)) return true
    if (scope != GamemodeScope.SURVIVAL) return false
    if (LifestealSharedCommands.sharedHere(name, current, hosted)) return true
    if (!LifestealSharedCommands.adminSharedHere(name, current, hosted)) return false
    return LifestealSharedCommands.ADMIN_SHARED[name]?.visibleWith == null || visible
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
    val adminShared = LifestealSharedCommands.ADMIN_SHARED.keys
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
        val expected = scope != GamemodeScope.SURVIVAL || name in shared || name in adminShared
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
    // Staff commands keep their scope; /eco is a staff share (issue #1599), never a player share.
    check("/eco is not a player share", "eco" !in shared && "eco" in adminShared)
    check("/phome (staff) is not shared", "phome" !in shared && "phome" !in adminShared)
    val commandManager = source("manager/CommandManager.kt")
    for (name in shared) {
        val permission = (commands[name] as? Map<*, *>)?.get("permission")
        check("/$name keeps a permission check", permission != null || "\"$name\" to \"joshymc." in commandManager || name in setOf("home", "sethome", "delhome", "worth", "rtp", "shop"))
    }
    check("god keeps its permission", "\"god\" to \"joshymc.god\"" in commandManager)
    check("fly keeps its permission", "\"fly\" to \"joshymc.fly\"" in commandManager)

    // ── E. Issue #1599: staff commands and approved player utilities ──
    check("the player utilities are shared and guarded", listOf("warp", "rtp", "shop").all { LifestealSharedCommands.guardOf(it) == Guard.COMPETITIVE })
    for (name in listOf("shop", "sell", "balance", "baltop", "warp", "rtp")) {
        check("approved /$name works for players on dedicated Lifesteal", canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks, visible = false))
    }
    for (name in adminShared) {
        check("staff /$name exists in plugin.yml", name in names)
        check("staff /$name keeps its SURVIVAL scope", GamemodeAssignments.COMMANDS[name] == GamemodeScope.SURVIVAL)
        check("staff /$name is not blacklisted or a player share", name !in blacklist && name !in shared)
        check("staff /$name stays refused in a mixed server's Lifesteal worlds", !canUse(name, LIFESTEAL, MIXED, setOf(SURVIVAL)))
        check("staff /$name not shared for Survival senders", !LifestealSharedCommands.adminSharedHere(name, SURVIVAL, DEDICATED_LIFESTEAL))
        check("staff /$name unchanged on Survival", canUse(name, SURVIVAL, SURVIVAL_ONLY, setOf(SURVIVAL)))
    }
    check("the issue's staff commands are shared",
        listOf("setwarp", "delwarp", "editwarp", "eco", "crate", "crateeditor", "event", "enderchest", "voucher").all { it in adminShared })
    check("restricted commands stay restricted", listOf("fly", "god", "heal", "feed", "repair", "back", "claim", "unclaim", "subclaim", "pv",
        "market", "stock", "invest", "coinflip", "casino", "tokens", "credits", "cshop", "flytime", "pwarp", "phome", "top", "resource")
        .all { it !in shared && it !in adminShared })
    for (name in listOf("claim", "pv", "market", "coinflip", "flytime", "back", "fly", "repair")) {
        check("/$name still refused on dedicated Lifesteal", !canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks))
    }
    check("/event hidden from players on Lifesteal", !canUse("event", LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks, visible = false))
    check("/enderchest hidden from players on Lifesteal", !canUse("enderchest", LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks, visible = false))
    check("/event visible with joshymc.event.inventory.recover", LifestealSharedCommands.ADMIN_SHARED["event"]?.visibleWith == "joshymc.event.inventory.recover")
    check("/enderchest visible with joshymc.enderchest.others", LifestealSharedCommands.ADMIN_SHARED["enderchest"]?.visibleWith == "joshymc.enderchest.others")

    fun inv(cmd: String, vararg a: String, me: String = "Staff") = LifestealSharedCommands.adminInvocation(cmd, a.toList(), me)
    fun tab(cmd: String, vararg a: String, me: String = "Staff") = LifestealSharedCommands.adminInvocation(cmd, a.toList(), me, typing = true)
    check("/event inventory runs", inv("event", "inventory", "list") && inv("event", "inv", "info", "x") && inv("event", "INVENTORY"))
    check("/event menu, start, join and the rest stay Survival", !inv("event") && !inv("event", "start", "spleef") && !inv("event", "join") &&
        !inv("event", "setworld") && !inv("event", "rewards", "retry"))
    check("/event tab offers only inventory", tab("event", "in") && tab("event", "") && !tab("event", "st") && tab("event", "inventory", "li"))
    check("/enderchest <other> runs", inv("enderchest", "Bob"))
    check("self /enderchest stays Survival", !inv("enderchest") && !inv("enderchest", "Staff", me = "Staff") && !inv("enderchest", "staff", me = "Staff"))
    check("/enderchest <player> clear stays Survival", !inv("enderchest", "Bob", "clear") && !tab("enderchest", "Bob", "c"))
    check("/voucher give chattag runs", inv("voucher", "give", "Bob", "chattag_haunted") && inv("voucher", "give", "Bob", "CHATTAG_x", "3"))
    check("fly vouchers stay Survival", !inv("voucher", "give", "Bob", "fly:1h") && !inv("voucher", "give", "Bob", "fly", "1h") &&
        !tab("voucher", "give", "Bob", "fly"))
    check("other vouchers stay Survival", !inv("voucher", "give", "Bob", "money1k") && !inv("voucher", "give", "Bob", "chattag_") &&
        !inv("voucher", "give", "Bob") && !inv("voucher", "create", "x", "10", "c", "cmd") && !inv("voucher", "setcommand", "x") &&
        !inv("voucher") && !inv("voucher", "list") && !inv("voucher", "delete", "x"))
    check("/voucher tag debug runs, tag delete doesn't", inv("voucher", "tag", "debug", "haunted") && !inv("voucher", "tag", "delete", "haunted"))
    check("/voucher tab offers give/tag and chattag ids", tab("voucher", "") && tab("voucher", "g") && !tab("voucher", "c") &&
        tab("voucher", "give", "Bob", "chattag_a") && tab("voucher", "give", "Bob", "ch") && tab("voucher", "give", "B"))
    for (name in listOf("setwarp", "delwarp", "editwarp", "eco", "crate", "crateeditor")) check("/$name runs in every form", inv(name, "x", "y"))

    check("/eco needs Owner", LifestealSharedCommands.minimumRank("eco", listOf("give")) == StaffRank.OWNER &&
        LifestealSharedCommands.minimumRank("eco", listOf("set")) == StaffRank.OWNER)
    for (name in listOf("setwarp", "delwarp", "editwarp", "crateeditor")) {
        check("/$name needs Admin+", LifestealSharedCommands.minimumRank(name, emptyList()) == StaffRank.ADMIN)
    }
    for (sub in listOf("setlocation", "removelocation", "locations", "givekey", "force", "models", "rotate", "display", "monthly", "rewards", "diagnose", "inspect")) {
        check("/crate $sub needs Admin+", LifestealSharedCommands.minimumRank("crate", listOf(sub)) == StaffRank.ADMIN)
    }
    for (sub in listOf("menu", "browse", "help", "list", "preview")) {
        check("/crate $sub stays a player subcommand", LifestealSharedCommands.minimumRank("crate", listOf(sub)) == null)
    }
    check("/crate (browser) has no floor", LifestealSharedCommands.minimumRank("crate", emptyList()) == null)
    check("recovery tools keep only their nodes", listOf("event", "enderchest", "voucher").all { LifestealSharedCommands.minimumRank(it, listOf("x")) == null })
    fun floor(f: StaffRank?, console: Boolean, player: Boolean, rank: StaffRank?) = LifestealSharedCommands.staffFloorRefusal(f, console, player, rank, "eco")
    check("console passes every floor", floor(StaffRank.OWNER, true, false, null) == null)
    check("Owner passes the Owner floor", floor(StaffRank.OWNER, false, true, StaffRank.OWNER) == null)
    check("Head Manager and Admin don't pass the Owner floor",
        floor(StaffRank.OWNER, false, true, StaffRank.HEAD_MANAGER) != null && floor(StaffRank.OWNER, false, true, StaffRank.ADMIN) != null)
    check("Admin and above pass the Admin floor", StaffRank.entries.filter { it >= StaffRank.ADMIN }.all { floor(StaffRank.ADMIN, false, true, it) == null })
    check("below Admin is refused", StaffRank.entries.filter { it < StaffRank.ADMIN }.all { floor(StaffRank.ADMIN, false, true, it) != null })
    check("players without a staff rank (donor/Lifesteal ranks) are refused", floor(StaffRank.ADMIN, false, true, null) != null)
    check("command blocks never pass a floor", floor(StaffRank.ADMIN, false, false, null) != null)
    check("no floor = nothing refused", floor(null, false, true, null) == null)

    check("item grants are refused on a held participant",
        RankedIntegrity.staffActionRefusal(RankedIntegrity.StaffAction.ITEM_GRANTS, "T", true, false, false) != null)
    check("item grants wait for a pending restore",
        RankedIntegrity.staffActionRefusal(RankedIntegrity.StaffAction.ITEM_GRANTS, "T", false, true, false) != null)

    for (m in listOf("END_CRYSTAL", "RESPAWN_ANCHOR", "OBSIDIAN", "GLOWSTONE", "ENDER_PEARL", "GOLDEN_APPLE", "ENCHANTED_GOLDEN_APPLE",
        "TOTEM_OF_UNDYING", "EXPERIENCE_BOTTLE", "TNT", "DIAMOND_SWORD", "DIAMOND_CHESTPLATE", "NETHERITE_HELMET", "DIAMOND_AXE", "SHIELD",
        "CHORUS_FRUIT", "COBWEB")) {
        check("shop: $m not sold on Lifesteal", LifestealShopRules.blocked(m, "MATERIAL"))
    }
    check("shop: potions and spawners not sold on Lifesteal", LifestealShopRules.blocked("SPLASH_POTION", "POTION") && LifestealShopRules.blocked("SPAWNER", "SPAWNER"))
    for (m in listOf("WHITE_CONCRETE", "REDSTONE", "HOPPER", "LANTERN", "END_STONE", "QUARTZ", "DIAMOND_PICKAXE", "SHULKER_BOX", "BLAZE_ROD")) {
        check("shop: $m still sold on Lifesteal", !LifestealShopRules.blocked(m, "MATERIAL"))
    }
    @Suppress("UNCHECKED_CAST")
    val shopCategories = resource("shop.yml")["categories"] as Map<String, Map<String, Any?>>
    @Suppress("UNCHECKED_CAST")
    fun entries(category: Map<String, Any?>): List<Pair<String, String>> = (category["items"] as Map<String, Map<String, Any?>>).map { (k, v) ->
        (v["material"] as? String ?: k) to (if (v["effect"] != null) "POTION" else if (v["spawner"] != null) "SPAWNER" else "MATERIAL")
    }
    check("shop: the bundled catalog still sells plenty on Lifesteal",
        shopCategories.values.flatMap { entries(it) }.count { (m, k) -> !LifestealShopRules.blocked(m, k) } > 50)
    check("shop: the bundled PvP & Gear page keeps only the pickaxe on Lifesteal",
        entries(shopCategories["pvp_gear"]!!).filterNot { (m, k) -> LifestealShopRules.blocked(m, k) }.map { it.first }.toSet() == setOf("DIAMOND_PICKAXE"))

    check("the gate checks staff-share forms and floors before the executor",
        gate.indexOf("manager.adminShareRefusal(sender, command, label, args.toList())") in 0 until gate.indexOf("executor.onCommand(sender, command, label, args)"))
    check("tab completion is filtered to the shared forms", "manager.filterAdminShareTab(" in gate)
    check("staff shares need their visibility node", "ADMIN_SHARED[command.name.lowercase()]?.visibleWith" in lockManager)
    check("the floor reads the staff rank", "plugin.staffRankManager.rankOf(p)" in lockManager)
    check("only the console passes as console",
        "sender is org.bukkit.command.ConsoleCommandSender || sender is org.bukkit.command.RemoteConsoleCommandSender" in lockManager)
    check("Lifesteal /enderchest <player> is read-only", "openReadOnly(sender, online)" in essentials && "ENDERCHEST_VIEW" in essentials)
    val shop = source("manager/ServerShopManager.kt")
    check("the shop hides blocked items and re-checks at the buy", "visibleCategories(player)" in shop && "blockedFor(player, shopItem)" in shop &&
        "plugin.gamemodeLockManager.competitiveState(player)" in shop)
    val warp = source("command/WarpCommand.kt")
    check("Lifesteal warps keep the combat checks and add a safe-spot check", "TeleportChecks.checkAndApply(sender, plugin)" in warp &&
        "SafeSpots.problem(location)" in warp && "getChunkAtAsync(location.blockX shr 4, location.blockZ shr 4, false)" in warp &&
        "CombatTeleportGuard.blocks(plugin, player)" in warp)
    check("Lifesteal /setwarp refuses unsafe spots", "SafeSpots.problem(sender.location)" in source("command/SetWarpCommand.kt"))
    val rtp = source("command/RtpCommand.kt")
    check("/rtp keeps its combat and End checks", "TeleportChecks.checkAndApply(sender, plugin)" in rtp &&
        "CombatTeleportGuard.blocks(plugin, player)" in rtp && "joshymc.rtp.end" in rtp)
    check("/crate givekey and chat-tag vouchers wait for ranked", "StaffAction.ITEM_GRANTS" in source("command/CrateCommand.kt") &&
        "StaffAction.ITEM_GRANTS" in source("command/VoucherCommand.kt"))
    check("/eco take|set stay operator/console only",
        "sender is ConsoleCommandSender || sender is RemoteConsoleCommandSender || (sender is Player && sender.isOp)" in source("command/AdminBalanceCommand.kt"))
    check("crate subcommands keep their own nodes", "givekey" in source("crate/CratePermissions.kt") && "CratePermissions" in source("command/CrateCommand.kt"))

    // ── F. Issue #1601 phase 1: cosmetics and crates ──
    for (name in listOf("tag", "chatcolor", "killeffect")) {
        check("cosmetic /$name is shared without a competitive guard", LifestealSharedCommands.guardOf(name) == Guard.NONE)
        check("cosmetic /$name works on dedicated Lifesteal", canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks, visible = false))
        check("cosmetic /$name stays refused in a mixed server's Lifesteal worlds", !canUse(name, LIFESTEAL, MIXED, setOf(SURVIVAL)))
        check("cosmetic /$name keeps its SURVIVAL scope", GamemodeAssignments.COMMANDS[name] == GamemodeScope.SURVIVAL)
        check("cosmetic /$name keeps its permission", "\"$name\" to \"joshymc.$name\"" in commandManager)
    }
    check("/nick stays NETWORK", GamemodeAssignments.COMMANDS["nick"] == GamemodeScope.NETWORK)
    check("unselected cosmetics stay Survival only", listOf("cosmetics", "signitem", "skull", "freetag").all { it !in shared && it !in adminShared })
    val killEffects = source("manager/KillEffectManager.kt")
    check("kill effects still need their own permission", "killer.hasPermission(effect.permission)" in killEffects)
    check("the Firework Pop firework is tagged before it spawns", "world.spawn(center, Firework::class.java)" in killEffects &&
        "cosmeticFireworkKey, PersistentDataType.BYTE" in killEffects && "fw.isPersistent = false" in killEffects)
    check("the Firework Pop explosion deals no damage", "fun onCosmeticFireworkDamage(event: EntityDamageByEntityEvent)" in killEffects &&
        "@EventHandler(priority = EventPriority.LOWEST)\n    fun onCosmeticFireworkDamage" in killEffects)
    check("no other kill effect spawns entities", Regex("""world\.spawn(Entity)?\(""").findAll(killEffects).count() == 1)
    check("lightning stays an effect only", "strikeLightning(" !in killEffects)
    check("chat tag vouchers still wait for ranked", "inventoryReplacedLater(event.player)" in source("listener/ChatTagVoucherListener.kt"))
    val crates = source("manager/CrateManager.kt")
    check("crates refuse Heart item rewards before a key is used",
        "rewardTemplate(reward)?.let { heartItemProblem(it) }" in crates.substringAfter("private fun crateProblem(").substringBefore("\n    }\n"))
    check("Heart items are found in shulker boxes and bundles", "lifestealHeartManager.items" in crates &&
        "org.bukkit.block.ShulkerBox" in crates && "BundleMeta" in crates)
    val editor = source("command/CrateEditorCommand.kt")
    check("the crate editor refuses Heart items and audits it", "plugin.crateManager.heartItemProblem(held)" in editor &&
        "CrateEditorAudit.Reason.HEART_ITEM" in editor &&
        editor.indexOf("heartItemProblem(held)") < editor.indexOf("pendingActions[p.uniqueId] = PendingCrateAction.AddReward"))

    // ── G. Issue #1609 phase 2: economy ──
    for (name in listOf("shop", "sell", "worth", "balance", "baltop", "pay", "ah", "orders", "order", "overflow")) {
        check("economy /$name works for players on dedicated Lifesteal", canUse(name, LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks, visible = false))
        check("economy /$name stays refused in a mixed server's Lifesteal worlds", !canUse(name, LIFESTEAL, MIXED, setOf(SURVIVAL)))
    }
    check("/buyorders is /orders", aliasOwner["buyorders"] == "orders")
    for (name in listOf("orders", "order")) {
        check("/$name is guarded in combat and ranked", LifestealSharedCommands.guardOf(name) == Guard.COMPETITIVE)
        check("/$name keeps joshymc.orders", "\"$name\" to \"joshymc.orders\"" in commandManager)
    }
    check("/eco stays an Owner-only staff share", "eco" !in shared && LifestealSharedCommands.minimumRank("eco", listOf("give")) == StaffRank.OWNER)
    check("/bank stays Lifesteal's own", GamemodeAssignments.COMMANDS["bank"] == GamemodeScope.LIFESTEAL &&
        canUse("bank", LIFESTEAL, DEDICATED_LIFESTEAL, dedicatedLocks, visible = false))
    check("click guard: nothing outside Lifesteal", LifestealSharedCommands.clickRefusal(false, CompetitiveState(true, true), "orders") == null)
    check("click guard: free players pass", LifestealSharedCommands.clickRefusal(true, free, "orders") == null)
    check("click guard: combat refused", LifestealSharedCommands.clickRefusal(true, tagged, "ah")!!.contains("combat"))
    check("click guard: ranked refused", LifestealSharedCommands.clickRefusal(true, ranked, "overflow")!!.contains("Ranked"))
    val catalog = LifestealSharedCommands.LifestealShopCatalog
    check("Lifesteal catalog used on dedicated Lifesteal when present", catalog.catalogFile(DEDICATED_LIFESTEAL, true) == "lifesteal-shop.yml")
    check("no Lifesteal catalog = shop.yml", catalog.catalogFile(DEDICATED_LIFESTEAL, false) == "shop.yml")
    check("Survival and mixed servers keep shop.yml", catalog.catalogFile(SURVIVAL_ONLY, true) == "shop.yml" && catalog.catalogFile(MIXED, true) == "shop.yml")
    check("no lifesteal-shop.yml is bundled (no invented prices)",
        Thread.currentThread().contextClassLoader.getResource("lifesteal-shop.yml") == null)
    check("the shop never merges bundled categories into the Lifesteal catalog",
        shop.substringAfter("if (catalog == LifestealSharedCommands.LifestealShopCatalog.FILE) {").substringBefore("        } else {\n            mergeMissingCategoriesFromDefaults").let {
            "mergeMissingCategoriesFromDefaults" !in it && "saveResource" !in it && "loadCategoriesInto(categories, catalog)" in it
        })
    val lockGuard = lockManager.substringAfter("fun refusesSharedAction(").substringBefore("\n    }\n")
    check("the click guard only checks Lifesteal worlds and reads the live state",
        "gamemodeOf(player.world) == ServerGamemode.LIFESTEAL" in lockGuard && "competitiveState(player)" in lockGuard)
    val orders = source("manager/OrderManager.kt")
    for (fn in listOf("private fun executeCreateOrder(", "fun executeFulfill(", "fun claimPendingDeliveries(")) {
        check("orders: $fn re-checks the guard first",
            orders.substringAfter(fn).substringBefore("\n    fun ").lines().take(4).any { "refusesSharedAction(" in it })
    }
    check("orders: escrow is still withdrawn before the order row", orders.substringAfter("private fun executeCreateOrder(").let {
        it.indexOf("economyManager.withdraw(player.uniqueId, escrow)") in 0 until it.indexOf("INSERT INTO buy_orders")
    })
    check("orders: fills are a conditional update", "WHERE id = ? AND remaining_qty >= ? AND expires_at > ?" in orders)
    check("orders: expired orders are still refunded", "fun checkExpired()" in orders)
    check("orders: filled items still go to Orders Overflow", "overflowManager.storeItems" in orders)
    val ah = source("manager/AuctionManager.kt")
    for (fn in listOf("fun listItem(", "fun buyItem(", "fun cancelListing(", "fun claimExpired(", "fun listBidItem(", "fun placeBid(", "fun cancelBidListing(")) {
        check("ah: $fn re-checks the guard first", ah.substringAfter(fn).lines().take(4).any { "refusesSharedAction(player, \"ah\")" in it })
    }
    check("overflow: withdrawals re-check the guard",
        "refusesSharedAction(player, \"overflow\")" in source("manager/OverflowManager.kt").substringAfter("private fun canWithdraw("))

    println("Lifesteal shared command checks passed ($checks checks).")
}
