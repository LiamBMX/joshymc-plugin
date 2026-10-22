package com.liam.joshymc.communityevent.arena

import com.liam.joshymc.communityevent.EventType
import kotlin.math.floor

/**
 * Saved arena data for hosted events (issue #1183). Everything here is plain data with no
 * Bukkit types: worlds are stored by name and positions as numbers, never as live
 * World/Location objects, so a saved arena survives restarts and unloaded worlds.
 */

/** A saved position: world name, coordinates and facing. */
data class ArenaPoint(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    val blockX: Int get() = floor(x).toInt()
    val blockY: Int get() = floor(y).toInt()
    val blockZ: Int get() = floor(z).toInt()

    fun describe(): String = "$blockX, $blockY, $blockZ"

    fun distanceSquared(other: ArenaPoint): Double {
        val dx = x - other.x; val dy = y - other.y; val dz = z - other.z
        return dx * dx + dy * dy + dz * dz
    }
}

/** An inclusive block box in one world. Build it with [of] so min/max are always ordered. */
data class ArenaRegion(
    val world: String,
    val minX: Int, val minY: Int, val minZ: Int,
    val maxX: Int, val maxY: Int, val maxZ: Int,
) {
    val sizeX: Int get() = maxX - minX + 1
    val sizeY: Int get() = maxY - minY + 1
    val sizeZ: Int get() = maxZ - minZ + 1
    val volume: Long get() = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()

    fun contains(point: ArenaPoint): Boolean =
        point.world == world &&
            point.blockX in minX..maxX && point.blockY in minY..maxY && point.blockZ in minZ..maxZ

    fun describe(): String = "($minX, $minY, $minZ) to ($maxX, $maxY, $maxZ), ${sizeX}x${sizeY}x$sizeZ"

    companion object {
        fun of(world: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) = ArenaRegion(
            world,
            minOf(x1, x2), minOf(y1, y2), minOf(z1, z2),
            maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2),
        )
    }
}

enum class ArenaFieldKind {
    /** One position with facing. */
    POINT,
    /** Several positions (mob spawn points). */
    POINT_LIST,
    /** A two-corner block box. */
    REGION,
    /** A Y level. */
    HEIGHT,
}

/**
 * Every setting an arena can hold. Which ones a given arena uses (and which of those are
 * required) comes from [ArenaLayouts]; an arena never stores a field its type doesn't list.
 * [key] is the persisted name, so never rename one.
 */
enum class ArenaField(val key: String, val label: String, val kind: ArenaFieldKind, val description: String) {
    LOBBY("lobby", "Lobby", ArenaFieldKind.POINT, "Where players wait before the event starts."),
    SPAWN("spawn", "Player Spawn", ArenaFieldKind.POINT, "Where participants start."),
    FLOOR("floor", "Floor Region", ArenaFieldKind.REGION, "The spleef floor players break."),
    LOSE_HEIGHT("lose", "Elimination Height", ArenaFieldKind.HEIGHT, "Players below this Y level are out."),
    SHARK_SPAWN("shark", "Shark Spawn", ArenaFieldKind.POINT, "Where the sharks start."),
    MINNOW_SPAWN("minnow", "Minnow Spawn", ArenaFieldKind.POINT, "Where the minnows start."),
    FINISH("finish", "Finish Zone", ArenaFieldKind.REGION, "The safe zone minnows cross to."),
    SPAWN_1("spawn1", "Competitor Spawn 1", ArenaFieldKind.POINT, "Where the first competitor starts."),
    SPAWN_2("spawn2", "Competitor Spawn 2", ArenaFieldKind.POINT, "Where the second competitor starts."),
    REGION("region", "Arena Boundary", ArenaFieldKind.REGION, "The area the fight stays inside."),
    MOB_SPAWNS("mobspawns", "Mob Spawn Points", ArenaFieldKind.POINT_LIST, "Where mobs will spawn. Add several."),
    /** Issue #1284: every arena's own spectator spot, so one arena's spot is never used for another. */
    VIEWING("viewing", "Viewing Area", ArenaFieldKind.POINT, "Where spectators and knocked-out players watch from."),
    ;

    companion object {
        fun fromKey(key: String): ArenaField? = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

/** What one event type's arenas hold, and the `/event <type> <sub>` command for each field. */
class ArenaLayout(
    val required: List<ArenaField>,
    val optional: List<ArenaField>,
    /** Type subcommand (e.g. `setfloor`) to the field it sets. */
    val commands: Map<String, ArenaField>,
) {
    val fields: List<ArenaField> get() = optional + required

    fun allows(field: ArenaField): Boolean = field in required || field in optional

    fun isRequired(field: ArenaField): Boolean = field in required

    /** The type subcommand that sets [field], if any (lobby and viewing area are set with `/event arena setlobby|setviewing`). */
    fun commandFor(field: ArenaField): String? = commands.entries.firstOrNull { it.value == field }?.key
}

object ArenaLayouts {
    private val layouts: Map<EventType, ArenaLayout> = mapOf(
        EventType.SPLEEF to ArenaLayout(
            required = listOf(ArenaField.SPAWN, ArenaField.FLOOR, ArenaField.LOSE_HEIGHT),
            optional = listOf(ArenaField.LOBBY, ArenaField.VIEWING),
            commands = linkedMapOf("setspawn" to ArenaField.SPAWN, "setfloor" to ArenaField.FLOOR, "setlose" to ArenaField.LOSE_HEIGHT),
        ),
        EventType.SHARKS to ArenaLayout(
            required = listOf(ArenaField.SHARK_SPAWN, ArenaField.MINNOW_SPAWN, ArenaField.FINISH),
            optional = listOf(ArenaField.LOBBY, ArenaField.VIEWING),
            commands = linkedMapOf("setshark" to ArenaField.SHARK_SPAWN, "setminnow" to ArenaField.MINNOW_SPAWN, "setfinish" to ArenaField.FINISH),
        ),
        EventType.SUMO to ArenaLayout(
            required = listOf(ArenaField.SPAWN_1, ArenaField.SPAWN_2, ArenaField.LOSE_HEIGHT),
            // Optional boundary (issue #1187): stepping outside it horizontally loses the match.
            optional = listOf(ArenaField.LOBBY, ArenaField.VIEWING, ArenaField.REGION),
            commands = linkedMapOf("setspawn1" to ArenaField.SPAWN_1, "setspawn2" to ArenaField.SPAWN_2, "setlose" to ArenaField.LOSE_HEIGHT, "setregion" to ArenaField.REGION),
        ),
        EventType.CPVP to ArenaLayout(
            required = listOf(ArenaField.SPAWN_1, ArenaField.SPAWN_2, ArenaField.REGION),
            optional = listOf(ArenaField.LOBBY, ArenaField.VIEWING),
            commands = linkedMapOf("setspawn1" to ArenaField.SPAWN_1, "setspawn2" to ArenaField.SPAWN_2, "setregion" to ArenaField.REGION),
        ),
        EventType.MOBS to ArenaLayout(
            required = listOf(ArenaField.SPAWN, ArenaField.MOB_SPAWNS, ArenaField.REGION),
            optional = listOf(ArenaField.LOBBY, ArenaField.VIEWING),
            commands = linkedMapOf("setplayers" to ArenaField.SPAWN, "setspawn" to ArenaField.MOB_SPAWNS, "setregion" to ArenaField.REGION),
        ),
    )

    fun of(type: EventType): ArenaLayout = layouts.getValue(type)
}

/** One stored setting. The variant always matches its field's [ArenaFieldKind]. */
sealed interface ArenaValue {
    data class Point(val point: ArenaPoint) : ArenaValue
    data class Points(val points: List<ArenaPoint>) : ArenaValue
    data class Region(val region: ArenaRegion) : ArenaValue
    data class Height(val y: Int) : ArenaValue

    fun matches(kind: ArenaFieldKind): Boolean = when (this) {
        is Point -> kind == ArenaFieldKind.POINT
        is Points -> kind == ArenaFieldKind.POINT_LIST
        is Region -> kind == ArenaFieldKind.REGION
        is Height -> kind == ArenaFieldKind.HEIGHT
    }
}

/**
 * A saved arena: the reusable preset an event loads when staff select it. Immutable; edits
 * go through [EventArenas] and produce a new copy. [id] is the lowercase unique key and
 * [displayName] keeps the casing staff typed.
 */
data class EventArena(
    val id: String,
    val displayName: String,
    val type: EventType,
    /** The world every location must be in (the event world when the arena was created). */
    val world: String,
    val values: Map<ArenaField, ArenaValue>,
    val createdBy: String,
    val createdAt: Long,
    val updatedBy: String,
    val updatedAt: Long,
) {
    val layout: ArenaLayout get() = ArenaLayouts.of(type)

    fun point(field: ArenaField): ArenaPoint? = (values[field] as? ArenaValue.Point)?.point
    fun points(field: ArenaField): List<ArenaPoint> = (values[field] as? ArenaValue.Points)?.points ?: emptyList()
    fun region(field: ArenaField): ArenaRegion? = (values[field] as? ArenaValue.Region)?.region
    fun height(field: ArenaField): Int? = (values[field] as? ArenaValue.Height)?.y

    /**
     * Where this arena's spectators and knocked-out players go (issue #1284): its Viewing Area,
     * else its own lobby. Null when neither is set; it never borrows another arena's spot.
     */
    fun spectatorPoint(): ArenaPoint? = point(ArenaField.VIEWING) ?: point(ArenaField.LOBBY)

    fun isSet(field: ArenaField): Boolean = when (val v = values[field]) {
        null -> false
        is ArenaValue.Points -> v.points.isNotEmpty()
        else -> true
    }

    /** Required fields that have no value yet. */
    val missing: List<ArenaField> get() = layout.required.filter { !isSet(it) }

    companion object {
        /** Arena names: letters, digits, `_` and `-`, 2 to 32 characters. */
        private val NAME = Regex("^[A-Za-z0-9_-]{2,32}$")

        fun isValidName(name: String): Boolean = NAME.matches(name)

        fun idOf(name: String): String = name.lowercase()
    }
}
