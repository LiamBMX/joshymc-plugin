package com.liam.joshymc.eventshop

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * The Event Shop's tables (issue #1299). No Bukkit, like CandyShopStore: items are stored as
 * the base64 strings the manager produces with `ItemStack.serializeAsBytes`.
 *
 *  - `event_shop_listings`: the admin-managed pool. AUTOINCREMENT ids, so a removed listing's id
 *    is never reused. Removal is a soft delete (kept for history and the audit log).
 *  - `event_shop_rotations`: one row per rotation. Scheduled ones carry `window_key` (`day#slot`,
 *    UNIQUE), so a scheduled window gets at most one rotation, ever. Manual openings and rerolls
 *    have no key. `window_id` is the rotation that started the window (itself, or the one a reroll
 *    replaced), so purchase limits span rerolls of the same window.
 *  - `event_shop_rotation_items`: the (up to) seven listings of a rotation, copied in when it
 *    opened (item, price, quantity, limits). Listing edits never change a running rotation.
 *  - `event_shop_purchases`: one row per purchase with a UNIQUE transaction id
 *    (PAID → DELIVERED or REFUNDED).
 *  - `event_shop_audit`: every admin change and override.
 *
 * Every state change is a conditional UPDATE on the expected current state, so a transition
 * (and its announcement) happens once.
 */
class EventShopStore(
    private val db: SqliteDatabase,
    /** `DatabaseManager.insertReturningId`: the insert and its rowid under one lock hold. */
    private val insertReturningId: (String, Array<out Any?>) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class ItemKind {
        /** A registered JoshyMC custom item, rebuilt from its definition on every delivery. */
        CUSTOM,
        /** A full serialized ItemStack (vanilla, or a custom item with changed metadata). */
        STACK,
    }

    enum class Currency { MONEY }

    enum class RotationKind {
        SCHEDULED,
        /** `/eventshop admin open`. */
        MANUAL,
        /** `/eventshop admin rotate`: replaces a running rotation until that one's end. */
        REROLL,
    }

    enum class RotationStatus {
        OPEN,
        CLOSED,
        /** Replaced by a reroll; its purchases stay untouched. */
        REPLACED,
        /** The window came but nothing could be offered (see `note`). */
        SKIPPED,
    }

    enum class PurchaseStatus {
        /** Money taken, delivery not confirmed yet. Left over only if the server died in between. */
        PAID,
        DELIVERED,
        /** Nothing could be delivered, so the money went back. */
        REFUNDED,
    }

    data class Listing(
        val id: Int,
        val displayName: String,
        val kind: ItemKind,
        val customItemId: String?,
        val itemData: String,
        val material: String,
        val quantity: Int,
        val price: Double,
        val currency: Currency,
        val enabled: Boolean,
        val perPlayerLimit: Int,
        val stockLimit: Int,
        val weight: Int,
        val createdAt: Long,
        val createdByName: String,
        val updatedAt: Long,
    )

    data class Rotation(
        val id: Int,
        val windowId: Int,
        val windowKey: String?,
        val kind: RotationKind,
        val start: Instant,
        val end: Instant,
        val status: RotationStatus,
        val eligibleCount: Int,
        val note: String?,
        val closedAt: Instant?,
        val closedBy: String?,
    ) {
        fun isActiveAt(now: Instant): Boolean = !now.isBefore(start) && now.isBefore(end)
    }

    /** One of a rotation's listings, as it was when the rotation opened. */
    data class RotationItem(
        val rotationId: Int,
        val position: Int,
        val listingId: Int,
        val displayName: String,
        val kind: ItemKind,
        val customItemId: String?,
        val itemData: String,
        val quantity: Int,
        val price: Double,
        val currency: Currency,
        val perPlayerLimit: Int,
        val stockLimit: Int,
    )

    data class NewRotation(
        val windowKey: String?,
        val windowId: Int?,
        val kind: RotationKind,
        val day: String?,
        val zone: String,
        val start: Instant,
        val end: Instant,
        val eligibleCount: Int,
        val note: String?,
        val replacesId: Int? = null,
    )

    fun createTables() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_shop_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                display_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                custom_item_id TEXT,
                item_data TEXT NOT NULL,
                material TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                price REAL NOT NULL,
                currency TEXT NOT NULL DEFAULT 'MONEY',
                enabled INTEGER NOT NULL DEFAULT 1,
                removed INTEGER NOT NULL DEFAULT 0,
                per_player_limit INTEGER NOT NULL DEFAULT 0,
                stock_limit INTEGER NOT NULL DEFAULT 0,
                weight INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                created_by TEXT,
                created_by_name TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                removed_at INTEGER,
                removed_by_name TEXT
            )
        """.trimIndent())
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_shop_rotations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                window_id INTEGER,
                window_key TEXT UNIQUE,
                kind TEXT NOT NULL,
                day TEXT,
                zone TEXT NOT NULL,
                start_at INTEGER NOT NULL,
                end_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                eligible_count INTEGER NOT NULL DEFAULT 0,
                replaces_id INTEGER,
                created_at INTEGER NOT NULL,
                closed_at INTEGER,
                closed_by TEXT,
                note TEXT
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_shop_rotations_status ON event_shop_rotations(status, end_at)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_shop_rotation_items (
                rotation_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                listing_id INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                custom_item_id TEXT,
                item_data TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                price REAL NOT NULL,
                currency TEXT NOT NULL,
                per_player_limit INTEGER NOT NULL,
                stock_limit INTEGER NOT NULL,
                PRIMARY KEY (rotation_id, position),
                UNIQUE (rotation_id, listing_id)
            )
        """.trimIndent())
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_shop_purchases (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                txn_id TEXT NOT NULL UNIQUE,
                rotation_id INTEGER NOT NULL,
                window_id INTEGER NOT NULL,
                position INTEGER NOT NULL,
                listing_id INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                price REAL NOT NULL,
                currency TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                note TEXT
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_event_shop_purchases_window ON event_shop_purchases(window_id, listing_id, uuid)")
        db.createTable("""
            CREATE TABLE IF NOT EXISTS event_shop_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                actor_uuid TEXT,
                actor_name TEXT NOT NULL,
                action TEXT NOT NULL,
                listing_id INTEGER,
                rotation_id INTEGER,
                details TEXT NOT NULL
            )
        """.trimIndent())
    }

    // ── Listings ─────────────────────────────────────────

    private fun readListing(rs: ResultSet) = Listing(
        id = rs.getInt("id"),
        displayName = rs.getString("display_name"),
        kind = runCatching { ItemKind.valueOf(rs.getString("kind")) }.getOrDefault(ItemKind.STACK),
        customItemId = rs.getString("custom_item_id"),
        itemData = rs.getString("item_data"),
        material = rs.getString("material"),
        quantity = rs.getInt("quantity"),
        price = rs.getDouble("price"),
        currency = runCatching { Currency.valueOf(rs.getString("currency")) }.getOrDefault(Currency.MONEY),
        enabled = rs.getInt("enabled") != 0,
        perPlayerLimit = rs.getInt("per_player_limit"),
        stockLimit = rs.getInt("stock_limit"),
        weight = rs.getInt("weight"),
        createdAt = rs.getLong("created_at"),
        createdByName = rs.getString("created_by_name"),
        updatedAt = rs.getLong("updated_at"),
    )

    /** Every listing not removed, oldest first. */
    fun listListings(): List<Listing> =
        db.query("SELECT * FROM event_shop_listings WHERE removed = 0 ORDER BY id", mapper = ::readListing)

    fun listing(id: Int): Listing? =
        db.queryFirst("SELECT * FROM event_shop_listings WHERE id = ? AND removed = 0", id, mapper = ::readListing)

    /** Adds a listing and its audit row in one transaction; returns the new stable id. */
    fun addListing(
        displayName: String, kind: ItemKind, customItemId: String?, itemData: String, material: String,
        quantity: Int, price: Double, actorUuid: UUID?, actorName: String,
    ): Int {
        var id: Int? = null
        db.transaction {
            val now = clock()
            id = insertReturningId(
                "INSERT INTO event_shop_listings (display_name, kind, custom_item_id, item_data, material, quantity, price, currency, " +
                    "enabled, removed, per_player_limit, stock_limit, weight, created_at, created_by, created_by_name, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'MONEY', 1, 0, 0, 0, 1, ?, ?, ?, ?)",
                arrayOf<Any?>(displayName, kind.name, customItemId, itemData, material, quantity, price, now, actorUuid?.toString(), actorName, now),
            ) ?: error("event_shop_listings insert returned no id")
            audit(actorUuid, actorName, "ADD", id, null,
                "name=$displayName kind=$kind custom=${customItemId ?: "-"} material=$material quantity=$quantity price=$price")
        }
        return id!!
    }

    /** Soft-deletes listing [id]; false when it doesn't exist (or was already removed). */
    fun removeListing(id: Int, actorUuid: UUID?, actorName: String): Boolean =
        updateListing(id, actorUuid, actorName, "REMOVE", "removed",
            "UPDATE event_shop_listings SET removed = 1, enabled = 0, removed_at = ?, removed_by_name = ?, updated_at = ? WHERE id = ? AND removed = 0",
        ) { now -> arrayOf(now, actorName, now, id) }

    fun setEnabled(id: Int, enabled: Boolean, actorUuid: UUID?, actorName: String): Boolean =
        updateListing(id, actorUuid, actorName, if (enabled) "ENABLE" else "DISABLE", "enabled=$enabled",
            "UPDATE event_shop_listings SET enabled = ?, updated_at = ? WHERE id = ? AND removed = 0 AND enabled = ?",
        ) { now -> arrayOf(if (enabled) 1 else 0, now, id, if (enabled) 0 else 1) }

    /** The editable listing columns, by `/eventshop admin edit` field name. */
    enum class Field(val column: String) {
        PRICE("price"),
        QUANTITY("quantity"),
        WEIGHT("weight"),
        LIMIT("per_player_limit"),
        STOCK("stock_limit"),
        NAME("display_name"),
        CURRENCY("currency"),
    }

    /** Sets one validated [field] of listing [id] to [value]; false when there's no such listing. */
    fun editListing(id: Int, field: Field, value: Any, actorUuid: UUID?, actorName: String): Boolean =
        updateListing(id, actorUuid, actorName, "EDIT", "${field.name.lowercase()}=$value",
            "UPDATE event_shop_listings SET ${field.column} = ?, updated_at = ? WHERE id = ? AND removed = 0",
        ) { now -> arrayOf(value, now, id) }

    private fun updateListing(
        id: Int, actorUuid: UUID?, actorName: String, action: String, details: String,
        sql: String, params: (Long) -> Array<Any?>,
    ): Boolean {
        var changed = false
        db.transaction {
            changed = db.executeUpdate(sql, *params(clock())) == 1
            if (changed) audit(actorUuid, actorName, action, id, null, details)
        }
        return changed
    }

    fun audit(actorUuid: UUID?, actorName: String, action: String, listingId: Int?, rotationId: Int?, details: String) {
        db.execute(
            "INSERT INTO event_shop_audit (at, actor_uuid, actor_name, action, listing_id, rotation_id, details) VALUES (?, ?, ?, ?, ?, ?, ?)",
            clock(), actorUuid?.toString(), actorName, action, listingId, rotationId, details,
        )
    }

    // ── Rotations ────────────────────────────────────────

    private fun readRotation(rs: ResultSet): Rotation {
        val id = rs.getInt("id")
        val closedAt = rs.getLong("closed_at").takeUnless { rs.wasNull() }
        return Rotation(
            id = id,
            windowId = rs.getInt("window_id").takeUnless { rs.wasNull() } ?: id,
            windowKey = rs.getString("window_key"),
            kind = runCatching { RotationKind.valueOf(rs.getString("kind")) }.getOrDefault(RotationKind.MANUAL),
            start = Instant.ofEpochMilli(rs.getLong("start_at")),
            end = Instant.ofEpochMilli(rs.getLong("end_at")),
            status = runCatching { RotationStatus.valueOf(rs.getString("status")) }.getOrDefault(RotationStatus.CLOSED),
            eligibleCount = rs.getInt("eligible_count"),
            note = rs.getString("note"),
            closedAt = closedAt?.let(Instant::ofEpochMilli),
            closedBy = rs.getString("closed_by"),
        )
    }

    fun rotation(id: Int): Rotation? =
        db.queryFirst("SELECT * FROM event_shop_rotations WHERE id = ?", id, mapper = ::readRotation)

    fun rotationByKey(key: String): Rotation? =
        db.queryFirst("SELECT * FROM event_shop_rotations WHERE window_key = ?", key, mapper = ::readRotation)

    /** OPEN rotations whose window ended at or before [now] (the server was down, or froze). */
    fun expiredOpen(now: Instant): List<Rotation> =
        db.query(
            "SELECT * FROM event_shop_rotations WHERE status = 'OPEN' AND end_at <= ? ORDER BY id",
            now.toEpochMilli(), mapper = ::readRotation,
        )

    /** OPEN rotations running at [now], newest first. */
    fun openAt(now: Instant): List<Rotation> =
        db.query(
            "SELECT * FROM event_shop_rotations WHERE status = 'OPEN' AND start_at <= ? AND end_at > ? ORDER BY id DESC",
            now.toEpochMilli(), now.toEpochMilli(), mapper = ::readRotation,
        )

    /** Whether some rotation that actually offered items was running at [instant]. */
    fun anyOfferedAt(instant: Instant): Boolean =
        db.queryFirst(
            "SELECT 1 FROM event_shop_rotations WHERE status IN ('OPEN', 'CLOSED', 'REPLACED') AND start_at < ? AND end_at > ? " +
                "AND (closed_at IS NULL OR closed_at > ?) LIMIT 1",
            instant.toEpochMilli() + 1, instant.toEpochMilli(), instant.toEpochMilli(),
        ) { true } == true

    fun recentRotations(limit: Int): List<Rotation> =
        db.query("SELECT * FROM event_shop_rotations ORDER BY id DESC LIMIT ?", limit, mapper = ::readRotation)

    /**
     * Creates a rotation with its [items] in one transaction. A scheduled rotation's window key is
     * UNIQUE (INSERT OR IGNORE), so when that window already has a rotation this returns null and
     * writes nothing. [status] is OPEN, or SKIPPED (with no items) when nothing could be offered.
     */
    fun createRotation(r: NewRotation, status: RotationStatus, items: List<RotationItem>, actorUuid: UUID?, actorName: String): Int? {
        var id: Int? = null
        db.transaction {
            val now = clock()
            val newId = insertReturningId(
                "INSERT OR IGNORE INTO event_shop_rotations (window_id, window_key, kind, day, zone, start_at, end_at, status, eligible_count, replaces_id, created_at, note) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(r.windowId, r.windowKey, r.kind.name, r.day, r.zone, r.start.toEpochMilli(), r.end.toEpochMilli(),
                    status.name, r.eligibleCount, r.replacesId, now, r.note),
            ) ?: return@transaction
            if (r.windowId == null) db.execute("UPDATE event_shop_rotations SET window_id = id WHERE id = ?", newId)
            if (status == RotationStatus.OPEN) {
                for (item in items) {
                    db.execute(
                        "INSERT INTO event_shop_rotation_items (rotation_id, position, listing_id, display_name, kind, custom_item_id, item_data, " +
                            "quantity, price, currency, per_player_limit, stock_limit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        newId, item.position, item.listingId, item.displayName, item.kind.name, item.customItemId, item.itemData,
                        item.quantity, item.price, item.currency.name, item.perPlayerLimit, item.stockLimit,
                    )
                }
            }
            audit(actorUuid, actorName, "ROTATION_${r.kind.name}_${status.name}", null, newId,
                "window=${r.windowKey ?: "-"} start=${r.start} end=${r.end} listings=${items.joinToString(",") { it.listingId.toString() }}" +
                    (r.replacesId?.let { " replaces=$it" } ?: "") + (r.note?.let { " note=$it" } ?: ""))
            id = newId
        }
        return id
    }

    /** OPEN → [to] (CLOSED or REPLACED). False if it wasn't OPEN, so a closing happens (and is announced) once. */
    fun finishRotation(id: Int, to: RotationStatus, by: String?, note: String?): Boolean =
        db.executeUpdate(
            "UPDATE event_shop_rotations SET status = ?, closed_at = ?, closed_by = ?, note = COALESCE(?, note) WHERE id = ? AND status = 'OPEN'",
            to.name, clock(), by, note, id,
        ) == 1

    fun itemsFor(rotationId: Int): List<RotationItem> =
        db.query("SELECT * FROM event_shop_rotation_items WHERE rotation_id = ? ORDER BY position", rotationId) { rs ->
            RotationItem(
                rotationId = rs.getInt("rotation_id"),
                position = rs.getInt("position"),
                listingId = rs.getInt("listing_id"),
                displayName = rs.getString("display_name"),
                kind = runCatching { ItemKind.valueOf(rs.getString("kind")) }.getOrDefault(ItemKind.STACK),
                customItemId = rs.getString("custom_item_id"),
                itemData = rs.getString("item_data"),
                quantity = rs.getInt("quantity"),
                price = rs.getDouble("price"),
                currency = runCatching { Currency.valueOf(rs.getString("currency")) }.getOrDefault(Currency.MONEY),
                perPlayerLimit = rs.getInt("per_player_limit"),
                stockLimit = rs.getInt("stock_limit"),
            )
        }

    // ── Purchases ────────────────────────────────────────

    /** Purchases of [listingId] in window [windowId] that weren't refunded (all players, or one). */
    fun soldCount(windowId: Int, listingId: Int, uuid: UUID? = null): Int =
        db.queryFirst(
            "SELECT COUNT(*) AS c FROM event_shop_purchases WHERE window_id = ? AND listing_id = ? AND status != 'REFUNDED'" +
                if (uuid != null) " AND uuid = ?" else "",
            *listOfNotNull<Any>(windowId, listingId, uuid?.toString()).toTypedArray(),
        ) { it.getInt("c") } ?: 0

    /** Records a PAID purchase. Call inside the transaction that took the money. */
    fun insertPurchase(txnId: String, rotation: Rotation, item: RotationItem, uuid: UUID, name: String): Int {
        val now = clock()
        return insertReturningId(
            "INSERT INTO event_shop_purchases (txn_id, rotation_id, window_id, position, listing_id, uuid, name, quantity, price, currency, status, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PAID', ?, ?)",
            arrayOf<Any?>(txnId, rotation.id, rotation.windowId, item.position, item.listingId, uuid.toString(), name,
                item.quantity, item.price, item.currency.name, now, now),
        ) ?: error("event_shop_purchases insert returned no id")
    }

    /** PAID → [to]. False if the row wasn't PAID, so a purchase is settled (and refunded) once. */
    fun settlePurchase(id: Int, to: PurchaseStatus, note: String? = null): Boolean =
        db.executeUpdate(
            "UPDATE event_shop_purchases SET status = ?, updated_at = ?, note = ? WHERE id = ? AND status = 'PAID'",
            to.name, clock(), note, id,
        ) == 1

    /** Purchases still PAID: money taken, delivery never confirmed (a crash in between). */
    fun unsettledPurchases(): List<Triple<String, String, Int>> =
        db.query("SELECT txn_id, name, listing_id FROM event_shop_purchases WHERE status = 'PAID' ORDER BY id") { rs ->
            Triple(rs.getString("txn_id"), rs.getString("name"), rs.getInt("listing_id"))
        }
}
