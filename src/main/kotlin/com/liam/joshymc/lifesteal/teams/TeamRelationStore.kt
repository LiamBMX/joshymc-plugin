package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Lifesteal team alliances and rivalries (issue #1405), in the Lifesteal server's own data.db next
 * to the team tables of [LifestealTeamStore]. Bukkit-free (only [SqliteDatabase]), so
 * `scripts/LifestealAllianceCheck.kt` runs it against real SQLite. Every relationship is keyed by
 * the stable team id (`lifesteal_teams.id`), never by a name; timestamps are UTC epoch millis.
 *
 * - `lifesteal_team_alliances`: every alliance ever formed (ACTIVE, DISSOLVED by a leader, ENDED_DISBANDED
 *   when a team was disbanded). Rows are kept as history.
 * - `lifesteal_team_alliance_slots`: one row per team in an ACTIVE alliance. `team_id` is the
 *   PRIMARY KEY, so a team can never be in two alliances, whatever races the code above loses.
 * - `lifesteal_team_alliance_requests`: requests with a unique id, source, target, sender, creation
 *   and expiry. One PENDING request per (source, target) (partial UNIQUE index); resolved rows
 *   keep their final status (ACCEPTED, DECLINED, CANCELLED, EXPIRED, INVALIDATED).
 * - `lifesteal_team_alliance_cooldowns`: per team, until when it can't form an alliance (only a
 *   voluntary dissolution writes it; never a disband).
 * - `lifesteal_team_rivalries`: directional declarations (declarer → target). At most one ACTIVE per
 *   declarer (partial UNIQUE index); withdrawn/removed rows are kept.
 *
 * Triggers refuse a self-alliance, a self-rivalry, an alliance between teams with a rivalry in either
 * direction, and a rivalry against an ally, so the two relationships can never coexist.
 *
 * Every change is one [SqliteDatabase.transaction] that re-reads everything it depends on (team
 * existence, the actor's current leadership, slots, cooldowns, rivalries, the request) and moves rows
 * on with conditional updates; concurrent changes run one after the other against fresh data. Every
 * change is also written to the existing `lifesteal_team_audit` table (one row per affected team).
 */
class TeamRelationStore(private val db: SqliteDatabase) {

    data class Alliance(val id: String, val teamA: String, val teamB: String, val createdAt: Long) {
        fun involves(team: String): Boolean = team == teamA || team == teamB
        fun other(team: String): String? = when (team) { teamA -> teamB; teamB -> teamA; else -> null }
    }

    data class Request(val id: String, val source: String, val target: String, val sender: UUID, val createdAt: Long, val expiresAt: Long) {
        fun expired(now: Long): Boolean = now >= expiresAt
        fun involves(team: String): Boolean = team == source || team == target
    }

    data class Rivalry(val id: String, val declarer: String, val target: String, val declaredBy: UUID, val declaredAt: Long)

    /** Everything that's in force right now; the manager keeps one of these as its read cache. */
    data class Snapshot(
        val alliances: List<Alliance> = emptyList(),
        val requests: List<Request> = emptyList(),
        val rivalries: List<Rivalry> = emptyList(),
        val cooldowns: Map<String, Long> = emptyMap(),
    ) {
        private val allianceByTeam: Map<String, Alliance> = buildMap { for (a in alliances) { put(a.teamA, a); put(a.teamB, a) } }
        private val rivalByDeclarer: Map<String, Rivalry> = rivalries.associateBy { it.declarer }

        fun allianceOf(team: String): Alliance? = allianceByTeam[team]
        fun allyOf(team: String): String? = allianceByTeam[team]?.other(team)
        fun allied(a: String, b: String): Boolean = a != b && allianceByTeam[a]?.other(a) == b
        /** The rivalry [team] declared, or null. */
        fun rivalOf(team: String): Rivalry? = rivalByDeclarer[team]
        /** Rivalries other teams declared against [team] (one-sided unless [team] declared back). */
        fun rivalsAgainst(team: String): List<Rivalry> = rivalries.filter { it.target == team }.sortedBy { it.declaredAt }
        fun rivalryBetween(a: String, b: String): Rivalry? = rivalByDeclarer[a]?.takeIf { it.target == b } ?: rivalByDeclarer[b]?.takeIf { it.target == a }
        fun cooldownUntil(team: String, now: Long): Long? = cooldowns[team]?.takeIf { it > now }
        fun incoming(team: String, now: Long): List<Request> = requests.filter { it.target == team && !it.expired(now) }.sortedBy { it.createdAt }
        fun outgoing(team: String, now: Long): List<Request> = requests.filter { it.source == team && !it.expired(now) }.sortedBy { it.createdAt }
        fun pending(source: String, target: String, now: Long): Request? = requests.firstOrNull { it.source == source && it.target == target && !it.expired(now) }
        fun anyExpired(now: Long): Boolean = requests.any { it.expired(now) }
    }

    /** Why a change was refused. [until] is set for the time-limited refusals. */
    enum class Refusal(val message: String) {
        NOT_IN_TEAM("You're not in a team."),
        NOT_LEADER("Only your team's current leader can manage alliances and rivalries."),
        TEAM_GONE("That team doesn't exist any more."),
        OWN_TEAM_GONE("Your team doesn't exist any more."),
        SELF_ALLIANCE("Your team can't ally with itself."),
        SELF_RIVALRY("Your team can't be its own rival."),
        ALREADY_ALLIED("Your teams are already allied."),
        OWN_ALLIANCE("Your team already has an alliance. End it first with /team ally leave."),
        TARGET_ALLIANCE("That team already has an alliance."),
        OWN_COOLDOWN("Your team recently ended an alliance and can form another one in {time}."),
        TARGET_COOLDOWN("That team recently ended an alliance and can form another one in {time}."),
        RIVALRY("Your teams have a rivalry declared (in either direction). It must be withdrawn before you can ally."),
        ALREADY_REQUESTED("Your team already sent that team an alliance request."),
        REVERSE_PENDING("That team already sent your team an alliance request. Accept it with /team ally accept {team}."),
        TOO_MANY_OUTGOING("Your team already has $MAX_OUTGOING_REQUESTS pending alliance requests. Cancel one first."),
        RECENTLY_REFUSED("Your last request to that team was declined or cancelled. You can send a new one in {time}."),
        NO_REQUEST("There's no pending alliance request from that team."),
        NO_OUTGOING_REQUEST("Your team has no pending alliance request to that team."),
        EXPIRED("That alliance request has expired."),
        NO_ALLIANCE("Your team has no alliance."),
        ALREADY_RIVAL("Your team already declared that team its rival."),
        OWN_RIVAL("Your team already declared a rival. Withdraw it first with /team rival remove."),
        RIVAL_IS_ALLY("You can't declare your ally a rival. End the alliance first (/team ally leave)."),
        NO_RIVAL("Your team hasn't declared a rival."),
    }

    sealed interface Result {
        data class Refused(val refusal: Refusal, val until: Long? = null) : Result
        data class RequestSent(val request: Request) : Result
        /** [invalidated]: other pending requests involving either team, cancelled because of the alliance. */
        data class Allied(val alliance: Alliance, val request: Request, val invalidated: List<Request>) : Result
        data class Declined(val request: Request) : Result
        data class Cancelled(val request: Request) : Result
        /** [byTeam] = the team whose leader ended it; both teams wait until [cooldownUntil]. */
        data class Dissolved(val alliance: Alliance, val byTeam: String, val cooldownUntil: Long) : Result
        /** [invalidated]: pending requests between the two teams, cancelled by the rivalry. */
        data class RivalDeclared(val rivalry: Rivalry, val invalidated: List<Request>) : Result
        data class RivalWithdrawn(val rivalry: Rivalry) : Result
    }

    /** What disbanding a team removed (no cooldown is ever written for the surviving ally). */
    data class Cleanup(
        val teamId: String,
        val alliance: Alliance?,
        val outgoingRival: Rivalry?,
        val incomingRivals: List<Rivalry>,
        val requests: List<Request>,
    ) {
        val empty: Boolean get() = alliance == null && outgoingRival == null && incomingRivals.isEmpty() && requests.isEmpty()
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_alliances (
                id TEXT PRIMARY KEY,
                team_a TEXT NOT NULL,
                team_b TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                created_at INTEGER NOT NULL,
                request_id TEXT,
                ended_at INTEGER,
                ended_by TEXT,
                end_reason TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_alliances_status ON lifesteal_team_alliances (status)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_alliance_slots (
                team_id TEXT PRIMARY KEY,
                alliance_id TEXT NOT NULL REFERENCES lifesteal_team_alliances (id)
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_alliance_requests (
                id TEXT PRIMARY KEY,
                source_team TEXT NOT NULL,
                target_team TEXT NOT NULL,
                sender TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                resolved_at INTEGER,
                resolved_by TEXT
            )
            """.trimIndent()
        )
        db.createTable(
            "CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_alliance_requests_pending ON lifesteal_team_alliance_requests (source_team, target_team) WHERE status = 'PENDING'"
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_alliance_requests_status ON lifesteal_team_alliance_requests (status, expires_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_alliance_cooldowns (
                team_id TEXT PRIMARY KEY,
                until INTEGER NOT NULL,
                alliance_id TEXT,
                started_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_rivalries (
                id TEXT PRIMARY KEY,
                declarer_team TEXT NOT NULL,
                target_team TEXT NOT NULL,
                declared_by TEXT NOT NULL,
                declared_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                ended_at INTEGER,
                ended_by TEXT,
                end_reason TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_rivalries_active ON lifesteal_team_rivalries (declarer_team) WHERE status = 'ACTIVE'")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_rivalries_target ON lifesteal_team_rivalries (target_team, status)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_alliance_insert BEFORE INSERT ON lifesteal_team_alliances WHEN NEW.status = 'ACTIVE'
            BEGIN
                SELECT RAISE(ABORT, 'a lifesteal team cannot ally with itself') WHERE NEW.team_a = NEW.team_b;
                SELECT RAISE(ABORT, 'lifesteal teams with a rivalry cannot ally')
                    WHERE EXISTS (SELECT 1 FROM lifesteal_team_rivalries WHERE status = 'ACTIVE' AND (
                        (declarer_team = NEW.team_a AND target_team = NEW.team_b) OR (declarer_team = NEW.team_b AND target_team = NEW.team_a)));
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_rivalry_insert BEFORE INSERT ON lifesteal_team_rivalries WHEN NEW.status = 'ACTIVE'
            BEGIN
                SELECT RAISE(ABORT, 'a lifesteal team cannot be its own rival') WHERE NEW.declarer_team = NEW.target_team;
                SELECT RAISE(ABORT, 'allied lifesteal teams cannot be rivals')
                    WHERE EXISTS (SELECT 1 FROM lifesteal_team_alliances WHERE status = 'ACTIVE' AND (
                        (team_a = NEW.declarer_team AND team_b = NEW.target_team) OR (team_a = NEW.target_team AND team_b = NEW.declarer_team)));
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_alliance_slot_insert BEFORE INSERT ON lifesteal_team_alliance_slots
            BEGIN
                SELECT RAISE(ABORT, 'lifesteal alliance slot for an inactive alliance')
                    WHERE NOT EXISTS (SELECT 1 FROM lifesteal_team_alliances WHERE id = NEW.alliance_id AND status = 'ACTIVE'
                        AND (team_a = NEW.team_id OR team_b = NEW.team_id));
            END
            """.trimIndent()
        )
    }

    // ── Reads ───────────────────────────────────────────────

    fun snapshot(): Snapshot = Snapshot(
        alliances = db.query("SELECT id, team_a, team_b, created_at FROM lifesteal_team_alliances WHERE status = 'ACTIVE' ORDER BY created_at") { allianceRow(it) },
        requests = db.query("SELECT * FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' ORDER BY created_at") { requestRow(it) }.filterNotNull(),
        rivalries = db.query("SELECT * FROM lifesteal_team_rivalries WHERE status = 'ACTIVE' ORDER BY declared_at") { rivalryRow(it) }.filterNotNull(),
        cooldowns = db.query("SELECT team_id, until FROM lifesteal_team_alliance_cooldowns") { it.getString("team_id") to it.getLong("until") }.toMap(),
    )

    fun requestStatus(id: String): String? =
        db.queryFirst("SELECT status FROM lifesteal_team_alliance_requests WHERE id = ?", id) { it.getString("status") }

    fun allianceStatus(id: String): String? =
        db.queryFirst("SELECT status FROM lifesteal_team_alliances WHERE id = ?", id) { it.getString("status") }

    fun slotCount(): Int = db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_alliance_slots") { it.getInt("n") } ?: 0

    // ── Alliance requests ───────────────────────────────────

    /**
     * [actor] (must lead their team right now) asks [targetTeam] for an alliance. Checked here, in
     * the transaction: both teams active, not the same team, neither in an alliance, neither on
     * cooldown, no rivalry either way, no duplicate pending request, no reverse request (accept that
     * one instead), at most [MAX_OUTGOING_REQUESTS] pending outgoing, and not within
     * [RESEND_AFTER_REFUSAL_MILLIS] of the last declined/cancelled request to the same team.
     * A request never reserves anything: every check runs again on acceptance.
     */
    fun requestAlliance(actor: UUID, actorName: String, targetTeam: String, now: Long, ttlMillis: Long, id: String = UUID.randomUUID().toString()): Result = tx {
        val source = ledTeam(actor) ?: return@tx refusedLeader(actor)
        if (source == targetTeam) return@tx Result.Refused(Refusal.SELF_ALLIANCE)
        if (!teamActive(targetTeam)) return@tx Result.Refused(Refusal.TEAM_GONE)
        eligibility(source, targetTeam, now)?.let { return@tx it }
        pendingRequest(source, targetTeam)?.let { existing ->
            if (!existing.expired(now)) return@tx Result.Refused(Refusal.ALREADY_REQUESTED)
            resolve(existing, "EXPIRED", null, now)
        }
        pendingRequest(targetTeam, source)?.let { reverse ->
            if (!reverse.expired(now)) return@tx Result.Refused(Refusal.REVERSE_PENDING)
            resolve(reverse, "EXPIRED", null, now)
        }
        val outgoing = db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_team_alliance_requests WHERE source_team = ? AND status = 'PENDING' AND expires_at > ?", source, now
        ) { it.getInt("n") } ?: 0
        if (outgoing >= MAX_OUTGOING_REQUESTS) return@tx Result.Refused(Refusal.TOO_MANY_OUTGOING)
        val lastRefused = db.queryFirst(
            "SELECT MAX(resolved_at) AS t FROM lifesteal_team_alliance_requests WHERE source_team = ? AND target_team = ? AND status IN ('DECLINED', 'CANCELLED')",
            source, targetTeam
        ) { rs -> rs.getLong("t").takeIf { !rs.wasNull() } }
        if (lastRefused != null && now < lastRefused + RESEND_AFTER_REFUSAL_MILLIS) {
            return@tx Result.Refused(Refusal.RECENTLY_REFUSED, lastRefused + RESEND_AFTER_REFUSAL_MILLIS)
        }
        val request = Request(id, source, targetTeam, actor, now, now + ttlMillis)
        db.execute(
            "INSERT INTO lifesteal_team_alliance_requests (id, source_team, target_team, sender, created_at, expires_at, status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
            request.id, source, targetTeam, actor.toString(), now, request.expiresAt
        )
        audit(source, "ALLY_REQUEST", actor, actorName, targetTeam, "request=${request.id} expires_at=${request.expiresAt}", now)
        audit(targetTeam, "ALLY_REQUEST_RECEIVED", actor, actorName, source, "request=${request.id}", now)
        Result.RequestSent(request)
    }

    /**
     * The current leader of the target team accepts [sourceTeam]'s request. Re-validates everything
     * (both teams still exist, the actor still leads, the request is still pending and unexpired, no
     * alliance, cooldown or rivalry on either side). The alliance row and both slot rows are written
     * together; the slot PRIMARY KEY makes a second alliance for either team impossible. Other
     * pending requests involving either team are INVALIDATED in the same transaction.
     */
    fun acceptAlliance(actor: UUID, actorName: String, sourceTeam: String, now: Long, allianceId: String = UUID.randomUUID().toString()): Result = tx {
        val target = ledTeam(actor) ?: return@tx refusedLeader(actor)
        val request = pendingRequest(sourceTeam, target) ?: return@tx Result.Refused(Refusal.NO_REQUEST)
        if (request.expired(now)) {
            resolve(request, "EXPIRED", null, now)
            return@tx Result.Refused(Refusal.EXPIRED)
        }
        if (!teamActive(sourceTeam)) {
            resolve(request, "INVALIDATED", actor, now)
            return@tx Result.Refused(Refusal.TEAM_GONE)
        }
        eligibility(target, sourceTeam, now)?.let { refusal ->
            // A rivalry or alliance makes the request permanently invalid; a cooldown can't exist
            // with a pending request (both were checked on sending), but is refused all the same.
            if (refusal.refusal == Refusal.RIVALRY || refusal.refusal == Refusal.OWN_ALLIANCE || refusal.refusal == Refusal.TARGET_ALLIANCE) {
                resolve(request, "INVALIDATED", actor, now)
            }
            return@tx refusal
        }
        val (a, b) = if (sourceTeam < target) sourceTeam to target else target to sourceTeam
        db.execute(
            "INSERT INTO lifesteal_team_alliances (id, team_a, team_b, status, created_at, request_id) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
            allianceId, a, b, now, request.id
        )
        db.execute("INSERT INTO lifesteal_team_alliance_slots (team_id, alliance_id) VALUES (?, ?)", a, allianceId)
        db.execute("INSERT INTO lifesteal_team_alliance_slots (team_id, alliance_id) VALUES (?, ?)", b, allianceId)
        val updated = db.executeUpdate(
            "UPDATE lifesteal_team_alliance_requests SET status = 'ACCEPTED', resolved_at = ?, resolved_by = ? WHERE id = ? AND status = 'PENDING'",
            now, actor.toString(), request.id
        )
        if (updated != 1) throw LifestealTeamStore.StaleWriteException("alliance request ${request.id} changed while it was accepted")
        val others = db.query(
            "SELECT * FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' AND (source_team IN (?, ?) OR target_team IN (?, ?))",
            a, b, a, b
        ) { requestRow(it) }.filterNotNull()
        for (other in others) resolve(other, "INVALIDATED", actor, now)
        val alliance = Alliance(allianceId, a, b, now)
        audit(target, "ALLY_ACCEPT", actor, actorName, sourceTeam, "alliance=$allianceId request=${request.id}", now)
        audit(sourceTeam, "ALLY_FORMED", actor, actorName, target, "alliance=$allianceId request=${request.id}", now)
        Result.Allied(alliance, request, others)
    }

    /** The target team's current leader declines [sourceTeam]'s request. */
    fun declineAlliance(actor: UUID, actorName: String, sourceTeam: String, now: Long): Result = tx {
        val target = ledTeam(actor) ?: return@tx refusedLeader(actor)
        val request = pendingRequest(sourceTeam, target) ?: return@tx Result.Refused(Refusal.NO_REQUEST)
        if (request.expired(now)) {
            resolve(request, "EXPIRED", null, now)
            return@tx Result.Refused(Refusal.EXPIRED)
        }
        resolve(request, "DECLINED", actor, now)
        audit(target, "ALLY_DECLINE", actor, actorName, sourceTeam, "request=${request.id}", now)
        audit(sourceTeam, "ALLY_DECLINED", actor, actorName, target, "request=${request.id}", now)
        Result.Declined(request)
    }

    /** The source team's current leader cancels their request to [targetTeam]. */
    fun cancelAlliance(actor: UUID, actorName: String, targetTeam: String, now: Long): Result = tx {
        val source = ledTeam(actor) ?: return@tx refusedLeader(actor)
        val request = pendingRequest(source, targetTeam) ?: return@tx Result.Refused(Refusal.NO_OUTGOING_REQUEST)
        if (request.expired(now)) {
            resolve(request, "EXPIRED", null, now)
            return@tx Result.Refused(Refusal.EXPIRED)
        }
        resolve(request, "CANCELLED", actor, now)
        audit(source, "ALLY_CANCEL", actor, actorName, targetTeam, "request=${request.id}", now)
        Result.Cancelled(request)
    }

    /** Pending requests whose time is up become EXPIRED; returns them (the sweep notifies both teams). */
    fun expireRequests(now: Long): List<Request> = tx {
        val due = db.query("SELECT * FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' AND expires_at <= ?", now) { requestRow(it) }.filterNotNull()
        due.filter { request ->
            val changed = db.executeUpdate(
                "UPDATE lifesteal_team_alliance_requests SET status = 'EXPIRED', resolved_at = ? WHERE id = ? AND status = 'PENDING'", now, request.id
            ) == 1
            if (changed) {
                audit(request.source, "ALLY_REQUEST_EXPIRED", null, null, request.target, "request=${request.id}", now)
                audit(request.target, "ALLY_REQUEST_EXPIRED", null, null, request.source, "request=${request.id}", now)
            }
            changed
        }
    }

    // ── Ending alliances ────────────────────────────────────

    /**
     * The current leader of either allied team ends the alliance (no approval from the other side).
     * The conditional `WHERE status = 'ACTIVE'` makes a second, concurrent dissolution find nothing,
     * so the alliance ends once and the cooldown starts once. Both teams get [cooldownMillis]; an
     * existing longer cooldown is never shortened.
     */
    fun dissolveAlliance(actor: UUID, actorName: String, now: Long, cooldownMillis: Long): Result = tx {
        val team = ledTeam(actor) ?: return@tx refusedLeader(actor)
        val alliance = activeAllianceOf(team) ?: return@tx Result.Refused(Refusal.NO_ALLIANCE)
        val updated = db.executeUpdate(
            "UPDATE lifesteal_team_alliances SET status = 'DISSOLVED', ended_at = ?, ended_by = ?, end_reason = 'DISSOLVED' WHERE id = ? AND status = 'ACTIVE'",
            now, actor.toString(), alliance.id
        )
        if (updated != 1) return@tx Result.Refused(Refusal.NO_ALLIANCE)
        db.execute("DELETE FROM lifesteal_team_alliance_slots WHERE alliance_id = ?", alliance.id)
        val until = now + cooldownMillis
        for (side in listOf(alliance.teamA, alliance.teamB)) {
            if (cooldownMillis > 0) {
                db.execute(
                    "INSERT INTO lifesteal_team_alliance_cooldowns (team_id, until, alliance_id, started_at) VALUES (?, ?, ?, ?) " +
                        "ON CONFLICT(team_id) DO UPDATE SET until = MAX(until, excluded.until), alliance_id = excluded.alliance_id, started_at = excluded.started_at",
                    side, until, alliance.id, now
                )
            }
            audit(side, "ALLY_DISSOLVE", actor, actorName, alliance.other(side), "alliance=${alliance.id} by_team=$team cooldown_until=$until", now)
        }
        Result.Dissolved(alliance, team, until)
    }

    // ── Rivalries ───────────────────────────────────────────

    /**
     * [actor] (current leader) declares [targetTeam] their team's rival. No approval. Refused for
     * the own team, a gone team, an ally (end the alliance first; never dissolved automatically), a
     * duplicate, or when the team already declared a rival. Alliance cooldowns don't apply. Pending
     * alliance requests between the two teams are INVALIDATED in the same transaction.
     */
    fun declareRival(actor: UUID, actorName: String, targetTeam: String, now: Long, id: String = UUID.randomUUID().toString()): Result = tx {
        val team = ledTeam(actor) ?: return@tx refusedLeader(actor)
        if (team == targetTeam) return@tx Result.Refused(Refusal.SELF_RIVALRY)
        if (!teamActive(targetTeam)) return@tx Result.Refused(Refusal.TEAM_GONE)
        if (activeAllianceOf(team)?.other(team) == targetTeam) return@tx Result.Refused(Refusal.RIVAL_IS_ALLY)
        activeRivalOf(team)?.let { existing ->
            return@tx Result.Refused(if (existing.target == targetTeam) Refusal.ALREADY_RIVAL else Refusal.OWN_RIVAL)
        }
        db.execute(
            "INSERT INTO lifesteal_team_rivalries (id, declarer_team, target_team, declared_by, declared_at, status) VALUES (?, ?, ?, ?, ?, 'ACTIVE')",
            id, team, targetTeam, actor.toString(), now
        )
        val between = db.query(
            "SELECT * FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' AND ((source_team = ? AND target_team = ?) OR (source_team = ? AND target_team = ?))",
            team, targetTeam, targetTeam, team
        ) { requestRow(it) }.filterNotNull()
        for (request in between) resolve(request, "INVALIDATED", actor, now)
        audit(team, "RIVAL_DECLARE", actor, actorName, targetTeam, "rivalry=$id", now)
        audit(targetTeam, "RIVAL_DECLARED_AGAINST", actor, actorName, team, "rivalry=$id", now)
        Result.RivalDeclared(Rivalry(id, team, targetTeam, actor, now), between)
    }

    /** Only the declaring team's current leader withdraws its rivalry (the target team never can). */
    fun withdrawRival(actor: UUID, actorName: String, now: Long): Result = tx {
        val team = ledTeam(actor) ?: return@tx refusedLeader(actor)
        val rivalry = activeRivalOf(team) ?: return@tx Result.Refused(Refusal.NO_RIVAL)
        val updated = db.executeUpdate(
            "UPDATE lifesteal_team_rivalries SET status = 'WITHDRAWN', ended_at = ?, ended_by = ?, end_reason = 'WITHDRAWN' WHERE id = ? AND status = 'ACTIVE'",
            now, actor.toString(), rivalry.id
        )
        if (updated != 1) return@tx Result.Refused(Refusal.NO_RIVAL)
        audit(team, "RIVAL_WITHDRAW", actor, actorName, rivalry.target, "rivalry=${rivalry.id}", now)
        audit(rivalry.target, "RIVAL_WITHDRAWN_AGAINST", actor, actorName, team, "rivalry=${rivalry.id}", now)
        Result.RivalWithdrawn(rivalry)
    }

    // ── Disbanding and repair ───────────────────────────────

    /**
     * Removes every relationship of [teamId], which is being disbanded. Called by
     * [LifestealTeamStore.disband] **inside its transaction**, so the team and its relationships end
     * together or not at all. The alliance ends as ENDED_DISBANDED with **no** cooldown for anyone;
     * the disbanded team's own cooldown row is deleted; its outgoing and all incoming rivalries are
     * REMOVED; pending requests involving it are INVALIDATED. A second call finds nothing.
     */
    fun cleanupDisbanded(teamId: String, actorId: String?, now: Long): Cleanup = tx {
        val alliance = activeAllianceOf(teamId)
        if (alliance != null) {
            db.executeUpdate(
                "UPDATE lifesteal_team_alliances SET status = 'ENDED_DISBANDED', ended_at = ?, ended_by = ?, end_reason = 'TEAM_DISBANDED' WHERE id = ? AND status = 'ACTIVE'",
                now, actorId, alliance.id
            )
            db.execute("DELETE FROM lifesteal_team_alliance_slots WHERE alliance_id = ?", alliance.id)
            alliance.other(teamId)?.let { audit(it, "ALLY_ENDED_DISBANDED", null, null, teamId, "alliance=${alliance.id}", now) }
        }
        db.execute("DELETE FROM lifesteal_team_alliance_slots WHERE team_id = ?", teamId)
        db.execute("DELETE FROM lifesteal_team_alliance_cooldowns WHERE team_id = ?", teamId)
        val outgoing = activeRivalOf(teamId)
        val incoming = db.query("SELECT * FROM lifesteal_team_rivalries WHERE status = 'ACTIVE' AND target_team = ?", teamId) { rivalryRow(it) }.filterNotNull()
        db.execute(
            "UPDATE lifesteal_team_rivalries SET status = 'REMOVED', ended_at = ?, ended_by = ?, end_reason = 'TEAM_DISBANDED' " +
                "WHERE status = 'ACTIVE' AND (declarer_team = ? OR target_team = ?)",
            now, actorId, teamId, teamId
        )
        for (rivalry in incoming) audit(rivalry.declarer, "RIVAL_REMOVED_DISBANDED", null, null, teamId, "rivalry=${rivalry.id}", now)
        outgoing?.let { audit(it.target, "RIVAL_REMOVED_DISBANDED", null, null, teamId, "rivalry=${it.id}", now) }
        val requests = db.query(
            "SELECT * FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' AND (source_team = ? OR target_team = ?)", teamId, teamId
        ) { requestRow(it) }.filterNotNull()
        for (request in requests) resolve(request, "INVALIDATED", null, now)
        val cleanup = Cleanup(teamId, alliance, outgoing, incoming, requests)
        if (!cleanup.empty) audit(teamId, "RELATIONS_REMOVED_DISBANDED", null, null, null, describe(cleanup), now)
        cleanup
    }

    /**
     * Startup repair: any relationship that still references a team that isn't ACTIVE (a crash
     * between writes on an older build, a manual database edit) is cleaned up exactly like a disband.
     * Returns what was removed.
     */
    fun repairOrphans(now: Long): List<Cleanup> = tx {
        val referenced = LinkedHashSet<String>()
        db.query("SELECT team_a, team_b FROM lifesteal_team_alliances WHERE status = 'ACTIVE'") { referenced += it.getString("team_a"); referenced += it.getString("team_b") }
        db.query("SELECT team_id FROM lifesteal_team_alliance_slots") { referenced += it.getString("team_id") }
        db.query("SELECT declarer_team, target_team FROM lifesteal_team_rivalries WHERE status = 'ACTIVE'") { referenced += it.getString("declarer_team"); referenced += it.getString("target_team") }
        db.query("SELECT source_team, target_team FROM lifesteal_team_alliance_requests WHERE status = 'PENDING'") { referenced += it.getString("source_team"); referenced += it.getString("target_team") }
        referenced.filterNot { teamActive(it) }.map { cleanupDisbanded(it, "repair", now) }.filterNot { it.empty }
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun <T> tx(block: () -> T): T {
        var result: kotlin.Result<T>? = null
        db.transaction { result = kotlin.Result.success(block()) }
        return result!!.getOrThrow()
    }

    /** Shared alliance eligibility of [own] (the acting team) with [other], or null when eligible. */
    private fun eligibility(own: String, other: String, now: Long): Result.Refused? {
        val ownAlliance = activeAllianceOf(own)
        if (ownAlliance != null) return Result.Refused(if (ownAlliance.other(own) == other) Refusal.ALREADY_ALLIED else Refusal.OWN_ALLIANCE)
        if (activeAllianceOf(other) != null) return Result.Refused(Refusal.TARGET_ALLIANCE)
        cooldown(own, now)?.let { return Result.Refused(Refusal.OWN_COOLDOWN, it) }
        cooldown(other, now)?.let { return Result.Refused(Refusal.TARGET_COOLDOWN, it) }
        if (rivalryExists(own, other)) return Result.Refused(Refusal.RIVALRY)
        return null
    }

    /** The team [actor] leads right now (membership row + `leader` column, both re-read), or null. */
    private fun ledTeam(actor: UUID): String? {
        val teamId = db.queryFirst("SELECT team_id FROM lifesteal_team_members WHERE uuid = ?", actor.toString()) { it.getString("team_id") } ?: return null
        val leader = db.queryFirst("SELECT leader FROM lifesteal_teams WHERE id = ? AND status = 'ACTIVE'", teamId) { it.getString("leader") } ?: return null
        return teamId.takeIf { leader == actor.toString() }
    }

    private fun refusedLeader(actor: UUID): Result.Refused {
        val inTeam = db.queryFirst("SELECT team_id FROM lifesteal_team_members WHERE uuid = ?", actor.toString()) { it.getString("team_id") } != null
        return Result.Refused(if (inTeam) Refusal.NOT_LEADER else Refusal.NOT_IN_TEAM)
    }

    private fun teamActive(id: String): Boolean =
        db.queryFirst("SELECT 1 AS x FROM lifesteal_teams WHERE id = ? AND status = 'ACTIVE'", id) { 1 } != null

    private fun activeAllianceOf(team: String): Alliance? =
        db.queryFirst(
            "SELECT id, team_a, team_b, created_at FROM lifesteal_team_alliances WHERE status = 'ACTIVE' AND (team_a = ? OR team_b = ?)", team, team
        ) { allianceRow(it) }

    private fun activeRivalOf(team: String): Rivalry? =
        db.queryFirst("SELECT * FROM lifesteal_team_rivalries WHERE status = 'ACTIVE' AND declarer_team = ?", team) { rivalryRow(it) }

    private fun rivalryExists(a: String, b: String): Boolean =
        db.queryFirst(
            "SELECT 1 AS x FROM lifesteal_team_rivalries WHERE status = 'ACTIVE' AND ((declarer_team = ? AND target_team = ?) OR (declarer_team = ? AND target_team = ?))",
            a, b, b, a
        ) { 1 } != null

    private fun cooldown(team: String, now: Long): Long? =
        db.queryFirst("SELECT until FROM lifesteal_team_alliance_cooldowns WHERE team_id = ?", team) { it.getLong("until") }?.takeIf { it > now }

    private fun pendingRequest(source: String, target: String): Request? =
        db.queryFirst(
            "SELECT * FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' AND source_team = ? AND target_team = ?", source, target
        ) { requestRow(it) }

    private fun resolve(request: Request, status: String, by: UUID?, now: Long) {
        db.executeUpdate(
            "UPDATE lifesteal_team_alliance_requests SET status = ?, resolved_at = ?, resolved_by = ? WHERE id = ? AND status = 'PENDING'",
            status, now, by?.toString(), request.id
        )
    }

    private fun describe(c: Cleanup): String = listOfNotNull(
        c.alliance?.let { "alliance=${it.id}" },
        c.outgoingRival?.let { "rivalry=${it.id}" },
        if (c.incomingRivals.isEmpty()) null else "incoming_rivalries=${c.incomingRivals.joinToString(",") { it.id }}",
        if (c.requests.isEmpty()) null else "requests=${c.requests.joinToString(",") { it.id }}",
    ).joinToString(" ")

    /** Writes into the existing team audit table (created by [LifestealTeamStore.createTables]). */
    private fun audit(teamId: String, action: String, actor: UUID?, actorName: String?, otherTeam: String?, details: String?, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_team_audit (team_id, action, actor, actor_name, target, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            teamId, action, actor?.toString(), actorName, null,
            listOfNotNull(otherTeam?.let { "team=$it" }, details).joinToString(" ").ifEmpty { null }, now
        )
    }

    private fun allianceRow(rs: ResultSet): Alliance =
        Alliance(rs.getString("id"), rs.getString("team_a"), rs.getString("team_b"), rs.getLong("created_at"))

    private fun requestRow(rs: ResultSet): Request? {
        val sender = uuidOrNull(rs.getString("sender")) ?: return null
        return Request(rs.getString("id"), rs.getString("source_team"), rs.getString("target_team"), sender, rs.getLong("created_at"), rs.getLong("expires_at"))
    }

    private fun rivalryRow(rs: ResultSet): Rivalry? {
        val by = uuidOrNull(rs.getString("declared_by")) ?: return null
        return Rivalry(rs.getString("id"), rs.getString("declarer_team"), rs.getString("target_team"), by, rs.getLong("declared_at"))
    }

    private fun uuidOrNull(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        /** Anti-spam: pending outgoing alliance requests per team. */
        const val MAX_OUTGOING_REQUESTS = 3
        /** Anti-spam: wait after a declined or cancelled request before asking the same team again. */
        const val RESEND_AFTER_REFUSAL_MILLIS = 10 * 60_000L
    }
}
