package com.liam.joshymc.candyshop

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The Candy Shop's tables (issue #1211). No Bukkit, like PurchaseDeliveryStore, so the state
 * machine can be checked against a real SQLite database on its own.
 *
 *  - `candy_shop_items`: the admin-managed pool. Ids are AUTOINCREMENT, so a removed entry's id
 *    is never handed to a new one. Removal is a soft delete (kept for history and audit).
 *  - `candy_shop_openings`: four rows per day, UNIQUE(day, slot), so a day is generated once.
 *  - `candy_shop_stock`: the three candies of an opening, with the price and chances copied in
 *    when it opened. Pool edits never change an opening that already picked its stock.
 *  - `candy_shop_purchases`: one row per purchase (PAID → DELIVERED or REFUNDED).
 *  - `candy_shop_audit`: every admin change.
 *
 * Every state change is a conditional UPDATE on the expected current state, so a transition
 * (and its announcement) can only happen once.
 */
class CandyShopStore(
    private val db: SqliteDatabase,
    /** `DatabaseManager.insertReturningId`: the insert and its rowid under one lock hold. */
    private val insertReturningId: (String, Array<out Any?>) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class OpeningStatus {
        SCHEDULED,
        OPEN,
        CLOSED,
        /** The whole window passed while the server was offline. Never replayed. */
        MISSED,
        /** The window came but the pool couldn't fill three slots (see `note`). */
        SKIPPED,
    }

    enum class PurchaseStatus {
        /** Money taken, candy not confirmed yet. Left over only if the server died in between. */
        PAID,
        DELIVERED,
        /** The candy couldn't be stored, so the money went back. */
        REFUNDED,
    }

    data class PoolEntry(
        val id: Int,
        val itemId: String,
        val price: Double,
        val chancePercent: Double,
        val enabled: Boolean,
        val removed: Boolean,
        val createdAt: Long,
        val createdByName: String,
    )

    data class Opening(
        val id: Int,
        val day: LocalDate,
        val slot: Int,
        val zone: String,
        val start: Instant,
        val end: Instant,
        val status: OpeningStatus,
        val note: String?,
    ) {
        fun isActiveAt(now: Instant): Boolean = !now.isBefore(start) && now.isBefore(end)
    }

    /** One of an opening's three candies, as it was when the opening picked it. */
    data class StockItem(
        val openingId: Int,
        val position: Int,
        val entryId: Int,
        val itemId: String,
        val price: Double,
        val chancePercent: Double,
        val effectivePercent: Double,
    )

    fun createTables() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS candy_shop_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_id TEXT NOT NULL,
                price REAL NOT NULL,
                chance REAL NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                removed INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                created_by TEXT,
                created_by_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                removed_at INTEGER,
                removed_by_name TEXT
            )
        """.trimIndent())
        db.createTable("""
            CREATE TABLE IF NOT EXISTS candy_shop_openings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                day TEXT NOT NULL,
                slot INTEGER NOT NULL,
                zone TEXT NOT NULL,
                start_at INTEGER NOT NULL,
                end_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                opened_at INTEGER,
                closed_at INTEGER,
                note TEXT,
                UNIQUE(day, slot)
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_candy_openings_start ON candy_shop_openings(start_at)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS candy_shop_stock (
                opening_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                entry_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                price REAL NOT NULL,
                chance REAL NOT NULL,
                effective_chance REAL NOT NULL,
                PRIMARY KEY (opening_id, position),
                UNIQUE (opening_id, entry_id),
                UNIQUE (opening_id, item_id)
            )
        """.trimIndent())
        db.createTable("""
            CREATE TABLE IF NOT EXISTS candy_shop_purchases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                opening_id INTEGER NOT NULL,
                entry_id INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                price REAL NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                note TEXT
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_candy_purchases_uuid ON candy_shop_purchases(uuid)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS candy_shop_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                actor_uuid TEXT,
                actor_name TEXT NOT NULL,
                action TEXT NOT NULL,
                entry_id INTEGER,
                details TEXT NOT NULL
            )
        """.trimIndent())
    }

    // ── Pool ─────────────────────────────────────────────

    private fun readEntry(rs: ResultSet) = PoolEntry(
        id = rs.getInt("id"),
        itemId = rs.getString("item_id"),
        price = rs.getDouble("price"),
        chancePercent = rs.getDouble("chance"),
        enabled = rs.getInt("enabled") != 0,
        removed = rs.getInt("removed") != 0,
        createdAt = rs.getLong("created_at"),
        createdByName = rs.getString("created_by_name"),
    )

    /** Every entry not removed, oldest first. */
    fun listEntries(): List<PoolEntry> =
        db.query("SELECT * FROM candy_shop_items WHERE removed = 0 ORDER BY id", mapper = ::readEntry)

    fun entry(id: Int): PoolEntry? =
        db.queryFirst("SELECT * FROM candy_shop_items WHERE id = ? AND removed = 0", id, mapper = ::readEntry)

    /** The live (not removed) entry for [itemId], if there is one. */
    fun entryForItem(itemId: String): PoolEntry? =
        db.queryFirst("SELECT * FROM candy_shop_items WHERE item_id = ? AND removed = 0 ORDER BY id LIMIT 1", itemId, mapper = ::readEntry)

    /** Adds an entry and its audit row in one transaction; returns the new stable id. */
    fun addEntry(itemId: String, price: Double, chancePercent: Double, actorUuid: UUID?, actorName: String): Int {
        var id: Int? = null
        db.transaction {
            val now = clock()
            id = insertReturningId(
                "INSERT INTO candy_shop_items (item_id, price, chance, enabled, removed, created_at, created_by, created_by_name, updated_at) " +
                    "VALUES (?, ?, ?, 1, 0, ?, ?, ?, ?)",
                arrayOf<Any?>(itemId, price, chancePercent, now, actorUuid?.toString(), actorName, now),
            ) ?: error("candy_shop_items insert returned no id")
            audit(actorUuid, actorName, "ADD", id, "item=$itemId price=$price chance=$chancePercent%")
        }
        return id!!
    }

    /** Soft-deletes entry [id]; false when it doesn't exist (or was already removed). */
    fun removeEntry(id: Int, actorUuid: UUID?, actorName: String): Boolean =
        updateEntry(id, actorUuid, actorName, "REMOVE", "removed",
            "UPDATE candy_shop_items SET removed = 1, enabled = 0, removed_at = ?, removed_by_name = ?, updated_at = ? WHERE id = ? AND removed = 0",
        ) { now -> arrayOf<Any?>(now, actorName, now, id) }

    fun setPrice(id: Int, price: Double, actorUuid: UUID?, actorName: String): Boolean =
        updateEntry(id, actorUuid, actorName, "SET_PRICE", "price=$price",
            "UPDATE candy_shop_items SET price = ?, updated_at = ? WHERE id = ? AND removed = 0",
        ) { now -> arrayOf<Any?>(price, now, id) }

    fun setChance(id: Int, chancePercent: Double, actorUuid: UUID?, actorName: String): Boolean =
        updateEntry(id, actorUuid, actorName, "SET_CHANCE", "chance=$chancePercent%",
            "UPDATE candy_shop_items SET chance = ?, updated_at = ? WHERE id = ? AND removed = 0",
        ) { now -> arrayOf<Any?>(chancePercent, now, id) }

    fun setEnabled(id: Int, enabled: Boolean, actorUuid: UUID?, actorName: String): Boolean =
        updateEntry(id, actorUuid, actorName, if (enabled) "ENABLE" else "DISABLE", "enabled=$enabled",
            "UPDATE candy_shop_items SET enabled = ?, updated_at = ? WHERE id = ? AND removed = 0 AND enabled = ?",
        ) { now -> arrayOf<Any?>(if (enabled) 1 else 0, now, id, if (enabled) 0 else 1) }

    private fun updateEntry(
        id: Int, actorUuid: UUID?, actorName: String, action: String, details: String,
        sql: String, params: (Long) -> Array<Any?>,
    ): Boolean {
        var changed = false
        db.transaction {
            changed = db.executeUpdate(sql, *params(clock())) == 1
            if (changed) audit(actorUuid, actorName, action, id, details)
        }
        return changed
    }

    fun audit(actorUuid: UUID?, actorName: String, action: String, entryId: Int?, details: String) {
        db.execute(
            "INSERT INTO candy_shop_audit (at, actor_uuid, actor_name, action, entry_id, details) VALUES (?, ?, ?, ?, ?, ?)",
            clock(), actorUuid?.toString(), actorName, action, entryId, details,
        )
    }

    // ── Schedule ─────────────────────────────────────────

    private fun readOpening(rs: ResultSet) = Opening(
        id = rs.getInt("id"),
        day = LocalDate.parse(rs.getString("day")),
        slot = rs.getInt("slot"),
        zone = rs.getString("zone"),
        start = Instant.ofEpochMilli(rs.getLong("start_at")),
        end = Instant.ofEpochMilli(rs.getLong("end_at")),
        status = runCatching { OpeningStatus.valueOf(rs.getString("status")) }.getOrDefault(OpeningStatus.SKIPPED),
        note = rs.getString("note"),
    )

    fun openingsForDay(day: LocalDate): List<Opening> =
        db.query("SELECT * FROM candy_shop_openings WHERE day = ? ORDER BY start_at", day.toString(), mapper = ::readOpening)

    /** Openings whose window ends after [from] and starts before [to], in order. */
    fun openingsBetween(from: Instant, to: Instant): List<Opening> =
        db.query(
            "SELECT * FROM candy_shop_openings WHERE end_at > ? AND start_at < ? ORDER BY start_at",
            from.toEpochMilli(), to.toEpochMilli(), mapper = ::readOpening,
        )

    /** Every opening still SCHEDULED or OPEN whose window ended at or before [now]. */
    fun unfinishedBefore(now: Instant): List<Opening> =
        db.query(
            "SELECT * FROM candy_shop_openings WHERE status IN ('SCHEDULED', 'OPEN') AND end_at <= ? ORDER BY start_at",
            now.toEpochMilli(), mapper = ::readOpening,
        )

    fun lastStartBefore(instant: Instant): Instant? =
        db.queryFirst(
            "SELECT MAX(start_at) AS s FROM candy_shop_openings WHERE start_at < ?", instant.toEpochMilli(),
        ) { rs -> rs.getLong("s").takeUnless { rs.wasNull() }?.let(Instant::ofEpochMilli) }

    fun firstStartAtOrAfter(instant: Instant): Instant? =
        db.queryFirst(
            "SELECT MIN(start_at) AS s FROM candy_shop_openings WHERE start_at >= ?", instant.toEpochMilli(),
        ) { rs -> rs.getLong("s").takeUnless { rs.wasNull() }?.let(Instant::ofEpochMilli) }

    /** The next opening still SCHEDULED that starts after [now]. */
    fun nextScheduled(now: Instant): Opening? =
        db.queryFirst(
            "SELECT * FROM candy_shop_openings WHERE status = 'SCHEDULED' AND start_at > ? ORDER BY start_at LIMIT 1",
            now.toEpochMilli(), mapper = ::readOpening,
        )

    /**
     * Stores [starts] as [day]'s schedule. INSERT OR IGNORE on UNIQUE(day, slot), all in one
     * transaction, so a day that already exists is never overwritten or duplicated.
     */
    fun insertDay(day: LocalDate, zone: ZoneId, starts: List<Instant>) {
        db.transaction {
            val now = clock()
            starts.sorted().forEachIndexed { idx, start ->
                db.execute(
                    "INSERT OR IGNORE INTO candy_shop_openings (day, slot, zone, start_at, end_at, status, created_at) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?)",
                    day.toString(), idx + 1, zone.id, start.toEpochMilli(),
                    start.plus(CandySchedule.WINDOW).toEpochMilli(), now,
                )
            }
        }
    }

    /** SCHEDULED → OPEN with its [stock], in one transaction. False if it wasn't SCHEDULED. */
    fun markOpen(openingId: Int, stock: List<StockItem>, note: String): Boolean {
        var opened = false
        db.transaction {
            opened = db.executeUpdate(
                "UPDATE candy_shop_openings SET status = 'OPEN', opened_at = ?, note = ? WHERE id = ? AND status = 'SCHEDULED'",
                clock(), note, openingId,
            ) == 1
            if (!opened) return@transaction
            for (s in stock) {
                db.execute(
                    "INSERT INTO candy_shop_stock (opening_id, position, entry_id, item_id, price, chance, effective_chance) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    openingId, s.position, s.entryId, s.itemId, s.price, s.chancePercent, s.effectivePercent,
                )
            }
        }
        return opened
    }

    /** OPEN → CLOSED. False if it wasn't OPEN, so a closing is only ever recorded (and announced) once. */
    fun markClosed(openingId: Int, note: String? = null): Boolean =
        db.executeUpdate(
            "UPDATE candy_shop_openings SET status = 'CLOSED', closed_at = ?, note = COALESCE(?, note) WHERE id = ? AND status = 'OPEN'",
            clock(), note, openingId,
        ) == 1

    /** SCHEDULED → MISSED (the window passed while offline). */
    fun markMissed(openingId: Int): Boolean =
        db.executeUpdate(
            "UPDATE candy_shop_openings SET status = 'MISSED', note = 'server was offline for the whole window' WHERE id = ? AND status = 'SCHEDULED'",
            openingId,
        ) == 1

    /** SCHEDULED → SKIPPED with the reason the pool couldn't fill it. */
    fun markSkipped(openingId: Int, reason: String): Boolean =
        db.executeUpdate(
            "UPDATE candy_shop_openings SET status = 'SKIPPED', note = ? WHERE id = ? AND status = 'SCHEDULED'",
            reason, openingId,
        ) == 1

    fun stockFor(openingId: Int): List<StockItem> =
        db.query("SELECT * FROM candy_shop_stock WHERE opening_id = ? ORDER BY position", openingId) { rs ->
            StockItem(
                openingId = rs.getInt("opening_id"),
                position = rs.getInt("position"),
                entryId = rs.getInt("entry_id"),
                itemId = rs.getString("item_id"),
                price = rs.getDouble("price"),
                chancePercent = rs.getDouble("chance"),
                effectivePercent = rs.getDouble("effective_chance"),
            )
        }

    // ── Purchases ────────────────────────────────────────

    /** Records a PAID purchase. Call inside the transaction that took the money. */
    fun insertPurchase(stock: StockItem, uuid: UUID, name: String): Int {
        val now = clock()
        return insertReturningId(
            "INSERT INTO candy_shop_purchases (opening_id, entry_id, item_id, uuid, name, price, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'PAID', ?, ?)",
            arrayOf<Any?>(stock.openingId, stock.entryId, stock.itemId, uuid.toString(), name, stock.price, now, now),
        ) ?: error("candy_shop_purchases insert returned no id")
    }

    /** PAID → [to]. False if the row wasn't PAID. */
    fun settlePurchase(id: Int, to: PurchaseStatus, note: String? = null): Boolean =
        db.executeUpdate(
            "UPDATE candy_shop_purchases SET status = ?, updated_at = ?, note = ? WHERE id = ? AND status = 'PAID'",
            to.name, clock(), note, id,
        ) == 1

    /** Purchases still PAID: money taken, delivery never confirmed (a crash in between). */
    fun unsettledPurchases(): List<Triple<Int, String, String>> =
        db.query("SELECT id, name, item_id FROM candy_shop_purchases WHERE status = 'PAID' ORDER BY id") { rs ->
            Triple(rs.getInt("id"), rs.getString("name"), rs.getString("item_id"))
        }
}
