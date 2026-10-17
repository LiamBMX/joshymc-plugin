package com.liam.joshymc.communityevent.game

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.EventPermissions
import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventSessions
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.arena.EventArenas
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.EventManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Spleef and Sumo gameplay for hosted events (issue #1187). Registers one [MinigameGame] per
 * type as the session's controller, so it plugs into the existing lifecycle instead of
 * running its own session. Also owns the floor snapshots, event gear, the gameplay listener
 * and the `/event spleef|sumo <start|reset|next|status>` commands.
 */
class HostedGames(
    val plugin: Joshymc,
    val sessions: EventSessions,
    val arenas: EventArenas,
    private val events: EventManager,
) {
    val floors = SpleefFloors(plugin)
    val gear = EventGear(plugin)
    val spleef = SpleefGame(this)
    val sumo = SumoGame(this)
    val listener = HostedGameListener(this)

    /** Refreshes open Event Manager menus; set by EventManager. */
    var onChange: (() -> Unit)? = null

    private val category = CommunicationsManager.Category.EVENT

    init {
        sessions.registerController(EventType.SPLEEF, spleef)
        sessions.registerController(EventType.SUMO, sumo)
    }

    val eventWorldName: String get() = events.eventWorldName

    /** Standing in the event world (EventManager's separate inventory applies). */
    fun inEventWorld(player: Player): Boolean = player.world.name == events.eventWorldName && events.isInEventWorld(player)

    /** EventManager has saved [player]'s real inventory and swapped in the event inventory. */
    fun inventorySaved(player: Player): Boolean =
        player.world.name == events.eventWorldName && player.uniqueId in events.playersInEventWorld

    fun start() {
        floors.load()
        // Worlds are loaded by the time the server finishes starting; restore any floor a crash left broken.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { floors.recoverAll { it.spawnLocation } }, 40L)
    }

    fun shutdown() {
        floors.shutdown()
    }

    fun changed() {
        runCatching { onChange?.invoke() }
    }

    fun gameFor(type: EventType): MinigameGame? = when (type) {
        EventType.SPLEEF -> spleef
        EventType.SUMO -> sumo
        else -> null
    }

    /** The running Spleef/Sumo game, if any. */
    val running: MinigameGame?
        get() = sessions.active?.takeIf { it.state == EventState.RUNNING }?.let { gameFor(it.type) }?.takeIf { it.isRunning }

    /** Extra start requirement for Spleef/Sumo sessions: a READY arena of the right kind and enough players. */
    fun startProblem(session: EventSession): String? {
        val game = gameFor(session.type) ?: return null
        val arena = arenas.selectedFor(session)
            ?: return "${session.type.displayName} needs an arena. Select one with /event arena select <name>."
        return game.startProblem(session, arena)
    }

    // ── Rules used by EventManager's listeners ────────────

    /**
     * Block breaking by a player of the running game: true = allowed (a Spleef floor block in
     * an ACTIVE round), false = denied (everything else they try), null = not in a game.
     */
    fun breakDecision(player: Player, block: Block): Boolean? {
        val game = running ?: return null
        if (!game.isPlaying(player)) return null
        return game === spleef && spleef.canBreak(player, block)
    }

    /** Players of the running game never place blocks. */
    fun deniesPlace(player: Player): Boolean = running?.isPlaying(player) == true

    // ── Commands ──────────────────────────────────────────

    private val spleefSubs = listOf("start", "reset", "status")
    private val sumoSubs = listOf("next", "status")

    fun handlesCommand(type: EventType, sub: String?): Boolean = when (type) {
        EventType.SPLEEF -> sub in spleefSubs
        EventType.SUMO -> sub in sumoSubs
        else -> false
    }

    fun tabSubs(type: EventType, sender: CommandSender): List<String> {
        if (!EventPermissions.canHost(sender)) return emptyList()
        return when (type) {
            EventType.SPLEEF -> spleefSubs
            EventType.SUMO -> sumoSubs
            else -> emptyList()
        }
    }

    fun handleCommand(sender: CommandSender, type: EventType, args: Array<out String>) {
        if (!EventPermissions.canHost(sender)) { Msg.noPermission(sender, category); return }
        when (args.getOrNull(1)?.lowercase()) {
            "status" -> sendStatus(sender, type)
            "start" -> reply(sender, startRoundCommand())
            "next" -> reply(sender, nextMatchCommand())
            "reset" -> {
                val name = args.getOrNull(2)
                val target = if (name != null) arenas.get(name) ?: run { Msg.error(sender, "No arena named '$name'.", category); return } else null
                val failure = spleef.resetFloor(target, sender)
                if (failure != null) Msg.error(sender, failure.message, category)
                else Msg.success(sender, "Restoring the Spleef floor. You'll be told when it's done.", category)
            }
        }
    }

    fun startRoundCommand(): EventResult {
        val session = sessions.active
        if (session == null || session.type != EventType.SPLEEF) return EventResult.Failure("There is no Spleef event. Create one with /event create spleef.")
        if (session.state != EventState.RUNNING) return EventResult.Failure("Spleef hasn't started. /event start begins the event and its first round.")
        return spleef.startNextRound()
    }

    fun nextMatchCommand(): EventResult {
        val session = sessions.active
        if (session == null || session.type != EventType.SUMO) return EventResult.Failure("There is no Sumo event. Create one with /event create sumo.")
        if (session.state != EventState.RUNNING) return EventResult.Failure("Sumo hasn't started. /event start begins the tournament and its first match.")
        return sumo.startNextMatch()
    }

    fun sendStatus(sender: CommandSender, type: EventType) {
        Msg.reply(sender, Component.text("=== ${type.displayName} Status ===", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), category)
        val session = sessions.active
        val game = gameFor(type) ?: return
        if (session == null || session.type != type) {
            Msg.reply(sender, Component.text("There is no ${type.displayName} event.", NamedTextColor.GRAY), category)
        } else if (session.state != EventState.RUNNING) {
            Msg.reply(sender, Component.text("The event is ${session.state.label.lowercase()} (${session.participantCount} registered).", NamedTextColor.GRAY), category)
            startProblem(session)?.let { Msg.reply(sender, Component.text("Can't start yet: $it", NamedTextColor.RED), category) }
        } else {
            game.statusLines().forEach { Msg.reply(sender, it, category) }
        }
        if (type == EventType.SPLEEF && !spleef.isRunning) {
            arenas.selectedFor(session)?.let {
                Msg.reply(sender, Component.text("Floor: ", NamedTextColor.GRAY).append(Component.text(spleef.floorState(it), NamedTextColor.WHITE)), category)
            }
        }
    }

    private fun reply(sender: CommandSender, result: EventResult) = when (result) {
        is EventResult.Success -> Msg.success(sender, result.message, category)
        is EventResult.Failure, is EventResult.NeedsConfirmation -> Msg.error(sender, result.message, category)
    }
}
