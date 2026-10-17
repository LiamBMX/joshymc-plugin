package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.ArenaRegion
import com.liam.joshymc.communityevent.arena.EventArena
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Hosted Crystal PvP (issue #1189): a single-elimination 1v1 tournament with the saved kit.
 * `/event start` builds the bracket (the same [SumoBracket] Sumo uses) and starts the first
 * match; staff start each following match with `/event cpvp next`.
 *
 * Before every match the Arena Boundary's blocks are snapshotted to `event_floor_snapshots`
 * (the store Spleef uses), so a crash mid-match still restores the arena at startup. During an
 * ACTIVE match only the two competitors may hurt each other, place End Crystals and the
 * allowed blocks, or break those blocks, and only inside the boundary; explosions there never
 * reach outside it and drop nothing. Lethal damage ends the match instead of killing (a held
 * Totem of Undying still works), so no death, drop or kill reward happens in the event world.
 * After each match the arena is restored before the next can start.
 */
class CrystalPvpGame(games: HostedGames) : MinigameGame(games, EventType.CPVP) {

    private var settings = CrystalPvpSettings.read(null)
    var bracket: SumoBracket? = null
        private set

    private var matchTimer: BukkitTask? = null

    private fun readSettings() = CrystalPvpSettings.read(plugin.config.getConfigurationSection("hosted-events"))

    override fun startProblem(session: EventSession, arena: EventArena): String? {
        val s = readSettings()
        if (arena.point(ArenaField.SPAWN_1) == null) return "Arena '${arena.displayName}' has no Competitor Spawn 1. Set it with /event cpvp setspawn1."
        if (arena.point(ArenaField.SPAWN_2) == null) return "Arena '${arena.displayName}' has no Competitor Spawn 2. Set it with /event cpvp setspawn2."
        val region = arena.region(ArenaField.REGION) ?: return "Arena '${arena.displayName}' has no Arena Boundary. Set it with /event cpvp setregion."
        if (region.volume > s.maxArenaBlocks) {
            return "The Arena Boundary is ${region.volume} blocks; Crystal PvP restores at most ${s.maxArenaBlocks} (hosted-events.cpvp.restore.max-blocks)."
        }
        arenaStateProblem(arena)?.let { return it }
        if (!games.kit.available) return "Crystal PvP kit storage failed to load at startup; check the console."
        if (games.kit.isEmpty) return "No Crystal PvP kit is saved. Build it in your event inventory and run /event cpvp setkit."
        if (session.participantCount < s.minPlayers) return "Crystal PvP needs at least ${s.minPlayers} registered players (${session.participantCount} registered)."
        return null
    }

    private fun arenaStateProblem(arena: EventArena): String? = when {
        !games.floors.available -> "Arena snapshot storage failed to load at startup; check the console."
        games.floors.isRestoring(arena.id) -> "The arena '${arena.displayName}' is still being restored. Try again in a moment."
        games.floors.isDamaged(arena.id) -> "The arena '${arena.displayName}' hasn't been restored since the last match yet. It restores itself a few seconds after each match; otherwise run /event cpvp reset."
        else -> null
    }

    override fun begin(session: EventSession) {
        settings = readSettings()
        val waiting = waitingLocation() ?: error("The event world isn't loaded")
        val entrants = ArrayList<UUID>()
        for (player in gatherPlayers(session)) {
            if (games.inEventWorld(player) || bringIn(player, waiting)) entrants += player.uniqueId
            else comms.send(player, Component.text("You couldn't be teleported to Crystal PvP.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        if (entrants.size < settings.minPlayers) error("only ${entrants.size} registered player(s) could be brought to the arena; Crystal PvP needs ${settings.minPlayers}")
        bracket = SumoBracket(entrants)
        tell(Component.text("Crystal PvP tournament: ${entrants.size} players, single elimination.", NamedTextColor.GOLD))
        when (val result = startNextMatch()) {
            is EventResult.Failure -> error(result.message)
            else -> {}
        }
    }

    // ── Matches ───────────────────────────────────────────

    /** `/event cpvp next` / Start Next Match. Only one match at a time, and only on a restored arena. */
    fun startNextMatch(): EventResult {
        val s = session ?: return EventResult.Failure("Crystal PvP isn't running. Start the event with /event start.")
        val b = bracket ?: return EventResult.Failure("Crystal PvP isn't running. Start the event with /event start.")
        when (round.state) {
            RoundState.COUNTDOWN, RoundState.ACTIVE -> {
                val m = b.current
                return EventResult.Failure("A match is already in progress${m?.let { ": ${nameOf(it.first)} vs ${nameOf(it.second)}" } ?: ""}.")
            }
            RoundState.ENDING -> return EventResult.Failure("The last match is still ending. Try again in a moment.")
            RoundState.COMPLETE -> return EventResult.Failure("The tournament is over. The event ends shortly.")
            RoundState.WAITING -> {}
        }
        settings = readSettings()
        val arena = arena ?: return EventResult.Failure("No arena is selected.")
        val region = arena.region(ArenaField.REGION) ?: return EventResult.Failure("The arena has no Arena Boundary.")
        val spawn1 = arena.point(ArenaField.SPAWN_1)?.let { toLocation(it) } ?: return EventResult.Failure("The arena has no Competitor Spawn 1.")
        val spawn2 = arena.point(ArenaField.SPAWN_2)?.let { toLocation(it) } ?: return EventResult.Failure("The arena has no Competitor Spawn 2.")
        arenaStateProblem(arena)?.let { return EventResult.Failure(it) }
        if (games.kit.isEmpty) return EventResult.Failure("No Crystal PvP kit is saved. Run /event cpvp setkit.")

        // Skip anyone who is no longer here; they forfeit. Bounded: each pass removes a player.
        repeat(64) {
            when (val next = b.next()) {
                is SumoBracket.Next.Busy -> return EventResult.Failure("A match is already in progress.")
                is SumoBracket.Next.Decided -> {
                    announceByes(next.byes)
                    crown(next.champion)
                    return EventResult.Success(s, "The tournament is decided.")
                }
                is SumoBracket.Next.Play -> {
                    announceByes(next.byes)
                    val match = next.match
                    val a = present(match.first)
                    val c = present(match.second)
                    if (a != null && c != null) {
                        // Snapshot first: nothing may change in the arena before it's saved.
                        games.floors.capture(arena.id, region)?.let {
                            b.replay()
                            return EventResult.Failure(it)
                        }
                        playMatch(match, a, c, spawn1, spawn2)
                        return EventResult.Success(s, "Match ${round.round}: ${a.name} vs ${c.name}.")
                    }
                    when {
                        a != null -> forfeitResult(match.first, match.second)
                        c != null -> forfeitResult(match.second, match.first)
                        else -> { b.bothOut(); tell(Component.text("${nameOf(match.first)} and ${nameOf(match.second)} are both gone; neither advances.", NamedTextColor.GRAY)) }
                    }
                }
            }
        }
        return EventResult.Failure("Couldn't find a match to play. Check /event cpvp status.")
    }

    private fun present(uuid: UUID): Player? {
        val s = session ?: return null
        if (!s.isRegistered(uuid)) return null
        return Bukkit.getPlayer(uuid)?.takeIf { games.inEventWorld(it) }
    }

    private fun forfeitResult(winner: UUID, loser: UUID) {
        bracket?.win(winner)
        tell(Component.text("${nameOf(winner)} advances: ${nameOf(loser)} is no longer here.", NamedTextColor.GRAY))
    }

    private fun announceByes(byes: List<UUID>) {
        byes.forEach { tell(Component.text("${nameOf(it)} gets a bye to the next bracket round.", NamedTextColor.GRAY)) }
    }

    private fun playMatch(match: SumoBracket.Match, a: Player, b: Player, spawn1: Location, spawn2: Location) {
        round.begin(listOf(a.uniqueId, b.uniqueId))
        names[a.uniqueId] = a.name; names[b.uniqueId] = b.name
        teleportSafely(a, spawn1)
        teleportSafely(b, spawn2)
        val kit = games.kit.items()
        val equipped = HashSet<UUID>()
        tell(
            Component.text("Match ${round.round} (bracket round ${match.bracketRound}): ", NamedTextColor.GOLD)
                .append(Component.text("${a.name} vs ${b.name}", NamedTextColor.YELLOW))
        )
        startCountdown(
            settings.countdownSeconds,
            onTick = {
                for (uuid in round.active) {
                    if (uuid in equipped) continue
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (applyKit(p, kit, GameMode.ADVENTURE)) equipped += uuid
                }
            },
            onGo = { onGo(equipped) },
        )
        games.changed()
    }

    private fun onGo(equipped: Set<UUID>) {
        round.active.filter { it !in equipped }.forEach { lose(it, "wasn't ready") }
        // Survival only so the kit's blocks and crystals can be placed; the listeners limit where.
        round.active.mapNotNull { Bukkit.getPlayer(it) }.forEach { it.gameMode = GameMode.SURVIVAL }
        val limit = settings.matchTimeLimitSeconds
        if (limit > 0) {
            val number = round.round
            matchTimer = session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (isRunning && round.round == number && round.state == RoundState.ACTIVE) timeUp()
            }, limit * 20L))
        }
        games.changed()
        queueEvaluate()
    }

    /** Time limit: the competitor with more health wins; equal health is a draw. */
    private fun timeUp() {
        val alive = round.active.mapNotNull { Bukkit.getPlayer(it) }
        tell(Component.text("Time's up!", NamedTextColor.YELLOW))
        if (alive.size != 2) return
        val (x, y) = alive
        when {
            x.health > y.health -> lose(y.uniqueId, "had less health at the time limit")
            y.health > x.health -> lose(x.uniqueId, "had less health at the time limit")
            else -> { lose(x.uniqueId, "time limit, equal health"); lose(y.uniqueId, "time limit, equal health") }
        }
    }

    // ── Rules used by the listeners ───────────────────────

    /** [uuid] is one of the two competitors and the match is ACTIVE. */
    fun isFighting(uuid: UUID): Boolean = isRunning && round.state == RoundState.ACTIVE && round.isAlive(uuid)

    /** Two different competitors of the ACTIVE match. */
    fun isOpponent(a: UUID, b: UUID): Boolean = a != b && isFighting(a) && isFighting(b)

    fun boundary(): ArenaRegion? = arena?.region(ArenaField.REGION)

    fun inArena(block: Block): Boolean {
        val r = boundary() ?: return false
        return block.world.name == r.world && block.x in r.minX..r.maxX && block.y in r.minY..r.maxY && block.z in r.minZ..r.maxZ
    }

    /** Inside the boundary, grown by [margin] blocks on every side. */
    fun nearArena(loc: Location, margin: Int): Boolean {
        val r = boundary() ?: return false
        return loc.world?.name == r.world &&
            loc.blockX in (r.minX - margin)..(r.maxX + margin) &&
            loc.blockY in (r.minY - margin)..(r.maxY + margin) &&
            loc.blockZ in (r.minZ - margin)..(r.maxZ + margin)
    }

    /** A competitor placing or breaking [block] (its type is the placed type on a place): an allowed block inside the boundary. */
    fun canBuild(player: Player, block: Block): Boolean =
        isFighting(player.uniqueId) && block.type in settings.allowedBlocks && inArena(block)

    /** An End Crystal placed on [base] by a competitor: the base and the crystal's space are inside the boundary. */
    fun canPlaceCrystal(player: Player, base: Block): Boolean =
        isFighting(player.uniqueId) && inArena(base) && inArena(base.getRelative(0, 1, 0))

    override fun onActiveMove(player: Player, to: Location) {
        val r = boundary() ?: return
        if (to.blockX !in r.minX..r.maxX || to.blockZ !in r.minZ..r.maxZ || to.blockY < r.minY) lose(player.uniqueId, "left the arena")
    }

    /** Damage that would have killed [player]: they're out instead of dying. */
    fun lethal(player: Player) {
        if (!isFighting(player.uniqueId)) return
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_DEATH, 0.8f, 1.0f)
        lose(player.uniqueId, "was eliminated")
    }

    override fun knockOut(uuid: UUID, reason: String) = lose(uuid, reason)

    /** [uuid] loses the current match (once). The result is settled at the end of the tick. */
    private fun lose(uuid: UUID, reason: String) {
        if (!round.eliminate(uuid)) return
        Bukkit.getPlayer(uuid)?.let { comms.send(it, Component.text("You lost the match ($reason).", NamedTextColor.RED), CommunicationsManager.Category.EVENT) }
        queueEvaluate()
    }

    override fun evaluate() {
        if (!round.state.inProgress) return
        val b = bracket ?: return
        val match = b.current ?: return
        val alive = round.active
        if (alive.size == 2) return
        matchTimer?.cancel(); matchTimer = null
        val players = listOf(match.first, match.second)
        val result: String
        if (alive.size == 1) {
            val winner = alive.first()
            round.end(winner)
            b.win(winner)
            result = "${nameOf(winner)} beat ${nameOf(players.first { it != winner })}!"
        } else {
            // Both out in the same tick. Anyone who left forfeits; if both are still here it's a draw.
            val stillHere = players.filter { present(it) != null }
            round.end(stillHere.singleOrNull())
            result = when (stillHere.size) {
                2 -> if (settings.rematchOnDraw) {
                    b.replay(); "${nameOf(match.first)} and ${nameOf(match.second)} went down together. Rematch next!"
                } else {
                    b.bothOut(); "${nameOf(match.first)} and ${nameOf(match.second)} went down together; neither advances."
                }
                1 -> { b.win(stillHere.first()); "${nameOf(stillHere.first())} advances; their opponent left." }
                else -> { b.bothOut(); "Both players left; neither advances." }
            }
        }
        for (uuid in players) present(uuid)?.let { recover(it); sendToWaiting(it) }
        comms.broadcast(Component.text("Crystal PvP: $result", NamedTextColor.GOLD), CommunicationsManager.Category.EVENT)
        round.roundWinner?.let { w -> Bukkit.getPlayer(w)?.let { winnerTitle(listOf(it), "You win!", "Match ${round.round}") } }
        scheduleRestore(settings.restoreDelaySeconds)

        val champion = b.champion
        if (champion != null || b.isEmpty) {
            crown(champion)
        } else {
            round.ready()
            val upNext = b.queue.take(2)
            val preview = if (upNext.size == 2) "${nameOf(upNext[0])} vs ${nameOf(upNext[1])}" else "the next bracket round"
            tellStaff(Component.text("Up next: $preview. Start it with /event cpvp next once the arena is restored.", NamedTextColor.GRAY))
        }
        games.changed()
    }

    /** Full health and no leftover kit effects before leaving the arena. */
    private fun recover(player: Player) {
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.fireTicks = 0
        player.fallDistance = 0f
        player.getAttribute(Attribute.MAX_HEALTH)?.value?.let { player.health = it }
        player.foodLevel = 20
        player.saturation = 20f
    }

    private fun crown(champion: UUID?) {
        round.complete(champion)
        val message = if (champion != null) Component.text("${nameOf(champion)} is the Crystal PvP champion!", NamedTextColor.GOLD)
        else Component.text("The Crystal PvP tournament ended with no champion.", NamedTextColor.YELLOW)
        comms.broadcast(message, CommunicationsManager.Category.EVENT)
        if (champion != null) winnerTitle(audience(), "${nameOf(champion)} wins!", "Crystal PvP champion")
        games.changed()
        finishEventSoon()
    }

    // ── Arena restoration ─────────────────────────────────

    /** `/event cpvp reset [arena]` / Reset Arena. Refused while a match is in progress. */
    fun resetArena(target: EventArena?, requester: CommandSender): EventResult.Failure? {
        val arena = target ?: this.arena ?: games.arenas.selectedFor(games.sessions.active)
            ?: return EventResult.Failure("No Crystal PvP arena is selected. Use /event cpvp reset <arena>.")
        if (arena.type != EventType.CPVP) return EventResult.Failure("'${arena.displayName}' isn't a Crystal PvP arena.")
        if (this.arena?.id == arena.id && isRunning && round.state.inProgress) {
            return EventResult.Failure("Match ${round.round} is in progress. The arena can only be reset between matches.")
        }
        val name = requester.name
        games.floors.restore(arena.id, { safeSpotFor(arena) }) { changed ->
            games.changed()
            val done = Component.text("The arena '${arena.displayName}' is restored ($changed block(s) changed).", NamedTextColor.GREEN)
            val online = Bukkit.getPlayerExact(name)
            if (online != null) comms.send(online, done, CommunicationsManager.Category.EVENT) else plugin.logger.info("[Event] Crystal PvP arena '${arena.id}' restored ($changed blocks).")
        }?.let { return EventResult.Failure(it) }
        games.changed()
        return null
    }

    private fun scheduleRestore(delaySeconds: Int) {
        val arena = arena ?: return
        // Shutting down: the snapshot stays saved and the arena is restored on the next startup.
        if (!plugin.isEnabled) return
        // Not a session task: a restore must still happen if the event is stopped first.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (this.arena?.id == arena.id && isRunning && round.state.inProgress) return@Runnable
            if (!games.floors.isDamaged(arena.id) || games.floors.isRestoring(arena.id)) return@Runnable
            games.floors.restore(arena.id, { safeSpotFor(arena) }) { changed ->
                games.changed()
                if (this.arena?.id == arena.id && isRunning && round.state == RoundState.WAITING) {
                    tellStaff(Component.text("The arena is restored ($changed block(s) changed). Ready for /event cpvp next.", NamedTextColor.GREEN))
                }
            }
            games.changed()
        }, delaySeconds * 20L + 1L)
    }

    override fun afterStop() {
        matchTimer = null
        // An event stopped mid-match leaves a damaged arena: restore it right away.
        scheduleRestore(0)
    }

    private fun safeSpotFor(arena: EventArena): Location? {
        val w = Bukkit.getWorld(arena.world) ?: return null
        return arena.point(ArenaField.LOBBY)?.let { toLocation(it, w) } ?: w.spawnLocation
    }

    fun arenaState(arena: EventArena): String = when {
        games.floors.isRestoring(arena.id) -> games.floors.progress(arena.id)?.let { (done, total) -> "restoring (${done * 100 / total.coerceAtLeast(1)}%)" } ?: "restoring"
        games.floors.isDamaged(arena.id) -> if (this.arena?.id == arena.id && round.state.inProgress) "match in progress" else "needs reset"
        else -> "intact"
    }

    fun currentArena(): EventArena? = arena

    // ── Kit ───────────────────────────────────────────────

    /**
     * `/event cpvp setkit`: saves [player]'s event-world inventory as the kit, never while
     * they're competing (see [MinigameGame.copyKit]).
     */
    fun saveKit(player: Player): KitSave {
        val busy = isRunning && round.isAlive(player.uniqueId) && round.state.inProgress
        return copyKit(player, games.kit, if (busy) "You're in a match. Save the kit between matches." else null)
    }

    // ── Leaving ───────────────────────────────────────────

    override fun withdraw(uuid: UUID, reason: LeaveReason) {
        val b = bracket ?: return
        if (round.state.inProgress && round.isAlive(uuid)) {
            lose(uuid, reason.label)
            return
        }
        if (b.withdraw(uuid)) {
            tell(Component.text("${nameOf(uuid)} ${reason.label} and is out of the tournament.", NamedTextColor.GRAY))
            if (round.state == RoundState.WAITING) {
                val champion = b.champion
                if (champion != null || b.isEmpty) crown(champion)
            }
            games.changed()
        }
    }

    override fun clearRun() {
        bracket = null
        matchTimer = null
    }

    // ── Status ────────────────────────────────────────────

    fun currentMatch(): Pair<UUID, UUID>? = bracket?.current?.takeIf { round.state.inProgress || round.state == RoundState.ENDING }?.let { it.first to it.second }

    override fun statusLines(): List<Component> {
        val b = bracket ?: return listOf(Component.text("Crystal PvP isn't running.", NamedTextColor.GRAY))
        val lines = mutableListOf(
            line("Bracket round", b.bracketRound.toString()),
            line("Matches played", b.completed.size.toString()),
            line("Match state", round.state.label, if (round.state.inProgress) NamedTextColor.GREEN else NamedTextColor.YELLOW),
        )
        currentMatch()?.let { (a, c) -> lines += line("Current match", "${nameOf(a)} vs ${nameOf(c)}") }
        lines += line("Remaining", b.remaining.size.toString())
        lines += line("Queue", b.queue.joinToString(", ") { nameOf(it) }.ifEmpty { "empty" })
        if (b.through.isNotEmpty()) lines += line("Through", b.through.joinToString(", ") { nameOf(it) })
        arena?.let { lines += line("Arena", arenaState(it)) }
        round.overallWinner?.let { lines += line("Champion", nameOf(it), NamedTextColor.GOLD) }
        return lines
    }
}
