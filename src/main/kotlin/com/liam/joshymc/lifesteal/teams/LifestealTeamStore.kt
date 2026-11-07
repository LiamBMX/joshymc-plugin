package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.lifesteal.LifestealRules
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistent Lifesteal teams (issue #1346), in the Lifesteal server's own data.db. Bukkit-free:
 * talks only to [SqliteDatabase], so `scripts/LifestealTeamCheck.kt` runs it against real SQLite.
 * The Survival team tables (`teams`, `team_members`, `team_invites`, ...) are never read or written.
 *
 * - `lifesteal_teams`: one row per team ever created. `id` is a random UUID that never changes
 *   (future Ranked Team Wars / team Elo key on it, never on the name). `canonical_name` is UNIQUE
 *   among ACTIVE teams (partial index), `leader` likewise (one led team per player). A disbanded
 *   team keeps its row (status DISBANDED, who and when), so history is never deleted and its name
 *   becomes free again. `version` rises with every change to the team or its roster.
 * - `lifesteal_team_members`: the current roster. `uuid` is the PRIMARY KEY, so a player can be in
 *   one team at most. A trigger refuses an insert into a team that is not ACTIVE or already holds
 *   [LifestealRules.MAX_TEAM_MEMBERS] players, so even a bug elsewhere can't break either rule.
 * - `lifesteal_team_invites`: pending invitations, one per (team, target UUID), with expiry.
 * - `lifesteal_team_membership_history`: who was in which team from when to when, and why they left.
 * - `lifesteal_team_audit`: every change (and admin action) with the actor.
 *
 * Every change is one [SqliteDatabase.transaction] (it holds the DB lock): it reads the current
 * rows, decides, and writes; the team row is then moved on with `WHERE version = <read version>`,
 * and a row that changed in between throws [StaleWriteException], rolling the whole change back.
 * Concurrent requests (two players taking the last slot, two disbands, two transfers) therefore
 * run one after the other against fresh data, and the second one sees the first one's result.
 */
class LifestealTeamStore(
    private val db: SqliteDatabase,
    /**
     * Issue #1405: alliances and rivalries. When set, [disband] removes the team's relationships
     * inside its own transaction (its tables must exist). Null in checks that only test teams.
     */
    private val relations: TeamRelationStore? = null,
) {

    data class Member(val uuid: UUID, val joinedAt: Long)

    data class Team(
        val id: String,
        val name: String,
        val canonicalName: String,
        val leader: UUID,
        val createdAt: Long,
        val createdBy: UUID,
        val version: Long,
        /** Leader first, then by join time. */
        val members: List<Member>,
    ) {
        val size: Int get() = members.size
        fun isMember(uuid: UUID): Boolean = members.any { it.uuid == uuid }
        fun hasRoom(maxSize: Int): Boolean = size < effectiveMax(maxSize)
    }

    data class Invite(val teamId: String, val target: UUID, val inviter: UUID, val createdAt: Long, val expiresAt: Long) {
        fun expired(now: Long): Boolean = now >= expiresAt
    }

    data class AuditRow(
        val teamId: String,
        val action: String,
        val actor: String?,
        val actorName: String?,
        val target: UUID?,
        val details: String?,
        val at: Long,
    )

    data class HistoryRow(val teamId: String, val uuid: UUID, val joinedAt: Long, val leftAt: Long, val reason: String)

    /** Who asks for a change. [uuid] null = console. [admin] = staff acting through `/team admin`. */
    data class Actor(val uuid: UUID?, val name: String, val admin: Boolean = false) {
        val id: String get() = uuid?.toString() ?: "console"
    }

    /** Thrown inside a transaction to roll it back when a row changed underneath it. */
    class StaleWriteException(message: String) : RuntimeException(message)

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_teams (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                canonical_name TEXT NOT NULL,
                leader TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                created_at INTEGER NOT NULL,
                created_by TEXT NOT NULL,
                version INTEGER NOT NULL DEFAULT 0,
                disbanded_at INTEGER,
                disbanded_by TEXT
            )
            """.trimIndent()
        )
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_teams_active_name ON lifesteal_teams (canonical_name) WHERE status = 'ACTIVE'")
        db.createTable("CREATE UNIQUE INDEX IF NOT EXISTS ux_lifesteal_teams_active_leader ON lifesteal_teams (leader) WHERE status = 'ACTIVE'")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_members (
                uuid TEXT PRIMARY KEY,
                team_id TEXT NOT NULL REFERENCES lifesteal_teams (id),
                joined_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_members_team ON lifesteal_team_members (team_id)")
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_team_members_insert BEFORE INSERT ON lifesteal_team_members
            BEGIN
                SELECT RAISE(ABORT, 'lifesteal team is not active')
                    WHERE NOT EXISTS (SELECT 1 FROM lifesteal_teams WHERE id = NEW.team_id AND status = 'ACTIVE');
                SELECT RAISE(ABORT, 'lifesteal team is full')
                    WHERE (SELECT COUNT(*) FROM lifesteal_team_members WHERE team_id = NEW.team_id) >= ${LifestealRules.MAX_TEAM_MEMBERS};
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_team_members_move BEFORE UPDATE OF team_id ON lifesteal_team_members
            BEGIN
                SELECT RAISE(ABORT, 'lifesteal team members are never moved between teams');
            END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_invites (
                team_id TEXT NOT NULL REFERENCES lifesteal_teams (id),
                target TEXT NOT NULL,
                inviter TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                PRIMARY KEY (team_id, target)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_invites_target ON lifesteal_team_invites (target)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_membership_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                team_id TEXT NOT NULL,
                uuid TEXT NOT NULL,
                joined_at INTEGER NOT NULL,
                left_at INTEGER NOT NULL,
                reason TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_history_team ON lifesteal_team_membership_history (team_id, joined_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_history_uuid ON lifesteal_team_membership_history (uuid, joined_at)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_team_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                team_id TEXT NOT NULL,
                action TEXT NOT NULL,
                actor TEXT,
                actor_name TEXT,
                target TEXT,
                details TEXT,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_team_audit_team ON lifesteal_team_audit (team_id, id)")
    }

    // ── Reads ───────────────────────────────────────────────

    /** Every ACTIVE team with its roster (startup cache load). */
    fun loadActiveTeams(): List<Team> {
        val rosters = HashMap<String, MutableList<Member>>()
        db.query("SELECT uuid, team_id, joined_at FROM lifesteal_team_members ORDER BY joined_at, uuid") { rs ->
            val uuid = uuidOrNull(rs.getString("uuid")) ?: return@query
            rosters.getOrPut(rs.getString("team_id")) { mutableListOf() } += Member(uuid, rs.getLong("joined_at"))
        }
        return db.query("SELECT * FROM lifesteal_teams WHERE status = 'ACTIVE' ORDER BY canonical_name") { rs -> teamRow(rs) }
            .mapNotNull { row -> row?.let { it.withMembers(rosters[it.id].orEmpty()) } }
    }

    /** The ACTIVE team [id], or null (disbanded or unknown). */
    fun loadTeam(id: String): Team? {
        val row = db.queryFirst("SELECT * FROM lifesteal_teams WHERE id = ? AND status = 'ACTIVE'", id) { teamRow(it) } ?: return null
        return row.withMembers(members(id))
    }

    fun teamByName(name: String): Team? {
        val id = db.queryFirst(
            "SELECT id FROM lifesteal_teams WHERE canonical_name = ? AND status = 'ACTIVE'", TeamNames.canonical(name)
        ) { it.getString("id") } ?: return null
        return loadTeam(id)
    }

    fun teamOf(uuid: UUID): Team? {
        val id = db.queryFirst("SELECT team_id FROM lifesteal_team_members WHERE uuid = ?", uuid.toString()) { it.getString("team_id") } ?: return null
        return loadTeam(id)
    }

    /** The team's status (ACTIVE / DISBANDED), or null when no such team was ever created. */
    fun status(id: String): String? =
        db.queryFirst("SELECT status FROM lifesteal_teams WHERE id = ?", id) { it.getString("status") }

    fun invites(): List<Invite> =
        db.query("SELECT * FROM lifesteal_team_invites ORDER BY created_at") { inviteRow(it) }.filterNotNull()

    fun invitesFor(target: UUID): List<Invite> =
        db.query("SELECT * FROM lifesteal_team_invites WHERE target = ? ORDER BY created_at", target.toString()) { inviteRow(it) }.filterNotNull()

    fun audit(teamId: String): List<AuditRow> =
        db.query("SELECT * FROM lifesteal_team_audit WHERE team_id = ? ORDER BY id", teamId) { rs ->
            AuditRow(
                rs.getString("team_id"), rs.getString("action"), rs.getString("actor"), rs.getString("actor_name"),
                rs.getString("target")?.let { uuidOrNull(it) }, rs.getString("details"), rs.getLong("created_at"),
            )
        }

    fun history(teamId: String): List<HistoryRow> =
        db.query("SELECT * FROM lifesteal_team_membership_history WHERE team_id = ? ORDER BY id", teamId) { rs ->
            uuidOrNull(rs.getString("uuid"))?.let {
                HistoryRow(rs.getString("team_id"), it, rs.getLong("joined_at"), rs.getLong("left_at"), rs.getString("reason"))
            }
        }.filterNotNull()

    fun memberCount(): Int = db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_members") { it.getInt("n") } ?: 0

    // ── Create ──────────────────────────────────────────────

    sealed interface CreateResult {
        data class Created(val team: Team) : CreateResult
        data class NameTaken(val name: String) : CreateResult
        data class AlreadyInTeam(val team: Team?) : CreateResult
    }

    /** [name] must already be validated ([TeamNames.validate]); the creator becomes leader and first member. */
    fun create(id: String, name: String, leader: UUID, leaderName: String, now: Long): CreateResult = tx {
        val canonical = TeamNames.canonical(name)
        val current = memberTeamId(leader)
        when {
            current != null -> CreateResult.AlreadyInTeam(loadTeam(current))
            activeIdByName(canonical) != null -> CreateResult.NameTaken(name)
            else -> {
                db.execute(
                    "INSERT INTO lifesteal_teams (id, name, canonical_name, leader, status, created_at, created_by, version) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
                    id, name, canonical, leader.toString(), now, leader.toString()
                )
                db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, ?)", leader.toString(), id, now)
                // A creator's own pending invitations die with their teamless state.
                db.execute("DELETE FROM lifesteal_team_invites WHERE target = ?", leader.toString())
                audit(id, "CREATE", Actor(leader, leaderName), leader, "name=$name", now)
                CreateResult.Created(loadTeam(id) ?: throw StaleWriteException("team $id vanished right after it was created"))
            }
        }
    }

    // ── Invitations ─────────────────────────────────────────

    sealed interface InviteResult {
        data class Invited(val invite: Invite, val team: Team) : InviteResult
        /** An invitation from this team to this player is still pending: nothing new is sent. */
        data class AlreadyInvited(val invite: Invite, val team: Team) : InviteResult
        data object NotInTeam : InviteResult
        data class NotLeader(val team: Team) : InviteResult
        data object Self : InviteResult
        data class AlreadyMember(val team: Team) : InviteResult
        data class TargetInOtherTeam(val team: Team) : InviteResult
        data class Full(val team: Team) : InviteResult
    }

    /** Only the leader invites (the blueprint has no officers). */
    fun invite(inviter: Actor, target: UUID, maxSize: Int, now: Long, ttlMillis: Long): InviteResult = tx {
        val inviterUuid = inviter.uuid ?: throw IllegalArgumentException("console can't invite")
        val teamId = memberTeamId(inviterUuid) ?: return@tx InviteResult.NotInTeam
        val team = loadTeam(teamId) ?: return@tx InviteResult.NotInTeam
        if (team.leader != inviterUuid) return@tx InviteResult.NotLeader(team)
        if (target == inviterUuid) return@tx InviteResult.Self
        if (team.isMember(target)) return@tx InviteResult.AlreadyMember(team)
        memberTeamId(target)?.let { other -> return@tx InviteResult.TargetInOtherTeam(loadTeam(other) ?: team) }
        if (!team.hasRoom(maxSize)) return@tx InviteResult.Full(team)
        val existing = inviteRow(teamId, target)
        if (existing != null && !existing.expired(now)) return@tx InviteResult.AlreadyInvited(existing, team)
        db.execute("DELETE FROM lifesteal_team_invites WHERE team_id = ? AND target = ?", teamId, target.toString())
        val invite = Invite(teamId, target, inviterUuid, now, now + ttlMillis)
        db.execute(
            "INSERT INTO lifesteal_team_invites (team_id, target, inviter, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            teamId, target.toString(), inviterUuid.toString(), invite.createdAt, invite.expiresAt
        )
        audit(teamId, "INVITE", inviter, target, "expires_at=${invite.expiresAt}", now)
        InviteResult.Invited(invite, team)
    }

    sealed interface AcceptResult {
        data class Joined(val team: Team, val invite: Invite) : AcceptResult
        data object NoInvite : AcceptResult
        data class Expired(val invite: Invite) : AcceptResult
        data class Full(val team: Team) : AcceptResult
        data class AlreadyInTeam(val team: Team?) : AcceptResult
        /** The team was disbanded (its invitation went with it) or never existed. */
        data object TeamGone : AcceptResult
    }

    /**
     * Joins the team named [teamName] (canonical match) on an invitation for [target]. Everything
     * is checked again here, against the rows as they are now: the invitation exists and hasn't
     * expired, the team is active and has room, the player is in no team. Joining removes every
     * other invitation of the player.
     */
    fun accept(target: UUID, targetName: String, teamName: String, maxSize: Int, now: Long): AcceptResult = tx {
        val teamId = activeIdByName(TeamNames.canonical(teamName)) ?: return@tx AcceptResult.TeamGone
        val invite = inviteRow(teamId, target) ?: return@tx AcceptResult.NoInvite
        if (invite.expired(now)) {
            db.execute("DELETE FROM lifesteal_team_invites WHERE team_id = ? AND target = ?", teamId, target.toString())
            audit(teamId, "INVITE_EXPIRED", Actor(target, targetName), target, "on accept", now)
            return@tx AcceptResult.Expired(invite)
        }
        memberTeamId(target)?.let { return@tx AcceptResult.AlreadyInTeam(loadTeam(it)) }
        val team = loadTeam(teamId) ?: return@tx AcceptResult.TeamGone
        if (!team.hasRoom(maxSize)) return@tx AcceptResult.Full(team)
        bump(team)
        db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, ?)", target.toString(), teamId, now)
        db.execute("DELETE FROM lifesteal_team_invites WHERE target = ?", target.toString())
        audit(teamId, "JOIN", Actor(target, targetName), target, "invited by ${invite.inviter}", now)
        AcceptResult.Joined(loadTeam(teamId) ?: throw StaleWriteException("team $teamId vanished during a join"), invite)
    }

    sealed interface DenyResult {
        data class Denied(val invite: Invite, val team: Team?) : DenyResult
        data class Expired(val invite: Invite) : DenyResult
        data object NoInvite : DenyResult
    }

    fun deny(target: UUID, targetName: String, teamName: String, now: Long): DenyResult = tx {
        val teamId = activeIdByName(TeamNames.canonical(teamName)) ?: return@tx DenyResult.NoInvite
        val invite = inviteRow(teamId, target) ?: return@tx DenyResult.NoInvite
        db.execute("DELETE FROM lifesteal_team_invites WHERE team_id = ? AND target = ?", teamId, target.toString())
        val expired = invite.expired(now)
        audit(teamId, if (expired) "INVITE_EXPIRED" else "DENY", Actor(target, targetName), target, null, now)
        if (expired) DenyResult.Expired(invite) else DenyResult.Denied(invite, loadTeam(teamId))
    }

    /** Deletes invitations whose time is up and returns them (the expiry sweep notifies both sides). */
    fun expireInvites(now: Long): List<Invite> = tx {
        val expired = db.query("SELECT * FROM lifesteal_team_invites WHERE expires_at <= ?", now) { inviteRow(it) }.filterNotNull()
        for (invite in expired) {
            val deleted = db.executeUpdate(
                "DELETE FROM lifesteal_team_invites WHERE team_id = ? AND target = ? AND expires_at <= ?",
                invite.teamId, invite.target.toString(), now
            )
            if (deleted == 1) audit(invite.teamId, "INVITE_EXPIRED", null, invite.target, null, now)
        }
        expired
    }

    // ── Leaving and kicking ─────────────────────────────────

    sealed interface LeaveResult {
        data class Left(val team: Team) : LeaveResult
        data object NotInTeam : LeaveResult
        /** The leader must transfer leadership (or disband) first. */
        data class LeaderMustTransfer(val team: Team) : LeaveResult
    }

    fun leave(uuid: UUID, name: String, now: Long): LeaveResult = tx {
        val teamId = memberTeamId(uuid) ?: return@tx LeaveResult.NotInTeam
        val team = loadTeam(teamId) ?: return@tx LeaveResult.NotInTeam
        if (team.leader == uuid) return@tx LeaveResult.LeaderMustTransfer(team)
        removeMember(team, uuid, "LEFT", now)
        audit(teamId, "LEAVE", Actor(uuid, name), uuid, null, now)
        LeaveResult.Left(loadTeam(teamId) ?: throw StaleWriteException("team $teamId vanished during a leave"))
    }

    sealed interface KickResult {
        data class Kicked(val team: Team) : KickResult
        data object NotInTeam : KickResult
        data class NotLeader(val team: Team) : KickResult
        data object Self : KickResult
        data class NotMember(val team: Team) : KickResult
    }

    /** The leader removes [target] from their own team; works the same for offline targets (UUID only). */
    fun kick(actor: Actor, target: UUID, now: Long): KickResult = tx {
        val actorUuid = actor.uuid ?: throw IllegalArgumentException("console kicks go through an admin disband/transfer")
        val teamId = memberTeamId(actorUuid) ?: return@tx KickResult.NotInTeam
        val team = loadTeam(teamId) ?: return@tx KickResult.NotInTeam
        if (team.leader != actorUuid) return@tx KickResult.NotLeader(team)
        if (target == actorUuid) return@tx KickResult.Self
        if (!team.isMember(target)) return@tx KickResult.NotMember(team)
        removeMember(team, target, "KICKED", now)
        audit(teamId, "KICK", actor, target, null, now)
        KickResult.Kicked(loadTeam(teamId) ?: throw StaleWriteException("team $teamId vanished during a kick"))
    }

    // ── Leadership ──────────────────────────────────────────

    sealed interface TransferResult {
        data class Transferred(val team: Team, val previousLeader: UUID) : TransferResult
        data object NotInTeam : TransferResult
        data class NotLeader(val team: Team) : TransferResult
        data class NotMember(val team: Team) : TransferResult
        data class AlreadyLeader(val team: Team) : TransferResult
        data object TeamGone : TransferResult
    }

    /**
     * Makes [newLeader] (a current member) the leader of the team. A player [actor] must be the
     * leader of the team they're in ([teamId] null); an admin names the team. The old leader
     * stays a member. The one `leader` column is the only place leadership lives, so a team can
     * never have two leaders.
     */
    fun transfer(actor: Actor, teamId: String?, newLeader: UUID, now: Long): TransferResult = tx {
        val id: String
        if (actor.admin) {
            id = teamId ?: throw IllegalArgumentException("an admin transfer names the team")
        } else {
            val actorUuid = actor.uuid ?: throw IllegalArgumentException("console transfers are admin transfers")
            id = memberTeamId(actorUuid) ?: return@tx TransferResult.NotInTeam
        }
        val team = loadTeam(id) ?: return@tx if (actor.admin) TransferResult.TeamGone else TransferResult.NotInTeam
        if (!actor.admin && team.leader != actor.uuid) return@tx TransferResult.NotLeader(team)
        if (!team.isMember(newLeader)) return@tx TransferResult.NotMember(team)
        if (team.leader == newLeader) return@tx TransferResult.AlreadyLeader(team)
        val updated = db.executeUpdate(
            "UPDATE lifesteal_teams SET leader = ?, version = version + 1 WHERE id = ? AND status = 'ACTIVE' AND leader = ? AND version = ?",
            newLeader.toString(), id, team.leader.toString(), team.version
        )
        if (updated != 1) throw StaleWriteException("team $id changed during a leadership transfer")
        audit(id, if (actor.admin) "ADMIN_TRANSFER" else "TRANSFER", actor, newLeader, "from ${team.leader}", now)
        TransferResult.Transferred(loadTeam(id) ?: throw StaleWriteException("team $id vanished during a transfer"), team.leader)
    }

    // ── Disband ─────────────────────────────────────────────

    sealed interface DisbandResult {
        /**
         * [team] is the roster right before disbanding; [invites] the invitations that were cancelled;
         * [relations] the alliance/rivalries/requests removed with it (issue #1405, no cooldown).
         */
        data class Disbanded(val team: Team, val invites: List<Invite>, val relations: TeamRelationStore.Cleanup? = null) : DisbandResult
        data object NotInTeam : DisbandResult
        data class NotLeader(val team: Team) : DisbandResult
        /** Already disbanded (a second, concurrent disband) or never existed. */
        data object TeamGone : DisbandResult
    }

    /**
     * Ends a team: the row becomes DISBANDED (kept for history), every member gets a history row
     * and loses the membership, and pending invitations are deleted. Hearts, inventories,
     * balances and anything else outside these tables are never touched. A player [actor] must be
     * the team's leader ([teamId] null = their own team); an admin names the team.
     */
    fun disband(actor: Actor, teamId: String?, now: Long): DisbandResult = tx {
        val id: String
        if (actor.admin) {
            id = teamId ?: throw IllegalArgumentException("an admin disband names the team")
        } else {
            val actorUuid = actor.uuid ?: throw IllegalArgumentException("console disbands are admin disbands")
            id = memberTeamId(actorUuid) ?: return@tx DisbandResult.NotInTeam
        }
        val team = loadTeam(id) ?: return@tx if (actor.admin) DisbandResult.TeamGone else DisbandResult.NotInTeam
        if (!actor.admin && team.leader != actor.uuid) return@tx DisbandResult.NotLeader(team)
        val invites = db.query("SELECT * FROM lifesteal_team_invites WHERE team_id = ?", id) { inviteRow(it) }.filterNotNull()
        val updated = db.executeUpdate(
            "UPDATE lifesteal_teams SET status = 'DISBANDED', disbanded_at = ?, disbanded_by = ?, version = version + 1 " +
                "WHERE id = ? AND status = 'ACTIVE' AND version = ?",
            now, actor.id, id, team.version
        )
        if (updated != 1) throw StaleWriteException("team $id changed while it was being disbanded")
        for (member in team.members) {
            db.execute(
                "INSERT INTO lifesteal_team_membership_history (team_id, uuid, joined_at, left_at, reason) VALUES (?, ?, ?, ?, ?)",
                id, member.uuid.toString(), member.joinedAt, now, "DISBANDED"
            )
        }
        db.execute("DELETE FROM lifesteal_team_members WHERE team_id = ?", id)
        db.execute("DELETE FROM lifesteal_team_invites WHERE team_id = ?", id)
        audit(id, if (actor.admin) "ADMIN_DISBAND" else "DISBAND", actor, null, "members=${team.members.joinToString(",") { it.uuid.toString() }}", now)
        // Issue #1405: same transaction, so the team and its relationships end together or not at all.
        val cleanup = relations?.cleanupDisbanded(id, actor.id, now)
        DisbandResult.Disbanded(team, invites, cleanup)
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun <T> tx(block: () -> T): T {
        var result: Result<T>? = null
        db.transaction { result = Result.success(block()) }
        return result!!.getOrThrow()
    }

    /** Moves the team row on by one version, or throws when it changed since [team] was read. */
    private fun bump(team: Team) {
        val updated = db.executeUpdate(
            "UPDATE lifesteal_teams SET version = version + 1 WHERE id = ? AND status = 'ACTIVE' AND version = ?",
            team.id, team.version
        )
        if (updated != 1) throw StaleWriteException("team ${team.id} changed underneath a membership change")
    }

    private fun removeMember(team: Team, uuid: UUID, reason: String, now: Long) {
        bump(team)
        val member = team.members.first { it.uuid == uuid }
        val deleted = db.executeUpdate("DELETE FROM lifesteal_team_members WHERE uuid = ? AND team_id = ?", uuid.toString(), team.id)
        if (deleted != 1) throw StaleWriteException("membership of $uuid in ${team.id} changed during removal")
        db.execute(
            "INSERT INTO lifesteal_team_membership_history (team_id, uuid, joined_at, left_at, reason) VALUES (?, ?, ?, ?, ?)",
            team.id, uuid.toString(), member.joinedAt, now, reason
        )
    }

    private fun memberTeamId(uuid: UUID): String? =
        db.queryFirst("SELECT team_id FROM lifesteal_team_members WHERE uuid = ?", uuid.toString()) { it.getString("team_id") }

    private fun activeIdByName(canonical: String): String? =
        db.queryFirst("SELECT id FROM lifesteal_teams WHERE canonical_name = ? AND status = 'ACTIVE'", canonical) { it.getString("id") }

    private fun inviteRow(teamId: String, target: UUID): Invite? =
        db.queryFirst("SELECT * FROM lifesteal_team_invites WHERE team_id = ? AND target = ?", teamId, target.toString()) { inviteRow(it) }

    private fun members(teamId: String): List<Member> =
        db.query("SELECT uuid, joined_at FROM lifesteal_team_members WHERE team_id = ? ORDER BY joined_at, uuid", teamId) { rs ->
            uuidOrNull(rs.getString("uuid"))?.let { Member(it, rs.getLong("joined_at")) }
        }.filterNotNull()

    private fun audit(teamId: String, action: String, actor: Actor?, target: UUID?, details: String?, now: Long) {
        db.execute(
            "INSERT INTO lifesteal_team_audit (team_id, action, actor, actor_name, target, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
            teamId, action, actor?.id, actor?.name, target?.toString(), details, now
        )
    }

    /** A team row without its roster; the leader is listed first once the roster is attached. */
    private class TeamRow(
        val id: String, val name: String, val canonical: String, val leader: UUID,
        val createdAt: Long, val createdBy: UUID, val version: Long,
    ) {
        fun withMembers(members: List<Member>): Team = Team(
            id, name, canonical, leader, createdAt, createdBy, version,
            members.sortedWith(compareBy<Member> { it.uuid != leader }.thenBy { it.joinedAt }),
        )
    }

    /** Null for a row whose UUIDs can't be read (never guessed). */
    private fun teamRow(rs: ResultSet): TeamRow? {
        val leader = uuidOrNull(rs.getString("leader")) ?: return null
        val createdBy = uuidOrNull(rs.getString("created_by")) ?: leader
        return TeamRow(
            rs.getString("id"), rs.getString("name"), rs.getString("canonical_name"), leader,
            rs.getLong("created_at"), createdBy, rs.getLong("version"),
        )
    }

    private fun inviteRow(rs: ResultSet): Invite? {
        val target = uuidOrNull(rs.getString("target")) ?: return null
        val inviter = uuidOrNull(rs.getString("inviter")) ?: return null
        return Invite(rs.getString("team_id"), target, inviter, rs.getLong("created_at"), rs.getLong("expires_at"))
    }

    private fun uuidOrNull(raw: String?): UUID? = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    companion object {
        /** The cap that applies: the configured size, never above the blueprint's five. */
        fun effectiveMax(maxSize: Int): Int = maxSize.coerceIn(1, LifestealRules.MAX_TEAM_MEMBERS)
    }
}
