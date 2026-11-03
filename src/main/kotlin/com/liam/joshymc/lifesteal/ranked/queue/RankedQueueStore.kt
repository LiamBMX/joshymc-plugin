package com.liam.joshymc.lifesteal.ranked.queue

import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * The ranked queue's audit trail (issue #1363), Bukkit-free: `lifesteal_ranked_queue_audit`, one
 * row per queue event (queued, agreed, match found, accepted, declined, expired, cancelled,
 * started, staff cancel). Only ids, team ids/names and player UUIDs are stored. The queue itself is
 * never stored (a restart starts with an empty queue); match records stay in
 * [com.liam.joshymc.lifesteal.ranked.RankedStore]. Rows older than [RETENTION_DAYS] are removed at
 * startup.
 */
class RankedQueueStore(private val db: SqliteDatabase) {

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_queue_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                kind TEXT NOT NULL,
                ref TEXT,
                team_id TEXT,
                uuid TEXT,
                detail TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_queue_audit_at ON lifesteal_ranked_queue_audit(at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_queue_audit_ref ON lifesteal_ranked_queue_audit(ref)")
    }

    fun log(at: Long, kind: String, ref: String?, teamId: String?, uuid: UUID?, detail: String?) {
        db.execute(
            "INSERT INTO lifesteal_ranked_queue_audit (at, kind, ref, team_id, uuid, detail) VALUES (?, ?, ?, ?, ?, ?)",
            at, kind.take(32), ref?.take(32), teamId?.take(64), uuid?.toString(), detail?.take(300),
        )
    }

    fun prune(now: Long): Int = db.executeUpdate("DELETE FROM lifesteal_ranked_queue_audit WHERE at < ?", now - RETENTION_DAYS * DAY)

    data class Row(val at: Long, val kind: String, val ref: String?, val teamId: String?, val uuid: UUID?, val detail: String?)

    fun recent(limit: Int): List<Row> =
        db.query("SELECT * FROM lifesteal_ranked_queue_audit ORDER BY id DESC LIMIT ?", limit) { rs ->
            Row(rs.getLong("at"), rs.getString("kind"), rs.getString("ref"), rs.getString("team_id"),
                rs.getString("uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() }, rs.getString("detail"))
        }

    companion object {
        const val RETENTION_DAYS = 30L
        private const val DAY = 24L * 60L * 60L * 1000L
    }
}
