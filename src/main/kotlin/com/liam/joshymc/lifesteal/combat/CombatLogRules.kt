package com.liam.joshymc.lifesteal.combat

import com.liam.joshymc.lifesteal.CombatLogHeartPenalty
import com.liam.joshymc.lifesteal.LifestealSettings
import java.util.UUID

/**
 * Lifesteal combat-logging rules (issue #1350), Bukkit-free.
 *
 * The combat tag itself stays the existing `CombatManager` tag (one framework, one duration). This
 * file decides what a disconnect while tagged *means* on Lifesteal: which disconnects count as a
 * combat log, which are the server's doing, and what (if anything) happens. Paper only reports a
 * quit reason (`DISCONNECTED`, `KICKED`, `TIMED_OUT`, `ERRONEOUS_STATE`) and, for kicks, the kick
 * cause; it can never tell an alt-F4 from a client crash or a network drop, so those count.
 */
object CombatLogRules {

    /** What a disconnect was, as far as the server can tell. */
    enum class DisconnectKind(val label: String) {
        /** The client closed the connection (quit button, alt-F4, crash, proxy dropped it). */
        QUIT("disconnected"),
        /** The connection stopped answering (crash, network drop, Bedrock/NetherNet interruption). */
        TIMED_OUT("timed out"),
        /** A kick the player can cause themselves (flying, spam, bad packets, idling, duplicate login, ...). */
        PLAYER_KICK("kicked (player-caused)"),
        /** The player left through a network transfer that was already on its way when they were tagged. */
        TRANSFER("server transfer"),
        /** Kicked by staff or a server rule only staff control (/kick, ban, whitelist, plugin kick). */
        STAFF_KICK("kicked by staff/server"),
        /** The server is stopping or restarting. */
        SERVER_SHUTDOWN("server shutdown"),
        /** The server dropped the connection after an internal error. */
        SERVER_ERROR("server error"),
    }

    /** Recorded outcome of an incident. */
    enum class PenaltyStatus(val label: String) {
        /** Counted as a combat log; the configured heart penalty is none (blueprint v1.1). */
        COUNTED_NO_HEART_PENALTY("combat log, no heart penalty (none approved)"),
        /** A timeout while `count-timeouts` is false: recorded, not counted. */
        NOT_COUNTED_TIMEOUT("not counted: timeout"),
        /** The server caused the disconnect: never counted. */
        EXEMPT_SERVER("not counted: server-caused"),
    }

    /**
     * Kick causes (Paper `PlayerKickEvent.Cause` names) only staff or the server itself can produce.
     * `PLUGIN` is here because every JoshyMC punishment and most plugin kicks use it; a player can't
     * pick it. Everything else (FLYING_PLAYER, SPAM, INVALID_*, IDLING, DUPLICATE_LOGIN,
     * RESOURCE_PACK_REJECTION, TIMEOUT, UNKNOWN, ...) is something a client can provoke, so it counts.
     */
    val STAFF_KICK_CAUSES = setOf("KICK_COMMAND", "BANNED", "IP_BANNED", "WHITELIST", "PLUGIN")
    /** Kick causes that mean the server is going down. */
    val SHUTDOWN_KICK_CAUSES = setOf("RESTART_COMMAND")

    /**
     * Classifies one disconnect. [quitReason] / [kickCause] are Paper enum names (null when unknown).
     * Fixed order: shutdown first (nobody is punished for a restart), then a transfer that was
     * already pending, then the kick cause, then the quit reason.
     */
    fun classify(quitReason: String?, kickCause: String?, serverStopping: Boolean, transferPending: Boolean): DisconnectKind {
        if (serverStopping) return DisconnectKind.SERVER_SHUTDOWN
        if (kickCause != null && kickCause in SHUTDOWN_KICK_CAUSES) return DisconnectKind.SERVER_SHUTDOWN
        if (transferPending) return DisconnectKind.TRANSFER
        if (kickCause != null) {
            return if (kickCause in STAFF_KICK_CAUSES) DisconnectKind.STAFF_KICK else DisconnectKind.PLAYER_KICK
        }
        return when (quitReason) {
            "TIMED_OUT" -> DisconnectKind.TIMED_OUT
            "ERRONEOUS_STATE" -> DisconnectKind.SERVER_ERROR
            // KICKED without a recorded kick event: we can't verify who kicked, so it counts.
            "KICKED" -> DisconnectKind.PLAYER_KICK
            else -> DisconnectKind.QUIT
        }
    }

    data class Enforcement(
        val counted: Boolean,
        val status: PenaltyStatus,
        /** Whether the existing combat-log NPC is left (and the player dies, unchanged behaviour). */
        val spawnNpc: Boolean,
        /** Hearts to take: always 0, no penalty is approved. Kept so a later approved penalty has one place. */
        val heartsToRemove: Int,
    )

    /** What happens for a disconnect of [kind] under [settings]. Never removes a heart (none approved). */
    fun enforcement(kind: DisconnectKind, settings: LifestealSettings.CombatLogging): Enforcement {
        val counted = when (kind) {
            DisconnectKind.QUIT, DisconnectKind.PLAYER_KICK, DisconnectKind.TRANSFER -> true
            DisconnectKind.TIMED_OUT -> settings.countTimeouts
            DisconnectKind.STAFF_KICK, DisconnectKind.SERVER_SHUTDOWN, DisconnectKind.SERVER_ERROR -> false
        }
        val status = when {
            counted -> PenaltyStatus.COUNTED_NO_HEART_PENALTY
            kind == DisconnectKind.TIMED_OUT -> PenaltyStatus.NOT_COUNTED_TIMEOUT
            else -> PenaltyStatus.EXEMPT_SERVER
        }
        val hearts = when (settings.heartPenalty) {
            CombatLogHeartPenalty.NONE -> 0
        }
        return Enforcement(counted, status, spawnNpc = counted && settings.leaveNpc, heartsToRemove = hearts)
    }

    /**
     * A player's heart count after a combat-log penalty of [penalty] hearts: never below [minimum]
     * (the 1-heart rule), never negative removal. Unused while no penalty is approved.
     */
    fun heartsAfterPenalty(current: Int, penalty: Int, minimum: Int): Int =
        if (current <= minimum) current else (current - penalty.coerceAtLeast(0)).coerceAtLeast(minimum)

    /** One incident per combat session per boot: duplicate quit events and retries find the same key. */
    fun dedupeKey(bootId: String, player: UUID, sessionId: Long): String = "combatlog:$bootId:$player:$sessionId"
}

/**
 * Lifesteal combat sessions by player UUID (issue #1350), Bukkit-free and main-thread only. The tag
 * itself (and its expiry) is `CombatManager`'s; this only remembers who the opponents were and
 * when the session began, so a combat-log incident can name them. Entries are removed when the tag
 * ends, the player dies or quits; [MAX_SESSIONS] and [MAX_OPPONENTS] bound it anyway.
 *
 * Team changes never touch it: leaving or disbanding a team keeps the session and its opponents.
 */
class CombatSessions {

    data class Session(
        /** Per-boot sequence number, part of the incident's dedupe key. */
        val id: Long,
        val startedAt: Long,
        var lastHitAt: Long,
        var expiresAt: Long,
        /** Opponent UUID -> last hit time, oldest first, at most [MAX_OPPONENTS]. */
        val opponents: LinkedHashMap<UUID, Long> = LinkedHashMap(),
    ) {
        fun snapshot(): Snapshot = Snapshot(id, startedAt, lastHitAt, expiresAt, opponents.keys.toList())
    }

    /** Immutable copy for an incident (no live references). */
    data class Snapshot(val id: Long, val startedAt: Long, val lastHitAt: Long, val expiresAt: Long, val opponents: List<UUID>)

    private val sessions = HashMap<UUID, Session>()
    private var nextId = 1L

    val size: Int get() = sessions.size

    /**
     * Records a hit between [attacker] and [victim] (both now tagged until [expiresAt]). Returns the
     * players whose session is new (they get the "now in combat" line once, not on every hit).
     */
    fun hit(attacker: UUID, victim: UUID, now: Long, expiresAt: Long): Set<UUID> {
        if (attacker == victim) return emptySet()
        val started = HashSet<UUID>(2)
        for ((self, other) in listOf(attacker to victim, victim to attacker)) {
            var session = sessions[self]
            if (session == null || session.expiresAt <= now) {
                if (session == null && sessions.size >= MAX_SESSIONS) evictOldest()
                session = Session(nextId++, now, now, expiresAt)
                sessions[self] = session
                started += self
            }
            session.lastHitAt = now
            session.expiresAt = maxOf(session.expiresAt, expiresAt)
            session.opponents.remove(other)
            session.opponents[other] = now
            while (session.opponents.size > MAX_OPPONENTS) session.opponents.remove(session.opponents.keys.first())
        }
        return started
    }

    fun get(uuid: UUID): Session? = sessions[uuid]

    /** Removes and returns [uuid]'s session (tag over, death, quit). */
    fun end(uuid: UUID): Snapshot? = sessions.remove(uuid)?.snapshot()

    /** Drops sessions that ran out more than [graceMillis] ago (a missed callback can't leak). */
    fun sweep(now: Long, graceMillis: Long): Int {
        val before = sessions.size
        sessions.entries.removeIf { it.value.expiresAt + graceMillis < now }
        return before - sessions.size
    }

    fun clear() = sessions.clear()

    private fun evictOldest() {
        val oldest = sessions.entries.minByOrNull { it.value.lastHitAt } ?: return
        sessions.remove(oldest.key)
    }

    companion object {
        const val MAX_OPPONENTS = 8
        const val MAX_SESSIONS = 2_000
    }
}
