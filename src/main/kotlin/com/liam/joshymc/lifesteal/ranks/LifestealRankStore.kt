package com.liam.joshymc.lifesteal.ranks

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Lifesteal rank ownership, rank changes and their audit (issue #1428). Bukkit-free.
 *
 * - `lifesteal_rank_ownership`: one row per player and rank, written only once LuckPerms has
 *   saved the group. Never deleted: a revoke sets `revoked_at`, a later grant clears it. Seasons
 *   never touch this table.
 * - `lifesteal_rank_ops`: every grant/revoke, recorded PENDING **before** LuckPerms is asked. The
 *   partial UNIQUE index allows one PENDING change per player, and `reference` is UNIQUE, so a
 *   repeated store command, a double click or a second server thread can never grant twice. A
 *   change that was PENDING at a crash is simply retried (adding/removing one group is idempotent).
 * - `lifesteal_rank_audit`: append-only history of every request, completion, failure and admin action.
 *
 * Every state change is a conditional UPDATE inside a transaction, so it happens once.
 */
class LifestealRankStore(private val db: SqliteDatabase, private val clock: () -> Long = System::currentTimeMillis) {

    enum class Action { GRANT, REVOKE }
    enum class Status { PENDING, COMPLETED, FAILED, CANCELLED }

    data class Op(
        val id: String,
        val uuid: UUID,
        val playerName: String,
        val rankId: String,
        val group: String,
        val context: String?,
        val action: Action,
        val source: String,
        val reference: String?,
        val actor: String,
        val status: Status,
        val attempts: Int,
        val lastError: String?,
        val createdAt: Long,
        val updatedAt: Long,
    )

    data class Ownership(val uuid: UUID, val rankId: String, val source: String, val grantedAt: Long, val revokedAt: Long?)

    data class AuditRow(val at: Long, val actor: String, val action: String, val uuid: UUID, val rankId: String, val opId: String?, val detail: String?)

    sealed class RequestResult {
        data class Requested(val op: Op) : RequestResult()
        /** A change with the same reference already exists; nothing new was recorded. */
        data class Duplicate(val existing: Op) : RequestResult()
        data class Refused(val decision: LifestealRankRules.Decision) : RequestResult()
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_rank_ownership (
                uuid TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                source TEXT NOT NULL,
                op_id TEXT,
                granted_at INTEGER NOT NULL,
                revoked_at INTEGER,
                PRIMARY KEY (uuid, rank_id)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_rank_ops (
                id TEXT PRIMARY KEY,
                uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                grp TEXT NOT NULL,
                context TEXT,
                action TEXT NOT NULL,
                source TEXT NOT NULL,
                reference TEXT UNIQUE,
                actor TEXT NOT NULL,
                status TEXT NOT NULL,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_lifesteal_rank_ops_one_pending ON lifesteal_rank_ops(uuid) WHERE status = 'PENDING'")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_rank_ops_status ON lifesteal_rank_ops(status, updated_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_rank_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                uuid TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                op_id TEXT,
                detail TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_rank_audit_uuid ON lifesteal_rank_audit(uuid, at)")
        // Append-only: history can't be edited or removed.
        db.createTable(
            "CREATE TRIGGER IF NOT EXISTS lifesteal_rank_audit_no_update BEFORE UPDATE ON lifesteal_rank_audit " +
                "BEGIN SELECT RAISE(ABORT, 'lifesteal_rank_audit is append-only'); END"
        )
        db.createTable(
            "CREATE TRIGGER IF NOT EXISTS lifesteal_rank_audit_no_delete BEFORE DELETE ON lifesteal_rank_audit " +
                "BEGIN SELECT RAISE(ABORT, 'lifesteal_rank_audit is append-only'); END"
        )
        // Ownership is permanent: rows are revoked, never deleted.
        db.createTable(
            "CREATE TRIGGER IF NOT EXISTS lifesteal_rank_ownership_no_delete BEFORE DELETE ON lifesteal_rank_ownership " +
                "BEGIN SELECT RAISE(ABORT, 'lifesteal rank ownership is never deleted; revoke it instead'); END"
        )
    }

    // ── Reads ───────────────────────────────────────────────────────────

    /** Ranks [uuid] currently owns (not revoked). */
    fun owned(uuid: UUID): Set<String> =
        db.query("SELECT rank_id FROM lifesteal_rank_ownership WHERE uuid = ? AND revoked_at IS NULL", uuid.toString()) { it.getString("rank_id") }.toSet()

    fun ownership(uuid: UUID): List<Ownership> =
        db.query("SELECT * FROM lifesteal_rank_ownership WHERE uuid = ? ORDER BY granted_at", uuid.toString()) { ownershipRow(it) }

    fun get(id: String): Op? = db.queryFirst("SELECT * FROM lifesteal_rank_ops WHERE id = ?", id) { op(it) }

    fun byReference(reference: String): Op? = db.queryFirst("SELECT * FROM lifesteal_rank_ops WHERE reference = ?", reference) { op(it) }

    fun pendingFor(uuid: UUID): Op? =
        db.queryFirst("SELECT * FROM lifesteal_rank_ops WHERE uuid = ? AND status = ?", uuid.toString(), Status.PENDING.name) { op(it) }

    fun pending(limit: Int): List<Op> =
        db.query("SELECT * FROM lifesteal_rank_ops WHERE status = ? ORDER BY updated_at LIMIT ?", Status.PENDING.name, limit) { op(it) }

    fun failed(limit: Int): List<Op> =
        db.query("SELECT * FROM lifesteal_rank_ops WHERE status = ? ORDER BY updated_at DESC LIMIT ?", Status.FAILED.name, limit) { op(it) }

    fun history(uuid: UUID, limit: Int): List<AuditRow> =
        db.query("SELECT * FROM lifesteal_rank_audit WHERE uuid = ? ORDER BY id DESC LIMIT ?", uuid.toString(), limit) { auditRow(it) }

    fun opsFor(uuid: UUID, limit: Int): List<Op> =
        db.query("SELECT * FROM lifesteal_rank_ops WHERE uuid = ? ORDER BY created_at DESC LIMIT ?", uuid.toString(), limit) { op(it) }

    // ── Changes ─────────────────────────────────────────────────────────

    /**
     * Records a grant request, all in one transaction: a known [reference] returns the existing
     * change; otherwise [decide] sees the player's current ownership and whether a change is
     * pending, and either refuses or the PENDING row (plus an audit row) is written.
     */
    fun requestGrant(
        uuid: UUID, name: String, rankId: String, group: String, context: String?, source: String, reference: String?, actor: String,
        decide: (owned: Set<String>, pending: Boolean) -> LifestealRankRules.Decision,
    ): RequestResult = request(Action.GRANT, uuid, name, rankId, group, context, source, reference, actor) { owned, pending ->
        decide(owned, pending)
    }

    /** Records a revoke request. Refused unless [rankId] is owned and nothing is pending. */
    fun requestRevoke(uuid: UUID, name: String, rankId: String, group: String, context: String?, reference: String?, actor: String): RequestResult =
        request(Action.REVOKE, uuid, name, rankId, group, context, "admin", reference, actor) { owned, pending ->
            when {
                rankId !in owned -> LifestealRankRules.Decision.UNKNOWN_RANK
                pending -> LifestealRankRules.Decision.PENDING
                else -> LifestealRankRules.Decision.GRANT
            }
        }

    private fun request(
        action: Action, uuid: UUID, name: String, rankId: String, group: String, context: String?, source: String, reference: String?, actor: String,
        decide: (Set<String>, Boolean) -> LifestealRankRules.Decision,
    ): RequestResult {
        var result: RequestResult? = null
        db.transaction {
            if (reference != null) {
                byReference(reference)?.let { result = RequestResult.Duplicate(it); return@transaction }
            }
            val decision = decide(owned(uuid), pendingFor(uuid) != null)
            if (decision != LifestealRankRules.Decision.GRANT) {
                result = RequestResult.Refused(decision)
                return@transaction
            }
            val now = clock()
            val id = UUID.randomUUID().toString()
            db.execute(
                "INSERT INTO lifesteal_rank_ops (id, uuid, player_name, rank_id, grp, context, action, source, reference, actor, status, attempts, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                id, uuid.toString(), name, rankId, group, context, action.name, source, reference, actor, Status.PENDING.name, now, now
            )
            audit(actor, "${action.name}_REQUESTED", uuid, rankId, id, "source=$source" + (reference?.let { " ref=$it" } ?: ""), now)
            result = RequestResult.Requested(get(id)!!)
        }
        return result!!
    }

    /**
     * PENDING → COMPLETED once LuckPerms saved the change, with the ownership row in the same
     * transaction. False when the change wasn't PENDING (already finished by another pass).
     */
    fun complete(opId: String, detail: String? = null): Boolean {
        var done = false
        db.transaction {
            val op = get(opId) ?: return@transaction
            val now = clock()
            if (db.executeUpdate(
                    "UPDATE lifesteal_rank_ops SET status = ?, updated_at = ?, attempts = attempts + 1, last_error = NULL WHERE id = ? AND status = ?",
                    Status.COMPLETED.name, now, opId, Status.PENDING.name
                ) != 1
            ) return@transaction
            when (op.action) {
                Action.GRANT -> db.execute(
                    "INSERT INTO lifesteal_rank_ownership (uuid, rank_id, source, op_id, granted_at, revoked_at) VALUES (?, ?, ?, ?, ?, NULL) " +
                        "ON CONFLICT(uuid, rank_id) DO UPDATE SET source = excluded.source, op_id = excluded.op_id, granted_at = excluded.granted_at, revoked_at = NULL",
                    op.uuid.toString(), op.rankId, op.source, op.id, now
                )
                Action.REVOKE -> db.execute(
                    "UPDATE lifesteal_rank_ownership SET revoked_at = ? WHERE uuid = ? AND rank_id = ? AND revoked_at IS NULL",
                    now, op.uuid.toString(), op.rankId
                )
            }
            audit(op.actor, "${op.action.name}_COMPLETED", op.uuid, op.rankId, op.id, detail, now)
            done = true
        }
        return done
    }

    /** One failed LuckPerms attempt: stays PENDING (retried) until [maxAttempts], then FAILED. Returns the new status. */
    fun recordFailure(opId: String, maxAttempts: Int, error: String): Status? {
        var next: Status? = null
        db.transaction {
            val op = get(opId) ?: return@transaction
            if (op.status != Status.PENDING) { next = op.status; return@transaction }
            val attempts = op.attempts + 1
            val status = if (attempts >= maxAttempts) Status.FAILED else Status.PENDING
            val now = clock()
            if (db.executeUpdate(
                    "UPDATE lifesteal_rank_ops SET status = ?, attempts = ?, last_error = ?, updated_at = ? WHERE id = ? AND status = ?",
                    status.name, attempts, error.take(500), now, opId, Status.PENDING.name
                ) == 1
            ) {
                audit("system", "${op.action.name}_ATTEMPT_FAILED", op.uuid, op.rankId, op.id, "attempt $attempts: ${error.take(200)}", now)
                if (status == Status.FAILED) audit("system", "${op.action.name}_FAILED", op.uuid, op.rankId, op.id, null, now)
            }
            next = status
        }
        return next
    }

    /** A pending/failed change that should no longer happen (e.g. the rank was removed from the file). */
    fun cancel(opId: String, actor: String, reason: String): Boolean {
        var done = false
        db.transaction {
            val op = get(opId) ?: return@transaction
            val now = clock()
            if (db.executeUpdate(
                    "UPDATE lifesteal_rank_ops SET status = ?, last_error = ?, updated_at = ? WHERE id = ? AND status IN (?, ?)",
                    Status.CANCELLED.name, reason.take(500), now, opId, Status.PENDING.name, Status.FAILED.name
                ) == 1
            ) {
                audit(actor, "${op.action.name}_CANCELLED", op.uuid, op.rankId, op.id, reason.take(200), now)
                done = true
            }
        }
        return done
    }

    /** FAILED → PENDING with a fresh attempt count. False when it isn't FAILED or the player already has a pending change. */
    fun retry(opId: String, actor: String): Boolean {
        var done = false
        try {
            db.transaction {
                val op = get(opId) ?: return@transaction
                if (pendingFor(op.uuid) != null) return@transaction
                val now = clock()
                if (db.executeUpdate(
                        "UPDATE lifesteal_rank_ops SET status = ?, attempts = 0, updated_at = ? WHERE id = ? AND status = ?",
                        Status.PENDING.name, now, opId, Status.FAILED.name
                    ) == 1
                ) {
                    audit(actor, "${op.action.name}_RETRY", op.uuid, op.rankId, op.id, null, now)
                    done = true
                }
            }
        } catch (_: Exception) {
            return false
        }
        return done
    }

    fun audit(actor: String, action: String, uuid: UUID, rankId: String, opId: String?, detail: String?, at: Long = clock()) {
        db.execute(
            "INSERT INTO lifesteal_rank_audit (at, actor, action, uuid, rank_id, op_id, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
            at, actor, action, uuid.toString(), rankId, opId, detail
        )
    }

    // ── Progression statistics ──────────────────────────────────────────

    /**
     * Lifesteal PvP kills by [uuid] since [since] that moved a heart: the heart system only records
     * `TRANSFERRED` after every rule passed (grace, teammates/allies, same-victim cooldown, the
     * victim's minimum), so self-kills, team farming and repeat kills on one player don't count.
     * Null when the heart tables don't exist here.
     */
    fun pvpKills(uuid: UUID, since: Long): Long? = try {
        db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_heart_transactions WHERE type = 'PVP' AND outcome = 'TRANSFERRED' AND killer = ? AND created_at >= ?",
            uuid.toString(), since
        ) { it.getLong("n") } ?: 0L
    } catch (_: Exception) {
        null
    }

    /** Different victims among [pvpKills]. */
    fun uniqueVictims(uuid: UUID, since: Long): Long? = try {
        db.queryFirst(
            "SELECT COUNT(DISTINCT victim) AS n FROM lifesteal_heart_transactions WHERE type = 'PVP' AND outcome = 'TRANSFERRED' AND killer = ? AND created_at >= ?",
            uuid.toString(), since
        ) { it.getLong("n") } ?: 0L
    } catch (_: Exception) {
        null
    }

    // ── Kit claims ──────────────────────────────────────────────────────

    /**
     * Reserves a Lifesteal kit claim in the shared `kit_cooldowns` table **before** anything is
     * handed out: inserts the row, or moves an expired one to [now], in one conditional statement.
     * False while the kit is still on cooldown, so a second click, command, alias or reconnect in
     * the same moment can't claim twice. A crash after this can only lose a claim, never duplicate one.
     */
    fun reserveKitClaim(uuid: UUID, kit: String, now: Long, cooldownMillis: Long): Boolean =
        db.executeUpdate(
            "INSERT INTO kit_cooldowns (uuid, kit, claimed_at) VALUES (?, ?, ?) " +
                "ON CONFLICT(uuid, kit) DO UPDATE SET claimed_at = excluded.claimed_at WHERE kit_cooldowns.claimed_at <= ?",
            uuid.toString(), kit, now, now - cooldownMillis
        ) == 1

    /** Undoes a reservation that delivered nothing (e.g. the inventory filled up meanwhile). */
    fun releaseKitClaim(uuid: UUID, kit: String, reservedAt: Long, previous: Long?) {
        if (previous == null) {
            db.executeUpdate("DELETE FROM kit_cooldowns WHERE uuid = ? AND kit = ? AND claimed_at = ?", uuid.toString(), kit, reservedAt)
        } else {
            db.executeUpdate("UPDATE kit_cooldowns SET claimed_at = ? WHERE uuid = ? AND kit = ? AND claimed_at = ?", previous, uuid.toString(), kit, reservedAt)
        }
    }

    fun kitClaimedAt(uuid: UUID, kit: String): Long? =
        db.queryFirst("SELECT claimed_at FROM kit_cooldowns WHERE uuid = ? AND kit = ?", uuid.toString(), kit) { it.getLong("claimed_at") }

    // ── Mapping ─────────────────────────────────────────────────────────

    private fun op(rs: ResultSet) = Op(
        id = rs.getString("id"),
        uuid = UUID.fromString(rs.getString("uuid")),
        playerName = rs.getString("player_name"),
        rankId = rs.getString("rank_id"),
        group = rs.getString("grp"),
        context = rs.getString("context"),
        action = Action.valueOf(rs.getString("action")),
        source = rs.getString("source"),
        reference = rs.getString("reference"),
        actor = rs.getString("actor"),
        status = Status.valueOf(rs.getString("status")),
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
        createdAt = rs.getLong("created_at"),
        updatedAt = rs.getLong("updated_at"),
    )

    private fun ownershipRow(rs: ResultSet) = Ownership(
        uuid = UUID.fromString(rs.getString("uuid")),
        rankId = rs.getString("rank_id"),
        source = rs.getString("source"),
        grantedAt = rs.getLong("granted_at"),
        revokedAt = rs.getLong("revoked_at").takeUnless { rs.wasNull() },
    )

    private fun auditRow(rs: ResultSet) = AuditRow(
        at = rs.getLong("at"),
        actor = rs.getString("actor"),
        action = rs.getString("action"),
        uuid = UUID.fromString(rs.getString("uuid")),
        rankId = rs.getString("rank_id"),
        opId = rs.getString("op_id"),
        detail = rs.getString("detail"),
    )
}
