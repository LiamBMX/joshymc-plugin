package com.liam.joshymc.advent

import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.PurchaseRewards
import java.util.UUID

/**
 * Advent Calendar claims (issue #1424), Bukkit-free.
 *
 * - `advent_claims`: one row per gamemode + calendar year + day + player (UNIQUE), written in the
 *   same transaction as the Credit withdrawal of a catch-up claim. A second claim, a double click
 *   or a restart can't add another row, so nothing is ever claimed twice.
 * - `advent_claim_rewards`: a snapshot of every reward the day gave, written with the claim, so a
 *   later config change never changes what an existing claim delivers. READY rows are handed to
 *   the store-delivery queue (reference `advent:<row id>`, UNIQUE there) and become QUEUED;
 *   WAITING rows are rewards this server can't build yet (placeholder not mapped, crate or chat tag
 *   missing) and become READY once it can. Nothing is ever deleted.
 * - `advent_free_passes`: past days staff made recoverable for free (one player or `*` = everyone).
 * - `advent_audit`: claims and staff actions.
 */
class AdventStore(private val db: DatabaseManager, private val wallet: Wallet) {

    /** Takes Credits inside the claim transaction (false = not enough). */
    fun interface Wallet {
        fun withdraw(uuid: UUID, amount: Double): Boolean
    }

    enum class RewardState { WAITING, READY, QUEUED }

    data class NewReward(
        val kind: String,
        val param: String,
        val quantity: Int,
        val label: String,
        val state: RewardState,
        val waitingReason: String?,
    )

    data class RewardRow(
        val id: Long,
        val claimId: Long,
        val gamemode: String,
        val year: Int,
        val day: Int,
        val uuid: UUID,
        val playerName: String,
        val kind: String,
        val param: String,
        val quantity: Int,
        val label: String,
        val state: RewardState,
        val waitingReason: String?,
        val deliveryId: Long?,
    ) {
        /** The delivery spec of a READY/QUEUED row (null for a WAITING placeholder). */
        val spec: PurchaseRewards.Spec?
            get() = PurchaseRewards.Kind.of(kind)?.let { PurchaseRewards.Spec(it, param, quantity) }
    }

    data class Claim(
        val id: Long,
        val gamemode: String,
        val year: Int,
        val day: Int,
        val uuid: UUID,
        val playerName: String,
        val type: AdventRules.ClaimType,
        val creditsPaid: Double,
        val claimedAt: Long,
    )

    data class ClaimRequest(
        val gamemode: String,
        val year: Int,
        val day: Int,
        val uuid: UUID,
        val playerName: String,
        /** True when [day] is today's present (always free). */
        val isToday: Boolean,
        val catchUpEnabled: Boolean,
        val catchUpCost: Double,
        /** The price the player was shown; a different price now is refused, never charged. */
        val expectedPrice: Double,
        val rewards: List<NewReward>,
        val now: Long,
    )

    sealed interface ClaimResult {
        data class Claimed(val claim: Claim, val rewards: List<RewardRow>) : ClaimResult
        data object AlreadyClaimed : ClaimResult
        data object NotRecoverable : ClaimResult
        data class PriceChanged(val price: Double) : ClaimResult
        data class InsufficientCredits(val price: Double) : ClaimResult
    }

    private class Abort(val result: ClaimResult) : RuntimeException("advent claim aborted")

    fun createSchema() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS advent_claims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                gamemode TEXT NOT NULL,
                year INTEGER NOT NULL,
                day INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                claim_type TEXT NOT NULL,
                credits_paid REAL NOT NULL DEFAULT 0,
                claimed_at INTEGER NOT NULL,
                UNIQUE (gamemode, year, day, uuid)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_advent_claims_player ON advent_claims (uuid, gamemode, year)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS advent_claim_rewards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                claim_id INTEGER NOT NULL REFERENCES advent_claims(id),
                idx INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                kind TEXT NOT NULL,
                param TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                label TEXT NOT NULL,
                state TEXT NOT NULL,
                waiting_reason TEXT,
                delivery_id INTEGER,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE (claim_id, idx)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_advent_rewards_state ON advent_claim_rewards (state, uuid)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS advent_free_passes (
                gamemode TEXT NOT NULL,
                year INTEGER NOT NULL,
                day INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                granted_by TEXT NOT NULL,
                reason TEXT,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (gamemode, year, day, uuid)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS advent_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                gamemode TEXT NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                target TEXT,
                details TEXT
            )
            """.trimIndent()
        )
    }

    // ── Reads ────────────────────────────────────────────────

    fun claimedDays(gamemode: String, year: Int, uuid: UUID): Set<Int> =
        db.query("SELECT day FROM advent_claims WHERE gamemode = ? AND year = ? AND uuid = ?", gamemode, year, uuid.toString()) {
            it.getInt("day")
        }.toSet()

    /** Days with a free pass for [uuid] (their own or everyone's). */
    fun freePassDays(gamemode: String, year: Int, uuid: UUID): Set<Int> =
        db.query(
            "SELECT DISTINCT day FROM advent_free_passes WHERE gamemode = ? AND year = ? AND uuid IN (?, ?)",
            gamemode, year, uuid.toString(), EVERYONE,
        ) { it.getInt("day") }.toSet()

    fun claims(gamemode: String, uuid: UUID, year: Int?): List<Claim> {
        val sql = "SELECT * FROM advent_claims WHERE gamemode = ? AND uuid = ?" + (if (year != null) " AND year = ?" else "") + " ORDER BY year, day"
        val params: Array<Any?> = if (year != null) arrayOf(gamemode, uuid.toString(), year) else arrayOf(gamemode, uuid.toString())
        return db.query(sql, *params) { claimOf(it) }
    }

    fun rewardsOf(claimId: Long): List<RewardRow> =
        db.query("$REWARD_SELECT WHERE r.claim_id = ? ORDER BY r.idx", claimId) { rewardOf(it) }

    fun reward(id: Long): RewardRow? = db.queryFirst("$REWARD_SELECT WHERE r.id = ?", id) { rewardOf(it) }

    /** Rows in [state] for this gamemode ([uuid] = only theirs). */
    fun rewardsIn(gamemode: String, state: RewardState, uuid: UUID?, limit: Int = 200): List<RewardRow> {
        val sql = "$REWARD_SELECT WHERE r.state = ? AND c.gamemode = ?" + (if (uuid != null) " AND r.uuid = ?" else "") + " ORDER BY r.id LIMIT ?"
        val params: Array<Any?> = if (uuid != null) arrayOf(state.name, gamemode, uuid.toString(), limit) else arrayOf(state.name, gamemode, limit)
        return db.query(sql, *params) { rewardOf(it) }
    }

    fun stateCounts(gamemode: String): Map<RewardState, Int> =
        db.query(
            "SELECT r.state AS state, COUNT(*) AS n FROM advent_claim_rewards r JOIN advent_claims c ON c.id = r.claim_id WHERE c.gamemode = ? GROUP BY r.state",
            gamemode,
        ) { rs -> RewardState.entries.firstOrNull { it.name == rs.getString("state") }?.let { it to rs.getInt("n") } }
            .filterNotNull().toMap()

    /**
     * QUEUED rows whose store delivery needs an operator (FAILED / INTERRUPTED in `purchase_deliveries`).
     * Rows whose delivery table doesn't exist yet (the check database) simply return nothing.
     */
    fun failedDeliveries(gamemode: String, limit: Int = 100): List<Pair<RewardRow, String>> = try {
        db.query(
            "SELECT r.*, c.gamemode AS gamemode, c.year AS year, c.day AS day, d.state AS delivery_state, d.last_error AS delivery_error " +
                "FROM advent_claim_rewards r JOIN advent_claims c ON c.id = r.claim_id JOIN purchase_deliveries d ON d.id = r.delivery_id " +
                "WHERE r.state = 'QUEUED' AND c.gamemode = ? AND d.state IN ('FAILED', 'INTERRUPTED') ORDER BY r.id LIMIT ?",
            gamemode, limit,
        ) { rs -> rewardOf(rs) to (rs.getString("delivery_state") + (rs.getString("delivery_error")?.let { ": $it" } ?: "")) }
    } catch (e: java.sql.SQLException) {
        if (e.message?.contains("no such table") == true) emptyList() else throw e
    }

    fun claimCount(gamemode: String, year: Int): Int =
        db.queryFirst("SELECT COUNT(*) AS n FROM advent_claims WHERE gamemode = ? AND year = ?", gamemode, year) { it.getInt("n") } ?: 0

    // ── Claiming ─────────────────────────────────────────────

    /**
     * One transaction: re-checks that the day is unclaimed and what it costs now, takes the Credits,
     * writes the claim and its reward snapshot. Anything refused rolls everything back.
     */
    fun claim(request: ClaimRequest): ClaimResult {
        var result: ClaimResult = ClaimResult.AlreadyClaimed
        try {
            db.transaction {
                val uuid = request.uuid.toString()
                val exists = db.queryFirst(
                    "SELECT 1 AS x FROM advent_claims WHERE gamemode = ? AND year = ? AND day = ? AND uuid = ?",
                    request.gamemode, request.year, request.day, uuid,
                ) { 1 } != null
                if (exists) throw Abort(ClaimResult.AlreadyClaimed)

                val hasPass = db.queryFirst(
                    "SELECT 1 AS x FROM advent_free_passes WHERE gamemode = ? AND year = ? AND day = ? AND uuid IN (?, ?)",
                    request.gamemode, request.year, request.day, uuid, EVERYONE,
                ) { 1 } != null
                val (type, price) = when {
                    request.isToday -> AdventRules.ClaimType.TODAY to 0.0
                    hasPass -> AdventRules.ClaimType.FREE_PASS to 0.0
                    request.catchUpEnabled -> AdventRules.ClaimType.CATCH_UP to request.catchUpCost
                    else -> throw Abort(ClaimResult.NotRecoverable)
                }
                if (price != request.expectedPrice) throw Abort(ClaimResult.PriceChanged(price))
                if (price > 0.0 && !wallet.withdraw(request.uuid, price)) throw Abort(ClaimResult.InsufficientCredits(price))

                val claimId = db.insertReturningId(
                    """
                    INSERT INTO advent_claims (gamemode, year, day, uuid, player_name, claim_type, credits_paid, claimed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    request.gamemode, request.year, request.day, uuid, request.playerName, type.name, price, request.now,
                )?.toLong() ?: error("claim row not inserted")

                val rows = request.rewards.mapIndexed { index, r ->
                    val id = db.insertReturningId(
                        """
                        INSERT INTO advent_claim_rewards (claim_id, idx, uuid, player_name, kind, param, quantity, label, state, waiting_reason, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                        claimId, index, uuid, request.playerName, r.kind, r.param, r.quantity, r.label, r.state.name, r.waitingReason, request.now, request.now,
                    )?.toLong() ?: error("reward row not inserted")
                    RewardRow(id, claimId, request.gamemode, request.year, request.day, request.uuid, request.playerName,
                        r.kind, r.param, r.quantity, r.label, r.state, r.waitingReason, null)
                }
                audit(request.now, request.gamemode, request.playerName, "CLAIM", uuid,
                    "${request.year} day ${request.day} ${type.name} credits=$price: ${request.rewards.joinToString("; ") { "${it.kind} ${it.param} x${it.quantity}" }}")
                result = ClaimResult.Claimed(
                    Claim(claimId, request.gamemode, request.year, request.day, request.uuid, request.playerName, type, price, request.now),
                    rows,
                )
            }
        } catch (a: Abort) {
            return a.result
        }
        return result
    }

    // ── Delivery bookkeeping ─────────────────────────────────

    /** READY → QUEUED once the delivery queue holds it (its reference is UNIQUE, so a repeat finds the same delivery). */
    fun markQueued(rewardId: Long, deliveryId: Long, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE advent_claim_rewards SET state = 'QUEUED', delivery_id = ?, waiting_reason = NULL, updated_at = ? WHERE id = ? AND state = 'READY'",
            deliveryId, now, rewardId,
        ) == 1

    /** WAITING → READY with the reward the server can now build. */
    fun resolveWaiting(rewardId: Long, spec: PurchaseRewards.Spec, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE advent_claim_rewards SET state = 'READY', kind = ?, param = ?, quantity = ?, waiting_reason = NULL, updated_at = ? WHERE id = ? AND state = 'WAITING'",
            spec.kind.id, spec.param, spec.quantity, now, rewardId,
        ) == 1

    /** A READY row the server can't build anymore (crate deleted, ...) goes back to WAITING. */
    fun backToWaiting(rewardId: Long, reason: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE advent_claim_rewards SET state = 'WAITING', waiting_reason = ?, updated_at = ? WHERE id = ? AND state = 'READY'",
            reason, now, rewardId,
        ) == 1

    fun noteWaiting(rewardId: Long, reason: String) {
        db.executeUpdate("UPDATE advent_claim_rewards SET waiting_reason = ? WHERE id = ? AND state = 'WAITING'", reason, rewardId)
    }

    // ── Free passes ──────────────────────────────────────────

    /** False when that pass already exists. [uuid] null = everyone. */
    fun grantFreePass(gamemode: String, year: Int, day: Int, uuid: UUID?, grantedBy: String, reason: String?, now: Long): Boolean {
        var inserted = false
        db.transaction {
            inserted = db.executeUpdate(
                "INSERT OR IGNORE INTO advent_free_passes (gamemode, year, day, uuid, granted_by, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                gamemode, year, day, uuid?.toString() ?: EVERYONE, grantedBy, reason, now,
            ) == 1
            if (inserted) audit(now, gamemode, grantedBy, "FREE_PASS", uuid?.toString() ?: EVERYONE, "$year day $day${reason?.let { ": $it" } ?: ""}")
        }
        return inserted
    }

    fun revokeFreePass(gamemode: String, year: Int, day: Int, uuid: UUID?, actor: String, now: Long): Boolean {
        var removed = false
        db.transaction {
            removed = db.executeUpdate(
                "DELETE FROM advent_free_passes WHERE gamemode = ? AND year = ? AND day = ? AND uuid = ?",
                gamemode, year, day, uuid?.toString() ?: EVERYONE,
            ) == 1
            if (removed) audit(now, gamemode, actor, "FREE_PASS_REVOKED", uuid?.toString() ?: EVERYONE, "$year day $day")
        }
        return removed
    }

    fun audit(now: Long, gamemode: String, actor: String, action: String, target: String?, details: String?) {
        db.execute(
            "INSERT INTO advent_audit (at, gamemode, actor, action, target, details) VALUES (?, ?, ?, ?, ?, ?)",
            now, gamemode, actor, action, target, details,
        )
    }

    // ── Mapping ──────────────────────────────────────────────

    private fun claimOf(rs: java.sql.ResultSet) = Claim(
        rs.getLong("id"), rs.getString("gamemode"), rs.getInt("year"), rs.getInt("day"), UUID.fromString(rs.getString("uuid")),
        rs.getString("player_name"),
        AdventRules.ClaimType.entries.firstOrNull { it.name == rs.getString("claim_type") } ?: AdventRules.ClaimType.TODAY,
        rs.getDouble("credits_paid"), rs.getLong("claimed_at"),
    )

    private fun rewardOf(rs: java.sql.ResultSet) = RewardRow(
        rs.getLong("id"), rs.getLong("claim_id"), rs.getString("gamemode"), rs.getInt("year"), rs.getInt("day"),
        UUID.fromString(rs.getString("uuid")), rs.getString("player_name"), rs.getString("kind"), rs.getString("param"),
        rs.getInt("quantity"), rs.getString("label"),
        RewardState.entries.firstOrNull { it.name == rs.getString("state") } ?: RewardState.WAITING,
        rs.getString("waiting_reason"), rs.getLong("delivery_id").takeUnless { rs.wasNull() },
    )

    companion object {
        /** `advent_free_passes.uuid` for a pass that covers every player. */
        const val EVERYONE = "*"

        /** `kind` of a reward row that is still a placeholder (param = the placeholder id). */
        const val PENDING_KIND = "pending"

        private const val REWARD_SELECT =
            "SELECT r.*, c.gamemode AS gamemode, c.year AS year, c.day AS day FROM advent_claim_rewards r JOIN advent_claims c ON c.id = r.claim_id"
    }
}
