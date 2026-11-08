package com.liam.joshymc.lifesteal.profile

import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Lifesteal profile data (issue #1429), in the Lifesteal server's own data.db. Bukkit-free: talks
 * only to [SqliteDatabase], so `scripts/LifestealProfileCheck.kt` runs it against real SQLite.
 *
 * Reads other systems' tables, never writes them: `lifesteal_heart_transactions` (PvP), the
 * heart records, `lifesteal_seasons`, bounty payouts, the ranked rating history, team membership,
 * `player_connections` (AltManager) and `playtime`. A table that doesn't exist (a system that never
 * started here) reads as empty.
 *
 * Owns:
 * - `lifesteal_profile_counters`: activity counters per (player, season, counter). Only ever raised
 *   (`value = value + n`, CHECK >= 0); a new season simply uses a new row, old seasons stay.
 * - `lifesteal_profile_dimensions`: dimensions entered per (player, season), once each.
 * - `lifesteal_achievements`: one row per (player, achievement, tier, period) once reached. The
 *   primary key makes completion happen once; status only moves COMPLETED -> CLAIMING -> CLAIMED
 *   through conditional UPDATEs, and triggers refuse any other change and every delete. The rewards
 *   are snapshotted on the row when the claim starts, so a retry after a crash delivers exactly what
 *   the first attempt promised, even if the config changed meanwhile.
 * - `lifesteal_profile_settings`: the player's chosen title / border / featured achievements / privacy.
 * - `lifesteal_achievement_audit`: completions, claims, deliveries and setting changes.
 */
class ProfileStore(private val db: SqliteDatabase) {

    class StaleWriteException(message: String) : RuntimeException(message)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_profile_counters (
                uuid TEXT NOT NULL,
                season_id TEXT NOT NULL,
                counter TEXT NOT NULL,
                value INTEGER NOT NULL CHECK (value >= 0),
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, season_id, counter)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_profile_counters_board ON lifesteal_profile_counters (season_id, counter, value DESC)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_profile_counters_raise BEFORE UPDATE OF value ON lifesteal_profile_counters
            WHEN NEW.value < OLD.value
            BEGIN
                SELECT RAISE(ABORT, 'profile counters only go up');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_profile_dimensions (
                uuid TEXT NOT NULL,
                season_id TEXT NOT NULL,
                dimension TEXT NOT NULL,
                first_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, season_id, dimension)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_achievements (
                uuid TEXT NOT NULL,
                achievement_id TEXT NOT NULL,
                tier INTEGER NOT NULL,
                period TEXT NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('COMPLETED', 'CLAIMING', 'CLAIMED')),
                value_at_completion INTEGER NOT NULL,
                completed_at INTEGER NOT NULL,
                claim_started_at INTEGER,
                claimed_at INTEGER,
                rewards TEXT,
                PRIMARY KEY (uuid, achievement_id, tier, period)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_achievements_status ON lifesteal_achievements (status)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_achievements_completed ON lifesteal_achievements (completed_at)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_achievements_forward BEFORE UPDATE ON lifesteal_achievements
            WHEN NOT (
                (OLD.status = 'COMPLETED' AND NEW.status = 'CLAIMING') OR
                (OLD.status = 'CLAIMING' AND NEW.status IN ('CLAIMING', 'CLAIMED'))
            ) OR NEW.uuid != OLD.uuid OR NEW.achievement_id != OLD.achievement_id OR NEW.tier != OLD.tier
              OR NEW.period != OLD.period OR NEW.completed_at != OLD.completed_at OR NEW.value_at_completion != OLD.value_at_completion
            BEGIN
                SELECT RAISE(ABORT, 'achievement rows only move forward');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_achievements_keep BEFORE DELETE ON lifesteal_achievements
            BEGIN
                SELECT RAISE(ABORT, 'achievement rows are never deleted');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_profile_settings (
                uuid TEXT PRIMARY KEY,
                title TEXT,
                border TEXT,
                featured TEXT,
                private INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_achievement_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                action TEXT NOT NULL,
                achievement_id TEXT,
                tier INTEGER,
                period TEXT,
                detail TEXT,
                at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_achievement_audit_uuid ON lifesteal_achievement_audit (uuid, at)")
        // Per-player PvP reads over the heart system's history (the table is the heart system's; only indexes are added).
        if (tableExists("lifesteal_heart_transactions")) {
            db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_tx_killer ON lifesteal_heart_transactions (killer, created_at)")
            db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_heart_tx_victim ON lifesteal_heart_transactions (victim, created_at)")
        }
    }

    fun tableExists(name: String): Boolean =
        db.queryFirst("SELECT 1 AS hit FROM sqlite_master WHERE type = 'table' AND name = ?", name) { true } == true

    // ── Other systems' data (read-only) ─────────────────────

    fun seasons(): List<SeasonRow> = if (!tableExists("lifesteal_seasons")) emptyList() else
        db.query("SELECT season_id, launch_at, status FROM lifesteal_seasons") { SeasonRow(it.getString("season_id"), it.getLong("launch_at"), it.getString("status")) }

    /** Every evaluated Lifesteal PvP death ([player] null = everyone; else deaths they were part of), oldest first. */
    fun pvpRows(player: UUID? = null): List<PvpRow> {
        if (!tableExists("lifesteal_heart_transactions")) return emptyList()
        val where = if (player == null) "" else " AND (killer = ? OR victim = ?)"
        val params: Array<Any?> = if (player == null) emptyArray() else arrayOf(player.toString(), player.toString())
        return db.query(
            "SELECT killer, victim, outcome, created_at FROM lifesteal_heart_transactions WHERE type = 'PVP' AND killer IS NOT NULL AND victim IS NOT NULL$where ORDER BY created_at, id",
            *params
        ) { rs ->
            val killer = runCatching { UUID.fromString(rs.getString("killer")) }.getOrNull()
            val victim = runCatching { UUID.fromString(rs.getString("victim")) }.getOrNull()
            if (killer == null || victim == null) null else PvpRow(killer, victim, rs.getString("outcome"), rs.getLong("created_at"))
        }.filterNotNull()
    }

    /** Account pairs that shared a connection hash (AltManager), limited to pairs including [player] when given. */
    fun linkedPairs(player: UUID? = null): Set<Pair<UUID, UUID>> {
        if (!tableExists("player_connections")) return emptySet()
        val where = if (player == null) "" else " WHERE a.uuid = ?"
        val params: Array<Any?> = if (player == null) emptyArray() else arrayOf(player.toString())
        return db.query(
            "SELECT DISTINCT a.uuid AS a, b.uuid AS b FROM player_connections a JOIN player_connections b " +
                "ON a.connection_hash = b.connection_hash AND a.uuid != b.uuid$where",
            *params
        ) { rs ->
            val a = runCatching { UUID.fromString(rs.getString("a")) }.getOrNull()
            val b = runCatching { UUID.fromString(rs.getString("b")) }.getOrNull()
            if (a == null || b == null) null else PvpCounting.pair(a, b)
        }.filterNotNull().toSet()
    }

    fun hearts(uuid: UUID): Int? = if (!tableExists("lifesteal_hearts")) null else
        db.queryFirst("SELECT hearts FROM lifesteal_hearts WHERE uuid = ?", uuid.toString()) { com.liam.joshymc.lifesteal.hearts.HeartStore.wholeHearts(it.getObject("hearts")) }

    fun hasHeartRecord(uuid: UUID): Boolean = tableExists("lifesteal_hearts") &&
        db.queryFirst("SELECT 1 AS hit FROM lifesteal_hearts WHERE uuid = ?", uuid.toString()) { true } == true

    /** Bounties claimed (payout rows) per killer inside [window] (null = all time). */
    fun bountyClaims(player: UUID?, window: SeasonWindow?): Map<UUID, Long> {
        if (!tableExists("lifesteal_bounty_payouts")) return emptyMap()
        val (clause, params) = filters(player, "killer", window, "created_at")
        return db.query("SELECT killer, COUNT(*) AS n FROM lifesteal_bounty_payouts$clause GROUP BY killer", *params) { rs ->
            runCatching { UUID.fromString(rs.getString("killer")) }.getOrNull()?.let { it to rs.getLong("n") }
        }.filterNotNull().toMap()
    }

    data class RankedTotals(val matches: Long, val wins: Long, val peakRating: Int?)

    /** Rated matches, wins and the peak Player Elo per player, from the committed rating history ([seasonId] null = every season). */
    fun rankedTotals(player: UUID?, seasonId: String?): Map<UUID, RankedTotals> {
        if (!tableExists("lifesteal_ranked_rating_history")) return emptyMap()
        val conditions = mutableListOf("rating_type = 'PLAYER_ELO'")
        val params = mutableListOf<Any?>()
        if (player != null) { conditions += "subject_id = ?"; params += player.toString() }
        if (seasonId != null) { conditions += "season_id = ?"; params += seasonId }
        return db.query(
            "SELECT subject_id, COUNT(DISTINCT match_id) AS matches, SUM(CASE WHEN outcome = 'WIN' THEN 1 ELSE 0 END) AS wins, MAX(new) AS peak " +
                "FROM lifesteal_ranked_rating_history WHERE ${conditions.joinToString(" AND ")} GROUP BY subject_id",
            *params.toTypedArray()
        ) { rs ->
            runCatching { UUID.fromString(rs.getString("subject_id")) }.getOrNull()?.let {
                it to RankedTotals(rs.getLong("matches"), rs.getLong("wins"), rs.getInt("peak").takeUnless { rs.wasNull() })
            }
        }.filterNotNull().toMap()
    }

    /** The peak Team Elo of the teams each player played rated matches for ([seasonId] null = every season). */
    fun peakTeamRatings(player: UUID?, seasonId: String?): Map<UUID, Int> {
        if (!tableExists("lifesteal_ranked_rating_history")) return emptyMap()
        val conditions = mutableListOf("p.rating_type = 'PLAYER_ELO'")
        val params = mutableListOf<Any?>()
        if (player != null) { conditions += "p.subject_id = ?"; params += player.toString() }
        if (seasonId != null) { conditions += "p.season_id = ?"; params += seasonId }
        return db.query(
            "SELECT p.subject_id AS uuid, MAX(t.new) AS peak FROM lifesteal_ranked_rating_history p " +
                "JOIN lifesteal_ranked_rating_history t ON t.match_id = p.match_id AND t.rating_type = 'TEAM_ELO' AND t.subject_id = p.team_id " +
                "WHERE ${conditions.joinToString(" AND ")} GROUP BY p.subject_id",
            *params.toTypedArray()
        ) { rs -> runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull()?.let { it to rs.getInt("peak") } }.filterNotNull().toMap()
    }

    /** The current-season rating of [subject] (a UUID or a team id), or null before its first rated match. */
    fun currentRating(seasonId: String, type: String, subject: String): Int? = if (!tableExists("lifesteal_ranked_ratings")) null else
        db.queryFirst(
            "SELECT rating FROM lifesteal_ranked_ratings WHERE season_id = ? AND rating_type = ? AND subject_id = ?", seasonId, type, subject,
        ) { it.getInt("rating") }

    /** Players who are or were in a Lifesteal team. */
    fun teamMembers(player: UUID?): Set<UUID> {
        val out = HashSet<UUID>()
        val tables = listOf("lifesteal_team_members", "lifesteal_team_membership_history").filter { tableExists(it) }
        for (table in tables) {
            val sql = if (player == null) "SELECT DISTINCT uuid FROM $table" else "SELECT DISTINCT uuid FROM $table WHERE uuid = ?"
            val params: Array<Any?> = if (player == null) emptyArray() else arrayOf(player.toString())
            db.query(sql, *params) { rs -> runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() }.filterNotNull().forEach { out += it }
        }
        return out
    }

    /** Stored playtime seconds (PlaytimeManager's `playtime` table; the current session isn't in it). */
    fun playtimeSeconds(player: UUID?): Map<UUID, Long> {
        if (!tableExists("playtime")) return emptyMap()
        val sql = if (player == null) "SELECT uuid, total_seconds FROM playtime" else "SELECT uuid, total_seconds FROM playtime WHERE uuid = ?"
        val params: Array<Any?> = if (player == null) emptyArray() else arrayOf(player.toString())
        return db.query(sql, *params) { rs -> runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull()?.let { it to rs.getLong("total_seconds") } }
            .filterNotNull().toMap()
    }

    // ── Activity counters ───────────────────────────────────

    /** Adds [deltas] (counter -> amount > 0) for [uuid] in [seasonId], in one transaction. */
    fun addCounters(uuid: UUID, seasonId: String, deltas: Map<String, Long>, now: Long) {
        val positive = deltas.filterValues { it > 0 }
        if (positive.isEmpty()) return
        db.transaction {
            for ((counter, amount) in positive) {
                db.executeUpdate(
                    "INSERT INTO lifesteal_profile_counters (uuid, season_id, counter, value, updated_at) VALUES (?, ?, ?, ?, ?) " +
                        "ON CONFLICT (uuid, season_id, counter) DO UPDATE SET value = value + excluded.value, updated_at = excluded.updated_at",
                    uuid.toString(), seasonId, counter, amount, now
                )
            }
        }
    }

    /** Counters per player: [seasonId] null = summed over every season (all time). */
    fun counters(player: UUID?, seasonId: String?): Map<UUID, Map<String, Long>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (player != null) { conditions += "uuid = ?"; params += player.toString() }
        if (seasonId != null) { conditions += "season_id = ?"; params += seasonId }
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val out = HashMap<UUID, HashMap<String, Long>>()
        db.query("SELECT uuid, counter, SUM(value) AS total FROM lifesteal_profile_counters$where GROUP BY uuid, counter", *params.toTypedArray()) { rs ->
            val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: return@query
            out.getOrPut(uuid) { HashMap() }[rs.getString("counter")] = rs.getLong("total")
        }
        return out
    }

    /** True when [dimension] was newly recorded for this season. */
    fun recordDimension(uuid: UUID, seasonId: String, dimension: String, now: Long): Boolean =
        db.executeUpdate(
            "INSERT OR IGNORE INTO lifesteal_profile_dimensions (uuid, season_id, dimension, first_at) VALUES (?, ?, ?, ?)",
            uuid.toString(), seasonId, dimension, now
        ) == 1

    /** Distinct dimensions per player ([seasonId] null = all time). */
    fun dimensions(player: UUID?, seasonId: String?): Map<UUID, Long> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (player != null) { conditions += "uuid = ?"; params += player.toString() }
        if (seasonId != null) { conditions += "season_id = ?"; params += seasonId }
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        return db.query("SELECT uuid, COUNT(DISTINCT dimension) AS n FROM lifesteal_profile_dimensions$where GROUP BY uuid", *params.toTypedArray()) { rs ->
            runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull()?.let { it to rs.getLong("n") }
        }.filterNotNull().toMap()
    }

    // ── Achievements ────────────────────────────────────────

    data class AchievementRow(
        val uuid: UUID,
        val key: AchievementRules.Key,
        val status: String,
        val valueAtCompletion: Long,
        val completedAt: Long,
        val claimedAt: Long?,
        val rewards: String?,
    )

    fun achievements(uuid: UUID): List<AchievementRow> =
        db.query("SELECT * FROM lifesteal_achievements WHERE uuid = ? ORDER BY completed_at", uuid.toString()) { row(it) }

    fun achievement(uuid: UUID, key: AchievementRules.Key): AchievementRow? =
        db.queryFirst(
            "SELECT * FROM lifesteal_achievements WHERE uuid = ? AND achievement_id = ? AND tier = ? AND period = ?",
            uuid.toString(), key.achievementId, key.tier, key.period
        ) { row(it) }

    /** Records a reached tier once (PRIMARY KEY). True = newly completed (announce it). */
    fun complete(uuid: UUID, key: AchievementRules.Key, value: Long, now: Long): Boolean {
        var inserted = false
        db.transaction {
            inserted = db.executeUpdate(
                "INSERT OR IGNORE INTO lifesteal_achievements (uuid, achievement_id, tier, period, status, value_at_completion, completed_at) VALUES (?, ?, ?, ?, 'COMPLETED', ?, ?)",
                uuid.toString(), key.achievementId, key.tier, key.period, value, now
            ) == 1
            if (inserted) audit(uuid, "COMPLETED", key, "value=$value", now)
        }
        return inserted
    }

    sealed interface ClaimStart {
        /** This call owns the claim; deliver [rewards] (snapshotted on the row). */
        data class Started(val rewards: String) : ClaimStart
        data object NotCompleted : ClaimStart
        data object AlreadyClaimed : ClaimStart
        /** Another claim of the same row is in progress (or was interrupted; the recovery finishes it). */
        data object InProgress : ClaimStart
    }

    /** COMPLETED -> CLAIMING (conditional) with the reward snapshot; one caller ever wins. */
    fun beginClaim(uuid: UUID, key: AchievementRules.Key, rewards: String, now: Long): ClaimStart {
        var result: ClaimStart = ClaimStart.NotCompleted
        db.transaction {
            val updated = db.executeUpdate(
                "UPDATE lifesteal_achievements SET status = 'CLAIMING', claim_started_at = ?, rewards = ? " +
                    "WHERE uuid = ? AND achievement_id = ? AND tier = ? AND period = ? AND status = 'COMPLETED'",
                now, rewards, uuid.toString(), key.achievementId, key.tier, key.period
            )
            if (updated == 1) {
                audit(uuid, "CLAIM_STARTED", key, rewards, now)
                result = ClaimStart.Started(rewards)
                return@transaction
            }
            result = when (achievement(uuid, key)?.status) {
                "CLAIMED" -> ClaimStart.AlreadyClaimed
                "CLAIMING" -> ClaimStart.InProgress
                else -> ClaimStart.NotCompleted
            }
        }
        return result
    }

    /** CLAIMING -> CLAIMED, once every reward was recorded for delivery. */
    fun finishClaim(uuid: UUID, key: AchievementRules.Key, now: Long): Boolean {
        var done = false
        db.transaction {
            done = db.executeUpdate(
                "UPDATE lifesteal_achievements SET status = 'CLAIMED', claimed_at = ? WHERE uuid = ? AND achievement_id = ? AND tier = ? AND period = ? AND status = 'CLAIMING'",
                now, uuid.toString(), key.achievementId, key.tier, key.period
            ) == 1
            if (done) audit(uuid, "CLAIMED", key, null, now)
        }
        return done
    }

    /** Claims left CLAIMING (a crash or a failed delivery record): the recovery re-sends them by the same references. */
    fun unfinishedClaims(limit: Int = 200): List<AchievementRow> =
        db.query("SELECT * FROM lifesteal_achievements WHERE status = 'CLAIMING' ORDER BY claim_started_at LIMIT ?", limit) { row(it) }

    /** Achievement rows per player ([window] null = all time; else completed inside the season window). */
    fun completedCounts(window: SeasonWindow?): Map<UUID, Long> {
        val (clause, params) = filters(null, "uuid", window, "completed_at")
        return db.query("SELECT uuid, COUNT(*) AS n FROM lifesteal_achievements$clause GROUP BY uuid", *params) { rs ->
            runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull()?.let { it to rs.getLong("n") }
        }.filterNotNull().toMap()
    }

    fun countByStatus(): Map<String, Int> =
        db.query("SELECT status, COUNT(*) AS n FROM lifesteal_achievements GROUP BY status") { it.getString("status") to it.getInt("n") }.toMap()

    // ── Profile settings ────────────────────────────────────

    data class Settings(
        /** Achievement key text of the claimed tier whose title is shown, or null. */
        val title: String? = null,
        val border: String? = null,
        val featured: List<String> = emptyList(),
        val private: Boolean = false,
    )

    fun settings(uuid: UUID): Settings =
        db.queryFirst("SELECT * FROM lifesteal_profile_settings WHERE uuid = ?", uuid.toString()) { rs ->
            Settings(
                rs.getString("title"), rs.getString("border"),
                rs.getString("featured")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(ProfileDecorations.MAX_FEATURED) ?: emptyList(),
                rs.getInt("private") != 0,
            )
        } ?: Settings()

    /** Saves [settings]; every referenced achievement must belong to [uuid] (re-checked here, never trusted from a GUI). */
    fun saveSettings(uuid: UUID, settings: Settings, titleAllowed: (AchievementRow) -> Boolean, borderAllowed: (String) -> Boolean, now: Long): Boolean {
        var ok = false
        db.transaction {
            settings.title?.let { title ->
                val key = AchievementRules.parseKey(title) ?: return@transaction
                val row = achievement(uuid, key) ?: return@transaction
                if (row.status != "CLAIMED" || !titleAllowed(row)) return@transaction
            }
            if (settings.border != null && !borderAllowed(settings.border)) return@transaction
            if (settings.featured.size > ProfileDecorations.MAX_FEATURED || settings.featured.toSet().size != settings.featured.size) return@transaction
            for (f in settings.featured) {
                val key = AchievementRules.parseKey(f) ?: return@transaction
                achievement(uuid, key) ?: return@transaction
            }
            db.executeUpdate(
                "INSERT INTO lifesteal_profile_settings (uuid, title, border, featured, private, updated_at) VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT (uuid) DO UPDATE SET title = excluded.title, border = excluded.border, featured = excluded.featured, private = excluded.private, updated_at = excluded.updated_at",
                uuid.toString(), settings.title, settings.border, settings.featured.joinToString(",").ifEmpty { null }, if (settings.private) 1 else 0, now
            )
            db.executeUpdate(
                "INSERT INTO lifesteal_achievement_audit (uuid, action, detail, at) VALUES (?, 'SETTINGS', ?, ?)",
                uuid.toString(), "title=${settings.title} border=${settings.border} featured=${settings.featured} private=${settings.private}", now
            )
            ok = true
        }
        return ok
    }

    // ── Audit ───────────────────────────────────────────────

    fun audit(uuid: UUID, action: String, key: AchievementRules.Key?, detail: String?, now: Long) {
        db.executeUpdate(
            "INSERT INTO lifesteal_achievement_audit (uuid, action, achievement_id, tier, period, detail, at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            uuid.toString(), action, key?.achievementId, key?.tier, key?.period, detail?.take(500), now
        )
    }

    fun recentAudit(uuid: UUID, limit: Int): List<String> =
        db.query(
            "SELECT action, achievement_id, tier, period, detail, at FROM lifesteal_achievement_audit WHERE uuid = ? ORDER BY id DESC LIMIT ?",
            uuid.toString(), limit.coerceIn(1, 50)
        ) { rs ->
            val key = rs.getString("achievement_id")?.let { "$it:${rs.getInt("tier")}:${rs.getString("period")}" } ?: "-"
            "${rs.getLong("at")} ${rs.getString("action")} $key ${rs.getString("detail") ?: ""}".trim()
        }

    // ── Internals ───────────────────────────────────────────

    private fun filters(player: UUID?, playerColumn: String, window: SeasonWindow?, timeColumn: String): Pair<String, Array<Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        if (player != null) { conditions += "$playerColumn = ?"; params += player.toString() }
        if (window != null) {
            conditions += "$timeColumn >= ?"; params += window.start
            if (window.end != null) { conditions += "$timeColumn < ?"; params += window.end }
        }
        return (if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}") to params.toTypedArray()
    }

    private fun row(rs: java.sql.ResultSet) = AchievementRow(
        uuid = UUID.fromString(rs.getString("uuid")),
        key = AchievementRules.Key(rs.getString("achievement_id"), rs.getInt("tier"), rs.getString("period")),
        status = rs.getString("status"),
        valueAtCompletion = rs.getLong("value_at_completion"),
        completedAt = rs.getLong("completed_at"),
        claimedAt = rs.getLong("claimed_at").takeUnless { rs.wasNull() },
        rewards = rs.getString("rewards"),
    )

    companion object {
        /** Reward snapshot: one `kind|param|quantity` line per store reward, `title|<text>`, `border|<colour>`. */
        fun encodeRewards(rewards: List<AchievementReward>): String = rewards.joinToString("\n") { r ->
            when (r) {
                is AchievementReward.Store -> "${r.spec.kind.id}|${r.spec.param}|${r.spec.quantity}"
                is AchievementReward.Title -> "title|${r.text.replace("\n", " ")}"
                is AchievementReward.Border -> "border|${r.color}"
            }
        }

        fun decodeRewards(raw: String?): List<AchievementReward> = raw.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            when (parts[0]) {
                "title" -> parts.getOrNull(1)?.let { AchievementReward.Title(it) }
                "border" -> parts.getOrNull(1)?.let { ProfileDecorations.normalizeBorder(it) }?.let { AchievementReward.Border(it) }
                else -> {
                    val kind = PurchaseRewards.Kind.of(parts[0])?.takeIf { it in AchievementParser.ALLOWED_STORE_KINDS }
                    val quantity = parts.getOrNull(2)?.toIntOrNull()
                    if (kind == null || quantity == null || parts.size < 3) null else AchievementReward.Store(PurchaseRewards.Spec(kind, parts[1], quantity))
                }
            }
        }
    }
}
