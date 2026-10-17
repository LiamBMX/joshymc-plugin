package com.liam.joshymc.communityevent.arena

import kotlin.math.abs

/** Build height of a loaded world: [minY] inclusive, [maxY] exclusive (Bukkit's minHeight/maxHeight). */
data class WorldBounds(val minY: Int, val maxY: Int)

/**
 * Readiness of a saved arena (issue #1183).
 * READY: every required setting is present and valid. INCOMPLETE: something required is missing.
 * INVALID: a stored value points at a missing world, bad coordinates or an impossible region.
 */
enum class ArenaStatus { READY, INCOMPLETE, INVALID }

data class ArenaReport(val status: ArenaStatus, val missing: List<ArenaField>, val problems: List<String>) {
    val isReady: Boolean get() = status == ArenaStatus.READY

    /** One line for chat and GUI lore, e.g. "INCOMPLETE: missing Floor Region". */
    fun summary(): String = when (status) {
        ArenaStatus.READY -> "READY"
        ArenaStatus.INCOMPLETE -> "INCOMPLETE: missing ${missing.joinToString(", ") { it.label }}"
        ArenaStatus.INVALID -> "INVALID: ${problems.first()}"
    }
}

/**
 * Checks arena values without touching the world: nothing here loads a world or a chunk.
 * [worldBounds] returns null for a world that isn't loaded, and [eventWorld] is the current
 * event world, the only world arenas may use (so event-world inventory separation always applies).
 */
object ArenaValidator {
    /** Bukkit's world border limit. */
    const val MAX_COORDINATE = 29_999_984.0
    /** Largest side of any saved region, in blocks. */
    const val MAX_REGION_SPAN = 256
    /** Largest saved region, in blocks. */
    const val MAX_REGION_VOLUME = 2_000_000L
    const val MAX_MOB_SPAWNS = 32
    /** Two competitor spawns closer than this (in blocks) would put players inside each other. */
    const val MIN_SPAWN_SEPARATION = 1.5

    /** Problem with a position, or null when it's usable. */
    fun checkPoint(point: ArenaPoint, arenaWorld: String, bounds: WorldBounds?): String? {
        if (point.world != arenaWorld) return "is in world '${point.world}', not the arena's world '$arenaWorld'"
        if (!point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite() ||
            !point.yaw.isFinite() || !point.pitch.isFinite()
        ) return "has invalid coordinates"
        if (abs(point.x) > MAX_COORDINATE || abs(point.z) > MAX_COORDINATE) return "is outside the world border"
        if (point.pitch < -90f || point.pitch > 90f) return "has an invalid pitch"
        if (bounds != null && (point.blockY < bounds.minY || point.blockY >= bounds.maxY - 1)) {
            return "is outside the world's build height (${bounds.minY} to ${bounds.maxY - 1})"
        }
        return null
    }

    /** Problem with a region, or null when it's usable. */
    fun checkRegion(region: ArenaRegion, arenaWorld: String, bounds: WorldBounds?): String? {
        if (region.world != arenaWorld) return "is in world '${region.world}', not the arena's world '$arenaWorld'"
        if (region.minX > region.maxX || region.minY > region.maxY || region.minZ > region.maxZ) return "has its corners reversed"
        if (abs(region.minX.toDouble()) > MAX_COORDINATE || abs(region.maxX.toDouble()) > MAX_COORDINATE ||
            abs(region.minZ.toDouble()) > MAX_COORDINATE || abs(region.maxZ.toDouble()) > MAX_COORDINATE
        ) return "is outside the world border"
        if (region.sizeX > MAX_REGION_SPAN || region.sizeY > MAX_REGION_SPAN || region.sizeZ > MAX_REGION_SPAN) {
            return "is ${region.sizeX}x${region.sizeY}x${region.sizeZ}; each side can be at most $MAX_REGION_SPAN blocks"
        }
        if (region.volume > MAX_REGION_VOLUME) return "is ${region.volume} blocks; the limit is $MAX_REGION_VOLUME"
        if (bounds != null && (region.minY < bounds.minY || region.maxY >= bounds.maxY)) {
            return "is outside the world's build height (${bounds.minY} to ${bounds.maxY - 1})"
        }
        return null
    }

    /** Elimination heights may sit up to 64 blocks under the world, for arenas over the void. */
    fun checkHeight(y: Int, bounds: WorldBounds?): String? {
        val min = bounds?.minY?.minus(64) ?: -2048
        val max = bounds?.maxY ?: 2048
        if (y < min || y > max) return "Y $y is outside the world ($min to $max)"
        return null
    }

    fun report(arena: EventArena, eventWorld: String, worldBounds: (String) -> WorldBounds?): ArenaReport {
        val layout = arena.layout
        val problems = mutableListOf<String>()
        val bounds = worldBounds(arena.world)
        if (arena.world != eventWorld) problems += "World '${arena.world}' is not the event world ('$eventWorld')"
        if (bounds == null) problems += "World '${arena.world}' is not loaded or doesn't exist"

        for ((field, value) in arena.values) {
            if (!layout.allows(field)) { problems += "${field.label} doesn't belong to a ${arena.type.displayName} arena"; continue }
            if (!value.matches(field.kind)) { problems += "${field.label} has the wrong kind of value"; continue }
            when (value) {
                is ArenaValue.Point -> checkPoint(value.point, arena.world, bounds)?.let { problems += "${field.label} $it" }
                is ArenaValue.Points -> {
                    if (value.points.size > MAX_MOB_SPAWNS) problems += "${field.label} has ${value.points.size} points; the limit is $MAX_MOB_SPAWNS"
                    value.points.forEachIndexed { i, p ->
                        checkPoint(p, arena.world, bounds)?.let { problems += "${field.label} #${i + 1} $it" }
                    }
                }
                is ArenaValue.Region -> checkRegion(value.region, arena.world, bounds)?.let { problems += "${field.label} $it" }
                is ArenaValue.Height -> checkHeight(value.y, bounds)?.let { problems += "${field.label}: $it" }
            }
        }
        problems += crossChecks(arena)

        val missing = arena.missing
        val status = when {
            problems.isNotEmpty() -> ArenaStatus.INVALID
            missing.isNotEmpty() -> ArenaStatus.INCOMPLETE
            else -> ArenaStatus.READY
        }
        return ArenaReport(status, missing, problems)
    }

    /** Checks between settings: spawns above the elimination height, inside the boundary, apart from each other. */
    private fun crossChecks(arena: EventArena): List<String> {
        val problems = mutableListOf<String>()
        val lose = arena.height(ArenaField.LOSE_HEIGHT)
        val boundary = arena.region(ArenaField.REGION)

        val s1 = arena.point(ArenaField.SPAWN_1)
        val s2 = arena.point(ArenaField.SPAWN_2)
        if (s1 != null && s2 != null && s1.distanceSquared(s2) < MIN_SPAWN_SEPARATION * MIN_SPAWN_SEPARATION) {
            problems += "Competitor spawns 1 and 2 are on top of each other"
        }

        if (lose != null) {
            for (field in listOf(ArenaField.SPAWN, ArenaField.SPAWN_1, ArenaField.SPAWN_2)) {
                val p = arena.point(field) ?: continue
                if (p.blockY < lose) problems += "${field.label} is below the elimination height (Y $lose)"
            }
            arena.region(ArenaField.FLOOR)?.let { floor ->
                if (floor.minY <= lose) problems += "Floor Region reaches down to the elimination height (Y $lose); set it below the floor"
            }
        }

        if (boundary != null) {
            for (field in listOf(ArenaField.SPAWN, ArenaField.SPAWN_1, ArenaField.SPAWN_2)) {
                val p = arena.point(field) ?: continue
                if (!boundary.contains(p)) problems += "${field.label} is outside the Arena Boundary"
            }
            arena.points(ArenaField.MOB_SPAWNS).forEachIndexed { i, p ->
                if (!boundary.contains(p)) problems += "Mob spawn #${i + 1} is outside the Arena Boundary"
            }
        }
        return problems
    }
}
