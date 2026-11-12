package com.liam.joshymc.lifesteal.hearts.monitor

import com.liam.joshymc.lifesteal.hearts.monitor.HeartMonitorRules.Signal
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Issue #1508: the heart transfer review log, Bukkit-free. Counts come only from history that
 * already exists: `lifesteal_heart_transactions` (the heart store's own rows, read through its
 * `(killer, victim, created_at)` index) and AltManager's `player_connections` (salted hashes,
 * compared inside SQL; no hash or address is ever read out or copied). The only new table is
 * the review log:
 *
 * - `lifesteal_heart_monitor_flags`: one row per flagged transfer. `transaction_id` (the heart
 *   transaction) is UNIQUE, so evaluating the same transfer again never adds a row. It holds the
 *   UUIDs, the heart counts before/after, the signal codes and counts, whether staff were told,
 *   and the review status (OPEN, REVIEWED, DISMISSED).
 *
 * Nothing here writes hearts, cooldowns, heart transactions, ratings or punishments.
 */
class HeartTransferMonitorStore(private val db: SqliteDatabase) {

    enum class Status { OPEN, REVIEWED, DISMISSED }

    data class Flag(
        val id: Long,
        val transactionId: String,
        val dedupeKey: String,
        val source: HeartMonitorRules.Source?,
        val matchId: String?,
        val killer: UUID,
        val victim: UUID,
        val killerBefore: Int,
        val killerAfter: Int,
        val victimBefore: Int,
        val victimAfter: Int,
        val signals: List<Signal>,
        val forward: Int,
        val reverse: Int,
        val associatedAccounts: Int,
        val windowHours: Int,
        val status: Status?,
        val notified: Boolean,
        val transferAt: Long,
        val at: Long,
        val reviewedBy: String?,
        val reviewedAt: Long?,
        val reviewNote: String?,
    )

    sealed interface Outcome {
        /** No signal: nothing written. */
        data object Clean : Outcome
        /** Flag [flag] written; [notify] = staff should be told now (not throttled). */
        data class Flagged(val flag: Flag, val notify: Boolean) : Outcome
        /** This heart transaction was already flagged: nothing written again. */
        data object AlreadyFlagged : Outcome
        /** No TRANSFERRED heart transaction with this id exists: nothing written. */
        data object NotATransfer : Outcome
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_heart_monitor_flags (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT NOT NULL UNIQUE,
                dedupe_key TEXT NOT NULL,
                source TEXT NOT NULL,
                match_id TEXT,
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                pair_key TEXT NOT NULL,
                killer_before INTEGER NOT NULL,
                killer_after INTEGER NOT NULL,
                victim_before INTEGER NOT NULL,
                victim_after INTEGER NOT NULL,
                signals TEXT NOT NULL,
                forward_transfers INTEGER NOT NULL,
                reverse_transfers INTEGER NOT NULL,
                associated_accounts INTEGER NOT NULL,
                window_hours INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'OPEN',
                notified INTEGER NOT NULL DEFAULT 0,
                transfer_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                reviewed_by TEXT,
                reviewed_at INTEGER,
                review_note TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_monitor_pair ON lifesteal_heart_monitor_flags (pair_key, created_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_monitor_killer ON lifesteal_heart_monitor_flags (killer, created_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_monitor_victim ON lifesteal_heart_monitor_flags (victim, created_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_monitor_status ON lifesteal_heart_monitor_flags (status, created_at)")
        // The counts below read existing tables; these are indexes only (no row changes). The heart
        // store creates the same pair index; IF NOT EXISTS makes either order fine.
        if (tableExists("lifesteal_heart_transactions")) {
            db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_transactions_pair ON lifesteal_heart_transactions (killer, victim, created_at)")
        }
        if (tableExists("player_connections")) {
            db.createTable("CREATE INDEX IF NOT EXISTS idx_player_connections_hash ON player_connections (connection_hash)")
        }
    }

    private fun tableExists(name: String): Boolean =
        db.queryFirst("SELECT 1 AS hit FROM sqlite_master WHERE type = 'table' AND name = ?", name) { true } == true

    // ── Counts over existing history ────────────────────────

    /** Hearts [killer] took from [victim] in [since, until] (committed TRANSFERRED PvP rows only, every heart path). */
    fun transferCount(killer: UUID, victim: UUID, since: Long, until: Long): Int {
        if (!tableExists("lifesteal_heart_transactions")) return 0
        return db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_heart_transactions WHERE killer = ? AND victim = ? AND created_at >= ? AND created_at <= ? " +
                "AND type = 'PVP' AND outcome = 'TRANSFERRED'",
            killer.toString(), victim.toString(), since, until
        ) { it.getInt("n") } ?: 0
    }

    /** AltManager saw [a] and [b] on one connection (hashes compared in SQL; false without the table). */
    fun sharedConnection(a: UUID, b: UUID): Boolean {
        if (a == b || !tableExists("player_connections")) return false
        return db.queryFirst(
            "SELECT 1 AS hit FROM player_connections x JOIN player_connections y ON x.connection_hash = y.connection_hash " +
                "WHERE x.uuid = ? AND y.uuid = ? LIMIT 1",
            a.toString(), b.toString()
        ) { true } == true
    }

    /**
     * Distinct accounts that gave [killer] a heart in [since, until] and share a connection with
     * [killer] or with another of those accounts. Two indexed queries, bounded by [MAX_DONORS].
     */
    fun associatedAccounts(killer: UUID, since: Long, until: Long): Int {
        if (!tableExists("lifesteal_heart_transactions") || !tableExists("player_connections")) return 0
        val donors = db.query(
            "SELECT DISTINCT victim FROM lifesteal_heart_transactions WHERE killer = ? AND created_at >= ? AND created_at <= ? " +
                "AND type = 'PVP' AND outcome = 'TRANSFERRED' AND victim IS NOT NULL LIMIT ?",
            killer.toString(), since, until, MAX_DONORS
        ) { it.getString("victim") }.filter { it != killer.toString() }
        if (donors.isEmpty()) return 0
        val donorMarks = donors.joinToString(",") { "?" }
        val params = ArrayList<Any?>(donors.size * 2 + 1)
        params.addAll(donors)
        params.addAll(donors)
        params.add(killer.toString())
        return db.queryFirst(
            "SELECT COUNT(DISTINCT x.uuid) AS n FROM player_connections x " +
                "JOIN player_connections y ON y.connection_hash = x.connection_hash AND y.uuid <> x.uuid " +
                "WHERE x.uuid IN ($donorMarks) AND y.uuid IN ($donorMarks, ?)",
            *params.toTypedArray()
        ) { it.getInt("n") } ?: 0
    }

    /** Everything [HeartMonitorRules.evaluate] needs for [transfer], inside the window ending at the transfer. */
    fun counts(transfer: HeartMonitorRules.Transfer, settings: HeartMonitorSettings): HeartMonitorRules.Counts {
        val until = transfer.at
        val since = until - settings.windowMillis
        val needPair = settings.repeatedTransfers > 0 || settings.oneWayTransfers > 0
        return HeartMonitorRules.Counts(
            sharedConnection = settings.sharedConnection && sharedConnection(transfer.killer, transfer.victim),
            forward = if (needPair) transferCount(transfer.killer, transfer.victim, since, until) else 0,
            reverse = if (needPair) transferCount(transfer.victim, transfer.killer, since, until) else 0,
            associatedAccounts = if (settings.sharedConnection && settings.associatedAccounts > 0) associatedAccounts(transfer.killer, since, until) else 0,
        )
    }

    // ── Evaluation + review log ─────────────────────────────

    /**
     * Checks one committed transfer and, when a signal is raised, writes its flag once. Runs as
     * one transaction: the heart transaction must exist as TRANSFERRED (a rejected or rolled-back
     * transfer is never logged as one), the flag is keyed by its id (UNIQUE), and [Outcome.Flagged.notify]
     * is false when staff were already told about this pair within the notify cooldown.
     */
    fun evaluate(transfer: HeartMonitorRules.Transfer, settings: HeartMonitorSettings, now: Long): Outcome {
        if (!settings.enabled) return Outcome.Clean
        var outcome: Outcome = Outcome.Clean
        db.transaction {
            val stored = if (tableExists("lifesteal_heart_transactions")) db.queryFirst(
                "SELECT outcome, killer, victim FROM lifesteal_heart_transactions WHERE id = ?",
                transfer.transactionId
            ) { Triple(it.getString("outcome"), it.getString("killer"), it.getString("victim")) } else null
            if (stored == null || stored.first != "TRANSFERRED" || stored.second != transfer.killer.toString() || stored.third != transfer.victim.toString()) {
                outcome = Outcome.NotATransfer
                return@transaction
            }
            if (db.queryFirst("SELECT 1 AS hit FROM lifesteal_heart_monitor_flags WHERE transaction_id = ?", transfer.transactionId) { true } == true) {
                outcome = Outcome.AlreadyFlagged
                return@transaction
            }
            val counts = counts(transfer, settings)
            val signals = HeartMonitorRules.evaluate(counts, settings)
            if (signals.isEmpty()) return@transaction
            val pair = HeartMonitorRules.pairKey(transfer.killer, transfer.victim)
            val notify = settings.notifyStaff && (settings.notifyCooldownMillis <= 0 || db.queryFirst(
                "SELECT 1 AS hit FROM lifesteal_heart_monitor_flags WHERE pair_key = ? AND notified = 1 AND created_at > ? LIMIT 1",
                pair, now - settings.notifyCooldownMillis
            ) { true } != true)
            val inserted = db.executeUpdate(
                "INSERT OR IGNORE INTO lifesteal_heart_monitor_flags (transaction_id, dedupe_key, source, match_id, killer, victim, pair_key, " +
                    "killer_before, killer_after, victim_before, victim_after, signals, forward_transfers, reverse_transfers, associated_accounts, " +
                    "window_hours, status, notified, transfer_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)",
                transfer.transactionId, transfer.dedupeKey.take(200), transfer.source.name, transfer.matchId, transfer.killer.toString(),
                transfer.victim.toString(), pair, transfer.killerBefore, transfer.killerAfter, transfer.victimBefore, transfer.victimAfter,
                signals.joinToString(",") { it.name }, counts.forward, counts.reverse, counts.associatedAccounts, settings.windowHours,
                if (notify) 1 else 0, transfer.at, now
            ) == 1
            if (!inserted) {
                outcome = Outcome.AlreadyFlagged
                return@transaction
            }
            // Read back by the UNIQUE transaction id (never last_insert_rowid, see CLAUDE.md issue #1159).
            outcome = Outcome.Flagged(flagForTransaction(transfer.transactionId) ?: return@transaction, notify)
        }
        return outcome
    }

    fun flag(id: Long): Flag? = db.queryFirst("SELECT * FROM lifesteal_heart_monitor_flags WHERE id = ?", id) { flag(it) }

    fun flagForTransaction(transactionId: String): Flag? =
        db.queryFirst("SELECT * FROM lifesteal_heart_monitor_flags WHERE transaction_id = ?", transactionId) { flag(it) }

    /** Newest first; with [player], only flags where they were the killer or the victim; with [status], only that status. */
    fun flags(player: UUID? = null, status: Status? = null, limit: Int = 15): List<Flag> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (player != null) {
            // Two indexed lookups instead of an OR over one table scan.
            where += "id IN (SELECT id FROM lifesteal_heart_monitor_flags WHERE killer = ? UNION SELECT id FROM lifesteal_heart_monitor_flags WHERE victim = ?)"
            params += player.toString()
            params += player.toString()
        }
        if (status != null) {
            where += "status = ?"
            params += status.name
        }
        params += limit
        val clause = if (where.isEmpty()) "" else "WHERE " + where.joinToString(" AND ") + " "
        return db.query("SELECT * FROM lifesteal_heart_monitor_flags ${clause}ORDER BY created_at DESC, id DESC LIMIT ?", *params.toTypedArray()) { flag(it) }
    }

    fun count(status: Status? = null): Int =
        if (status == null) db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_monitor_flags") { it.getInt("n") } ?: 0
        else db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_monitor_flags WHERE status = ?", status.name) { it.getInt("n") } ?: 0

    /** Staff review: sets [status] on flag [id] (conditional, so a repeat changes nothing). False when no row changed. */
    fun setStatus(id: Long, status: Status, by: String, note: String?, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE lifesteal_heart_monitor_flags SET status = ?, reviewed_by = ?, reviewed_at = ?, review_note = ? WHERE id = ? AND status <> ?",
            status.name, by.take(64), now, note?.take(200), id, status.name
        ) == 1

    /** Deletes REVIEWED/DISMISSED flags created before [retentionDays] days ago (open flags stay). 0 = keep everything. */
    fun purge(retentionDays: Int, now: Long): Int {
        if (retentionDays <= 0) return 0
        return db.executeUpdate(
            "DELETE FROM lifesteal_heart_monitor_flags WHERE status <> 'OPEN' AND created_at < ?",
            now - retentionDays * 86_400_000L
        )
    }

    private fun flag(rs: ResultSet) = Flag(
        id = rs.getLong("id"),
        transactionId = rs.getString("transaction_id"),
        dedupeKey = rs.getString("dedupe_key"),
        source = HeartMonitorRules.Source.parse(rs.getString("source")),
        matchId = rs.getString("match_id"),
        killer = UUID.fromString(rs.getString("killer")),
        victim = UUID.fromString(rs.getString("victim")),
        killerBefore = rs.getInt("killer_before"),
        killerAfter = rs.getInt("killer_after"),
        victimBefore = rs.getInt("victim_before"),
        victimAfter = rs.getInt("victim_after"),
        signals = Signal.parse(rs.getString("signals")),
        forward = rs.getInt("forward_transfers"),
        reverse = rs.getInt("reverse_transfers"),
        associatedAccounts = rs.getInt("associated_accounts"),
        windowHours = rs.getInt("window_hours"),
        status = runCatching { Status.valueOf(rs.getString("status")) }.getOrNull(),
        notified = rs.getInt("notified") != 0,
        transferAt = rs.getLong("transfer_at"),
        at = rs.getLong("created_at"),
        reviewedBy = rs.getString("reviewed_by"),
        reviewedAt = rs.getLong("reviewed_at").takeUnless { rs.wasNull() },
        reviewNote = rs.getString("review_note"),
    )

    companion object {
        /** Upper bound on the accounts read for signal D per transfer. */
        const val MAX_DONORS = 50
    }
}
