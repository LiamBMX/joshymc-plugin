package com.liam.joshymc.recovery

import kotlin.math.sqrt

/**
 * The Bukkit-free decisions behind stuck-player recovery (issue #1241). [StuckRecoveryManager]
 * gathers the facts from the server and asks these rules what to do, so every threshold that
 * decides "is this player really stuck?" lives in one place.
 */

/** A world position without a live Bukkit world, safe to keep after quit and to read off the main thread. */
data class Spot(val world: String, val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f) {
    fun distanceTo(world: String, x: Double, y: Double, z: Double): Double {
        if (world != this.world) return Double.POSITIVE_INFINITY
        val dx = x - this.x
        val dy = y - this.y
        val dz = z - this.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * What a player is doing that legitimately keeps them off the ground. Any of these means
 * airtime is normal movement and never a softlock: falling is handled separately by
 * [StuckRules.isSuspended] (a falling player moves).
 */
data class MovementState(
    val canFly: Boolean,       // creative, spectator, allowFlight or flying
    val gliding: Boolean,      // elytra
    val riptiding: Boolean,
    val levitating: Boolean,   // levitation or slow falling
    val inVehicle: Boolean,
    val inLiquid: Boolean,     // swimming, water, lava, bubble columns
    val climbing: Boolean,     // ladders, vines, scaffolding, cobwebs: anything not plain air at feet/head
    val sleepingOrDead: Boolean,
) {
    fun explainsAirtime(): Boolean =
        canFly || gliding || riptiding || levitating || inVehicle || inLiquid || climbing || sleepingOrDead
}

/** One look at the player during the /unstuck check. [supported] = blocks or a hard entity right under their feet. */
data class MotionSample(val x: Double, val y: Double, val z: Double, val supported: Boolean, val state: MovementState)

/**
 * Kick-grace bookkeeping for one player. An *incident* is a burst of "Flying is not enabled"
 * kicks that were cancelled because the client never got the chunk it stands in: one incident
 * keeps cancelling for [incidentMs], and only [maxIncidents] incidents start per [windowMs], so a
 * client that keeps "floating" can't stay exempt from the kick forever.
 */
class KickGrace(private val incidentMs: Long, private val maxIncidents: Int, private val windowMs: Long) {

    enum class Decision { NEW_INCIDENT, CONTINUE, LIMIT_REACHED }

    private val incidentStarts = ArrayDeque<Long>()
    private var currentStart = -1L

    fun onFlyingKick(now: Long): Decision {
        if (currentStart >= 0 && now - currentStart <= incidentMs) return Decision.CONTINUE
        currentStart = -1L
        prune(now)
        if (incidentStarts.size >= maxIncidents) return Decision.LIMIT_REACHED
        incidentStarts.addLast(now)
        currentStart = now
        return Decision.NEW_INCIDENT
    }

    /** When the latest incident started, or null when none is left in the window. */
    fun lastIncidentAt(now: Long): Long? {
        prune(now)
        return incidentStarts.lastOrNull()
    }

    fun isStale(now: Long): Boolean = lastIncidentAt(now) == null

    private fun prune(now: Long) {
        while (incidentStarts.isNotEmpty() && now - incidentStarts.first() > windowMs) incidentStarts.removeFirst()
    }
}

/**
 * A flying kick that was not cancelled (or an admin's request), remembered so the player's next
 * login can start somewhere safe instead of the same midair spot. [kickedAt] is null for an
 * admin-queued recovery, which applies wherever the player logs in (event world excepted).
 */
data class LoginRecovery(
    val kickedAt: Spot?,
    val destination: Spot,
    val createdAt: Long,
    val attempt: Int,
    val byAdmin: Boolean,
)

object StuckRules {

    /** How far a "frozen" player may drift during the /unstuck check (server positions don't drift when the client is frozen). */
    const val MAX_DRIFT = 0.05

    /** How close the login spawn must be to the recorded kick spot for the login recovery to apply. */
    const val LOGIN_MATCH_DISTANCE = 8.0

    /** Login recoveries per player in a row before the plugin stops moving them and leaves it to staff. */
    const val MAX_LOGIN_ATTEMPTS = 2

    /**
     * True only when every sample shows the player hanging in plain air, with nothing under them,
     * nothing that explains the airtime, and not moving. A player who is falling, jumping, knocked
     * back, pearling or standing on anything fails at least one sample.
     */
    fun isSuspended(samples: List<MotionSample>): Boolean {
        if (samples.size < 2) return false
        val first = samples.first()
        return samples.all { s ->
            !s.supported && !s.state.explainsAirtime() &&
                distance(s.x, s.y, s.z, first.x, first.y, first.z) <= MAX_DRIFT
        }
    }

    /**
     * The login recovery to apply for a player about to spawn at [spawn], or null. A kick
     * record only applies while fresh and when the player would spawn at (or right by) the spot
     * they were kicked from; nothing applies to an event-world spawn, which the event system's
     * own inventory handling owns.
     */
    fun loginRecoveryFor(record: LoginRecovery?, spawn: Spot, eventWorld: String, now: Long, windowMs: Long): LoginRecovery? {
        if (record == null) return null
        if (spawn.world == eventWorld || record.destination.world == eventWorld) return null
        if (now - record.createdAt > windowMs) return null
        if (record.byAdmin) return record
        val kicked = record.kickedAt ?: return null
        if (kicked.distanceTo(spawn.world, spawn.x, spawn.y, spawn.z) > LOGIN_MATCH_DISTANCE) return null
        return record
    }

    /**
     * The attempt number for a new flying-kick record, given the previous one (still pending or
     * already applied). A kick within the window near the previous kick spot or near where the
     * last recovery put them counts up, so a spot that keeps failing escalates instead of looping;
     * anything else starts over at 1.
     */
    fun nextAttempt(previous: LoginRecovery?, kickedAt: Spot, now: Long, windowMs: Long): Int {
        if (previous == null || previous.byAdmin) return 1
        if (now - previous.createdAt > windowMs) return 1
        val near = listOfNotNull(previous.kickedAt, previous.destination).any {
            it.distanceTo(kickedAt.world, kickedAt.x, kickedAt.y, kickedAt.z) <= LOGIN_MATCH_DISTANCE * 4
        }
        return if (near) previous.attempt + 1 else 1
    }

    private fun distance(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
