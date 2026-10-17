package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.result.EventOutcome
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.ArenaPoint
import com.liam.joshymc.communityevent.arena.ArenaRegion
import com.liam.joshymc.communityevent.arena.EventArena
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Hosted Sharks and Minnows (issue #1189). `/event start` assigns the starting Sharks at
 * random, puts everyone on their spawn and starts crossing 1. Each crossing runs from the
 * Minnow Spawn to the Finish Zone; a Shark's hit catches a crossing Minnow, who waits off the
 * arena and becomes a Shark for the next crossing. Staff start every following crossing with
 * `/event sharks next`. The last Minnow left wins; if none are left nobody does.
 *
 * Crossing direction: a Sharks arena holds one Minnow Spawn and one Finish Zone, so every
 * crossing runs the same way. Between crossings the surviving Minnows go back to the Minnow
 * Spawn and the Sharks (old and new) to the Shark Spawn; nothing is placed anywhere the
 * arena doesn't define.
 *
 * Hits never deal damage (HostedGameListener cancels every hit on a player in a game);
 * they're only read as catches.
 */
class SharksGame(games: HostedGames) : MinigameGame(games, EventType.SHARKS) {

    private var settings = SharksSettings.read(null)
    var roster = SharksRoster()
        private set

    private var crossingTimer: BukkitTask? = null
    private var transition: BukkitTask? = null
    private val lastCatch = HashMap<UUID, Long>()

    override fun startProblem(session: EventSession, arena: EventArena): String? {
        val s = SharksSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        val shark = arena.point(ArenaField.SHARK_SPAWN) ?: return "Arena '${arena.displayName}' has no Shark Spawn. Set it with /event sharks setshark."
        val minnow = arena.point(ArenaField.MINNOW_SPAWN) ?: return "Arena '${arena.displayName}' has no Minnow Spawn. Set it with /event sharks setminnow."
        val finish = arena.region(ArenaField.FINISH) ?: return "Arena '${arena.displayName}' has no Finish Zone. Set it with /event sharks setfinish."
        if (inside(finish, minnow)) return "The Minnow Spawn of '${arena.displayName}' is inside the Finish Zone; minnows would be safe at once."
        if (inside(finish, shark)) return "The Shark Spawn of '${arena.displayName}' is inside the Finish Zone; sharks can't start in the safe zone."
        if (session.participantCount < s.minPlayers) return "Sharks and Minnows needs at least ${s.minPlayers} registered players (${session.participantCount} registered)."
        return null
    }

    override fun begin(session: EventSession) {
        settings = SharksSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        val waiting = waitingLocation() ?: error("The event world isn't loaded")
        val entrants = ArrayList<UUID>()
        for (player in gatherPlayers(session)) {
            if (games.inEventWorld(player) || bringIn(player, waiting)) entrants += player.uniqueId
            else comms.send(player, Component.text("You couldn't be teleported to Sharks and Minnows.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        if (entrants.size < settings.minPlayers) {
            error("only ${entrants.size} registered player(s) could be brought to the arena; Sharks and Minnows needs ${settings.minPlayers}")
        }
        roster.assign(entrants, settings.startingSharks)
        val sharks = roster.sharks
        tell(
            Component.text("Sharks and Minnows: ${entrants.size} players. Starting shark${if (sharks.size > 1) "s" else ""}: ", NamedTextColor.GOLD)
                .append(Component.text(sharks.joinToString(", ") { nameOf(it) }, NamedTextColor.AQUA))
        )
        startCrossing()?.let { error(it) }
    }

    // ── Crossings ─────────────────────────────────────────

    /** `/event sharks next` / Start Next Crossing. Only one crossing at a time. */
    fun startNextCrossing(): EventResult {
        val s = session ?: return EventResult.Failure("Sharks and Minnows isn't running. Start the event with /event start.")
        if (!isRunning) return EventResult.Failure("Sharks and Minnows isn't running. Start the event with /event start.")
        when (round.state) {
            RoundState.COUNTDOWN, RoundState.ACTIVE -> return EventResult.Failure("Crossing ${round.round} is already in progress.")
            RoundState.ENDING -> return EventResult.Failure("Crossing ${round.round} is still ending. Try again in a moment.")
            RoundState.COMPLETE -> return EventResult.Failure("Sharks and Minnows is over. The event ends shortly.")
            RoundState.WAITING -> {}
        }
        settings = SharksSettings.read(plugin.config.getConfigurationSection("hosted-events"))
        startCrossing()?.let { return EventResult.Failure(it) }
        return EventResult.Success(s, "Crossing ${round.round} is starting.")
    }

    /** Places everyone and starts the countdown. Returns why it couldn't, or null. */
    private fun startCrossing(): String? {
        val arena = arena ?: return "No arena is selected."
        val sharkSpawn = arena.point(ArenaField.SHARK_SPAWN)?.let { toLocation(it) } ?: return "The arena has no Shark Spawn."
        val minnowSpawn = arena.point(ArenaField.MINNOW_SPAWN)?.let { toLocation(it) } ?: return "The arena has no Minnow Spawn."

        // Anyone who drifted out of the event world without the session noticing sits this out.
        roster.everyone.filter { present(it) == null }.forEach { roster.withdraw(it) }
        if (roster.crossingNumber > 0) {
            val standing = roster.standing()
            if (standing !is SharksRoster.Outcome.Continue) { finish(standing); return null }
        }
        transition?.cancel(); transition = null
        if (!roster.beginCrossing()) return "There aren't enough Sharks and Minnows left for a crossing."
        val minnows = roster.stillCrossing
        val sharks = roster.sharks
        if (!round.begin(sharks + minnows)) { roster.endCrossing(); return "A crossing is already in progress." }
        lastCatch.clear()

        sharks.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { names[uuid] = it.name; teleportSafely(it, sharkSpawn) } }
        minnows.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { names[uuid] = it.name; teleportSafely(it, minnowSpawn) } }
        val sharkShirt = games.gear.roleShirt("Shark", SHARK_COLOR, NamedTextColor.AQUA)
        val minnowShirt = games.gear.roleShirt("Minnow", MINNOW_COLOR, NamedTextColor.GOLD)
        val equipped = HashSet<UUID>()
        tell(
            Component.text("Crossing ${roster.crossingNumber}: ", NamedTextColor.GOLD)
                .append(Component.text("${minnows.size} minnow(s) vs ${sharks.size} shark(s). ", NamedTextColor.YELLOW))
                .append(Component.text("Minnows: reach the finish zone without being tagged!", NamedTextColor.GRAY))
        )
        startCountdown(
            settings.countdownSeconds,
            onTick = {
                for (uuid in round.active) {
                    if (uuid in equipped) continue
                    val p = Bukkit.getPlayer(uuid) ?: continue
                    if (!equip(p, GameMode.ADVENTURE, emptyList())) continue
                    p.inventory.setChestplate((if (roster.isShark(uuid)) sharkShirt else minnowShirt).clone())
                    equipped += uuid
                }
            },
            onGo = { onGo(equipped) },
        )
        games.changed()
        return null
    }

    private fun onGo(equipped: Set<UUID>) {
        for (uuid in round.active.filter { it !in equipped }) {
            // Never readied (EventManager never tracked them in the event world): they sit this crossing out.
            if (roster.isShark(uuid)) {
                round.eliminate(uuid)
                Bukkit.getPlayer(uuid)?.let { sendToWaiting(it) }
            } else {
                catchMinnow(uuid, null, "wasn't ready")
            }
        }
        val limit = settings.crossingTimeLimitSeconds
        if (limit > 0) {
            val number = round.round
            crossingTimer = session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (isRunning && round.round == number && round.state == RoundState.ACTIVE) timeUp()
            }, limit * 20L))
        }
        games.changed()
        queueEvaluate()
    }

    private fun timeUp() {
        val late = roster.stillCrossing
        if (late.isNotEmpty()) tell(Component.text("Time's up! ${late.size} minnow(s) didn't make it across.", NamedTextColor.YELLOW))
        late.forEach { catchMinnow(it, null, "ran out of time") }
        queueEvaluate()
    }

    /**
     * Movement during an ACTIVE crossing: a crossing Minnow entering the Finish Zone is safe
     * (once); a Shark may never enter it and is held at its edge.
     */
    fun onMove(event: PlayerMoveEvent) {
        val finish = arena?.region(ArenaField.FINISH) ?: return
        val player = event.player
        val to = event.to
        val entering = inside(finish, to) && !inside(finish, event.from)
        when {
            roster.isShark(player.uniqueId) -> if (inside(finish, to)) {
                event.setTo(if (inside(finish, event.from)) sharkSpawnOr(event.from) else event.from.clone().apply { yaw = to.yaw; pitch = to.pitch })
                if (entering) comms.sendActionBar(player, Component.text("Sharks can't enter the safe zone.", NamedTextColor.RED))
            }
            roster.stateOf(player.uniqueId) == SharksRoster.MinnowState.CROSSING -> if (inside(finish, to)) reachSafety(player)
        }
    }

    override fun onActiveMove(player: Player, to: Location) {
        // Handled in onMove, which can also hold Sharks back at the finish line.
    }

    private fun sharkSpawnOr(fallback: Location): Location =
        arena?.point(ArenaField.SHARK_SPAWN)?.let { toLocation(it) } ?: fallback

    private fun reachSafety(player: Player) {
        if (!roster.reachSafety(player.uniqueId)) return
        comms.send(player, Component.text("You made it! You're safe for this crossing.", NamedTextColor.GREEN), CommunicationsManager.Category.EVENT)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f)
        tell(
            Component.text("${player.name} reached safety", NamedTextColor.GREEN)
                .append(Component.text(" (${roster.stillCrossing.size} still crossing)", NamedTextColor.GRAY))
        )
        games.changed()
        queueEvaluate()
    }

    /**
     * A hit between two players of the crossing. Only a Shark hitting a crossing Minnow while
     * the crossing is ACTIVE counts; every other hit is ignored. The damage is already cancelled.
     */
    fun hit(attacker: Player, victim: Player) {
        if (round.state != RoundState.ACTIVE) return
        if (!roster.isShark(attacker.uniqueId) || !round.isAlive(attacker.uniqueId)) return
        if (roster.stateOf(victim.uniqueId) != SharksRoster.MinnowState.CROSSING) return
        val now = System.currentTimeMillis()
        val last = lastCatch[attacker.uniqueId]
        if (last != null && now - last < settings.catchCooldownMs) return
        if (catchMinnow(victim.uniqueId, attacker, "tagged by ${attacker.name}")) lastCatch[attacker.uniqueId] = now
    }

    /** Catches a crossing Minnow once: off the arena now, a Shark from the next crossing. */
    private fun catchMinnow(uuid: UUID, shark: Player?, reason: String): Boolean {
        val caught = if (shark != null) roster.catch(shark.uniqueId, uuid) else roster.forceCatch(uuid)
        if (!caught) return false
        round.eliminate(uuid)
        val player = Bukkit.getPlayer(uuid)
        if (player != null) {
            player.world.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH, 0.7f, 1.2f)
            sendToWaiting(player)
            comms.send(player, Component.text("You were caught ($reason). You'll be a shark next crossing.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
        }
        tell(
            Component.text("${nameOf(uuid)} was caught", NamedTextColor.YELLOW)
                .append(Component.text(" ($reason, ${roster.stillCrossing.size} still crossing)", NamedTextColor.GRAY))
        )
        games.changed()
        queueEvaluate()
        return true
    }

    override fun knockOut(uuid: UUID, reason: String) {
        if (roster.stateOf(uuid) == SharksRoster.MinnowState.CROSSING) catchMinnow(uuid, null, reason)
    }

    override fun evaluate() {
        if (round.state != RoundState.ACTIVE) return
        if (!roster.crossingDone) return
        endCrossing()
    }

    private fun endCrossing() {
        crossingTimer?.cancel(); crossingTimer = null
        val number = roster.crossingNumber
        val safe = roster.safe
        val outcome = roster.endCrossing() ?: return
        round.end(null)
        val caughtNow = roster.caught
        comms.broadcast(
            Component.text("Crossing $number is over: ", NamedTextColor.GOLD)
                .append(Component.text("${safe.size} made it, ${caughtNow.size} caught.", NamedTextColor.YELLOW)),
            CommunicationsManager.Category.EVENT,
        )
        if (outcome !is SharksRoster.Outcome.Continue) { finish(outcome); return }
        round.ready()
        if (caughtNow.isNotEmpty()) tell(Component.text("New sharks: ${caughtNow.joinToString(", ") { nameOf(it) }}.", NamedTextColor.AQUA))
        // Give everyone a moment, then line them up for the next crossing (started by staff).
        val delay = settings.transitionDelaySeconds * 20L
        transition = session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (isRunning && round.state == RoundState.WAITING && round.round == number) lineUp()
        }, delay.coerceAtLeast(1L)))
        tellStaff(Component.text("${outcome.minnows.size} minnows left. Start the next crossing with /event sharks next.", NamedTextColor.GRAY))
        games.changed()
    }

    /** Between crossings: Minnows back on the Minnow Spawn, Sharks on the Shark Spawn, no gear. */
    private fun lineUp() {
        val arena = arena ?: return
        val sharkSpawn = arena.point(ArenaField.SHARK_SPAWN)?.let { toLocation(it) }
        val minnowSpawn = arena.point(ArenaField.MINNOW_SPAWN)?.let { toLocation(it) }
        for (uuid in roster.everyone) {
            val player = present(uuid) ?: continue
            if (!games.inventorySaved(player)) continue
            games.gear.strip(player)
            if (player.gameMode == GameMode.SURVIVAL) player.gameMode = GameMode.ADVENTURE
            teleportSafely(player, if (roster.isShark(uuid)) sharkSpawn else minnowSpawn)
        }
        tell(Component.text("Get ready: the next crossing starts when staff are ready.", NamedTextColor.GRAY))
    }

    private fun finish(outcome: SharksRoster.Outcome) {
        crossingTimer?.cancel(); crossingTimer = null
        transition?.cancel(); transition = null
        if (round.state.inProgress) round.end(null)
        for (uuid in round.active) Bukkit.getPlayer(uuid)?.let { sendToWaiting(it) }
        when (outcome) {
            is SharksRoster.Outcome.Winner -> {
                round.complete(outcome.winner)
                reportResult(EventOutcome.WINNER, listOf(outcome.winner), "${nameOf(outcome.winner)} was the last minnow standing")
                comms.broadcast(Component.text("${nameOf(outcome.winner)} is the last minnow standing and wins Sharks and Minnows!", NamedTextColor.GOLD), CommunicationsManager.Category.EVENT)
                winnerTitle(audience(), "${nameOf(outcome.winner)} wins!", "Last minnow standing")
            }
            is SharksRoster.Outcome.NoWinner -> {
                round.complete(null)
                val tied = outcome.lastStanding
                reportResult(
                    if (tied.size > 1) EventOutcome.DRAW else EventOutcome.NO_WINNER, emptyList(),
                    if (tied.size > 1) "The last minnows (${tied.joinToString(", ") { nameOf(it) }}) were caught together" else "Every minnow was caught",
                )
                val detail = if (tied.isNotEmpty()) " The last minnows (${tied.joinToString(", ") { nameOf(it) }}) were caught together." else ""
                comms.broadcast(Component.text("Every minnow was caught: Sharks and Minnows ends with no winner.$detail", NamedTextColor.YELLOW), CommunicationsManager.Category.EVENT)
                winnerTitle(audience(), "No winner", "Every minnow was caught")
            }
            is SharksRoster.Outcome.Continue -> return
        }
        games.changed()
        finishEventSoon()
    }

    // ── Leaving ───────────────────────────────────────────

    override fun withdraw(uuid: UUID, reason: LeaveReason) {
        if (roster.roleOf(uuid) == null) return
        round.eliminate(uuid)
        roster.withdraw(uuid)
        tell(Component.text("${nameOf(uuid)} ${reason.label} and is out of Sharks and Minnows.", NamedTextColor.GRAY))
        when (round.state) {
            // During a countdown the crossing is checked right after GO.
            RoundState.ACTIVE -> queueEvaluate()
            RoundState.WAITING -> if (roster.crossingNumber > 0) {
                val standing = roster.standing()
                if (standing !is SharksRoster.Outcome.Continue) finish(standing)
            }
            else -> {}
        }
        games.changed()
    }

    override fun clearRun() {
        roster = SharksRoster()
        crossingTimer = null
        transition = null
        lastCatch.clear()
    }

    private fun present(uuid: UUID): Player? {
        val s = session ?: return null
        if (!s.isRegistered(uuid)) return null
        return Bukkit.getPlayer(uuid)?.takeIf { games.inEventWorld(it) }
    }

    // ── Status ────────────────────────────────────────────

    override fun statusLines(): List<Component> {
        if (session == null) return listOf(Component.text("Sharks and Minnows isn't running.", NamedTextColor.GRAY))
        val lines = mutableListOf(
            line("Crossing", if (roster.crossingNumber == 0) "not started" else roster.crossingNumber.toString()),
            line("Crossing state", round.state.label, if (round.state.inProgress) NamedTextColor.GREEN else NamedTextColor.YELLOW),
            line("Sharks", roster.sharks.size.toString(), NamedTextColor.AQUA),
            line("Minnows", roster.minnows.size.toString(), NamedTextColor.GOLD),
        )
        if (roster.inCrossing) {
            lines += line("Still crossing", roster.stillCrossing.size.toString())
            lines += line("Safe", roster.safe.size.toString(), NamedTextColor.GREEN)
        }
        if (roster.crossingNumber > 0) lines += line("Caught this crossing", roster.caught.size.toString(), NamedTextColor.RED)
        round.overallWinner?.let { lines += line("Winner", nameOf(it), NamedTextColor.GOLD) }
        return lines
    }

    private companion object {
        val SHARK_COLOR: Color = Color.fromRGB(0x2B5F8A)
        val MINNOW_COLOR: Color = Color.fromRGB(0xF2A33A)

        fun inside(r: ArenaRegion, p: ArenaPoint): Boolean = r.contains(p)

        fun inside(r: ArenaRegion, loc: Location): Boolean =
            loc.world?.name == r.world && loc.blockX in r.minX..r.maxX && loc.blockY in r.minY..r.maxY && loc.blockZ in r.minZ..r.maxZ
    }
}
