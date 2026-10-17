package com.liam.joshymc.communityevent.arena

import com.liam.joshymc.communityevent.EventType
import com.liam.joshymc.manager.SqliteDatabase

/**
 * The `event_arenas` and `event_arena_values` tables in data.db (issue #1183). No Bukkit,
 * so it can be checked against a plain SQLite database.
 *
 * One `event_arenas` row per arena (the id is the primary key, so a duplicate can't be
 * inserted) and one `event_arena_values` row per stored setting: points use world/x/y/z/yaw/pitch,
 * regions put their min corner in x/y/z and max corner in x2/y2/z2, heights use y.
 * Every write runs in one transaction, so an arena is never half-saved.
 */
class ArenaStore(private val db: SqliteDatabase) {

    data class LoadResult(val arenas: List<EventArena>, val warnings: List<String>)

    fun createSchema() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS event_arenas (
                id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                type TEXT NOT NULL,
                world TEXT NOT NULL,
                created_by TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_by TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS event_arena_values (
                arena_id TEXT NOT NULL,
                field TEXT NOT NULL,
                idx INTEGER NOT NULL DEFAULT 0,
                world TEXT,
                x REAL, y REAL, z REAL,
                yaw REAL, pitch REAL,
                x2 REAL, y2 REAL, z2 REAL,
                PRIMARY KEY (arena_id, field, idx)
            )
            """.trimIndent()
        )
    }

    private class Row(
        val arenaId: String, val field: String, val idx: Int, val world: String?,
        val x: Double?, val y: Double?, val z: Double?, val yaw: Double?, val pitch: Double?,
        val x2: Double?, val y2: Double?, val z2: Double?,
    )

    /**
     * Reads every arena. A record that can't be understood (unknown type, unknown field,
     * missing coordinates) is skipped with a warning instead of failing the whole load.
     * Skipped arenas stay in the database untouched.
     */
    fun loadAll(): LoadResult {
        val warnings = mutableListOf<String>()
        val rows = db.query("SELECT * FROM event_arena_values ORDER BY arena_id, field, idx") { rs ->
            fun d(col: String): Double? = rs.getDouble(col).takeUnless { rs.wasNull() }
            Row(
                rs.getString("arena_id"), rs.getString("field"), rs.getInt("idx"), rs.getString("world"),
                d("x"), d("y"), d("z"), d("yaw"), d("pitch"), d("x2"), d("y2"), d("z2"),
            )
        }.groupBy { it.arenaId }

        val arenas = db.query("SELECT * FROM event_arenas ORDER BY id") { rs ->
            ArenaHeader(
                rs.getString("id"), rs.getString("display_name"), rs.getString("type"), rs.getString("world"),
                rs.getString("created_by"), rs.getLong("created_at"), rs.getString("updated_by"), rs.getLong("updated_at"),
            )
        }.mapNotNull { header ->
            try {
                val type = EventType.fromId(header.type)
                if (type == null) {
                    warnings += "Arena '${header.id}' has unknown event type '${header.type}'; skipped."
                    return@mapNotNull null
                }
                val layout = ArenaLayouts.of(type)
                val values = LinkedHashMap<ArenaField, ArenaValue>()
                val listPoints = LinkedHashMap<ArenaField, MutableList<ArenaPoint>>()
                for ((key, fieldRows) in rows[header.id].orEmpty().groupBy { it.field }) {
                    val field = ArenaField.fromKey(key)
                    if (field == null || !layout.allows(field)) {
                        warnings += "Arena '${header.id}': setting '$key' doesn't belong to a ${type.displayName} arena; ignored."
                        continue
                    }
                    for (row in fieldRows) {
                        val value = decode(field, row)
                        if (value == null) {
                            warnings += "Arena '${header.id}': ${field.label} #${row.idx} is malformed; ignored."
                            continue
                        }
                        if (value is ArenaValue.Points) listPoints.getOrPut(field) { mutableListOf() } += value.points
                        else values[field] = value
                    }
                }
                listPoints.forEach { (field, points) -> values[field] = ArenaValue.Points(points) }
                EventArena(
                    id = header.id, displayName = header.displayName, type = type, world = header.world,
                    values = values, createdBy = header.createdBy, createdAt = header.createdAt,
                    updatedBy = header.updatedBy, updatedAt = header.updatedAt,
                )
            } catch (e: Exception) {
                warnings += "Arena '${header.id}' couldn't be read (${e.message}); skipped."
                null
            }
        }
        return LoadResult(arenas, warnings)
    }

    private class ArenaHeader(
        val id: String, val displayName: String, val type: String, val world: String,
        val createdBy: String, val createdAt: Long, val updatedBy: String, val updatedAt: Long,
    )

    private fun decode(field: ArenaField, row: Row): ArenaValue? {
        fun point(): ArenaPoint? {
            val world = row.world ?: return null
            return ArenaPoint(
                world, row.x ?: return null, row.y ?: return null, row.z ?: return null,
                (row.yaw ?: 0.0).toFloat(), (row.pitch ?: 0.0).toFloat(),
            )
        }
        return when (field.kind) {
            ArenaFieldKind.POINT -> point()?.let { ArenaValue.Point(it) }
            ArenaFieldKind.POINT_LIST -> point()?.let { ArenaValue.Points(listOf(it)) }
            ArenaFieldKind.REGION -> {
                val world = row.world ?: return null
                ArenaValue.Region(
                    ArenaRegion.of(
                        world,
                        (row.x ?: return null).toInt(), (row.y ?: return null).toInt(), (row.z ?: return null).toInt(),
                        (row.x2 ?: return null).toInt(), (row.y2 ?: return null).toInt(), (row.z2 ?: return null).toInt(),
                    )
                )
            }
            ArenaFieldKind.HEIGHT -> row.y?.let { ArenaValue.Height(it.toInt()) }
        }
    }

    /** Inserts a new arena. Returns false, writing nothing, when the id is already taken. */
    fun insert(arena: EventArena): Boolean {
        var inserted = false
        db.transaction {
            val count = db.executeUpdate(
                "INSERT OR IGNORE INTO event_arenas (id, display_name, type, world, created_by, created_at, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arena.id, arena.displayName, arena.type.id, arena.world,
                arena.createdBy, arena.createdAt, arena.updatedBy, arena.updatedAt,
            )
            if (count > 0) {
                writeValues(arena)
                inserted = true
            }
        }
        return inserted
    }

    /** Saves an existing arena's settings. Returns false when the arena no longer exists. */
    fun update(arena: EventArena): Boolean {
        var updated = false
        db.transaction {
            val count = db.executeUpdate(
                "UPDATE event_arenas SET display_name = ?, world = ?, updated_by = ?, updated_at = ? WHERE id = ?",
                arena.displayName, arena.world, arena.updatedBy, arena.updatedAt, arena.id,
            )
            if (count > 0) {
                db.execute("DELETE FROM event_arena_values WHERE arena_id = ?", arena.id)
                writeValues(arena)
                updated = true
            }
        }
        return updated
    }

    /** Deletes one arena's configuration (nothing in the world). Returns false when it didn't exist. */
    fun delete(id: String): Boolean {
        var deleted = false
        db.transaction {
            db.execute("DELETE FROM event_arena_values WHERE arena_id = ?", id)
            deleted = db.executeUpdate("DELETE FROM event_arenas WHERE id = ?", id) > 0
        }
        return deleted
    }

    private fun writeValues(arena: EventArena) {
        val sql = "INSERT INTO event_arena_values (arena_id, field, idx, world, x, y, z, yaw, pitch, x2, y2, z2) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        for ((field, value) in arena.values) {
            when (value) {
                is ArenaValue.Point -> writePoint(sql, arena.id, field, 0, value.point)
                is ArenaValue.Points -> value.points.forEachIndexed { i, p -> writePoint(sql, arena.id, field, i, p) }
                is ArenaValue.Region -> with(value.region) {
                    db.execute(sql, arena.id, field.key, 0, world,
                        minX.toDouble(), minY.toDouble(), minZ.toDouble(), null, null,
                        maxX.toDouble(), maxY.toDouble(), maxZ.toDouble())
                }
                is ArenaValue.Height -> db.execute(sql, arena.id, field.key, 0, null,
                    null, value.y.toDouble(), null, null, null, null, null, null)
            }
        }
    }

    private fun writePoint(sql: String, arenaId: String, field: ArenaField, idx: Int, p: ArenaPoint) {
        db.execute(sql, arenaId, field.key, idx, p.world, p.x, p.y, p.z, p.yaw.toDouble(), p.pitch.toDouble(), null, null, null)
    }
}
