package com.liam.joshymc.lifesteal.teams.progression

import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Lifesteal team progression (issue #1426): Team XP, upgrades, the team bank, shared objectives and
 * milestones, in the Lifesteal server's own data.db. Bukkit-free: talks only to [SqliteDatabase] and a
 * [Wallet], so `scripts/LifestealTeamProgressionCheck.kt` runs it against real SQLite.
 *
 * Everything belongs to the **team id** (the never-changing `lifesteal_teams.id`), never to a player
 * or a name: leaving, kicks, renames and leadership transfers never move progression, and a new team
 * (even one with the same name and members) starts at Level 1 with an empty bank.
 *
 * Every write is one [SqliteDatabase.transaction] that re-reads the rows it depends on, including the
 * team's `lifesteal_teams.status` (ACTIVE) and, for purchases and style changes, its **current**
 * leader, so a kicked member, a former leader or a disbanded team is refused at the moment of the
 * write, whatever a GUI showed. Tables:
 *
 * - `lifesteal_team_progress`: total Team XP per team (the level is derived from it).
 * - `lifesteal_team_season_xp`: Team XP per team and season (history; never reset).
 * - `lifesteal_team_xp_events`: one row per gameplay event, `dedupe_key` UNIQUE, so a repeated event
 *   (a second death event, a retried rating) is recorded once and awards once.
 * - `lifesteal_team_xp_daily`: base XP per team/day/bucket (source, whole team, member) for the caps.
 * - `lifesteal_team_bank` + `lifesteal_team_bank_ledger`: the team bank (cents, `CHECK >= 0`) and every
 *   deposit / purchase / milestone credit (`reference` UNIQUE, append-only).
 * - `lifesteal_team_upgrades` (tier per category; triggers refuse a lower tier or a delete) and
 *   `lifesteal_team_upgrade_purchases` (`transaction_id` PRIMARY KEY, append-only).
 * - `lifesteal_team_objective_progress` / `_subjects` (unique victims / contributors per cycle).
 * - `lifesteal_team_milestone_claims` (PRIMARY KEY team + level, append-only), `lifesteal_team_unlocks`,
 *   `lifesteal_team_style` and `lifesteal_team_progress_audit`.
 *
 * Disbanding never touches these tables: a disbanded team's XP, tiers and bank stay as history and,
 * because every write checks ACTIVE, can never be used again.
 */
class TeamProgressionStore(private val db: SqliteDatabase, private val wallet: Wallet) {

    /** The player's coin balance (EconomyManager on the server, an in-memory map in the checks). */
    interface Wallet {
        fun withdraw(uuid: UUID, coins: Double): Boolean
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_progress (
                team_id TEXT PRIMARY KEY,
                total_xp INTEGER NOT NULL DEFAULT 0 CHECK (total_xp >= 0),
                version INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_team_progress_down BEFORE UPDATE OF total_xp ON lifesteal_team_progress
            WHEN NEW.total_xp < OLD.total_xp
            BEGIN SELECT RAISE(ABORT, 'team xp never goes down'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_season_xp (
                team_id TEXT NOT NULL,
                season_id TEXT NOT NULL,
                xp INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (team_id, season_id)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_xp_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dedupe_key TEXT NOT NULL UNIQUE,
                team_id TEXT,
                member TEXT,
                source TEXT NOT NULL,
                subject TEXT,
                base_xp INTEGER NOT NULL DEFAULT 0,
                awarded_xp INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                season_id TEXT NOT NULL,
                day TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_xp_events_team ON lifesteal_team_xp_events (team_id, id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_xp_events_subject ON lifesteal_team_xp_events (team_id, source, subject, created_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_xp_daily (
                team_id TEXT NOT NULL,
                day TEXT NOT NULL,
                bucket TEXT NOT NULL,
                amount INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (team_id, day, bucket)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_bank (
                team_id TEXT PRIMARY KEY,
                balance_cents INTEGER NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
                deposited_cents INTEGER NOT NULL DEFAULT 0,
                spent_cents INTEGER NOT NULL DEFAULT 0,
                rewarded_cents INTEGER NOT NULL DEFAULT 0,
                version INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_bank_ledger (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                team_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                amount_cents INTEGER NOT NULL,
                balance_after INTEGER NOT NULL,
                actor TEXT,
                actor_name TEXT,
                reference TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_bank_ledger_team ON lifesteal_team_bank_ledger (team_id, id)")
        appendOnly("lifesteal_team_bank_ledger")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_upgrades (
                team_id TEXT NOT NULL,
                category TEXT NOT NULL,
                tier INTEGER NOT NULL CHECK (tier >= 0),
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (team_id, category)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_team_upgrades_down BEFORE UPDATE OF tier ON lifesteal_team_upgrades
            WHEN NEW.tier < OLD.tier
            BEGIN SELECT RAISE(ABORT, 'team upgrades are never downgraded'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_team_upgrades_delete BEFORE DELETE ON lifesteal_team_upgrades
            BEGIN SELECT RAISE(ABORT, 'team upgrades are never removed'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_upgrade_purchases (
                transaction_id TEXT PRIMARY KEY,
                team_id TEXT NOT NULL,
                category TEXT NOT NULL,
                from_tier INTEGER NOT NULL,
                to_tier INTEGER NOT NULL,
                cost_cents INTEGER NOT NULL,
                buyer TEXT NOT NULL,
                buyer_name TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_team_upgrade_purchases_tier ON lifesteal_team_upgrade_purchases (team_id, category, to_tier)")
        appendOnly("lifesteal_team_upgrade_purchases")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_objective_progress (
                team_id TEXT NOT NULL,
                objective_id TEXT NOT NULL,
                cycle TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                completed_at INTEGER,
                PRIMARY KEY (team_id, objective_id, cycle)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_objective_subjects (
                team_id TEXT NOT NULL,
                objective_id TEXT NOT NULL,
                cycle TEXT NOT NULL,
                subject TEXT NOT NULL,
                PRIMARY KEY (team_id, objective_id, cycle, subject)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_milestone_claims (
                team_id TEXT NOT NULL,
                level INTEGER NOT NULL,
                claimed_by TEXT NOT NULL,
                claimed_by_name TEXT,
                coins_cents INTEGER NOT NULL DEFAULT 0,
                title TEXT,
                season_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (team_id, level)
            )
            """.trimIndent()
        )
        appendOnly("lifesteal_team_milestone_claims")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_unlocks (
                team_id TEXT NOT NULL,
                unlock TEXT NOT NULL,
                source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (team_id, unlock)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_style (
                team_id TEXT PRIMARY KEY,
                color TEXT,
                title TEXT,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_progress_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                team_id TEXT NOT NULL,
                action TEXT NOT NULL,
                actor TEXT,
                actor_name TEXT,
                details TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_progress_audit_team ON lifesteal_team_progress_audit (team_id, id)")
    }

    private fun appendOnly(table: String) {
        db.createTable("CREATE TRIGGER IF NOT EXISTS trg_${table}_no_update BEFORE UPDATE ON $table BEGIN SELECT RAISE(ABORT, '$table is append-only'); END")
        db.createTable("CREATE TRIGGER IF NOT EXISTS trg_${table}_no_delete BEFORE DELETE ON $table BEGIN SELECT RAISE(ABORT, '$table is append-only'); END")
    }

    // ── Reads ───────────────────────────────────────────────

    data class ObjectiveState(val progress: Long, val completed: Boolean)

    data class Snapshot(
        val teamId: String,
        val totalXp: Long,
        val seasonXp: Long,
        val tiers: Map<UpgradeCategory, Int>,
        val bankCents: Long,
        val claimed: Set<Int>,
        val titles: List<String>,
        val color: String?,
        val title: String?,
        val cycle: String,
        val objectives: Map<String, ObjectiveState>,
    )

    fun snapshot(teamId: String, cycle: String, seasonId: String): Snapshot = Snapshot(
        teamId = teamId,
        totalXp = totalXp(teamId),
        seasonXp = db.queryFirst("SELECT xp FROM lifesteal_team_season_xp WHERE team_id = ? AND season_id = ?", teamId, seasonId) { it.getLong("xp") } ?: 0,
        tiers = tiers(teamId),
        bankCents = bankBalance(teamId),
        claimed = db.query("SELECT level FROM lifesteal_team_milestone_claims WHERE team_id = ?", teamId) { it.getInt("level") }.toSet(),
        titles = db.query("SELECT unlock FROM lifesteal_team_unlocks WHERE team_id = ? AND unlock LIKE 'title:%' ORDER BY created_at", teamId) {
            it.getString("unlock").removePrefix("title:")
        },
        color = db.queryFirst("SELECT color FROM lifesteal_team_style WHERE team_id = ?", teamId) { it.getString("color") },
        title = db.queryFirst("SELECT title FROM lifesteal_team_style WHERE team_id = ?", teamId) { it.getString("title") },
        cycle = cycle,
        objectives = db.query(
            "SELECT objective_id, progress, completed_at FROM lifesteal_team_objective_progress WHERE team_id = ? AND cycle = ?", teamId, cycle
        ) { rs -> rs.getString("objective_id") to ObjectiveState(rs.getLong("progress"), rs.getObject("completed_at") != null) }.toMap(),
    )

    fun totalXp(teamId: String): Long =
        db.queryFirst("SELECT total_xp FROM lifesteal_team_progress WHERE team_id = ?", teamId) { it.getLong("total_xp") } ?: 0

    fun tiers(teamId: String): Map<UpgradeCategory, Int> =
        db.query("SELECT category, tier FROM lifesteal_team_upgrades WHERE team_id = ?", teamId) { rs ->
            UpgradeCategory.fromId(rs.getString("category"))?.let { it to rs.getInt("tier") }
        }.filterNotNull().toMap()

    fun bankBalance(teamId: String): Long =
        db.queryFirst("SELECT balance_cents FROM lifesteal_team_bank WHERE team_id = ?", teamId) { it.getLong("balance_cents") } ?: 0

    /** Every ACTIVE team id (startup cache load). */
    fun activeTeamIds(): List<String> = db.query("SELECT id FROM lifesteal_teams WHERE status = 'ACTIVE'") { it.getString("id") }

    data class Contribution(val member: UUID, val xp: Long)

    fun contributions(teamId: String, seasonId: String): List<Contribution> =
        db.query(
            "SELECT member, SUM(awarded_xp) AS xp FROM lifesteal_team_xp_events WHERE team_id = ? AND season_id = ? AND member IS NOT NULL " +
                "AND awarded_xp > 0 GROUP BY member ORDER BY xp DESC", teamId, seasonId
        ) { rs -> runCatching { UUID.fromString(rs.getString("member")) }.getOrNull()?.let { Contribution(it, rs.getLong("xp")) } }.filterNotNull()

    data class Activity(val source: String, val member: UUID?, val xp: Long, val at: Long)

    fun activity(teamId: String, limit: Int): List<Activity> =
        db.query(
            "SELECT source, member, awarded_xp, created_at FROM lifesteal_team_xp_events WHERE team_id = ? AND awarded_xp > 0 ORDER BY id DESC LIMIT ?",
            teamId, limit
        ) { rs ->
            Activity(rs.getString("source"), rs.getString("member")?.let { runCatching { UUID.fromString(it) }.getOrNull() }, rs.getLong("awarded_xp"), rs.getLong("created_at"))
        }

    fun breakdown(teamId: String, seasonId: String): Map<String, Long> =
        db.query(
            "SELECT source, SUM(awarded_xp) AS xp FROM lifesteal_team_xp_events WHERE team_id = ? AND season_id = ? AND awarded_xp > 0 GROUP BY source",
            teamId, seasonId
        ) { rs -> rs.getString("source") to rs.getLong("xp") }.toMap()

    data class AuditRow(val action: String, val actor: String?, val actorName: String?, val details: String?, val at: Long)

    fun audit(teamId: String, limit: Int = 50): List<AuditRow> =
        db.query("SELECT * FROM lifesteal_team_progress_audit WHERE team_id = ? ORDER BY id DESC LIMIT ?", teamId, limit) { rs ->
            AuditRow(rs.getString("action"), rs.getString("actor"), rs.getString("actor_name"), rs.getString("details"), rs.getLong("created_at"))
        }

    fun eventStatus(dedupeKey: String): String? =
        db.queryFirst("SELECT status FROM lifesteal_team_xp_events WHERE dedupe_key = ?", dedupeKey) { it.getString("status") }

    // ── Team XP ─────────────────────────────────────────────

    /**
     * One verified gameplay event. [member] = the player who earned it (caps, membership age);
     * null for team events (a rated ranked match), which name [teamId] instead.
     */
    data class XpEvent(
        val dedupeKey: String,
        val source: XpSource,
        val member: UUID?,
        val teamId: String?,
        val baseXp: Long,
        /** Objective increments this event carries (e.g. PVP_KILLS 1, UNIQUE_VICTIMS 1). */
        val counts: Map<ObjectiveType, Long>,
        /** The victim / opponent (PvP same-victim rule, unique-victim objectives). */
        val subject: String?,
        val seasonId: String,
        val now: Long,
    )

    enum class Refusal(val message: String) {
        NO_TEAM("not in a Lifesteal team"),
        TOO_NEW("member joined the team too recently"),
        TEAM_INACTIVE("team is disbanded"),
        REPEAT_VICTIM("same victim within the PvP window"),
    }

    data class CompletedObjective(val objective: TeamProgressionSettings.Objective, val xp: Long)

    sealed interface AwardResult {
        data object Duplicate : AwardResult
        data class Refused(val reason: Refusal, val teamId: String?) : AwardResult
        data class Awarded(
            val teamId: String,
            val baseAllowed: Long,
            val awarded: Long,
            /** Some or all of the base XP was cut by a daily cap. */
            val capped: Boolean,
            val totalBefore: Long,
            val totalAfter: Long,
            val completed: List<CompletedObjective>,
        ) : AwardResult
    }

    fun award(event: XpEvent, s: TeamProgressionSettings): AwardResult = tx {
        val day = TeamProgressCalendar.day(event.now, s.xp.timezone)
        val inserted = db.executeUpdate(
            "INSERT OR IGNORE INTO lifesteal_team_xp_events (dedupe_key, team_id, member, source, subject, base_xp, awarded_xp, status, season_id, day, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0, 'PENDING', ?, ?, ?)",
            event.dedupeKey, event.teamId, event.member?.toString(), event.source.id, event.subject, event.baseXp, event.seasonId, day, event.now
        )
        if (inserted != 1) return@tx AwardResult.Duplicate

        fun finish(status: String, teamId: String?, awarded: Long = 0) {
            db.execute("UPDATE lifesteal_team_xp_events SET status = ?, team_id = ?, awarded_xp = ? WHERE dedupe_key = ?", status, teamId, awarded, event.dedupeKey)
        }

        val teamId: String
        if (event.member != null) {
            val row = db.queryFirst("SELECT team_id, joined_at FROM lifesteal_team_members WHERE uuid = ?", event.member.toString()) {
                it.getString("team_id") to it.getLong("joined_at")
            }
            if (row == null) { finish("NO_TEAM", null); return@tx AwardResult.Refused(Refusal.NO_TEAM, null) }
            if (event.teamId != null && event.teamId != row.first) { finish("NO_TEAM", row.first); return@tx AwardResult.Refused(Refusal.NO_TEAM, row.first) }
            if (row.second > event.now - s.xp.minimumMembershipMillis) { finish("TOO_NEW", row.first); return@tx AwardResult.Refused(Refusal.TOO_NEW, row.first) }
            teamId = row.first
        } else {
            teamId = event.teamId ?: throw IllegalArgumentException("a team event names its team")
        }
        if (!teamActive(teamId)) { finish("TEAM_INACTIVE", teamId); return@tx AwardResult.Refused(Refusal.TEAM_INACTIVE, teamId) }
        if (event.source == XpSource.PVP_KILL && event.subject != null && s.xp.pvpSameVictimMillis > 0) {
            val repeat = db.queryFirst(
                "SELECT 1 AS x FROM lifesteal_team_xp_events WHERE team_id = ? AND source = ? AND subject = ? AND status IN ('AWARDED', 'CAPPED') " +
                    "AND created_at > ? AND dedupe_key <> ?",
                teamId, XpSource.PVP_KILL.id, event.subject, event.now - s.xp.pvpSameVictimMillis, event.dedupeKey
            ) { it.getInt("x") }
            if (repeat != null) { finish("REPEAT_VICTIM", teamId); return@tx AwardResult.Refused(Refusal.REPEAT_VICTIM, teamId) }
        }

        // Daily caps apply to the base XP; the progression bonus rides on what was allowed.
        val sourceBucket = "source:${event.source.id}"
        val memberBucket = event.member?.let { "member:$it" }
        var allowed = event.baseXp.coerceAtLeast(0)
        allowed = minOf(allowed, (s.xp.dailyCaps[event.source] ?: 0) - daily(teamId, day, sourceBucket))
        allowed = minOf(allowed, s.xp.teamDailyCap - daily(teamId, day, "team"))
        if (memberBucket != null) allowed = minOf(allowed, s.xp.memberDailyCap - daily(teamId, day, memberBucket))
        allowed = allowed.coerceAtLeast(0)
        val capped = allowed < event.baseXp
        val bonusPercent = TeamPerks.of(tiers(teamId), s).xpBonusPercent
        val awarded = allowed + allowed * bonusPercent / 100
        if (allowed > 0) {
            addDaily(teamId, day, sourceBucket, allowed)
            addDaily(teamId, day, "team", allowed)
            if (memberBucket != null) addDaily(teamId, day, memberBucket, allowed)
        }
        val before = totalXp(teamId)
        if (awarded > 0) addXp(teamId, event.seasonId, awarded, event.now)

        // Objectives count every eligible event, even once the XP cap is reached.
        val cycle = TeamProgressCalendar.cycle(s.objectives.cycle, event.now, s.xp.timezone, event.seasonId)
        val counts = event.counts.toMutableMap()
        if (event.member != null && awarded > 0) counts[ObjectiveType.UNIQUE_CONTRIBUTORS] = 1
        val completed = mutableListOf<CompletedObjective>()
        for (objective in s.objectives.list) {
            val amount = counts[objective.type] ?: continue
            if (amount <= 0) continue
            val subject = when (objective.type) {
                ObjectiveType.UNIQUE_VICTIMS -> event.subject ?: continue
                ObjectiveType.UNIQUE_CONTRIBUTORS -> event.member?.toString() ?: continue
                else -> null
            }
            advance(teamId, objective, cycle, if (subject != null) 1 else amount, subject, event.now, bonusPercent, event.seasonId, day)?.let { completed += it }
        }
        finish(if (allowed > 0) "AWARDED" else "CAPPED", teamId, awarded)
        AwardResult.Awarded(teamId, allowed, awarded, capped, before, totalXp(teamId), completed)
    }

    /** Advances one objective; returns the completion (and awards its XP) the one time it reaches the target in this cycle. */
    private fun advance(
        teamId: String, objective: TeamProgressionSettings.Objective, cycle: String, amount: Long, subject: String?,
        now: Long, bonusPercent: Int, seasonId: String, day: String,
    ): CompletedObjective? {
        if (subject != null) {
            val fresh = db.executeUpdate(
                "INSERT OR IGNORE INTO lifesteal_team_objective_subjects (team_id, objective_id, cycle, subject) VALUES (?, ?, ?, ?)",
                teamId, objective.id, cycle, subject
            )
            if (fresh != 1) return null
        }
        db.execute(
            "INSERT OR IGNORE INTO lifesteal_team_objective_progress (team_id, objective_id, cycle, progress) VALUES (?, ?, ?, 0)",
            teamId, objective.id, cycle
        )
        val updated = db.executeUpdate(
            "UPDATE lifesteal_team_objective_progress SET progress = MIN(?, progress + ?) WHERE team_id = ? AND objective_id = ? AND cycle = ? AND completed_at IS NULL",
            objective.target, amount, teamId, objective.id, cycle
        )
        if (updated != 1) return null
        val done = db.executeUpdate(
            "UPDATE lifesteal_team_objective_progress SET completed_at = ? WHERE team_id = ? AND objective_id = ? AND cycle = ? AND completed_at IS NULL AND progress >= ?",
            now, teamId, objective.id, cycle, objective.target
        )
        if (done != 1) return null
        val xp = objective.xp + objective.xp * bonusPercent / 100
        // Its own event row (UNIQUE key per team/objective/cycle): completes and awards once.
        val inserted = db.executeUpdate(
            "INSERT OR IGNORE INTO lifesteal_team_xp_events (dedupe_key, team_id, member, source, subject, base_xp, awarded_xp, status, season_id, day, created_at) " +
                "VALUES (?, ?, NULL, ?, ?, ?, ?, 'AWARDED', ?, ?, ?)",
            "objective:$teamId:${objective.id}:$cycle", teamId, XpSource.OBJECTIVE.id, objective.id, objective.xp, xp, seasonId, day, now
        )
        if (inserted != 1) return null
        if (xp > 0) addXp(teamId, seasonId, xp, now)
        audit(teamId, "OBJECTIVE_COMPLETED", null, null, "objective=${objective.id} cycle=$cycle xp=$xp", now)
        return CompletedObjective(objective, xp)
    }

    private fun addXp(teamId: String, seasonId: String, xp: Long, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_team_progress (team_id, total_xp, version, created_at, updated_at) VALUES (?, ?, 1, ?, ?) " +
                "ON CONFLICT(team_id) DO UPDATE SET total_xp = total_xp + excluded.total_xp, version = version + 1, updated_at = excluded.updated_at",
            teamId, xp, now, now
        )
        db.execute(
            "INSERT INTO lifesteal_team_season_xp (team_id, season_id, xp) VALUES (?, ?, ?) ON CONFLICT(team_id, season_id) DO UPDATE SET xp = xp + excluded.xp",
            teamId, seasonId, xp
        )
    }

    private fun daily(teamId: String, day: String, bucket: String): Long =
        db.queryFirst("SELECT amount FROM lifesteal_team_xp_daily WHERE team_id = ? AND day = ? AND bucket = ?", teamId, day, bucket) { it.getLong("amount") } ?: 0

    private fun addDaily(teamId: String, day: String, bucket: String, amount: Long) {
        db.execute(
            "INSERT INTO lifesteal_team_xp_daily (team_id, day, bucket, amount) VALUES (?, ?, ?, ?) ON CONFLICT(team_id, day, bucket) DO UPDATE SET amount = amount + excluded.amount",
            teamId, day, bucket, amount
        )
    }

    /** Deletes daily cap counters older than [keepFromDay] (ISO date strings sort by date). */
    fun pruneDaily(keepFromDay: String) {
        db.execute("DELETE FROM lifesteal_team_xp_daily WHERE day < ?", keepFromDay)
    }

    /**
     * Deletes old event rows that awarded nothing (no team, too new, capped, repeat victim). Rows that
     * awarded XP are history (contributions, activity) and are never deleted.
     */
    fun pruneUnawarded(before: Long) {
        db.execute("DELETE FROM lifesteal_team_xp_events WHERE awarded_xp = 0 AND status <> 'PENDING' AND created_at < ?", before)
    }

    // ── Team bank ───────────────────────────────────────────

    data class DepositRequest(
        val reference: String,
        val member: UUID,
        val memberName: String,
        val cents: Long,
        val now: Long,
    )

    sealed interface DepositResult {
        data class Deposited(val teamId: String, val cents: Long, val balanceAfter: Long) : DepositResult
        data object Duplicate : DepositResult
        data object NotInTeam : DepositResult
        data object TeamInactive : DepositResult
        data class BelowMinimum(val minimumCents: Long) : DepositResult
        data class OverCapacity(val roomCents: Long, val capacityCents: Long) : DepositResult
        data object InsufficientFunds : DepositResult
    }

    /**
     * A member moves coins from their own balance into the team bank. Coins in the bank can only be
     * spent on upgrades (there is no withdrawal), so no team action can ever create coins.
     */
    fun deposit(req: DepositRequest, s: TeamProgressionSettings): DepositResult = tx {
        if (ledgerExists(req.reference)) return@tx DepositResult.Duplicate
        if (req.cents < s.bank.minimumDepositCents) return@tx DepositResult.BelowMinimum(s.bank.minimumDepositCents)
        val teamId = memberTeam(req.member) ?: return@tx DepositResult.NotInTeam
        if (!teamActive(teamId)) return@tx DepositResult.TeamInactive
        ensureBank(teamId)
        val balance = bankBalance(teamId)
        val capacity = TeamPerks.of(tiers(teamId), s).bankCapacityCents
        val after = BountyMoney.add(balance, req.cents)
        if (after == null || after > capacity) return@tx DepositResult.OverCapacity((capacity - balance).coerceAtLeast(0), capacity)
        if (!wallet.withdraw(req.member, BountyMoney.toCoins(req.cents))) return@tx DepositResult.InsufficientFunds
        val updated = db.executeUpdate(
            "UPDATE lifesteal_team_bank SET balance_cents = balance_cents + ?, deposited_cents = deposited_cents + ?, version = version + 1 WHERE team_id = ? AND balance_cents = ?",
            req.cents, req.cents, teamId, balance
        )
        if (updated != 1) throw IllegalStateException("team bank $teamId changed during a deposit")
        ledger(teamId, "DEPOSIT", req.cents, after, req.member.toString(), req.memberName, req.reference, req.now)
        audit(teamId, "BANK_DEPOSIT", req.member.toString(), req.memberName, "amount=${req.cents} balance=$after", req.now)
        DepositResult.Deposited(teamId, req.cents, after)
    }

    // ── Upgrades ────────────────────────────────────────────

    data class PurchaseRequest(
        val transactionId: String,
        val buyer: UUID,
        val buyerName: String,
        /** The team the confirmation screen was about. */
        val teamId: String,
        val category: UpgradeCategory,
        /** The tier the screen showed as current; anything else means the screen is stale. */
        val expectedTier: Int,
        val now: Long,
    )

    sealed interface PurchaseResult {
        data class Purchased(val teamId: String, val category: UpgradeCategory, val tier: Int, val costCents: Long, val balanceAfter: Long) : PurchaseResult
        data object Duplicate : PurchaseResult
        data object NotInTeam : PurchaseResult
        data object TeamInactive : PurchaseResult
        data object NotLeader : PurchaseResult
        data object Unavailable : PurchaseResult
        data class Stale(val currentTier: Int) : PurchaseResult
        data object MaxTier : PurchaseResult
        data class LevelTooLow(val required: Int, val level: Int) : PurchaseResult
        data class InsufficientFunds(val costCents: Long, val balanceCents: Long) : PurchaseResult
    }

    fun purchase(req: PurchaseRequest, s: TeamProgressionSettings): PurchaseResult = tx {
        if (db.queryFirst("SELECT 1 AS x FROM lifesteal_team_upgrade_purchases WHERE transaction_id = ?", req.transactionId) { it.getInt("x") } != null) {
            return@tx PurchaseResult.Duplicate
        }
        if (memberTeam(req.buyer) != req.teamId) return@tx PurchaseResult.NotInTeam
        val leader = db.queryFirst("SELECT leader, status FROM lifesteal_teams WHERE id = ?", req.teamId) { it.getString("leader") to it.getString("status") }
            ?: return@tx PurchaseResult.TeamInactive
        if (leader.second != "ACTIVE") return@tx PurchaseResult.TeamInactive
        if (leader.first != req.buyer.toString()) return@tx PurchaseResult.NotLeader
        val category = s.category(req.category)
        if (!category.enabled || !req.category.systemAvailable) return@tx PurchaseResult.Unavailable
        val current = tiers(req.teamId)[req.category] ?: 0
        if (current != req.expectedTier) return@tx PurchaseResult.Stale(current)
        if (current >= category.maxTier) return@tx PurchaseResult.MaxTier
        val tier = category.tiers[current]
        val level = TeamLevels.levelFor(totalXp(req.teamId), s.levels)
        if (level < tier.requiredLevel) return@tx PurchaseResult.LevelTooLow(tier.requiredLevel, level)
        ensureBank(req.teamId)
        val paid = db.executeUpdate(
            "UPDATE lifesteal_team_bank SET balance_cents = balance_cents - ?, spent_cents = spent_cents + ?, version = version + 1 WHERE team_id = ? AND balance_cents >= ?",
            tier.costCents, tier.costCents, req.teamId, tier.costCents
        )
        if (paid != 1) return@tx PurchaseResult.InsufficientFunds(tier.costCents, bankBalance(req.teamId))
        val next = current + 1
        val moved = if (current == 0) {
            db.executeUpdate(
                "INSERT OR IGNORE INTO lifesteal_team_upgrades (team_id, category, tier, updated_at) VALUES (?, ?, ?, ?)",
                req.teamId, req.category.id, next, req.now
            )
        } else {
            db.executeUpdate(
                "UPDATE lifesteal_team_upgrades SET tier = ?, updated_at = ? WHERE team_id = ? AND category = ? AND tier = ?",
                next, req.now, req.teamId, req.category.id, current
            )
        }
        if (moved != 1) throw IllegalStateException("upgrade ${req.category.id} of ${req.teamId} changed during a purchase")
        db.execute(
            "INSERT INTO lifesteal_team_upgrade_purchases (transaction_id, team_id, category, from_tier, to_tier, cost_cents, buyer, buyer_name, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            req.transactionId, req.teamId, req.category.id, current, next, tier.costCents, req.buyer.toString(), req.buyerName, req.now
        )
        val after = bankBalance(req.teamId)
        ledger(req.teamId, "PURCHASE", -tier.costCents, after, req.buyer.toString(), req.buyerName, "purchase:${req.transactionId}", req.now)
        audit(req.teamId, "UPGRADE_PURCHASE", req.buyer.toString(), req.buyerName, "category=${req.category.id} tier=$current->$next cost=${tier.costCents} balance=$after", req.now)
        PurchaseResult.Purchased(req.teamId, req.category, next, tier.costCents, after)
    }

    // ── Milestones ──────────────────────────────────────────

    sealed interface ClaimResult {
        data class Claimed(val teamId: String, val level: Int, val reward: TeamProgressionSettings.Reward, val balanceAfter: Long) : ClaimResult
        data object AlreadyClaimed : ClaimResult
        data object NotInTeam : ClaimResult
        data object TeamInactive : ClaimResult
        data object NotMilestone : ClaimResult
        data class NotReached(val level: Int) : ClaimResult
    }

    /**
     * Claims the reward of milestone [level] for the claimer's **current** team, once per team ever
     * (PRIMARY KEY team + level). The coins go to the team bank, where they can only buy upgrades.
     */
    fun claimMilestone(member: UUID, memberName: String, teamId: String, level: Int, seasonId: String, now: Long, s: TeamProgressionSettings): ClaimResult = tx {
        if (memberTeam(member) != teamId) return@tx ClaimResult.NotInTeam
        if (!teamActive(teamId)) return@tx ClaimResult.TeamInactive
        if (!TeamLevels.isMilestone(level, s.milestones)) return@tx ClaimResult.NotMilestone
        val current = TeamLevels.levelFor(totalXp(teamId), s.levels)
        if (current < level) return@tx ClaimResult.NotReached(current)
        val reward = TeamLevels.rewardFor(level, s.milestones)
        val inserted = db.executeUpdate(
            "INSERT OR IGNORE INTO lifesteal_team_milestone_claims (team_id, level, claimed_by, claimed_by_name, coins_cents, title, season_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            teamId, level, member.toString(), memberName, reward.coinsCents, reward.title, seasonId, now
        )
        if (inserted != 1) return@tx ClaimResult.AlreadyClaimed
        ensureBank(teamId)
        if (reward.coinsCents > 0) {
            db.execute(
                "UPDATE lifesteal_team_bank SET balance_cents = balance_cents + ?, rewarded_cents = rewarded_cents + ?, version = version + 1 WHERE team_id = ?",
                reward.coinsCents, reward.coinsCents, teamId
            )
            ledger(teamId, "MILESTONE", reward.coinsCents, bankBalance(teamId), member.toString(), memberName, "milestone:$teamId:$level", now)
        }
        reward.title?.let {
            db.execute("INSERT OR IGNORE INTO lifesteal_team_unlocks (team_id, unlock, source, created_at) VALUES (?, ?, ?, ?)", teamId, "title:$it", "milestone:$level", now)
        }
        audit(teamId, "MILESTONE_CLAIM", member.toString(), memberName, "level=$level coins=${reward.coinsCents} title=${reward.title}", now)
        ClaimResult.Claimed(teamId, level, reward, bankBalance(teamId))
    }

    // ── Style (cosmetics) ───────────────────────────────────

    sealed interface StyleResult {
        data class Changed(val color: String?, val title: String?) : StyleResult
        data object NotInTeam : StyleResult
        data object TeamInactive : StyleResult
        data object NotLeader : StyleResult
        data object Locked : StyleResult
    }

    /** The leader picks an unlocked colour (null = default) and/or an unlocked title (null = none). */
    fun setStyle(leader: UUID, leaderName: String, teamId: String, color: String?, title: String?, now: Long, s: TeamProgressionSettings): StyleResult = tx {
        if (memberTeam(leader) != teamId) return@tx StyleResult.NotInTeam
        val row = db.queryFirst("SELECT leader, status FROM lifesteal_teams WHERE id = ?", teamId) { it.getString("leader") to it.getString("status") }
        if (row == null || row.second != "ACTIVE") return@tx StyleResult.TeamInactive
        if (row.first != leader.toString()) return@tx StyleResult.NotLeader
        val perks = TeamPerks.of(tiers(teamId), s)
        if (color != null && color !in s.cosmetics.colors.take(perks.colorCount)) return@tx StyleResult.Locked
        if (title != null && db.queryFirst("SELECT 1 AS x FROM lifesteal_team_unlocks WHERE team_id = ? AND unlock = ?", teamId, "title:$title") { it.getInt("x") } == null) {
            return@tx StyleResult.Locked
        }
        db.execute(
            "INSERT INTO lifesteal_team_style (team_id, color, title, updated_at) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT(team_id) DO UPDATE SET color = excluded.color, title = excluded.title, updated_at = excluded.updated_at",
            teamId, color, title, now
        )
        audit(teamId, "STYLE", leader.toString(), leaderName, "color=$color title=$title", now)
        StyleResult.Changed(color, title)
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun <T> tx(block: () -> T): T {
        var result: Result<T>? = null
        db.transaction { result = Result.success(block()) }
        return result!!.getOrThrow()
    }

    private fun teamActive(teamId: String): Boolean =
        db.queryFirst("SELECT status FROM lifesteal_teams WHERE id = ?", teamId) { it.getString("status") } == "ACTIVE"

    private fun memberTeam(uuid: UUID): String? =
        db.queryFirst("SELECT team_id FROM lifesteal_team_members WHERE uuid = ?", uuid.toString()) { it.getString("team_id") }

    private fun ensureBank(teamId: String) {
        db.execute("INSERT OR IGNORE INTO lifesteal_team_bank (team_id, balance_cents) VALUES (?, 0)", teamId)
    }

    private fun ledgerExists(reference: String): Boolean =
        db.queryFirst("SELECT 1 AS x FROM lifesteal_team_bank_ledger WHERE reference = ?", reference) { it.getInt("x") } != null

    private fun ledger(teamId: String, kind: String, cents: Long, after: Long, actor: String?, actorName: String?, reference: String, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_team_bank_ledger (team_id, kind, amount_cents, balance_after, actor, actor_name, reference, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            teamId, kind, cents, after, actor, actorName, reference, now
        )
    }

    fun audit(teamId: String, action: String, actor: String?, actorName: String?, details: String?, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_team_progress_audit (team_id, action, actor, actor_name, details, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            teamId, action, actor, actorName, details, now
        )
    }
}
