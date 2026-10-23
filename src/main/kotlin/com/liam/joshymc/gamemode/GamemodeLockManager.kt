package com.liam.joshymc.gamemode

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.World
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginCommand
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

/**
 * Gamemode isolation locks (issue #1289).
 *
 * Each server (or world, via `gamemode-lock.worlds`) runs one [ServerGamemode]. Commands and
 * features assigned to a gamemode ([GamemodeAssignments], `gamemode-lock.commands`, or a
 * feature's own [GamemodeScope]) only work outside that gamemode while it is unlocked; locking
 * it restricts them to their own gamemode. Lock states live in SQLite and survive restarts.
 *
 * Enforcement of commands:
 *  - JoshyMC's own assigned commands get their executor and tab completer wrapped
 *    ([GatedCommand]), so every route in (aliases, `joshymc:` names, /execute, dispatchCommand)
 *    is checked.
 *  - Every assigned command (also other plugins' ones from config) is checked again when typed
 *    by a player or the console and hidden from the client's command list.
 */
class GamemodeLockManager(private val plugin: Joshymc) {

    private val store = GamemodeLockStore(plugin.databaseManager)

    @Volatile private var locked: Set<ServerGamemode> = emptySet()

    var serverGamemode: ServerGamemode = ServerGamemode.SURVIVAL
        private set
    /** Lower-case world name → gamemode, for worlds that differ from [serverGamemode]. */
    private var worldGamemodes: Map<String, ServerGamemode> = emptyMap()
    /** Code-declared scopes; apply only to JoshyMC's own commands. */
    private var ownScopes: Map<String, GamemodeScope> = emptyMap()
    /** Config-assigned scopes; apply to any command with that name. */
    private var configScopes: Map<String, GamemodeScope> = emptyMap()

    val hasWorldOverrides: Boolean get() = worldGamemodes.isNotEmpty()

    fun start() {
        store.createTables()
        loadConfig()
        try {
            locked = store.lockedGamemodes()
        } catch (e: Exception) {
            plugin.logger.severe("[GamemodeLock] Could not load lock states (keeping ${describeLocks()}): ${e.message}")
        }
        applyCommandGates()
        refreshCommands()
        plugin.logger.info("[GamemodeLock] This server runs ${serverGamemode.displayName}; locked: ${describeLocks()}.")
    }

    private fun loadConfig() {
        val cfg = plugin.config
        val rawServer = cfg.getString("gamemode-lock.server-gamemode", "survival")
        serverGamemode = ServerGamemode.fromId(rawServer) ?: run {
            plugin.logger.warning("[GamemodeLock] Unknown gamemode-lock.server-gamemode '$rawServer', using survival.")
            ServerGamemode.SURVIVAL
        }

        val worlds = mutableMapOf<String, ServerGamemode>()
        cfg.getConfigurationSection("gamemode-lock.worlds")?.let { section ->
            for (world in section.getKeys(false)) {
                val raw = section.getString(world)
                val gamemode = ServerGamemode.fromId(raw)
                if (gamemode == null) plugin.logger.warning("[GamemodeLock] Ignoring world '$world': unknown gamemode '$raw'.")
                else worlds[world.lowercase()] = gamemode
            }
        }
        worldGamemodes = worlds

        ownScopes = GamemodeAssignments.COMMANDS.mapKeys { it.key.lowercase() }
        val fromConfig = mutableMapOf<String, GamemodeScope>()
        cfg.getConfigurationSection("gamemode-lock.commands")?.let { section ->
            for (name in section.getKeys(false)) {
                val raw = section.getString(name)
                val scope = GamemodeScope.fromId(raw)
                val key = name.lowercase().removePrefix("/")
                when {
                    scope == null -> plugin.logger.warning("[GamemodeLock] Ignoring command '$name': unknown gamemode '$raw'.")
                    ownScopes[key] != null && ownScopes[key] != scope ->
                        plugin.logger.warning("[GamemodeLock] Ignoring command '$name' = $raw: JoshyMC declares it ${ownScopes[key]!!.id}.")
                    else -> fromConfig[key] = scope
                }
            }
        }
        configScopes = fromConfig
    }

    // ── Queries ─────────────────────────────────────────────

    fun isLocked(gamemode: ServerGamemode): Boolean = gamemode in locked

    fun gamemodeOf(world: World?): ServerGamemode =
        world?.let { worldGamemodes[it.name.lowercase()] } ?: serverGamemode

    fun gamemodeOf(sender: CommandSender): ServerGamemode = when (sender) {
        is Entity -> gamemodeOf(sender.world)
        is BlockCommandSender -> gamemodeOf(sender.block.world)
        else -> serverGamemode
    }

    /** For features: may something declared [scope] run in [world]? */
    fun isAllowed(scope: GamemodeScope, world: World?): Boolean =
        GamemodeIsolation.allowed(scope, gamemodeOf(world), locked)

    fun isAllowed(scope: GamemodeScope, sender: CommandSender): Boolean =
        GamemodeIsolation.allowed(scope, gamemodeOf(sender), locked)

    /** The gamemode scope of [command], or null when it was never assigned. */
    fun scopeOf(command: Command): GamemodeScope? {
        val name = command.name.lowercase()
        if (command is PluginCommand && command.plugin === plugin) ownScopes[name]?.let { return it }
        return configScopes[name]
    }

    fun canUse(sender: CommandSender, command: Command): Boolean {
        val scope = scopeOf(command) ?: return true
        return isAllowed(scope, sender)
    }

    /** Resolves a typed label (alias or `plugin:name` too) to the command it runs. */
    fun resolve(label: String): Command? =
        plugin.server.commandMap.getCommand(label.lowercase().removePrefix("/"))

    fun deny(sender: CommandSender, command: Command) {
        val owner = scopeOf(command)?.gamemode ?: return
        Msg.error(sender, "That command is only available on ${owner.displayName}.")
    }

    /** Labels (names, aliases, namespaced forms) [player] may not use right now. */
    fun hiddenLabels(player: Player): Set<String> {
        if (ownScopes.values.none { it.gamemode != null } && configScopes.values.none { it.gamemode != null }) return emptySet()
        val hidden = HashSet<String>()
        for ((label, command) in plugin.server.commandMap.knownCommands) {
            if (!canUse(player, command)) hidden.add(label)
        }
        return hidden
    }

    fun describeLocks(): String =
        if (locked.isEmpty()) "none" else ServerGamemode.entries.filter { it in locked }.joinToString { it.id }

    // ── Lock / unlock ───────────────────────────────────────

    enum class ChangeResult { CHANGED, ALREADY, FAILED }

    fun setLocked(gamemode: ServerGamemode, lock: Boolean, sender: CommandSender): ChangeResult {
        val actor = sender.name
        val actorUuid = (sender as? Player)?.uniqueId?.toString()
        val changed = try {
            store.setLocked(gamemode, lock, actor, actorUuid, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.severe("[GamemodeLock] $actor tried to ${if (lock) "lock" else "unlock"} ${gamemode.id}, but saving failed: ${e.message}")
            return ChangeResult.FAILED
        }
        if (!changed) {
            // Keep memory in step with the database even if they ever disagreed.
            locked = if (lock) locked + gamemode else locked - gamemode
            return ChangeResult.ALREADY
        }
        locked = if (lock) locked + gamemode else locked - gamemode
        plugin.logger.info("[GamemodeLock] $actor ${if (lock) "LOCKED" else "UNLOCKED"} ${gamemode.displayName} isolation (locked now: ${describeLocks()}).")
        refreshCommands()
        return ChangeResult.CHANGED
    }

    // ── Command gating ──────────────────────────────────────

    /** Resends every online player's command list (visibility depends on locks and world). */
    fun refreshCommands() {
        for (player in plugin.server.onlinePlayers) player.updateCommands()
    }

    /**
     * Wraps JoshyMC's assigned commands in a [GatedCommand] and unwraps ones no longer
     * assigned. Runs after CommandManager.registerAll (which installs fresh executors).
     */
    private fun applyCommandGates() {
        @Suppress("DEPRECATION")
        for (name in plugin.description.commands.keys) {
            val command = plugin.getCommand(name) ?: continue
            val current = command.executor
            if (current is GatedCommand) {
                command.setExecutor(current.executor)
                command.tabCompleter = current.completer
            }
            if (scopeOf(command)?.gamemode == null) continue
            val gate = GatedCommand(this, command.executor, command.tabCompleter)
            command.setExecutor(gate)
            command.tabCompleter = gate
        }
    }

    class GatedCommand(
        private val manager: GamemodeLockManager,
        val executor: CommandExecutor,
        val completer: TabCompleter?,
    ) : CommandExecutor, TabCompleter {

        override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
            if (!manager.canUse(sender, command)) {
                manager.deny(sender, command)
                return true
            }
            return executor.onCommand(sender, command, label, args)
        }

        override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String>? {
            if (!manager.canUse(sender, command)) return emptyList()
            val delegate = completer ?: executor as? TabCompleter ?: return null
            return delegate.onTabComplete(sender, command, label, args)
        }
    }

    companion object {
        fun statusLine(gamemode: ServerGamemode, locked: Boolean): Component =
            Component.text("${gamemode.displayName}: ", NamedTextColor.GRAY)
                .append(
                    if (locked) Component.text("locked", NamedTextColor.RED)
                    else Component.text("unlocked", NamedTextColor.GREEN)
                )
    }
}
