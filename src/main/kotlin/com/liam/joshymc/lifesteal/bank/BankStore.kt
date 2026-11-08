package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Lifesteal Protected Bank and PvP coin stealing persistence (issue #1427), Bukkit-free.
 *
 * The **wallet** is the existing economy balance (`economy` table, EconomyManager, seen through
 * [Wallet]); the **bank** is `lifesteal_bank_accounts.balance_cents`, a separate whole-cent
 * balance in the same data.db. Every deposit, withdrawal, PvP steal and admin adjustment is **one
 * SQLite transaction** that moves the wallet side, the bank side (or the other player's wallet)
 * and writes the ledger row together (EconomyManager's statements join an enclosing
 * transaction), so a failure anywhere rolls the whole thing back: coins are never duplicated or
 * destroyed. Every bank update is conditional on the row's `version` (and on the balance
 * covering a withdrawal), and the database lock is held for the whole transaction, so
 * concurrent requests are serialized.
 *
 * Idempotency: a transfer's transaction id may complete once (`ux_lifesteal_bank_ledger_tx`), and
 * a PvP death's dedupe key may move coins once (`lifesteal_coin_steals.dedupe_key UNIQUE`), so a
 * double click, a retried request or a repeated death event never moves coins twice.
 *
 * Triggers keep the history honest: ledger rows and steal rows are never deleted and their
 * amounts never change (only the review columns of a ledger row may be filled in), and a bank
 * account row can never be deleted (a lowered capacity or a new season never removes coins).
 */
class BankStore(private val db: SqliteDatabase, private val wallet: Wallet) {

    /** The existing economy, seen by the store (EconomyManager in the plugin, a fake in the checks). */
    interface Wallet {
        fun balance(uuid: UUID): Double
        /** Takes [coins] only if the balance covers it; false = not enough. Throws on a database error. */
        fun withdraw(uuid: UUID, coins: Double): Boolean
        /** Adds [coins]; false = refused. Throws on a database error. */
        fun deposit(uuid: UUID, coins: Double): Boolean
    }

    enum class EntryType { DEPOSIT, WITHDRAW, PVP_LOSS, PVP_GAIN, ADMIN_ADD, ADMIN_REMOVE }

    data class LedgerRow(
        val id: Long,
        val txId: String,
        val uuid: UUID,
        val at: Long,
        val type: EntryType,
        val amountCents: Long,
        val feeCents: Long,
        val netCents: Long,
        val counterparty: UUID?,
        val state: String,
        val bankAfterCents: Long?,
        val walletAfterCents: Long?,
        val season: String?,
        val source: String,
        val actor: String?,
        val detail: String?,
        val reviewedAt: Long?,
        val reviewedBy: String?,
        val reviewNote: String?,
    )

    data class StealRow(
        val id: String,
        val dedupeKey: String,
        val killer: UUID,
        val victim: UUID,
        val amountCents: Long,
        val victimWalletBeforeCents: Long,
        val at: Long,
        val season: String?,
        val ranked: Boolean,
        val source: String,
    )

    data class SuspiciousRow(val id: Long, val at: Long, val killer: UUID, val victim: UUID, val stealId: String?, val detail: String)

    data class Notice(val id: Long, val uuid: UUID, val message: String)

    // ── Schema ──────────────────────────────────────────────

    fun createTables() {
        val max = BountyMoney.MAX_CENTS
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bank_accounts (
                uuid TEXT PRIMARY KEY,
                balance_cents INTEGER NOT NULL CHECK (balance_cents >= 0 AND balance_cents <= $max),
                version INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bank_accounts_keep BEFORE DELETE ON lifesteal_bank_accounts
            BEGIN
                SELECT RAISE(ABORT, 'a bank account is never deleted');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bank_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                tx_id TEXT NOT NULL,
                uuid TEXT NOT NULL,
                at INTEGER NOT NULL,
                type TEXT NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAW', 'PVP_LOSS', 'PVP_GAIN', 'ADMIN_ADD', 'ADMIN_REMOVE')),
                amount_cents INTEGER NOT NULL CHECK (amount_cents >= 0 AND amount_cents <= $max),
                fee_cents INTEGER NOT NULL DEFAULT 0 CHECK (fee_cents >= 0),
                net_cents INTEGER NOT NULL DEFAULT 0 CHECK (net_cents >= 0),
                counterparty TEXT,
                state TEXT NOT NULL CHECK (state IN ('COMPLETED', 'FAILED')),
                bank_after_cents INTEGER,
                wallet_after_cents INTEGER,
                season TEXT,
                source TEXT NOT NULL,
                actor TEXT,
                detail TEXT,
                reviewed_at INTEGER,
                reviewed_by TEXT,
                review_note TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_bank_ledger_tx ON lifesteal_bank_ledger (tx_id, uuid) WHERE state = 'COMPLETED'")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bank_ledger_uuid ON lifesteal_bank_ledger (uuid, at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bank_ledger_state ON lifesteal_bank_ledger (state, reviewed_at)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bank_ledger_no_delete BEFORE DELETE ON lifesteal_bank_ledger
            BEGIN
                SELECT RAISE(ABORT, 'bank history is append-only');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bank_ledger_fixed BEFORE UPDATE OF
                tx_id, uuid, at, type, amount_cents, fee_cents, net_cents, counterparty, state, bank_after_cents, wallet_after_cents, season, source, actor, detail
            ON lifesteal_bank_ledger
            BEGIN
                SELECT RAISE(ABORT, 'a bank history row never changes');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_coin_steals (
                id TEXT PRIMARY KEY,
                dedupe_key TEXT NOT NULL UNIQUE,
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK (amount_cents > 0 AND amount_cents <= $max),
                victim_wallet_before_cents INTEGER NOT NULL,
                at INTEGER NOT NULL,
                season TEXT,
                ranked INTEGER NOT NULL DEFAULT 0,
                source TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_coin_steals_pair ON lifesteal_coin_steals (killer, victim, at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_coin_steals_victim ON lifesteal_coin_steals (victim, at)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_coin_steals_fixed BEFORE UPDATE ON lifesteal_coin_steals
            BEGIN
                SELECT RAISE(ABORT, 'a coin steal record never changes');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_coin_steals_no_delete BEFORE DELETE ON lifesteal_coin_steals
            BEGIN
                SELECT RAISE(ABORT, 'coin steal history is append-only');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_coin_steal_cooldowns (
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                last_at INTEGER NOT NULL,
                PRIMARY KEY (killer, victim)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_coin_suspicious (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                killer TEXT NOT NULL,
                victim TEXT NOT NULL,
                steal_id TEXT,
                detail TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_coin_suspicious_at ON lifesteal_coin_suspicious (at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bank_notices (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivered_at INTEGER
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bank_notices_uuid ON lifesteal_bank_notices (uuid, delivered_at)")
    }

    // ── Reads ───────────────────────────────────────────────

    private data class Account(val balanceCents: Long, val version: Long)

    private fun account(uuid: UUID): Account? =
        db.queryFirst("SELECT balance_cents, version FROM lifesteal_bank_accounts WHERE uuid = ?", uuid.toString()) {
            Account(it.getLong(1), it.getLong(2))
        }

    /** Protected bank balance in cents (0 for a player without an account). */
    fun bankCents(uuid: UUID): Long = account(uuid)?.balanceCents ?: 0L

    /** Wallet (the economy balance) in whole cents, rounded down. */
    fun walletCents(uuid: UUID): Long = BankRules.walletCents(wallet.balance(uuid))

    data class Balances(val walletCents: Long, val bankCents: Long)

    fun balances(uuid: UUID): Balances {
        var result: Balances? = null
        db.transaction { result = Balances(walletCents(uuid), bankCents(uuid)) }
        return result!!
    }

    // ── Deposits and withdrawals ────────────────────────────

    data class TransferRequest(
        /** Unique per attempt; a GUI confirmation screen reuses its id, so a double click completes once. */
        val txId: String,
        val uuid: UUID,
        val direction: BankRules.Direction,
        /** null = everything that can move ("all"), resolved inside the transaction. */
        val requestedCents: Long?,
        /** null = unlimited. */
        val capacityCents: Long?,
        val feeBps: Int,
        val now: Long,
        val season: String?,
        val source: String,
    )

    sealed interface TransferResult {
        data class Done(val plan: BankRules.Plan, val walletAfterCents: Long, val bankAfterCents: Long) : TransferResult
        /** This transaction id already completed: nothing moved again. */
        data object Duplicate : TransferResult
        data class Refused(val reason: BankRules.Refusal, val walletCents: Long, val bankCents: Long) : TransferResult
    }

    fun transfer(req: TransferRequest): TransferResult {
        var result: TransferResult? = null
        db.transaction { result = transferInTransaction(req) }
        return result!!
    }

    private fun completed(txId: String, uuid: UUID): Boolean =
        db.queryFirst("SELECT 1 FROM lifesteal_bank_ledger WHERE tx_id = ? AND uuid = ? AND state = 'COMPLETED'", txId, uuid.toString()) { 1 } != null

    private fun transferInTransaction(req: TransferRequest): TransferResult {
        if (completed(req.txId, req.uuid)) return TransferResult.Duplicate
        val walletBefore = walletCents(req.uuid)
        val acct = account(req.uuid)
        val bankBefore = acct?.balanceCents ?: 0L
        val plan = when (val p = BankRules.plan(req.direction, req.requestedCents, walletBefore, bankBefore, req.capacityCents, req.feeBps)) {
            is BankRules.PlanResult.Refused -> return TransferResult.Refused(p.reason, walletBefore, bankBefore)
            is BankRules.PlanResult.Ok -> p.plan
        }
        val bankAfter: Long
        when (plan.direction) {
            BankRules.Direction.DEPOSIT -> {
                if (!wallet.withdraw(req.uuid, BountyMoney.toCoins(plan.amountCents))) {
                    return TransferResult.Refused(BankRules.Refusal.INSUFFICIENT_WALLET, walletBefore, bankBefore)
                }
                bankAfter = bankBefore + plan.netCents
                credit(req.uuid, acct, plan.netCents, req.now)
            }
            BankRules.Direction.WITHDRAW -> {
                checkNotNull(acct) { "no bank account to withdraw from" }
                val updated = db.executeUpdate(
                    "UPDATE lifesteal_bank_accounts SET balance_cents = balance_cents - ?, version = version + 1, updated_at = ? WHERE uuid = ? AND version = ? AND balance_cents >= ?",
                    plan.amountCents, req.now, req.uuid.toString(), acct.version, plan.amountCents,
                )
                check(updated == 1) { "bank account ${req.uuid} changed during the withdrawal" }
                bankAfter = bankBefore - plan.amountCents
                check(wallet.deposit(req.uuid, BountyMoney.toCoins(plan.netCents))) { "the economy refused the withdrawal deposit" }
            }
        }
        val walletAfter = walletCents(req.uuid)
        val type = if (plan.direction == BankRules.Direction.DEPOSIT) EntryType.DEPOSIT else EntryType.WITHDRAW
        ledger(
            txId = req.txId, uuid = req.uuid, at = req.now, type = type, amount = plan.amountCents, fee = plan.feeCents, net = plan.netCents,
            counterparty = null, state = "COMPLETED", bankAfter = bankAfter, walletAfter = walletAfter, season = req.season, source = req.source,
            actor = req.uuid.toString(), detail = if (req.requestedCents == null) "all" else null,
        )
        return TransferResult.Done(plan, walletAfter, bankAfter)
    }

    /** Adds [cents] to the bank (creating the account), conditional on the version read in this transaction. */
    private fun credit(uuid: UUID, acct: Account?, cents: Long, now: Long) {
        if (acct == null) {
            db.execute(
                "INSERT INTO lifesteal_bank_accounts (uuid, balance_cents, version, created_at, updated_at) VALUES (?, ?, 0, ?, ?)",
                uuid.toString(), cents, now, now,
            )
        } else {
            val total = BountyMoney.add(acct.balanceCents, cents) ?: error("bank balance would overflow")
            val updated = db.executeUpdate(
                "UPDATE lifesteal_bank_accounts SET balance_cents = ?, version = version + 1, updated_at = ? WHERE uuid = ? AND version = ?",
                total, now, uuid.toString(), acct.version,
            )
            check(updated == 1) { "bank account $uuid changed during the deposit" }
        }
    }

    private fun ledger(
        txId: String, uuid: UUID, at: Long, type: EntryType, amount: Long, fee: Long, net: Long, counterparty: UUID?, state: String,
        bankAfter: Long?, walletAfter: Long?, season: String?, source: String, actor: String?, detail: String?,
    ) {
        db.execute(
            """
            INSERT INTO lifesteal_bank_ledger (tx_id, uuid, at, type, amount_cents, fee_cents, net_cents, counterparty, state, bank_after_cents, wallet_after_cents, season, source, actor, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            txId, uuid.toString(), at, type.name, amount, fee, net, counterparty?.toString(), state, bankAfter, walletAfter, season, source, actor, detail,
        )
    }

    /**
     * A transaction that threw and was rolled back (nothing moved), recorded on its own afterwards
     * for staff (`/bank admin failed`). Best effort: a database that is down can't record it either.
     */
    fun recordFailure(txId: String, uuid: UUID, type: EntryType, amountCents: Long?, counterparty: UUID?, now: Long, season: String?, source: String, error: String) {
        ledger(
            txId = txId, uuid = uuid, at = now, type = type, amount = (amountCents ?: 0L).coerceIn(0, BountyMoney.MAX_CENTS), fee = 0, net = 0,
            counterparty = counterparty, state = "FAILED", bankAfter = null, walletAfter = null, season = season, source = source,
            actor = null, detail = "rolled back, nothing moved: ${error.take(300)}",
        )
    }

    // ── PvP coin stealing ───────────────────────────────────

    data class StealRequest(
        /** One per death (`coins:pvp:<boot>:<victim>:<tick>` or `coins:combatnpc:<session>`). */
        val dedupeKey: String,
        val killer: UUID,
        val victim: UUID,
        val settings: CoinStealSettings,
        val now: Long,
        val season: String?,
        val ranked: Boolean,
        val source: String,
        /** Saved for the victim to read on their next join (Combat NPC deaths: the victim is offline). (amount, walletAfter) -> text. */
        val offlineVictimNotice: ((Long, Long) -> String)? = null,
    )

    sealed interface StealResult {
        data class Stolen(
            val stealId: String,
            val amountCents: Long,
            val victimWalletAfterCents: Long,
            val killerWalletAfterCents: Long,
            /** Earlier steals by this killer from this victim inside the repeat window (for the suspicious log). */
            val earlierInWindow: Int,
        ) : StealResult
        data object Duplicate : StealResult
        data class Cooldown(val remainingMillis: Long) : StealResult
        /** The victim's wallet held nothing the configured share could take. */
        data object Empty : StealResult
    }

    fun steal(req: StealRequest): StealResult {
        require(req.killer != req.victim) { "a player can't steal from themselves" }
        var result: StealResult? = null
        db.transaction { result = stealInTransaction(req) }
        return result!!
    }

    private fun stealInTransaction(req: StealRequest): StealResult {
        if (db.queryFirst("SELECT 1 FROM lifesteal_coin_steals WHERE dedupe_key = ?", req.dedupeKey) { 1 } != null) return StealResult.Duplicate
        val s = req.settings
        if (s.antiFarmingEnabled && s.sameVictimCooldownMillis > 0) {
            val last = db.queryFirst("SELECT last_at FROM lifesteal_coin_steal_cooldowns WHERE killer = ? AND victim = ?", req.killer.toString(), req.victim.toString()) { it.getLong(1) }
            val remaining = HeartRules.cooldownRemaining(last, req.now, s.sameVictimCooldownMillis)
            if (remaining > 0) return StealResult.Cooldown(remaining)
        }
        val before = walletCents(req.victim)
        val amount = BankRules.stealAmount(before, s)
        if (amount <= 0) return StealResult.Empty
        if (!wallet.withdraw(req.victim, BountyMoney.toCoins(amount))) return StealResult.Empty
        check(wallet.deposit(req.killer, BountyMoney.toCoins(amount))) { "the economy refused the killer's deposit" }
        val earlier = db.queryFirst(
            "SELECT COUNT(*) FROM lifesteal_coin_steals WHERE killer = ? AND victim = ? AND at > ?",
            req.killer.toString(), req.victim.toString(), req.now - s.repeatWindowMillis,
        ) { it.getInt(1) } ?: 0
        val id = UUID.randomUUID().toString()
        db.execute(
            "INSERT INTO lifesteal_coin_steals (id, dedupe_key, killer, victim, amount_cents, victim_wallet_before_cents, at, season, ranked, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, req.dedupeKey, req.killer.toString(), req.victim.toString(), amount, before, req.now, req.season, if (req.ranked) 1 else 0, req.source,
        )
        db.execute(
            "INSERT INTO lifesteal_coin_steal_cooldowns (killer, victim, last_at) VALUES (?, ?, ?) ON CONFLICT(killer, victim) DO UPDATE SET last_at = excluded.last_at",
            req.killer.toString(), req.victim.toString(), req.now,
        )
        val victimAfter = walletCents(req.victim)
        val killerAfter = walletCents(req.killer)
        // The bank balances are deliberately not part of these rows' detail: a steal never reads or touches them.
        ledger(id, req.victim, req.now, EntryType.PVP_LOSS, amount, 0, amount, req.killer, "COMPLETED", null, victimAfter, req.season, req.source, null, "dedupe=${req.dedupeKey}")
        ledger(id, req.killer, req.now, EntryType.PVP_GAIN, amount, 0, amount, req.victim, "COMPLETED", null, killerAfter, req.season, req.source, null, "dedupe=${req.dedupeKey}")
        req.offlineVictimNotice?.let { notice(req.victim, it(amount, victimAfter), req.now) }
        return StealResult.Stolen(id, amount, victimAfter, killerAfter, earlier)
    }

    fun recordSuspicious(at: Long, killer: UUID, victim: UUID, stealId: String?, detail: String) {
        db.execute(
            "INSERT INTO lifesteal_coin_suspicious (at, killer, victim, steal_id, detail) VALUES (?, ?, ?, ?, ?)",
            at, killer.toString(), victim.toString(), stealId, detail,
        )
    }

    // ── Administrative adjustments ──────────────────────────

    enum class AdminOp { ADD, REMOVE }

    data class AdminRequest(
        val txId: String,
        val uuid: UUID,
        val op: AdminOp,
        val cents: Long,
        val maxCents: Long,
        /** `<uuid> (<name>)` or `CONSOLE`. */
        val actor: String,
        val reason: String,
        val now: Long,
        val season: String?,
    )

    sealed interface AdminResult {
        data class Done(val bankAfterCents: Long) : AdminResult
        data class Refused(val message: String) : AdminResult
    }

    fun adjust(req: AdminRequest): AdminResult {
        if (req.cents <= 0 || req.cents > BountyMoney.MAX_CENTS) return AdminResult.Refused("The amount must be positive.")
        if (req.cents > req.maxCents) return AdminResult.Refused("One adjustment can move at most ${BountyMoney.format(req.maxCents)} (bank.admin-max-adjustment).")
        if (req.reason.isBlank()) return AdminResult.Refused("A reason is required.")
        var result: AdminResult? = null
        db.transaction {
            result = run {
                if (completed(req.txId, req.uuid)) return@run AdminResult.Refused("That adjustment was already recorded.")
                val acct = account(req.uuid)
                val before = acct?.balanceCents ?: 0L
                val after = when (req.op) {
                    AdminOp.ADD -> {
                        val total = BountyMoney.add(before, req.cents) ?: return@run AdminResult.Refused("That would make the bank balance too large.")
                        credit(req.uuid, acct, req.cents, req.now)
                        total
                    }
                    AdminOp.REMOVE -> {
                        if (acct == null || before < req.cents) return@run AdminResult.Refused("The bank holds only ${BountyMoney.format(before)}; balances never go negative.")
                        val updated = db.executeUpdate(
                            "UPDATE lifesteal_bank_accounts SET balance_cents = balance_cents - ?, version = version + 1, updated_at = ? WHERE uuid = ? AND version = ? AND balance_cents >= ?",
                            req.cents, req.now, req.uuid.toString(), acct.version, req.cents,
                        )
                        check(updated == 1) { "bank account ${req.uuid} changed during the adjustment" }
                        before - req.cents
                    }
                }
                val type = if (req.op == AdminOp.ADD) EntryType.ADMIN_ADD else EntryType.ADMIN_REMOVE
                ledger(req.txId, req.uuid, req.now, type, req.cents, 0, req.cents, null, "COMPLETED", after, null, req.season, "admin", req.actor, req.reason.take(200))
                AdminResult.Done(after)
            }
        }
        return result!!
    }

    // ── Staff reads ─────────────────────────────────────────

    private fun ledgerRow(rs: ResultSet) = LedgerRow(
        id = rs.getLong("id"),
        txId = rs.getString("tx_id"),
        uuid = UUID.fromString(rs.getString("uuid")),
        at = rs.getLong("at"),
        type = EntryType.valueOf(rs.getString("type")),
        amountCents = rs.getLong("amount_cents"),
        feeCents = rs.getLong("fee_cents"),
        netCents = rs.getLong("net_cents"),
        counterparty = rs.getString("counterparty")?.let { UUID.fromString(it) },
        state = rs.getString("state"),
        bankAfterCents = rs.getLong("bank_after_cents").takeUnless { rs.wasNull() },
        walletAfterCents = rs.getLong("wallet_after_cents").takeUnless { rs.wasNull() },
        season = rs.getString("season"),
        source = rs.getString("source"),
        actor = rs.getString("actor"),
        detail = rs.getString("detail"),
        reviewedAt = rs.getLong("reviewed_at").takeUnless { rs.wasNull() },
        reviewedBy = rs.getString("reviewed_by"),
        reviewNote = rs.getString("review_note"),
    )

    fun history(uuid: UUID, offset: Int, limit: Int): List<LedgerRow> =
        db.query("SELECT * FROM lifesteal_bank_ledger WHERE uuid = ? ORDER BY id DESC LIMIT ? OFFSET ?", uuid.toString(), limit, offset) { ledgerRow(it) }

    fun failed(limit: Int, includeReviewed: Boolean = false): List<LedgerRow> =
        db.query(
            "SELECT * FROM lifesteal_bank_ledger WHERE state = 'FAILED'${if (includeReviewed) "" else " AND reviewed_at IS NULL"} ORDER BY id DESC LIMIT ?",
            limit,
        ) { ledgerRow(it) }

    /** Marks a FAILED row as investigated. Failed transactions moved nothing, so there is never anything to resend. */
    fun review(id: Long, actor: String, note: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE lifesteal_bank_ledger SET reviewed_at = ?, reviewed_by = ?, review_note = ? WHERE id = ? AND state = 'FAILED' AND reviewed_at IS NULL",
            now, actor, note.take(200), id,
        ) == 1

    fun steals(uuid: UUID, limit: Int): List<StealRow> =
        db.query(
            "SELECT * FROM lifesteal_coin_steals WHERE killer = ? OR victim = ? ORDER BY at DESC LIMIT ?",
            uuid.toString(), uuid.toString(), limit,
        ) {
            StealRow(
                id = it.getString("id"),
                dedupeKey = it.getString("dedupe_key"),
                killer = UUID.fromString(it.getString("killer")),
                victim = UUID.fromString(it.getString("victim")),
                amountCents = it.getLong("amount_cents"),
                victimWalletBeforeCents = it.getLong("victim_wallet_before_cents"),
                at = it.getLong("at"),
                season = it.getString("season"),
                ranked = it.getInt("ranked") == 1,
                source = it.getString("source"),
            )
        }

    fun suspicious(limit: Int): List<SuspiciousRow> =
        db.query("SELECT * FROM lifesteal_coin_suspicious ORDER BY id DESC LIMIT ?", limit) {
            SuspiciousRow(it.getLong("id"), it.getLong("at"), UUID.fromString(it.getString("killer")), UUID.fromString(it.getString("victim")), it.getString("steal_id"), it.getString("detail"))
        }

    /** Sum of every bank balance (for `/bank admin summary`). */
    fun totalBankCents(): Long = db.queryFirst("SELECT COALESCE(SUM(balance_cents), 0) FROM lifesteal_bank_accounts") { it.getLong(1) } ?: 0L

    // ── Notices for offline players ─────────────────────────

    private fun notice(uuid: UUID, message: String, now: Long) {
        db.execute("INSERT INTO lifesteal_bank_notices (uuid, message, created_at) VALUES (?, ?, ?)", uuid.toString(), message, now)
    }

    /** Claims [uuid]'s undelivered notices (each one exactly once). */
    fun takeNotices(uuid: UUID, now: Long): List<Notice> {
        val claimed = mutableListOf<Notice>()
        db.transaction {
            val pending = db.query("SELECT id, message FROM lifesteal_bank_notices WHERE uuid = ? AND delivered_at IS NULL ORDER BY id", uuid.toString()) {
                Notice(it.getLong(1), uuid, it.getString(2))
            }
            for (n in pending) {
                if (db.executeUpdate("UPDATE lifesteal_bank_notices SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL", now, n.id) == 1) claimed += n
            }
        }
        return claimed
    }
}
