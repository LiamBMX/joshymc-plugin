package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.lifesteal.hearts.HeartRules.changesHearts
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
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
 *   reason, actor, Heart item quantity and serial), plus rejected changes, for investigating lost
 *   or duplicated hearts.
 * - `lifesteal_heart_items` (issue #1344): every physical Heart item ever made, by serial. An item
 *   is only real while its row is ISSUED; redeeming moves it to REDEEMED in the same transaction
 *   that adds the heart, so a copied item (creative clone, dupe glitch, inventory rollback) finds
 *   its serial already used, and a hand-made item finds no row at all.
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
        val itemQuantity: Int = 0,
        val itemSerial: String? = null,
    )

    enum class ItemStatus { ISSUED, REDEEMED, VOIDED }

    /** Where an item came from: a player's own withdrawn heart, or a staff grant (no heart taken). */
    enum class ItemSource { WITHDRAW, ADMIN }

    data class ItemRow(
        val serial: String,
        val status: ItemStatus?,
        val source: ItemSource?,
        val issuedTo: UUID,
        val issuedBy: String?,
        val issueTransaction: String,
        val issuedAt: Long,
        val closedBy: String?,
        val closeTransaction: String?,
        val closedAt: Long?,
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
                created_at INTEGER NOT NULL,
                item_quantity INTEGER NOT NULL DEFAULT 0,
                item_serial TEXT
            )
            """.trimIndent()
        )
        // Audit tables from issue #1341 predate the Heart item columns (issue #1344).
        val auditColumns = db.query("PRAGMA table_info(lifesteal_heart_audit)") { it.getString("name") }.toSet()
        if ("item_quantity" !in auditColumns) db.execute("ALTER TABLE lifesteal_heart_audit ADD COLUMN item_quantity INTEGER NOT NULL DEFAULT 0")
        if ("item_serial" !in auditColumns) db.execute("ALTER TABLE lifesteal_heart_audit ADD COLUMN item_serial TEXT")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_audit_uuid ON lifesteal_heart_audit (uuid, created_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_heart_items (
                serial TEXT PRIMARY KEY,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                issued_to TEXT NOT NULL,
                issued_by TEXT,
                issue_transaction TEXT NOT NULL,
                issued_at INTEGER NOT NULL,
                closed_by TEXT,
                close_transaction TEXT,
                closed_at INTEGER
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_items_owner ON lifesteal_heart_items (issued_to, status)")
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

    fun loadItem(serial: String): ItemRow? =
        db.queryFirst("SELECT * FROM lifesteal_heart_items WHERE serial = ?", serial) { itemRow(it) }

    /** Items issued to [uuid] that were never redeemed or voided, newest first. */
    fun outstandingItems(uuid: UUID, limit: Int = 20): List<ItemRow> =
        db.query(
            "SELECT * FROM lifesteal_heart_items WHERE issued_to = ? AND status = 'ISSUED' ORDER BY issued_at DESC, serial LIMIT ?",
            uuid.toString(), limit
        ) { itemRow(it) }

    fun itemCount(status: ItemStatus? = null): Int =
        if (status == null) db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_items") { it.getInt("n") } ?: 0
        else db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_heart_items WHERE status = ?", status.name) { it.getInt("n") } ?: 0

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
        /** The team check made when the death happened (issue #1348); anything but ELIGIBLE moves no heart. */
        val team: TeamCombatRules.HeartCheck = TeamCombatRules.HeartCheck.ELIGIBLE,
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
     * cooldown, the transaction row and both audit rows. Rejections (teammates or unknown team
     * membership, last heart, cooldown, killer at maximum) are recorded too, without touching hearts or the cooldown. Any database error
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
            val decision = HeartRules.decide(victimHearts, killerHearts, remaining, request.limits, request.policy, request.team)
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

    // ── Heart items (issue #1344) ───────────────────────────

    sealed interface WithdrawResult {
        /** One heart taken and [serial] issued; the caller now hands the item over. */
        data class Withdrawn(val transactionId: String, val serial: String, val before: Int, val after: Int) : WithdrawResult
        /** The player is on their final heart (at the minimum): nothing changed. */
        data class AtMinimum(val hearts: Int) : WithdrawResult
        /** No record, or an unreadable one: nothing changed. */
        data class Unavailable(val reason: String) : WithdrawResult
    }

    /**
     * `/withdrawheart` as one transaction: fresh read, [HeartRules.withdraw], the conditional heart
     * update, the ISSUED item row for [serial], the transaction row (dedupe key `withdraw:<serial>`)
     * and a WITHDRAW audit row. Rejections are audited (WITHDRAW_REJECTED). Any error rolls it all
     * back and is rethrown: no heart is taken and the serial doesn't exist.
     */
    fun withdraw(uuid: UUID, serial: String, limits: HeartRules.Limits, now: Long, transactionId: String = UUID.randomUUID().toString()): WithdrawResult {
        require(HeartRules.parseSerial(serial) == serial) { "not a Heart item serial: $serial" }
        var result: WithdrawResult? = null
        db.transaction {
            val record = load(uuid)
            val stored = record?.hearts
            if (record == null || stored == null) {
                val reason = if (record == null) "no heart record" else "heart record is unreadable"
                insertAudit(null, uuid, "WITHDRAW_REJECTED", stored, stored, uuid.toString(), reason, now)
                result = WithdrawResult.Unavailable(reason)
                return@transaction
            }
            when (val decision = HeartRules.withdraw(stored, limits)) {
                HeartRules.WithdrawDecision.AtMinimum -> {
                    insertAudit(null, uuid, "WITHDRAW_REJECTED", stored, stored, uuid.toString(), "FINAL_HEART", now)
                    result = WithdrawResult.AtMinimum(limits.clamp(stored))
                }
                is HeartRules.WithdrawDecision.Withdraw -> {
                    insertTransaction(transactionId, "withdraw:$serial", "WITHDRAW", "WITHDRAWN", null, uuid, uuid.toString(), now)
                    writeHearts(record, decision.after, transactionId, now)
                    insertItem(serial, ItemSource.WITHDRAW, uuid, uuid.toString(), transactionId, now)
                    insertAudit(transactionId, uuid, "WITHDRAW", stored, decision.after, uuid.toString(), "withdrew 1 heart", now, 1, serial)
                    result = WithdrawResult.Withdrawn(transactionId, serial, limits.clamp(stored), decision.after)
                }
            }
        }
        return result!!
    }

    sealed interface RedeemResult {
        /** [serial] used up and one heart added; the caller now removes the item. */
        data class Redeemed(val transactionId: String, val serial: String, val before: Int, val after: Int) : RedeemResult
        /** At the maximum: nothing changed, the item stays valid. */
        data class AtMaximum(val hearts: Int) : RedeemResult
        /** No item with this serial was ever issued here: a forged item. */
        data object UnknownItem : RedeemResult
        /** This serial was already redeemed (a copy of a used item). */
        data class AlreadyRedeemed(val by: String?, val at: Long?) : RedeemResult
        /** Staff voided this serial (e.g. after a refund). */
        data object Voided : RedeemResult
        /** No record, or an unreadable one: nothing changed. */
        data class Unavailable(val reason: String) : RedeemResult
    }

    /**
     * Redeeming one Heart item as one transaction: the item row must be ISSUED, then
     * [HeartRules.redeem] on a fresh read (the maximum is enforced here, not only by the caller),
     * the item row moved to REDEEMED with `WHERE status = 'ISSUED'`, the conditional heart
     * update, the transaction row (dedupe key `redeem:<serial>`, UNIQUE, so a serial can be
     * redeemed once ever) and a REDEEM audit row. Refusals are audited (REDEEM_REJECTED).
     */
    fun redeem(uuid: UUID, serial: String, limits: HeartRules.Limits, now: Long, transactionId: String = UUID.randomUUID().toString()): RedeemResult {
        var result: RedeemResult? = null
        db.transaction {
            val item = HeartRules.parseSerial(serial)?.let { loadItem(it) }
            val record = load(uuid)
            val stored = record?.hearts
            val refusal: Pair<String, RedeemResult>? = when {
                item == null -> "UNKNOWN_SERIAL" to RedeemResult.UnknownItem
                item.status == ItemStatus.REDEEMED -> "ALREADY_REDEEMED" to RedeemResult.AlreadyRedeemed(item.closedBy, item.closedAt)
                item.status != ItemStatus.ISSUED -> "VOIDED" to RedeemResult.Voided
                record == null -> "no heart record" to RedeemResult.Unavailable("no heart record")
                stored == null -> "heart record is unreadable" to RedeemResult.Unavailable("heart record is unreadable")
                else -> when (HeartRules.redeem(stored, limits)) {
                    HeartRules.RedeemDecision.AtMaximum -> "AT_MAXIMUM" to RedeemResult.AtMaximum(limits.clamp(stored))
                    is HeartRules.RedeemDecision.Redeem -> null
                }
            }
            if (refusal != null) {
                insertAudit(null, uuid, "REDEEM_REJECTED", stored, stored, uuid.toString(), refusal.first, now, 0, serial.take(64))
                result = refusal.second
                return@transaction
            }
            val after = (HeartRules.redeem(stored!!, limits) as HeartRules.RedeemDecision.Redeem).after
            insertTransaction(transactionId, "redeem:$serial", "REDEEM", "REDEEMED", null, uuid, uuid.toString(), now)
            closeItem(serial, ItemStatus.REDEEMED, uuid.toString(), transactionId, now)
            writeHearts(record!!, after, transactionId, now)
            insertAudit(transactionId, uuid, "REDEEM", stored, after, uuid.toString(), "redeemed 1 heart issued to ${item!!.issuedTo}", now, 1, serial)
            result = RedeemResult.Redeemed(transactionId, serial, limits.clamp(stored), after)
        }
        return result!!
    }

    sealed interface GrantResult {
        data class Granted(val transactionId: String, val serials: List<String>, val hearts: Int?) : GrantResult
        data class Invalid(val reason: String) : GrantResult
        data object NoRecord : GrantResult
    }

    /**
     * Staff-made Heart items (`/lifesteal hearts give`): [serials] issued to [uuid] as ADMIN items in
     * one transaction with one ADMIN_GRANT transaction row and one ADMIN_GRANT_ITEM audit row
     * (quantity = how many). The player's own hearts don't change.
     */
    fun grantItems(uuid: UUID, serials: List<String>, actor: String, actorName: String, now: Long, transactionId: String = UUID.randomUUID().toString()): GrantResult {
        if (serials.isEmpty() || serials.size > HeartRules.MAX_ADMIN_ITEM_GRANT) {
            return GrantResult.Invalid("The amount must be between 1 and ${HeartRules.MAX_ADMIN_ITEM_GRANT}.")
        }
        require(serials.all { HeartRules.parseSerial(it) == it } && serials.toSet().size == serials.size) { "invalid or repeated Heart item serials" }
        var result: GrantResult? = null
        db.transaction {
            val record = load(uuid)
            if (record == null) {
                result = GrantResult.NoRecord
                return@transaction
            }
            insertTransaction(transactionId, "admin-grant:$transactionId", "ADMIN_GRANT", "GRANTED", null, uuid, actor, now)
            for (serial in serials) insertItem(serial, ItemSource.ADMIN, uuid, actor, transactionId, now)
            insertAudit(
                transactionId, uuid, "ADMIN_GRANT_ITEM", record.hearts, record.hearts, actor,
                "$actorName: give ${serials.size} Heart item(s)", now, serials.size, serials.first()
            )
            result = GrantResult.Granted(transactionId, serials, record.hearts)
        }
        return result!!
    }

    sealed interface VoidResult {
        /** Voided; [refunded] = the owner got the withdrawn heart back ([before] -> [after]). */
        data class Voided(val transactionId: String, val owner: UUID, val refunded: Boolean, val before: Int?, val after: Int?) : VoidResult
        data object UnknownItem : VoidResult
        data class NotOutstanding(val status: ItemStatus?) : VoidResult
        data class Refused(val reason: String) : VoidResult
    }

    /**
     * Staff recovery for a Heart item that was lost (e.g. a crash right after `/withdrawheart`,
     * before the player's inventory was saved): the serial becomes VOIDED, so the item can never be
     * redeemed if it still exists somewhere, and with [refund] the heart its withdrawal took goes
     * back to the player who withdrew it (WITHDRAW items only, never past the maximum). One
     * transaction; ADMIN_VOID transaction row, ITEM_VOID (+ ITEM_REFUND) audit rows.
     */
    fun voidItem(
        serial: String, refund: Boolean, limits: HeartRules.Limits, actor: String, actorName: String, now: Long,
        transactionId: String = UUID.randomUUID().toString(),
    ): VoidResult {
        val canonical = HeartRules.parseSerial(serial.trim().lowercase()) ?: return VoidResult.UnknownItem
        var result: VoidResult? = null
        db.transaction {
            val item = loadItem(canonical)
            if (item == null) {
                result = VoidResult.UnknownItem
                return@transaction
            }
            if (item.status != ItemStatus.ISSUED) {
                result = VoidResult.NotOutstanding(item.status)
                return@transaction
            }
            var record: Record? = null
            var after: Int? = null
            if (refund) {
                if (item.source != ItemSource.WITHDRAW) {
                    result = VoidResult.Refused("This Heart item was made by staff, not withdrawn, so it has no heart to refund. Void it without refund.")
                    return@transaction
                }
                record = load(item.issuedTo)
                val stored = record?.hearts
                if (record == null || stored == null) {
                    result = VoidResult.Refused("The owner's heart record is missing or unreadable.")
                    return@transaction
                }
                when (val decision = HeartRules.redeem(stored, limits)) {
                    HeartRules.RedeemDecision.AtMaximum -> {
                        result = VoidResult.Refused("The owner is at the maximum of ${limits.maximum} hearts, so the heart can't be refunded.")
                        return@transaction
                    }
                    is HeartRules.RedeemDecision.Redeem -> after = decision.after
                }
            }
            insertTransaction(transactionId, "void:$canonical", "ADMIN_VOID", if (refund) "VOIDED_REFUNDED" else "VOIDED", null, item.issuedTo, actor, now)
            closeItem(canonical, ItemStatus.VOIDED, actor, transactionId, now)
            insertAudit(transactionId, item.issuedTo, "ITEM_VOID", null, null, actor, "$actorName: void Heart item", now, 1, canonical)
            val refunded = record
            if (refunded != null && after != null) {
                writeHearts(refunded, after, transactionId, now)
                insertAudit(transactionId, item.issuedTo, "ITEM_REFUND", refunded.hearts, after, actor, "$actorName: refund of a voided Heart item", now, 0, canonical)
            }
            result = VoidResult.Voided(transactionId, item.issuedTo, refund, refunded?.hearts, after)
        }
        return result!!
    }

    // ── Internals ───────────────────────────────────────────

    private fun insertItem(serial: String, source: ItemSource, issuedTo: UUID, issuedBy: String, transactionId: String, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_heart_items (serial, status, source, issued_to, issued_by, issue_transaction, issued_at) VALUES (?, 'ISSUED', ?, ?, ?, ?, ?)",
            serial, source.name, issuedTo.toString(), issuedBy, transactionId, now
        )
    }

    /** ISSUED -> [status], conditionally: a row that isn't ISSUED any more throws and rolls back. */
    private fun closeItem(serial: String, status: ItemStatus, by: String, transactionId: String, now: Long) {
        val updated = db.executeUpdate(
            "UPDATE lifesteal_heart_items SET status = ?, closed_by = ?, close_transaction = ?, closed_at = ? WHERE serial = ? AND status = 'ISSUED'",
            status.name, by, transactionId, now, serial
        )
        if (updated != 1) throw StaleWriteException("Heart item $serial was no longer outstanding during transaction $transactionId")
    }

    private fun itemRow(rs: ResultSet): ItemRow = ItemRow(
        serial = rs.getString("serial"),
        status = runCatching { ItemStatus.valueOf(rs.getString("status")) }.getOrNull(),
        source = runCatching { ItemSource.valueOf(rs.getString("source")) }.getOrNull(),
        issuedTo = UUID.fromString(rs.getString("issued_to")),
        issuedBy = rs.getString("issued_by"),
        issueTransaction = rs.getString("issue_transaction"),
        issuedAt = rs.getLong("issued_at"),
        closedBy = rs.getString("closed_by"),
        closeTransaction = rs.getString("close_transaction"),
        closedAt = rs.getLong("closed_at").takeUnless { rs.wasNull() },
    )

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

    private fun insertAudit(
        transactionId: String?, uuid: UUID, operation: String, previous: Int?, next: Int?, actor: String?, reason: String?, now: Long,
        itemQuantity: Int = 0, itemSerial: String? = null,
    ) {
        db.execute(
            "INSERT INTO lifesteal_heart_audit (transaction_id, uuid, operation, previous_hearts, new_hearts, actor, reason, created_at, item_quantity, item_serial) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            transactionId, uuid.toString(), operation, previous, next, actor, reason, now, itemQuantity, itemSerial
        )
    }

    private fun outcome(decision: HeartRules.Decision): String = when (decision) {
        is HeartRules.Decision.Transfer -> "TRANSFERRED"
        is HeartRules.Decision.VictimLosesOnly -> "VICTIM_LOST_ONLY"
        HeartRules.Decision.VictimAtMinimum -> "VICTIM_AT_MINIMUM"
        HeartRules.Decision.KillerAtMaximum -> "KILLER_AT_MAXIMUM"
        is HeartRules.Decision.Cooldown -> "COOLDOWN"
        HeartRules.Decision.Teammates -> "TEAMMATES"
        HeartRules.Decision.TeamUnknown -> "TEAM_UNKNOWN"
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
        itemQuantity = rs.getInt("item_quantity"),
        itemSerial = rs.getString("item_serial"),
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
