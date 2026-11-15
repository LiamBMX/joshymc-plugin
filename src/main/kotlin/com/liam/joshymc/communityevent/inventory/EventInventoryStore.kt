package com.liam.joshymc.communityevent.inventory

import com.liam.joshymc.communityevent.inventory.EventInventoryRules.ACTIVE
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.LEGACY
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.RESTORING
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.REVIEW
import com.liam.joshymc.communityevent.inventory.EventInventoryRules.UNSETTLED
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Issue #1550: event-world inventory snapshots in data.db (Bukkit-free; stacks are opaque strings).
 *
 * - `event_inventory_snapshots`: one row per entry. The original items ([Snapshot.slots]) are written
 *   once and never changed (trigger); the status only moves along [EventInventoryRules.TRANSITIONS]
 *   (trigger), always through a conditional UPDATE, so a second callback, a second thread or a second
 *   boot can never take the same row twice. Rows are never deleted.
 * - A partial UNIQUE index allows one unsettled row per player, so a player can't get a second
 *   snapshot while an older one still waits.
 * - `event_inventory_audit`: every status change, in the same transaction as the change.
 *
 * Every write throws on failure (nothing is swallowed); callers change the player only after it returned.
 */
class EventInventoryStore(private val db: SqliteDatabase) {

    data class Snapshot(
        val id: Long,
        val uuid: UUID,
        val name: String?,
        val status: String,
        val source: String,
        val slots: List<String?>?,
        val heldSlot: Int,
        val gamemode: String?,
        val markerRef: Long?,
        val bootId: String?,
        val createdAt: Long,
        val updatedAt: Long,
        val restoreStartedAt: Long?,
        val detail: String?,
        val resolvedBy: String?,
        val hasReplaced: Boolean,
    ) {
        val hasItems: Boolean get() = slots != null
        val itemCount: Int get() = slots?.count { it != null } ?: 0
        fun row() = EventInventoryRules.Row(id, status, hasItems)
    }

    data class Audit(val at: Long, val snapshotId: Long?, val action: String, val actor: String, val detail: String?)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS event_inventory_snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                player_name TEXT,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                contents TEXT,
                slot_count INTEGER NOT NULL DEFAULT 0,
                held_slot INTEGER NOT NULL DEFAULT 0,
                gamemode TEXT,
                marker_ref INTEGER,
                legacy_key TEXT UNIQUE,
                boot_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                restore_started_at INTEGER,
                replaced_contents TEXT,
                replaced_count INTEGER,
                detail TEXT,
                resolved_by TEXT
            )
            """.trimIndent()
        )
        val unsettled = UNSETTLED.joinToString(",") { "'$it'" }
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_event_inventory_open ON event_inventory_snapshots(uuid) WHERE status IN ($unsettled)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_inventory_uuid ON event_inventory_snapshots(uuid, id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_inventory_status ON event_inventory_snapshots(status, updated_at)")
        val allowed = EventInventoryRules.TRANSITIONS.entries.filter { it.value.isNotEmpty() }.joinToString(" OR ") { (from, to) ->
            "(OLD.status = '$from' AND NEW.status IN (${to.joinToString(",") { "'$it'" }}))"
        }
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_event_inventory_status BEFORE UPDATE OF status ON event_inventory_snapshots
            WHEN NOT ($allowed)
            BEGIN SELECT RAISE(ABORT, 'event inventory snapshot status change not allowed'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_event_inventory_contents BEFORE UPDATE OF contents, slot_count, uuid, held_slot, gamemode ON event_inventory_snapshots
            WHEN NEW.contents IS NOT OLD.contents OR NEW.slot_count IS NOT OLD.slot_count OR NEW.uuid IS NOT OLD.uuid
                OR NEW.held_slot IS NOT OLD.held_slot OR NEW.gamemode IS NOT OLD.gamemode
            BEGIN SELECT RAISE(ABORT, 'event inventory snapshot contents are immutable'); END
            """.trimIndent()
        )
        db.createTable("CREATE TRIGGER IF NOT EXISTS trg_event_inventory_no_delete BEFORE DELETE ON event_inventory_snapshots BEGIN SELECT RAISE(ABORT, 'event inventory snapshots are never deleted'); END")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS event_inventory_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                snapshot_id INTEGER,
                action TEXT NOT NULL,
                actor TEXT NOT NULL,
                detail TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_inventory_audit_uuid ON event_inventory_audit(uuid, id)")
    }

    // ── Reads ───────────────────────────────────────────────

    private val columns = "id, uuid, player_name, status, source, contents, slot_count, held_slot, gamemode, marker_ref, boot_id, " +
        "created_at, updated_at, restore_started_at, detail, resolved_by, replaced_contents IS NOT NULL AS has_replaced"

    /** The player's one unsettled row (ACTIVE / RESTORING / REVIEW / LEGACY), if any. */
    fun open(uuid: UUID): Snapshot? {
        val statuses = UNSETTLED.toList()
        return db.queryFirst(
            "SELECT $columns FROM event_inventory_snapshots WHERE uuid = ? AND status IN (?, ?, ?, ?) ORDER BY id DESC LIMIT 1",
            uuid.toString(), statuses[0], statuses[1], statuses[2], statuses[3],
        ) { map(it) }
    }

    fun byId(id: Long): Snapshot? = db.queryFirst("SELECT $columns FROM event_inventory_snapshots WHERE id = ?", id) { map(it) }

    /** The row a player-file marker points at: that player's row with this id, else their newest orphan review row for it. */
    fun forMarker(uuid: UUID, marker: Long): Snapshot? =
        db.queryFirst("SELECT $columns FROM event_inventory_snapshots WHERE id = ? AND uuid = ?", marker, uuid.toString()) { map(it) }
            ?: db.queryFirst(
                "SELECT $columns FROM event_inventory_snapshots WHERE uuid = ? AND source = ? AND marker_ref = ? ORDER BY id DESC LIMIT 1",
                uuid.toString(), EventInventoryRules.SOURCE_ORPHAN, marker,
            ) { map(it) }

    fun history(uuid: UUID, limit: Int = 10): List<Snapshot> =
        db.query("SELECT $columns FROM event_inventory_snapshots WHERE uuid = ? ORDER BY id DESC LIMIT ?", uuid.toString(), limit) { map(it) }

    /** Rows needing attention: REVIEW first, then other unsettled rows, oldest update first. */
    fun unsettled(limit: Int = 20): List<Snapshot> = db.query(
        "SELECT $columns FROM event_inventory_snapshots WHERE status IN ('$REVIEW', '$RESTORING', '$LEGACY', '$ACTIVE') " +
            "ORDER BY CASE status WHEN '$REVIEW' THEN 0 WHEN '$RESTORING' THEN 1 WHEN '$LEGACY' THEN 2 ELSE 3 END, updated_at ASC LIMIT ?",
        limit,
    ) { map(it) }

    fun countByStatus(): Map<String, Int> =
        db.query("SELECT status, COUNT(*) AS n FROM event_inventory_snapshots GROUP BY status") { it.getString("status") to it.getInt("n") }.toMap()

    fun audit(uuid: UUID, limit: Int = 10): List<Audit> = db.query(
        "SELECT at, snapshot_id, action, actor, detail FROM event_inventory_audit WHERE uuid = ? ORDER BY id DESC LIMIT ?",
        uuid.toString(), limit,
    ) { rs -> Audit(rs.getLong("at"), rs.getLong("snapshot_id").takeIf { !rs.wasNull() }, rs.getString("action"), rs.getString("actor"), rs.getString("detail")) }

    /** The original items the restore of [id] replaced (what the player held at the time), for staff. */
    fun replaced(id: Long): List<String?>? = db.queryFirst(
        "SELECT replaced_contents, replaced_count FROM event_inventory_snapshots WHERE id = ? AND replaced_contents IS NOT NULL", id,
    ) { rs -> EventInventoryRules.decodeSlots(rs.getString("replaced_contents"), rs.getInt("replaced_count")) }

    // ── Writes ──────────────────────────────────────────────

    /**
     * Stores a new ACTIVE snapshot (entry). Throws when the player already has an unsettled row
     * (UNIQUE index) or the write fails; the caller then leaves the player's items untouched.
     */
    fun insertActive(uuid: UUID, name: String, slots: List<String?>, heldSlot: Int, gamemode: String, bootId: String, now: Long): Long {
        var id = -1L
        db.transaction {
            db.execute(
                "INSERT INTO event_inventory_snapshots (uuid, player_name, status, source, contents, slot_count, held_slot, gamemode, boot_id, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid.toString(), name, ACTIVE, EventInventoryRules.SOURCE_ENTRY, EventInventoryRules.encodeSlots(slots), slots.size, heldSlot, gamemode, bootId, now, now,
            )
            // Same transaction (the connection lock is held throughout), so this is our row's id.
            id = db.queryFirst("SELECT last_insert_rowid() AS id") { it.getLong("id") } ?: error("no row id")
            audit(uuid, id, "ENTRY_SNAPSHOT", "system", "slots=${slots.count { it != null }} gamemode=$gamemode boot=$bootId", now)
        }
        return id
    }

    /**
     * Moves [id] from [from] to [to] if it is still [from] (false = someone else already moved it).
     * [replaced] (the inventory a restore overwrites) and [bootId] are stored when given.
     */
    fun move(
        id: Long, from: String, to: String, reason: String, actor: String, now: Long,
        bootId: String? = null, replaced: List<String?>? = null, resolvedBy: String? = null,
    ): Boolean {
        require(EventInventoryRules.canMove(from, to)) { "$from -> $to is not allowed" }
        var moved = false
        db.transaction {
            val uuid = db.queryFirst("SELECT uuid FROM event_inventory_snapshots WHERE id = ? AND status = ?", id, from) { it.getString("uuid") }
                ?: return@transaction
            val changed = db.executeUpdate(
                "UPDATE event_inventory_snapshots SET status = ?, updated_at = ?, detail = ?, " +
                    "boot_id = COALESCE(?, boot_id), " +
                    "restore_started_at = CASE WHEN ? = '$RESTORING' THEN ? ELSE restore_started_at END, " +
                    "replaced_contents = COALESCE(?, replaced_contents), replaced_count = COALESCE(?, replaced_count), " +
                    "resolved_by = COALESCE(?, resolved_by) " +
                    "WHERE id = ? AND status = ?",
                to, now, reason, bootId, to, now,
                replaced?.let { EventInventoryRules.encodeSlots(it) }, replaced?.size,
                resolvedBy, id, from,
            )
            if (changed != 1) return@transaction
            audit(UUID.fromString(uuid), id, "$from->$to", actor, reason, now)
            moved = true
        }
        return moved
    }

    /** A REVIEW row for a player-file marker with no matching row (no items: staff can only resolve it). */
    fun insertOrphanReview(uuid: UUID, name: String, marker: Long, reason: String, now: Long): Long {
        var id = -1L
        db.transaction {
            db.execute(
                "INSERT INTO event_inventory_snapshots (uuid, player_name, status, source, contents, slot_count, marker_ref, created_at, updated_at, detail) " +
                    "VALUES (?, ?, ?, ?, NULL, 0, ?, ?, ?, ?)",
                uuid.toString(), name, REVIEW, EventInventoryRules.SOURCE_ORPHAN, marker, now, now, reason,
            )
            id = db.queryFirst("SELECT last_insert_rowid() AS id") { it.getLong("id") } ?: error("no row id")
            audit(uuid, id, "ORPHAN_MARKER", "system", "marker=$marker $reason", now)
        }
        return id
    }

    fun audit(uuid: UUID, snapshotId: Long?, action: String, actor: String, detail: String?, now: Long) {
        db.execute(
            "INSERT INTO event_inventory_audit (at, uuid, snapshot_id, action, actor, detail) VALUES (?, ?, ?, ?, ?, ?)",
            now, uuid.toString(), snapshotId, action, actor, detail,
        )
    }

    data class LegacyEntry(val uuid: UUID, val key: String, val slots: List<String?>, val gamemode: String, val problems: List<String>)

    /**
     * Imports old `event_inventories.yml` entries as LEGACY rows, all in one transaction (all or
     * nothing). Idempotent: [LegacyEntry.key] is UNIQUE, so a re-run after a crash imports nothing twice.
     * Returns how many rows were added; entries whose player already has an unsettled row are reported in [skipped].
     */
    fun importLegacy(entries: List<LegacyEntry>, now: Long, skipped: MutableList<UUID> = mutableListOf()): Int {
        var added = 0
        db.transaction {
            for (e in entries) {
                val exists = db.queryFirst("SELECT id FROM event_inventory_snapshots WHERE legacy_key = ?", e.key) { it.getLong("id") }
                if (exists != null) continue
                if (open(e.uuid) != null) { skipped += e.uuid; continue }
                val detail = "imported from event_inventories.yml" + if (e.problems.isEmpty()) "" else "; problems: ${e.problems.joinToString("; ")}"
                // An entry with an unreadable item is never settled automatically.
                val status = if (e.problems.isEmpty()) LEGACY else REVIEW
                db.execute(
                    "INSERT INTO event_inventory_snapshots (uuid, status, source, contents, slot_count, held_slot, gamemode, legacy_key, created_at, updated_at, detail) " +
                        "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?)",
                    e.uuid.toString(), status, EventInventoryRules.SOURCE_LEGACY, EventInventoryRules.encodeSlots(e.slots), e.slots.size, e.gamemode, e.key, now, now, detail,
                )
                val id = db.queryFirst("SELECT last_insert_rowid() AS id") { it.getLong("id") }
                audit(e.uuid, id, "LEGACY_IMPORT", "system", detail, now)
                added++
            }
        }
        return added
    }

    private fun map(rs: ResultSet): Snapshot {
        val count = rs.getInt("slot_count")
        val raw = rs.getString("contents")
        return Snapshot(
            id = rs.getLong("id"),
            uuid = UUID.fromString(rs.getString("uuid")),
            name = rs.getString("player_name"),
            status = rs.getString("status"),
            source = rs.getString("source"),
            slots = if (raw == null) null else EventInventoryRules.decodeSlots(raw, count),
            heldSlot = rs.getInt("held_slot"),
            gamemode = rs.getString("gamemode"),
            markerRef = rs.getLong("marker_ref").takeIf { !rs.wasNull() },
            bootId = rs.getString("boot_id"),
            createdAt = rs.getLong("created_at"),
            updatedAt = rs.getLong("updated_at"),
            restoreStartedAt = rs.getLong("restore_started_at").takeIf { !rs.wasNull() },
            detail = rs.getString("detail"),
            resolvedBy = rs.getString("resolved_by"),
            hasReplaced = rs.getInt("has_replaced") == 1,
        )
    }
}
