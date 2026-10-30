// Checks the gamemode scope assignments and isolation rules (issue #1329). The Gradle build has no
// test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/gamemode-scope-check.init.gradle.kts gamemodeScopeCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeEventGate
import com.liam.joshymc.gamemode.GamemodeFeature
import com.liam.joshymc.gamemode.GamemodeIsolation
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.GamemodeScope.NETWORK
import com.liam.joshymc.gamemode.GamemodeScope.SURVIVAL
import com.liam.joshymc.gamemode.ServerGamemode
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

// Paper's own classes target Java 25 and this check runs on the Java 21 toolchain, so it never
// loads a Bukkit class: YAML through snakeyaml, events by class name.
private fun resource(name: String): Map<String, Any?> {
    val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(name)
        ?: run { System.err.println("FAIL: $name not on the classpath"); exitProcess(1) }
    return InputStreamReader(stream, Charsets.UTF_8).use { Yaml().load<Map<String, Any?>>(it) }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.section(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

private fun Map<String, Any?>.path(path: String): Any? {
    var node: Any? = this
    for (part in path.split('.')) node = (node as? Map<*, *>)?.get(part)
    return node
}

private fun events(vararg hierarchy: String) = hierarchy.asSequence().map { "org.bukkit.event.$it" }

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

/** Same rule the command gate and the feature checks use. */
private fun allowed(scope: GamemodeScope?, current: ServerGamemode, locked: Set<ServerGamemode>): Boolean =
    scope == null || GamemodeIsolation.allowed(scope, current, locked)

private val SURVIVAL_SERVER = ServerGamemode.SURVIVAL
private val LIFESTEAL_SERVER = ServerGamemode.LIFESTEAL
private val NONE = emptySet<ServerGamemode>()
private val SURVIVAL_LOCKED = setOf(ServerGamemode.SURVIVAL)
private val LIFESTEAL_LOCKED = setOf(ServerGamemode.LIFESTEAL)
private val BOTH_LOCKED = setOf(ServerGamemode.SURVIVAL, ServerGamemode.LIFESTEAL)

fun main() {
    val pluginYml = resource("plugin.yml")
    val commands = pluginYml.section("commands")!!
    val names = commands.keys.map { it.lowercase() }.toSet()
    val table = GamemodeAssignments.COMMANDS
    val scopeOf = { name: String -> table[name] }

    // ── Classification coverage ────────────────────────────
    for (name in names) check("/$name is assigned or deliberately unassigned", name in GamemodeAssignments.KNOWN)
    for (name in GamemodeAssignments.KNOWN) check("/$name in the scope table exists in plugin.yml", name in names)
    check("no command is both assigned and unassigned", table.keys.intersect(GamemodeAssignments.UNASSIGNED.keys).isEmpty())
    check("unassigned commands all say why", GamemodeAssignments.UNASSIGNED.values.all { it.isNotBlank() })
    check("keys are lower-case plugin.yml names", table.keys.all { it == it.lowercase() && !it.startsWith("/") })
    check("no JoshyMC command is assigned to Lifesteal yet", table.values.none { it == GamemodeScope.LIFESTEAL })

    // Scopes set by earlier issues are unchanged.
    check("jgamemode NETWORK", scopeOf("jgamemode") == NETWORK)
    check("freerank NETWORK", scopeOf("freerank") == NETWORK)
    check("punish NETWORK", scopeOf("punish") == NETWORK)
    check("eventshop SURVIVAL", scopeOf("eventshop") == SURVIVAL)
    check("flytime SURVIVAL", scopeOf("flytime") == SURVIVAL)

    // Survival gameplay.
    for (name in listOf(
        "claim", "unclaim", "subclaim", "balance", "pay", "eco", "baltop", "ah", "orders", "order", "market", "invest",
        "shop", "sell", "chestshop", "crate", "crateeditor", "quests", "daily", "eventquests", "loginstreak", "kit",
        "customenchant", "fly", "voucher", "rankvoucher", "candy", "spawner", "hopper", "team", "leaderboard", "event",
        "home", "warp", "rtp", "pv", "tokens", "credits", "cshop", "resurge", "jvote", "tutorial",
    )) check("/$name is SURVIVAL", scopeOf(name) == SURVIVAL)

    // Network-wide infrastructure, staff and moderation.
    for (name in listOf(
        "joshymc", "ban", "tempban", "unban", "mute", "tempmute", "unmute", "warn", "pkick", "history", "admin",
        "vanish", "modmode", "staffchat", "report", "reports", "msg", "reply", "link", "unlink", "settings",
        "announce", "jrestart", "help", "rules", "discord", "jmc-violation", "purchase", "rank", "unstuck", "tp",
    )) check("/$name is NETWORK", scopeOf(name) == NETWORK)

    // ── Aliases and subcommands ────────────────────────────
    // Aliases resolve to their command (Bukkit's command map), so they must never be scope-table keys
    // of a different command, and an alias must not shadow another command's name.
    val aliasOwner = HashMap<String, String>()
    for (name in names) {
        val aliases = commands.section(name)?.get("aliases")
        val list = when (aliases) {
            is List<*> -> aliases.map { it.toString() }
            is String -> listOf(aliases)
            else -> emptyList()
        }
        for (alias in list) aliasOwner[alias.lowercase()] = name
    }
    for ((alias, owner) in aliasOwner) {
        check("alias /$alias is not a separate table key", alias !in GamemodeAssignments.KNOWN || alias == owner)
    }
    check("/q resolves to /quests", aliasOwner["q"] == "quests" && scopeOf("quests") == SURVIVAL)
    check("/bal resolves to /balance", aliasOwner["bal"] == "balance")
    check("/eshop resolves to /eventshop", aliasOwner["eshop"] == "eventshop")
    check("/sc resolves to /staffchat (network)", aliasOwner["sc"] == "staffchat" && scopeOf("staffchat") == NETWORK)
    // Subcommands share the command's executor, which the gate wraps as a whole.
    val lockManager = source("gamemode/GamemodeLockManager.kt")
    check("gate wraps the whole executor", "GatedCommand(this, command.executor, command.tabCompleter)" in lockManager)
    check("gate checks before running the executor", Regex("""if \(!manager\.canUse\(sender, command\)\) \{\s+manager\.deny""").containsMatchIn(lockManager))
    check("gate also filters tab completion", "if (!manager.canUse(sender, command)) return emptyList()" in lockManager)
    check("denied senders get a clear message", "is only available on" in lockManager)

    // ── Permissions untouched ──────────────────────────────
    // The gate only adds a check in front of the executor; Bukkit's own permission check still runs first.
    check("gate never grants permissions", "hasPermission" !in lockManager.substringAfter("class GatedCommand"))
    check("jgamemode keeps its permissions", commands.path("jgamemode.permission") == "joshymc.jgm.lock;joshymc.jgm.unlock")
    check("flytime keeps its permission", commands.path("flytime.permission") == "joshymc.flytime.use")

    // ── Isolation scenarios ────────────────────────────────
    for ((name, scope) in table) {
        // Survival server: nothing ever changes, locked or not (a lock never restricts its own gamemode).
        for (locks in listOf(NONE, SURVIVAL_LOCKED, LIFESTEAL_LOCKED, BOTH_LOCKED)) {
            check("/$name works on Survival with locks $locks", allowed(scope, SURVIVAL_SERVER, locks))
        }
        // Lifesteal server with nothing locked: backward compatible, everything still works.
        check("/$name works on unlocked Lifesteal", allowed(scope, LIFESTEAL_SERVER, NONE))
        // Lifesteal with Survival isolation on: only network commands remain.
        check("/$name on Lifesteal with Survival locked", allowed(scope, LIFESTEAL_SERVER, SURVIVAL_LOCKED) == (scope == NETWORK))
        // Locking Lifesteal alone never blocks Survival or network commands anywhere.
        check("/$name on Lifesteal with Lifesteal locked", allowed(scope, LIFESTEAL_SERVER, LIFESTEAL_LOCKED))
    }
    for (name in GamemodeAssignments.UNASSIGNED.keys) {
        check("unassigned /$name works on locked Lifesteal", allowed(scopeOf(name), LIFESTEAL_SERVER, BOTH_LOCKED))
    }
    check("SURVIVAL blocked on Lifesteal when isolation is active", !allowed(SURVIVAL, LIFESTEAL_SERVER, SURVIVAL_LOCKED))
    check("NETWORK available on locked Lifesteal", allowed(NETWORK, LIFESTEAL_SERVER, BOTH_LOCKED))
    check("NETWORK available on locked Survival", allowed(NETWORK, SURVIVAL_SERVER, BOTH_LOCKED))

    // ── Features (listeners, managers, scheduled tasks) ────
    check("feature ids are unique", GamemodeFeature.entries.map { it.id }.toSet().size == GamemodeFeature.entries.size)
    for (feature in GamemodeFeature.entries) {
        check("${feature.id} is Survival", feature.scope == SURVIVAL)
        check("${feature.id} parses", GamemodeFeature.fromId(feature.id.uppercase()) == feature)
        check("${feature.id} runs on Survival", allowed(feature.scope, SURVIVAL_SERVER, BOTH_LOCKED))
        check("${feature.id} runs on unlocked Lifesteal", allowed(feature.scope, LIFESTEAL_SERVER, NONE))
        check("${feature.id} stops on Lifesteal with Survival locked", !allowed(feature.scope, LIFESTEAL_SERVER, SURVIVAL_LOCKED))
    }

    // Cleanup events always reach scoped listeners, gameplay events don't.
    check("quit always runs", GamemodeEventGate.alwaysRuns(events("player.PlayerQuitEvent", "player.PlayerEvent", "Event")))
    check("join always runs", GamemodeEventGate.alwaysRuns(events("player.PlayerJoinEvent", "player.PlayerEvent", "Event")))
    check("kick always runs", GamemodeEventGate.alwaysRuns(events("player.PlayerKickEvent", "player.PlayerEvent", "Event")))
    check("world change always runs", GamemodeEventGate.alwaysRuns(events("player.PlayerChangedWorldEvent", "player.PlayerEvent", "Event")))
    check("a subclass of a cleanup event always runs", GamemodeEventGate.alwaysRuns(sequenceOf("x.CustomQuit") + events("player.PlayerQuitEvent", "Event")))
    check("block break is gated", !GamemodeEventGate.alwaysRuns(events("block.BlockBreakEvent", "block.BlockExpEvent", "block.BlockEvent", "Event")))
    check("damage is gated", !GamemodeEventGate.alwaysRuns(events("entity.EntityDamageByEntityEvent", "entity.EntityDamageEvent", "entity.EntityEvent", "Event")))
    check("interact is gated", !GamemodeEventGate.alwaysRuns(events("player.PlayerInteractEvent", "player.PlayerEvent", "Event")))
    check("consume is gated", !GamemodeEventGate.alwaysRuns(events("player.PlayerItemConsumeEvent", "player.PlayerEvent", "Event")))

    val scopedListeners = source("gamemode/GamemodeScopedListeners.kt")
    check("scoped listeners skip the check while nothing is locked", "!plugin.gamemodeLockManager.anyLocked ||" in scopedListeners)
    check("scoped listeners keep priority and ignoreCancelled", "annotation.priority, executor, plugin, annotation.ignoreCancelled" in scopedListeners)

    val listeners = source("manager/ListenerManager.kt")
    for ((listener, feature) in listOf(
        "CombatEnchantListener" to GamemodeFeature.CUSTOM_ENCHANTS,
        "ToolEnchantListener" to GamemodeFeature.CUSTOM_ENCHANTS,
        "WeaponEnchantListener" to GamemodeFeature.CUSTOM_ENCHANTS,
        "DrillMiningListener" to GamemodeFeature.CUSTOM_ITEMS,
        "EasterEggListener" to GamemodeFeature.CUSTOM_ITEMS,
        "SellWandListener" to GamemodeFeature.CUSTOM_ITEMS,
        "PeanutButterCupListener" to GamemodeFeature.CUSTOM_ITEMS,
        "LinkPopListener" to GamemodeFeature.CUSTOM_ITEMS,
        "JosheesBarListener" to GamemodeFeature.CUSTOM_ITEMS,
        "RurusListener" to GamemodeFeature.CUSTOM_ITEMS,
        "CandyCornListener" to GamemodeFeature.CUSTOM_ITEMS,
        "VeinminerListener" to GamemodeFeature.MINING_PERKS,
        "AutoSmeltListener" to GamemodeFeature.MINING_PERKS,
        "TreeFellerListener" to GamemodeFeature.MINING_PERKS,
    )) {
        check("$listener is scoped to ${feature.id}", "scoped($listener(plugin), GamemodeFeature.${feature.name})" in listeners)
        check("$listener is not also registered unscoped", "pm.registerEvents($listener(plugin)" !in listeners)
    }
    // Protection/integrity listeners must stay unscoped so nothing becomes exploitable anywhere.
    for (listener in listOf("ClaimProtectionListener", "VikingBoomerangListener", "TomatoListener", "SellWandAnvilListener", "CustomArmorAnvilListener")) {
        check("$listener stays unscoped", "pm.registerEvents($listener(plugin), plugin)" in listeners)
    }

    // Scheduled/background progress and rewards.
    for ((file, feature) in listOf(
        "manager/QuestCycleManager.kt" to GamemodeFeature.QUESTS,
        "manager/EventQuestManager.kt" to GamemodeFeature.EVENT_QUESTS,
        "manager/LoginStreakManager.kt" to GamemodeFeature.LOGIN_STREAK,
        "manager/AFKManager.kt" to GamemodeFeature.AFK_REWARDS,
    )) {
        check("$file checks ${feature.id}", "isAllowed(com.liam.joshymc.gamemode.GamemodeFeature.${feature.name}, player.world)" in source(file))
    }
    check("tutorial auto-open respects the gate", "gamemodeLockManager.canUse(player, command)" in source("listener/WelcomeListener.kt"))

    // ── Default configuration compatibility ────────────────
    val config = resource("config.yml")
    check("default server gamemode is survival", config.path("gamemode-lock.server-gamemode") == "survival")
    check("default has no world overrides", (config.path("gamemode-lock.worlds") as? Map<*, *>).isNullOrEmpty())
    check("default has no extra command assignments", (config.path("gamemode-lock.commands") as? Map<*, *>).isNullOrEmpty())
    check("default server id unchanged", config.path("network.server-id") == "survival")
    check("default primary unchanged", config.path("network.primary") == true)
    check("bundled server gamemode parses", ServerGamemode.fromId(config.path("gamemode-lock.server-gamemode") as? String) == ServerGamemode.SURVIVAL)

    println("OK: $checks gamemode scope checks passed.")
}
