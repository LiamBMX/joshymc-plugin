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

    /** Locks stored in the database (`/jgamemode lock|unlock`). */
    @Volatile private var storedLocks: Set<ServerGamemode> = emptySet()
    /** Gamemodes a dedicated server isolates automatically (issue #1336); can't be unlocked. */
    @Volatile private var implied: Set<ServerGamemode> = emptySet()
    /** Every lock in effect: the stored ones plus [implied]. */
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
            storedLocks = store.lockedGamemodes()
        } catch (e: Exception) {
            plugin.logger.severe("[GamemodeLock] Could not load lock states (keeping ${describeLocks()}): ${e.message}")
        }
        locked = storedLocks + implied
        applyCommandGates()
        refreshCommands()
        plugin.logger.info("[GamemodeLock] This server runs ${serverGamemode.displayName}; locked: ${describeLocks()}.")
        // Issue #1576: a stored lock on the gamemode every world runs looks like isolation but does nothing.
        for (gamemode in storedLocks.filter { lockEffect(it) == GamemodeLockEffect.Effect.NO_EFFECT }) {
            plugin.logger.warning(
                "[GamemodeLock] The ${gamemode.displayName} lock has no effect: every world on this server runs ${gamemode.displayName}. " +
                    "If this server should run another gamemode, set gamemode-lock.server-gamemode in config.yml."
            )
        }
    }

    private fun loadConfig() {
        val cfg = plugin.config
        // Resolved (and any bad value logged) by the network identity, read right before (issue #1336).
        serverGamemode = plugin.networkIdentityManager.gamemode
        implied = plugin.networkIdentityManager.isolatedGamemodes

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

    /** Locked automatically because this dedicated server doesn't run [gamemode] (issue #1336). */
    fun isImplied(gamemode: ServerGamemode): Boolean = gamemode in implied

    /** The server gamemode plus every gamemode `gamemode-lock.worlds` maps a world to. */
    val hostedGamemodes: Set<ServerGamemode> get() = worldGamemodes.values.toSet() + serverGamemode

    /** What locking [gamemode] changes here (issue #1576): nothing when every world already runs it. */
    fun lockEffect(gamemode: ServerGamemode): GamemodeLockEffect.Effect =
        GamemodeLockEffect.of(gamemode, hostedGamemodes, implied)

    fun gamemodeOf(world: World?): ServerGamemode =
        world?.let { worldGamemodes[it.name.lowercase()] } ?: serverGamemode

    fun gamemodeOf(sender: CommandSender): ServerGamemode = when (sender) {
        is Entity -> gamemodeOf(sender.world)
        is BlockCommandSender -> gamemodeOf(sender.block.world)
        else -> serverGamemode
    }

    /** Whether any gamemode is locked; while none is, nothing is ever restricted. */
    val anyLocked: Boolean get() = locked.isNotEmpty()

    /** For features: may something declared [scope] run in [world]? */
    fun isAllowed(scope: GamemodeScope, world: World?): Boolean {
        val current = locked
        if (current.isEmpty()) return true
        return GamemodeIsolation.allowed(scope, gamemodeOf(world), current)
    }

    fun isAllowed(scope: GamemodeScope, sender: CommandSender): Boolean {
        val current = locked
        if (current.isEmpty()) return true
        return GamemodeIsolation.allowed(scope, gamemodeOf(sender), current)
    }

    /** For features without a command (issue #1329): may [feature] run in [world]? */
    fun isAllowed(feature: GamemodeFeature, world: World?): Boolean = isAllowed(feature.scope, world)

    fun isAllowed(feature: GamemodeFeature, sender: CommandSender): Boolean = isAllowed(feature.scope, sender)

    /** The gamemode scope of [command], or null when it was never assigned. */
    fun scopeOf(command: Command): GamemodeScope? {
        val name = command.name.lowercase()
        if (command is PluginCommand && command.plugin === plugin) ownScopes[name]?.let { return it }
        return configScopes[name]
    }

    fun canUse(sender: CommandSender, command: Command): Boolean {
        val scope = scopeOf(command) ?: return true
        if (isAllowed(scope, sender)) return true
        return isSharedHere(sender, command)
    }

    /**
     * Issue #1573: a SURVIVAL command listed in [LifestealSharedCommands.SHARED] also runs for a
     * sender in Lifesteal on a server that hosts only Lifesteal (its data is Lifesteal's). Survival
     * stays locked; only JoshyMC's own commands can be shared.
     */
    fun isSharedHere(sender: CommandSender, command: Command): Boolean {
        if (command !is PluginCommand || command.plugin !== plugin) return false
        if (scopeOf(command) != GamemodeScope.SURVIVAL) return false
        val current = gamemodeOf(sender)
        val hosted = plugin.networkIdentityManager.hostedGamemodes
        if (LifestealSharedCommands.sharedHere(command.name, current, hosted)) return true
        // Issue #1599: staff commands, only for senders holding the node that makes them visible there.
        if (!LifestealSharedCommands.adminSharedHere(command.name, current, hosted)) return false
        val node = LifestealSharedCommands.ADMIN_SHARED[command.name.lowercase()]?.visibleWith ?: return true
        return sender.hasPermission(node)
    }

    /** Whether [command] runs for [sender] only because it is an issue #1599 staff share (so its arguments are checked). */
    fun isAdminShareHere(sender: CommandSender, command: Command): Boolean {
        val scope = scopeOf(command) ?: return false
        if (isAllowed(scope, sender)) return false
        if (command !is PluginCommand || command.plugin !== plugin || scope != GamemodeScope.SURVIVAL) return false
        return LifestealSharedCommands.adminSharedHere(command.name, gamemodeOf(sender), plugin.networkIdentityManager.hostedGamemodes)
    }

    /**
     * Issue #1599: why an [LifestealSharedCommands.ADMIN_SHARED] command may not run with [args] for
     * [sender] on Lifesteal (a form Lifesteal doesn't share, or below the staff-rank floor), or null.
     * Every check of the command's own executor (permission nodes, staff protection, ranked
     * refusals, audit) still runs after this.
     */
    fun adminShareRefusal(sender: CommandSender, command: Command, label: String, args: List<String>): String? {
        if (!isAdminShareHere(sender, command)) return null
        if (!LifestealSharedCommands.adminInvocation(command.name, args, (sender as? Player)?.name)) {
            return LifestealSharedCommands.adminFormMessage(command.name)
        }
        val floor = LifestealSharedCommands.minimumRank(command.name, args) ?: return null
        val isConsole = sender is org.bukkit.command.ConsoleCommandSender || sender is org.bukkit.command.RemoteConsoleCommandSender
        val rank = (sender as? Player)?.let { p -> try { plugin.staffRankManager.rankOf(p) } catch (_: Exception) { null } }
        return LifestealSharedCommands.staffFloorRefusal(floor, isConsole, sender is Player, rank, label.lowercase().substringAfter(':'))
    }

    /** Tab suggestions for an issue #1599 staff share: only the forms Lifesteal runs. */
    fun filterAdminShareTab(sender: CommandSender, command: Command, args: Array<out String>, suggestions: List<String>?): List<String>? {
        if (suggestions == null || !isAdminShareHere(sender, command)) return suggestions
        val before = args.dropLast(1)
        val name = (sender as? Player)?.name
        return suggestions.filter { LifestealSharedCommands.adminInvocation(command.name, before + it, name, typing = true) }
    }

    /**
     * Why a [LifestealSharedCommands.Guard.COMPETITIVE] shared command is refused for [sender]
     * right now (combat tag, ranked match), or null. Only applies where the command runs because it
     * is shared; on Survival nothing changes.
     */
    fun sharedGuardRefusal(sender: CommandSender, command: Command, label: String): String? {
        val player = sender as? Player ?: return null
        if (isAllowed(scopeOf(command) ?: return null, sender) || !isSharedHere(sender, command)) return null
        val guard = LifestealSharedCommands.guardOf(command.name) ?: return null
        return LifestealSharedCommands.competitiveRefusal(guard, competitiveState(player), label.lowercase().substringAfter(':'))
    }

    /** The live combat/ranked state the competitive guard reads (a check that can't run counts as true). */
    fun competitiveState(player: Player): LifestealSharedCommands.CompetitiveState =
        LifestealSharedCommands.CompetitiveState(
            combatTagged = safe { plugin.combatManager.isTagged(player) },
            ranked = safe {
                val ranked = plugin.lifestealRankedManager
                ranked.holds(player) || ranked.isSpectator(player.uniqueId) || ranked.hasPendingRestore(player.uniqueId)
            },
        )

    /** A check that throws (manager not started) refuses rather than letting the command through. */
    private inline fun safe(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { true }

    /** Resolves a typed label (alias or `plugin:name` too) to the command it runs. */
    fun resolve(label: String): Command? =
        plugin.server.commandMap.getCommand(label.lowercase().removePrefix("/"))

    fun deny(sender: CommandSender, command: Command) {
        val owner = scopeOf(command)?.gamemode ?: return
        // Issue #1573: the blacklisted commands say they're disabled here, not "only on Survival".
        if (gamemodeOf(sender) == ServerGamemode.LIFESTEAL && command.name.lowercase() in LifestealSharedCommands.BLACKLIST) {
            Msg.error(sender, LifestealSharedCommands.blacklistMessage(command.name))
            return
        }
        Msg.error(sender, "That command is only available on ${owner.displayName}.")
    }

    /** Labels (names, aliases, namespaced forms) [player] may not use right now. */
    fun hiddenLabels(player: Player): Set<String> {
        if (locked.isEmpty()) return emptySet()
        if (ownScopes.values.none { it.gamemode != null } && configScopes.values.none { it.gamemode != null }) return emptySet()
        val hidden = HashSet<String>()
        for ((label, command) in plugin.server.commandMap.knownCommands) {
            if (!canUse(player, command)) hidden.add(label)
        }
        return hidden
    }

    fun describeLocks(): String =
        if (locked.isEmpty()) "none"
        else ServerGamemode.entries.filter { it in locked }.joinToString { if (it in implied) "${it.id} (dedicated server)" else it.id }

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
        // Keep memory in step with the database even if they ever disagreed.
        storedLocks = if (lock) storedLocks + gamemode else storedLocks - gamemode
        locked = storedLocks + implied
        if (!changed) return ChangeResult.ALREADY
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
            manager.sharedGuardRefusal(sender, command, label)?.let {
                Msg.error(sender, it)
                return true
            }
            manager.adminShareRefusal(sender, command, label, args.toList())?.let {
                Msg.error(sender, it)
                return true
            }
            return executor.onCommand(sender, command, label, args)
        }

        override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String>? {
            if (!manager.canUse(sender, command)) return emptyList()
            val delegate = completer ?: executor as? TabCompleter ?: return null
            return manager.filterAdminShareTab(sender, command, args, delegate.onTabComplete(sender, command, label, args))
        }

        companion object {
            /** The command's own executor, whether or not a gate wraps it. */
            fun unwrap(executor: CommandExecutor): CommandExecutor = (executor as? GatedCommand)?.executor ?: executor
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
