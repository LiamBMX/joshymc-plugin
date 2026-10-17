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
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Hosted Sumo (issue #1187): a single-elimination knockback tournament. `/event start`
 * brings everyone to the lobby, builds the [SumoBracket] and starts the first match; each
 * following match starts with `/event sumo next` (or after `auto-next-seconds`). Hits between
 * the two competitors deal no damage and only knock back; falling below the elimination
 * height or leaving the optional Arena Boundary loses the match.
 */
class SumoGame(games: HostedGames) : MinigameGame(games, EventType.SUMO) {

    private var settings = SumoSettings.read(null)
    var bracket: SumoBracket? = null
        private set

    /** Last knockback each competitor took, for the hit cooldown. */
    private val lastHit = HashMap<UUID, Long>()

    override fun startProblem(session: EventSession, arena: EventArena): String? {
        val s = SumoSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        if (arena.point(ArenaField.SPAWN_1) == null) return "Arena '${arena.displayName}' has no Competitor Spawn 1. Set it with /event sumo setspawn1."
        if (arena.point(ArenaField.SPAWN_2) == null) return "Arena '${arena.displayName}' has no Competitor Spawn 2. Set it with /event sumo setspawn2."
        if (arena.height(ArenaField.LOSE_HEIGHT) == null) return "Arena '${arena.displayName}' has no Elimination Height. Set it with /event sumo setlose."
        if (session.participantCount < s.minPlayers) return "Sumo needs at least ${s.minPlayers} registered players (${session.participantCount} registered)."
        return null
    }

    override fun begin(session: EventSession) {
        settings = SumoSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        val waiting = waitingLocation() ?: error("The event world isn't loaded")
        val entrants = ArrayList<UUID>()
        for (player in gatherPlayers(session)) {
            if (bringIn(player, waiting)) entrants += player.uniqueId
            else comms.send(player, Component.text("You couldn't be teleported to Sumo.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        if (entrants.size < settings.minPlayers) error("only ${entrants.size} registered player(s) could be brought to the arena; Sumo needs ${settings.minPlayers}")
        bracket = SumoBracket(entrants)
        tell(Component.text("Sumo tournament: ${entrants.size} players, single elimination.", NamedTextColor.GOLD))
        when (val result = startNextMatch()) {
            is EventResult.Failure -> error(result.message)
            else -> {}
        }
    }

    // ── Matches ───────────────────────────────────────────

    /** `/event sumo next` / Start Next Match. Only one match at a time. */
    fun startNextMatch(): EventResult {
        val s = session ?: return EventResult.Failure("Sumo isn't running. Start the event with /event start.")
        val b = bracket ?: return EventResult.Failure("Sumo isn't running. Start the event with /event start.")
        when (round.state) {
            RoundState.COUNTDOWN, RoundState.ACTIVE -> {
                val m = b.current
                return EventResult.Failure("A match is already in progress${m?.let { ": ${nameOf(it.first)} vs ${nameOf(it.second)}" } ?: ""}.")
            }
            RoundState.ENDING -> return EventResult.Failure("The last match is still ending. Try again in a moment.")
            RoundState.COMPLETE -> return EventResult.Failure("The tournament is over. The event ends shortly.")
            RoundState.WAITING -> {}
        }
        settings = SumoSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        val arena = arena ?: return EventResult.Failure("No arena is selected.")
        val spawn1 = arena.point(ArenaField.SPAWN_1)?.let { toLocation(it) } ?: return EventResult.Failure("The arena has no Competitor Spawn 1.")
        val spawn2 = arena.point(ArenaField.SPAWN_2)?.let { toLocation(it) } ?: return EventResult.Failure("The arena has no Competitor Spawn 2.")

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
                    val bPlayer = present(match.second)
                    if (a != null && bPlayer != null) {
                        playMatch(match, a, bPlayer, spawn1, spawn2)
                        return EventResult.Success(s, "Match ${round.round}: ${a.name} vs ${bPlayer.name}.")
                    }
                    // One or both are gone: settle it without a fight and pick again.
                    when {
                        a != null -> forfeitResult(match.first, match.second)
                        bPlayer != null -> forfeitResult(match.second, match.first)
                        else -> { b.bothOut(); tell(Component.text("${nameOf(match.first)} and ${nameOf(match.second)} are both gone; neither advances.", NamedTextColor.GRAY)) }
                    }
                }
            }
        }
        return EventResult.Failure("Couldn't find a match to play. Check /event sumo status.")
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
        lastHit.clear()
        teleportSafely(a, spawn1)
        teleportSafely(b, spawn2)
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
                    if (equip(p, GameMode.ADVENTURE, games.gear.loadout(settings))) equipped += uuid
                }
            },
            onGo = {
                round.active.filter { it !in equipped }.forEach { lose(it, "wasn't ready") }
                games.changed()
                queueEvaluate()
            },
        )
        games.changed()
    }

    override fun onActiveMove(player: Player, to: Location) {
        val arena = arena ?: return
        val lose = arena.height(ArenaField.LOSE_HEIGHT) ?: return
        if (to.blockY < lose) { lose(player.uniqueId, "fell off"); return }
        val boundary = arena.region(ArenaField.REGION) ?: return
        if (to.blockX !in boundary.minX..boundary.maxX || to.blockZ !in boundary.minZ..boundary.maxZ) lose(player.uniqueId, "left the arena")
    }

    /**
     * A hit between the two competitors of the ACTIVE match: knockback only. The damage event
     * itself is cancelled by the caller, so nobody takes damage, gets combat-tagged or dies.
     */
    fun knockback(attacker: Player, victim: Player) {
        if (round.state != RoundState.ACTIVE || !round.isAlive(attacker.uniqueId) || !round.isAlive(victim.uniqueId)) return
        val now = System.currentTimeMillis()
        val last = lastHit[victim.uniqueId]
        if (last != null && now - last < settings.hitCooldownMs) return
        lastHit[victim.uniqueId] = now
        val dir = victim.location.toVector().subtract(attacker.location.toVector()).setY(0)
        if (dir.lengthSquared() < 1.0E-4) dir.copy(attacker.location.direction.setY(0))
        if (dir.lengthSquared() < 1.0E-4) return
        val sprint = if (attacker.isSprinting) 1.5 else 1.0
        val push = dir.normalize().multiply(settings.knockbackHorizontal * sprint)
        victim.velocity = Vector(push.x, settings.knockbackVertical, push.z)
        victim.playHurtAnimation(0f)
        victim.world.playSound(victim.location, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.8f, 1.0f)
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
        val players = listOf(match.first, match.second)
        val result: String
        when {
            alive.size == 1 -> {
                val winner = alive.first()
                round.end(winner)
                b.win(winner)
                result = "${nameOf(winner)} beat ${nameOf(players.first { it != winner })}!"
            }
            else -> {
                // Both out in the same tick. Anyone who left forfeits; if both are still here it's a rematch.
                val stillHere = players.filter { present(it) != null }
                round.end(stillHere.singleOrNull())
                result = when (stillHere.size) {
                    2 -> { b.replay(); "${nameOf(match.first)} and ${nameOf(match.second)} fell together. Rematch next!" }
                    1 -> { b.win(stillHere.first()); "${nameOf(stillHere.first())} advances; their opponent left." }
                    else -> { b.bothOut(); "Both players left; neither advances." }
                }
            }
        }
        for (uuid in players) present(uuid)?.let { sendToWaiting(it) }
        comms.broadcast(Component.text("Sumo: $result", NamedTextColor.GOLD), CommunicationsManager.Category.EVENT)
        round.roundWinner?.let { w -> Bukkit.getPlayer(w)?.let { winnerTitle(listOf(it), "You win!", "Match ${round.round}") } }

        val champion = b.champion
        if (champion != null || b.isEmpty) {
            crown(champion)
        } else {
            round.ready()
            announceNext()
        }
        games.changed()
    }

    /** Tells staff who's up next, or starts it after `auto-next-seconds`. */
    private fun announceNext() {
        val b = bracket ?: return
        val upNext = b.queue.take(2)
        val preview = if (upNext.size == 2) "${nameOf(upNext[0])} vs ${nameOf(upNext[1])}" else "the next bracket round"
        val auto = settings.autoNextSeconds
        if (auto > 0) {
            tell(Component.text("Next match ($preview) starts in ${auto}s.", NamedTextColor.GRAY))
            val matchNumber = round.round
            session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (isRunning && round.state == RoundState.WAITING && round.round == matchNumber) {
                    val r = startNextMatch()
                    if (r is EventResult.Failure) tellStaff(Component.text("Couldn't start the next match: ${r.message}", NamedTextColor.RED))
                }
            }, auto * 20L))
        } else {
            tellStaff(Component.text("Up next: $preview. Start it with /event sumo next.", NamedTextColor.GRAY))
        }
    }

    private fun crown(champion: UUID?) {
        round.complete(champion)
        val finalLoser = bracket?.completed?.lastOrNull()?.takeIf { champion != null && it.winner == champion }?.loser
        reportResult(
            if (champion != null) EventOutcome.WINNER else EventOutcome.NO_WINNER,
            listOfNotNull(champion),
            if (champion != null) "${nameOf(champion)} won the Sumo tournament" else "The tournament ended with no champion",
            listOfNotNull(finalLoser),
        )
        val message = if (champion != null) Component.text("${nameOf(champion)} is the Sumo champion!", NamedTextColor.GOLD)
        else Component.text("The Sumo tournament ended with no champion.", NamedTextColor.YELLOW)
        comms.broadcast(message, CommunicationsManager.Category.EVENT)
        if (champion != null) winnerTitle(audience(), "${nameOf(champion)} wins!", "Sumo champion")
        games.changed()
        finishEventSoon()
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
            // Their leaving may have decided the bracket.
            if (round.state == RoundState.WAITING) {
                val champion = b.champion
                if (champion != null || b.isEmpty) crown(champion)
            }
            games.changed()
        }
    }

    override fun clearRun() {
        bracket = null
        lastHit.clear()
    }

    // ── Status ────────────────────────────────────────────

    fun currentMatch(): Pair<UUID, UUID>? = bracket?.current?.takeIf { round.state.inProgress || round.state == RoundState.ENDING }?.let { it.first to it.second }

    override fun statusLines(): List<Component> {
        val b = bracket ?: return listOf(Component.text("Sumo isn't running.", NamedTextColor.GRAY))
        val lines = mutableListOf(
            line("Bracket round", b.bracketRound.toString()),
            line("Matches played", b.completed.size.toString()),
            line("Match state", round.state.label, if (round.state.inProgress) NamedTextColor.GREEN else NamedTextColor.YELLOW),
        )
        currentMatch()?.let { (a, c) -> lines += line("Current match", "${nameOf(a)} vs ${nameOf(c)}") }
        lines += line("Remaining", b.remaining.size.toString())
        lines += line("Queue", b.queue.joinToString(", ") { nameOf(it) }.ifEmpty { "empty" })
        if (b.through.isNotEmpty()) lines += line("Through", b.through.joinToString(", ") { nameOf(it) })
        round.overallWinner?.let { lines += line("Champion", nameOf(it), NamedTextColor.GOLD) }
        return lines
    }
}
