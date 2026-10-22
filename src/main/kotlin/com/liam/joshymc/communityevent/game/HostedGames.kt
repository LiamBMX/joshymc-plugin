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
 * Spleef, Sumo (issue #1187), Sharks and Minnows, Crystal PvP (issue #1189) and Mob Waves
 * (issue #1192) gameplay for hosted events. Registers one [MinigameGame] per type as the session's controller, so it
 * plugs into the existing lifecycle instead of running its own session. Also owns the arena
 * snapshots, event gear, the Crystal PvP and Mob Waves kits, the gameplay listeners and the
 * `/event spleef|sumo|sharks|cpvp|mobs ...` game commands.
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
    val mobs = MobWavesGame(this)
    val kit = EventKit(plugin, "event_cpvp_kit", "Crystal PvP")
    val mobsKit = EventKit(plugin, "event_mobs_kit", "Mob Waves")
    val listener = HostedGameListener(this)
    val mobsListener = MobWavesListener(this)

    /** Refreshes open Event Manager menus; set by EventManager. */
    var onChange: (() -> Unit)? = null

    private val category = CommunicationsManager.Category.EVENT

    init {
        sessions.registerController(EventType.SPLEEF, spleef)
        sessions.registerController(EventType.SUMO, sumo)
        sessions.registerController(EventType.SHARKS, sharks)
        sessions.registerController(EventType.CPVP, cpvp)
        sessions.registerController(EventType.MOBS, mobs)
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
        mobsKit.load()
        // Worlds are loaded by the time the server finishes starting; restore any floor a crash left broken.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            floors.recoverAll { it.spawnLocation }
            // No event survives a restart, so any Mob Waves mob still loaded is left over.
            Bukkit.getWorld(eventWorldName)?.let { w ->
                val removed = mobs.sweepStale(w)
                if (removed > 0) plugin.logger.info("[Event] Removed $removed leftover Mob Waves mob(s) from '${w.name}'.")
            }
        }, 40L)
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
        EventType.MOBS -> mobs
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

    // ── Spectating (issue #1284) ──────────────────────────

    /**
     * `/event spectate`: takes [player] to the Viewing Area of the arena the current event uses
     * (its lobby when no Viewing Area is set). Never another arena's spot, and never the event world
     * spawn: without a spot for this arena it refuses. Entering the event world goes through
     * EventManager as usual, so their real inventory is saved and they're in adventure mode.
     */
    fun spectate(player: Player): EventResult {
        val session = sessions.active ?: return EventResult.Failure("There is no event to watch right now.")
        if (session.state != EventState.OPEN && session.state != EventState.RUNNING) {
            return EventResult.Failure("${session.type.displayName} hasn't opened yet.")
        }
        val game = gameFor(session.type) ?: return EventResult.Failure("${session.type.displayName} can't be watched.")
        if (game.isCompeting(player.uniqueId)) return EventResult.Failure("You're competing right now.")
        val arena = arenas.selectedFor(session)
            ?: return EventResult.Failure("${session.type.displayName} has no arena selected yet, so there's nowhere to watch from.")
        val point = arena.spectatorPoint()
            ?: return EventResult.Failure("The arena '${arena.displayName}' has no viewing area yet. Staff can set one with /event arena setviewing ${arena.displayName}.")
        val world = Bukkit.getWorld(arena.world)
        if (world == null || world.name != eventWorldName) return EventResult.Failure("The event world isn't loaded right now.")
        if (!inEventWorld(player) && plugin.combatManager.isTagged(player)) return EventResult.Failure("You can't go watch while you're in combat.")
        if (player.isInsideVehicle) player.leaveVehicle()
        player.fallDistance = 0f
        if (!player.teleport(org.bukkit.Location(world, point.x, point.y, point.z, point.yaw, point.pitch)) || player.world.name != eventWorldName) {
            return EventResult.Failure("You couldn't be teleported to the viewing area.")
        }
        return EventResult.Success(session, "You're watching ${session.type.displayName} in '${arena.displayName}'. Use /spawn to leave.")
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
    private val mobsSubs = listOf("next", "pause", "resume", "status", "setkit")

    private fun subsFor(type: EventType): List<String> = when (type) {
        EventType.SPLEEF -> spleefSubs
        EventType.SUMO -> sumoSubs
        EventType.SHARKS -> sharksSubs
        EventType.CPVP -> cpvpSubs
        EventType.MOBS -> mobsSubs
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
                EventType.MOBS -> nextWaveCommand()
                else -> nextMatchCommand()
            })
            "pause" -> reply(sender, pauseWavesCommand())
            "resume" -> reply(sender, resumeWavesCommand())
            "setkit" -> {
                val player = sender as? Player ?: run { Msg.playersOnly(sender); return }
                val result = if (type == EventType.MOBS) mobs.saveKit(player) else cpvp.saveKit(player)
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

    /** Why a Mob Waves command can't run now, or null. */
    private fun mobsProblem(): EventResult.Failure? {
        val session = sessions.active
        if (session == null || session.type != EventType.MOBS) return EventResult.Failure("There is no Mob Waves event. Create one with /event create mobs.")
        if (session.state != EventState.RUNNING) return EventResult.Failure("Mob Waves hasn't started. /event start begins the event and its first wave.")
        return null
    }

    fun nextWaveCommand(): EventResult = mobsProblem() ?: mobs.nextWave()

    fun pauseWavesCommand(): EventResult = mobsProblem() ?: mobs.pause()

    fun resumeWavesCommand(): EventResult = mobsProblem() ?: mobs.resume()

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
            sendKitLine(sender, kit, "cpvp")
        }
        if (type == EventType.MOBS) {
            val s = mobs.readSettings()
            Msg.reply(sender, Component.text("Waves configured: ", NamedTextColor.GRAY).append(Component.text("${s.playable.size} (max ${s.maxActiveMobs} mobs alive, ${s.intermissionSeconds}s intermission)", NamedTextColor.WHITE)), category)
            s.problems.take(5).forEach { Msg.reply(sender, Component.text("Config problem: $it", NamedTextColor.RED), category) }
            if (s.problems.size > 5) Msg.reply(sender, Component.text("...and ${s.problems.size - 5} more (see hosted-events.mobs in config.yml).", NamedTextColor.RED), category)
            sendKitLine(sender, mobsKit, "mobs")
        }
    }

    private fun sendKitLine(sender: CommandSender, kit: EventKit, id: String) {
        val kitText = if (kit.isEmpty) "not saved (/event $id setkit)" else "${kit.size} item stack(s)"
        Msg.reply(sender, Component.text("Kit: ", NamedTextColor.GRAY).append(Component.text(kitText, if (kit.isEmpty) NamedTextColor.RED else NamedTextColor.WHITE)), category)
    }

    private fun reply(sender: CommandSender, result: EventResult) = when (result) {
        is EventResult.Success -> Msg.success(sender, result.message, category)
        is EventResult.Failure, is EventResult.NeedsConfirmation -> Msg.error(sender, result.message, category)
    }
}
