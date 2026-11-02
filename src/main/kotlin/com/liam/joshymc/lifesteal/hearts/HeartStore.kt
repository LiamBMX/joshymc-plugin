package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.lifesteal.hearts.HeartRules.changesHearts
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistent Lifesteal hearts (issue #1341), in the Lifesteal server's own data.db. Bukkit-free:
 * talks only to [SqliteDatabase], so `scripts/LifestealHeartCheck.kt` runs it against real SQLite.
 *
 * - `lifesteal_hearts`: one row per player UUID with the stored hearts, `initial_granted`, a
 *   `version` raised on every change, the last change time and the transaction that made it.
 *   The primary key makes the starting grant happen once ([ensure] is INSERT OR IGNORE).
 * - `lifesteal_heart_cooldowns`: last heart transfer per (killer, victim) pair, for anti-farming.
 * - `lifesteal_heart_transactions`: every evaluated PvP death and every admin change, with a
 *   UNIQUE `dedupe_key`, so the same death can never be processed twice.
 * - `lifesteal_heart_audit`: one row per player per change (previous/new hearts, operation,
 *   reason, actor), plus rejected changes, for investigating lost or duplicated hearts.
 *
 * Every change runs inside one [SqliteDatabase.transaction] (which holds the DB lock), reads the
 * rows fresh, and writes them back with `WHERE version = <read version>`; a row that changed in
 * between makes the update hit nothing, which throws and rolls the whole change back. So a
 * transfer is all-or-nothing and no stale value overwrites a newer one.
 */
class HeartStore(private val db: SqliteDatabase) {

    /**
     * A stored record. [hearts] is null when the stored value can't be read as a whole number
     * (malformed row): it is never guessed or reset, only `set` by an admin repairs it.
     */
    data class Record(
        val uuid: UUID,
        val hearts: Int?,
        val initialGranted: Boolean,
        val version: Long,
        val updatedAt: Long,
        val lastTransaction: String?,
    )

    data class AuditRow(
        val transactionId: String?,
        val uuid: UUID,
        val operation: String,
        val previous: Int?,
        val next: Int?,
        val actor: String?,
        val reason: String?,
        val at: Long,
    )

    /** Thrown inside a transaction to roll it back when a row changed underneath it. */
    class StaleWriteException(message: String) : RuntimeException(message)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_hearts (
                uuid TEXT PRIMARY KEY,
                hearts INTEGER NOT NULL,
                initial_granted INTEGER NOT NULL DEFAULT 1,
                version INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_transaction TEXT
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_heart_cooldowns (
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                last_transfer_at INTEGER NOT NULL,
                PRIMARY KEY (killer, victim)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_heart_transactions (
                id TEXT PRIMARY KEY,
                dedupe_key TEXT NOT NULL UNIQUE,
                type TEXT NOT NULL,
                outcome TEXT NOT NULL,
                killer TEXT,
                victim TEXT,
                actor TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_heart_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT,
                uuid TEXT NOT NULL,
                operation TEXT NOT NULL,
                previous_hearts INTEGER,
                new_hearts INTEGER,
                actor TEXT,
                reason TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_audit_uuid ON lifesteal_heart_audit (uuid, created_at)")
    }

    // ── Reads ───────────────────────────────────────────────

    fun load(uuid: UUID): Record? =
        db.queryFirst("SELECT * FROM lifesteal_hearts WHERE uuid = ?", uuid.toString()) { record(it) }

    fun lastTransferAt(killer: UUID, victim: UUID): Long? =
        db.queryFirst(
            "SELECT last_transfer_at FROM lifesteal_heart_cooldowns WHERE killer = ? AND victim = ?",
            killer.toString(), victim.toString()
        ) { rs -> rs.getLong("last_transfer_at").takeUnless { rs.wasNull() } }

    fun audit(uuid: UUID): List<AuditRow> =
        db.query(
            "SELECT * FROM lifesteal_heart_audit WHERE uuid = ? ORDER BY id",
            uuid.toString()
        ) { auditRow(it) }

    fun transactionCount(): Int =
        db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_transactions") { it.getInt("n") } ?: 0

    // ── Initial grant ───────────────────────────────────────

    /**
     * The player's record, created with [startingHearts] the first time (with an INITIAL audit
     * row). The primary key makes the grant happen once however often this runs (reconnects,
     * two joins racing): a second call only reads.
     */
    fun ensure(uuid: UUID, startingHearts: Int, now: Long): Record {
        var result: Record? = null
        db.transaction {
            val inserted = db.executeUpdate(
                "INSERT OR IGNORE INTO lifesteal_hearts (uuid, hearts, initial_granted, version, created_at, updated_at, last_transaction) " +
                    "VALUES (?, ?, 1, 0, ?, ?, NULL)",
                uuid.toString(), startingHearts, now, now
            ) == 1
            if (inserted) insertAudit(null, uuid, "INITIAL", null, startingHearts, null, "starting hearts", now)
            result = load(uuid) ?: throw StaleWriteException("heart record for $uuid vanished right after it was created")
        }
        return result!!
    }

    // ── PvP transfers ───────────────────────────────────────

    data class PvpRequest(
        val dedupeKey: String,
        val killer: UUID,
        val victim: UUID,
        val now: Long,
        val cooldownMillis: Long,
        val limits: HeartRules.Limits,
        val policy: HeartRules.KillerAtMaximumPolicy = HeartRules.KILLER_AT_MAXIMUM,
    )

    sealed interface PvpResult {
        /** The death was evaluated (and recorded under [transactionId]). */
        data class Done(
            val transactionId: String,
            val decision: HeartRules.Decision,
            val victimBefore: Int,
            val victimAfter: Int,
            val killerBefore: Int,
            val killerAfter: Int,
        ) : PvpResult
        /** This death ([PvpRequest.dedupeKey]) was already processed: nothing happened again. */
        data object Duplicate : PvpResult
        /** A record is missing or unreadable, so nothing was changed (the refusal is audited). */
        data class Unavailable(val reason: String) : PvpResult
    }

    /**
     * One PvP death as one transaction: dedupe check, fresh read of both records and the pair's
     * cooldown, [HeartRules.decide], then (only for a transfer) both conditional updates, the
     * cooldown, the transaction row and both audit rows. Rejections (last heart, cooldown, killer
     * at maximum) are recorded too, without touching hearts or the cooldown. Any database error
     * rolls everything back and is rethrown: hearts are then exactly as before.
     */
    fun transferPvp(request: PvpRequest, transactionId: String = UUID.randomUUID().toString()): PvpResult {
        require(request.killer != request.victim) { "a player can't steal their own heart" }
        var result: PvpResult? = null
        db.transaction {
            if (dedupeExists(request.dedupeKey)) {
                result = PvpResult.Duplicate
                return@transaction
            }
            val victim = load(request.victim)
            val killer = load(request.killer)
            val victimHearts = victim?.hearts
            val killerHearts = killer?.hearts
            if (victim == null || killer == null || victimHearts == null || killerHearts == null) {
                val reason = when {
                    victim == null -> "victim has no heart record"
                    killer == null -> "killer has no heart record"
                    victimHearts == null -> "victim's heart record is unreadable"
                    else -> "killer's heart record is unreadable"
                }
                insertTransaction(transactionId, request.dedupeKey, "PVP", "UNAVAILABLE", request.killer, request.victim, null, request.now)
                insertAudit(transactionId, request.victim, "PVP_REJECTED", victimHearts, victimHearts, request.killer.toString(), reason, request.now)
                result = PvpResult.Unavailable(reason)
                return@transaction
            }

            val remaining = HeartRules.cooldownRemaining(lastTransferAt(request.killer, request.victim), request.now, request.cooldownMillis)
            val decision = HeartRules.decide(victimHearts, killerHearts, remaining, request.limits, request.policy)
            val victimBefore = request.limits.clamp(victimHearts)
            val killerBefore = request.limits.clamp(killerHearts)
            var victimAfter = victimBefore
            var killerAfter = killerBefore
            when (decision) {
                is HeartRules.Decision.Transfer -> {
                    victimAfter = decision.victimAfter
                    killerAfter = decision.killerAfter
                }
                is HeartRules.Decision.VictimLosesOnly -> victimAfter = decision.victimAfter
                else -> {}
            }

            insertTransaction(transactionId, request.dedupeKey, "PVP", outcome(decision), request.killer, request.victim, null, request.now)
            if (decision.changesHearts) {
                if (victimAfter != victimHearts) writeHearts(victim, victimAfter, transactionId, request.now)
                if (killerAfter != killerHearts) writeHearts(killer, killerAfter, transactionId, request.now)
                db.executeUpdate(
                    "INSERT INTO lifesteal_heart_cooldowns (killer, victim, last_transfer_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT (killer, victim) DO UPDATE SET last_transfer_at = excluded.last_transfer_at",
                    request.killer.toString(), request.victim.toString(), request.now
                )
                insertAudit(transactionId, request.victim, "PVP_LOSS", victimHearts, victimAfter, request.killer.toString(), "killed by ${request.killer}", request.now)
                if (decision is HeartRules.Decision.Transfer) {
                    insertAudit(transactionId, request.killer, "PVP_GAIN", killerHearts, killerAfter, request.victim.toString(), "killed ${request.victim}", request.now)
                }
            } else {
                insertAudit(transactionId, request.victim, "PVP_REJECTED", victimHearts, victimHearts, request.killer.toString(), outcome(decision), request.now)
            }
            result = PvpResult.Done(transactionId, decision, victimBefore, victimAfter, killerBefore, killerAfter)
        }
        return result!!
    }

    // ── Administrative changes ──────────────────────────────

    sealed interface AdminResult {
        data class Changed(val transactionId: String, val before: Int?, val after: Int, val clamped: Boolean) : AdminResult
        /** Already at that value: nothing written. */
        data class Unchanged(val hearts: Int) : AdminResult
        data class Invalid(val reason: String) : AdminResult
        /** The player has no heart record on this server (never played Lifesteal here). */
        data object NoRecord : AdminResult
    }

    /**
     * `/lifesteal hearts set|add|remove` as one transaction (fresh read, [HeartRules.adminChange],
     * conditional update, transaction + audit rows). [actor] is the staff UUID, or "console".
     */
    fun adminChange(
        uuid: UUID,
        op: HeartRules.AdminOp,
        amount: Int,
        limits: HeartRules.Limits,
        actor: String,
        actorName: String,
        now: Long,
        transactionId: String = UUID.randomUUID().toString(),
    ): AdminResult {
        var result: AdminResult? = null
        db.transaction {
            val record = load(uuid)
            if (record == null) {
                result = AdminResult.NoRecord
                return@transaction
            }
            when (val decision = HeartRules.adminChange(op, record.hearts, amount, limits)) {
                is HeartRules.AdminDecision.Invalid -> result = AdminResult.Invalid(decision.reason)
                is HeartRules.AdminDecision.Change -> {
                    if (decision.after == record.hearts) {
                        result = AdminResult.Unchanged(decision.after)
                        return@transaction
                    }
                    val operation = "ADMIN_${op.name}"
                    insertTransaction(transactionId, "admin:$transactionId", "ADMIN", operation, null, uuid, actor, now)
                    writeHearts(record, decision.after, transactionId, now)
                    insertAudit(transactionId, uuid, operation, record.hearts, decision.after, actor, "$actorName: ${op.id} $amount", now)
                    result = AdminResult.Changed(transactionId, record.hearts, decision.after, decision.clamped)
                }
            }
        }
        return result!!
    }

    // ── Internals ───────────────────────────────────────────

    private fun writeHearts(record: Record, hearts: Int, transactionId: String, now: Long) {
        val updated = db.executeUpdate(
            "UPDATE lifesteal_hearts SET hearts = ?, version = version + 1, updated_at = ?, last_transaction = ? " +
                "WHERE uuid = ? AND version = ?",
            hearts, now, transactionId, record.uuid.toString(), record.version
        )
        if (updated != 1) throw StaleWriteException("heart record for ${record.uuid} changed during transaction $transactionId")
    }

    private fun dedupeExists(key: String): Boolean =
        db.queryFirst("SELECT 1 AS hit FROM lifesteal_heart_transactions WHERE dedupe_key = ?", key) { true } == true

    private fun insertTransaction(id: String, dedupeKey: String, type: String, outcome: String, killer: UUID?, victim: UUID?, actor: String?, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_heart_transactions (id, dedupe_key, type, outcome, killer, victim, actor, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            id, dedupeKey, type, outcome, killer?.toString(), victim?.toString(), actor, now
        )
    }

    private fun insertAudit(transactionId: String?, uuid: UUID, operation: String, previous: Int?, next: Int?, actor: String?, reason: String?, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_heart_audit (transaction_id, uuid, operation, previous_hearts, new_hearts, actor, reason, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            transactionId, uuid.toString(), operation, previous, next, actor, reason, now
        )
    }

    private fun outcome(decision: HeartRules.Decision): String = when (decision) {
        is HeartRules.Decision.Transfer -> "TRANSFERRED"
        is HeartRules.Decision.VictimLosesOnly -> "VICTIM_LOST_ONLY"
        HeartRules.Decision.VictimAtMinimum -> "VICTIM_AT_MINIMUM"
        HeartRules.Decision.KillerAtMaximum -> "KILLER_AT_MAXIMUM"
        is HeartRules.Decision.Cooldown -> "COOLDOWN"
    }

    private fun record(rs: ResultSet): Record = Record(
        uuid = UUID.fromString(rs.getString("uuid")),
        hearts = wholeHearts(rs.getObject("hearts")),
        initialGranted = rs.getInt("initial_granted") != 0,
        version = rs.getLong("version"),
        updatedAt = rs.getLong("updated_at"),
        lastTransaction = rs.getString("last_transaction"),
    )

    private fun auditRow(rs: ResultSet): AuditRow = AuditRow(
        transactionId = rs.getString("transaction_id"),
        uuid = UUID.fromString(rs.getString("uuid")),
        operation = rs.getString("operation"),
        previous = rs.getObject("previous_hearts")?.let { wholeHearts(it) },
        next = rs.getObject("new_hearts")?.let { wholeHearts(it) },
        actor = rs.getString("actor"),
        reason = rs.getString("reason"),
        at = rs.getLong("created_at"),
    )

    companion object {
        /** A stored hearts value as a whole number, or null when it isn't one (malformed row). */
        fun wholeHearts(raw: Any?): Int? = when (raw) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            is Short -> raw.toInt()
            is Byte -> raw.toInt()
            is Double -> raw.takeIf { it.isFinite() && it == Math.floor(it) && kotlin.math.abs(it) < Int.MAX_VALUE }?.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        }
    }
}
