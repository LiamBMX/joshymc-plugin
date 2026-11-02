package com.liam.joshymc.lifesteal.combat

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.manager.CombatManager
import com.liam.joshymc.manager.CommunicationsManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerQuitEvent
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifesteal combat logging (issue #1350). Lifesteal only: it attaches to the existing
 * [CombatManager] as its [CombatManager.TagObserver] only where this server hosts Lifesteal, and
 * every callback also checks that the player is in a Lifesteal world, so Survival (and Survival
 * worlds on a mixed server) keep the unchanged combat tag, NPC and messages.
 *
 * - Tagging stays CombatManager's (same hit, same duration, same action-bar countdown). This adds
 *   the opponents and start time per session ([CombatSessions]) and the two chat lines.
 * - A tagged quit is classified by [CombatLogRules.classify] and recorded as one incident
 *   ([CombatLogStore], written on the DB thread). Server-caused disconnects (shutdown, staff kick,
 *   server error) never count and leave no combat-log NPC; counted ones keep the NPC.
 * - No heart penalty exists (blueprint v1.1 defines none), so nothing here changes hearts.
 *
 * All state is main-thread, keyed by UUID (never a Player), and removed when the tag ends.
 */
class LifestealCombatManager(private val plugin: Joshymc) : CombatManager.TagObserver {

    private val store = CombatLogStore(plugin.databaseManager)
    private val sessions = CombatSessions()
    /** Kick cause of a player being kicked right now (PlayerKickEvent -> PlayerQuitEvent), cleared on quit. */
    private val pendingKicks = ConcurrentHashMap<UUID, String>()

    /** Per boot, so dedupe keys never collide after a restart. */
    private val bootId = UUID.randomUUID().toString().substring(0, 8)

    data class Failure(val at: Long, val incidentId: String, val player: UUID, val message: String)
    /** Recent write failures for `/lifesteal combat failures` (bounded). */
    private val failures = ArrayDeque<Failure>()

    @Volatile var settings: LifestealSettings.CombatLogging? = null
        private set
    @Volatile private var stopping = false
    private var sweepTask: Int = -1
    private var tablesReady = false

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes
    /** Hosted, lifesteal.yml valid, combat-logging enabled and tables ready. */
    val active: Boolean get() = hosted && tablesReady && settings != null && !stopping

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealConfigManager.load(). */
    fun refresh() {
        detach()
        stopping = false
        settings = null
        if (!hosted) return
        val loaded = plugin.lifestealConfigManager.settings
        if (loaded == null) {
            plugin.logger.severe("[CombatLog] lifesteal.yml is invalid, so Lifesteal combat-log recording is off (the server-wide combat tag still works).")
            return
        }
        val combat = loaded.combatLogging
        if (!combat.enabled) {
            plugin.logger.info("[CombatLog] combat-logging.enabled is false: the plain server-wide combat-log behaviour applies on Lifesteal.")
            return
        }
        try {
            store.createTables()
            tablesReady = true
        } catch (e: Exception) {
            tablesReady = false
            plugin.logger.severe("[CombatLog] Could not create the combat-log table, so Lifesteal combat-log recording is off: ${e.message}")
            return
        }
        settings = combat
        plugin.combatManager.tagObserver = this
        val cutoff = System.currentTimeMillis() - combat.retentionDays * DAY_MILLIS
        plugin.databaseManager.async("lifesteal combat-log retention") {
            val removed = store.purgeBefore(cutoff)
            if (removed > 0) plugin.logger.info("[CombatLog] Deleted $removed combat-log incident(s) older than ${combat.retentionDays} days.")
        }
        // One sweep task per refresh (detach() cancelled the previous one): drops any session whose
        // tag ended without a callback, so nothing can pile up.
        sweepTask = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            sessions.sweep(System.currentTimeMillis(), SWEEP_GRACE_MILLIS)
        }, SWEEP_TICKS, SWEEP_TICKS)
        plugin.logger.info(
            "[CombatLog] Lifesteal combat logging on: tag ${plugin.combatManager.tagDurationMillis / 1000}s (config.yml combat.tag-duration-seconds), " +
                "heart penalty ${combat.heartPenalty.id}, NPC ${if (combat.leaveNpc) "on" else "off"}, timeouts ${if (combat.countTimeouts) "counted" else "not counted"}."
        )
    }

    /** Shutdown: no new incidents or decisions from here on (already-queued writes still finish). */
    fun stop() {
        stopping = true
        detach()
    }

    private fun detach() {
        if (plugin.combatManager.tagObserver === this) plugin.combatManager.tagObserver = null
        if (sweepTask != -1) {
            plugin.server.scheduler.cancelTask(sweepTask)
            sweepTask = -1
        }
        sessions.clear()
        pendingKicks.clear()
    }

    // ── Events (from LifestealCombatListener) ───────────────

    /** PlayerKickEvent at MONITOR (not cancelled): remembered until the quit that follows. */
    fun onKick(player: Player, cause: String) {
        if (!active) return
        pendingKicks[player.uniqueId] = cause
    }

    /** PlayerQuitEvent at MONITOR, after CombatListener handled the quit. */
    fun onQuitCleanup(uuid: UUID) {
        pendingKicks.remove(uuid)
        sessions.end(uuid)
    }

    // ── CombatManager.TagObserver ───────────────────────────

    override fun onPvpTag(attacker: Player, victim: Player, expiresAt: Long) {
        val combat = settings ?: return
        if (stopping || !isLifesteal(victim) || !isLifesteal(attacker)) return
        val started = sessions.hit(attacker.uniqueId, victim.uniqueId, System.currentTimeMillis(), expiresAt)
        if (!combat.notifications) return
        for (player in listOf(attacker, victim)) {
            if (player.uniqueId in started) {
                plugin.commsManager.send(player, plugin.commsManager.parseLegacy(combat.messages.tagged), CommunicationsManager.Category.COMBAT)
            }
        }
    }

    override fun onTagExpired(player: Player) {
        val combat = settings ?: return
        val ended = sessions.end(player.uniqueId) ?: return
        if (combat.notifications && ended.opponents.isNotEmpty() && isLifesteal(player)) {
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy(combat.messages.expired), CommunicationsManager.Category.COMBAT)
        }
    }

    override fun onTagCleared(uuid: UUID) {
        sessions.end(uuid)
    }

    override fun onTaggedQuit(player: Player, reason: PlayerQuitEvent.QuitReason): Boolean {
        val combat = settings ?: return true
        if (!isLifesteal(player)) return true
        val uuid = player.uniqueId
        val now = System.currentTimeMillis()
        val kickCause = pendingKicks.remove(uuid)
        val shuttingDown = stopping || Bukkit.isStopping()
        val transferPending = try { plugin.transferManager.isTransferPending(uuid) } catch (_: Exception) { false }
        val kind = CombatLogRules.classify(reason.name, kickCause, shuttingDown, transferPending)
        val decision = CombatLogRules.enforcement(kind, combat)

        val session = sessions.end(uuid)
        val expiresAt = plugin.combatManager.tagExpiresAt(uuid) ?: now
        val snapshot = session ?: CombatSessions.Snapshot(0, now, now, expiresAt, emptyList())
        // A session-less tag (e.g. tagged before a reload) still gets one incident per tag expiry.
        val dedupe = CombatLogRules.dedupeKey(bootId, uuid, if (session != null) snapshot.id else -expiresAt)
        val incident = CombatLogStore.Incident(
            id = UUID.randomUUID().toString(),
            dedupeKey = dedupe,
            player = uuid,
            opponents = snapshot.opponents,
            serverId = plugin.networkIdentityManager.serverId,
            combatStartedAt = snapshot.startedAt,
            lastHitAt = snapshot.lastHitAt,
            tagExpiresAt = maxOf(snapshot.expiresAt, expiresAt),
            disconnectedAt = now,
            kind = kind,
            quitReason = reason.name,
            kickCause = kickCause,
            counted = decision.counted,
            status = decision.status,
            npcSpawned = decision.spawnNpc,
            heartTransactionId = null,
        )
        // Also during a shutdown (exempt, for the record); a closed database logs it as not saved.
        write(incident, player.name)
        if (combat.diagnosticLogging) {
            plugin.logger.info(
                "[CombatLog] ${player.name} ($uuid) left while tagged: ${kind.label}" + (kickCause?.let { " [$it]" } ?: "") +
                    " -> ${decision.status.label}; NPC ${if (decision.spawnNpc) "yes" else "no"}; incident ${incident.id.substring(0, 8)}."
            )
        }
        return decision.spawnNpc
    }

    private fun write(incident: CombatLogStore.Incident, name: String) {
        plugin.databaseManager.async("lifesteal combat-log incident") {
            try {
                store.record(incident)
            } catch (e: Exception) {
                val failure = Failure(System.currentTimeMillis(), incident.id, incident.player, e.message ?: e.javaClass.simpleName)
                synchronized(failures) {
                    failures.addLast(failure)
                    while (failures.size > MAX_FAILURES) failures.removeFirst()
                }
                plugin.logger.warning("[CombatLog] Could not save combat-log incident ${incident.id.substring(0, 8)} for $name: ${failure.message}")
            }
        }
    }

    // ── Diagnostics (/lifesteal combat) ─────────────────────

    data class Status(val tagExpiresAt: Long?, val session: CombatSessions.Snapshot?)

    fun status(uuid: UUID): Status = Status(plugin.combatManager.tagExpiresAt(uuid), sessions.get(uuid)?.snapshot())

    fun recent(limit: Int, player: UUID?): List<CombatLogStore.Incident> = store.recent(limit, player)
    fun incident(prefix: String): List<CombatLogStore.Incident> = store.byIdPrefix(prefix)
    fun countedSince(player: UUID, since: Long): Int = store.countFor(player, since)
    fun failures(): List<Failure> = synchronized(failures) { failures.toList() }
    val trackedSessions: Int get() = sessions.size

    companion object {
        private const val DAY_MILLIS = 86_400_000L
        private const val SWEEP_TICKS = 20L * 30
        private const val SWEEP_GRACE_MILLIS = 5_000L
        const val MAX_FAILURES = 50
    }
}
