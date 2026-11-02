// Checks the Lifesteal team system (issue #1346): the lifesteal.yml team settings, the Bukkit-free
// name rules, the team store against a real SQLite database (fresh temp directories, deleted
// afterwards) including restarts, concurrency and rollback, and the Lifesteal-only / Survival
// isolation wiring. The Gradle build has no test source set, so it runs through an init script,
// from the repo root:
//
//   ./gradlew -I scripts/lifesteal-team-check.init.gradle.kts lifestealTeamCheck
//
// Exits non-zero on the first failed check. Gameplay on a real Paper server (and with real Bedrock
// clients) is NOT covered here (see docs/lifesteal-teams.md, "Manual tests").

import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealRules
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.AcceptResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Actor
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.CreateResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.DenyResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.DisbandResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.InviteResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.KickResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.LeaveResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.TransferResult
import com.liam.joshymc.lifesteal.teams.TeamNames
import com.liam.joshymc.manager.DatabaseManager
import com.liam.joshymc.manager.SqliteDatabase
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }

private fun <T> withDb(dir: File, block: (DatabaseManager) -> T): T {
    val db = DatabaseManager(dir, quietLogger())
    db.start()
    try {
        return block(db)
    } finally {
        db.shutdown()
    }
}

private fun <T> withStore(name: String, block: (LifestealTeamStore, DatabaseManager) -> T): T {
    val dir = Files.createTempDirectory("joshymc-teams-$name").toFile()
    try {
        return withDb(dir) { db -> val store = LifestealTeamStore(db); store.createTables(); block(store, db) }
    } finally {
        dir.deleteRecursively()
    }
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private const val T0 = 1_800_000_000_000L
private const val TTL = 60_000L
private const val MAX = 5

/** A Floodgate-style Bedrock UUID: just a UUID to the team system. */
private val BEDROCK = UUID.fromString("00000000-0000-0000-0009-01f2a3b4c5d6")

private fun actor(uuid: UUID, name: String = "p-${uuid.toString().take(4)}") = Actor(uuid, name)
private val ADMIN = Actor(null, "Console", admin = true)

private fun created(store: LifestealTeamStore, name: String, leader: UUID = UUID.randomUUID(), now: Long = T0): LifestealTeamStore.Team {
    val result = store.create(UUID.randomUUID().toString(), name, leader, "leader", now)
    check("team $name created", result is CreateResult.Created)
    return (result as CreateResult.Created).team
}

/** Invites [player] to [team] (by its leader) and accepts; returns the accept result. */
private fun join(store: LifestealTeamStore, team: LifestealTeamStore.Team, player: UUID, now: Long = T0, max: Int = MAX): AcceptResult {
    store.invite(actor(team.leader), player, max, now, TTL)
    return store.accept(player, "joiner", team.name, max, now)
}

fun main() {
    settings()
    names()
    store()
    persistence()
    databaseRules()
    concurrency()
    rollback()
    wiring()
    println("OK: $checks Lifesteal team checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

private fun bundledLifestealYml(): Map<String, Any?> {
    val text = InputStreamReader(Thread.currentThread().contextClassLoader.getResourceAsStream("lifesteal.yml")!!, Charsets.UTF_8).readText()
    return Yaml().load(text)
}

private fun parseWith(map: Map<String, Any?>) =
    LifestealConfigParser.parse { p -> p.split('.').fold<String, Any?>(map) { node, key -> (node as? Map<*, *>)?.get(key) } }

@Suppress("UNCHECKED_CAST")
private fun withTeams(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> {
    val map = bundledLifestealYml().toMutableMap()
    val section = (map["teams"] as Map<String, Any?>).toMutableMap()
    edit(section)
    map["teams"] = section
    return map
}

private fun settings() {
    val parsed = parseWith(bundledLifestealYml())
    val teams = parsed.settings!!.teams
    check("bundled teams = defaults", teams == LifestealSettings.Teams())
    check("bundled file has no problems", parsed.problems.isEmpty())
    check("five players by default", teams.maxSize == 5)
    check("invitations last 60 seconds by default", teams.inviteExpirationSeconds == 60)
    check("enabled, creation and disbanding on by default", teams.enabled && teams.allowCreation && teams.allowDisbanding)
    check("name length 3..16 by default", teams.nameMinLength == 3 && teams.nameMaxLength == 16)
    check("approved limit is five", LifestealRules.MAX_TEAM_MEMBERS == 5 && LifestealConfigParser.MAX_TEAM_SIZE == 5)
    check("no officer rank", !LifestealRules.TEAM_OFFICERS)

    check("max-size 6 refused (above the approved five)", parseWith(withTeams { it["max-size"] = 6 }).settings == null)
    check("max-size 0 refused", parseWith(withTeams { it["max-size"] = 0 }).settings == null)
    check("max-size 3 accepted with smaller ranked sizes", parseWith(withTeams { it["max-size"] = 3 }.toMutableMap().apply {
        this["ranked-team-wars"] = mapOf("team-sizes" to listOf(2, 3))
    }).settings?.teams?.maxSize == 3)
    check("invite expiry 5s refused", parseWith(withTeams { it["invite-expiration-seconds"] = 5 }).settings == null)
    check("invite expiry 'soon' refused", parseWith(withTeams { it["invite-expiration-seconds"] = "soon" }).settings == null)
    check("invite expiry 120 accepted", parseWith(withTeams { it["invite-expiration-seconds"] = 120 }).settings?.teams?.inviteExpirationSeconds == 120)
    check("enabled must be a boolean", parseWith(withTeams { it["enabled"] = "yes" }).settings == null)
    check("teams can be turned off", parseWith(withTeams { it["enabled"] = false }).settings?.teams?.enabled == false)
    check("min length above max refused", parseWith(withTeams { it["name-min-length"] = 10; it["name-max-length"] = 5 }).settings == null)
    check("name length above 16 refused", parseWith(withTeams { it["name-max-length"] = 32 }).settings == null)
    check("reserved names must be a list", parseWith(withTeams { it["reserved-names"] = "joshy" }).settings == null)
    check("reserved names read", parseWith(withTeams { it["reserved-names"] = listOf("Event", " Build ") }).settings?.teams?.reservedNames == listOf("Event", "Build"))

    // A lifesteal.yml from before issue #1346 (teams.max-size only) still loads, with one warning.
    val old = withTeams { section -> section.keys.retainAll(setOf("max-size")) }
    val oldParsed = parseWith(old)
    check("old teams section still loads", oldParsed.settings?.teams == LifestealSettings.Teams())
    check("old teams section is one warning", oldParsed.problems.size == 1 && !oldParsed.problems[0].severe && "invite-expiration-seconds" in oldParsed.problems[0].message)
    check("invalid team settings make the whole file invalid (hearts pause too, nothing guessed)", parseWith(withTeams { it["max-size"] = 50 }).settings == null)
}

// ── Names ────────────────────────────────────────────────────

private fun valid(name: String, reserved: List<String> = emptyList(), profane: (String) -> Boolean = { false }) =
    TeamNames.validate(name, 3, 16, reserved, profane) is TeamNames.Check.Valid

private fun names() {
    val ok = TeamNames.validate("  RedWolves ", 3, 16)
    check("valid name keeps the chosen capitalization", ok == TeamNames.Check.Valid("RedWolves", "redwolves"))
    check("canonical is lower case", TeamNames.canonical("ReD_WoLvEs") == "red_wolves")
    check("digits and _ allowed", valid("Team_42"))
    check("too short refused", !valid("ab"))
    check("too long refused", !valid("abcdefghijklmnopq"))
    check("spaces refused", !valid("red wolves"))
    check("symbols refused", !valid("red&wolves"))
    check("colour codes refused", !valid("§cRed"))
    check("non-ASCII look-alikes refused", !valid("Аdmin")) // Cyrillic А
    check("only underscores refused", !valid("____"))
    check("empty refused", !valid("   "))
    for (name in listOf("Admin", "ADMIN", "adm1n", "Adm_in", "TheAdmins", "Staff", "St4ff_Team", "Moderator", "M0derat0r", "JoshyMC", "J0shy_MC", "joshy", "Console", "0fficia1", "Owner", "Server", "System", "Mod", "OP", "Dev", "Helper", "Lifesteal", "Notch")) {
        check("$name is protected", !valid(name))
    }
    for (name in listOf("Modern", "Observer", "Systematic", "Develop", "Operation_X", "Helpful", "Manor", "Teamwork")) {
        check("$name is allowed (only contains a short protected word)", valid(name))
    }
    check("config reserved names apply", !valid("EventCrew", reserved = listOf("eventcrew")))
    check("config reserved names ignore case and _", !valid("Event_Crew", reserved = listOf("EVENTCREW")))
    check("config reserved names are whole names", valid("EventCrews", reserved = listOf("eventcrew")))
    val profane = { s: String -> Regex("(?<![a-zA-Z0-9])badword(?![a-zA-Z0-9])", RegexOption.IGNORE_CASE).containsMatchIn(s) }
    check("profanity refused", !valid("badword", profane = profane))
    check("profanity behind _ refused", !valid("the_badword", profane = profane))
    check("profanity split by _ refused", !valid("bad_word", profane = profane))
    check("clean names pass the filter", valid("goodword", profane = profane))
}

// ── Store ────────────────────────────────────────────────────

private fun store() = withStore("store") { store, _ ->
    val leader = UUID.randomUUID()
    val team = created(store, "Wolves", leader)
    check("creator is leader", team.leader == leader)
    check("creator is the only member", team.members.map { it.uuid } == listOf(leader))
    check("stable id is a UUID", runCatching { UUID.fromString(team.id) }.isSuccess)
    check("display name kept", team.name == "Wolves" && team.canonicalName == "wolves")
    check("creation time stored", team.createdAt == T0 && team.createdBy == leader)

    check("duplicate name refused", store.create(UUID.randomUUID().toString(), "Wolves", UUID.randomUUID(), "x", T0) is CreateResult.NameTaken)
    check("duplicate name refused ignoring case", store.create(UUID.randomUUID().toString(), "WOLVES", UUID.randomUUID(), "x", T0) is CreateResult.NameTaken)
    check("creating while in a team refused", store.create(UUID.randomUUID().toString(), "Other", leader, "x", T0) is CreateResult.AlreadyInTeam)

    // Invitations
    val a = UUID.randomUUID()
    val invited = store.invite(actor(leader), a, MAX, T0, TTL)
    check("invite sent", invited is InviteResult.Invited && invited.invite.target == a && invited.invite.expiresAt == T0 + TTL)
    val again = store.invite(actor(leader), a, MAX, T0 + 1_000, TTL)
    check("duplicate invite is not a new invitation", again is InviteResult.AlreadyInvited && again.invite.expiresAt == T0 + TTL)
    check("one invitation row", store.invitesFor(a).size == 1)
    check("self invite refused", store.invite(actor(leader), leader, MAX, T0, TTL) is InviteResult.Self)
    check("non-member can't invite", store.invite(actor(UUID.randomUUID()), a, MAX, T0, TTL) is InviteResult.NotInTeam)

    val joined = store.accept(a, "a", "wolves", MAX, T0 + 2_000)
    check("accept joins (team found by canonical name)", joined is AcceptResult.Joined && joined.team.isMember(a) && joined.team.size == 2)
    check("joined invitation removed", store.invitesFor(a).isEmpty())
    check("member can't invite (leader only)", store.invite(actor(a), UUID.randomUUID(), MAX, T0, TTL) is InviteResult.NotLeader)
    check("inviting a member refused", store.invite(actor(leader), a, MAX, T0, TTL) is InviteResult.AlreadyMember)
    check("accepting again refused", store.accept(a, "a", "Wolves", MAX, T0) is AcceptResult.NoInvite)
    check("membership timestamp stored", store.teamOf(a)!!.members.first { it.uuid == a }.joinedAt == T0 + 2_000)

    // Other team / one team per player
    val otherLeader = UUID.randomUUID()
    val other = created(store, "Foxes", otherLeader)
    check("inviting someone in another team refused", store.invite(actor(otherLeader), a, MAX, T0, TTL) is InviteResult.TargetInOtherTeam)
    val b = UUID.randomUUID()
    store.invite(actor(leader), b, MAX, T0, TTL)
    store.invite(actor(otherLeader), b, MAX, T0, TTL)
    check("two invitations pending", store.invitesFor(b).size == 2)
    check("b joins Foxes", store.accept(b, "b", "Foxes", MAX, T0) is AcceptResult.Joined)
    check("joining removes every other invitation", store.invitesFor(b).isEmpty())
    check("b can't join a second team", store.accept(b, "b", "Wolves", MAX, T0) is AcceptResult.NoInvite)

    // Expiry
    val c = UUID.randomUUID()
    store.invite(actor(leader), c, MAX, T0, TTL)
    check("expired invitation can't be accepted", store.accept(c, "c", "Wolves", MAX, T0 + TTL) is AcceptResult.Expired)
    check("expired invitation removed", store.invitesFor(c).isEmpty())
    store.invite(actor(leader), c, MAX, T0, TTL)
    check("re-invite after expiry is a fresh invitation", store.invite(actor(leader), c, MAX, T0 + TTL, TTL) is InviteResult.Invited)
    check("sweep keeps live invitations", store.expireInvites(T0 + TTL + 1).isEmpty())
    check("sweep removes expired invitations", store.expireInvites(T0 + 2 * TTL).map { it.target } == listOf(c) && store.invitesFor(c).isEmpty())
    check("accept with no invitation refused", store.accept(c, "c", "Wolves", MAX, T0) is AcceptResult.NoInvite)
    check("accept to an unknown team refused", store.accept(c, "c", "Nobody", MAX, T0) is AcceptResult.TeamGone)

    // Deny
    store.invite(actor(leader), c, MAX, T0, TTL)
    check("deny", store.deny(c, "c", "Wolves", T0 + 1) is DenyResult.Denied)
    check("denied invitation gone", store.invitesFor(c).isEmpty() && store.accept(c, "c", "Wolves", MAX, T0) is AcceptResult.NoInvite)
    check("deny without invitation", store.deny(c, "c", "Wolves", T0) is DenyResult.NoInvite)
    store.invite(actor(leader), c, MAX, T0, TTL)
    check("deny of an expired invitation says so", store.deny(c, "c", "Wolves", T0 + TTL) is DenyResult.Expired)

    // Capacity
    val full = created(store, "Bears")
    val fillers = List(4) { UUID.randomUUID() }
    for (p in fillers) check("filler joins", join(store, full, p) is AcceptResult.Joined)
    check("five members", store.loadTeam(full.id)!!.size == 5)
    check("inviting into a full team refused", store.invite(actor(full.leader), UUID.randomUUID(), MAX, T0, TTL) is InviteResult.Full)
    val smaller = created(store, "Owls")
    check("configured max 2 is enforced", join(store, smaller, UUID.randomUUID(), max = 2) is AcceptResult.Joined)
    val third = UUID.randomUUID()
    check("third invite into a 2-player team refused", store.invite(actor(smaller.leader), third, 2, T0, TTL) is InviteResult.Full)
    check("a configured max above five is capped at five", LifestealTeamStore.effectiveMax(10) == 5 && LifestealTeamStore.effectiveMax(0) == 1)
    // An invitation sent while there was room can't be used once the team filled up.
    val late = created(store, "Hawks")
    val waiting = UUID.randomUUID()
    store.invite(actor(late.leader), waiting, MAX, T0, TTL)
    repeat(4) { join(store, late, UUID.randomUUID()) }
    check("accept into a full team refused (validated again on accept)", store.accept(waiting, "w", "Hawks", MAX, T0) is AcceptResult.Full)
    check("still five", store.loadTeam(late.id)!!.size == 5)

    // Leave / kick
    check("leader can't leave while leading", store.leave(leader, "l", T0) is LeaveResult.LeaderMustTransfer)
    check("teamless leave", store.leave(UUID.randomUUID(), "x", T0) is LeaveResult.NotInTeam)
    val left = store.leave(a, "a", T0 + 5)
    check("member leaves", left is LeaveResult.Left && !left.team.isMember(a) && store.teamOf(a) == null)
    check("history row for the leave", store.history(team.id).any { it.uuid == a && it.reason == "LEFT" && it.joinedAt == T0 + 2_000 && it.leftAt == T0 + 5 })
    val d = UUID.randomUUID()
    join(store, store.loadTeam(team.id)!!, d)
    check("kick by non-leader refused", store.kick(actor(d), leader, T0) is KickResult.NotLeader)
    check("kick self refused", store.kick(actor(leader), leader, T0) is KickResult.Self)
    check("kick member of another team refused", store.kick(actor(leader), b, T0) is KickResult.NotMember)
    check("kick by teamless player refused", store.kick(actor(UUID.randomUUID()), d, T0) is KickResult.NotInTeam)
    check("kick (offline: UUID only)", store.kick(actor(leader), d, T0) is KickResult.Kicked && store.teamOf(d) == null)
    check("kicked player can be invited again", store.invite(actor(leader), d, MAX, T0, TTL) is InviteResult.Invited)

    // Transfer
    val e = UUID.randomUUID()
    join(store, store.loadTeam(team.id)!!, e)
    check("transfer to non-member refused", store.transfer(actor(leader), null, b, T0) is TransferResult.NotMember)
    check("transfer by member refused", store.transfer(actor(e), null, e, T0) is TransferResult.NotLeader)
    check("transfer to self refused", store.transfer(actor(leader), null, leader, T0) is TransferResult.AlreadyLeader)
    val moved = store.transfer(actor(leader), null, e, T0)
    check("transfer", moved is TransferResult.Transferred && moved.team.leader == e && moved.previousLeader == leader)
    check("old leader stays a member", store.loadTeam(team.id)!!.isMember(leader))
    check("leader listed first", store.loadTeam(team.id)!!.members.first().uuid == e)
    check("old leader can now leave", store.leave(leader, "l", T0) is LeaveResult.Left)
    check("admin transfer to non-member refused", store.transfer(ADMIN, team.id, leader, T0) is TransferResult.NotMember)

    // Disband
    val roster = created(store, "Lynx")
    val r1 = UUID.randomUUID()
    join(store, roster, r1)
    val pending = UUID.randomUUID()
    store.invite(actor(roster.leader), pending, MAX, T0, TTL)
    check("member can't disband", store.disband(actor(r1), null, T0) is DisbandResult.NotLeader)
    val gone = store.disband(actor(roster.leader), null, T0 + 9)
    check("disband", gone is DisbandResult.Disbanded && gone.team.size == 2 && gone.invites.map { it.target } == listOf(pending))
    check("disbanded team no longer loads", store.loadTeam(roster.id) == null && store.teamByName("Lynx") == null)
    check("disbanded row kept for history", store.status(roster.id) == "DISBANDED")
    check("members lost membership", store.teamOf(r1) == null && store.teamOf(roster.leader) == null)
    check("outstanding invitations invalidated", store.invitesFor(pending).isEmpty() && store.accept(pending, "p", "Lynx", MAX, T0) is AcceptResult.TeamGone)
    check("history rows for every member", store.history(roster.id).count { it.reason == "DISBANDED" } == 2)
    check("disband audited", store.audit(roster.id).any { it.action == "DISBAND" })
    check("second disband refused", store.disband(actor(roster.leader), null, T0) is DisbandResult.NotInTeam)
    check("admin disband of a disbanded team refused", store.disband(ADMIN, roster.id, T0) is DisbandResult.TeamGone)
    check("name is free again after disbanding", store.create(UUID.randomUUID().toString(), "lynx", UUID.randomUUID(), "x", T0) is CreateResult.Created)
    val adminGone = store.disband(ADMIN, other.id, T0)
    check("admin disband", adminGone is DisbandResult.Disbanded && store.audit(other.id).any { it.action == "ADMIN_DISBAND" && it.actor == "console" })

    // Bedrock (Floodgate) UUIDs are plain UUIDs: cross-platform teams.
    val mixed = created(store, "Crossplay")
    check("Bedrock player joins a Java leader's team", join(store, mixed, BEDROCK) is AcceptResult.Joined)
    check("Bedrock leadership", store.transfer(actor(mixed.leader), null, BEDROCK, T0) is TransferResult.Transferred && store.teamOf(BEDROCK)!!.leader == BEDROCK)

    // Audit trail
    val actions = store.audit(team.id).map { it.action }
    for (action in listOf("CREATE", "INVITE", "JOIN", "DENY", "INVITE_EXPIRED", "LEAVE", "KICK", "TRANSFER")) check("audit has $action", action in actions)
}

// ── Persistence ──────────────────────────────────────────────

private fun persistence() {
    val dir = Files.createTempDirectory("joshymc-teams-restart").toFile()
    try {
        val leader = UUID.randomUUID()
        val member = UUID.randomUUID()
        val invitee = UUID.randomUUID()
        val id = withDb(dir) { db ->
            val store = LifestealTeamStore(db).also { it.createTables() }
            val team = created(store, "Persist", leader)
            join(store, team, member)
            store.invite(actor(leader), invitee, MAX, T0, TTL)
            team.id
        }
        withDb(dir) { db ->
            val store = LifestealTeamStore(db).also { it.createTables() } // createTables twice is harmless
            val loaded = store.loadActiveTeams()
            check("team survives a restart", loaded.size == 1 && loaded[0].id == id && loaded[0].name == "Persist")
            check("leader survives a restart", loaded[0].leader == leader)
            check("members survive a restart", loaded[0].members.map { it.uuid }.toSet() == setOf(leader, member))
            check("invitation survives a restart", store.invites().map { it.target } == listOf(invitee))
            check("invitation usable after restart", store.accept(invitee, "i", "persist", MAX, T0 + 1) is AcceptResult.Joined)
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Database-level rules ─────────────────────────────────────

private fun databaseRules() = withStore("db") { store, db ->
    val team = created(store, "Strict")
    repeat(4) { join(store, team, UUID.randomUUID()) }
    val sixth = runCatching { db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, ?)", UUID.randomUUID().toString(), team.id, T0) }
    check("the database itself refuses a sixth member", sixth.isFailure && store.loadTeam(team.id)!!.size == 5)
    val member = team.leader
    val twice = runCatching { db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, ?)", member.toString(), created(store, "Second").id, T0) }
    check("the database itself refuses a second membership", twice.isFailure)
    val ghost = runCatching { db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, ?)", UUID.randomUUID().toString(), "no-such-team", T0) }
    check("the database refuses a membership of an unknown team", ghost.isFailure)
    val small = created(store, "Gone")
    store.disband(actor(small.leader), null, T0)
    val intoDisbanded = runCatching { db.execute("INSERT INTO lifesteal_team_members (uuid, team_id, joined_at) VALUES (?, ?, ?)", UUID.randomUUID().toString(), small.id, T0) }
    check("the database refuses a membership of a disbanded team", intoDisbanded.isFailure)
    val move = runCatching { db.execute("UPDATE lifesteal_team_members SET team_id = ? WHERE uuid = ?", small.id, member.toString()) }
    check("the database refuses moving a member between teams", move.isFailure)
    val dupName = runCatching {
        db.execute(
            "INSERT INTO lifesteal_teams (id, name, canonical_name, leader, status, created_at, created_by) VALUES (?, 'STRICT', 'strict', ?, 'ACTIVE', 0, ?)",
            UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString()
        )
    }
    check("the database refuses two active teams with one canonical name", dupName.isFailure)
}

// ── Concurrency ──────────────────────────────────────────────

private fun <T> race(threads: Int, task: (Int) -> T): List<T> {
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val results = Collections.synchronizedList(mutableListOf<T>())
    val futures = (0 until threads).map { i -> pool.submit { start.await(); results += task(i) } }
    start.countDown()
    futures.forEach { it.get(30, TimeUnit.SECONDS) }
    pool.shutdown()
    return results
}

private fun concurrency() = withStore("race") { store, _ ->
    // Two (here: six) players racing for the final slot.
    val team = created(store, "LastSlot")
    repeat(3) { join(store, team, UUID.randomUUID()) }
    val racers = List(6) { UUID.randomUUID() }
    for (p in racers) store.invite(actor(team.leader), p, MAX, T0, TTL)
    val results = race(racers.size) { i -> store.accept(racers[i], "r$i", "LastSlot", MAX, T0 + 1) }
    check("exactly one player gets the final slot", results.count { it is AcceptResult.Joined } == 1)
    check("everyone else is told the team is full", results.count { it is AcceptResult.Full } == racers.size - 1)
    check("team ends at five", store.loadTeam(team.id)!!.size == 5)

    // Many players accepting invitations from several teams at once.
    val teams = List(4) { created(store, "Race$it") }
    val players = List(24) { UUID.randomUUID() }
    for (p in players) for (t in teams) store.invite(actor(t.leader), p, MAX, T0, TTL)
    race(players.size * teams.size) { i -> store.accept(players[i / teams.size], "p", teams[i % teams.size].name, MAX, T0 + 1) }
    val loaded = store.loadActiveTeams().filter { it.name.startsWith("Race") }
    check("no team above five", loaded.all { it.size <= 5 })
    check("all slots filled (4 x 4 joined)", loaded.sumOf { it.size } == 4 * 5)
    val memberships = loaded.flatMap { it.members.map { m -> m.uuid } }
    check("no player in two teams", memberships.size == memberships.toSet().size)

    // Duplicate creation: same name from many players, and many names from one player.
    val sameName = race(8) { store.create(UUID.randomUUID().toString(), "Dupe", UUID.randomUUID(), "x", T0) }
    check("one team per name under concurrency", sameName.count { it is CreateResult.Created } == 1 && sameName.count { it is CreateResult.NameTaken } == 7)
    val creator = UUID.randomUUID()
    val oneCreator = race(8) { i -> store.create(UUID.randomUUID().toString(), "Solo$i", creator, "x", T0) }
    check("one team per creator under concurrency", oneCreator.count { it is CreateResult.Created } == 1)

    // Concurrent disbands and transfers.
    val target = created(store, "Contested")
    val members = List(4) { UUID.randomUUID() }.onEach { join(store, target, it) }
    val disbands = race(10) { store.disband(actor(target.leader), null, T0) }
    check("exactly one disband", disbands.count { it is DisbandResult.Disbanded } == 1)
    check("one disband audit row", store.audit(target.id).count { it.action == "DISBAND" } == 1)
    check("no member left behind", members.all { store.teamOf(it) == null })

    val ruled = created(store, "Crown")
    val heirs = List(4) { UUID.randomUUID() }.onEach { join(store, ruled, it) }
    val transfers = race(heirs.size) { i -> store.transfer(actor(ruled.leader), null, heirs[i], T0) }
    check("exactly one leadership transfer", transfers.count { it is TransferResult.Transferred } == 1)
    val after = store.loadTeam(ruled.id)!!
    check("one leader, a member", after.leader in heirs && after.isMember(after.leader) && after.size == 5)

    // Mixed: joins, leaves and kicks hammering one team keep it consistent.
    val busy = created(store, "Busy")
    val pool = List(10) { UUID.randomUUID() }
    race(40) { i ->
        val p = pool[i % pool.size]
        when (i % 4) {
            0 -> { store.invite(actor(busy.leader), p, MAX, T0, TTL); store.accept(p, "p", "Busy", MAX, T0 + 1) }
            1 -> store.leave(p, "p", T0 + 2)
            2 -> store.kick(actor(busy.leader), p, T0 + 3)
            else -> store.accept(p, "p", "Busy", MAX, T0 + 4)
        }
    }
    val final = store.loadTeam(busy.id)!!
    check("mixed changes keep the cap", final.size in 1..5)
    check("leader still a member after mixed changes", final.isMember(busy.leader) && final.leader == busy.leader)
    check("roster matches membership lookups", pool.all { p -> (store.teamOf(p)?.id == busy.id) == final.isMember(p) })
}

// ── Rollback ─────────────────────────────────────────────────

/** Fails statements whose parameters contain [failOn]; runs [beforeUpdate] once before the next team UPDATE. */
private class FailingDb(private val real: DatabaseManager) : SqliteDatabase by real {
    var failOn: String? = null
    var beforeUpdate: (() -> Unit)? = null

    override fun execute(sql: String, vararg params: Any?) {
        val fail = failOn
        if (fail != null && params.any { it == fail }) throw java.sql.SQLException("injected failure on $fail")
        real.execute(sql, *params)
    }

    override fun executeUpdate(sql: String, vararg params: Any?): Int {
        if (sql.startsWith("UPDATE lifesteal_teams")) beforeUpdate?.let { beforeUpdate = null; it() }
        return real.executeUpdate(sql, *params)
    }
}

private fun rollback() {
    val dir = Files.createTempDirectory("joshymc-teams-rollback").toFile()
    try {
        withDb(dir) { real ->
            val db = FailingDb(real)
            val store = LifestealTeamStore(db)
            store.createTables()
            val team = created(store, "Fragile")
            val p = UUID.randomUUID()
            store.invite(actor(team.leader), p, MAX, T0, TTL)

            // The audit row of a join fails after the membership row was written.
            db.failOn = "JOIN"
            check("database failure surfaces", runCatching { store.accept(p, "p", "Fragile", MAX, T0) }.isFailure)
            db.failOn = null
            check("no membership left behind", store.teamOf(p) == null)
            check("invitation still there", store.invitesFor(p).size == 1)
            check("team version unchanged", store.loadTeam(team.id)!!.version == team.version)
            check("retry works after the rollback", store.accept(p, "p", "Fragile", MAX, T0) is AcceptResult.Joined)

            // Disband failing half way (history row) keeps the team whole.
            db.failOn = "DISBANDED"
            check("disband failure surfaces", runCatching { store.disband(actor(team.leader), null, T0) }.isFailure)
            db.failOn = null
            check("failed disband changed nothing", store.loadTeam(team.id)?.size == 2 && store.status(team.id) == "ACTIVE")

            // Stale write: the team row changes between the read and the conditional update.
            db.beforeUpdate = { real.executeUpdate("UPDATE lifesteal_teams SET version = version + 1 WHERE id = ?", team.id) }
            val stale = runCatching { store.transfer(actor(team.leader), null, p, T0) }
            check("stale write refused", stale.exceptionOrNull() is LifestealTeamStore.StaleWriteException)
            check("stale transfer rolled back entirely", store.loadTeam(team.id)!!.leader == team.leader)

            db.beforeUpdate = { real.executeUpdate("UPDATE lifesteal_teams SET version = version + 1 WHERE id = ?", team.id) }
            check("stale leave refused", runCatching { store.leave(p, "p", T0) }.exceptionOrNull() is LifestealTeamStore.StaleWriteException)
            check("stale leave kept the member", store.loadTeam(team.id)!!.isMember(p))

            // Creation failure leaves no half team.
            val creator = UUID.randomUUID()
            db.failOn = "name=Broken"
            check("create failure surfaces", runCatching { store.create(UUID.randomUUID().toString(), "Broken", creator, "c", T0) }.isFailure)
            db.failOn = null
            check("no half-created team", store.teamByName("Broken") == null && store.teamOf(creator) == null)
        }
        val closed = DatabaseManager(dir, quietLogger()).also { it.start(); it.shutdown() }
        check("closed database refuses team changes", runCatching { LifestealTeamStore(closed).create(UUID.randomUUID().toString(), "Late", UUID.randomUUID(), "x", T0) }.isFailure)
    } finally {
        dir.deleteRecursively()
    }
}

// ── Wiring (source) ──────────────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/teams/LifestealTeamManager.kt")
    val store = source("lifesteal/teams/LifestealTeamStore.kt")
    val menu = source("lifesteal/teams/LifestealTeamMenu.kt")
    val command = source("lifesteal/teams/LifestealTeamCommand.kt")
    val router = source("command/TeamCommandRouter.kt")
    val commands = source("manager/CommandManager.kt")
    val listeners = source("manager/ListenerManager.kt")
    val main = source("Joshymc.kt")
    val survivalManager = source("manager/TeamManager.kt")
    val pluginYml = resourceText("plugin.yml")

    // Gamemode isolation: /team is routed, Survival keeps its own system, scope and permission.
    check("/team is NETWORK (routed per gamemode)", GamemodeAssignments.COMMANDS["team"] == GamemodeScope.NETWORK)
    check("router sends Lifesteal senders to Lifesteal teams", "plugin.lifestealTeamManager.hosted && plugin.gamemodeLockManager.gamemodeOf(sender) == ServerGamemode.LIFESTEAL" in router)
    check("router keeps the Survival scope check", "isAllowed(GamemodeScope.SURVIVAL, sender)" in router)
    check("router keeps the Survival permission", "SURVIVAL_PERMISSION = \"joshymc.team\"" in router && "hasPermission(SURVIVAL_PERMISSION)" in router)
    check("router delegates to the Survival TeamCommand", "survival.onCommand(sender, command, label, args)" in router)
    check("Survival TeamCommand still wired", "TeamCommandRouter(\n                plugin, TeamCommand(plugin)" in commands)
    check("command permission covers both systems", "COMMAND_PERMISSION = \"joshymc.team;joshymc.lifesteal.team.use\"" in router && "\"team\" to com.liam.joshymc.command.TeamCommandRouter.COMMAND_PERMISSION" in commands)
    check("Lifesteal store never touches Survival team tables", Regex("""\b(FROM|INTO|UPDATE|TABLE IF NOT EXISTS)\s+(teams|team_members|team_invites)\b""").find(store) == null)
    check("Survival TeamManager never touches Lifesteal team tables", "lifesteal_team" !in survivalManager)
    check("tables only created where Lifesteal runs", manager.indexOf("if (!hosted)") in 0 until manager.indexOf("store.createTables()"))
    check("listener only registered where Lifesteal runs", "if (plugin.lifestealTeamManager.hosted) {\n            pm.registerEvents(com.liam.joshymc.lifesteal.teams.LifestealTeamListener" in listeners)
    check("manager refreshed at startup and reload", main.split("lifestealTeamManager.refresh()").size == 3)
    check("shutdown step registered", "shutdown.step(\"lifestealTeamManager.stop\", { ::lifestealTeamManager.isInitialized })" in main)
    check("shutdown waits for in-flight changes", "awaitTermination(WORKER_WAIT_SECONDS" in manager)

    // Permissions.
    for ((node, default) in listOf("joshymc.lifesteal.team.use" to "true", "joshymc.lifesteal.team.create" to "true", "joshymc.lifesteal.team.admin" to "op")) {
        check("$node in plugin.yml (default $default)", Regex("""  ${Regex.escape(node)}:\n    description: [^\n]+\n    default: $default""").containsMatchIn(pluginYml))
    }
    check("every player action checks the use permission", "!player.hasPermission(PERMISSION_USE)" in manager && "playerProblem(player)?.let" in manager)
    check("create checks the create permission", "if (!player.hasPermission(PERMISSION_CREATE)) return Msg.noPermission(player)" in manager)
    check("admin actions check the admin permission", "!sender.hasPermission(PERMISSION_ADMIN) -> Msg.NO_PERMISSION" in manager && "if (!teams.canAdmin(sender)) return Msg.noPermission(sender)" in command)
    check("player actions run every check in the manager", manager.split("playerProblem(player)?.let { return refuse(player, it) }").size - 1 == 8)
    check("leadership is checked against the team, not a permission", "team.leader != player.uniqueId" in manager)

    // GUI authorization: buttons only call manager actions (which re-check), never the store.
    check("menu never touches the store", "store." !in menu && "LifestealTeamStore(" !in menu)
    for (call in listOf("teams.transfer(it, name, confirmed = true)", "teams.kick(it, name)", "teams.disband(it, confirmed = true)", "teams.leave(it)", "teams.accept(p, team.name)", "teams.deny(p, team.name)")) {
        check("menu action goes through the manager: $call", call in menu)
    }
    check("destructive menu actions confirm first", menu.split("confirm(p, ").size - 1 >= 4)
    check("commands confirm disband and transfer", "confirmed(args, 1)" in command && "confirmed(args, 2)" in command)

    // Concurrency plumbing and sessions.
    check("changes run on one worker thread", "Executors.newSingleThreadExecutor" in manager)
    check("one change per player at a time", "if (!busy.add(actorKey))" in manager)
    check("rapid commands are throttled", "now - last < ACTION_COOLDOWN_MS" in manager)
    check("older snapshots never overwrite newer ones", "old.version > team.version" in manager && "retiredAt >= team.version" in manager)
    check("session state dropped on quit", "lastAction.remove(player.uniqueId)" in manager)
    check("membership doesn't live in the session", "fun onQuit(player: Player) {\n        lastAction.remove(player.uniqueId)\n    }" in manager)

    // Identity: UUIDs only, names are never identifiers, no blocking name lookups.
    check("no Bukkit.getOfflinePlayer(String)", Regex("""getOfflinePlayer\((name|targetName|\w*Name)\)""").find(manager + command + menu) == null)
    check("targets resolved through PlayerResolver", "plugin.playerResolver.online(targetName)" in manager && "plugin.playerResolver.resolve(name)" in manager)
    check("Bedrock players get typed commands too (no clickable chat)", "Bedrock clients can't click chat" in manager)

    // Never touches hearts, inventories or balances.
    for (forbidden in listOf("lifesteal_hearts", "economyManager", "inventory.clear", "heartManager")) {
        check("team code never touches $forbidden", forbidden !in manager && forbidden !in store)
    }
    // Heart stealing/friendly fire stay out of scope (the blueprint defines no team PvP rule).
    check("heart system has no team rule", "lifestealTeamManager" !in source("lifesteal/hearts/LifestealHeartManager.kt"))

    // Ranked Team Wars / Elo are later issues: no Elo values are created here.
    check("no Elo columns", Regex("""\w*elo\w*\s+(INTEGER|REAL)""", RegexOption.IGNORE_CASE).find(store) == null)
}
