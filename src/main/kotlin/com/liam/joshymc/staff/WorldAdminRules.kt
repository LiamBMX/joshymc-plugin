package com.liam.joshymc.staff

/**
 * Safeguards for the destructive world tools (issue #1353, audit findings D-M6 / P5-H1).
 * Bukkit-free.
 *
 * `/world delete <name>` deleted any loaded world that wasn't one of the three vanilla
 * defaults, in one step, with no audit row. `/gencave [radius]` overwrote a sphere of up to
 * 200 blocks around the sender with stone and caves, ignoring claims, WorldGuard and
 * containers, with no confirmation and no audit row.
 */
object WorldAdminRules {
    const val DELETE_PERMISSION = "joshymc.world.delete"
    const val CONFIRM = "confirm"

    /** Folder-safe world names only: no path separators, dots or spaces. */
    private val WORLD_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun validWorldName(name: String): Boolean = WORLD_NAME.matches(name)

    enum class DeleteProblem(val message: String) {
        INVALID_NAME("That isn't a valid world name."),
        PROTECTED("That world is protected and can't be deleted with /world delete."),
        NOT_LOADED("That world isn't loaded."),
        NEEDS_CONFIRM("This permanently deletes the world folder. Add 'confirm' to the end of the command to delete it."),
    }

    /**
     * [protectedWorlds]: worlds the server runs on (defaults, main world, spawn, resource,
     * AFK, event, PvP, dungeon, gamemode-mapped and configured ones), compared case-insensitively.
     */
    fun deleteProblem(name: String, protectedWorlds: Set<String>, loaded: Boolean, confirmed: Boolean): DeleteProblem? = when {
        !validWorldName(name) -> DeleteProblem.INVALID_NAME
        protectedWorlds.any { it.equals(name, ignoreCase = true) } -> DeleteProblem.PROTECTED
        !loaded -> DeleteProblem.NOT_LOADED
        !confirmed -> DeleteProblem.NEEDS_CONFIRM
        else -> null
    }

    // ── /gencave ─────────────────────────────────────────────

    const val MIN_RADIUS = 10
    const val MAX_RADIUS = 200
    const val CONFIRM_SECONDS = 60L

    enum class CaveProblem(val message: String) {
        RADIUS("Radius must be $MIN_RADIUS-$MAX_RADIUS."),
        WORLD("Caves can't be generated in this world."),
        CLAIM("The cave would overwrite a claim."),
        CONTAINER("The cave would overwrite containers or other block entities (chests, spawners, signs, ...)."),
        WORLDGUARD("WorldGuard doesn't let you build in the whole area (or couldn't be checked)."),
        RUNNING("A cave is already being generated. Wait for it to finish."),
        NO_PENDING("Nothing to confirm. Run /gencave <radius> first."),
        EXPIRED("That confirmation expired. Run /gencave <radius> again."),
    }

    fun radiusProblem(radius: Int): CaveProblem? = if (radius in MIN_RADIUS..MAX_RADIUS) null else CaveProblem.RADIUS

    /** Inclusive block box of a sphere of [radius] around ([x], [z]): the area the cave can touch (+1 for wall decoration). */
    data class Area(val minX: Int, val minZ: Int, val maxX: Int, val maxZ: Int)

    fun area(x: Int, z: Int, radius: Int): Area = Area(x - radius - 1, z - radius - 1, x + radius + 1, z + radius + 1)

    /** A rectangular claim [x1]..[x2] × [z1]..[z2] (any corner order) overlaps [area]. */
    fun overlaps(area: Area, x1: Int, z1: Int, x2: Int, z2: Int): Boolean =
        minOf(x1, x2) <= area.maxX && maxOf(x1, x2) >= area.minX &&
            minOf(z1, z2) <= area.maxZ && maxOf(z1, z2) >= area.minZ

    /** A block at offset ([dx], [dy], [dz]) from the centre can be changed (sphere + the one-block wall pass). */
    fun touches(dx: Int, dy: Int, dz: Int, radius: Int): Boolean {
        val r = radius + 1.0
        return dx.toDouble() * dx + dy.toDouble() * dy + dz.toDouble() * dz <= r * r
    }

    fun approximateBlocks(radius: Int): Long = Math.round(4.0 / 3.0 * Math.PI * radius * radius * radius)

    /** A pending confirmation is usable for [CONFIRM_SECONDS] after it was made. */
    fun expired(createdAtMillis: Long, nowMillis: Long): Boolean = nowMillis - createdAtMillis > CONFIRM_SECONDS * 1000
}
