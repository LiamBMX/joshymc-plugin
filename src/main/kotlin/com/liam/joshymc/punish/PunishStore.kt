package com.liam.joshymc.punish

import com.liam.joshymc.manager.SqliteDatabase
import java.security.MessageDigest
import java.util.UUID

/**
 * Rule-based punishment state (issue #1298), Bukkit-free:
 *  - `punish_incidents`: one row per confirmed offense (the escalation history), linked to the
 *    `punishments` row that enforces it. Never deleted; overturning only marks it.
 *  - `punish_confirmations`: pending /punish confirmations, single-use (conditional UPDATE).
 *  - `punish_audit`: append-only staff audit log, each row hash-chained to the one before so
 *    an edited or deleted row shows up in [verifyAudit].
 *
 * The actual mute/ban/warn/kick still lives in the shared `punishments` table, so /history,
 * /unban, /unmute, /unwarn, expiry, login checks and the Discord log all keep working.
 */
class PunishStore(
    private val db: SqliteDatabase,
    /** `DatabaseManager.insertReturningId`: the insert and its rowid under one lock hold. */
    private val insertReturningId: (String, Array<out Any?>) -> Int?,
) {

    enum class Status { EXECUTING, ENFORCED, FAILED, INTERRUPTED }
    enum class Appeal { NONE, PENDING_REVIEW, REVIEWED, OVERTURNED }
    enum class ConfirmationStatus { PENDING, USED, CANCELLED, EXPIRED, STALE }

    data class Incident(
        val id: Int,
        val targetUuid: UUID,
        val targetName: String,
        val ruleId: Int,
        val ruleKeyword: String,
        val ruleName: String,
        val gamemode: String,
        val scope: String,
        val createdAt: Long,
        val staffUuid: String?,
        val staffName: String,
        val staffLevel: String,
        val previousCount: Int,
        val tier: Int,
        val action: String,
        val durationMs: Long?,
        val evidence: String?,
        val status: Status,
        val appeal: Appeal,
        val punishmentId: Int?,
        val reviewDueAt: Long?,
        val reversedBy: String?,
        val reversedReason: String?,
    ) {
        val offense: Int get() = previousCount + 1
    }

    data class Confirmation(
        val id: String,
        val staffUuid: String,
        val staffName: String,
        val targetUuid: UUID,
        val targetName: String,
        val ruleId: Int,
        val gamemode: String,
        val network: Boolean,
        val overrideTier: Int?,
        val alternate: Boolean,
        val evidence: String?,
        val fingerprint: String,
        val createdAt: Long,
        val expiresAt: Long,
        val status: ConfirmationStatus,
    )

    data class NewIncident(
        val confirmationId: String,
        val targetUuid: UUID,
        val targetName: String,
        val plan: PunishPlan,
        val staffUuid: String?,
        val staffName: String,
        val durationMs: Long?,
        val evidence: String?,
        val status: Status,
        val punishmentId: Int?,
        val reviewDueAt: Long?,
        val now: Long,
    )

    data class AuditEntry(
        val actorUuid: String?,
        val actorName: String,
        val action: String,
        val targetUuid: UUID? = null,
        val targetName: String? = null,
        val incidentId: Int? = null,
        val rule: PunishRule? = null,
        val details: String = "",
    )

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS punish_incidents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                confirmation_id TEXT NOT NULL UNIQUE,
                target_uuid TEXT NOT NULL,
                target_name TEXT NOT NULL,
                rule_id INTEGER NOT NULL,
                rule_keyword TEXT NOT NULL,
                rule_name TEXT NOT NULL,
                gamemode TEXT NOT NULL,
                scope TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                staff_uuid TEXT,
                staff_name TEXT NOT NULL,
                staff_level TEXT NOT NULL,
                previous_count INTEGER NOT NULL,
                tier INTEGER NOT NULL,
                punishment_type TEXT NOT NULL,
                duration_ms INTEGER,
                evidence TEXT,
                alternate INTEGER NOT NULL DEFAULT 0,
                overridden INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                failure TEXT,
                appeal_status TEXT NOT NULL DEFAULT 'NONE',
                punishment_id INTEGER,
                review_due_at INTEGER,
                reviewed_by_uuid TEXT,
                reviewed_by_name TEXT,
                reviewed_at INTEGER,
                review_note TEXT,
                reversed_by_uuid TEXT,
                reversed_by_name TEXT,
                reversed_reason TEXT,
                reversed_at INTEGER
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_punish_incidents_target ON punish_incidents (target_uuid, rule_id, created_at)")

        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS punish_confirmations (
                id TEXT PRIMARY KEY,
                staff_uuid TEXT NOT NULL,
                staff_name TEXT NOT NULL,
                target_uuid TEXT NOT NULL,
                target_name TEXT NOT NULL,
                rule_id INTEGER NOT NULL,
                gamemode TEXT NOT NULL,
                network INTEGER NOT NULL,
                override_tier INTEGER,
                alternate INTEGER NOT NULL,
                evidence TEXT,
                fingerprint TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )

        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS punish_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                actor_uuid TEXT,
                actor_name TEXT NOT NULL,
                action TEXT NOT NULL,
                target_uuid TEXT,
                target_name TEXT,
                incident_id INTEGER,
                rule_id INTEGER,
                rule_keyword TEXT,
                details TEXT NOT NULL,
                prev_hash TEXT NOT NULL,
                hash TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    // ── Escalation ──────────────────────────────────────────

    /**
     * Earlier offenses of [ruleId] that count towards the next tier: enforced, not overturned,
     * created at or after [since]. Counted across every gamemode (history is shared).
     */
    fun qualifyingCount(target: UUID, ruleId: Int, since: Long): Int =
        db.queryFirst(
            "SELECT COUNT(*) AS n FROM punish_incidents WHERE target_uuid = ? AND rule_id = ? AND created_at >= ? AND status = ? AND appeal_status != ?",
            target.toString(), ruleId, since, Status.ENFORCED.name, Appeal.OVERTURNED.name
        ) { it.getInt("n") } ?: 0

    /**
     * An enforced (or executing), not overturned incident of [ruleId] for [target] with exactly
     * this [evidence] since [since]: the same incident reported twice. Limited in time so generic
     * evidence text reused for a genuinely new offense later isn't refused.
     */
    fun hasDuplicateEvidence(target: UUID, ruleId: Int, evidence: String, since: Long): Boolean =
        db.queryFirst(
            "SELECT 1 AS found FROM punish_incidents WHERE target_uuid = ? AND rule_id = ? AND evidence = ? AND created_at >= ? AND status IN (?, ?) AND appeal_status != ? LIMIT 1",
            target.toString(), ruleId, evidence, since, Status.ENFORCED.name, Status.EXECUTING.name, Appeal.OVERTURNED.name
        ) { it.getInt("found") } != null

    // ── Incidents ───────────────────────────────────────────

    /** Inserts the incident and returns its id (joins an enclosing transaction). */
    fun insertIncident(i: NewIncident): Int? {
        val p = i.plan
        val appeal = if (p.needsReview) Appeal.PENDING_REVIEW else Appeal.NONE
        return insertReturningId(
            """
            INSERT INTO punish_incidents (confirmation_id, target_uuid, target_name, rule_id, rule_keyword, rule_name, gamemode, scope,
                created_at, staff_uuid, staff_name, staff_level, previous_count, tier, punishment_type, duration_ms, evidence,
                alternate, overridden, status, appeal_status, punishment_id, review_due_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                i.confirmationId, i.targetUuid.toString(), i.targetName, p.rule.id, p.rule.keyword, p.rule.displayName,
                p.gamemode.id, p.scopeId, i.now, i.staffUuid, i.staffName, p.level.id, p.previousCount, p.tierNumber,
                p.tier.action.name, i.durationMs, i.evidence, if (p.alternate) 1 else 0, if (p.overridden) 1 else 0,
                i.status.name, appeal.name, i.punishmentId, i.reviewDueAt,
            )
        )
    }

    fun setStatus(incidentId: Int, from: Status, to: Status, failure: String? = null): Boolean =
        db.executeUpdate(
            "UPDATE punish_incidents SET status = ?, failure = COALESCE(?, failure) WHERE id = ? AND status = ?",
            to.name, failure, incidentId, from.name
        ) == 1

    /** Kicks left EXECUTING by a crash: never re-run, only marked. Returns how many. */
    fun markInterrupted(): Int =
        db.executeUpdate(
            "UPDATE punish_incidents SET status = ?, failure = 'server stopped before execution finished' WHERE status = ?",
            Status.INTERRUPTED.name, Status.EXECUTING.name
        )

    fun incident(id: Int): Incident? =
        db.queryFirst("SELECT * FROM punish_incidents WHERE id = ?", id) { mapIncident(it) }

    fun history(target: UUID, ruleId: Int? = null, limit: Int = 50): List<Incident> =
        if (ruleId == null) db.query(
            "SELECT * FROM punish_incidents WHERE target_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ?",
            target.toString(), limit
        ) { mapIncident(it) }
        else db.query(
            "SELECT * FROM punish_incidents WHERE target_uuid = ? AND rule_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
            target.toString(), ruleId, limit
        ) { mapIncident(it) }

    fun pendingReviews(): List<Incident> =
        db.query(
            "SELECT * FROM punish_incidents WHERE appeal_status = ? ORDER BY review_due_at ASC",
            Appeal.PENDING_REVIEW.name
        ) { mapIncident(it) }

    /** PENDING_REVIEW -> REVIEWED, once. */
    fun markReviewed(id: Int, byUuid: String?, byName: String, note: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE punish_incidents SET appeal_status = ?, reviewed_by_uuid = ?, reviewed_by_name = ?, reviewed_at = ?, review_note = ? WHERE id = ? AND appeal_status = ?",
            Appeal.REVIEWED.name, byUuid, byName, now, note, id, Appeal.PENDING_REVIEW.name
        ) == 1

    /** Marks an incident overturned, once. It stays in the history but stops counting. */
    fun markOverturned(id: Int, byUuid: String?, byName: String, reason: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE punish_incidents SET appeal_status = ?, reversed_by_uuid = ?, reversed_by_name = ?, reversed_reason = ?, reversed_at = ? WHERE id = ? AND appeal_status != ?",
            Appeal.OVERTURNED.name, byUuid, byName, reason, now, id, Appeal.OVERTURNED.name
        ) == 1

    private fun mapIncident(rs: java.sql.ResultSet): Incident = Incident(
        id = rs.getInt("id"),
        targetUuid = UUID.fromString(rs.getString("target_uuid")),
        targetName = rs.getString("target_name"),
        ruleId = rs.getInt("rule_id"),
        ruleKeyword = rs.getString("rule_keyword"),
        ruleName = rs.getString("rule_name"),
        gamemode = rs.getString("gamemode"),
        scope = rs.getString("scope"),
        createdAt = rs.getLong("created_at"),
        staffUuid = rs.getString("staff_uuid"),
        staffName = rs.getString("staff_name"),
        staffLevel = rs.getString("staff_level"),
        previousCount = rs.getInt("previous_count"),
        tier = rs.getInt("tier"),
        action = rs.getString("punishment_type"),
        durationMs = rs.getLong("duration_ms").takeIf { !rs.wasNull() },
        evidence = rs.getString("evidence"),
        status = Status.entries.firstOrNull { it.name == rs.getString("status") } ?: Status.FAILED,
        appeal = Appeal.entries.firstOrNull { it.name == rs.getString("appeal_status") } ?: Appeal.NONE,
        punishmentId = rs.getInt("punishment_id").takeIf { !rs.wasNull() },
        reviewDueAt = rs.getLong("review_due_at").takeIf { !rs.wasNull() },
        reversedBy = rs.getString("reversed_by_name"),
        reversedReason = rs.getString("reversed_reason"),
    )

    // ── Confirmations ───────────────────────────────────────

    fun insertConfirmation(c: Confirmation) {
        db.execute(
            """
            INSERT INTO punish_confirmations (id, staff_uuid, staff_name, target_uuid, target_name, rule_id, gamemode, network,
                override_tier, alternate, evidence, fingerprint, created_at, expires_at, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            c.id, c.staffUuid, c.staffName, c.targetUuid.toString(), c.targetName, c.ruleId, c.gamemode,
            if (c.network) 1 else 0, c.overrideTier, if (c.alternate) 1 else 0, c.evidence, c.fingerprint,
            c.createdAt, c.expiresAt, c.status.name
        )
    }

    fun confirmation(id: String): Confirmation? =
        db.queryFirst("SELECT * FROM punish_confirmations WHERE id = ?", id) { rs ->
            Confirmation(
                id = rs.getString("id"),
                staffUuid = rs.getString("staff_uuid"),
                staffName = rs.getString("staff_name"),
                targetUuid = UUID.fromString(rs.getString("target_uuid")),
                targetName = rs.getString("target_name"),
                ruleId = rs.getInt("rule_id"),
                gamemode = rs.getString("gamemode"),
                network = rs.getInt("network") == 1,
                overrideTier = rs.getInt("override_tier").takeIf { !rs.wasNull() },
                alternate = rs.getInt("alternate") == 1,
                evidence = rs.getString("evidence"),
                fingerprint = rs.getString("fingerprint"),
                createdAt = rs.getLong("created_at"),
                expiresAt = rs.getLong("expires_at"),
                status = ConfirmationStatus.entries.firstOrNull { it.name == rs.getString("status") } ?: ConfirmationStatus.EXPIRED,
            )
        }

    /** PENDING -> USED for [staffUuid] while unexpired. Exactly one caller can win. */
    fun claimConfirmation(id: String, staffUuid: String, now: Long): Boolean =
        db.executeUpdate(
            "UPDATE punish_confirmations SET status = ? WHERE id = ? AND staff_uuid = ? AND status = ? AND expires_at > ?",
            ConfirmationStatus.USED.name, id, staffUuid, ConfirmationStatus.PENDING.name, now
        ) == 1

    /** PENDING -> [to] (CANCELLED / EXPIRED / STALE). */
    fun closeConfirmation(id: String, to: ConfirmationStatus): Boolean =
        db.executeUpdate(
            "UPDATE punish_confirmations SET status = ? WHERE id = ? AND status = ?",
            to.name, id, ConfirmationStatus.PENDING.name
        ) == 1

    fun setConfirmationEvidence(id: String, evidence: String) {
        db.execute("UPDATE punish_confirmations SET evidence = ? WHERE id = ? AND status = ?", evidence, id, ConfirmationStatus.PENDING.name)
    }

    /** Cancels a staff member's other open confirmations for the same target and rule. */
    fun supersede(staffUuid: String, target: UUID, ruleId: Int): Int =
        db.executeUpdate(
            "UPDATE punish_confirmations SET status = ? WHERE staff_uuid = ? AND target_uuid = ? AND rule_id = ? AND status = ?",
            ConfirmationStatus.CANCELLED.name, staffUuid, target.toString(), ruleId, ConfirmationStatus.PENDING.name
        )

    fun expireConfirmations(now: Long): Int =
        db.executeUpdate(
            "UPDATE punish_confirmations SET status = ? WHERE status = ? AND expires_at <= ?",
            ConfirmationStatus.EXPIRED.name, ConfirmationStatus.PENDING.name, now
        )

    // ── Audit ───────────────────────────────────────────────

    /** Appends one audit row, chained to the previous row's hash. Joins an enclosing transaction. */
    fun audit(entry: AuditEntry, now: Long) {
        db.transaction {
            val prev = db.queryFirst("SELECT hash FROM punish_audit ORDER BY id DESC LIMIT 1") { it.getString("hash") } ?: GENESIS
            val fields = listOf(
                now.toString(), entry.actorUuid.orEmpty(), entry.actorName, entry.action,
                entry.targetUuid?.toString().orEmpty(), entry.targetName.orEmpty(), entry.incidentId?.toString().orEmpty(),
                entry.rule?.id?.toString().orEmpty(), entry.rule?.keyword.orEmpty(), entry.details,
            )
            db.execute(
                "INSERT INTO punish_audit (at, actor_uuid, actor_name, action, target_uuid, target_name, incident_id, rule_id, rule_keyword, details, prev_hash, hash) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                now, entry.actorUuid, entry.actorName, entry.action, entry.targetUuid?.toString(), entry.targetName,
                entry.incidentId, entry.rule?.id, entry.rule?.keyword, entry.details, prev, chainHash(prev, fields)
            )
        }
    }

    /** Walks the audit chain. Returns the id of the first row that doesn't match, or null when intact. */
    fun verifyAudit(): Int? {
        var prev = GENESIS
        val rows = db.query("SELECT * FROM punish_audit ORDER BY id ASC") { rs ->
            Triple(
                rs.getInt("id"),
                rs.getString("prev_hash") to rs.getString("hash"),
                listOf(
                    rs.getLong("at").toString(), rs.getString("actor_uuid").orEmpty(), rs.getString("actor_name"),
                    rs.getString("action"), rs.getString("target_uuid").orEmpty(), rs.getString("target_name").orEmpty(),
                    rs.getInt("incident_id").let { v -> if (rs.wasNull()) "" else v.toString() },
                    rs.getInt("rule_id").let { v -> if (rs.wasNull()) "" else v.toString() },
                    rs.getString("rule_keyword").orEmpty(), rs.getString("details"),
                ),
            )
        }
        for ((id, hashes, fields) in rows) {
            val (storedPrev, stored) = hashes
            if (storedPrev != prev || stored != chainHash(prev, fields)) return id
            prev = stored
        }
        return null
    }

    fun auditCount(): Int = db.queryFirst("SELECT COUNT(*) AS n FROM punish_audit") { it.getInt("n") } ?: 0

    companion object {
        private const val GENESIS = "genesis"

        fun chainHash(prev: String, fields: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(prev.toByteArray())
            for (field in fields) {
                digest.update(0)
                digest.update(field.toByteArray())
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
