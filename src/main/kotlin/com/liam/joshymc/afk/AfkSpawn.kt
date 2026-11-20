package com.liam.joshymc.afk

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Where `/afk` sends a player (issue #1596), one per gamemode. Survival and Lifesteal run on
 * separate servers with separate databases, and on a server hosting both the row is keyed by
 * the gamemode of the world the player is in, so setting one never touches the other.
 * The world is stored by name; it's resolved (and its chunk loaded) only when used.
 */
data class AfkSpawn(
    val gamemode: ServerGamemode,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
)

/** Bukkit-free rules and texts for the AFK spawn. */
object AfkSpawnRules {

    /** What entering AFK does with the stored spawn and whether its world is loaded. */
    enum class Destination {
        /** Teleport there (after the chunk is loaded and the spot checked). */
        TELEPORT,
        /** No AFK spawn set for this gamemode: the player goes AFK where they stand. */
        NOT_SET,
        /** The saved world isn't loaded (deleted or renamed): AFK in place, staff warned in the log. */
        WORLD_MISSING,
        /** The saved row can't be used (bad coordinates): AFK in place. */
        INVALID,
    }

    fun destination(spawn: AfkSpawn?, worldLoaded: Boolean): Destination = when {
        spawn == null -> Destination.NOT_SET
        coordinateProblem(spawn) != null -> Destination.INVALID
        !worldLoaded -> Destination.WORLD_MISSING
        else -> Destination.TELEPORT
    }

    /** Null when the stored values are usable; otherwise why not. */
    fun coordinateProblem(spawn: AfkSpawn): String? = when {
        spawn.world.isBlank() -> "no world"
        !spawn.x.isFinite() || !spawn.y.isFinite() || !spawn.z.isFinite() -> "invalid coordinates"
        !spawn.yaw.isFinite() || !spawn.pitch.isFinite() -> "invalid rotation"
        spawn.pitch < -90f || spawn.pitch > 90f -> "invalid rotation"
        else -> null
    }

    const val MSG_NOT_SET = "No AFK spawn has been set here yet, so you'll stay where you are."
    const val MSG_UNAVAILABLE = "The AFK spawn isn't available right now, so you'll stay where you are."
    const val MSG_SET_UNSAFE = "You can't set the AFK spawn here: "
    const val MSG_SET_WHILE_AFK = "Leave AFK before setting the AFK spawn."

    fun formatSaved(spawn: AfkSpawn): String =
        "AFK spawn for ${spawn.gamemode.displayName} set to ${spawn.world} " +
            "(${fmt(spawn.x)}, ${fmt(spawn.y)}, ${fmt(spawn.z)}), yaw ${fmt(spawn.yaw.toDouble())}, pitch ${fmt(spawn.pitch.toDouble())}."

    private fun fmt(v: Double): String = String.format(java.util.Locale.ROOT, "%.2f", v)
}

/** `afk_spawns`: one row per gamemode id. Bukkit-free: talks only to [SqliteDatabase]. */
class AfkSpawnStore(private val db: SqliteDatabase) {

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS afk_spawns (
                gamemode TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                x REAL NOT NULL,
                y REAL NOT NULL,
                z REAL NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL,
                set_by_uuid TEXT,
                set_by_name TEXT,
                set_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    /** Replaces only [spawn]'s gamemode row. */
    fun save(spawn: AfkSpawn, byUuid: UUID?, byName: String, now: Long) {
        db.execute(
            """
            INSERT INTO afk_spawns (gamemode, world, x, y, z, yaw, pitch, set_by_uuid, set_by_name, set_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(gamemode) DO UPDATE SET world = excluded.world, x = excluded.x, y = excluded.y,
                z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch, set_by_uuid = excluded.set_by_uuid,
                set_by_name = excluded.set_by_name, set_at = excluded.set_at
            """.trimIndent(),
            spawn.gamemode.id, spawn.world, spawn.x, spawn.y, spawn.z, spawn.yaw.toDouble(), spawn.pitch.toDouble(),
            byUuid?.toString(), byName, now
        )
    }

    fun load(gamemode: ServerGamemode): AfkSpawn? =
        db.queryFirst("SELECT world, x, y, z, yaw, pitch FROM afk_spawns WHERE gamemode = ?", gamemode.id) { rs ->
            AfkSpawn(
                gamemode, rs.getString("world") ?: "",
                rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getDouble("yaw").toFloat(), rs.getDouble("pitch").toFloat(),
            )
        }
}
