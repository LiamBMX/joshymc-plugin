package com.liam.joshymc.lifesteal.combat

import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Persistent combat-log incidents (issue #1350), Bukkit-free. One row per combat session that ended
 * in a disconnect while tagged, in the Lifesteal server's own `data.db` (created only where
 * Lifesteal is hosted). `dedupe_key` is UNIQUE and inserts are `INSERT OR IGNORE`, so a repeated
 * quit event or a retried write never creates a second incident. Only UUIDs, the server id, times
 * and enum names are stored: no names, addresses or chat.
 */
class CombatLogStore(private val db: SqliteDatabase) {

    data class Incident(
        val id: String,
        val dedupeKey: String,
        val player: UUID,
        /** Opponents of the session, most recent last (at most [CombatSessions.MAX_OPPONENTS]). */
        val opponents: List<UUID>,
        val serverId: String,
        val combatStartedAt: Long,
        val lastHitAt: Long,
        val tagExpiresAt: Long,
        val disconnectedAt: Long,
        val kind: CombatLogRules.DisconnectKind,
        /** Paper's quit reason / kick cause names, as reported. */
        val quitReason: String?,
        val kickCause: String?,
        val counted: Boolean,
        val status: CombatLogRules.PenaltyStatus,
        val npcSpawned: Boolean,
        /** The heart transaction of an applied penalty; always null while no penalty is approved. */
        val heartTransactionId: String?,
    )

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_combat_logs (
                id TEXT PRIMARY KEY,
                dedupe_key TEXT NOT NULL UNIQUE,
                player TEXT NOT NULL,
                opponents TEXT NOT NULL,
                server_id TEXT NOT NULL,
                combat_started_at INTEGER NOT NULL,
                last_hit_at INTEGER NOT NULL,
                tag_expires_at INTEGER NOT NULL,
                disconnected_at INTEGER NOT NULL,
                disconnect_kind TEXT NOT NULL,
                quit_reason TEXT,
                kick_cause TEXT,
                counted INTEGER NOT NULL,
                penalty_status TEXT NOT NULL,
                npc_spawned INTEGER NOT NULL,
                heart_transaction_id TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_combat_logs_player ON lifesteal_combat_logs(player, disconnected_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_combat_logs_time ON lifesteal_combat_logs(disconnected_at)")
    }

    /** Inserts [incident]; false when its dedupe key is already stored (nothing changes). */
    fun record(incident: Incident): Boolean = db.executeUpdate(
        """
        INSERT OR IGNORE INTO lifesteal_combat_logs (id, dedupe_key, player, opponents, server_id, combat_started_at, last_hit_at,
            tag_expires_at, disconnected_at, disconnect_kind, quit_reason, kick_cause, counted, penalty_status, npc_spawned, heart_transaction_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        incident.id, incident.dedupeKey, incident.player.toString(), incident.opponents.joinToString(","), incident.serverId,
        incident.combatStartedAt, incident.lastHitAt, incident.tagExpiresAt, incident.disconnectedAt, incident.kind.name,
        incident.quitReason, incident.kickCause, if (incident.counted) 1 else 0, incident.status.name,
        if (incident.npcSpawned) 1 else 0, incident.heartTransactionId,
    ) > 0

    /** Newest first; only [player]'s when given. */
    fun recent(limit: Int, player: UUID? = null): List<Incident> =
        if (player == null) db.query("SELECT * FROM lifesteal_combat_logs ORDER BY disconnected_at DESC, id LIMIT ?", limit.coerceIn(1, 100)) { read(it) }.filterNotNull()
        else db.query(
            "SELECT * FROM lifesteal_combat_logs WHERE player = ? ORDER BY disconnected_at DESC, id LIMIT ?", player.toString(), limit.coerceIn(1, 100)
        ) { read(it) }.filterNotNull()

    /** Incidents whose id starts with [prefix] (staff type the short id); at most 2 so ambiguity shows. */
    fun byIdPrefix(prefix: String): List<Incident> {
        val clean = prefix.lowercase().filter { it.isLetterOrDigit() || it == '-' }
        if (clean.length < 4) return emptyList()
        return db.query("SELECT * FROM lifesteal_combat_logs WHERE id LIKE ? ORDER BY id LIMIT 2", "$clean%") { read(it) }.filterNotNull()
    }

    fun countFor(player: UUID, since: Long): Int =
        db.queryFirst("SELECT COUNT(*) FROM lifesteal_combat_logs WHERE player = ? AND counted = 1 AND disconnected_at >= ?", player.toString(), since) { it.getInt(1) } ?: 0

    /** Retention: deletes incidents that ended before [cutoff]. Returns how many. */
    fun purgeBefore(cutoff: Long): Int = db.executeUpdate("DELETE FROM lifesteal_combat_logs WHERE disconnected_at < ?", cutoff)

    private fun read(rs: java.sql.ResultSet): Incident? = try {
        Incident(
            id = rs.getString("id"),
            dedupeKey = rs.getString("dedupe_key"),
            player = UUID.fromString(rs.getString("player")),
            opponents = rs.getString("opponents").split(',').filter { it.isNotBlank() }.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() },
            serverId = rs.getString("server_id"),
            combatStartedAt = rs.getLong("combat_started_at"),
            lastHitAt = rs.getLong("last_hit_at"),
            tagExpiresAt = rs.getLong("tag_expires_at"),
            disconnectedAt = rs.getLong("disconnected_at"),
            kind = CombatLogRules.DisconnectKind.valueOf(rs.getString("disconnect_kind")),
            quitReason = rs.getString("quit_reason"),
            kickCause = rs.getString("kick_cause"),
            counted = rs.getInt("counted") == 1,
            status = CombatLogRules.PenaltyStatus.valueOf(rs.getString("penalty_status")),
            npcSpawned = rs.getInt("npc_spawned") == 1,
            heartTransactionId = rs.getString("heart_transaction_id"),
        )
    } catch (_: Exception) {
        null // A malformed row is skipped, never rewritten.
    }
}
