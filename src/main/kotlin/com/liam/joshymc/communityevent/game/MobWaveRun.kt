package com.liam.joshymc.communityevent.game

import java.util.UUID

/**
 * Where a Mob Waves run is (issue #1192). It lives inside a RUNNING session and never replaces
 * the session's own state: COUNTDOWN -> ACTIVE (wave N) -> INTERMISSION -> ACTIVE (wave N+1)
 * ... -> VICTORY, or DEFEAT from any point once nobody is left alive.
 */
enum class WavePhase(val label: String) {
    COUNTDOWN("Countdown"),
    ACTIVE("Wave in progress"),
    INTERMISSION("Intermission"),
    VICTORY("Victory"),
    DEFEAT("Defeat");

    val isOver: Boolean get() = this == VICTORY || this == DEFEAT
}

/** A mob of the current wave still to be spawned: its group index and how often a spawn spot couldn't be found. */
data class PendingSpawn(val group: Int, val attempts: Int = 0)

/**
 * Wave bookkeeping for Mob Waves: the phase, the wave number, the mobs still to spawn, the
 * event mobs alive (by UUID) and how each wave's mobs were resolved. Plain Kotlin with no
 * Bukkit types; [MobWavesGame] drives it from the main thread.
 *
 * Every transition is checked here, so a second "next wave", a late death event or a stale
 * GUI click can't start two waves, complete one twice or count an unrelated mob.
 */
class MobWaveRun(val totalWaves: Int, private val maxSpawnAttempts: Int = 40) {
    var phase: WavePhase = WavePhase.COUNTDOWN
        private set
    /** The current (or last finished) wave; 0 before wave 1. */
    var wave: Int = 0
        private set
    var paused: Boolean = false
        private set

    private val queue = ArrayDeque<PendingSpawn>()
    private val alive = LinkedHashSet<UUID>()

    /** Mobs of this wave in total, killed by players, removed some other way, and given up on (no safe spot). */
    var waveSize: Int = 0
        private set
    var killed: Int = 0
        private set
    var lost: Int = 0
        private set
    var skipped: Int = 0
        private set
    var totalKilled: Int = 0
        private set

    val activeMobs: Int get() = alive.size
    val pendingSpawns: Int get() = queue.size
    /** Mobs that still have to be dealt with this wave. Never negative. */
    val remaining: Int get() = queue.size + alive.size
    val trackedMobs: List<UUID> get() = alive.toList()

    fun isTracked(uuid: UUID): Boolean = uuid in alive

    /** Every spawned mob is dead or gone and nothing is left to spawn. */
    val isCleared: Boolean get() = phase == WavePhase.ACTIVE && queue.isEmpty() && alive.isEmpty()

    /**
     * Starts wave [number] with [spawns] (one entry per mob). Only the wave right after the
     * current one, only from COUNTDOWN or INTERMISSION, never while paused or with mobs still
     * tracked, and never past the last wave.
     */
    fun beginWave(number: Int, spawns: List<PendingSpawn>): Boolean {
        if (paused || (phase != WavePhase.COUNTDOWN && phase != WavePhase.INTERMISSION)) return false
        if (number != wave + 1 || number > totalWaves || spawns.isEmpty() || alive.isNotEmpty()) return false
        wave = number
        queue.clear()
        queue.addAll(spawns)
        waveSize = spawns.size
        killed = 0; lost = 0; skipped = 0
        phase = WavePhase.ACTIVE
        return true
    }

    /**
     * The mobs to spawn now: at most [batchSize], and only as many as keep the alive count at
     * or under [maxActive]. Nothing while paused or outside an active wave.
     */
    fun nextBatch(batchSize: Int, maxActive: Int): List<PendingSpawn> {
        if (phase != WavePhase.ACTIVE || paused) return emptyList()
        val room = (maxActive - alive.size).coerceAtLeast(0)
        val n = minOf(batchSize.coerceAtLeast(0), room, queue.size)
        return List(n) { queue.removeFirst() }
    }

    /** A mob of this wave entered the world. False (and nothing tracked) if the wave isn't active. */
    fun spawned(uuid: UUID): Boolean {
        if (phase != WavePhase.ACTIVE) return false
        return alive.add(uuid)
    }

    /**
     * No safe spot was found for [entry]: it goes back to the front of the queue, or is given up
     * on after too many tries so a blocked arena can never spawn forever. True if given up.
     */
    fun retry(entry: PendingSpawn): Boolean {
        if (phase != WavePhase.ACTIVE) return false
        val next = entry.copy(attempts = entry.attempts + 1)
        if (next.attempts >= maxSpawnAttempts) { skipped++; return true }
        queue.addFirst(next)
        return false
    }

    /**
     * An event mob died ([killed]) or disappeared. True only the first time for a tracked mob;
     * unrelated mobs and repeats are ignored, so counts never go negative.
     */
    fun resolve(uuid: UUID, killed: Boolean): Boolean {
        if (!alive.remove(uuid)) return false
        if (killed) { this.killed++; totalKilled++ } else lost++
        return true
    }

    /**
     * Ends the cleared wave: INTERMISSION, or VICTORY after the last one. Returns the new phase,
     * or null if the wave isn't cleared (or was already completed), so it can't run twice.
     */
    fun completeWave(): WavePhase? {
        if (!isCleared || paused) return null
        phase = if (wave >= totalWaves) WavePhase.VICTORY else WavePhase.INTERMISSION
        return phase
    }

    /** Everyone is out. Stops all spawning. False if the run is already over. */
    fun defeat(): Boolean {
        if (phase.isOver) return false
        phase = WavePhase.DEFEAT
        queue.clear()
        paused = false
        return true
    }

    fun pause(): Boolean {
        if (paused || (phase != WavePhase.ACTIVE && phase != WavePhase.INTERMISSION)) return false
        paused = true
        return true
    }

    fun resume(): Boolean {
        if (!paused || phase.isOver) return false
        paused = false
        return true
    }

    /** Forgets every tracked mob (they're being removed) and returns them. */
    fun releaseAll(): List<UUID> {
        val all = alive.toList()
        alive.clear()
        queue.clear()
        return all
    }
}
