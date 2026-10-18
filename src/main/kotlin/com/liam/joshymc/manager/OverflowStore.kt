package com.liam.joshymc.manager

import java.util.UUID

/**
 * The rows behind /overflow (issue #1234): one row per stored stack, no per-player cap.
 * No Bukkit, like [PurchaseDeliveryStore], so it can be checked against a real SQLite
 * database on its own. Items arrive already serialized by [OverflowManager]: [StoredStack.item]
 * is one unit of the stack (amount 1) and the row's `amount` is the stack size, so a page
 * only decodes the stacks it shows and topping a stack up never rewrites the item data.
 *
 * Every change runs in one transaction and every withdrawal is a conditional UPDATE on the
 * amount still stored, so two withdrawals can never both take the same items.
 */
class OverflowStore(
    private val db: SqliteDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class StoredStack(val id: Long, val category: String, val item: String, val amount: Int)

    /** One stored item as the caller serialized it: [item] is one unit, [stackKey] is equal for stacks that may merge. */
    data class Encoded(val item: String, val stackKey: String, val maxStack: Int)

    enum class DepositResult { STORED, DUPLICATE }

    data class LegacyResult(val migrated: Int, val unreadable: Int)

    /** Idempotent: safe on every start. */
    fun createSchema() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS overflow_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner TEXT NOT NULL,
                category TEXT NOT NULL,
                stack_key TEXT NOT NULL,
                item TEXT NOT NULL,
                amount INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_overflow_items_page ON overflow_items (owner, category, id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_overflow_items_stack ON overflow_items (owner, category, stack_key)")
        // A deposit made with a reference is recorded here in the same transaction, so a
        // retried delivery (or a re-run migration) finds it and stores nothing twice.
        db.createTable("""
            CREATE TABLE IF NOT EXISTS overflow_deposit_refs (
                reference TEXT PRIMARY KEY,
                owner TEXT NOT NULL,
                category TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    /**
     * Stores [amount] of [encoded] for [owner] in [category]: partial stacks with the same
     * stack key are topped up first, the rest becomes new rows of at most `maxStack`. Existing
     * rows are never replaced. All of it is one transaction, so on any failure nothing was
     * stored and the exception reaches the caller, which still holds the whole delivery.
     */
    fun deposit(owner: UUID, category: String, encoded: Encoded, amount: Int, reference: String? = null): DepositResult {
        require(amount > 0) { "amount must be positive" }
        val max = encoded.maxStack.coerceAtLeast(1)
        var result = DepositResult.STORED
        db.transaction {
            if (reference != null) {
                val recorded = db.executeUpdate(
                    "INSERT OR IGNORE INTO overflow_deposit_refs (reference, owner, category, created_at) VALUES (?, ?, ?, ?)",
                    reference, owner.toString(), category, clock()
                )
                if (recorded == 0) {
                    result = DepositResult.DUPLICATE
                    return@transaction
                }
            }
            var remaining = amount
            val partial = db.query(
                "SELECT id, amount FROM overflow_items WHERE owner = ? AND category = ? AND stack_key = ? AND amount < ? ORDER BY id",
                owner.toString(), category, encoded.stackKey, max
            ) { rs -> rs.getLong("id") to rs.getInt("amount") }
            for ((id, stored) in partial) {
                if (remaining <= 0) break
                val add = minOf(max - stored, remaining)
                if (add <= 0) continue
                val updated = db.executeUpdate(
                    "UPDATE overflow_items SET amount = amount + ? WHERE id = ? AND amount + ? <= ?",
                    add, id, add, max
                )
                if (updated == 1) remaining -= add
            }
            val now = clock()
            while (remaining > 0) {
                val stack = minOf(max, remaining)
                db.execute(
                    "INSERT INTO overflow_items (owner, category, stack_key, item, amount, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    owner.toString(), category, encoded.stackKey, encoded.item, stack, now
                )
                remaining -= stack
            }
        }
        return result
    }

    /** Number of stored stacks (rows). */
    fun countStacks(owner: UUID, category: String): Long =
        db.queryFirst(
            "SELECT COUNT(*) AS cnt FROM overflow_items WHERE owner = ? AND category = ?",
            owner.toString(), category
        ) { rs -> rs.getLong("cnt") } ?: 0L

    /** Total number of stored items across all stacks. */
    fun countItems(owner: UUID, category: String): Long =
        db.queryFirst(
            "SELECT COALESCE(SUM(amount), 0) AS total FROM overflow_items WHERE owner = ? AND category = ?",
            owner.toString(), category
        ) { rs -> rs.getLong("total") } ?: 0L

    /** Stacks of zero-based [page], oldest first. Pages past the end are empty. */
    fun page(owner: UUID, category: String, page: Int, pageSize: Int): List<StoredStack> {
        require(pageSize > 0) { "pageSize must be positive" }
        val offset = page.coerceAtLeast(0).toLong() * pageSize
        return db.query(
            "SELECT id, category, item, amount FROM overflow_items WHERE owner = ? AND category = ? ORDER BY id LIMIT ? OFFSET ?",
            owner.toString(), category, pageSize, offset
        ) { rs -> stack(rs) }
    }

    /** Up to [limit] stacks with an id above [afterId], oldest first (keyset paging for bulk withdrawals). */
    fun after(owner: UUID, category: String, afterId: Long, limit: Int): List<StoredStack> =
        db.query(
            "SELECT id, category, item, amount FROM overflow_items WHERE owner = ? AND category = ? AND id > ? ORDER BY id LIMIT ?",
            owner.toString(), category, afterId, limit
        ) { rs -> stack(rs) }

    fun get(owner: UUID, category: String, id: Long): StoredStack? =
        db.queryFirst(
            "SELECT id, category, item, amount FROM overflow_items WHERE id = ? AND owner = ? AND category = ?",
            id, owner.toString(), category
        ) { rs -> stack(rs) }

    /**
     * Removes [amount] items from stack [id] if it still holds at least that many and still
     * belongs to [owner]'s [category]. Returns false (changing nothing) otherwise, so a second
     * click, a stale page or another withdrawal can never take the same items again.
     */
    fun take(owner: UUID, category: String, id: Long, amount: Int): Boolean {
        if (amount <= 0) return false
        var taken = false
        db.transaction {
            val updated = db.executeUpdate(
                "UPDATE overflow_items SET amount = amount - ? WHERE id = ? AND owner = ? AND category = ? AND amount >= ?",
                amount, id, owner.toString(), category, amount
            )
            if (updated != 1) return@transaction
            db.execute("DELETE FROM overflow_items WHERE id = ? AND amount <= 0", id)
            taken = true
        }
        return taken
    }

    /**
     * Moves the pre-#1234 slot table (`overflow_storage`, fixed slots per storage) into
     * [deposit] rows, oldest storage slot first. Each row is copied with the reference
     * `legacy:<uuid>:<type>:<slot>` and deleted in the same transaction, so a crash or a
     * failure leaves every unmigrated row in the old table and a re-run never imports twice.
     * Rows [decode] can't read stay in `overflow_storage` untouched for staff to recover.
     */
    fun migrateLegacy(decode: (String) -> Pair<Encoded, Int>?): LegacyResult {
        val exists = db.queryFirst(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'overflow_storage'"
        ) { rs -> rs.getString("name") } != null
        if (!exists) return LegacyResult(0, 0)

        data class Legacy(val uuid: String, val type: String, val slot: Int, val item: String)
        val rows = db.query(
            "SELECT uuid, type, slot, item FROM overflow_storage ORDER BY uuid, type, slot"
        ) { rs -> Legacy(rs.getString("uuid"), rs.getString("type"), rs.getInt("slot"), rs.getString("item")) }
        if (rows.isEmpty()) return LegacyResult(0, 0)

        var migrated = 0
        var unreadable = 0
        db.transaction {
            for (row in rows) {
                val owner = runCatching { UUID.fromString(row.uuid) }.getOrNull()
                val decoded = if (owner == null) null else runCatching { decode(row.item) }.getOrNull()
                if (owner == null || decoded == null || decoded.second <= 0) {
                    unreadable++
                    continue
                }
                deposit(owner, row.type, decoded.first, decoded.second, "legacy:${row.uuid}:${row.type}:${row.slot}")
                db.execute(
                    "DELETE FROM overflow_storage WHERE uuid = ? AND type = ? AND slot = ?",
                    row.uuid, row.type, row.slot
                )
                migrated++
            }
        }
        return LegacyResult(migrated, unreadable)
    }

    private fun stack(rs: java.sql.ResultSet) =
        StoredStack(rs.getLong("id"), rs.getString("category"), rs.getString("item"), rs.getInt("amount"))
}
