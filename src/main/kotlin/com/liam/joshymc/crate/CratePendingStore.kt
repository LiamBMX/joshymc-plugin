package com.liam.joshymc.crate

import com.liam.joshymc.manager.SqliteDatabase

/**
 * `crate_pending_rewards` with durable transaction states (issue #1529). Bukkit-free: talks only
 * to [SqliteDatabase], items are the Base64 snapshots CrateManager already stores.
 *
 * A row's [Status]:
 *  - [Status.RESERVED]: written in the same tick a key was taken from the live inventory, before
 *    that inventory was saved. Never delivered. Promoted to PENDING right after `saveData()`
 *    returned; one still RESERVED at startup means the process died between those two writes
 *    (nobody can tell whether the key's removal reached the player file), so it becomes REVIEW.
 *  - [Status.PENDING]: the key is durably gone (or no key was involved); deliverable.
 *  - [Status.DELIVERING]: one delivery attempt owns it (conditional PENDING -> DELIVERING, so only
 *    one caller ever delivers a row). The attempt marks every delivered stack with a fresh
 *    [Row.deliveryToken] and saves the player before acknowledging; a row still DELIVERING at the
 *    next join is reconciled by counting the stacks with that token ([CrateDeliveryRecovery]).
 *  - [Status.REVIEW]: ambiguous after a crash; held with its evidence until staff grant (back to
 *    PENDING) or void it. Never delivered, never deleted automatically.
 *  - [Status.VOIDED]: staff decided it was not owed. Kept as evidence.
 *
 * Rows from before #1529 had no status and were all deliverable, so the migration gives them
 * PENDING. A fully delivered row is deleted (that DELETE is the acknowledgement), as before.
 */
class CratePendingStore(private val db: SqliteDatabase) {

    enum class Status { RESERVED, PENDING, DELIVERING, REVIEW, VOIDED }

    data class Row(
        val id: String,
        val owner: String,
        val item: String,
        val label: String,
        val createdAt: Long,
        val status: Status,
        val deliveryToken: String?,
        val note: String?,
        val updatedAt: Long?
    )

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS crate_pending_rewards (
                id TEXT PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                item TEXT NOT NULL,
                label TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        migrate()
    }

    /** Adds the #1529 columns to an older table. Existing rows keep every value and become PENDING. */
    fun migrate() {
        val columns = db.query("PRAGMA table_info(crate_pending_rewards)") { it.getString("name") }.toSet()
        if ("status" !in columns) db.execute("ALTER TABLE crate_pending_rewards ADD COLUMN status TEXT NOT NULL DEFAULT '${Status.PENDING.name}'")
        if ("delivery_token" !in columns) db.execute("ALTER TABLE crate_pending_rewards ADD COLUMN delivery_token TEXT")
        if ("note" !in columns) db.execute("ALTER TABLE crate_pending_rewards ADD COLUMN note TEXT")
        if ("updated_at" !in columns) db.execute("ALTER TABLE crate_pending_rewards ADD COLUMN updated_at INTEGER")
        db.execute("CREATE INDEX IF NOT EXISTS idx_crate_pending_owner ON crate_pending_rewards (owner_uuid, status)")
    }

    private fun map(rs: java.sql.ResultSet) = Row(
        id = rs.getString("id"),
        owner = rs.getString("owner_uuid"),
        item = rs.getString("item"),
        label = rs.getString("label"),
        createdAt = rs.getLong("created_at"),
        // An unknown status is never treated as deliverable.
        status = Status.entries.firstOrNull { it.name == rs.getString("status") } ?: Status.REVIEW,
        deliveryToken = rs.getString("delivery_token"),
        note = rs.getString("note"),
        updatedAt = (rs.getObject("updated_at") as? Number)?.toLong()
    )

    private val columns = "id, owner_uuid, item, label, created_at, status, delivery_token, note, updated_at"

    /** Inserts a new row. False when [id] already exists (a duplicate operation id is never overwritten). */
    fun insert(id: String, owner: String, item: String, label: String, now: Long, status: Status): Boolean {
        require(status == Status.RESERVED || status == Status.PENDING) { "new rows are RESERVED or PENDING" }
        return db.executeUpdate(
            "INSERT OR IGNORE INTO crate_pending_rewards (id, owner_uuid, item, label, created_at, status, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            id, owner, item, label, now, status.name, now
        ) == 1
    }

    fun row(id: String): Row? =
        db.queryFirst("SELECT $columns FROM crate_pending_rewards WHERE id = ?", id) { map(it) }

    fun rowsOf(owner: String, status: Status): List<Row> =
        db.query("SELECT $columns FROM crate_pending_rewards WHERE owner_uuid = ? AND status = ? ORDER BY created_at ASC, id ASC", owner, status.name) { map(it) }

    fun rowsWith(status: Status, limit: Int = 50): List<Row> =
        db.query("SELECT $columns FROM crate_pending_rewards WHERE status = ? ORDER BY created_at ASC, id ASC LIMIT ?", status.name, limit) { map(it) }

    fun countOf(owner: String, status: Status): Int =
        db.queryFirst("SELECT COUNT(*) AS n FROM crate_pending_rewards WHERE owner_uuid = ? AND status = ?", owner, status.name) { it.getInt("n") } ?: 0

    /** Owners with at least one deliverable row. */
    fun ownersWithPending(): List<String> =
        db.query("SELECT DISTINCT owner_uuid FROM crate_pending_rewards WHERE status = ?", Status.PENDING.name) { it.getString("owner_uuid") }

    /** RESERVED -> PENDING once the key's removal was saved. Returns how many rows moved. */
    fun confirm(id: String, now: Long): Boolean = move(id, Status.RESERVED, Status.PENDING, now)

    /** RESERVED -> REVIEW with [note] (the save failed, or found at startup). */
    fun quarantine(id: String, note: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, note = ?, updated_at = ? WHERE id = ? AND status = ?",
            Status.REVIEW.name, note, now, id, Status.RESERVED.name
        ) == 1

    /** Startup: every row still RESERVED was interrupted mid-transaction. Returns the rows moved to REVIEW. */
    fun quarantineReserved(note: String, now: Long): List<Row> {
        val stuck = rowsWith(Status.RESERVED, Int.MAX_VALUE)
        return stuck.filter { quarantine(it.id, note, now) }.map { it.copy(status = Status.REVIEW, note = note, updatedAt = now) }
    }

    /** PENDING -> DELIVERING with a fresh [token] for this attempt. Only one caller ever wins. */
    fun beginDelivery(id: String, owner: String, token: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, delivery_token = ?, updated_at = ? WHERE id = ? AND owner_uuid = ? AND status = ?",
            Status.DELIVERING.name, token, now, id, owner, Status.PENDING.name
        ) == 1

    /** Acknowledges a complete delivery: deletes the row, only while it is DELIVERING with [token]. */
    fun completeDelivery(id: String, token: String): Boolean =
        db.executeUpdate(
            "DELETE FROM crate_pending_rewards WHERE id = ? AND status = ? AND delivery_token = ?",
            id, Status.DELIVERING.name, token
        ) == 1

    /** Part was delivered: the row keeps only [remainingItem] and is PENDING again. */
    fun returnRemainder(id: String, token: String, remainingItem: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, item = ?, delivery_token = NULL, updated_at = ? WHERE id = ? AND status = ? AND delivery_token = ?",
            Status.PENDING.name, remainingItem, now, id, Status.DELIVERING.name, token
        ) == 1

    /** Nothing of this attempt reached the player: back to PENDING unchanged. */
    fun abortDelivery(id: String, token: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, delivery_token = NULL, updated_at = ? WHERE id = ? AND status = ? AND delivery_token = ?",
            Status.PENDING.name, now, id, Status.DELIVERING.name, token
        ) == 1

    /** DELIVERING -> REVIEW (the attempt can't be reconciled, e.g. its stored item no longer decodes). */
    fun reviewDelivering(id: String, token: String?, note: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, note = ?, updated_at = ? WHERE id = ? AND status = ? AND delivery_token IS ?",
            Status.REVIEW.name, note, now, id, Status.DELIVERING.name, token
        ) == 1

    /** Staff: REVIEW -> PENDING (the reward is owed and will be delivered once). */
    fun grant(id: String, actor: String, now: Long): Boolean = resolveReview(id, Status.PENDING, "granted by $actor", now)

    /** Staff: REVIEW -> VOIDED (not owed; the row stays as evidence). */
    fun void(id: String, actor: String, now: Long): Boolean = resolveReview(id, Status.VOIDED, "voided by $actor", now)

    private fun resolveReview(id: String, to: Status, what: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, note = COALESCE(note || '; ', '') || ?, updated_at = ? WHERE id = ? AND status = ?",
            to.name, what, now, id, Status.REVIEW.name
        ) == 1

    private fun move(id: String, from: Status, to: Status, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE crate_pending_rewards SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
            to.name, now, id, from.name
        ) == 1
}

/**
 * How a crate reward row may be delivered to its owner right now (issue #1529). Bukkit-free.
 * The event world swaps in a temporary inventory that is wiped when the player leaves, so a
 * permanent reward is never put there: it stays PENDING ([Route.HOLD]) until they're out. Mod
 * Mode keeps the real inventory in `modmode_backups` (SQLite), so delivery there is one database
 * transaction ([Route.BACKUP]); otherwise it goes into the live inventory ([Route.INVENTORY]).
 */
object CrateDeliveryRules {
    enum class Route { HOLD, BACKUP, INVENTORY }

    fun route(inEventWorld: Boolean, modMode: Boolean): Route = when {
        inEventWorld -> Route.HOLD
        modMode -> Route.BACKUP
        else -> Route.INVENTORY
    }
}

/**
 * Reconciling a row left DELIVERING by a crash (issue #1529). Bukkit-free.
 *
 * A delivery attempt marks every stack it puts in the inventory with its own token and calls
 * `saveData()` before it acknowledges the row, all in one server tick (autosave can't run in
 * between, and the player file is replaced atomically). At the next join the inventory is the
 * last saved file, so the stacks carrying the row's token are exactly what that attempt durably
 * delivered: none = the save never happened (deliver again), all = only the acknowledgement was
 * lost (done), some = the rest is still owed.
 */
object CrateDeliveryRecovery {
    sealed interface Outcome {
        data object NotDelivered : Outcome
        data object Delivered : Outcome
        data class Remaining(val amount: Int) : Outcome
        /** The stored reward can't be read, so nothing can be compared: staff review. */
        data object Unknown : Outcome
    }

    fun resolve(rowAmount: Int?, markedAmount: Int): Outcome = when {
        rowAmount == null || rowAmount <= 0 -> Outcome.Unknown
        markedAmount <= 0 -> Outcome.NotDelivered
        markedAmount >= rowAmount -> Outcome.Delivered
        else -> Outcome.Remaining(rowAmount - markedAmount)
    }
}

/**
 * The persistence ordering of crate rewards (issue #1529), Bukkit-free so the checks can inject
 * crashes at every step. CrateManager supplies the real inventory, `saveData()` and item codec.
 *
 * Key removal ([reserveThenCommit]): the caller has already taken the key from the live
 * inventory (memory only). Then: insert RESERVED -> save the player -> RESERVED to PENDING.
 *
 * Delivery ([deliver]): PENDING -> DELIVERING (token) -> put the token-marked stack in the
 * inventory -> save the player -> delete the row (or keep the remainder as PENDING) -> strip the
 * token. If the save throws, the row stays DELIVERING and the marks stay on the items, so the
 * next join reconciles it.
 */
object CrateDeliveryProtocol {

    /** Thrown by test hooks to simulate the process dying at that point. */
    class SimulatedCrash : RuntimeException("simulated crash")

    /** Returns false (and quarantines the rows) when the save failed while the server kept running. */
    fun reserveThenCommit(
        store: CratePendingStore,
        rows: List<PendingInsert>,
        save: () -> Unit,
        now: () -> Long,
        onSaveFailed: (Exception) -> Unit = {}
    ): Boolean {
        for (r in rows) check(store.insert(r.id, r.owner, r.item, r.label, now(), CratePendingStore.Status.RESERVED)) { "crate reward id ${r.id} already exists" }
        try {
            save()
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Exception) {
            onSaveFailed(e)
            for (r in rows) store.quarantine(r.id, "player data save failed after the key was taken: ${e.message}", now())
            return false
        }
        for (r in rows) store.confirm(r.id, now())
        return true
    }

    data class PendingInsert(val id: String, val owner: String, val item: String, val label: String)

    sealed interface Result {
        data object NotClaimed : Result
        data object Delivered : Result
        data class Partial(val remaining: Int) : Result
        /** Nothing fit: back to PENDING untouched. */
        data object NothingFit : Result
        /** Delivered but the save or acknowledgement failed; the next join reconciles it. */
        data object Unconfirmed : Result
    }

    /**
     * [deposit] puts the item marked with the token into the inventory and returns the amount
     * that didn't fit plus its unmarked serialized form (null when everything fit).
     */
    fun deliver(
        store: CratePendingStore,
        id: String,
        owner: String,
        token: String,
        amount: Int,
        deposit: (token: String) -> Pair<Int, String>?,
        save: () -> Unit,
        stripToken: (token: String) -> Unit,
        now: () -> Long,
        onFailure: (String, Exception) -> Unit = { _, _ -> }
    ): Result {
        if (!store.beginDelivery(id, owner, token, now())) return Result.NotClaimed
        val leftover = deposit(token)
        if (leftover != null && leftover.first >= amount) {
            // Nothing was placed (the deposit kept the whole stack), so nothing needs saving.
            store.abortDelivery(id, token, now())
            return Result.NothingFit
        }
        try {
            save()
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Exception) {
            onFailure("player data save failed", e)
            return Result.Unconfirmed
        }
        val acknowledged = try {
            if (leftover == null) store.completeDelivery(id, token)
            else store.returnRemainder(id, token, leftover.second, now())
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Exception) {
            onFailure("acknowledgement failed", e)
            false
        }
        if (!acknowledged) return Result.Unconfirmed
        stripToken(token)
        return if (leftover == null) Result.Delivered else Result.Partial(leftover.first)
    }

    /**
     * Settles one row left DELIVERING (see [CrateDeliveryRecovery]). [rowAmount] is the stored
     * item's amount (null = undecodable), [marked] how many items carrying the row's token the
     * loaded inventory holds, [remainderItem] the stored item serialized with a given amount.
     */
    fun reconcile(
        store: CratePendingStore,
        row: CratePendingStore.Row,
        rowAmount: Int?,
        marked: Int,
        remainderItem: (Int) -> String,
        now: Long
    ): CrateDeliveryRecovery.Outcome {
        val token = row.deliveryToken
        val outcome = if (token == null) CrateDeliveryRecovery.Outcome.Unknown else CrateDeliveryRecovery.resolve(rowAmount, marked)
        when (outcome) {
            CrateDeliveryRecovery.Outcome.Delivered -> store.completeDelivery(row.id, token!!)
            CrateDeliveryRecovery.Outcome.NotDelivered -> store.abortDelivery(row.id, token!!, now)
            is CrateDeliveryRecovery.Outcome.Remaining -> store.returnRemainder(row.id, token!!, remainderItem(outcome.amount), now)
            CrateDeliveryRecovery.Outcome.Unknown ->
                store.reviewDelivering(row.id, token, "interrupted delivery could not be reconciled (marked=$marked)", now)
        }
        return outcome
    }
}
