package com.liam.joshymc.lifesteal.ranked

/**
 * A Ranked Team Wars arena (issue #1361), Bukkit-free and immutable. The world is stored by name
 * (never a live World), points are plain coordinates, so arenas can be validated, stored and
 * tested without a server and validating one never loads a world or a chunk.
 *
 * Runtime availability (RESERVED / ACTIVE / RESETTING) is never stored: it comes from the match
 * that currently holds the arena ([RankedArenaRules.availability]), so a restart can't leave an
 * arena "occupied" by a match that no longer exists.
 */
data class RankedArena(
    val id: String,
    val displayName: String,
    val world: String,
    val bounds: Box?,
    val teamA: List<Point> = emptyList(),
    val teamB: List<Point> = emptyList(),
    val spectators: List<Point> = emptyList(),
    /** Players per side this arena may host (2 = 2v2 ... 5 = 5v5). */
    val sizes: Set<Int> = RankedArenaRules.ALL_SIZES,
    val enabled: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val version: Long = 1L,
) {
    fun spawns(side: Side): List<Point> = if (side == Side.A) teamA else teamB

    /** The spawn of the [index]th player of [side]: players share spawns round-robin when there are fewer spawns than players. */
    fun spawnFor(side: Side, index: Int): Point? = spawns(side).takeIf { it.isNotEmpty() }?.let { it[index % it.size] }

    fun withSpawns(side: Side, points: List<Point>): RankedArena = if (side == Side.A) copy(teamA = points) else copy(teamB = points)
}

enum class Side(val label: String) {
    A("Team A"),
    B("Team B");

    val other: Side get() = if (this == A) B else A

    companion object {
        fun parse(raw: String): Side? = when (raw.lowercase()) {
            "a", "1", "teama", "team_a" -> A
            "b", "2", "teamb", "team_b" -> B
            else -> null
        }
    }
}

/** A position inside a world (block-centred or exact, with facing). */
data class Point(val x: Double, val y: Double, val z: Double, val yaw: Float = 0f, val pitch: Float = 0f) {
    val blockX: Int get() = kotlin.math.floor(x).toInt()
    val blockY: Int get() = kotlin.math.floor(y).toInt()
    val blockZ: Int get() = kotlin.math.floor(z).toInt()

    fun encode(): String = "${fmt(x)},${fmt(y)},${fmt(z)},${fmt(yaw.toDouble())},${fmt(pitch.toDouble())}"

    private fun fmt(v: Double): String = if (v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    companion object {
        /** Reads one encoded point; null for anything malformed or non-finite. */
        fun decode(raw: String): Point? {
            val parts = raw.split(',')
            if (parts.size != 5) return null
            val n = parts.map { it.trim().toDoubleOrNull() ?: return null }
            if (n.any { !it.isFinite() }) return null
            return Point(n[0], n[1], n[2], n[3].toFloat(), n[4].toFloat())
        }

        fun encodeList(points: List<Point>): String = points.joinToString(";") { it.encode() }

        /** Null when any entry is malformed (the whole arena is then INVALID, never silently shortened). */
        fun decodeList(raw: String?): List<Point>? {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(';').map { decode(it) ?: return null }
        }
    }
}

/** An axis-aligned block box, inclusive on both ends (the corners are blocks). */
data class Box(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    /** Any position inside the blocks of the box. */
    fun contains(x: Double, y: Double, z: Double): Boolean =
        x >= minX && x < maxX + 1.0 && y >= minY && y < maxY + 1.0 && z >= minZ && z < maxZ + 1.0

    fun contains(p: Point): Boolean = contains(p.x, p.y, p.z)

    fun overlaps(o: Box): Boolean =
        minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ

    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1

    fun describe(): String = "($minX, $minY, $minZ) to ($maxX, $maxY, $maxZ), ${sizeX}x${sizeY}x${sizeZ}"

    companion object {
        fun of(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
            Box(minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))
    }
}

/** How an arena can be used right now (issue #1361). Only [AVAILABLE] arenas are ever picked for a match. */
enum class ArenaAvailability(val label: String) {
    AVAILABLE("Available"),
    /** Held by a match that is being created, validated or prepared. */
    RESERVED("Reserved"),
    /** A match is counting down or fighting in it. */
    ACTIVE("Active"),
    /** A match finished and its players/entities are being cleaned up. */
    RESETTING("Resetting"),
    /** Disabled by staff, or not ready (incomplete/invalid/world not loaded). */
    DISABLED("Disabled"),
}

enum class ArenaReadiness { READY, INCOMPLETE, INVALID }

object RankedArenaRules {

    val ALL_SIZES: Set<Int> = setOf(2, 3, 4, 5)
    const val MIN_SIZE = 2
    const val MAX_SIZE = 5
    /** Spawn points per side and spectator points; more than one per player is never needed. */
    const val MAX_POINTS = 5
    const val MAX_NAME_LENGTH = 32
    /** Smallest arena side in blocks: two players must fit, and the boundary check needs room. */
    const val MIN_BOX_SIDE = 5
    /** Largest arena side in blocks: keeps entity cleanup and boundary checks bounded. */
    const val MAX_BOX_SIDE = 512

    private val ID = Regex("[a-z0-9_-]{1,32}")

    fun validId(id: String): Boolean = ID.matches(id)

    fun canonicalId(raw: String): String = raw.trim().lowercase()

    /** Strips control characters and section signs; blank or too long = null. */
    fun cleanName(raw: String): String? {
        val clean = raw.filter { !it.isISOControl() && it != '§' }.trim()
        return clean.takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
    }

    /** Parses `2,3,5` / `2 3` / `2v2,3v3` / `all`; null on anything outside 2..5 or empty. */
    fun parseSizes(raw: String): Set<Int>? {
        if (raw.trim().equals("all", ignoreCase = true)) return ALL_SIZES
        val parts = raw.split(',', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val sizes = parts.map { p -> parseSize(p) ?: return null }
        return sizes.toSortedSet()
    }

    /** `3` or `3v3`; null outside 2..5. */
    fun parseSize(raw: String): Int? {
        val p = raw.trim().lowercase()
        val n = if ('v' in p) {
            val (a, b) = p.split('v', limit = 2)
            if (a != b) return null
            a.toIntOrNull()
        } else p.toIntOrNull()
        return n?.takeIf { it in MIN_SIZE..MAX_SIZE }
    }

    data class Validation(val readiness: ArenaReadiness, val problems: List<String>) {
        val ready: Boolean get() = readiness == ArenaReadiness.READY
    }

    /**
     * Whether [arena] is usable. [worldProblem] is null when its world is loaded and a Lifesteal
     * world (answered from the loaded world list only: validating never loads a world). [others] are the other stored arenas: an enabled arena may not overlap another enabled
     * arena in the same world, so a fight can never reach an unrelated arena. [serverSizes] are the
     * sizes lifesteal.yml allows.
     */
    fun validate(arena: RankedArena, worldProblem: String?, others: Collection<RankedArena>, serverSizes: Collection<Int>): Validation {
        val invalid = mutableListOf<String>()
        val missing = mutableListOf<String>()
        if (!validId(arena.id)) invalid += "the id '${arena.id}' isn't valid (a-z, 0-9, _ and -, up to 32)"
        if (cleanName(arena.displayName) == null) invalid += "the display name is blank or too long"
        if (arena.world.isBlank()) invalid += "no world is set"
        else if (worldProblem != null) invalid += worldProblem
        val box = arena.bounds
        if (box == null) missing += "the boundary isn't set (setcorner 1 and 2)"
        else {
            if (minOf(box.sizeX, box.sizeZ) < MIN_BOX_SIDE || box.sizeY < 3) invalid += "the boundary is too small (at least ${MIN_BOX_SIDE}x3x$MIN_BOX_SIDE blocks)"
            if (maxOf(box.sizeX, box.sizeY, box.sizeZ) > MAX_BOX_SIDE) invalid += "the boundary is too large (at most $MAX_BOX_SIDE blocks per side)"
        }
        for (side in Side.entries) {
            val spawns = arena.spawns(side)
            if (spawns.isEmpty()) missing += "${side.label} has no spawn"
            if (spawns.size > MAX_POINTS) invalid += "${side.label} has more than $MAX_POINTS spawns"
            if (box != null && spawns.any { !box.contains(it) }) invalid += "a ${side.label} spawn is outside the boundary"
        }
        if (arena.spectators.size > MAX_POINTS) invalid += "more than $MAX_POINTS spectator points"
        // Spectators watch from outside: inside the boundary they would be in the fight.
        if (box != null && arena.spectators.any { box.contains(it) }) invalid += "a spectator point is inside the boundary (spectators watch from outside it)"
        if (arena.sizes.isEmpty()) invalid += "no match size is allowed"
        if (arena.sizes.any { it !in MIN_SIZE..MAX_SIZE }) invalid += "match sizes must be 2..5"
        if (arena.sizes.isNotEmpty() && arena.sizes.none { it in serverSizes }) invalid += "none of its sizes (${arena.sizes.sorted().joinToString()}) is allowed by lifesteal.yml ranked-team-wars.team-sizes"
        if (box != null) {
            val clash = others.firstOrNull { it.id != arena.id && it.enabled && it.world == arena.world && it.bounds?.overlaps(box) == true }
            if (clash != null) invalid += "the boundary overlaps the enabled arena '${clash.id}'"
        }
        val readiness = when {
            invalid.isNotEmpty() -> ArenaReadiness.INVALID
            missing.isNotEmpty() -> ArenaReadiness.INCOMPLETE
            else -> ArenaReadiness.READY
        }
        return Validation(readiness, invalid + missing)
    }

    /** Runtime availability: disabled/not ready wins, then the state of the match holding it. */
    fun availability(arena: RankedArena, ready: Boolean, holder: MatchState?): ArenaAvailability = when {
        holder != null && !holder.terminal -> when (holder) {
            MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING -> ArenaAvailability.RESERVED
            MatchState.COUNTDOWN, MatchState.ACTIVE -> ArenaAvailability.ACTIVE
            else -> ArenaAvailability.RESETTING
        }
        !arena.enabled || !ready -> ArenaAvailability.DISABLED
        else -> ArenaAvailability.AVAILABLE
    }
}
