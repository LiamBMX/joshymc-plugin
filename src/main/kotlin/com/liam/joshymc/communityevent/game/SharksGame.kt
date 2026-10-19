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
 * Hosted Sharks and Minnows (issues #1189, #1247). `/event start` assigns the starting Sharks
 * at random, puts everyone on their spawn and starts crossing 1. Each crossing runs from the
 * Minnow Spawn to the Finish Zone. A Shark's hit catches a crossing Minnow, who turns into a
 * Shark on the spot (Shark shirt) and hunts the Minnows still crossing; Minnows in the Finish
 * Zone are safe for the rest of that crossing.
 *
 * A crossing ends once every Minnow is safe or caught. Making it across never ends the game:
 * the surviving Minnows go back to the Minnow Spawn, every Shark (old and new) to the Shark
 * Spawn, and the next crossing starts by itself after `auto-next-seconds` (or with
 * `/event sharks next`). The game ends when the last Minnow finishes a crossing (1st place;
 * a Minnow who was already across when the others were caught counts) or no Minnow is left
 * (no 1st place). Placements are per crossing, see [SharksRoster].
 *
 * Crossing direction: a Sharks arena holds one Minnow Spawn and one Finish Zone, so every
 * crossing runs the same way; nothing is placed anywhere the arena doesn't define.
 *
 * Hits never deal damage (HostedGameListener cancels every hit and all fall damage on a player
 * in a game); they're only read as catches, and only while a crossing is ACTIVE.
 */
class SharksGame(games: HostedGames) : MinigameGame(games, EventType.SHARKS) {

    private var settings = SharksSettings.read(null)
    var roster = SharksRoster()
        private set

    private var crossingTimer: BukkitTask? = null
    private var transition: BukkitTask? = null
    private val lastCatch = HashMap<UUID, Long>()
    /** Readied (shirt on) for the current crossing. Only they can turn into a Shark on the spot. */
    private val equipped = HashSet<UUID>()
    private var lastMinnowAnnounced = false

    override fun minPlayers(): Int = SharksSettings.read(plugin.config.getConfigurationSection("hosted-events")).minPlayers

    override fun setupProblem(arena: EventArena): String? {
        val shark = arena.point(ArenaField.SHARK_SPAWN) ?: return "Arena '${arena.displayName}' has no Shark Spawn. Set it with /event sharks setshark."
        val minnow = arena.point(ArenaField.MINNOW_SPAWN) ?: return "Arena '${arena.displayName}' has no Minnow Spawn. Set it with /event sharks setminnow."
        val finish = arena.region(ArenaField.FINISH) ?: return "Arena '${arena.displayName}' has no Finish Zone. Set it with /event sharks setfinish."
        if (inside(finish, minnow)) return "The Minnow Spawn of '${arena.displayName}' is inside the Finish Zone; minnows would be safe at once."
        if (inside(finish, shark)) return "The Shark Spawn of '${arena.displayName}' is inside the Finish Zone; sharks can't start in the safe zone."
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
        if (round.state == RoundState.COMPLETE) return EventResult.Success(s, "Sharks and Minnows is over.")
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
        if (!round.begin(sharks + minnows)) { roster.abortCrossing(); return "A crossing is already in progress." }
        lastCatch.clear()
        equipped.clear()
        lastMinnowAnnounced = false

        sharks.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { names[uuid] = it.name; teleportSafely(it, sharkSpawn) } }
        minnows.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { names[uuid] = it.name; teleportSafely(it, minnowSpawn) } }
        val sharkShirt = sharkShirt()
        val minnowShirt = games.gear.roleShirt("Minnow", MINNOW_COLOR, NamedTextColor.GOLD)
        tell(
            Component.text("Round ${roster.crossingNumber} begins! Reach the other side! ", NamedTextColor.GOLD)
                .append(Component.text("(${minnows.size} minnow(s) vs ${sharks.size} shark(s))", NamedTextColor.GRAY))
        )
        announceLastMinnow()
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
            onGo = { onGo() },
        )
        games.changed()
        return null
    }

    private fun onGo() {
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
     * (once); a Shark (including a Minnow caught this crossing) may never enter it and is held at its edge.
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
     * A hit between two players of the crossing. Only a Shark still in the crossing hitting a
     * crossing Minnow outside the Finish Zone while the crossing is ACTIVE counts; every other hit
     * is ignored. The damage is already cancelled.
     */
    fun hit(attacker: Player, victim: Player) {
        if (round.state != RoundState.ACTIVE) return
        if (!roster.isShark(attacker.uniqueId) || !round.isAlive(attacker.uniqueId)) return
        if (roster.stateOf(victim.uniqueId) != SharksRoster.MinnowState.CROSSING) return
        val finish = arena?.region(ArenaField.FINISH)
        if (finish != null && inside(finish, victim.location)) {
            // Already over the line (the move that got them there just hasn't been seen yet): safe, not caught.
            reachSafety(victim)
            return
        }
        val now = System.currentTimeMillis()
        val last = lastCatch[attacker.uniqueId]
        if (last != null && now - last < settings.catchCooldownMs) return
        if (catchMinnow(victim.uniqueId, attacker, "tagged by ${attacker.name}")) lastCatch[attacker.uniqueId] = now
    }

    /**
     * Catches a crossing Minnow once. A readied Minnow turns into a Shark where they stand and
     * keeps playing this crossing; one who was never readied waits off the arena and is a Shark
     * from the next crossing.
     */
    private fun catchMinnow(uuid: UUID, shark: Player?, reason: String): Boolean {
        val caught = if (shark != null) roster.catch(shark.uniqueId, uuid) else roster.forceCatch(uuid)
        if (!caught) return false
        val player = Bukkit.getPlayer(uuid)
        val inPlace = player != null && uuid in equipped && round.isAlive(uuid) && games.inventorySaved(player)
        if (player != null) player.world.playSound(player.location, Sound.ENTITY_PLAYER_SPLASH, 0.7f, 1.2f)
        if (inPlace && player != null) {
            player.inventory.setChestplate(sharkShirt())
            // No tag in the same instant they were caught.
            lastCatch[uuid] = System.currentTimeMillis()
            comms.send(player, Component.text("You were caught ($reason)! You're a Shark now: tag the minnows still crossing.", NamedTextColor.AQUA), CommunicationsManager.Category.EVENT)
        } else {
            round.eliminate(uuid)
            if (player != null) {
                sendToWaiting(player)
                comms.send(player, Component.text("You were caught ($reason). You'll be a Shark next crossing.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
            }
        }
        tell(
            Component.text("${nameOf(uuid)} was caught and is now a Shark!", NamedTextColor.YELLOW)
                .append(Component.text(" ($reason, ${roster.stillCrossing.size} still crossing)", NamedTextColor.GRAY))
        )
        announceLastMinnow()
        games.changed()
        queueEvaluate()
        return true
    }

    /** "Only one Minnow remains!" once per crossing, while that Minnow still has to make it across. */
    private fun announceLastMinnow() {
        if (lastMinnowAnnounced || !roster.inCrossing) return
        val last = roster.minnows.singleOrNull() ?: return
        if (roster.stateOf(last) != SharksRoster.MinnowState.CROSSING) return
        lastMinnowAnnounced = true
        tell(Component.text("Only one Minnow remains! Can ${nameOf(last)} make it across?", NamedTextColor.GOLD))
    }

    override fun knockOut(uuid: UUID, reason: String) {
        if (roster.stateOf(uuid) == SharksRoster.MinnowState.CROSSING) catchMinnow(uuid, null, reason)
    }

    override fun evaluate() {
        if (round.state != RoundState.ACTIVE) return
        if (!roster.crossingDone) return
        endCrossing()
    }

    /** Ends the ACTIVE crossing once (queued evaluations, the timer and staff can all ask). */
    private fun endCrossing() {
        if (round.state != RoundState.ACTIVE || !roster.inCrossing) return
        crossingTimer?.cancel(); crossingTimer = null
        val safe = roster.safe
        val outcome = roster.endCrossing() ?: return
        round.end(null)
        val caughtNow = roster.caught
        if (outcome !is SharksRoster.Outcome.Continue) { finish(outcome); return }
        round.ready()
        tell(
            Component.text("Round complete! Surviving Minnows are returning to the start. ", NamedTextColor.GOLD)
                .append(Component.text("(${safe.size} made it, ${caughtNow.size} caught, ${outcome.minnows.size} minnow(s) left)", NamedTextColor.GRAY))
        )
        val placed = caughtNow.groupBy { roster.placeOf(it) }
        for (place in listOf(2, 3)) {
            val group = placed[place] ?: continue
            tell(Component.text("${joinNames(group)} ${if (group.size > 1) "share" else "takes"} ${ordinal(place)} place.", NamedTextColor.YELLOW))
        }
        // Give everyone a moment, line them up, then start the next crossing (or wait for staff).
        val number = round.round
        val delay = settings.transitionDelaySeconds * 20L
        transition = session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (isRunning && round.state == RoundState.WAITING && round.round == number) lineUp(number)
        }, delay.coerceAtLeast(1L)))
        games.changed()
    }

    /** Between crossings: Minnows back on the Minnow Spawn, Sharks on the Shark Spawn, no gear. Then the next crossing. */
    private fun lineUp(number: Int) {
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
        val auto = settings.autoNextSeconds
        if (auto <= 0) {
            tell(Component.text("Get ready: the next crossing starts when staff are ready.", NamedTextColor.GRAY))
            tellStaff(Component.text("${roster.minnows.size} minnow(s) left. Start the next crossing with /event sharks next.", NamedTextColor.GRAY))
            return
        }
        tell(Component.text("Get ready: the next crossing starts in ${auto}s.", NamedTextColor.GRAY))
        transition = session?.track(Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!isRunning || round.state != RoundState.WAITING || round.round != number) return@Runnable
            startCrossing()?.let { tellStaff(Component.text("Couldn't start the next crossing: $it", NamedTextColor.RED)) }
        }, auto * 20L))
    }

    private fun finish(outcome: SharksRoster.Outcome) {
        crossingTimer?.cancel(); crossingTimer = null
        transition?.cancel(); transition = null
        if (round.state.inProgress) round.end(null)
        for (uuid in round.active) Bukkit.getPlayer(uuid)?.let { sendToWaiting(it) }
        val crossings = roster.crossingNumber
        val after = "after $crossings crossing${if (crossings == 1) "" else "s"}"
        val second = roster.placed(2)
        val third = roster.placed(3)
        val places = buildString {
            if (second.isNotEmpty()) append(" ${if (second.size > 1) "Tied 2nd" else "2nd"}: ${joinNames(second)}.")
            if (third.isNotEmpty()) append(" ${if (third.size > 1) "Tied 3rd" else "3rd"}: ${joinNames(third)}.")
        }
        when (outcome) {
            is SharksRoster.Outcome.Winner -> {
                round.complete(outcome.winner)
                reportResult(EventOutcome.WINNER, listOf(outcome.winner), "${nameOf(outcome.winner)} was the last minnow to make it across ($after).$places", second, third)
                comms.broadcast(Component.text("${nameOf(outcome.winner)} wins Sharks and Minnows!", NamedTextColor.GOLD), CommunicationsManager.Category.EVENT)
                winnerTitle(audience(), "${nameOf(outcome.winner)} wins!", "Last minnow across")
            }
            is SharksRoster.Outcome.NoWinner -> {
                round.complete(null)
                val why = if (roster.minnows.isEmpty()) "Every minnow was caught" else "No sharks were left for the last minnow"
                reportResult(EventOutcome.NO_WINNER, emptyList(), "$why ($after), so there is no 1st place.$places", second, third)
                val headline = if (roster.minnows.isEmpty()) "All Minnows were caught! No first-place winner!" else "No Sharks are left: Sharks and Minnows ends with no first-place winner."
                comms.broadcast(Component.text(headline, NamedTextColor.YELLOW), CommunicationsManager.Category.EVENT)
                winnerTitle(audience(), "No winner", if (roster.minnows.isEmpty()) "Every minnow was caught" else "No sharks left")
            }
            is SharksRoster.Outcome.Continue -> return
        }
        if (second.size > 1) tell(Component.text("${joinNames(second)} tied for second place!", NamedTextColor.YELLOW))
        if (places.isNotBlank()) tell(Component.text("Placements:$places", NamedTextColor.GRAY))
        games.changed()
        finishEventSoon()
    }

    // ── Leaving ───────────────────────────────────────────

    /**
     * [uuid] left the event (left, quit, left the event world). They're out for good (the session
     * won't take them back), a Minnow who leaves gets no place, and a place they already earned
     * stays recorded. Their leaving never blocks the crossing: it is checked again right away.
     */
    override fun withdraw(uuid: UUID, reason: LeaveReason) {
        if (roster.roleOf(uuid) == null) return
        round.eliminate(uuid)
        roster.withdraw(uuid)
        equipped.remove(uuid)
        tell(Component.text("${nameOf(uuid)} ${reason.label} and is out of Sharks and Minnows.", NamedTextColor.GRAY))
        when (round.state) {
            // During a countdown the crossing is checked right after GO.
            RoundState.ACTIVE -> { announceLastMinnow(); queueEvaluate() }
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
        equipped.clear()
        lastMinnowAnnounced = false
    }

    private fun present(uuid: UUID): Player? {
        val s = session ?: return null
        if (!s.isRegistered(uuid)) return null
        return Bukkit.getPlayer(uuid)?.takeIf { games.inEventWorld(it) }
    }

    private fun sharkShirt() = games.gear.roleShirt("Shark", SHARK_COLOR, NamedTextColor.AQUA)

    private fun joinNames(players: List<UUID>): String = when (players.size) {
        0 -> ""
        1 -> nameOf(players[0])
        else -> players.dropLast(1).joinToString(", ") { nameOf(it) } + " and " + nameOf(players.last())
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
        for (place in 1..3) {
            val group = roster.placed(place)
            if (group.isNotEmpty()) lines += line(ordinal(place).replaceFirstChar { it.uppercase() }, joinNames(group), NamedTextColor.GOLD)
        }
        return lines
    }

    private companion object {
        val SHARK_COLOR: Color = Color.fromRGB(0x2B5F8A)
        val MINNOW_COLOR: Color = Color.fromRGB(0xF2A33A)

        fun ordinal(place: Int): String = when (place) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${place}th" }

        fun inside(r: ArenaRegion, p: ArenaPoint): Boolean = r.contains(p)

        fun inside(r: ArenaRegion, loc: Location): Boolean =
            loc.world?.name == r.world && loc.blockX in r.minX..r.maxX && loc.blockY in r.minY..r.maxY && loc.blockZ in r.minZ..r.maxZ
    }
}
