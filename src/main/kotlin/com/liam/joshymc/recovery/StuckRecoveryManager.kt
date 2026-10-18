package com.liam.joshymc.recovery

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.BackCommand
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.PvpTeleportGuard
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent
import io.papermc.paper.event.player.PlayerClientLoadedWorldEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.potion.PotionEffectType
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Recovers players stuck midair in chunks their client never loaded (issue #1241).
 *
 * Paper kicks a survival player with "Flying is not enabled on this server" once the client has
 * reported floating with no blocks around for ~80 ticks. A client that hasn't received the chunk
 * it stands in can't simulate gravity, so it floats in place and gets that kick, and on rejoin it
 * spawns at the same spot and is kicked again. This manager:
 *
 *  - holds off that kick (cancel + [Player.resetFlyingTicks]) only while the chunk under the player
 *    has not been sent to their client, a server-side fact the client can't fake, and at most
 *    a few incidents per player in a window ([KickGrace]); a real float over chunks the client
 *    has is still kicked by Paper;
 *  - if the second kick of an incident arrives with the chunk still missing, moves the player to a
 *    validated recent safe spot / their /back spot / spawn ([startRecovery]);
 *  - remembers a flying kick that did go through, so the next login starts at a safe spot
 *    ([AsyncPlayerSpawnLocationEvent]), escalating to spawn and then to staff instead of looping;
 *  - backs `/unstuck`, which only acts on a genuine softlock: a missing chunk, a recent kick
 *    grace, a client-load timeout, or 3 seconds frozen in open air with nothing under the player.
 *
 * It never touches inventories, experience, effects or game modes, never moves players out of
 * the event world (the event system owns that, via /spawn and /event leave), never moves a
 * combat-tagged player, and never loads chunks on the main thread.
 */
class StuckRecoveryManager(private val plugin: Joshymc) : Listener {

    companion object {
        const val PERMISSION = "joshymc.unstuck"
        const val OTHERS_PERMISSION = "joshymc.unstuck.others"
        const val BYPASS_PERMISSION = "joshymc.unstuck.bypass"

        private const val SAMPLE_INTERVAL_MS = 2_000L
        private const val RECENT_SUSPICION_MS = 120_000L
        private const val SPAM_GUARD_MS = 3_000L
        private const val FAILED_COOLDOWN_MS = 10_000L
        private const val ADMIN_QUEUE_WINDOW_MS = 24 * 60 * 60 * 1000L
        private const val CHECK_PERIOD_TICKS = 10L
        private const val CHECK_SAMPLES = 6 // 3 seconds
        private const val CLEANUP_PERIOD_TICKS = 6_000L
    }

    enum class Trigger(val label: String) {
        KICK_GRACE("flying-kick grace"),
        UNSTUCK("/unstuck"),
        ADMIN("staff /unstuck"),
        LOGIN("login recovery"),
    }

    private data class SafeSpot(val location: Location, val at: Long)
    private data class Candidate(val label: String, val location: Location, val trusted: Boolean)

    // Main thread only.
    private val safeSpots = HashMap<UUID, SafeSpot>()
    private val lastSample = HashMap<UUID, Long>()
    private val graces = HashMap<UUID, KickGrace>()
    private val clientLoadTimeouts = HashMap<UUID, Long>()
    private val recovering = HashMap<UUID, Int>()
    private val checks = HashMap<UUID, BukkitTask>()
    private val cooldowns = HashMap<UUID, Long>()
    private val spamGuard = HashMap<UUID, Long>()
    private var nextToken = 0
    private var cleanupTask: BukkitTask? = null

    // Read by AsyncPlayerSpawnLocationEvent off the main thread.
    private val loginRecoveries = ConcurrentHashMap<UUID, LoginRecovery>()
    private val appliedLogins = ConcurrentHashMap<UUID, LoginRecovery>()
    private val joinChecks: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @Volatile private var enabled = true
    private var kickGrace = true
    private var graceIncidentMs = 20_000L
    private var graceMaxIncidents = 3
    private var graceWindowMs = 600_000L
    private var safeSpotMaxAgeMs = 600_000L
    private var unstuckCooldownMs = 60_000L
    @Volatile private var loginWindowMs = 900_000L

    fun start() {
        val cfg = plugin.config
        enabled = plugin.isFeatureEnabled("stuck-recovery")
        kickGrace = cfg.getBoolean("stuck-recovery.kick-grace", true)
        graceIncidentMs = cfg.getLong("stuck-recovery.grace-incident-seconds", 20L).coerceIn(5L, 60L) * 1000L
        graceMaxIncidents = cfg.getInt("stuck-recovery.max-grace-incidents", 3).coerceIn(0, 10)
        graceWindowMs = cfg.getLong("stuck-recovery.grace-window-minutes", 10L).coerceIn(1L, 120L) * 60_000L
        safeSpotMaxAgeMs = cfg.getLong("stuck-recovery.safe-spot-max-age-minutes", 10L).coerceIn(1L, 120L) * 60_000L
        unstuckCooldownMs = cfg.getLong("stuck-recovery.unstuck-cooldown-seconds", 60L).coerceIn(5L, 3600L) * 1000L
        loginWindowMs = cfg.getLong("stuck-recovery.login-recovery-window-minutes", 15L).coerceIn(1L, 1440L) * 60_000L
        cleanupTask?.cancel()
        cleanupTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable { cleanup() }, CLEANUP_PERIOD_TICKS, CLEANUP_PERIOD_TICKS)
    }

    fun stop() {
        cleanupTask?.cancel()
        cleanupTask = null
        checks.values.forEach { it.cancel() }
        checks.clear()
        // In-flight async callbacks see their token gone and do nothing.
        recovering.clear()
    }

    // ── Safe-spot tracking ───────────────────────────────

    /** Remembers where the player last stood safely, at most every 2s and only on block changes. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!enabled || !event.hasChangedBlock()) return
        val player = event.player
        val now = System.currentTimeMillis()
        val last = lastSample[player.uniqueId]
        if (last != null && now - last < SAMPLE_INTERVAL_MS) return
        lastSample[player.uniqueId] = now
        // isOnGround is client-reported; the block checks below decide whether the spot is real.
        @Suppress("DEPRECATION")
        if (!player.isOnGround || player.isFlying || player.isGliding || player.isInsideVehicle) return
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) return
        if (plugin.eventManager.isInEventWorld(player)) return
        val to = event.to
        if (SafeSpots.problem(to) != null) return
        safeSpots[player.uniqueId] = SafeSpot(to.clone(), now)
    }

    // ── Flying kicks ─────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFlyingKick(event: PlayerKickEvent) {
        if (!enabled || !kickGrace || event.cause != PlayerKickEvent.Cause.FLYING_PLAYER) return
        val player = event.player
        // The client has the ground under it: this is a real float, so Paper's kick stands.
        if (ownChunkSent(player)) return

        val now = System.currentTimeMillis()
        val grace = graces.getOrPut(player.uniqueId) { KickGrace(graceIncidentMs, graceMaxIncidents, graceWindowMs) }
        val decision = grace.onFlyingKick(now)
        if (decision == KickGrace.Decision.LIMIT_REACHED) {
            plugin.logger.warning("[Unstuck] ${describe(player)} hit the flying-kick grace limit ($graceMaxIncidents per window); letting Paper's kick through.")
            return
        }
        event.isCancelled = true
        player.resetFlyingTicks()

        val autoBlock = autoRecoveryBlock(player)
        if (decision == KickGrace.Decision.NEW_INCIDENT) {
            // First kick of an incident: the chunk is often only late, so just wait for it.
            plugin.logger.info(
                "[Unstuck] Suspected softlock: ${describe(player)} floating in a chunk their client hasn't received; " +
                    "held off the flying kick" + (autoBlock?.let { " (no automatic move: $it)" } ?: ""),
            )
            return
        }
        // Still missing a whole kick-check later: move them somewhere loaded.
        if (autoBlock == null && player.uniqueId !in recovering) startRecovery(player, Trigger.KICK_GRACE)
    }

    /** A flying kick that went through: remember it so the next login doesn't start at the same spot. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFlyingKickApplied(event: PlayerKickEvent) {
        if (!enabled || event.cause != PlayerKickEvent.Cause.FLYING_PLAYER) return
        val player = event.player
        if (plugin.eventManager.isInEventWorld(player)) return
        val now = System.currentTimeMillis()
        val kickedAt = player.location.toSpot()
        val previous = loginRecoveries[player.uniqueId] ?: appliedLogins[player.uniqueId]
        val attempt = StuckRules.nextAttempt(previous, kickedAt, now, loginWindowMs)
        if (attempt > StuckRules.MAX_LOGIN_ATTEMPTS) {
            loginRecoveries.remove(player.uniqueId)
            plugin.logger.warning(
                "[Unstuck] ${describe(player)} keeps getting flying kicks after ${attempt - 1} login recoveries;" +
                    "automatic login recovery stopped. Staff can run /unstuck ${player.name} (works offline: moves them to spawn on next login).",
            )
            return
        }
        // First time: their last safe spot. Again near the same place: straight to spawn.
        val recent = if (attempt == 1) recentSafeSpot(player, now) else null
        val destination = recent ?: fallbacks(player).firstOrNull()
        if (destination == null) {
            plugin.logger.warning("[Unstuck] ${describe(player)} was kicked for flying but no recovery spot is known for their next login.")
            return
        }
        loginRecoveries[player.uniqueId] = LoginRecovery(kickedAt, destination.toSpot(), now, attempt, byAdmin = false)
        plugin.logger.info(
            "[Unstuck] ${describe(player)} was kicked for flying (attempt $attempt); a login within " +
                "${loginWindowMs / 60_000} min starts at ${fmt(destination)}.",
        )
    }

    // ── Login recovery ───────────────────────────────────

    /** Runs off the main thread: only reads the in-memory records and sets the spawn location. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onSpawnLocation(event: AsyncPlayerSpawnLocationEvent) {
        if (!enabled || event.isNewPlayer) return
        val uuid = event.connection.profile.id ?: return
        val record = loginRecoveries[uuid] ?: return
        val spawn = event.spawnLocation
        val spawnWorld = spawn.world ?: return
        val window = if (record.byAdmin) ADMIN_QUEUE_WINDOW_MS else loginWindowMs
        val now = System.currentTimeMillis()
        val apply = StuckRules.loginRecoveryFor(
            record, Spot(spawnWorld.name, spawn.x, spawn.y, spawn.z), plugin.eventManager.eventWorldName, now, window,
        ) ?: return
        val dest = apply.destination
        val world = Bukkit.getWorld(dest.world) ?: return
        if (!loginRecoveries.remove(uuid, record)) return
        appliedLogins[uuid] = apply
        joinChecks += uuid
        event.spawnLocation = Location(world, dest.x, dest.y, dest.z, dest.yaw, dest.pitch)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!joinChecks.remove(player.uniqueId)) return
        val applied = appliedLogins[player.uniqueId] ?: return
        // The spot was checked when it was recorded; blocks may have changed since, so look again.
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            val problem = SafeSpots.problem(player.location)
            plugin.logger.info(
                "[Unstuck] Login recovery for ${describe(player)} (attempt ${applied.attempt}" +
                    (if (applied.byAdmin) ", staff request" else "") + ")" +
                    (problem?.let { ": spot is no longer safe ($it), looking for another" } ?: ": spawned at the safe spot"),
            )
            if (problem != null) {
                startRecovery(player, Trigger.LOGIN, skipRecent = true)
            } else {
                player.fallDistance = 0f
                tell(player, Component.text("You were disconnected while stuck, so you've been moved to a safe spot.", NamedTextColor.GREEN))
            }
        }, 1L)
    }

    /** The client didn't confirm it loaded the world in time: a loading problem /unstuck may act on. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onClientLoaded(event: PlayerClientLoadedWorldEvent) {
        if (!enabled || !event.isTimeout) return
        clientLoadTimeouts[event.player.uniqueId] = System.currentTimeMillis()
        plugin.logger.info("[Unstuck] ${describe(event.player)}'s client didn't finish loading the world in time (suspected loading problem).")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        checks.remove(uuid)?.cancel()
        recovering.remove(uuid)
        safeSpots.remove(uuid)
        lastSample.remove(uuid)
        clientLoadTimeouts.remove(uuid)
        spamGuard.remove(uuid)
        val now = System.currentTimeMillis()
        if (graces[uuid]?.isStale(now) == true) graces.remove(uuid)
        // Cooldowns stay so a relog doesn't reset them; cleanup() drops expired ones.
    }

    // ── /unstuck ─────────────────────────────────────────

    /** Self-service /unstuck: acts only when the player is genuinely stuck. */
    fun requestUnstuck(player: Player) {
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        if (!enabled) { tell(player, red("/unstuck is disabled on this server.")); return }
        val lastTry = spamGuard[uuid]
        if (lastTry != null && now - lastTry < SPAM_GUARD_MS) { tell(player, red("Please wait a moment before trying again.")); return }
        spamGuard[uuid] = now
        if (uuid in recovering || uuid in checks) { tell(player, red("Already working on getting you unstuck.")); return }
        blockReason(player, admin = false)?.let { tell(player, red(it)); return }
        if (!player.hasPermission(BYPASS_PERMISSION)) {
            val until = cooldowns[uuid] ?: 0L
            if (now < until) {
                tell(player, red("You can use /unstuck again in ${(until - now + 999) / 1000}s."))
                return
            }
        }

        suspicion(player, now)?.let { reason ->
            plugin.logger.info("[Unstuck] Suspected softlock: ${describe(player)} ran /unstuck ($reason).")
            startRecovery(player, Trigger.UNSTUCK)
            return
        }

        // No server-side sign of a loading problem: they must be frozen in open air for 3 seconds.
        val samples = mutableListOf(sample(player))
        if (!StuckRules.isSuspended(listOf(samples[0], samples[0]))) {
            tell(player, notStuckMessage())
            return
        }
        tell(player, Component.text("Checking whether you're stuck. Stay still for 3 seconds...", NamedTextColor.YELLOW))
        checks[uuid] = object : BukkitRunnable() {
            override fun run() {
                if (!player.isOnline || checks[uuid]?.taskId != taskId) { cancel(); return }
                samples += sample(player)
                if (!StuckRules.isSuspended(samples)) {
                    checks.remove(uuid); cancel()
                    tell(player, notStuckMessage())
                    return
                }
                if (samples.size > CHECK_SAMPLES) {
                    checks.remove(uuid); cancel()
                    plugin.logger.info("[Unstuck] Suspected softlock: ${describe(player)} frozen in open air for 3s after /unstuck.")
                    startRecovery(player, Trigger.UNSTUCK)
                }
            }
        }.runTaskTimer(plugin, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS)
    }

    /** Staff /unstuck on an online player: skips the softlock check, combat and cooldown, not the event world. */
    fun adminUnstuck(sender: CommandSender, target: Player): Boolean {
        if (!enabled) { sender.sendMessage(red("Stuck recovery is disabled (features.stuck-recovery).")); return false }
        if (target.uniqueId in recovering) { sender.sendMessage(red("${target.name} is already being recovered.")); return false }
        blockReason(target, admin = true)?.let { sender.sendMessage(red("Can't recover ${target.name}: $it")); return false }
        checks.remove(target.uniqueId)?.cancel()
        return startRecovery(target, Trigger.ADMIN, notify = sender)
    }

    /** Staff /unstuck on an offline player: their next login (within 24h) starts at spawn, unless they log into the event world. */
    fun queueOfflineRecovery(sender: CommandSender, uuid: UUID, name: String): Boolean {
        if (!enabled) { sender.sendMessage(red("Stuck recovery is disabled (features.stuck-recovery).")); return false }
        val spawn = plugin.warpManager.getSpawn()
        if (spawn?.world == null) { sender.sendMessage(red("No /spawn is set, so there's nowhere safe to send $name.")); return false }
        loginRecoveries[uuid] = LoginRecovery(null, spawn.toSpot(), System.currentTimeMillis(), 1, byAdmin = true)
        plugin.logger.info("[Unstuck] ${sender.name} queued a login recovery to spawn for $name ($uuid).")
        return true
    }

    // ── Recovery ─────────────────────────────────────────

    /**
     * Moves [player] to the first candidate that loads, is still safe and accepts the teleport.
     * One recovery per player at a time; every async callback checks its token, so a quit,
     * reload or newer attempt makes the old one do nothing.
     */
    fun startRecovery(player: Player, trigger: Trigger, notify: CommandSender? = null, skipRecent: Boolean = false): Boolean {
        val uuid = player.uniqueId
        if (uuid in recovering) return false
        val token = ++nextToken
        recovering[uuid] = token
        val from = player.location.clone()
        val candidates = candidates(player, skipRecent)
        tryCandidate(player, token, trigger, from, candidates, 0, mutableListOf(), notify)
        return true
    }

    private fun tryCandidate(
        player: Player, token: Int, trigger: Trigger, from: Location,
        candidates: List<Candidate>, index: Int, problems: MutableList<String>, notify: CommandSender?,
    ) {
        if (recovering[player.uniqueId] != token) return
        if (!player.isOnline) { recovering.remove(player.uniqueId); return }
        val candidate = candidates.getOrNull(index)
        if (candidate == null) {
            finishFailed(player, trigger, from, if (problems.isEmpty()) "no recovery spot known" else problems.joinToString("; "), notify)
            return
        }
        val next = { problem: String ->
            problems += "${candidate.label}: $problem"
            tryCandidate(player, token, trigger, from, candidates, index + 1, problems, notify)
        }
        val world = candidate.location.world
        if (world == null || Bukkit.getWorld(world.uid) == null) { next("world not loaded"); return }

        // Never generate terrain for a recovery: every candidate is somewhere that already exists.
        world.getChunkAtAsync(candidate.location.blockX shr 4, candidate.location.blockZ shr 4, false).whenComplete { chunk, error ->
            onMain {
                if (recovering[player.uniqueId] != token) return@onMain
                if (!player.isOnline) { recovering.remove(player.uniqueId); return@onMain }
                if (error != null || chunk == null) { next(if (error != null) "chunk load failed" else "chunk not generated"); return@onMain }

                var dest = candidate.location
                var problem = SafeSpots.problem(dest)
                if (problem != null && candidate.trusted) {
                    // A world spawn point can sit in the air or underground; try the surface of its column.
                    SafeSpots.surfaceAbove(dest)?.let { surface ->
                        if (SafeSpots.problem(surface) == null) { dest = surface; problem = null }
                    }
                }
                if (problem == null && !candidate.trusted && plugin.claimManager.isDenied(player, dest)) problem = "banned from that claim"
                problem?.let { next(it); return@onMain }

                // Combat or a world change may have happened while the chunk loaded.
                blockReason(player, admin = trigger == Trigger.ADMIN || trigger == Trigger.LOGIN)?.let {
                    finishFailed(player, trigger, from, it, notify, toPlayer = it)
                    return@onMain
                }
                if (trigger != Trigger.ADMIN && trigger != Trigger.LOGIN && dest.world != player.world && !crossWorldAllowed(player)) {
                    next("different world"); return@onMain
                }

                val back = BackCommand.lastLocations[player.uniqueId]
                player.teleportAsync(dest, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete { ok, teleportError ->
                    onMain {
                        if (recovering[player.uniqueId] != token) return@onMain
                        if (!player.isOnline) { recovering.remove(player.uniqueId); return@onMain }
                        if (teleportError != null || ok != true) { next("teleport refused"); return@onMain }
                        // /back must not lead straight back to the stuck spot.
                        if (back != null) BackCommand.lastLocations[player.uniqueId] = back
                        else BackCommand.lastLocations.remove(player.uniqueId)
                        finishSucceeded(player, trigger, from, candidate.label, dest, notify)
                    }
                }
            }
        }
    }

    private fun finishSucceeded(player: Player, trigger: Trigger, from: Location, label: String, dest: Location, notify: CommandSender?) {
        recovering.remove(player.uniqueId)
        // Only the movement state the stuck spot left behind; inventory, XP, effects and game mode are untouched.
        player.fallDistance = 0f
        player.velocity = Vector(0, 0, 0)
        player.resetFlyingTicks()
        if (trigger == Trigger.UNSTUCK) cooldowns[player.uniqueId] = System.currentTimeMillis() + unstuckCooldownMs
        plugin.logger.info("[Unstuck] ${trigger.label}: moved ${player.name} (${player.uniqueId}) from ${fmt(from)} to $label ${fmt(dest)}.")
        tell(player, Component.text("You were stuck, so you've been moved to $label.", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.2f)
        notify?.sendMessage(Component.text("Moved ${player.name} to $label.", NamedTextColor.GREEN))
    }

    private fun finishFailed(player: Player, trigger: Trigger, from: Location, reason: String, notify: CommandSender?, toPlayer: String? = null) {
        recovering.remove(player.uniqueId)
        if (trigger == Trigger.UNSTUCK) cooldowns[player.uniqueId] = System.currentTimeMillis() + FAILED_COOLDOWN_MS
        plugin.logger.warning("[Unstuck] ${trigger.label} FAILED for ${player.name} (${player.uniqueId}) at ${fmt(from)}: $reason")
        if (trigger != Trigger.KICK_GRACE) {
            tell(player, red(toPlayer ?: "Couldn't find a safe place to move you. Please ask a staff member for help."))
        }
        notify?.sendMessage(red("Couldn't recover ${player.name}: $reason"))
    }

    /**
     * Where a stuck player may go, best first: their recent safe spot, their /back spot (same
     * world only), then spawn. Cross-world spawn is left out for the PvP world (its teleport lock)
     * and checked again against the event world before teleporting.
     */
    private fun candidates(player: Player, skipRecent: Boolean): List<Candidate> {
        val now = System.currentTimeMillis()
        val list = mutableListOf<Candidate>()
        if (!skipRecent) recentSafeSpot(player, now)?.let { list += Candidate("your last safe spot", it, trusted = false) }
        BackCommand.lastLocations[player.uniqueId]?.takeIf { it.world == player.world }?.let {
            list += Candidate("your /back location", it.clone(), trusted = false)
        }
        fallbacks(player).forEach { list += Candidate("spawn", it, trusted = true) }
        return list
    }

    /** The configured /spawn when it's in this world, this world's spawn, then /spawn in another world where allowed. */
    private fun fallbacks(player: Player): List<Location> {
        val world = player.world
        val serverSpawn = plugin.warpManager.getSpawn()?.takeIf { it.world != null }
        val list = mutableListOf<Location>()
        if (serverSpawn != null && serverSpawn.world == world) list += serverSpawn.clone()
        list += world.spawnLocation
        if (serverSpawn != null && serverSpawn.world != world && crossWorldAllowed(player)) list += serverSpawn.clone()
        return list
    }

    private fun recentSafeSpot(player: Player, now: Long): Location? {
        val spot = safeSpots[player.uniqueId] ?: return null
        if (now - spot.at > safeSpotMaxAgeMs || spot.location.world != player.world) return null
        return spot.location.clone()
    }

    private fun crossWorldAllowed(player: Player): Boolean =
        !PvpTeleportGuard.isPvpWorld(player.world) && !plugin.eventManager.isInEventWorld(player)

    // ── Eligibility ──────────────────────────────────────

    /** Why [player] can't be recovered right now, or null. Staff override everything but death and the event world. */
    private fun blockReason(player: Player, admin: Boolean): String? {
        if (player.isDead) return "You can't do that while dead."
        if (plugin.eventManager.isInEventWorld(player)) return "You're in the event world. Use /spawn or /event leave to get out safely."
        if (admin) return null
        if (plugin.adminManager.isFrozen(player.uniqueId)) return "You are frozen."
        if (plugin.combatManager.isTagged(player)) return "You can't use /unstuck while in combat."
        if (player.isSleeping) return "You can't do that while sleeping."
        if (player.isInsideVehicle) return "Get out of your vehicle first."
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return "You can fly in this game mode, so /unstuck isn't needed."
        return null
    }

    /** Why a held-off kick won't also move the player, or null when it may. */
    private fun autoRecoveryBlock(player: Player): String? = when {
        plugin.eventManager.isInEventWorld(player) -> "in the event world"
        plugin.combatManager.isTagged(player) -> "combat-tagged"
        plugin.adminManager.isFrozen(player.uniqueId) -> "frozen"
        player.isInsideVehicle -> "in a vehicle"
        player.isDead || player.isSleeping -> "dead or sleeping"
        else -> null
    }

    /** A server-side sign that the player can't move because of loading, or null. */
    private fun suspicion(player: Player, now: Long): String? {
        val loc = player.location
        if (!player.world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return "their chunk isn't loaded on the server"
        if (!ownChunkSent(player)) return "their client hasn't received the chunk they're in"
        graces[player.uniqueId]?.lastIncidentAt(now)?.let { if (now - it <= RECENT_SUSPICION_MS) return "recent flying-kick grace" }
        clientLoadTimeouts[player.uniqueId]?.let { if (now - it <= RECENT_SUSPICION_MS) return "client world load timed out" }
        return null
    }

    private fun ownChunkSent(player: Player): Boolean {
        val loc = player.location
        return player.isChunkSent(Chunk.getChunkKey(loc.blockX shr 4, loc.blockZ shr 4))
    }

    private fun sample(player: Player): MotionSample {
        val loc = player.location
        val world = player.world
        val loaded = world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)
        val box = player.boundingBox
        val underFeet = BoundingBox(box.minX, box.minY - 0.1, box.minZ, box.maxX, box.minY, box.maxZ)
        val feet = if (loaded) loc.block else null
        val state = MovementState(
            canFly = player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR || player.allowFlight || player.isFlying,
            gliding = player.isGliding,
            riptiding = player.isRiptiding,
            levitating = player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.SLOW_FALLING),
            inVehicle = player.isInsideVehicle,
            inLiquid = player.isInWaterOrBubbleColumn || player.isInLava || player.isSwimming,
            climbing = feet == null || !SafeSpots.isOpenAir(feet) || !SafeSpots.isOpenAir(feet.getRelative(BlockFace.UP)),
            sleepingOrDead = player.isSleeping || player.isDead,
        )
        val supported = !loaded || world.hasCollisionsIn(underFeet)
        return MotionSample(loc.x, loc.y, loc.z, supported, state)
    }

    // ── Helpers ──────────────────────────────────────────

    private fun cleanup() {
        val now = System.currentTimeMillis()
        graces.entries.removeIf { (uuid, grace) -> Bukkit.getPlayer(uuid) == null && grace.isStale(now) }
        cooldowns.entries.removeIf { it.value <= now }
        safeSpots.entries.removeIf { now - it.value.at > safeSpotMaxAgeMs }
        clientLoadTimeouts.entries.removeIf { now - it.value > RECENT_SUSPICION_MS }
        loginRecoveries.entries.removeIf { now - it.value.createdAt > (if (it.value.byAdmin) ADMIN_QUEUE_WINDOW_MS else loginWindowMs) }
        appliedLogins.entries.removeIf { now - it.value.createdAt > loginWindowMs }
        joinChecks.removeIf { !appliedLogins.containsKey(it) }
    }

    private fun onMain(block: () -> Unit) {
        if (Bukkit.isPrimaryThread()) block()
        else if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { block() })
    }

    private fun notStuckMessage(): Component = red(
        "You don't look stuck: you're standing on something, moving, or in something that holds you up. " +
            "/unstuck only helps when you're frozen in midair or the world around you didn't load. Try /spawn instead.",
    )

    private fun tell(player: Player, message: Component) =
        plugin.commsManager.send(player, message, CommunicationsManager.Category.TELEPORT)

    private fun red(text: String): Component = Component.text(text, NamedTextColor.RED)

    private fun describe(player: Player): String = "${player.name} (${player.uniqueId}) at ${fmt(player.location)}"

    private fun fmt(loc: Location): String =
        "${loc.world?.name ?: "?"} ${"%.1f".format(loc.x)}, ${"%.1f".format(loc.y)}, ${"%.1f".format(loc.z)}"

    private fun Location.toSpot(): Spot = Spot(world?.name ?: "", x, y, z, yaw, pitch)
}
