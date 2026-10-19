package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.EventController
import com.liam.joshymc.communityevent.EventResult
import com.liam.joshymc.communityevent.EventSession
import com.liam.joshymc.communityevent.EventState
import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.communityevent.LeaveReason
import com.liam.joshymc.communityevent.arena.ArenaField
import com.liam.joshymc.communityevent.arena.ArenaPoint
import com.liam.joshymc.communityevent.arena.EventArena
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID

/**
 * Shared gameplay for the hosted Spleef and Sumo events (issue #1187), plugged into the
 * session as its [EventController]. It never owns a session or its state: the session moves
 * CREATED -> OPEN -> RUNNING -> FINISHED as before, and a game only exists while its session
 * is RUNNING. Every scheduled task goes through [EventSession.track], so stop/reset/shutdown
 * cancel it.
 *
 * Players reach the arena by teleport into the event world, so [com.liam.joshymc.manager.EventManager]'s
 * inventory separation (save on entry, restore on exit) always applies; this class only ever
 * clears or fills the event-world inventory, and only once EventManager tracks the player there.
 */
abstract class MinigameGame(protected val games: HostedGames, val type: EventType) : EventController {

    protected val plugin get() = games.plugin
    protected val comms get() = plugin.commsManager

    protected var session: EventSession? = null
    protected var arena: EventArena? = null
    protected var world: World? = null

    /** The current round/match. Replaced (never reused) when a run starts or ends. */
    var round = RoundTracker()
        private set

    /** Last known names, so offline players still show up in lists. */
    protected val names = HashMap<UUID, String>()

    private var evaluateQueued = false
    private var countdown: BukkitTask? = null

    /** The game is live: its session is RUNNING. */
    val isRunning: Boolean get() = session?.let { it.state == EventState.RUNNING && games.sessions.active === it } == true

    fun nameOf(uuid: UUID): String = names[uuid] ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)

    /** A registered player of the running game who is in the event world. */
    fun isPlaying(player: Player): Boolean {
        val s = session ?: return false
        return isRunning && s.isRegistered(player.uniqueId) && games.inEventWorld(player)
    }

    /** In the round right now (countdown or active) and not knocked out. */
    fun isCompeting(uuid: UUID): Boolean = isRunning && round.state.inProgress && round.isAlive(uuid)

    // ── Subclass hooks ────────────────────────────────────

    /** Why [session] can't start, or null. Runs before the session moves to RUNNING. */
    fun startProblem(session: EventSession, arena: EventArena): String? =
        setupProblem(arena) ?: playerProblem(session.participantCount)

    /**
     * Why [arena] and this game's settings (kit, config, floor storage) aren't ready, or null.
     * Ignores registrations, so `/event start <type>` (issue #1248) checks it before registration opens.
     */
    abstract fun setupProblem(arena: EventArena): String?

    /** The configured minimum number of registered players. */
    abstract fun minPlayers(): Int

    /** Why [registered] players aren't enough, or null. */
    fun playerProblem(registered: Int): String? {
        val min = minPlayers()
        if (registered >= min) return null
        return "${type.displayName} needs at least $min registered player${if (min == 1) "" else "s"} ($registered registered)."
    }

    /** A competitor moved to [to] while the round is ACTIVE. */
    abstract fun onActiveMove(player: Player, to: Location)

    /** Knocks [uuid] out of the current round/match (once; repeats are ignored). */
    protected abstract fun knockOut(uuid: UUID, reason: String)

    /** A player of the game died (only possible through /kill and the like: game damage is cancelled). */
    fun died(uuid: UUID) {
        if (isCompeting(uuid)) knockOut(uuid, "died")
    }

    /** [uuid] left the event (left, quit, left the event world). */
    protected abstract fun withdraw(uuid: UUID, reason: LeaveReason)

    /** Called at the end of a tick in which someone was knocked out. */
    protected abstract fun evaluate()

    /** Clears per-run state after stop/reset. */
    protected abstract fun clearRun()

    /** Status lines for `/event <type> status` and the GUI. */
    abstract fun statusLines(): List<Component>

    // ── EventController ───────────────────────────────────

    final override fun onStart(session: EventSession) {
        val arena = games.arenas.selectedFor(session) ?: error("No arena is selected")
        val world = Bukkit.getWorld(arena.world) ?: error("World '${arena.world}' isn't loaded")
        round = RoundTracker()
        names.clear()
        clearRun()
        this.session = session
        this.arena = arena
        this.world = world
        try {
            begin(session)
        } catch (e: Exception) {
            cleanup(session)
            throw e
        }
    }

    /** Puts the registered players in the event world and starts the first round/match. Throw to cancel the start. */
    protected abstract fun begin(session: EventSession)

    final override fun onStop(session: EventSession) {
        if (this.session !== session) return
        cleanup(session)
    }

    final override fun onReset(session: EventSession) {
        if (this.session !== session) return
        cleanup(session)
    }

    final override fun onParticipantLeave(session: EventSession, uuid: UUID, reason: LeaveReason) {
        if (this.session !== session || !isRunning) return
        withdraw(uuid, reason)
        if (reason == LeaveReason.LEFT) {
            // Still in the event world: off the arena, empty-handed, and free to /spawn.
            Bukkit.getPlayer(uuid)?.let { sendToWaiting(it) }
        }
    }

    /** Puts everyone still around back to normal event-world state and forgets the run. */
    private fun cleanup(session: EventSession) {
        countdown = null
        for (uuid in session.participants) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (!games.inventorySaved(player)) continue
            val wasCompeting = round.isAlive(uuid) && round.state.inProgress
            games.gear.strip(player)
            if (player.gameMode == GameMode.SURVIVAL) player.gameMode = GameMode.ADVENTURE
            if (wasCompeting) teleportSafely(player, waitingLocation())
        }
        afterStop()
        this.session = null
        this.arena = null
        this.world = null
        evaluateQueued = false
        round = RoundTracker()
        clearRun()
    }

    /** Hook for work after a stop, e.g. restoring the Spleef floor. */
    protected open fun afterStop() {}

    // ── Players ───────────────────────────────────────────

    /**
     * Registered players who are online and can be brought into the arena. Combat-tagged
     * players are skipped (a teleport would let them escape a fight) and told why.
     */
    protected fun gatherPlayers(session: EventSession): List<Player> {
        val result = ArrayList<Player>()
        for (uuid in session.participants) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            names[uuid] = player.name
            if (!games.inEventWorld(player) && plugin.combatManager.isTagged(player)) {
                comms.send(player, Component.text("You're in combat, so you couldn't be brought into ${type.displayName}.", NamedTextColor.RED), CommunicationsManager.Category.EVENT)
                continue
            }
            result += player
        }
        return result
    }

    /** Teleports [player] into the event world. False if something refused the teleport. */
    protected fun bringIn(player: Player, to: Location): Boolean {
        if (player.isInsideVehicle) player.leaveVehicle()
        if (!teleportSafely(player, to)) return false
        return player.world.name == games.eventWorldName
    }

    protected fun teleportSafely(player: Player, to: Location?): Boolean {
        if (to == null) return false
        player.fallDistance = 0f
        player.velocity = org.bukkit.util.Vector()
        return player.teleport(to)
    }

    /** Where players wait between rounds and after being knocked out: the arena lobby, else the event world spawn. */
    fun waitingLocation(): Location? {
        val w = world ?: Bukkit.getWorld(games.eventWorldName) ?: return null
        return arena?.point(ArenaField.LOBBY)?.let { toLocation(it, w) } ?: w.spawnLocation
    }

    protected fun toLocation(point: ArenaPoint, w: World? = world): Location? =
        w?.let { Location(it, point.x, point.y, point.z, point.yaw, point.pitch) }

    /**
     * Off the arena: no gear, adventure mode, at the waiting spot. Does nothing once
     * EventManager has given the player their real inventory and game mode back (they quit or
     * left the event world), so those are never touched.
     */
    protected fun sendToWaiting(player: Player) {
        if (!games.inventorySaved(player)) return
        games.gear.strip(player)
        if (player.gameMode == GameMode.SURVIVAL) player.gameMode = GameMode.ADVENTURE
        teleportSafely(player, waitingLocation())
    }

    /**
     * Readies a competitor: only once EventManager tracks them in the event world (so their
     * real inventory is already saved) is the event inventory cleared and filled.
     * Returns false when they aren't there yet; callers retry on the next countdown tick.
     */
    protected fun equip(player: Player, gameMode: GameMode, items: List<org.bukkit.inventory.ItemStack>): Boolean {
        if (!games.inventorySaved(player)) return false
        player.inventory.clear()
        player.inventory.setArmorContents(arrayOfNulls(4))
        player.inventory.setItemInOffHand(null)
        items.forEach { player.inventory.addItem(it) }
        player.gameMode = gameMode
        player.allowFlight = false
        player.isFlying = false
        player.fireTicks = 0
        player.fallDistance = 0f
        player.foodLevel = 20
        player.saturation = 20f
        player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value?.let { player.health = it }
        UNFAIR_EFFECTS.forEach { player.removePotionEffect(it) }
        return true
    }

    // ── Kits ──────────────────────────────────────────────

    /**
     * `/event <type> setkit`: saves [player]'s event-world inventory as [kit]. Only while
     * EventManager holds their real inventory (so a survival inventory is never saved), and
     * never while [busy] (the reason they can't save right now) is set.
     */
    protected fun copyKit(player: Player, kit: EventKit, busy: String?): KitSave {
        if (!games.inventorySaved(player)) {
            return KitSave(false, "Stand in the event world first: the kit is copied from your event inventory, never your survival one.")
        }
        if (busy != null) return KitSave(false, busy)
        val wand = org.bukkit.NamespacedKey(plugin, "event_wand")
        val inv = player.inventory
        val items = LinkedHashMap<Int, org.bukkit.inventory.ItemStack>()
        for (slot in EventKit.SLOTS) {
            val item = inv.getItem(slot) ?: continue
            if (item.type.isAir || item.persistentDataContainer.has(wand, org.bukkit.persistence.PersistentDataType.INTEGER)) continue
            items[slot] = games.gear.untagCopy(item)
        }
        kit.save(items)?.let { return KitSave(false, it) }
        plugin.logger.info("[Event] ${player.name} saved the ${kit.label} kit (${items.size} item stack(s)).")
        games.changed()
        return KitSave(true, "Saved the ${kit.label} kit: ${items.size} item stack(s), armor and off-hand included.")
    }

    /** Clears the event inventory and fills it with tagged copies of [kit], slot for slot. */
    protected fun applyKit(player: Player, kit: Map<Int, org.bukkit.inventory.ItemStack>, gameMode: GameMode): Boolean {
        if (!equip(player, gameMode, emptyList())) return false
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        for ((slot, item) in kit) player.inventory.setItem(slot, games.gear.tagCopy(item))
        return true
    }

    // ── Countdown ─────────────────────────────────────────

    /**
     * "5, 4, 3, 2, 1, GO!" for the round's players. [onTick] runs every second before GO
     * (equipping stragglers), [onGo] once at the end. Tied to the session.
     */
    protected fun startCountdown(seconds: Int, onTick: () -> Unit, onGo: () -> Unit) {
        val s = session ?: return
        var left = seconds
        countdown = s.track(Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!isRunning || round.state != RoundState.COUNTDOWN) { countdown?.cancel(); return@Runnable }
            onTick()
            val players = round.active.mapNotNull { Bukkit.getPlayer(it) }
            if (left > 0) {
                players.forEach {
                    it.showTitle(Title.title(
                        Component.text(left.toString(), NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true),
                        Component.text(type.displayName, NamedTextColor.GRAY),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100)),
                    ))
                    it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f)
                }
                left--
                return@Runnable
            }
            countdown?.cancel()
            players.forEach {
                it.showTitle(Title.title(
                    Component.text("GO!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
                    Component.empty(),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(300)),
                ))
                it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f)
            }
            if (round.go()) onGo()
        }, 1L, 20L))
    }

    // ── Eliminations ──────────────────────────────────────

    /** Checks the round once at the end of this tick, so players knocked out in the same tick count as simultaneous. */
    protected fun queueEvaluate() {
        val s = session ?: return
        if (evaluateQueued) return
        evaluateQueued = true
        s.track(Bukkit.getScheduler().runTask(plugin, Runnable {
            evaluateQueued = false
            if (isRunning) evaluate()
        }))
    }

    /**
     * Hands the game's verdict to the session (issue #1199) so it is recorded for staff to
     * confirm when the event ends. Only the first report of a run counts.
     */
    protected fun reportResult(
        outcome: com.liam.joshymc.communityevent.result.EventOutcome,
        winners: List<UUID>,
        summary: String,
        second: List<UUID> = emptyList(),
    ) {
        val s = session ?: return
        if (s.reportResult(com.liam.joshymc.communityevent.DetectedResult(outcome, winners.distinct(), second.distinct() - winners.toSet(), summary = summary))) {
            plugin.logger.info("[Event] ${type.displayName} result detected: ${outcome.name}, winners ${winners.joinToString { nameOf(it) }.ifEmpty { "none" }}.")
        }
    }

    /** Ends the event with its result a few seconds after the final round, through the normal stop. */
    protected fun finishEventSoon() {
        val s = session ?: return
        val id = s.id
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val active = games.sessions.active
            if (active != null && active.id == id && active.state == EventState.RUNNING) {
                val result = games.sessions.stop()
                if (result !is EventResult.Success) plugin.logger.warning("[Event] Couldn't end ${type.displayName} after the final round: ${result.message}")
            }
        }, 100L)
    }

    // ── Messages ──────────────────────────────────────────

    /** Online registered players and the host: the people following this event. */
    protected fun audience(): List<Player> {
        val s = session ?: return emptyList()
        val ids = LinkedHashSet(s.participants)
        s.hostId?.let { ids += it }
        return ids.mapNotNull { Bukkit.getPlayer(it) }
    }

    protected fun tell(message: Component) {
        audience().forEach { comms.send(it, message, CommunicationsManager.Category.EVENT) }
    }

    protected fun tellStaff(message: Component) {
        val host = session?.hostId?.let { Bukkit.getPlayer(it) }
        if (host != null) comms.send(host, message, CommunicationsManager.Category.EVENT)
        else plugin.logger.info("[Event] ${net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(message)}")
    }

    protected fun winnerTitle(players: List<Player>, headline: String, sub: String) {
        players.forEach {
            it.showTitle(Title.title(
                Component.text(headline, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                Component.text(sub, NamedTextColor.YELLOW),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600)),
            ))
        }
    }

    protected fun line(label: String, value: String, color: NamedTextColor = NamedTextColor.WHITE): Component =
        Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color))

    protected companion object {
        /** Potion effects that would decide a round; cleared when a competitor is readied. */
        val UNFAIR_EFFECTS = listOf(
            PotionEffectType.SPEED, PotionEffectType.SLOWNESS, PotionEffectType.JUMP_BOOST,
            PotionEffectType.LEVITATION, PotionEffectType.SLOW_FALLING, PotionEffectType.STRENGTH,
            PotionEffectType.WEAKNESS, PotionEffectType.RESISTANCE, PotionEffectType.HASTE,
            PotionEffectType.MINING_FATIGUE, PotionEffectType.INVISIBILITY,
        )
    }
}
