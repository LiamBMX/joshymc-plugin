package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.result.EventOutcome
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.EventArena
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Hosted Spleef (issue #1187). `/event start` brings every registered player to the arena
 * and starts round 1; after each round staff start the next with `/event spleef start` until
 * `max-rounds` is reached (or `/event stop`). Players break only the saved floor region, and
 * only while their round is ACTIVE; dropping below the elimination height knocks them out.
 * The floor is captured before every round and restored afterwards (see [SpleefFloors]).
 */
class SpleefGame(games: HostedGames) : MinigameGame(games, EventType.SPLEEF) {

    private var settings = SpleefSettings.read(null)
    private var roundTimer: org.bukkit.scheduler.BukkitTask? = null

    override fun minPlayers(): Int = SpleefSettings.read(plugin.config.getConfigurationSection("hosted-events")).minPlayers

    override fun setupProblem(arena: EventArena): String? {
        val s = SpleefSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        val floor = arena.region(ArenaField.FLOOR) ?: return "Arena '${arena.displayName}' has no Floor Region. Set it with /event spleef setfloor."
        if (arena.height(ArenaField.LOSE_HEIGHT) == null) return "Arena '${arena.displayName}' has no Elimination Height. Set it with /event spleef setlose."
        if (arena.point(ArenaField.SPAWN) == null) return "Arena '${arena.displayName}' has no Player Spawn. Set it with /event spleef setspawn."
        if (floor.volume > s.maxFloorBlocks) {
            return "The floor is ${floor.volume} blocks; Spleef floors can be at most ${s.maxFloorBlocks} (hosted-events.spleef.floor.max-blocks)."
        }
        if (!games.floors.available) return "Spleef floor storage failed to load at startup; check the console."
        if (games.floors.isRestoring(arena.id)) return "The floor of '${arena.displayName}' is still being restored. Try again in a moment."
        if (games.floors.isDamaged(arena.id)) return "The floor of '${arena.displayName}' hasn't been restored yet. Run /event spleef reset first."
        return null
    }

    override fun begin(session: EventSession) {
        settings = SpleefSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        val waiting = waitingLocation() ?: error("The event world isn't loaded")
        var arrived = 0
        for (player in gatherPlayers(session)) {
            if (games.inEventWorld(player) || bringIn(player, waiting)) arrived++
            else comms.send(player, Component.text("You couldn't be teleported to Spleef.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        if (arrived < settings.minPlayers) error("only $arrived registered player(s) could be brought to the arena; Spleef needs ${settings.minPlayers}")
        startRound()?.let { error(it) }
    }

    // ── Rounds ────────────────────────────────────────────

    /** `/event spleef start` / the GUI's Start Round. */
    fun startNextRound(): EventResult {
        val s = session ?: return EventResult.Failure("Spleef isn't running. Start the event with /event start.")
        if (!isRunning) return EventResult.Failure("Spleef isn't running. Start the event with /event start.")
        when (round.state) {
            RoundState.COUNTDOWN, RoundState.ACTIVE -> return EventResult.Failure("Round ${round.round} is already in progress.")
            RoundState.ENDING -> return EventResult.Failure("Round ${round.round} is still ending. Try again in a moment.")
            RoundState.COMPLETE -> return EventResult.Failure("Spleef is complete. The event ends shortly.")
            RoundState.WAITING -> {}
        }
        settings = SpleefSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        if (settings.maxRounds in 1..round.round) return EventResult.Failure("All ${settings.maxRounds} round(s) have been played.")
        startRound()?.let { return EventResult.Failure(it) }
        return EventResult.Success(s, "Spleef round ${round.round} is starting.")
    }

    /** Captures the floor, places the round's players and starts the countdown. Returns why it couldn't, or null. */
    private fun startRound(): String? {
        val s = session ?: return "Spleef isn't running."
        val arena = arena ?: return "No arena is selected."
        val floor = arena.region(ArenaField.FLOOR) ?: return "The arena has no Floor Region."
        val spawn = arena.point(ArenaField.SPAWN)?.let { toLocation(it) } ?: return "The arena has no Player Spawn."

        val players = s.participants.mapNotNull { Bukkit.getPlayer(it) }.filter { games.inEventWorld(it) }
        if (players.size < settings.minPlayers) {
            return "Only ${players.size} registered player(s) are in the event world; Spleef needs ${settings.minPlayers}."
        }
        games.floors.capture(arena.id, floor)?.let { return it }
        if (!round.begin(players.map { it.uniqueId })) return "A round is already in progress."

        players.forEach { names[it.uniqueId] = it.name; teleportSafely(it, spawn) }
        val shovel = games.gear.shovel(settings)
        val equipped = HashSet<UUID>()
        tell(Component.text("Spleef round ${round.round} starts in ${settings.countdownSeconds}s with ${players.size} players!", NamedTextColor.GOLD))
        startCountdown(
            settings.countdownSeconds,
            onTick = {
                for (uuid in round.active) {
                    if (uuid in equipped) continue
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (equip(p, GameMode.ADVENTURE, listOf(shovel.clone()))) equipped += uuid
                }
            },
            onGo = { onGo(equipped) },
        )
        games.changed()
        return null
    }

    private fun onGo(equipped: Set<UUID>) {
        for (uuid in round.active) {
            val p = Bukkit.getPlayer(uuid) ?: continue
            if (uuid !in equipped) {
                // Never readied (EventManager never tracked them in the event world): sit this round out.
                eliminate(uuid, "wasn't ready")
                continue
            }
            // Survival only so the shovel can break; HostedGameListener limits breaking to the floor.
            p.gameMode = GameMode.SURVIVAL
        }
        val limit = settings.roundTimeLimitSeconds
        if (limit > 0) {
            val roundNumber = round.round
            roundTimer = session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (isRunning && round.round == roundNumber && round.state == RoundState.ACTIVE) endRound(null, timeUp = true)
            }, limit * 20L))
        }
        games.changed()
        queueEvaluate()
    }

    override fun onActiveMove(player: Player, to: Location) {
        val lose = arena?.height(ArenaField.LOSE_HEIGHT) ?: return
        if (to.blockY < lose) eliminate(player.uniqueId, "fell")
    }

    override fun knockOut(uuid: UUID, reason: String) = eliminate(uuid, reason)

    /** Knocks [uuid] out of the round once; the round result is decided at the end of the tick. */
    private fun eliminate(uuid: UUID, reason: String) {
        if (!round.eliminate(uuid)) return
        val player = Bukkit.getPlayer(uuid)
        if (player != null && games.inventorySaved(player)) {
            sendToWaiting(player)
            comms.send(player, Component.text("You're out ($reason).", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        tell(
            Component.text("${nameOf(uuid)} is out", NamedTextColor.YELLOW)
                .append(Component.text(" ($reason, ${round.active.size} left)", NamedTextColor.GRAY))
        )
        games.changed()
        queueEvaluate()
    }

    override fun evaluate() {
        if (round.state != RoundState.ACTIVE && round.state != RoundState.COUNTDOWN) return
        when (round.active.size) {
            0 -> endRound(null)
            1 -> if (round.state == RoundState.ACTIVE) endRound(round.active.first())
            else -> {}
        }
    }

    private fun endRound(winner: UUID?, timeUp: Boolean = false) {
        val survivors = round.active
        if (!round.end(winner)) return
        roundTimer?.cancel(); roundTimer = null
        for (uuid in survivors) Bukkit.getPlayer(uuid)?.let { sendToWaiting(it) }

        val number = round.round
        val message = when {
            winner != null -> Component.text("${nameOf(winner)} won Spleef round $number!", NamedTextColor.GOLD)
            timeUp -> Component.text("Spleef round $number hit the time limit with ${survivors.size} player(s) standing. No winner.", NamedTextColor.YELLOW)
            else -> Component.text("Spleef round $number ended in a draw: the last players fell together.", NamedTextColor.YELLOW)
        }
        comms.broadcast(message, CommunicationsManager.Category.EVENT)
        winnerTitle(audience(), if (winner != null) "${nameOf(winner)} wins!" else "No winner", "Spleef round $number")

        val finalRound = settings.maxRounds in 1..number
        if (finalRound) {
            complete()
        } else {
            round.ready()
            tellStaff(Component.text("Round $number is over. Start the next with /event spleef start (the floor is restored first).", NamedTextColor.GRAY))
        }
        if (settings.restoreAfterRound || finalRound) scheduleRestore(settings.restoreDelaySeconds)
        games.changed()
    }

    private fun complete() {
        val champion = round.leader()
        round.complete(champion)
        // Runner-up only when it's unambiguous: a single round, where the last player to fall was second.
        val second = if (champion != null && round.round == 1) listOfNotNull(round.eliminated.lastOrNull()) else emptyList()
        reportResult(
            when {
                champion != null -> EventOutcome.WINNER
                round.wins.isEmpty() -> EventOutcome.NO_WINNER
                else -> EventOutcome.DRAW
            },
            listOfNotNull(champion),
            if (champion != null) "${nameOf(champion)} won with ${round.wins[champion]} round win(s)" else "No single champion after ${round.round} round(s)",
            second,
        )
        val message = if (champion != null) {
            Component.text("${nameOf(champion)} is the Spleef champion with ${round.wins[champion]} round win(s)!", NamedTextColor.GOLD)
        } else {
            Component.text("Spleef is over with no single champion.", NamedTextColor.YELLOW)
        }
        comms.broadcast(message, CommunicationsManager.Category.EVENT)
        finishEventSoon()
    }

    // ── Floor ─────────────────────────────────────────────

    /** May a participant break [block] right now? Only an ACTIVE competitor, only inside the floor region. */
    fun canBreak(player: Player, block: Block): Boolean {
        if (round.state != RoundState.ACTIVE || !round.isAlive(player.uniqueId)) return false
        val floor = arena?.region(ArenaField.FLOOR) ?: return false
        return block.world.name == floor.world &&
            block.x in floor.minX..floor.maxX && block.y in floor.minY..floor.maxY && block.z in floor.minZ..floor.maxZ
    }

    /** `/event spleef reset [arena]` / Reset Floor. Refused while a round is in progress. */
    fun resetFloor(target: EventArena?, requester: org.bukkit.command.CommandSender): EventResult.Failure? {
        val arena = target ?: this.arena ?: games.arenas.selectedFor(games.sessions.active)
            ?: return EventResult.Failure("No Spleef arena is selected. Use /event spleef reset <arena>.")
        if (arena.type != EventType.SPLEEF) return EventResult.Failure("'${arena.displayName}' isn't a Spleef arena.")
        if (this.arena?.id == arena.id && isRunning && round.state.inProgress) {
            return EventResult.Failure("Round ${round.round} is in progress. The floor can only be reset between rounds.")
        }
        val name = requester.name
        games.floors.restore(arena.id, { safeSpotFor(arena) }) { changed ->
            games.changed()
            val done = Component.text("The floor of '${arena.displayName}' is restored ($changed block(s) changed).", NamedTextColor.GREEN)
            val online = Bukkit.getPlayerExact(name)
            if (online != null) comms.send(online, done, CommunicationsManager.Category.EVENT) else plugin.logger.info("[Event] Floor of '${arena.id}' restored ($changed blocks).")
        }?.let { return EventResult.Failure(it) }
        games.changed()
        return null
    }

    private fun scheduleRestore(delaySeconds: Int) {
        val arena = arena ?: return
        // Shutting down: the snapshot stays saved and the floor is restored on the next startup.
        if (!plugin.isEnabled) return
        // Not a session task: a restore must still happen if the event is stopped first.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (this.arena?.id == arena.id && isRunning && round.state.inProgress) return@Runnable
            if (!games.floors.isDamaged(arena.id) || games.floors.isRestoring(arena.id)) return@Runnable
            games.floors.restore(arena.id, { safeSpotFor(arena) }) { games.changed() }
            games.changed()
        }, delaySeconds * 20L + 1L)
    }

    override fun afterStop() {
        roundTimer = null
        // An event stopped mid-round leaves a broken floor: restore it right away.
        scheduleRestore(0)
    }

    private fun safeSpotFor(arena: EventArena): Location? {
        val w = Bukkit.getWorld(arena.world) ?: return null
        return arena.point(ArenaField.LOBBY)?.let { toLocation(it, w) } ?: w.spawnLocation
    }

    // ── Leaving ───────────────────────────────────────────

    override fun withdraw(uuid: UUID, reason: LeaveReason) {
        if (round.state.inProgress && round.isAlive(uuid)) eliminate(uuid, reason.label)
    }

    override fun clearRun() {
        roundTimer = null
    }

    // ── Status ────────────────────────────────────────────

    fun remaining(): List<UUID> = if (round.state.inProgress) round.active else session?.participants?.toList() ?: emptyList()

    fun eliminatedThisRound(): List<UUID> = round.eliminated

    override fun statusLines(): List<Component> {
        val s = session ?: return listOf(Component.text("Spleef isn't running.", NamedTextColor.GRAY))
        val a = arena
        val lines = mutableListOf(
            line("Round", if (round.round == 0) "not started" else "${round.round}${if (settings.maxRounds > 0) " of ${settings.maxRounds}" else ""}"),
            line("Round state", round.state.label, if (round.state.inProgress) NamedTextColor.GREEN else NamedTextColor.YELLOW),
            line("Players", s.participantCount.toString()),
        )
        if (round.state.inProgress) {
            lines += line("Still standing", round.active.size.toString())
            lines += line("Out this round", round.eliminated.size.toString())
        }
        round.roundWinner?.let { lines += line("Last round winner", nameOf(it), NamedTextColor.GOLD) }
        if (round.wins.isNotEmpty()) {
            lines += line("Round wins", round.wins.entries.sortedByDescending { it.value }.take(5).joinToString(", ") { "${nameOf(it.key)} ${it.value}" })
        }
        round.overallWinner?.let { lines += line("Champion", nameOf(it), NamedTextColor.GOLD) }
        if (a != null) lines += line("Floor", floorState(a))
        return lines
    }

    fun floorState(arena: EventArena): String = when {
        games.floors.isRestoring(arena.id) -> games.floors.progress(arena.id)?.let { (done, total) -> "restoring (${done * 100 / total.coerceAtLeast(1)}%)" } ?: "restoring"
        games.floors.isDamaged(arena.id) -> "needs reset"
        else -> "intact"
    }

    fun currentArena(): EventArena? = arena
}
