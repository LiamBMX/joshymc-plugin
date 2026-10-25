package com.liam.joshymc.freerank

import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * `/freerank` claims (issue #1295), one row per player per gamemode, so Survival and
 * Lifesteal claims never share state. A row is PENDING from the moment the grant is sent
 * until LuckPerms confirms it (COMPLETED) or it fails (the row is deleted, so a failed
 * grant never uses up the claim). The primary key makes the reservation atomic.
 * Bukkit-free: talks only to [SqliteDatabase].
 */
class FreeRankStore(private val db: SqliteDatabase) {

    enum class Status { PENDING, COMPLETED }

    data class Claim(val uuid: UUID, val gamemode: ServerGamemode, val rankId: String, val status: Status)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS free_rank_claims (
                uuid TEXT NOT NULL,
                gamemode TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                status TEXT NOT NULL,
                claimed_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, gamemode)
            )
            """.trimIndent()
        )
    }

    fun status(uuid: UUID, gamemode: ServerGamemode): Status? =
        db.queryFirst(
            "SELECT status FROM free_rank_claims WHERE uuid = ? AND gamemode = ?",
            uuid.toString(), gamemode.id
        ) { it.getString("status") }?.let { raw -> Status.entries.firstOrNull { it.name == raw } }

    /** Reserves the claim as PENDING. False when this player already has a row for [gamemode]. */
    fun reserve(uuid: UUID, name: String, gamemode: ServerGamemode, rankId: String, now: Long): Boolean =
        db.executeUpdate(
            "INSERT OR IGNORE INTO free_rank_claims (uuid, gamemode, rank_id, player_name, status, claimed_at) VALUES (?, ?, ?, ?, ?, ?)",
            uuid.toString(), gamemode.id, rankId, name, Status.PENDING.name, now
        ) == 1

    /** PENDING -> COMPLETED, exactly once. */
    fun markCompleted(uuid: UUID, gamemode: ServerGamemode): Boolean =
        db.executeUpdate(
            "UPDATE free_rank_claims SET status = ? WHERE uuid = ? AND gamemode = ? AND status = ?",
            Status.COMPLETED.name, uuid.toString(), gamemode.id, Status.PENDING.name
        ) == 1

    /** Drops a PENDING reservation so the player can claim again. Never touches a COMPLETED claim. */
    fun release(uuid: UUID, gamemode: ServerGamemode): Boolean =
        db.executeUpdate(
            "DELETE FROM free_rank_claims WHERE uuid = ? AND gamemode = ? AND status = ?",
            uuid.toString(), gamemode.id, Status.PENDING.name
        ) == 1

    fun pending(uuid: UUID): List<Claim> =
        db.query(
            "SELECT gamemode, rank_id FROM free_rank_claims WHERE uuid = ? AND status = ?",
            uuid.toString(), Status.PENDING.name
        ) { rs -> rs.getString("gamemode") to rs.getString("rank_id") }
            .mapNotNull { (gm, rank) -> ServerGamemode.fromId(gm)?.let { Claim(uuid, it, rank, Status.PENDING) } }

    fun hasPending(uuid: UUID): Boolean =
        db.queryFirst(
            "SELECT 1 AS found FROM free_rank_claims WHERE uuid = ? AND status = ? LIMIT 1",
            uuid.toString(), Status.PENDING.name
        ) { it.getInt("found") } != null
}
