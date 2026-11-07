package com.liam.joshymc.lifesteal.bounty

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Lifesteal bounty persistence and escrow (issue #1401), Bukkit-free.
 *
 * Escrow is the bounty rows themselves: a contribution's coins leave the contributor's `economy`
 * balance in the **same SQLite transaction** that records the contribution and raises the bounty
 * total (data.db holds both, and EconomyManager's statements join an enclosing transaction). So a
 * contribution is either fully recorded and paid for, or nothing happened. The same goes for
 * payouts (PENDING -> PAID + deposit + statistics) and refunds (PENDING -> REFUNDED + deposit):
 * the state change and the deposit commit together, so a retry can never pay twice and a failure
 * leaves the row PENDING for the next retry.
 *
 * Every bounty ends exactly once: `ACTIVE -> CLAIMED` (claim) or `ACTIVE -> EXPIRED` (expiration)
 * are conditional UPDATEs on `state = 'ACTIVE'`, and triggers refuse any change out of a terminal
 * state, any decrease of a total and any edit of a contribution amount. All calls hold the
 * database lock for their whole transaction, so concurrent claims, contributions, refunds and
 * expirations are serialized.
 *
 * Team membership is read from the Lifesteal team tables (`lifesteal_team_members` of an ACTIVE
 * `lifesteal_teams` row) inside the transaction, never from names or caches; "has joined
 * Lifesteal" is the player's `lifesteal_hearts` row, created on their first entry into Lifesteal.
 */
class BountyStore(private val db: SqliteDatabase, private val wallet: Wallet) {

    /** The existing economy, seen by the store (EconomyManager in the plugin, a fake in the checks). */
    interface Wallet {
        /** Takes [coins] only if the balance covers it; false = not enough. Throws on a database error. */
        fun withdraw(uuid: UUID, coins: Double): Boolean
        /** Adds [coins]; false = refused. Throws on a database error. */
        fun deposit(uuid: UUID, coins: Double): Boolean
    }

    enum class State { ACTIVE, CLAIMED, EXPIRED }

    data class ActiveBounty(
        val id: String,
        val target: UUID,
        val targetName: String,
        val totalCents: Long,
        val contributions: Int,
        val createdAt: Long,
        val expiresAt: Long,
    )

    data class BountyRow(
        val id: String,
        val target: UUID,
        val targetName: String,
        val state: State,
        val totalCents: Long,
        val contributions: Int,
        val createdAt: Long,
        val expiresAt: Long,
        val endedAt: Long?,
        val claimedBy: UUID?,
    )

    data class Contribution(
        val id: String,
        val bountyId: String,
        val contributor: UUID,
        val contributorName: String,
        val amountCents: Long,
        val source: String,
        val createdAt: Long,
        val refundState: String?,
        val refundedAt: Long?,
        val refundAttempts: Int,
        val refundError: String?,
    )

    data class OwnContribution(
        val contributionId: String,
        val bountyId: String,
        val target: UUID,
        val targetName: String,
        val amountCents: Long,
        val contributedAt: Long,
        val bountyTotalCents: Long,
        val expiresAt: Long,
    )

    data class Payout(
        val id: String,
        val bountyId: String,
        val killer: UUID,
        val killerName: String,
        val target: UUID,
        val targetName: String,
        val amountCents: Long,
        val state: String,
        val createdAt: Long,
        val paidAt: Long?,
        val attempts: Int,
        val lastError: String?,
        val announced: Boolean,
    )

    data class Stats(
        val uuid: UUID,
        val claims: Long = 0,
        val earnedCents: Long = 0,
        val contributedCents: Long = 0,
        val contributions: Long = 0,
        val refundedCents: Long = 0,
    )

    data class Notification(val id: Long, val uuid: UUID, val kind: String, val message: String, val createdAt: Long)

    data class AuditRow(
        val id: Long,
        val at: Long,
        val action: String,
        val actor: String,
        val bountyId: String?,
        val subject: String?,
        val amountCents: Long?,
        val detail: String?,
    )

    enum class Board { EARNED, CLAIMS }

    data class BoardEntry(val position: Int, val uuid: UUID, val claims: Long, val earnedCents: Long)

    // ── Schema ──────────────────────────────────────────────

    fun createTables() {
        val max = BountyMoney.MAX_CENTS
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounties (
                id TEXT PRIMARY KEY,
                target TEXT NOT NULL,
                target_name TEXT NOT NULL,
                state TEXT NOT NULL CHECK (state IN ('ACTIVE', 'CLAIMED', 'EXPIRED')),
                total_cents INTEGER NOT NULL CHECK (total_cents > 0 AND total_cents <= $max),
                contributions INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                ended_at INTEGER,
                claimed_by TEXT,
                version INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_bounties_active_target ON lifesteal_bounties (target) WHERE state = 'ACTIVE'")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounties_state_expiry ON lifesteal_bounties (state, expires_at)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bounties_terminal BEFORE UPDATE ON lifesteal_bounties
            WHEN OLD.state != 'ACTIVE'
            BEGIN
                SELECT RAISE(ABORT, 'a claimed or expired bounty never changes');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bounties_total BEFORE UPDATE OF total_cents ON lifesteal_bounties
            WHEN NEW.total_cents < OLD.total_cents
            BEGIN
                SELECT RAISE(ABORT, 'a bounty total never decreases');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_contributions (
                id TEXT PRIMARY KEY,
                bounty_id TEXT NOT NULL REFERENCES lifesteal_bounties (id),
                contributor TEXT NOT NULL,
                contributor_name TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK (amount_cents > 0 AND amount_cents <= $max),
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                refund_state TEXT CHECK (refund_state IS NULL OR refund_state IN ('PENDING', 'REFUNDED')),
                refunded_at INTEGER,
                refund_attempts INTEGER NOT NULL DEFAULT 0,
                refund_next_at INTEGER NOT NULL DEFAULT 0,
                refund_error TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_contributions_bounty ON lifesteal_bounty_contributions (bounty_id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_contributions_contributor ON lifesteal_bounty_contributions (contributor)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_contributions_refund ON lifesteal_bounty_contributions (refund_state, refund_next_at)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bounty_contributions_amount BEFORE UPDATE OF amount_cents, contributor, bounty_id ON lifesteal_bounty_contributions
            BEGIN
                SELECT RAISE(ABORT, 'a contribution is never edited');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bounty_contributions_refunded BEFORE UPDATE OF refund_state ON lifesteal_bounty_contributions
            WHEN OLD.refund_state = 'REFUNDED'
            BEGIN
                SELECT RAISE(ABORT, 'a refunded contribution is never refunded again');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_payouts (
                id TEXT PRIMARY KEY,
                bounty_id TEXT NOT NULL UNIQUE REFERENCES lifesteal_bounties (id),
                dedupe_key TEXT NOT NULL UNIQUE,
                killer TEXT NOT NULL,
                killer_name TEXT NOT NULL,
                target TEXT NOT NULL,
                target_name TEXT NOT NULL,
                amount_cents INTEGER NOT NULL CHECK (amount_cents > 0 AND amount_cents <= $max),
                state TEXT NOT NULL CHECK (state IN ('PENDING', 'PAID')),
                created_at INTEGER NOT NULL,
                paid_at INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                announced INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_payouts_state ON lifesteal_bounty_payouts (state, next_attempt_at)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_bounty_payouts_paid BEFORE UPDATE OF state, amount_cents, killer ON lifesteal_bounty_payouts
            WHEN OLD.state = 'PAID'
            BEGIN
                SELECT RAISE(ABORT, 'a paid bounty payout never changes');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_stats (
                uuid TEXT PRIMARY KEY,
                claims INTEGER NOT NULL DEFAULT 0,
                earned_cents INTEGER NOT NULL DEFAULT 0,
                contributed_cents INTEGER NOT NULL DEFAULT 0,
                contributions INTEGER NOT NULL DEFAULT 0,
                refunded_cents INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_stats_earned ON lifesteal_bounty_stats (earned_cents DESC, claims DESC, uuid)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_stats_claims ON lifesteal_bounty_stats (claims DESC, earned_cents DESC, uuid)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_cooldowns (
                killer TEXT NOT NULL,
                target TEXT NOT NULL,
                last_claim_at INTEGER NOT NULL,
                PRIMARY KEY (killer, target)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_kills (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                killer TEXT NOT NULL,
                target TEXT NOT NULL,
                at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_kills_pair ON lifesteal_bounty_kills (killer, target, at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_notifications (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                kind TEXT NOT NULL,
                message TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivered_at INTEGER
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_notifications_uuid ON lifesteal_bounty_notifications (uuid, delivered_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_bounty_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                action TEXT NOT NULL,
                actor TEXT NOT NULL,
                bounty_id TEXT,
                subject TEXT,
                amount_cents INTEGER,
                detail TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_audit_action ON lifesteal_bounty_audit (action, at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_bounty_audit_bounty ON lifesteal_bounty_audit (bounty_id)")
    }

    // ── Contributions ───────────────────────────────────────

    data class ContributeRequest(
        /** Unique per contribution attempt (a GUI confirmation screen reuses its id, so a double click can't pay twice). */
        val transactionId: String,
        val contributor: UUID,
        val contributorName: String,
        val target: UUID,
        val targetName: String,
        val cents: Long,
        val minimumCents: Long,
        /** Used only when this contribution creates the bounty. */
        val expirationMillis: Long,
        val now: Long,
        /** The bounty the player saw (GUI); null = create or add to whatever is active (`/bounty add`). */
        val expectedBountyId: String? = null,
        val source: String = "command",
        /** Private message to the target, built once the bounty is created: (total, expiresAt) -> text. */
        val targetNotice: ((Long, Long) -> String)? = null,
    )

    enum class Refusal(val message: String) {
        INVALID_AMOUNT("That amount can't be contributed."),
        SELF("You can't place a bounty on yourself."),
        NEVER_JOINED("That player has never played Lifesteal."),
        TEAMMATE("You can't place a bounty on a teammate."),
        INSUFFICIENT_FUNDS("You don't have enough coins for that contribution."),
        TOO_LARGE("That would make the bounty too large to process."),
        STALE("That bounty has ended. Check /bounty for the current list."),
    }

    sealed interface ContributeResult {
        data class Contributed(
            val transactionId: String,
            val bountyId: String,
            val created: Boolean,
            val contributionCents: Long,
            val totalCents: Long,
            val expiresAt: Long,
            /** Bounties expired in the same transaction (the previous one on this target had run out). */
            val expired: List<String>,
        ) : ContributeResult
        /** This transaction id was already recorded: nothing was taken again. */
        data class Duplicate(val bountyId: String) : ContributeResult
        data class Refused(val reason: Refusal, val expired: List<String> = emptyList()) : ContributeResult
    }

    fun contribute(req: ContributeRequest): ContributeResult {
        if (BountyRules.amountProblem(req.cents, req.minimumCents) != null) return ContributeResult.Refused(Refusal.INVALID_AMOUNT)
        if (req.contributor == req.target) return ContributeResult.Refused(Refusal.SELF)
        var result: ContributeResult? = null
        db.transaction {
            result = contributeInTransaction(req)
        }
        return result!!
    }

    private fun contributeInTransaction(req: ContributeRequest): ContributeResult {
        db.queryFirst("SELECT bounty_id FROM lifesteal_bounty_contributions WHERE id = ?", req.transactionId) { it.getString(1) }
            ?.let { return ContributeResult.Duplicate(it) }
        if (!joinedLifesteal(req.target)) return ContributeResult.Refused(Refusal.NEVER_JOINED)
        if (teammatesStored(req.contributor, req.target)) return ContributeResult.Refused(Refusal.TEAMMATE)

        val expired = mutableListOf<String>()
        var active = activeRowFor(req.target)
        if (active != null && active.expiresAt <= req.now) {
            if (expireInTransaction(active.id, req.now, "contribution")) expired += active.id
            active = null
        }
        if (req.expectedBountyId != null && active?.id != req.expectedBountyId) return ContributeResult.Refused(Refusal.STALE, expired)
        val total = BountyMoney.add(active?.totalCents ?: 0L, req.cents) ?: return ContributeResult.Refused(Refusal.TOO_LARGE, expired)

        if (!wallet.withdraw(req.contributor, BountyMoney.toCoins(req.cents))) return ContributeResult.Refused(Refusal.INSUFFICIENT_FUNDS, expired)

        val bountyId: String
        val expiresAt: Long
        if (active == null) {
            bountyId = UUID.randomUUID().toString()
            expiresAt = Math.addExact(req.now, req.expirationMillis)
            db.execute(
                "INSERT INTO lifesteal_bounties (id, target, target_name, state, total_cents, contributions, created_at, expires_at) VALUES (?, ?, ?, 'ACTIVE', ?, 1, ?, ?)",
                bountyId, req.target.toString(), req.targetName, total, req.now, expiresAt,
            )
        } else {
            bountyId = active.id
            expiresAt = active.expiresAt
            val updated = db.executeUpdate(
                "UPDATE lifesteal_bounties SET total_cents = ?, contributions = contributions + 1, version = version + 1 WHERE id = ? AND state = 'ACTIVE' AND version = ?",
                total, active.id, active.version,
            )
            check(updated == 1) { "bounty ${active.id} changed during the contribution" }
        }
        db.execute(
            "INSERT INTO lifesteal_bounty_contributions (id, bounty_id, contributor, contributor_name, amount_cents, source, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            req.transactionId, bountyId, req.contributor.toString(), req.contributorName, req.cents, req.source, req.now,
        )
        bumpStats(req.contributor, req.now, contributedCents = req.cents, contributions = 1)
        audit(req.now, "CONTRIBUTE", req.contributor.toString(), bountyId, req.target.toString(), req.cents,
            "tx=${req.transactionId} source=${req.source} total=$total${if (active == null) " created" else ""}")
        if (active == null) {
            req.targetNotice?.let { notify(req.target, "TARGET", it(total, expiresAt), req.now) }
        }
        return ContributeResult.Contributed(req.transactionId, bountyId, active == null, req.cents, total, expiresAt, expired)
    }

    // ── Claims and payouts ──────────────────────────────────

    data class ClaimRequest(
        /** One per death (boot + victim + server tick), so a repeated event can't claim twice. */
        val dedupeKey: String,
        val killer: UUID,
        val killerName: String,
        val victim: UUID,
        val victimName: String,
        val deathAt: Long,
        val cooldownMillis: Long,
        val now: Long = deathAt,
    )

    sealed interface ClaimResult {
        data class Claimed(val payoutId: String, val bountyId: String, val amountCents: Long) : ClaimResult
        data object NoBounty : ClaimResult
        /** The bounty had already run out when the target died (it is refunded, never claimed). */
        data object Expired : ClaimResult
        data object Duplicate : ClaimResult
        data object Teammates : ClaimResult
        data class Cooldown(val remainingMillis: Long) : ClaimResult
    }

    /** Claims the target's active bounty for the killer: ACTIVE -> CLAIMED plus a PENDING payout, in one transaction. */
    fun claim(req: ClaimRequest): ClaimResult {
        var result: ClaimResult? = null
        db.transaction {
            result = claimInTransaction(req)
        }
        return result!!
    }

    private fun claimInTransaction(req: ClaimRequest): ClaimResult {
        if (req.killer == req.victim) return ClaimResult.NoBounty
        if (db.queryFirst("SELECT 1 FROM lifesteal_bounty_payouts WHERE dedupe_key = ?", req.dedupeKey) { 1 } != null) return ClaimResult.Duplicate
        val active = activeRowFor(req.victim) ?: return ClaimResult.NoBounty
        if (active.expiresAt <= req.deathAt) return ClaimResult.Expired
        if (teammatesStored(req.killer, req.victim)) {
            audit(req.now, "CLAIM_REFUSED", req.killer.toString(), active.id, req.victim.toString(), active.totalCents, "teammates")
            return ClaimResult.Teammates
        }
        val last = db.queryFirst("SELECT last_claim_at FROM lifesteal_bounty_cooldowns WHERE killer = ? AND target = ?", req.killer.toString(), req.victim.toString()) { it.getLong(1) }
        val remaining = BountyRules.cooldownRemaining(last, req.deathAt, req.cooldownMillis)
        if (remaining > 0L) {
            audit(req.now, "CLAIM_REFUSED", req.killer.toString(), active.id, req.victim.toString(), active.totalCents, "cooldown remaining=${remaining}ms")
            return ClaimResult.Cooldown(remaining)
        }
        val updated = db.executeUpdate(
            "UPDATE lifesteal_bounties SET state = 'CLAIMED', ended_at = ?, claimed_by = ?, version = version + 1 WHERE id = ? AND state = 'ACTIVE' AND version = ?",
            req.now, req.killer.toString(), active.id, active.version,
        )
        check(updated == 1) { "bounty ${active.id} changed during the claim" }
        val payoutId = UUID.randomUUID().toString()
        db.execute(
            """
            INSERT INTO lifesteal_bounty_payouts (id, bounty_id, dedupe_key, killer, killer_name, target, target_name, amount_cents, state, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """.trimIndent(),
            payoutId, active.id, req.dedupeKey, req.killer.toString(), req.killerName, req.victim.toString(), req.victimName, active.totalCents, req.now,
        )
        db.execute(
            "INSERT INTO lifesteal_bounty_cooldowns (killer, target, last_claim_at) VALUES (?, ?, ?) ON CONFLICT(killer, target) DO UPDATE SET last_claim_at = excluded.last_claim_at",
            req.killer.toString(), req.victim.toString(), req.deathAt,
        )
        audit(req.now, "CLAIM", req.killer.toString(), active.id, req.victim.toString(), active.totalCents, "payout=$payoutId dedupe=${req.dedupeKey}")
        return ClaimResult.Claimed(payoutId, active.id, active.totalCents)
    }

    sealed interface PayResult {
        data class Paid(val payout: Payout) : PayResult
        data object AlreadyPaid : PayResult
        data object NotFound : PayResult
        data class Failed(val error: String) : PayResult
    }

    /**
     * Pays a PENDING payout: PENDING -> PAID, the deposit, the hunter's statistics and their private
     * notice in one transaction. A failure leaves it PENDING (attempts and the error recorded) for the
     * retry sweep; a second call after success is [PayResult.AlreadyPaid].
     */
    fun pay(payoutId: String, now: Long, retryDelayMillis: Long, hunterNotice: (Payout) -> String): PayResult {
        var result: PayResult? = null
        try {
            db.transaction {
                val row = payout(payoutId)
                result = when {
                    row == null -> PayResult.NotFound
                    row.state == "PAID" -> PayResult.AlreadyPaid
                    else -> {
                        val marked = db.executeUpdate(
                            "UPDATE lifesteal_bounty_payouts SET state = 'PAID', paid_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ? AND state = 'PENDING'",
                            now, payoutId,
                        )
                        if (marked != 1) PayResult.AlreadyPaid
                        else {
                            check(wallet.deposit(row.killer, BountyMoney.toCoins(row.amountCents))) { "the economy refused the deposit" }
                            bumpStats(row.killer, now, claims = 1, earnedCents = row.amountCents)
                            notify(row.killer, "PAYOUT", hunterNotice(row), now)
                            audit(now, "PAYOUT", "SYSTEM", row.bountyId, row.killer.toString(), row.amountCents, "payout=${row.id}")
                            PayResult.Paid(row.copy(state = "PAID", paidAt = now, attempts = row.attempts + 1, lastError = null))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val error = (e.message ?: e.javaClass.simpleName).take(500)
            recordFailure {
                db.execute(
                    "UPDATE lifesteal_bounty_payouts SET attempts = attempts + 1, next_attempt_at = ?, last_error = ? WHERE id = ? AND state = 'PENDING'",
                    now + retryDelayMillis, error, payoutId,
                )
                audit(now, "PAYOUT_FAILED", "SYSTEM", null, payoutId, null, error)
            }
            return PayResult.Failed(error)
        }
        return result!!
    }

    /** True exactly once per payout: the claim announcement is sent only by whoever flips this. */
    fun markAnnounced(payoutId: String): Boolean =
        db.executeUpdate("UPDATE lifesteal_bounty_payouts SET announced = 1 WHERE id = ? AND state = 'PAID' AND announced = 0", payoutId) == 1

    fun duePayouts(now: Long, limit: Int = 50): List<String> =
        db.query("SELECT id FROM lifesteal_bounty_payouts WHERE state = 'PENDING' AND next_attempt_at <= ? ORDER BY created_at LIMIT ?", now, limit) { it.getString(1) }

    /** PAID payouts whose announcement never went out (the server stopped in between). */
    fun unannouncedPayouts(limit: Int = 20): List<Payout> =
        db.query("SELECT * FROM lifesteal_bounty_payouts WHERE state = 'PAID' AND announced = 0 ORDER BY paid_at LIMIT ?", limit, mapper = ::mapPayout)

    // ── Expiration and refunds ──────────────────────────────

    /** Expires every ACTIVE bounty whose stored expiration has passed; returns their ids. */
    fun expireDue(now: Long, limit: Int = 100): List<String> {
        val due = db.query("SELECT id FROM lifesteal_bounties WHERE state = 'ACTIVE' AND expires_at <= ? ORDER BY expires_at LIMIT ?", now, limit) { it.getString(1) }
        val expired = mutableListOf<String>()
        for (id in due) {
            var done = false
            db.transaction { done = expireInTransaction(id, now, "sweep") }
            if (done) expired += id
        }
        return expired
    }

    /** ACTIVE -> EXPIRED (only once its time is up) and every contribution's refund becomes PENDING. */
    private fun expireInTransaction(bountyId: String, now: Long, by: String): Boolean {
        val updated = db.executeUpdate(
            "UPDATE lifesteal_bounties SET state = 'EXPIRED', ended_at = ?, version = version + 1 WHERE id = ? AND state = 'ACTIVE' AND expires_at <= ?",
            now, bountyId, now,
        )
        if (updated != 1) return false
        val refunds = db.executeUpdate(
            "UPDATE lifesteal_bounty_contributions SET refund_state = 'PENDING', refund_next_at = 0 WHERE bounty_id = ? AND refund_state IS NULL",
            bountyId,
        )
        audit(now, "EXPIRE", "SYSTEM", bountyId, null, null, "by=$by refunds=$refunds")
        return true
    }

    sealed interface RefundResult {
        data class Refunded(val contribution: Contribution, val targetName: String) : RefundResult
        data object AlreadyRefunded : RefundResult
        data object NotPending : RefundResult
        data class Failed(val error: String) : RefundResult
    }

    fun dueRefunds(now: Long, limit: Int = 100): List<String> =
        db.query(
            "SELECT id FROM lifesteal_bounty_contributions WHERE refund_state = 'PENDING' AND refund_next_at <= ? ORDER BY created_at LIMIT ?",
            now, limit,
        ) { it.getString(1) }

    /** Refunds one contribution of an expired bounty: PENDING -> REFUNDED + deposit + notice, in one transaction. */
    fun refund(contributionId: String, now: Long, retryDelayMillis: Long, notice: (Contribution, String) -> String): RefundResult {
        var result: RefundResult? = null
        try {
            db.transaction {
                val row = contribution(contributionId)
                result = when (row?.refundState) {
                    null -> RefundResult.NotPending
                    "REFUNDED" -> RefundResult.AlreadyRefunded
                    else -> {
                        val bounty = bounty(row.bountyId)
                        check(bounty != null && bounty.state == State.EXPIRED) { "only contributions of expired bounties are refunded" }
                        val marked = db.executeUpdate(
                            "UPDATE lifesteal_bounty_contributions SET refund_state = 'REFUNDED', refunded_at = ?, refund_attempts = refund_attempts + 1, refund_error = NULL WHERE id = ? AND refund_state = 'PENDING'",
                            now, contributionId,
                        )
                        if (marked != 1) RefundResult.AlreadyRefunded
                        else {
                            check(wallet.deposit(row.contributor, BountyMoney.toCoins(row.amountCents))) { "the economy refused the deposit" }
                            bumpStats(row.contributor, now, refundedCents = row.amountCents)
                            notify(row.contributor, "REFUND", notice(row, bounty.targetName), now)
                            audit(now, "REFUND", "SYSTEM", row.bountyId, row.contributor.toString(), row.amountCents, "contribution=${row.id}")
                            RefundResult.Refunded(row, bounty.targetName)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val error = (e.message ?: e.javaClass.simpleName).take(500)
            recordFailure {
                db.execute(
                    "UPDATE lifesteal_bounty_contributions SET refund_attempts = refund_attempts + 1, refund_next_at = ?, refund_error = ? WHERE id = ? AND refund_state = 'PENDING'",
                    now + retryDelayMillis, error, contributionId,
                )
                audit(now, "REFUND_FAILED", "SYSTEM", null, contributionId, null, error)
            }
            return RefundResult.Failed(error)
        }
        return result!!
    }

    /** Best effort bookkeeping of a failure; a database that is down can't record it, and the row simply stays PENDING. */
    private inline fun recordFailure(block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }

    // ── Notifications ───────────────────────────────────────

    private fun notify(uuid: UUID, kind: String, message: String, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_bounty_notifications (uuid, kind, message, created_at) VALUES (?, ?, ?, ?)",
            uuid.toString(), kind, message, now,
        )
    }

    fun pendingNotifications(uuid: UUID, limit: Int = 20): List<Notification> =
        db.query(
            "SELECT id, uuid, kind, message, created_at FROM lifesteal_bounty_notifications WHERE uuid = ? AND delivered_at IS NULL ORDER BY id LIMIT ?",
            uuid.toString(), limit,
        ) { Notification(it.getLong(1), UUID.fromString(it.getString(2)), it.getString(3), it.getString(4), it.getLong(5)) }

    /** True exactly once: only the caller that flips it shows the notice. */
    fun markDelivered(id: Long, now: Long): Boolean =
        db.executeUpdate("UPDATE lifesteal_bounty_notifications SET delivered_at = ? WHERE id = ? AND delivered_at IS NULL", now, id) == 1

    /** The player left before it could be shown: deliver it again next time. */
    fun unmarkDelivered(id: Long) {
        db.execute("UPDATE lifesteal_bounty_notifications SET delivered_at = NULL WHERE id = ?", id)
    }

    // ── Anti-abuse bookkeeping ──────────────────────────────

    /** Records an eligible kill of a bounty target and returns how many kills of this pair came before it inside the window. */
    fun recordKill(killer: UUID, target: UUID, at: Long, windowMillis: Long): Int {
        var earlier = 0
        db.transaction {
            earlier = db.queryFirst(
                "SELECT COUNT(*) FROM lifesteal_bounty_kills WHERE killer = ? AND target = ? AND at > ?",
                killer.toString(), target.toString(), at - windowMillis,
            ) { it.getInt(1) } ?: 0
            db.execute("INSERT INTO lifesteal_bounty_kills (killer, target, at) VALUES (?, ?, ?)", killer.toString(), target.toString(), at)
        }
        return earlier
    }

    fun recordSuspicious(at: Long, killer: UUID, target: UUID, bountyId: String?, detail: String) =
        audit(at, "SUSPICIOUS", killer.toString(), bountyId, target.toString(), null, detail)

    /** Housekeeping at startup: kill records older than [keepMillis]. Never touches money rows. */
    fun prune(now: Long, keepMillis: Long) {
        db.execute("DELETE FROM lifesteal_bounty_kills WHERE at < ?", now - keepMillis)
    }

    fun audit(at: Long, action: String, actor: String, bountyId: String?, subject: String?, amountCents: Long?, detail: String?) {
        db.execute(
            "INSERT INTO lifesteal_bounty_audit (at, action, actor, bounty_id, subject, amount_cents, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
            at, action, actor, bountyId, subject, amountCents, detail,
        )
    }

    // ── Reads ───────────────────────────────────────────────

    /** Active, unexpired bounties, highest total first (see [BountyRules.ORDER]). */
    fun activeBounties(now: Long): List<ActiveBounty> =
        db.query(
            "SELECT * FROM lifesteal_bounties WHERE state = 'ACTIVE' AND expires_at > ? ORDER BY total_cents DESC, created_at ASC, id ASC",
            now,
        ) { rs -> mapRow(rs).let { ActiveBounty(it.id, it.target, it.targetName, it.totalCents, it.contributions, it.createdAt, it.expiresAt) } }

    fun activeFor(target: UUID): BountyRow? =
        db.queryFirst("SELECT * FROM lifesteal_bounties WHERE target = ? AND state = 'ACTIVE'", target.toString(), mapper = ::mapRow)

    fun bounty(id: String): BountyRow? = db.queryFirst("SELECT * FROM lifesteal_bounties WHERE id = ?", id, mapper = ::mapRow)

    fun bountiesOn(target: UUID, limit: Int = 20): List<BountyRow> =
        db.query("SELECT * FROM lifesteal_bounties WHERE target = ? ORDER BY created_at DESC LIMIT ?", target.toString(), limit, mapper = ::mapRow)

    /** Staff only: every contribution of a bounty, with contributor identities. */
    fun contributionsOf(bountyId: String): List<Contribution> =
        db.query("SELECT * FROM lifesteal_bounty_contributions WHERE bounty_id = ? ORDER BY created_at, id", bountyId, mapper = ::mapContribution)

    fun contribution(id: String): Contribution? =
        db.queryFirst("SELECT * FROM lifesteal_bounty_contributions WHERE id = ?", id, mapper = ::mapContribution)

    /** The player's own contributions to bounties that are still active (for their own `/bounty stats`). */
    fun activeContributionsBy(contributor: UUID): List<OwnContribution> =
        db.query(
            """
            SELECT c.id AS cid, c.bounty_id, c.amount_cents, c.created_at AS contributed_at, b.target, b.target_name, b.total_cents, b.expires_at
            FROM lifesteal_bounty_contributions c JOIN lifesteal_bounties b ON b.id = c.bounty_id
            WHERE c.contributor = ? AND b.state = 'ACTIVE'
            ORDER BY b.expires_at, c.created_at, c.id
            """.trimIndent(),
            contributor.toString(),
        ) { rs ->
            OwnContribution(
                rs.getString("cid"), rs.getString("bounty_id"), UUID.fromString(rs.getString("target")), rs.getString("target_name"),
                rs.getLong("amount_cents"), rs.getLong("contributed_at"), rs.getLong("total_cents"), rs.getLong("expires_at"),
            )
        }

    fun stats(uuid: UUID): Stats =
        db.queryFirst("SELECT * FROM lifesteal_bounty_stats WHERE uuid = ?", uuid.toString()) { rs ->
            Stats(uuid, rs.getLong("claims"), rs.getLong("earned_cents"), rs.getLong("contributed_cents"), rs.getLong("contributions"), rs.getLong("refunded_cents"))
        } ?: Stats(uuid)

    fun leaderboard(board: Board, offset: Int, limit: Int): List<BoardEntry> {
        val order = when (board) {
            Board.EARNED -> "earned_cents DESC, claims DESC, uuid ASC"
            Board.CLAIMS -> "claims DESC, earned_cents DESC, uuid ASC"
        }
        var position = offset
        return db.query("SELECT uuid, claims, earned_cents FROM lifesteal_bounty_stats WHERE claims > 0 ORDER BY $order LIMIT ? OFFSET ?", limit, offset) { rs ->
            BoardEntry(++position, UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getLong(3))
        }
    }

    fun leaderboardSize(): Int = db.queryFirst("SELECT COUNT(*) FROM lifesteal_bounty_stats WHERE claims > 0") { it.getInt(1) } ?: 0

    fun payout(id: String): Payout? = db.queryFirst("SELECT * FROM lifesteal_bounty_payouts WHERE id = ?", id, mapper = ::mapPayout)

    fun payoutFor(bountyId: String): Payout? = db.queryFirst("SELECT * FROM lifesteal_bounty_payouts WHERE bounty_id = ?", bountyId, mapper = ::mapPayout)

    fun pendingPayouts(limit: Int = 50): List<Payout> =
        db.query("SELECT * FROM lifesteal_bounty_payouts WHERE state = 'PENDING' ORDER BY created_at LIMIT ?", limit, mapper = ::mapPayout)

    fun pendingRefunds(limit: Int = 50): List<Contribution> =
        db.query("SELECT * FROM lifesteal_bounty_contributions WHERE refund_state = 'PENDING' ORDER BY created_at LIMIT ?", limit, mapper = ::mapContribution)

    fun payoutsInvolving(uuid: UUID, limit: Int = 20): List<Payout> =
        db.query(
            "SELECT * FROM lifesteal_bounty_payouts WHERE killer = ? OR target = ? ORDER BY created_at DESC LIMIT ?",
            uuid.toString(), uuid.toString(), limit, mapper = ::mapPayout,
        )

    fun auditLog(action: String?, offset: Int, limit: Int): List<AuditRow> {
        val mapper = { rs: ResultSet ->
            AuditRow(
                rs.getLong("id"), rs.getLong("at"), rs.getString("action"), rs.getString("actor"), rs.getString("bounty_id"),
                rs.getString("subject"), rs.getLong("amount_cents").takeUnless { rs.wasNull() }, rs.getString("detail"),
            )
        }
        return if (action == null) db.query("SELECT * FROM lifesteal_bounty_audit ORDER BY id DESC LIMIT ? OFFSET ?", limit, offset, mapper = mapper)
        else db.query("SELECT * FROM lifesteal_bounty_audit WHERE action = ? ORDER BY id DESC LIMIT ? OFFSET ?", action, limit, offset, mapper = mapper)
    }

    /** Coins held in escrow right now: the totals of every active bounty plus refunds and payouts not yet paid. */
    fun escrowCents(): Long {
        val active = db.queryFirst("SELECT COALESCE(SUM(total_cents), 0) FROM lifesteal_bounties WHERE state = 'ACTIVE'") { it.getLong(1) } ?: 0L
        val refunds = db.queryFirst("SELECT COALESCE(SUM(amount_cents), 0) FROM lifesteal_bounty_contributions WHERE refund_state = 'PENDING'") { it.getLong(1) } ?: 0L
        val payouts = db.queryFirst("SELECT COALESCE(SUM(amount_cents), 0) FROM lifesteal_bounty_payouts WHERE state = 'PENDING'") { it.getLong(1) } ?: 0L
        return active + refunds + payouts
    }

    /**
     * Consistency check for staff: every bounty's total equals the sum of its contributions, and
     * claimed bounties have exactly one payout of that total. Returns problems (empty = consistent).
     */
    fun consistencyProblems(): List<String> {
        val problems = mutableListOf<String>()
        db.query(
            """
            SELECT b.id, b.total_cents, b.contributions, COALESCE(SUM(c.amount_cents), 0) AS summed, COUNT(c.id) AS n
            FROM lifesteal_bounties b LEFT JOIN lifesteal_bounty_contributions c ON c.bounty_id = b.id
            GROUP BY b.id HAVING b.total_cents != summed OR b.contributions != n
            """.trimIndent()
        ) { "bounty ${it.getString(1)}: total ${it.getLong(2)} / ${it.getInt(3)} contributions, recorded ${it.getLong(4)} / ${it.getInt(5)}" }
            .let { problems += it }
        db.query(
            """
            SELECT b.id FROM lifesteal_bounties b LEFT JOIN lifesteal_bounty_payouts p ON p.bounty_id = b.id
            WHERE (b.state = 'CLAIMED' AND (p.id IS NULL OR p.amount_cents != b.total_cents)) OR (b.state != 'CLAIMED' AND p.id IS NOT NULL)
            """.trimIndent()
        ) { "bounty ${it.getString(1)}: payout doesn't match its state" }.let { problems += it }
        db.query(
            """
            SELECT c.id FROM lifesteal_bounty_contributions c JOIN lifesteal_bounties b ON b.id = c.bounty_id
            WHERE (b.state = 'EXPIRED' AND c.refund_state IS NULL) OR (b.state != 'EXPIRED' AND c.refund_state IS NOT NULL)
            """.trimIndent()
        ) { "contribution ${it.getString(1)}: refund state doesn't match its bounty" }.let { problems += it }
        return problems
    }

    // ── Helpers ─────────────────────────────────────────────

    private data class ActiveRow(val id: String, val totalCents: Long, val expiresAt: Long, val version: Long)

    private fun activeRowFor(target: UUID): ActiveRow? =
        db.queryFirst("SELECT id, total_cents, expires_at, version FROM lifesteal_bounties WHERE target = ? AND state = 'ACTIVE'", target.toString()) {
            ActiveRow(it.getString(1), it.getLong(2), it.getLong(3), it.getLong(4))
        }

    /** The player has a Lifesteal heart record, created on their first entry into Lifesteal. */
    fun joinedLifesteal(uuid: UUID): Boolean =
        db.queryFirst("SELECT 1 FROM lifesteal_hearts WHERE uuid = ?", uuid.toString()) { 1 } != null

    /** Both are members of the same ACTIVE Lifesteal team, read from the team tables (the source of truth). */
    fun teammatesStored(a: UUID, b: UUID): Boolean {
        if (a == b) return false
        return db.queryFirst(
            """
            SELECT 1 FROM lifesteal_team_members ma
            JOIN lifesteal_team_members mb ON mb.team_id = ma.team_id
            JOIN lifesteal_teams t ON t.id = ma.team_id AND t.status = 'ACTIVE'
            WHERE ma.uuid = ? AND mb.uuid = ?
            """.trimIndent(),
            a.toString(), b.toString(),
        ) { 1 } != null
    }

    private fun bumpStats(
        uuid: UUID, now: Long, claims: Long = 0, earnedCents: Long = 0, contributedCents: Long = 0, contributions: Long = 0, refundedCents: Long = 0,
    ) {
        val current = stats(uuid)
        // Exact, overflow-checked sums (a corrupted counter fails the transaction instead of wrapping).
        val next = Stats(
            uuid,
            Math.addExact(current.claims, claims),
            Math.addExact(current.earnedCents, earnedCents),
            Math.addExact(current.contributedCents, contributedCents),
            Math.addExact(current.contributions, contributions),
            Math.addExact(current.refundedCents, refundedCents),
        )
        db.execute(
            """
            INSERT INTO lifesteal_bounty_stats (uuid, claims, earned_cents, contributed_cents, contributions, refunded_cents, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET claims = excluded.claims, earned_cents = excluded.earned_cents,
                contributed_cents = excluded.contributed_cents, contributions = excluded.contributions,
                refunded_cents = excluded.refunded_cents, updated_at = excluded.updated_at
            """.trimIndent(),
            uuid.toString(), next.claims, next.earnedCents, next.contributedCents, next.contributions, next.refundedCents, now,
        )
    }

    private fun mapRow(rs: ResultSet): BountyRow = BountyRow(
        id = rs.getString("id"),
        target = UUID.fromString(rs.getString("target")),
        targetName = rs.getString("target_name"),
        state = State.valueOf(rs.getString("state")),
        totalCents = rs.getLong("total_cents"),
        contributions = rs.getInt("contributions"),
        createdAt = rs.getLong("created_at"),
        expiresAt = rs.getLong("expires_at"),
        endedAt = rs.getLong("ended_at").takeUnless { rs.wasNull() },
        claimedBy = rs.getString("claimed_by")?.let { UUID.fromString(it) },
    )

    private fun mapContribution(rs: ResultSet): Contribution = Contribution(
        id = rs.getString("id"),
        bountyId = rs.getString("bounty_id"),
        contributor = UUID.fromString(rs.getString("contributor")),
        contributorName = rs.getString("contributor_name"),
        amountCents = rs.getLong("amount_cents"),
        source = rs.getString("source"),
        createdAt = rs.getLong("created_at"),
        refundState = rs.getString("refund_state"),
        refundedAt = rs.getLong("refunded_at").takeUnless { rs.wasNull() },
        refundAttempts = rs.getInt("refund_attempts"),
        refundError = rs.getString("refund_error"),
    )

    private fun mapPayout(rs: ResultSet): Payout = Payout(
        id = rs.getString("id"),
        bountyId = rs.getString("bounty_id"),
        killer = UUID.fromString(rs.getString("killer")),
        killerName = rs.getString("killer_name"),
        target = UUID.fromString(rs.getString("target")),
        targetName = rs.getString("target_name"),
        amountCents = rs.getLong("amount_cents"),
        state = rs.getString("state"),
        createdAt = rs.getLong("created_at"),
        paidAt = rs.getLong("paid_at").takeUnless { rs.wasNull() },
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
        announced = rs.getInt("announced") == 1,
    )
}
