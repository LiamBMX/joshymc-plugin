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
 * Spleef, Sumo (issue #1187), Sharks and Minnows and Crystal PvP (issue #1189) gameplay for
 * hosted events. Registers one [MinigameGame] per type as the session's controller, so it
 * plugs into the existing lifecycle instead of running its own session. Also owns the arena
 * snapshots, event gear, the Crystal PvP kit, the gameplay listener and the
 * `/event spleef|sumo|sharks|cpvp ...` game commands.
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
    val sharks = SharksGame(this)
    val cpvp = CrystalPvpGame(this)
    val kit = CrystalPvpKit(plugin)
    val listener = HostedGameListener(this)

    /** Refreshes open Event Manager menus; set by EventManager. */
    var onChange: (() -> Unit)? = null

    private val category = CommunicationsManager.Category.EVENT

    init {
        sessions.registerController(EventType.SPLEEF, spleef)
        sessions.registerController(EventType.SUMO, sumo)
        sessions.registerController(EventType.SHARKS, sharks)
        sessions.registerController(EventType.CPVP, cpvp)
    }

    val eventWorldName: String get() = events.eventWorldName

    /** Standing in the event world (EventManager's separate inventory applies). */
    fun inEventWorld(player: Player): Boolean = player.world.name == events.eventWorldName && events.isInEventWorld(player)

    /** EventManager has saved [player]'s real inventory and swapped in the event inventory. */
    fun inventorySaved(player: Player): Boolean =
        player.world.name == events.eventWorldName && player.uniqueId in events.playersInEventWorld

    fun start() {
        floors.load()
        kit.load()
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
        EventType.SHARKS -> sharks
        EventType.CPVP -> cpvp
        EventType.MOBS -> null
    }

    /** The running hosted game, if any. */
    val running: MinigameGame?
        get() = sessions.active?.takeIf { it.state == EventState.RUNNING }?.let { gameFor(it.type) }?.takeIf { it.isRunning }

    /** Extra start requirement for hosted game sessions: a READY arena of the right kind and enough players. */
    fun startProblem(session: EventSession): String? {
        val game = gameFor(session.type) ?: return null
        val arena = arenas.selectedFor(session)
            ?: return "${session.type.displayName} needs an arena. Select one with /event arena select <name>."
        return game.startProblem(session, arena)
    }

    // ── Rules used by EventManager's listeners ────────────

    /**
     * Block breaking by a player of the running game: true = allowed (a Spleef floor block in
     * an ACTIVE round, or an allowed Crystal PvP block inside the boundary during a match),
     * false = denied (everything else they try), null = not in a game.
     */
    fun breakDecision(player: Player, block: Block): Boolean? {
        val game = running ?: return null
        if (!game.isPlaying(player)) return null
        return when {
            game === spleef -> spleef.canBreak(player, block)
            game === cpvp -> cpvp.canBuild(player, block)
            else -> false
        }
    }

    /**
     * Block placing, same answers as [breakDecision]: only Crystal PvP competitors place, only
     * the allowed blocks, only inside the boundary, only while their match is ACTIVE.
     */
    fun placeDecision(player: Player, block: Block): Boolean? {
        val game = running ?: return null
        if (!game.isPlaying(player)) return null
        return game === cpvp && cpvp.canBuild(player, block)
    }

    /** [player] is a competitor of an ACTIVE Crystal PvP match (their PvP toggle doesn't apply there). */
    fun inCrystalMatch(player: Player): Boolean = cpvp.isFighting(player.uniqueId)

    /** A hit between the two competitors of the ACTIVE Crystal PvP match: allowed, and never a combat tag. */
    fun isMatchFight(attacker: Player, victim: Player): Boolean = cpvp.isOpponent(attacker.uniqueId, victim.uniqueId)

    // ── Commands ──────────────────────────────────────────

    private val spleefSubs = listOf("start", "reset", "status")
    private val sumoSubs = listOf("next", "status")
    private val sharksSubs = listOf("next", "status")
    private val cpvpSubs = listOf("next", "status", "setkit", "reset")

    private fun subsFor(type: EventType): List<String> = when (type) {
        EventType.SPLEEF -> spleefSubs
        EventType.SUMO -> sumoSubs
        EventType.SHARKS -> sharksSubs
        EventType.CPVP -> cpvpSubs
        EventType.MOBS -> emptyList()
    }

    fun handlesCommand(type: EventType, sub: String?): Boolean = sub in subsFor(type)

    fun tabSubs(type: EventType, sender: CommandSender): List<String> {
        if (!EventPermissions.canHost(sender)) return emptyList()
        return subsFor(type)
    }

    fun handleCommand(sender: CommandSender, type: EventType, args: Array<out String>) {
        if (!EventPermissions.canHost(sender)) { Msg.noPermission(sender, category); return }
        when (args.getOrNull(1)?.lowercase()) {
            "status" -> sendStatus(sender, type)
            "start" -> reply(sender, startRoundCommand())
            "next" -> reply(sender, when (type) {
                EventType.SHARKS -> nextCrossingCommand()
                EventType.CPVP -> nextCrystalMatchCommand()
                else -> nextMatchCommand()
            })
            "setkit" -> {
                val player = sender as? Player ?: run { Msg.playersOnly(sender); return }
                val result = cpvp.saveKit(player)
                if (result.saved) Msg.success(sender, result.message, category) else Msg.error(sender, result.message, category)
            }
            "reset" -> {
                val name = args.getOrNull(2)
                val target = if (name != null) arenas.get(name) ?: run { Msg.error(sender, "No arena named '$name'.", category); return } else null
                val failure = if (type == EventType.CPVP) cpvp.resetArena(target, sender) else spleef.resetFloor(target, sender)
                val what = if (type == EventType.CPVP) "Crystal PvP arena" else "Spleef floor"
                if (failure != null) Msg.error(sender, failure.message, category)
                else Msg.success(sender, "Restoring the $what. You'll be told when it's done.", category)
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

    fun nextCrossingCommand(): EventResult {
        val session = sessions.active
        if (session == null || session.type != EventType.SHARKS) return EventResult.Failure("There is no Sharks and Minnows event. Create one with /event create sharks.")
        if (session.state != EventState.RUNNING) return EventResult.Failure("Sharks and Minnows hasn't started. /event start begins the event and its first crossing.")
        return sharks.startNextCrossing()
    }

    fun nextCrystalMatchCommand(): EventResult {
        val session = sessions.active
        if (session == null || session.type != EventType.CPVP) return EventResult.Failure("There is no Crystal PvP event. Create one with /event create cpvp.")
        if (session.state != EventState.RUNNING) return EventResult.Failure("Crystal PvP hasn't started. /event start begins the tournament and its first match.")
        return cpvp.startNextMatch()
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
        if (type == EventType.CPVP) {
            if (!cpvp.isRunning) {
                arenas.selectedFor(session)?.let {
                    Msg.reply(sender, Component.text("Arena: ", NamedTextColor.GRAY).append(Component.text(cpvp.arenaState(it), NamedTextColor.WHITE)), category)
                }
            }
            val kitText = if (kit.isEmpty) "not saved (/event cpvp setkit)" else "${kit.size} item stack(s)"
            Msg.reply(sender, Component.text("Kit: ", NamedTextColor.GRAY).append(Component.text(kitText, if (kit.isEmpty) NamedTextColor.RED else NamedTextColor.WHITE)), category)
        }
    }

    private fun reply(sender: CommandSender, result: EventResult) = when (result) {
        is EventResult.Success -> Msg.success(sender, result.message, category)
        is EventResult.Failure, is EventResult.NeedsConfirmation -> Msg.error(sender, result.message, category)
    }
}
