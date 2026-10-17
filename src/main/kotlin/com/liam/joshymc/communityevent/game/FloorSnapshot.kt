package com.liam.joshymc.communityevent.game

import com.liam.joshymc.communityevent.arena.ArenaRegion
import com.liam.joshymc.manager.SqliteDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The original blocks of a Spleef floor (issue #1187), captured before a round changes it.
 * Every block in [region] is one entry of [blocks], an index into [palette] (block data
 * strings such as `minecraft:snow_block`), in x, then y, then z order (see [indexOf]).
 */
class FloorSnapshot(
    val arenaId: String,
    val region: ArenaRegion,
    val palette: List<String>,
    val blocks: IntArray,
    val capturedAt: Long,
) {
    init {
        require(blocks.size.toLong() == region.volume) { "Snapshot has ${blocks.size} blocks; its region holds ${region.volume}" }
        require(blocks.all { it in palette.indices }) { "Snapshot refers to a missing palette entry" }
    }

    val size: Int get() = blocks.size

    fun indexOf(x: Int, y: Int, z: Int): Int =
        ((x - region.minX) * region.sizeY + (y - region.minY)) * region.sizeZ + (z - region.minZ)

    /** Coordinates of entry [i]: the inverse of [indexOf]. */
    fun positionOf(i: Int): Triple<Int, Int, Int> {
        val z = i % region.sizeZ
        val y = (i / region.sizeZ) % region.sizeY
        val x = i / (region.sizeZ * region.sizeY)
        return Triple(region.minX + x, region.minY + y, region.minZ + z)
    }

    fun dataAt(x: Int, y: Int, z: Int): String = palette[blocks[indexOf(x, y, z)]]

    /** Gzipped palette + indices, as stored in the database. */
    fun encode(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(GZIPOutputStream(bytes)).use { out ->
            out.writeInt(FORMAT)
            out.writeInt(palette.size)
            palette.forEach { out.writeUTF(it) }
            out.writeInt(blocks.size)
            blocks.forEach { out.writeShort(it) }
        }
        return bytes.toByteArray()
    }

    companion object {
        private const val FORMAT = 1
        /** Palette indices are written as unsigned shorts. */
        const val MAX_PALETTE = 65_535

        /** Reads what [encode] wrote. Throws when the data is damaged or doesn't fit [region]. */
        fun decode(arenaId: String, region: ArenaRegion, capturedAt: Long, data: ByteArray): FloorSnapshot {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(data))).use { input ->
                val format = input.readInt()
                require(format == FORMAT) { "Unknown floor snapshot format $format" }
                val paletteSize = input.readInt()
                require(paletteSize in 1..MAX_PALETTE) { "Bad palette size $paletteSize" }
                val palette = List(paletteSize) { input.readUTF() }
                val count = input.readInt()
                require(count.toLong() == region.volume) { "Snapshot has $count blocks; its region holds ${region.volume}" }
                val blocks = IntArray(count) { input.readUnsignedShort() }
                return FloorSnapshot(arenaId, region, palette, blocks, capturedAt)
            }
        }

        /** Builds a snapshot from one block data string per position, in [indexOf] order. */
        fun of(arenaId: String, region: ArenaRegion, data: List<String>, capturedAt: Long): FloorSnapshot {
            val palette = LinkedHashMap<String, Int>()
            val blocks = IntArray(data.size) { palette.getOrPut(data[it]) { palette.size } }
            require(palette.size <= MAX_PALETTE) { "The floor has more than $MAX_PALETTE different blocks" }
            return FloorSnapshot(arenaId, region, palette.keys.toList(), blocks, capturedAt)
        }
    }
}

/**
 * `event_floor_snapshots` in data.db: one row per arena whose floor may be damaged. A row is
 * written before the first block of a round can break and deleted only after the floor is
 * fully restored, so a crash or restart mid-round still knows the original floor. No Bukkit.
 */
class FloorSnapshotStore(private val db: SqliteDatabase) {

    fun createSchema() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS event_floor_snapshots (
                arena_id TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                min_x INTEGER NOT NULL, min_y INTEGER NOT NULL, min_z INTEGER NOT NULL,
                max_x INTEGER NOT NULL, max_y INTEGER NOT NULL, max_z INTEGER NOT NULL,
                data BLOB NOT NULL,
                captured_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun save(snapshot: FloorSnapshot) {
        val r = snapshot.region
        db.execute(
            "INSERT OR REPLACE INTO event_floor_snapshots (arena_id, world, min_x, min_y, min_z, max_x, max_y, max_z, data, captured_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            snapshot.arenaId, r.world, r.minX, r.minY, r.minZ, r.maxX, r.maxY, r.maxZ, snapshot.encode(), snapshot.capturedAt,
        )
    }

    fun delete(arenaId: String) {
        db.execute("DELETE FROM event_floor_snapshots WHERE arena_id = ?", arenaId)
    }

    /** Every stored snapshot. Rows that can't be read are reported in [warnings] and left in place. */
    fun loadAll(warnings: MutableList<String>): List<FloorSnapshot> {
        class Row(val arenaId: String, val region: ArenaRegion, val data: ByteArray, val capturedAt: Long)
        val rows = db.query("SELECT * FROM event_floor_snapshots ORDER BY arena_id") { rs ->
            Row(
                rs.getString("arena_id"),
                ArenaRegion(
                    rs.getString("world"),
                    rs.getInt("min_x"), rs.getInt("min_y"), rs.getInt("min_z"),
                    rs.getInt("max_x"), rs.getInt("max_y"), rs.getInt("max_z"),
                ),
                rs.getBytes("data"),
                rs.getLong("captured_at"),
            )
        }
        return rows.mapNotNull { row ->
            try {
                FloorSnapshot.decode(row.arenaId, row.region, row.capturedAt, row.data)
            } catch (e: Exception) {
                warnings += "Floor snapshot for arena '${row.arenaId}' can't be read (${e.message}); it was kept but won't be restored."
                null
            }
        }
    }
}
