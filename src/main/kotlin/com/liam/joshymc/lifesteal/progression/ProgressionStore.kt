package com.liam.joshymc.lifesteal.progression

import com.liam.joshymc.lifesteal.progression.ProgressionRules.Milestone
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Season
import com.liam.joshymc.manager.SqliteDatabase

/**
 * Persistent launch progression (issue #1352), Bukkit-free, in the Lifesteal server's own `data.db`
 * (tables created only where Lifesteal is hosted).
 *
 * - `lifesteal_seasons`: one row per season (stable id, launch time in UTC epoch millis, status).
 *   At most one is ACTIVE (partial UNIQUE index). Rows are never deleted or overwritten by another
 *   season; this issue only ever creates [ProgressionRules.FIRST_SEASON_ID].
 * - `lifesteal_season_milestones`: milestones reached per season (PK season + milestone, so each is
 *   recorded once) and whether it was announced (a conditional UPDATE claims the announcement).
 * - `lifesteal_progression_audit`: every create/move/activate and config discrepancy.
 *
 * The launch time only changes through [createSeason] / [moveLaunch], both of which refuse once the
 * stored launch time has passed (checked inside the UPDATE), so a launched season can't be reset.
 */
class ProgressionStore(private val db: SqliteDatabase) {

    class StaleWriteException(message: String) : RuntimeException(message)

    data class MilestoneRow(val milestone: Milestone, val reachedAt: Long, val announced: Boolean)

    data class AuditRow(val at: Long, val seasonId: String, val action: String, val actor: String, val before: String?, val after: String?, val detail: String?)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_seasons (
                season_id TEXT PRIMARY KEY,
                launch_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                created_by TEXT NOT NULL,
                source TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS idx_lifesteal_seasons_active ON lifesteal_seasons(status) WHERE status = 'ACTIVE'")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_season_milestones (
                season_id TEXT NOT NULL,
                milestone TEXT NOT NULL,
                reached_at INTEGER NOT NULL,
                recorded_at INTEGER NOT NULL,
                announced INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (season_id, milestone)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_progression_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                season_id TEXT NOT NULL,
                at INTEGER NOT NULL,
                action TEXT NOT NULL,
                actor TEXT NOT NULL,
                before_value TEXT,
                after_value TEXT,
                detail TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_progression_audit_season ON lifesteal_progression_audit(season_id, at)")
    }

    /** The ACTIVE season, or null when none was ever scheduled. Throws on a database error or a malformed row. */
    fun activeSeason(): Season? = db.queryFirst(
        "SELECT season_id, launch_at, version FROM lifesteal_seasons WHERE status = 'ACTIVE'"
    ) { rs ->
        val id = rs.getString("season_id")
        if (!ProgressionRules.validSeasonId(id)) throw IllegalStateException("the active season id '$id' is not a valid season id")
        Season(id, rs.getLong("launch_at"), rs.getInt("version"))
    }

    fun seasonCount(): Int = db.queryFirst("SELECT COUNT(*) FROM lifesteal_seasons") { it.getInt(1) } ?: 0

    /** Milestones recorded for [seasonId] only. Unknown milestone names (a newer build) are skipped. */
    fun milestones(seasonId: String): Map<Milestone, MilestoneRow> =
        db.query("SELECT milestone, reached_at, announced FROM lifesteal_season_milestones WHERE season_id = ?", seasonId) { rs ->
            Milestone.fromId(rs.getString("milestone"))?.let { MilestoneRow(it, rs.getLong("reached_at"), rs.getInt("announced") == 1) }
        }.filterNotNull().associateBy { it.milestone }

    /**
     * Creates [seasonId] as the ACTIVE season. Refused (false) when any active season exists or the
     * id is taken: one season is never initialized twice.
     */
    fun createSeason(seasonId: String, launchAt: Long, actor: String, source: String, now: Long): Boolean {
        require(ProgressionRules.validSeasonId(seasonId)) { "invalid season id $seasonId" }
        var created = false
        db.transaction {
            val active = db.queryFirst("SELECT 1 FROM lifesteal_seasons WHERE status = 'ACTIVE' OR season_id = ?", seasonId) { 1 }
            if (active != null) return@transaction
            db.executeUpdate(
                "INSERT INTO lifesteal_seasons (season_id, launch_at, status, created_at, created_by, source, updated_at, version) VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, 1)",
                seasonId, launchAt, now, actor, source, now
            )
            audit(seasonId, now, "CREATE", actor, null, ProgressionRules.formatUtc(launchAt), "source=$source")
            created = true
        }
        return created
    }

    /**
     * Moves the launch of a season that hasn't launched yet. The UPDATE only matches the expected
     * [version] and a stored launch time still after [now], so a stale command or a season that
     * launched meanwhile changes nothing (throws [StaleWriteException], the transaction rolls back).
     */
    fun moveLaunch(seasonId: String, expectedVersion: Int, from: Long, to: Long, actor: String, action: String, now: Long) {
        db.transaction {
            val changed = db.executeUpdate(
                "UPDATE lifesteal_seasons SET launch_at = ?, updated_at = ?, version = version + 1 " +
                    "WHERE season_id = ? AND status = 'ACTIVE' AND version = ? AND launch_at = ? AND launch_at > ?",
                to, now, seasonId, expectedVersion, from, now
            )
            if (changed != 1) throw StaleWriteException("the season changed or launched meanwhile")
            audit(seasonId, now, action, actor, ProgressionRules.formatUtc(from), ProgressionRules.formatUtc(to), null)
        }
    }

    /** Records [milestones] as reached (INSERT OR IGNORE: each one once per season). Returns the newly recorded ones. */
    fun recordMilestones(seasonId: String, milestones: Map<Milestone, Long>, now: Long): Set<Milestone> {
        val added = mutableSetOf<Milestone>()
        db.transaction {
            for ((milestone, reachedAt) in milestones) {
                val rows = db.executeUpdate(
                    "INSERT OR IGNORE INTO lifesteal_season_milestones (season_id, milestone, reached_at, recorded_at, announced) VALUES (?, ?, ?, ?, 0)",
                    seasonId, milestone.id, reachedAt, now
                )
                if (rows > 0) {
                    added += milestone
                    audit(seasonId, now, "MILESTONE", "server", null, milestone.id, "scheduled ${ProgressionRules.formatUtc(reachedAt)}")
                }
            }
        }
        return added
    }

    /**
     * Marks [milestones] announced. Returns true only for the caller that flipped [announce] from 0,
     * so the announcement goes out once even across restarts.
     */
    fun claimAnnouncement(seasonId: String, announce: Milestone, alsoMark: Collection<Milestone>): Boolean {
        var claimed = false
        db.transaction {
            claimed = db.executeUpdate(
                "UPDATE lifesteal_season_milestones SET announced = 1 WHERE season_id = ? AND milestone = ? AND announced = 0",
                seasonId, announce.id
            ) == 1
            for (m in alsoMark) {
                db.executeUpdate("UPDATE lifesteal_season_milestones SET announced = 1 WHERE season_id = ? AND milestone = ? AND announced = 0", seasonId, m.id)
            }
        }
        return claimed
    }

    fun audit(seasonId: String, at: Long, action: String, actor: String, before: String?, after: String?, detail: String?) {
        db.executeUpdate(
            "INSERT INTO lifesteal_progression_audit (season_id, at, action, actor, before_value, after_value, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
            seasonId, at, action, actor, before, after, detail
        )
    }

    fun recentAudit(limit: Int): List<AuditRow> = db.query(
        "SELECT season_id, at, action, actor, before_value, after_value, detail FROM lifesteal_progression_audit ORDER BY id DESC LIMIT ?",
        limit.coerceIn(1, 50)
    ) { rs ->
        AuditRow(rs.getLong("at"), rs.getString("season_id"), rs.getString("action"), rs.getString("actor"), rs.getString("before_value"), rs.getString("after_value"), rs.getString("detail"))
    }
}
