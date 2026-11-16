package com.liam.joshymc.lifesteal.diagnostics

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Read-only lookups for `/lifesteal admin diag` (issue #1564). Bukkit-free.
 *
 * Only `SELECT`s, never a write: every query is a primary-key or indexed lookup for one player,
 * or a count over an existing (partial) index for the health view, always bounded with `LIMIT`.
 * The tables belong to their own systems' stores (hearts, ranked, Combat NPCs, event inventories,
 * bank, bounties); this class only reads columns those stores already create, so it never changes
 * a schema, a trigger or a row.
 *
 * Each part of a report is its own [Section]: a table that doesn't exist on this server (system not
 * started) is [Section.NotCreated], a failing query is [Section.Failed] with its message, and one
 * broken part never hides the others. Nothing here throws.
 */
class LifestealDiagnosticsStore(private val db: SqliteDatabase) {

    sealed interface Section<out T> {
        data class Ok<T>(val value: T) : Section<T>
        data object NotCreated : Section<Nothing>
        data class Failed(val message: String) : Section<Nothing>
    }

    data class HeartRow(val hearts: Long?, val version: Long, val createdAt: Long, val updatedAt: Long, val lastTransaction: String?)
    data class HeartAuditRow(val operation: String, val previous: Long?, val next: Long?, val actor: String?, val reason: String?, val createdAt: Long)
    data class RankedParticipant(val matchId: String, val side: String, val status: String, val matchState: String?, val kind: String?, val createdAt: Long?)
    data class RankedSnapshot(val snapshotId: String, val matchId: String, val status: String, val createdAt: Long, val restoreStartedAt: Long?, val reviewReason: String?)
    data class CombatNpcSession(val id: String, val state: String, val outcome: String?, val createdAt: Long, val expiresAt: Long, val world: String, val x: Double, val y: Double, val z: Double)
    data class EventInventoryRow(val id: Long, val status: String, val source: String, val createdAt: Long, val detail: String?)
    data class Bank(val balanceCents: Long?, val failedUnreviewed: Int)
    data class PendingPayout(val id: String, val amountCents: Long, val attempts: Int, val lastError: String?)
    data class Bounty(val activeOnPlayerCents: Long?, val pendingPayouts: List<PendingPayout>, val pendingRefunds: Int)

    data class PlayerReport(
        /** Null when the database answered; otherwise why it couldn't be read at all. */
        val databaseProblem: String?,
        val hearts: Section<HeartRow?>,
        val heartAudit: Section<List<HeartAuditRow>>,
        val outstandingHeartItems: Section<Int>,
        val rankedOpen: Section<RankedParticipant?>,
        val rankedSnapshots: Section<List<RankedSnapshot>>,
        val combatNpcs: Section<List<CombatNpcSession>>,
        val eventInventory: Section<List<EventInventoryRow>>,
        val bank: Section<Bank?>,
        val bounty: Section<Bounty>,
    )

    data class HealthReport(
        val databaseProblem: String?,
        val latencyMillis: Long?,
        /** Ranked snapshots still waiting by status (PENDING / RESTORING / REVIEW). */
        val rankedSnapshots: Section<Map<String, Int>>,
        val combatNpcsOpen: Section<Map<String, Int>>,
        val eventInventory: Section<Map<String, Int>>,
        val bountyPayoutsPending: Section<Int>,
        val bountyRefundsPending: Section<Int>,
        val bankFailedUnreviewed: Section<Int>,
    )

    fun player(uuid: UUID): PlayerReport {
        val id = uuid.toString()
        val tables = try {
            tables()
        } catch (e: Exception) {
            val failed = Section.Failed(message(e))
            return PlayerReport(message(e), failed, failed, failed, failed, failed, failed, failed, failed, failed)
        }
        return PlayerReport(
            databaseProblem = null,
            hearts = section(tables, "lifesteal_hearts") {
                db.queryFirst("SELECT hearts, version, created_at, updated_at, last_transaction FROM lifesteal_hearts WHERE uuid = ?", id) { rs ->
                    HeartRow(longOrNull(rs, "hearts"), rs.getLong("version"), rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("last_transaction"))
                }
            },
            heartAudit = section(tables, "lifesteal_heart_audit") {
                db.query(
                    "SELECT operation, previous_hearts, new_hearts, actor, reason, created_at FROM lifesteal_heart_audit WHERE uuid = ? ORDER BY created_at DESC LIMIT ?",
                    id, AUDIT_LIMIT,
                ) { rs -> HeartAuditRow(rs.getString("operation"), longOrNull(rs, "previous_hearts"), longOrNull(rs, "new_hearts"), rs.getString("actor"), rs.getString("reason"), rs.getLong("created_at")) }
            },
            outstandingHeartItems = section(tables, "lifesteal_heart_items") {
                db.queryFirst("SELECT COUNT(*) FROM lifesteal_heart_items WHERE issued_to = ? AND status = 'ISSUED'", id) { it.getInt(1) } ?: 0
            },
            rankedOpen = section(tables, "lifesteal_ranked_participants", "lifesteal_ranked_matches") {
                db.queryFirst(
                    "SELECT p.match_id, p.side, p.status, m.state, m.kind, m.created_at FROM lifesteal_ranked_participants p " +
                        "LEFT JOIN lifesteal_ranked_matches m ON m.match_id = p.match_id WHERE p.uuid = ? AND p.open = 1 LIMIT 1",
                    id,
                ) { rs -> RankedParticipant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), longOrNull(rs, 6)) }
            },
            rankedSnapshots = section(tables, "lifesteal_ranked_snapshots") {
                db.query(
                    "SELECT snapshot_id, match_id, status, created_at, restore_started_at, review_reason FROM lifesteal_ranked_snapshots " +
                        "WHERE uuid = ? AND status IN ('PENDING', 'RESTORING', 'REVIEW') LIMIT ?",
                    id, ROW_LIMIT,
                ) { rs -> RankedSnapshot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), longOrNull(rs, 5), rs.getString(6)) }
            },
            combatNpcs = section(tables, "lifesteal_combat_npcs") {
                db.query(
                    "SELECT id, state, outcome, created_at, expires_at, world, x, y, z FROM lifesteal_combat_npcs WHERE owner = ? AND state <> 'COMPLETE' LIMIT ?",
                    id, ROW_LIMIT,
                ) { rs -> CombatNpcSession(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getString(6), rs.getDouble(7), rs.getDouble(8), rs.getDouble(9)) }
            },
            eventInventory = section(tables, "event_inventory_snapshots") {
                db.query(
                    "SELECT id, status, source, created_at, detail FROM event_inventory_snapshots WHERE uuid = ? AND status IN ($EVENT_UNSETTLED) ORDER BY id DESC LIMIT ?",
                    id, ROW_LIMIT,
                ) { rs -> EventInventoryRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5)) }
            },
            bank = section(tables, "lifesteal_bank_accounts", "lifesteal_bank_ledger") {
                val balance = db.queryFirst("SELECT balance_cents FROM lifesteal_bank_accounts WHERE uuid = ?", id) { it.getLong(1) }
                val failed = db.queryFirst(
                    "SELECT COUNT(*) FROM lifesteal_bank_ledger WHERE uuid = ? AND state = 'FAILED' AND reviewed_at IS NULL", id,
                ) { it.getInt(1) } ?: 0
                if (balance == null && failed == 0) null else Bank(balance, failed)
            },
            bounty = section(tables, "lifesteal_bounties", "lifesteal_bounty_payouts", "lifesteal_bounty_contributions") {
                val active = db.queryFirst("SELECT total_cents FROM lifesteal_bounties WHERE target = ? AND state = 'ACTIVE' LIMIT 1", id) { it.getLong(1) }
                val payouts = db.query(
                    "SELECT id, amount_cents, attempts, last_error FROM lifesteal_bounty_payouts WHERE killer = ? AND state = 'PENDING' LIMIT ?",
                    id, ROW_LIMIT,
                ) { rs -> PendingPayout(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getString(4)) }
                val refunds = db.queryFirst(
                    "SELECT COUNT(*) FROM lifesteal_bounty_contributions WHERE contributor = ? AND refund_state = 'PENDING'", id,
                ) { it.getInt(1) } ?: 0
                Bounty(active, payouts, refunds)
            },
        )
    }

    fun health(clock: () -> Long = System::nanoTime): HealthReport {
        val started = clock()
        val tables = try {
            db.queryFirst("SELECT 1") { it.getInt(1) }
            tables()
        } catch (e: Exception) {
            val failed = Section.Failed(message(e))
            return HealthReport(message(e), null, failed, failed, failed, failed, failed, failed)
        }
        val latency = (clock() - started) / 1_000_000L
        return HealthReport(
            databaseProblem = null,
            latencyMillis = latency,
            // Each count matches an existing (partial) index's own condition, so no table scan.
            rankedSnapshots = section(tables, "lifesteal_ranked_snapshots") {
                grouped("SELECT status, COUNT(*) FROM lifesteal_ranked_snapshots WHERE status IN ('PENDING', 'RESTORING', 'REVIEW') GROUP BY status")
            },
            combatNpcsOpen = section(tables, "lifesteal_combat_npcs") {
                grouped("SELECT state, COUNT(*) FROM lifesteal_combat_npcs WHERE state <> 'COMPLETE' GROUP BY state")
            },
            eventInventory = section(tables, "event_inventory_snapshots") {
                grouped("SELECT status, COUNT(*) FROM event_inventory_snapshots WHERE status IN ($EVENT_UNSETTLED) GROUP BY status")
            },
            bountyPayoutsPending = section(tables, "lifesteal_bounty_payouts") {
                db.queryFirst("SELECT COUNT(*) FROM lifesteal_bounty_payouts WHERE state = 'PENDING'") { it.getInt(1) } ?: 0
            },
            bountyRefundsPending = section(tables, "lifesteal_bounty_contributions") {
                db.queryFirst("SELECT COUNT(*) FROM lifesteal_bounty_contributions WHERE refund_state = 'PENDING'") { it.getInt(1) } ?: 0
            },
            bankFailedUnreviewed = section(tables, "lifesteal_bank_ledger") {
                db.queryFirst("SELECT COUNT(*) FROM lifesteal_bank_ledger WHERE state = 'FAILED' AND reviewed_at IS NULL") { it.getInt(1) } ?: 0
            },
        )
    }

    private fun grouped(sql: String): Map<String, Int> =
        db.query(sql) { rs -> rs.getString(1) to rs.getInt(2) }.toMap(sortedMapOf())

    private fun tables(): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(1) }.toSet()

    private inline fun <T> section(tables: Set<String>, vararg required: String, read: () -> T): Section<T> {
        if (required.any { it !in tables }) return Section.NotCreated
        return try {
            Section.Ok(read())
        } catch (e: Exception) {
            Section.Failed(message(e))
        }
    }

    private fun longOrNull(rs: ResultSet, column: String): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
    private fun longOrNull(rs: ResultSet, column: Int): Long? = rs.getLong(column).takeUnless { rs.wasNull() }
    private fun message(e: Exception): String = (e.message ?: e.javaClass.simpleName).take(200)

    companion object {
        const val AUDIT_LIMIT = 5
        const val ROW_LIMIT = 5
        /** The event inventory's unsettled statuses, written the same way as its own partial index. */
        private val EVENT_UNSETTLED = com.liam.joshymc.communityevent.inventory.EventInventoryRules.UNSETTLED.joinToString(",") { "'$it'" }
    }
}
