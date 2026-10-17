package com.liam.joshymc.manager

import com.liam.joshymc.manager.PurchaseRewards.Kind
import java.sql.ResultSet
import java.util.UUID

/**
 * The `purchase_deliveries` table (issue #1175): every store purchase line JoshyMC was
 * asked to fulfill, and what has happened to it. No Bukkit, like [AdminBalanceLedger],
 * so the state machine can be checked against a real SQLite database on its own.
 *
 * Every state change is a conditional UPDATE on the expected current state, so two
 * callers can never both move the same row (and so never both grant it).
 */
class PurchaseDeliveryStore(
    private val db: SqliteDatabase,
    /** `DatabaseManager.insertReturningId`: the insert and its rowid under one lock hold. */
    private val insertReturningId: (String, Array<out Any?>) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State {
        /** Waiting to be delivered (recipient offline, inventory full, or not processed yet). */
        PENDING,
        /** Claimed by one delivery pass. Item rows are only in this state within one server tick. */
        PROCESSING,
        COMPLETED,
        /** Needs an operator: see `last_error`. */
        FAILED,
        /** An item delivery was mid-flight when the server stopped; the items may or may not have arrived. */
        INTERRUPTED,
        /** The store sent a name this server has never seen; held for `/purchase assign`. */
        UNRESOLVED,
        /** The command itself was invalid (unknown type, bad amount, ...); kept so it can be reviewed. */
        REJECTED,
        CANCELLED,
    }

    data class Delivery(
        val id: Int,
        val recipientUuid: UUID?,
        val recipientName: String,
        val rawType: String,
        val param: String,
        val quantity: Int,
        val remaining: Int,
        val state: State,
        val externalRef: String?,
        val dedupeKey: String?,
        val source: String,
        val createdAt: Long,
        val updatedAt: Long,
        val completedAt: Long?,
        val attempts: Int,
        val lastError: String?,
    ) {
        val kind: Kind? get() = Kind.of(rawType)
        val spec: PurchaseRewards.Spec? get() = kind?.let { PurchaseRewards.Spec(it, param, quantity) }
        fun describe(): String = spec?.describe() ?: "$rawType $param".trim()
    }

    sealed class InsertResult {
        data class Inserted(val delivery: Delivery) : InsertResult()
        /** A row with the same dedupe key already exists; nothing was written. */
        data class Duplicate(val existing: Delivery) : InsertResult()
    }

    /** Idempotent: safe on every start. */
    fun createSchema() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS purchase_deliveries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                recipient_uuid TEXT,
                recipient_name TEXT NOT NULL,
                reward_type TEXT NOT NULL,
                reward_param TEXT NOT NULL DEFAULT '',
                quantity INTEGER NOT NULL,
                remaining INTEGER NOT NULL,
                state TEXT NOT NULL,
                external_ref TEXT,
                dedupe_key TEXT UNIQUE,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                CHECK (quantity > 0 AND remaining >= 0 AND remaining <= quantity)
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_purchase_deliveries_recipient ON purchase_deliveries (recipient_uuid, state)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_purchase_deliveries_state ON purchase_deliveries (state)")
    }

    data class Recovery(val interrupted: List<Delivery>, val requeued: List<Delivery>)

    /**
     * Rows left PROCESSING by a stop. Item rows become INTERRUPTED (the items may already be
     * in the player's saved inventory, so only an operator may resend them); rank rows go
     * back to PENDING, since a re-attempt first checks whether the tier already landed.
     */
    fun recoverInterrupted(): Recovery {
        val interrupted = mutableListOf<Delivery>()
        val requeued = mutableListOf<Delivery>()
        for (row in db.query("SELECT * FROM purchase_deliveries WHERE state = ?", State.PROCESSING.name) { map(it) }) {
            val next = if (row.kind?.delivery == PurchaseRewards.Delivery.ITEM) State.INTERRUPTED else State.PENDING
            val changed = db.executeUpdate(
                "UPDATE purchase_deliveries SET state = ?, updated_at = ?, last_error = ? WHERE id = ? AND state = ?",
                next.name, clock(), "interrupted by a server stop during delivery", row.id, State.PROCESSING.name
            )
            if (changed == 1) (if (next == State.INTERRUPTED) interrupted else requeued) += row
        }
        return Recovery(interrupted, requeued)
    }

    /** Counts of rows still needing something, by state. */
    fun openCounts(): Map<State, Int> = db.query(
        "SELECT state, COUNT(*) AS n FROM purchase_deliveries WHERE state IN (?, ?, ?, ?, ?) GROUP BY state",
        State.PENDING.name, State.FAILED.name, State.INTERRUPTED.name, State.UNRESOLVED.name, State.REJECTED.name
    ) { rs -> runCatching { State.valueOf(rs.getString("state")) }.getOrNull()?.let { it to rs.getInt("n") } }
        .filterNotNull().toMap()

    /**
     * Records one validated purchase line as PENDING (or UNRESOLVED without a recipient).
     * With a [dedupeKey] the UNIQUE constraint makes this atomic: of any number of identical
     * referenced commands, exactly one inserts. Database errors are thrown to the caller.
     */
    fun insert(
        recipientUuid: UUID?,
        recipientName: String,
        spec: PurchaseRewards.Spec,
        reference: String?,
        dedupeKey: String?,
        source: String,
    ): InsertResult {
        val state = if (recipientUuid == null) State.UNRESOLVED else State.PENDING
        val time = clock()
        val id = insertReturningId(
            """INSERT OR IGNORE INTO purchase_deliveries
               (recipient_uuid, recipient_name, reward_type, reward_param, quantity, remaining, state,
                external_ref, dedupe_key, source, created_at, updated_at, attempts)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
            arrayOf<Any?>(
                recipientUuid?.toString(), recipientName, spec.kind.id, spec.param, spec.quantity, spec.quantity,
                state.name, reference, dedupeKey, source, time, time
            )
        )
        if (id != null) return InsertResult.Inserted(get(id) ?: error("row #$id missing right after insert"))
        val existing = dedupeKey?.let { findByDedupeKey(it) } ?: error("insert ignored without a matching dedupe key")
        return InsertResult.Duplicate(existing)
    }

    /** Keeps an invalid store command on record (REJECTED) so it shows up in `/purchase list`. */
    fun insertRejected(recipientInput: String, rawType: String, rawArgs: String, reason: String, source: String): Int? {
        val time = clock()
        return insertReturningId(
            """INSERT INTO purchase_deliveries
               (recipient_uuid, recipient_name, reward_type, reward_param, quantity, remaining, state,
                source, created_at, updated_at, attempts, last_error)
               VALUES (?, ?, ?, ?, 1, 1, ?, ?, ?, ?, 0, ?)""",
            arrayOf<Any?>(
                PurchaseRewards.parseUuid(recipientInput)?.toString(), recipientInput.take(64), rawType.take(32),
                rawArgs.take(256), State.REJECTED.name, source, time, time, reason.take(256)
            )
        )
    }

    fun get(id: Int): Delivery? = db.queryFirst("SELECT * FROM purchase_deliveries WHERE id = ?", id) { map(it) }

    fun findByDedupeKey(key: String): Delivery? =
        db.queryFirst("SELECT * FROM purchase_deliveries WHERE dedupe_key = ?", key) { map(it) }

    /** [uuid]'s PENDING rows, oldest first. */
    fun pendingFor(uuid: UUID, limit: Int): List<Delivery> = db.query(
        "SELECT * FROM purchase_deliveries WHERE recipient_uuid = ? AND state = ? ORDER BY id ASC LIMIT ?",
        uuid.toString(), State.PENDING.name, limit
    ) { map(it) }

    fun pendingWithRecipient(limit: Int): List<Delivery> = db.query(
        "SELECT * FROM purchase_deliveries WHERE state = ? AND recipient_uuid IS NOT NULL ORDER BY id ASC LIMIT ?",
        State.PENDING.name, limit
    ) { map(it) }

    /** Newest first; null [states] means every state. */
    fun list(states: Collection<State>?, recipient: UUID?, limit: Int): List<Delivery> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (states != null) {
            where += "state IN (${states.joinToString(",") { "?" }})"
            params.addAll(states.map { it.name })
        }
        if (recipient != null) {
            where += "recipient_uuid = ?"
            params.add(recipient.toString())
        }
        params.add(limit)
        val clause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        return db.query("SELECT * FROM purchase_deliveries $clause ORDER BY id DESC LIMIT ?", *params.toTypedArray()) { map(it) }
    }

    /**
     * PENDING → PROCESSING for this caller only. Returns the row as it now stands (callers
     * must use this, not the snapshot they loaded earlier), or null if it wasn't PENDING.
     */
    fun claim(id: Int, countAttempt: Boolean = false): Delivery? {
        val changed = db.executeUpdate(
            "UPDATE purchase_deliveries SET state = ?, updated_at = ?${if (countAttempt) ", attempts = attempts + 1" else ""} WHERE id = ? AND state = ?",
            State.PROCESSING.name, clock(), id, State.PENDING.name
        )
        return if (changed == 1) get(id) else null
    }

    enum class CurrencyOutcome {
        /** This call moved the row to COMPLETED and [completeCurrency]'s deposit ran, in one transaction. */
        PAID,
        /** The row wasn't PENDING (already paid, cancelled, ...); nothing was deposited. */
        NOT_PENDING,
        /** The balance manager refused the amount; rolled back, row marked FAILED. */
        REJECTED,
    }

    private class DepositRejected : RuntimeException()

    /**
     * PENDING → COMPLETED and the [deposit] in one transaction: either both commit or
     * neither does, so a balance can't be credited without the row closing (or the reverse).
     * A database error rolls back and is thrown; the row is then still PENDING.
     */
    fun completeCurrency(id: Int, deposit: () -> Boolean): CurrencyOutcome {
        var outcome = CurrencyOutcome.NOT_PENDING
        try {
            db.transaction {
                val time = clock()
                val claimed = db.executeUpdate(
                    "UPDATE purchase_deliveries SET state = ?, remaining = 0, completed_at = ?, updated_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ? AND state = ?",
                    State.COMPLETED.name, time, time, id, State.PENDING.name
                ) == 1
                if (!claimed) return@transaction
                if (!deposit()) throw DepositRejected()
                outcome = CurrencyOutcome.PAID
            }
        } catch (e: DepositRejected) {
            markFailed(id, "the balance manager rejected the amount")
            return CurrencyOutcome.REJECTED
        }
        return outcome
    }

    /**
     * Closes an item pass: subtracts what was actually [delivered] (relative, so it can't
     * clobber another writer) and moves PROCESSING → [next].
     */
    fun finishItems(id: Int, delivered: Int, next: State, error: String?): Boolean {
        val time = clock()
        return db.executeUpdate(
            "UPDATE purchase_deliveries SET state = ?, remaining = remaining - ?, updated_at = ?, completed_at = ?, last_error = ? WHERE id = ? AND state = ?",
            next.name, delivered, time, if (next == State.COMPLETED) time else null, error?.take(256), id, State.PROCESSING.name
        ) == 1
    }

    /** PROCESSING → [next] for a whole-row reward (ranks). COMPLETED zeroes `remaining`. */
    fun finish(id: Int, next: State, note: String?): Boolean {
        val time = clock()
        val remainingSql = if (next == State.COMPLETED) "0" else "remaining"
        return db.executeUpdate(
            "UPDATE purchase_deliveries SET state = ?, remaining = $remainingSql, updated_at = ?, completed_at = ?, last_error = ? WHERE id = ? AND state = ?",
            next.name, time, if (next == State.COMPLETED) time else null, note?.take(256), id, State.PROCESSING.name
        ) == 1
    }

    /** PENDING → FAILED: something only an operator can fix (e.g. the crate was deleted). */
    fun markFailed(id: Int, reason: String): Boolean = db.executeUpdate(
        "UPDATE purchase_deliveries SET state = ?, updated_at = ?, last_error = ? WHERE id = ? AND state = ?",
        State.FAILED.name, clock(), reason.take(256), id, State.PENDING.name
    ) == 1

    /** A transient failure on a PENDING row: counted; FAILED once [max] attempts are used up. Returns the new state. */
    fun recordAttemptFailure(id: Int, max: Int, reason: String): State? {
        db.executeUpdate(
            "UPDATE purchase_deliveries SET attempts = attempts + 1, state = CASE WHEN attempts + 1 >= ? THEN ? ELSE ? END, " +
                "updated_at = ?, last_error = ? WHERE id = ? AND state = ?",
            max, State.FAILED.name, State.PENDING.name, clock(), reason.take(256), id, State.PENDING.name
        )
        return get(id)?.state
    }

    /** FAILED / INTERRUPTED → PENDING, keeping `remaining`, so only what is still owed is retried. */
    fun retry(id: Int): Boolean = db.executeUpdate(
        "UPDATE purchase_deliveries SET state = ?, attempts = 0, updated_at = ?, last_error = NULL WHERE id = ? AND state IN (?, ?) AND recipient_uuid IS NOT NULL",
        State.PENDING.name, clock(), id, State.FAILED.name, State.INTERRUPTED.name
    ) == 1

    /** Closes an open row. Never touches COMPLETED or PROCESSING rows. */
    fun cancel(id: Int, reason: String): Boolean = db.executeUpdate(
        "UPDATE purchase_deliveries SET state = ?, updated_at = ?, last_error = ? WHERE id = ? AND state IN (?, ?, ?, ?, ?)",
        State.CANCELLED.name, clock(), reason.take(256), id,
        State.PENDING.name, State.FAILED.name, State.INTERRUPTED.name, State.UNRESOLVED.name, State.REJECTED.name
    ) == 1

    /**
     * UNRESOLVED → PENDING for an operator-chosen recipient. Throws if the new dedupe key
     * collides (the same referenced reward is already recorded for that player).
     */
    fun assign(id: Int, uuid: UUID, name: String, dedupeKey: String?): Boolean = db.executeUpdate(
        "UPDATE purchase_deliveries SET state = ?, recipient_uuid = ?, recipient_name = ?, dedupe_key = ?, updated_at = ?, last_error = NULL WHERE id = ? AND state = ?",
        State.PENDING.name, uuid.toString(), name, dedupeKey, clock(), id, State.UNRESOLVED.name
    ) == 1

    private fun map(rs: ResultSet): Delivery = Delivery(
        id = rs.getInt("id"),
        recipientUuid = rs.getString("recipient_uuid")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        recipientName = rs.getString("recipient_name"),
        rawType = rs.getString("reward_type"),
        param = rs.getString("reward_param") ?: "",
        quantity = rs.getInt("quantity"),
        remaining = rs.getInt("remaining"),
        state = runCatching { State.valueOf(rs.getString("state")) }.getOrDefault(State.FAILED),
        externalRef = rs.getString("external_ref"),
        dedupeKey = rs.getString("dedupe_key"),
        source = rs.getString("source"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
        completedAt = rs.getLong("completed_at").takeIf { !rs.wasNull() },
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
    )
}
