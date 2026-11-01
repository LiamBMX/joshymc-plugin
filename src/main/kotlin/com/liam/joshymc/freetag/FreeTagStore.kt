package com.liam.joshymc.freetag

import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * `/freetag` claims (issue #1339), one row per player UUID. The primary key makes the
 * reservation atomic: a second claim (spam, reconnect, another session) finds the row and is
 * refused. A row is PENDING while the voucher is being handed over and COMPLETED once it is in
 * the player's inventory; a failed delivery deletes the PENDING row so the claim isn't used up.
 * Redeeming the voucher never touches this table, so the claim stays recorded for good.
 * Bukkit-free: talks only to [SqliteDatabase].
 */
class FreeTagStore(private val db: SqliteDatabase) {

    enum class Status { PENDING, COMPLETED }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS free_tag_claims (
                uuid TEXT PRIMARY KEY,
                player_name TEXT NOT NULL,
                voucher_id TEXT NOT NULL,
                status TEXT NOT NULL,
                claimed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun status(uuid: UUID): Status? =
        db.queryFirst("SELECT status FROM free_tag_claims WHERE uuid = ?", uuid.toString()) { it.getString("status") }
            ?.let { raw -> Status.entries.firstOrNull { it.name == raw } ?: Status.COMPLETED }

    fun hasClaimed(uuid: UUID): Boolean = status(uuid) != null

    /** Reserves the claim as PENDING. False when this player already has a row. */
    fun reserve(uuid: UUID, name: String, voucherId: String, now: Long): Boolean =
        db.executeUpdate(
            "INSERT OR IGNORE INTO free_tag_claims (uuid, player_name, voucher_id, status, claimed_at) VALUES (?, ?, ?, ?, ?)",
            uuid.toString(), name, voucherId, Status.PENDING.name, now
        ) == 1

    /** PENDING -> COMPLETED, exactly once. */
    fun markCompleted(uuid: UUID): Boolean =
        db.executeUpdate(
            "UPDATE free_tag_claims SET status = ? WHERE uuid = ? AND status = ?",
            Status.COMPLETED.name, uuid.toString(), Status.PENDING.name
        ) == 1

    /** Drops a PENDING reservation so the player can claim again. Never touches a COMPLETED claim. */
    fun release(uuid: UUID): Boolean =
        db.executeUpdate(
            "DELETE FROM free_tag_claims WHERE uuid = ? AND status = ?",
            uuid.toString(), Status.PENDING.name
        ) == 1

    /**
     * Deletes every PENDING row. Delivery runs reserve → hand over → complete in one main-thread
     * call, so a PENDING row at startup can only come from a crash mid-delivery, before the
     * player's inventory (and the voucher) was ever saved.
     */
    fun releaseAllPending(): Int =
        db.executeUpdate("DELETE FROM free_tag_claims WHERE status = ?", Status.PENDING.name)
}
