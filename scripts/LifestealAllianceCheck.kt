// Checks Lifesteal team alliances and rivalries (issue #1405): the settings, the Bukkit-free
// relationship store against real SQLite (fresh temp directories, deleted afterwards), the alliance
// combat/heart/chat rules, concurrency, disband cleanup, leadership changes, restarts, and the
// Lifesteal-only wiring. The Gradle build has no test source set, so it runs through an init script,
// from the repo root:
//
//   ./gradlew -I scripts/lifesteal-alliance-check.init.gradle.kts lifestealAllianceCheck
//
// Exits non-zero on the first failed check. Real hits, chat and GUIs on a Paper server (and Bedrock
// clients) are NOT covered here (see docs/lifesteal-teams.md, "Manual tests").

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartRules.Decision
import com.liam.joshymc.lifesteal.hearts.HeartRules.changesHearts
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.hearts.HeartStore.PvpResult
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Actor
import com.liam.joshymc.lifesteal.teams.TeamCombatRules
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.DamageDecision
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.HeartCheck
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.Policy
import com.liam.joshymc.lifesteal.teams.TeamCombatRules.Relation
import com.liam.joshymc.lifesteal.teams.TeamRelationRules
import com.liam.joshymc.lifesteal.teams.TeamRelationStore
import com.liam.joshymc.lifesteal.teams.TeamRelationStore.Refusal
import com.liam.joshymc.lifesteal.teams.TeamRelationStore.Result
import com.liam.joshymc.manager.DatabaseManager
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
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
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private const val T0 = 1_800_000_000_000L
private const val HOUR = 3_600_000L
private const val DAY = 24 * HOUR

fun main() {
    settings()
    combatRules()
    heartRules()
    chatAndProfiles()
    requests()
    alliances()
    dissolution()
    rivalries()
    exclusivity()
    disbanding()
    leadership()
    restarts()
    concurrency()
    wiring()
    println("OK: $checks Lifesteal alliance and rivalry checks passed.")
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
    val teams = (map["teams"] as Map<String, Any?>).toMutableMap()
    edit(teams)
    map["teams"] = teams
    return map
}

@Suppress("UNCHECKED_CAST")
private fun withAlliances(edit: (MutableMap<String, Any?>) -> Unit): Map<String, Any?> = withTeams { teams ->
    val a = (teams["alliances"] as Map<String, Any?>).toMutableMap()
    edit(a)
    teams["alliances"] = a
}

private fun settings() {
    val parsed = parseWith(bundledLifestealYml())
    val teams = parsed.settings!!.teams
    check("bundled file loads without problems", parsed.problems.isEmpty())
    check("bundled alliances = defaults", teams.alliances == LifestealSettings.Alliances())
    check("defaults: enabled, 24h requests, 24h cooldown, protection on, chat on",
        teams.alliances.enabled && teams.alliances.requestExpirationHours == 24 && teams.alliances.dissolutionCooldownHours == 24 &&
            !teams.alliances.friendlyFire && teams.alliances.chatEnabled)
    check("rivalries enabled by default", teams.rivalriesEnabled)
    check("bundled teams = data class defaults", teams == LifestealSettings.Teams())
    check("request hours must be at least 1", parseWith(withAlliances { it["request-expiration-hours"] = 0 }).settings == null)
    check("request hours at most 168", parseWith(withAlliances { it["request-expiration-hours"] = 169 }).settings == null)
    check("cooldown hours at most 720", parseWith(withAlliances { it["dissolution-cooldown-hours"] = 721 }).settings == null)
    check("cooldown may be 0", parseWith(withAlliances { it["dissolution-cooldown-hours"] = 0 }).settings?.teams?.alliances?.dissolutionCooldownHours == 0)
    check("alliance friendly fire must be a boolean", parseWith(withAlliances { it["friendly-fire"] = "no" }).settings == null)
    check("alliance switch must be a boolean", parseWith(withAlliances { it["enabled"] = 1 }).settings == null)
    val off = parseWith(withAlliances { it["enabled"] = false }).settings?.teams?.alliances
    check("alliances can be turned off", off?.enabled == false)
    val old = parseWith(withTeams { it.remove("alliances"); it.remove("rivalries") })
    check("a file from before issue #1405 loads with the defaults", old.settings?.teams == LifestealSettings.Teams())
    check("that is one warning", old.problems.size == 1 && !old.problems[0].severe && "alliances.enabled" in old.problems[0].message)
    check("the one-alliance and one-rival limits are fixed rules",
        com.liam.joshymc.lifesteal.LifestealRules.MAX_ALLIANCES_PER_TEAM == 1 && com.liam.joshymc.lifesteal.LifestealRules.MAX_DECLARED_RIVALS_PER_TEAM == 1)
}

// ── Combat rules ─────────────────────────────────────────────

private val A = UUID.randomUUID()
private val B = UUID.randomUUID()

private val PROTECTED = Policy(friendlyFire = false, teammateHeartTransfers = false, allyProtection = true)
private val ALLY_FF = Policy(friendlyFire = false, teammateHeartTransfers = false, allyProtection = false)
private val TEAM_FF_ON = Policy(friendlyFire = true, teammateHeartTransfers = true, allyProtection = true)

private fun combatRules() {
    val allied: (String, String) -> Boolean = { x, y -> setOf(x, y) == setOf("t1", "t2") }
    check("different allied teams = allies", TeamCombatRules.withAlliance(Relation.NOT_TEAMMATES, "t1", "t2", allied) == Relation.ALLIES)
    check("unrelated teams stay not teammates", TeamCombatRules.withAlliance(Relation.NOT_TEAMMATES, "t1", "t3", allied) == Relation.NOT_TEAMMATES)
    check("a teamless player is never an ally", TeamCombatRules.withAlliance(Relation.NOT_TEAMMATES, "t1", null, allied) == Relation.NOT_TEAMMATES)
    check("teammates stay teammates", TeamCombatRules.withAlliance(Relation.TEAMMATES, "t1", "t1", allied) == Relation.TEAMMATES)
    check("recent teammates stay recent teammates", TeamCombatRules.withAlliance(Relation.RECENT_TEAMMATES, "t1", "t2", allied) == Relation.RECENT_TEAMMATES)
    check("unknown membership is never turned into allies", TeamCombatRules.withAlliance(Relation.UNKNOWN, "t1", "t2", allied) == Relation.UNKNOWN)
    check("after the alliance ends: not teammates at once", TeamCombatRules.withAlliance(Relation.NOT_TEAMMATES, "t1", "t2") { _, _ -> false } == Relation.NOT_TEAMMATES)

    check("allied melee/projectile hit blocked", TeamCombatRules.damage(A, B, Relation.ALLIES, PROTECTED) == DamageDecision.BLOCK_FRIENDLY_FIRE)
    check("allied hit blocked even with team friendly fire on", TeamCombatRules.damage(A, B, Relation.ALLIES, TEAM_FF_ON) == DamageDecision.BLOCK_FRIENDLY_FIRE)
    check("alliance friendly fire on lets allies fight", TeamCombatRules.damage(A, B, Relation.ALLIES, ALLY_FF) == DamageDecision.ALLOW)
    check("unrelated players can damage each other", TeamCombatRules.damage(A, B, Relation.NOT_TEAMMATES, PROTECTED) == DamageDecision.ALLOW)
    check("environment / mobs (no attacker) always hurt allies", TeamCombatRules.damage(null, B, Relation.ALLIES, PROTECTED) == DamageDecision.ALLOW)
    check("self-damage untouched", TeamCombatRules.damage(B, B, Relation.ALLIES, PROTECTED) == DamageDecision.ALLOW)
    check("settings unreadable: nothing cancelled", TeamCombatRules.damage(A, B, Relation.ALLIES, null) == DamageDecision.ALLOW)
    check("teammate rules unchanged (friendly fire off)", TeamCombatRules.damage(A, B, Relation.TEAMMATES, PROTECTED) == DamageDecision.BLOCK_FRIENDLY_FIRE)
    check("teammate rules unchanged (friendly fire on)", TeamCombatRules.damage(A, B, Relation.TEAMMATES, TEAM_FF_ON) == DamageDecision.ALLOW)
    check("harmful potions from an ally are held back", TeamCombatRules.blocksPotion(true, Relation.ALLIES, PROTECTED))
    check("helpful potions still reach allies", !TeamCombatRules.blocksPotion(false, Relation.ALLIES, PROTECTED))
    check("potions between unrelated players untouched", !TeamCombatRules.blocksPotion(true, Relation.NOT_TEAMMATES, PROTECTED))
    check("ranked: opposing sides are never allies", TeamCombatRules.rankedRelation(false) == Relation.NOT_TEAMMATES)
    check("ranked opposing allies can hit each other", TeamCombatRules.damage(A, B, TeamCombatRules.rankedRelation(false), PROTECTED) == DamageDecision.ALLOW)
}

// ── Hearts ───────────────────────────────────────────────────

private val LIMITS = HeartRules.Limits(starting = 10, minimum = 1, maximum = 20)

private fun heartRules() {
    check("allied kill: no heart (rule)", TeamCombatRules.heartCheck(Relation.ALLIES, PROTECTED) == HeartCheck.ALLIES)
    check("allied kill: no heart even with alliance friendly fire on", TeamCombatRules.heartCheck(Relation.ALLIES, ALLY_FF) == HeartCheck.ALLIES)
    check("unrelated kill: eligible", TeamCombatRules.heartCheck(Relation.NOT_TEAMMATES, PROTECTED) == HeartCheck.ELIGIBLE)
    check("ranked opposing sides: eligible (hearts at risk)", TeamCombatRules.heartCheck(TeamCombatRules.rankedRelation(false), PROTECTED) == HeartCheck.ELIGIBLE)
    check("allies decision", HeartRules.decide(10, 10, 0, LIMITS, team = HeartCheck.ALLIES) == Decision.Allies)
    check("allies decision moves no heart", !Decision.Allies.changesHearts)
    withDb("hearts") { db, _ ->
        val store = HeartStore(db)
        store.createTables()
        store.ensure(A, 10, T0); store.ensure(B, 10, T0)
        val r = store.transferPvp(HeartStore.PvpRequest("ally-death", A, B, T0, 30 * 60_000L, LIMITS, team = HeartCheck.ALLIES))
        check("allied kill in the real heart store: no heart moved", r is PvpResult.Done && r.decision == Decision.Allies && store.load(A)!!.hearts == 10 && store.load(B)!!.hearts == 10)
        check("allied kill: audited as a rejection", store.audit(B).any { it.operation == "PVP_REJECTED" && it.reason == "ALLIES" })
        check("allied kill: no cooldown started", store.lastTransferAt(A, B) == null)
        check("allied kill: deduplicated", store.transferPvp(HeartStore.PvpRequest("ally-death", A, B, T0, 30 * 60_000L, LIMITS)) == PvpResult.Duplicate)
        val after = store.transferPvp(HeartStore.PvpRequest("after-dissolve", A, B, T0 + 1, 30 * 60_000L, LIMITS))
        check("after dissolution normal heart stealing resumes", after is PvpResult.Done && after.decision == Decision.Transfer(9, 11))
    }
}

// ── Chat and profiles ────────────────────────────────────────

private fun chatAndProfiles() {
    val rosters = mapOf("t1" to listOf(A), "t2" to listOf(B), "t3" to listOf(UUID.randomUUID()))
    val roster: (String) -> List<UUID> = { rosters[it].orEmpty() }
    val allies = mapOf("t1" to "t2", "t2" to "t1")
    val recipients = TeamRelationRules.chatRecipients("t1", { allies[it] }, roster)
    check("alliance chat reaches both allied teams", recipients.toSet() == setOf(A, B))
    check("alliance chat never reaches unrelated teams", rosters["t3"]!!.none { it in recipients })
    check("no alliance: nobody (message refused)", TeamRelationRules.chatRecipients("t3", { allies[it] }, roster).isEmpty())
    check("former allies receive nothing", TeamRelationRules.chatRecipients("t1", { null }, roster).isEmpty())
    check("inconsistent one-sided state delivers nothing", TeamRelationRules.chatRecipients("t1", { if (it == "t1") "t2" else null }, roster).isEmpty())
    check("only current leaders manage", TeamRelationRules.canManage(A, A) && !TeamRelationRules.canManage(A, B) && !TeamRelationRules.canManage(A, null))
    val snap = TeamRelationStore.Snapshot(
        alliances = listOf(TeamRelationStore.Alliance("x", "t1", "t2", T0)),
        rivalries = listOf(
            TeamRelationStore.Rivalry("r1", "t1", "t3", A, T0),
            TeamRelationStore.Rivalry("r2", "t3", "t1", A, T0),
            TeamRelationStore.Rivalry("r3", "t2", "t3", B, T0),
        ),
    )
    val p1 = TeamRelationRules.profile(snap, "t1")
    check("profile: ally", p1.allyId == "t2")
    check("profile: declared rival", p1.declaredRivalId == "t3")
    check("profile: mutual only when both declared", p1.mutual())
    val p2 = TeamRelationRules.profile(snap, "t2")
    check("profile: one-sided rivalry isn't mutual", p2.declaredRivalId == "t3" && !p2.mutual())
    val p3 = TeamRelationRules.profile(snap, "t3")
    check("profile: declared against is separate from declared by", p3.declaredRivalId == "t1" && p3.rivalOfIds.toSet() == setOf("t1", "t2"))
    check("refusal text fills the time", "1h 0m" in TeamRelationRules.refusalText(Refusal.OWN_COOLDOWN, T0 + HOUR, T0, null))
    check("refusal text fills the team", "Wolves" in TeamRelationRules.refusalText(Refusal.REVERSE_PENDING, null, T0, "Wolves"))
}

// ── Store fixtures ───────────────────────────────────────────

private fun <T> withDb(name: String, block: (DatabaseManager, File) -> T): T {
    val dir = Files.createTempDirectory("joshymc-alliance-$name").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            return block(db, dir)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

private class Fixture(val db: DatabaseManager) {
    val relations = TeamRelationStore(db)
    val teams = LifestealTeamStore(db, relations)
    val leaders = HashMap<String, UUID>()
    val ids = HashMap<String, String>()

    init {
        teams.createTables()
        relations.createTables()
    }

    /** Creates team [name] led by a fresh UUID, optionally with [members] extra members. */
    fun team(name: String, members: List<UUID> = emptyList()): String {
        val leader = UUID.randomUUID()
        val created = teams.create(UUID.randomUUID().toString(), name, leader, name + "Leader", T0) as LifestealTeamStore.CreateResult.Created
        for (m in members) {
            teams.invite(Actor(leader, "l"), m, 5, T0, 60_000)
            teams.accept(m, "m", name, 5, T0)
        }
        leaders[name] = leader
        ids[name] = created.team.id
        return created.team.id
    }

    fun leader(name: String) = leaders.getValue(name)
    fun id(name: String) = ids.getValue(name)
    fun request(from: String, to: String, now: Long = T0, ttl: Long = DAY) = relations.requestAlliance(leader(from), from, id(to), now, ttl)
    fun accept(by: String, from: String, now: Long = T0) = relations.acceptAlliance(leader(by), by, id(from), now)
    fun snap() = relations.snapshot()
    fun activeRowsReferencing(teamId: String): Int =
        (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_alliances WHERE status = 'ACTIVE' AND (team_a = ? OR team_b = ?)", teamId, teamId) { it.getInt("n") } ?: 0) +
            (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_alliance_slots WHERE team_id = ?", teamId) { it.getInt("n") } ?: 0) +
            (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_rivalries WHERE status = 'ACTIVE' AND (declarer_team = ? OR target_team = ?)", teamId, teamId) { it.getInt("n") } ?: 0) +
            (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_alliance_requests WHERE status = 'PENDING' AND (source_team = ? OR target_team = ?)", teamId, teamId) { it.getInt("n") } ?: 0) +
            (db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_alliance_cooldowns WHERE team_id = ?", teamId) { it.getInt("n") } ?: 0)
}

private fun refusal(result: Result): Refusal? = (result as? Result.Refused)?.refusal

// ── Requests ─────────────────────────────────────────────────

private fun requests() = withDb("requests") { db, _ ->
    val f = Fixture(db)
    val memberA = UUID.randomUUID()
    val memberB = UUID.randomUUID()
    f.team("Alpha", listOf(memberA)); f.team("Bravo", listOf(memberB)); f.team("Charlie"); f.team("Delta"); f.team("Echo")

    val sent = f.request("Alpha", "Bravo")
    check("leader sends a request", sent is Result.RequestSent)
    val request = (sent as Result.RequestSent).request
    check("request has id, source, target, times", request.id.isNotBlank() && request.source == f.id("Alpha") && request.target == f.id("Bravo") &&
        request.createdAt == T0 && request.expiresAt == T0 + DAY)
    check("ordinary member can't send a request", refusal(f.relations.requestAlliance(memberA, "m", f.id("Charlie"), T0, DAY)) == Refusal.NOT_LEADER)
    check("teamless player can't send a request", refusal(f.relations.requestAlliance(UUID.randomUUID(), "x", f.id("Charlie"), T0, DAY)) == Refusal.NOT_IN_TEAM)
    check("no self-alliance", refusal(f.request("Alpha", "Alpha")) == Refusal.SELF_ALLIANCE)
    check("duplicate request refused", refusal(f.request("Alpha", "Bravo")) == Refusal.ALREADY_REQUESTED)
    check("duplicate made no second pending row", f.snap().requests.count { it.source == f.id("Alpha") && it.target == f.id("Bravo") } == 1)
    check("reverse request points to accepting", refusal(f.request("Bravo", "Alpha")) == Refusal.REVERSE_PENDING)
    check("gone team refused", refusal(f.relations.requestAlliance(f.leader("Alpha"), "a", "no-such-team", T0, DAY)) == Refusal.TEAM_GONE)
    check("unauthorized member can't accept", refusal(f.relations.acceptAlliance(memberB, "m", f.id("Alpha"), T0)) == Refusal.NOT_LEADER)
    check("another team's leader can't accept it", refusal(f.accept("Charlie", "Alpha")) == Refusal.NO_REQUEST)
    check("no alliance before approval", f.snap().alliances.isEmpty())

    // Anti-spam: at most three outgoing, and a wait after a refusal.
    check("2nd outgoing ok", f.request("Alpha", "Charlie") is Result.RequestSent)
    check("3rd outgoing ok", f.request("Alpha", "Delta") is Result.RequestSent)
    check("4th outgoing refused", refusal(f.request("Alpha", "Echo")) == Refusal.TOO_MANY_OUTGOING)
    check("decline works", f.relations.declineAlliance(f.leader("Delta"), "d", f.id("Alpha"), T0) is Result.Declined)
    check("declined request is gone", f.snap().pending(f.id("Alpha"), f.id("Delta"), T0) == null)
    val again = f.request("Alpha", "Delta", now = T0 + 1_000)
    check("re-sending right after a decline is refused with a time", refusal(again) == Refusal.RECENTLY_REFUSED && (again as Result.Refused).until == T0 + TeamRelationStore.RESEND_AFTER_REFUSAL_MILLIS)
    check("after the wait it can be sent", f.request("Alpha", "Delta", now = T0 + TeamRelationStore.RESEND_AFTER_REFUSAL_MILLIS) is Result.RequestSent)
    check("source cancels", f.relations.cancelAlliance(f.leader("Alpha"), "a", f.id("Delta"), T0 + TeamRelationStore.RESEND_AFTER_REFUSAL_MILLIS + 1) is Result.Cancelled)
    check("target can't cancel someone else's request", refusal(f.relations.cancelAlliance(f.leader("Charlie"), "c", f.id("Alpha"), T0)) == Refusal.NO_OUTGOING_REQUEST)

    // Expiry (persisted timestamps).
    val late = T0 + DAY
    val expiredAccept = f.accept("Charlie", "Alpha", now = late)
    check("request expires after 24 hours", refusal(expiredAccept) == Refusal.EXPIRED)
    check("expired request can't be accepted later either", refusal(f.accept("Charlie", "Alpha", now = late + 1)) == Refusal.NO_REQUEST)
    val swept = f.relations.expireRequests(late)
    check("expiry sweep returns the remaining expired request", swept.map { it.target } == listOf(f.id("Bravo")))
    check("sweep is idempotent", f.relations.expireRequests(late).isEmpty())
    check("nothing left pending", f.snap().requests.isEmpty())
    check("no alliance came out of expired requests", f.snap().alliances.isEmpty() && f.relations.slotCount() == 0)
    check("expiry was audited", db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_audit WHERE action = 'ALLY_REQUEST_EXPIRED'") { it.getInt("n") }!! >= 2)
}

// ── Alliances ────────────────────────────────────────────────

private fun alliances() = withDb("alliances") { db, _ ->
    val f = Fixture(db)
    f.team("Alpha"); f.team("Bravo"); f.team("Charlie"); f.team("Delta")
    f.request("Alpha", "Bravo"); f.request("Charlie", "Bravo"); f.request("Alpha", "Delta"); f.request("Delta", "Charlie")
    val allied = f.accept("Bravo", "Alpha")
    check("receiving leader accepts", allied is Result.Allied)
    allied as Result.Allied
    check("alliance between the two teams", f.snap().allied(f.id("Alpha"), f.id("Bravo")) && f.relations.slotCount() == 2)
    check("other requests involving either team were invalidated",
        allied.invalidated.map { it.source to it.target }.toSet() == setOf(f.id("Charlie") to f.id("Bravo"), f.id("Alpha") to f.id("Delta")))
    check("unrelated request untouched", f.snap().pending(f.id("Delta"), f.id("Charlie"), T0) != null)
    check("Alpha can't ally with Charlie", refusal(f.request("Alpha", "Charlie")) == Refusal.OWN_ALLIANCE)
    check("Bravo can't ally with Delta", refusal(f.request("Bravo", "Delta")) == Refusal.OWN_ALLIANCE)
    check("Charlie can't ask allied Alpha", refusal(f.request("Charlie", "Alpha")) == Refusal.TARGET_ALLIANCE)
    check("already allied", refusal(f.request("Alpha", "Bravo")) == Refusal.ALREADY_ALLIED)
    check("an invalidated request can't be accepted", refusal(f.accept("Bravo", "Charlie")) == Refusal.NO_REQUEST)
    check("alliance creation audited for both teams",
        db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_audit WHERE action IN ('ALLY_ACCEPT', 'ALLY_FORMED')") { it.getInt("n") } == 2)
    // The slot PRIMARY KEY makes a second alliance impossible even if every check above were skipped.
    val second = runCatching {
        db.transaction {
            db.execute("INSERT INTO lifesteal_team_alliances (id, team_a, team_b, status, created_at) VALUES ('x', ?, ?, 'ACTIVE', 0)", f.id("Alpha"), f.id("Charlie"))
            db.execute("INSERT INTO lifesteal_team_alliance_slots (team_id, alliance_id) VALUES (?, 'x')", f.id("Alpha"))
        }
    }
    check("database refuses a second alliance slot", second.isFailure && f.snap().alliances.size == 1)
    val selfInsert = runCatching { db.execute("INSERT INTO lifesteal_team_alliances (id, team_a, team_b, status, created_at) VALUES ('s', ?, ?, 'ACTIVE', 0)", f.id("Alpha"), f.id("Alpha")) }
    check("database refuses a self-alliance", selfInsert.isFailure)
}

// ── Dissolution and cooldowns ────────────────────────────────

private fun dissolution() = withDb("dissolve") { db, _ ->
    val f = Fixture(db)
    val memberA = UUID.randomUUID()
    f.team("Alpha", listOf(memberA)); f.team("Bravo"); f.team("Charlie")
    f.request("Alpha", "Bravo"); f.accept("Bravo", "Alpha")
    check("ordinary member can't dissolve", refusal(f.relations.dissolveAlliance(memberA, "m", T0 + 1, DAY)) == Refusal.NOT_LEADER)
    check("a leader of an unrelated team can't dissolve it", refusal(f.relations.dissolveAlliance(f.leader("Charlie"), "c", T0 + 1, DAY)) == Refusal.NO_ALLIANCE)
    val at = T0 + 14 * HOUR
    val dissolved = f.relations.dissolveAlliance(f.leader("Bravo"), "b", at, DAY)
    check("either leader (here the accepting one) dissolves without approval", dissolved is Result.Dissolved && (dissolved as Result.Dissolved).byTeam == f.id("Bravo"))
    check("alliance removed immediately", !f.snap().allied(f.id("Alpha"), f.id("Bravo")) && f.relations.slotCount() == 0)
    check("both teams get the 24h cooldown", f.snap().cooldownUntil(f.id("Alpha"), at) == at + DAY && f.snap().cooldownUntil(f.id("Bravo"), at) == at + DAY)
    check("dissolving twice is refused", refusal(f.relations.dissolveAlliance(f.leader("Alpha"), "a", at + 5, DAY)) == Refusal.NO_ALLIANCE)
    check("a second dissolve doesn't restart the cooldown", f.snap().cooldownUntil(f.id("Alpha"), at + 5) == at + DAY)
    check("exactly one dissolution audited per team",
        db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_audit WHERE action = 'ALLY_DISSOLVE'") { it.getInt("n") } == 2)
    val own = f.request("Alpha", "Charlie", now = at + HOUR)
    check("cooldown blocks a new alliance, with the time", refusal(own) == Refusal.OWN_COOLDOWN && (own as Result.Refused).until == at + DAY)
    check("cooldown blocks being asked too", refusal(f.request("Charlie", "Bravo", now = at + HOUR)) == Refusal.TARGET_COOLDOWN)
    check("same two teams can't re-ally during cooldown", refusal(f.request("Alpha", "Bravo", now = at + HOUR)) == Refusal.OWN_COOLDOWN)
    check("ending an alliance doesn't stop an immediate rivalry", f.relations.declareRival(f.leader("Alpha"), "a", f.id("Bravo"), at + 1) is Result.RivalDeclared)
    check("cooldown ends exactly 24h later (2 PM to 2 PM)", f.request("Alpha", "Charlie", now = at + DAY) is Result.RequestSent)
}

// ── Rivalries ────────────────────────────────────────────────

private fun rivalries() = withDb("rivals") { db, _ ->
    val f = Fixture(db)
    val memberA = UUID.randomUUID()
    f.team("Alpha", listOf(memberA)); f.team("Bravo"); f.team("Charlie")
    val declared = f.relations.declareRival(f.leader("Alpha"), "a", f.id("Bravo"), T0)
    check("leader declares a rivalry without approval", declared is Result.RivalDeclared && f.snap().rivalOf(f.id("Alpha"))?.target == f.id("Bravo"))
    check("member can't declare", refusal(f.relations.declareRival(memberA, "m", f.id("Charlie"), T0)) == Refusal.NOT_LEADER)
    check("duplicate declaration refused", refusal(f.relations.declareRival(f.leader("Alpha"), "a", f.id("Bravo"), T0)) == Refusal.ALREADY_RIVAL)
    check("one outgoing rivalry per team", refusal(f.relations.declareRival(f.leader("Alpha"), "a", f.id("Charlie"), T0)) == Refusal.OWN_RIVAL)
    check("no self-rivalry", refusal(f.relations.declareRival(f.leader("Alpha"), "a", f.id("Alpha"), T0)) == Refusal.SELF_RIVALRY)
    check("target team keeps its own declaration slot", f.relations.declareRival(f.leader("Bravo"), "b", f.id("Charlie"), T0) is Result.RivalDeclared)
    check("one-sided: Bravo didn't declare Alpha", f.snap().rivalOf(f.id("Bravo"))?.target == f.id("Charlie") && f.snap().rivalsAgainst(f.id("Alpha")).isEmpty())
    check("the target can't remove another team's declaration", refusal(f.relations.withdrawRival(f.leader("Charlie"), "c", T0)) == Refusal.NO_RIVAL &&
        f.snap().rivalOf(f.id("Bravo")) != null)
    check("member can't withdraw", refusal(f.relations.withdrawRival(memberA, "m", T0)) == Refusal.NOT_LEADER)
    check("declaring leader withdraws", f.relations.withdrawRival(f.leader("Alpha"), "a", T0 + 1) is Result.RivalWithdrawn && f.snap().rivalOf(f.id("Alpha")) == null)
    check("withdrawn rivalry kept as history", db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_rivalries WHERE status = 'WITHDRAWN'") { it.getInt("n") } == 1)
    check("no rivalry cooldown", f.relations.declareRival(f.leader("Alpha"), "a", f.id("Charlie"), T0 + 2) is Result.RivalDeclared)
    // Labels only: no rewards, scores or statistics anywhere in the relationship code.
    val code = source("lifesteal/teams/TeamRelationStore.kt") + source("lifesteal/teams/LifestealTeamRelations.kt")
    check("rivalries give no coins, hearts, Elo or stats",
        listOf("economyManager", ".deposit(", "HeartStore", "lifestealHeartManager", "lifestealRatingManager", "RatingStore", "recordKill", "killStreak").none { it in code })
    check("rivalries never change PvP (no rival relation in the combat rules)", "rival" !in source("lifesteal/teams/TeamCombatRules.kt").lowercase())
    check("rating code doesn't read rivalries", "rival" !in File("src/main/kotlin/com/liam/joshymc/lifesteal/ranked").walkTopDown().filter { it.isFile }.joinToString { it.readText() }.lowercase())
}

// ── Exclusivity ──────────────────────────────────────────────

private fun exclusivity() = withDb("exclusive") { db, _ ->
    val f = Fixture(db)
    f.team("Alpha"); f.team("Bravo"); f.team("Charlie"); f.team("Delta")
    f.relations.declareRival(f.leader("Alpha"), "a", f.id("Bravo"), T0)
    check("rival teams can't ally (declarer asks)", refusal(f.request("Alpha", "Bravo")) == Refusal.RIVALRY)
    check("rivalry in the other direction blocks too", refusal(f.request("Bravo", "Alpha")) == Refusal.RIVALRY)
    // A rivalry appearing while a request is pending invalidates it.
    f.request("Charlie", "Delta")
    val rival = f.relations.declareRival(f.leader("Delta"), "d", f.id("Charlie"), T0 + 1)
    check("rivalry declared while a request is pending", rival is Result.RivalDeclared && (rival as Result.RivalDeclared).invalidated.size == 1)
    check("that request can't be accepted any more", refusal(f.accept("Delta", "Charlie", T0 + 2)) == Refusal.NO_REQUEST)
    check("no alliance and a rivalry: never both", !f.snap().allied(f.id("Charlie"), f.id("Delta")))
    // Allies can't be declared rivals; the alliance is never dissolved automatically.
    f.relations.withdrawRival(f.leader("Alpha"), "a", T0 + 3)
    f.request("Alpha", "Bravo", now = T0 + 4); f.accept("Bravo", "Alpha", T0 + 4)
    check("allied team can't be declared rival", refusal(f.relations.declareRival(f.leader("Alpha"), "a", f.id("Bravo"), T0 + 5)) == Refusal.RIVAL_IS_ALLY)
    check("the other ally can't either", refusal(f.relations.declareRival(f.leader("Bravo"), "b", f.id("Alpha"), T0 + 5)) == Refusal.RIVAL_IS_ALLY)
    check("alliance still standing (never auto-dissolved)", f.snap().allied(f.id("Alpha"), f.id("Bravo")))
    val trigger = runCatching {
        db.execute("INSERT INTO lifesteal_team_rivalries (id, declarer_team, target_team, declared_by, declared_at, status) VALUES ('t', ?, ?, ?, 0, 'ACTIVE')",
            f.id("Alpha"), f.id("Bravo"), UUID.randomUUID().toString())
    }
    check("database refuses a rivalry between allies", trigger.isFailure)
    val trigger2 = runCatching {
        db.execute("INSERT INTO lifesteal_team_alliances (id, team_a, team_b, status, created_at) VALUES ('t2', ?, ?, 'ACTIVE', 0)", f.id("Charlie"), f.id("Delta"))
    }
    check("database refuses an alliance between rivals", trigger2.isFailure)
    val snap = f.snap()
    check("no team pair is both allied and rivals", snap.alliances.none { snap.rivalryBetween(it.teamA, it.teamB) != null })
}

// ── Disbanding ───────────────────────────────────────────────

private fun disbanding() = withDb("disband") { db, _ ->
    val f = Fixture(db)
    for (n in listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")) f.team(n)
    f.request("Alpha", "Bravo"); f.accept("Bravo", "Alpha")
    f.relations.declareRival(f.leader("Alpha"), "a", f.id("Charlie"), T0)
    f.relations.declareRival(f.leader("Delta"), "d", f.id("Alpha"), T0)
    f.request("Echo", "Foxtrot")
    f.relations.requestAlliance(f.leader("Echo"), "e", f.id("Foxtrot"), T0, DAY) // duplicate, refused
    // Echo can't ask allied Alpha; give Alpha an incoming request from a fresh team before the alliance instead:
    val alphaId = f.id("Alpha")
    val result = f.teams.disband(Actor(f.leader("Alpha"), "a"), null, T0 + 10)
    check("disband succeeded", result is LifestealTeamStore.DisbandResult.Disbanded)
    val cleanup = (result as LifestealTeamStore.DisbandResult.Disbanded).relations!!
    check("alliance removed with the team", cleanup.alliance?.other(alphaId) == f.id("Bravo") && f.snap().allyOf(f.id("Bravo")) == null)
    check("outgoing rivalry removed", cleanup.outgoingRival?.target == f.id("Charlie"))
    check("incoming rivalries removed", cleanup.incomingRivals.map { it.declarer } == listOf(f.id("Delta")) && f.snap().rivalOf(f.id("Delta")) == null)
    check("surviving ally gets NO cooldown", f.snap().cooldownUntil(f.id("Bravo"), T0 + 10) == null)
    check("surviving ally can ally again immediately", f.request("Bravo", "Echo", now = T0 + 11) is Result.RequestSent)
    check("no orphaned relationship rows remain", f.activeRowsReferencing(alphaId) == 0)
    check("ended alliance kept as history", f.relations.allianceStatus(cleanup.alliance!!.id) == "ENDED_DISBANDED")
    check("a duplicate cleanup finds nothing", f.relations.cleanupDisbanded(alphaId, null, T0 + 12).empty)
    check("a second disband is refused by the team store", f.teams.disband(Actor(UUID.randomUUID(), "x", admin = true), alphaId, T0 + 13) == LifestealTeamStore.DisbandResult.TeamGone)

    // Pending requests involving a disbanded team are invalidated in the same transaction.
    val echoId = f.id("Echo")
    val pendingIds = f.snap().requests.filter { it.involves(echoId) }.map { it.id }
    check("Echo had pending requests", pendingIds.size == 2)
    val echoGone = f.teams.disband(Actor(f.leader("Echo"), "e"), null, T0 + 20) as LifestealTeamStore.DisbandResult.Disbanded
    check("pending requests removed on disband", echoGone.relations!!.requests.map { it.id }.toSet() == pendingIds.toSet() && pendingIds.all { f.relations.requestStatus(it) == "INVALIDATED" })
    check("nothing orphaned for Echo", f.activeRowsReferencing(echoId) == 0)

    // Disbanding a team on cooldown deletes its cooldown row; the old store without relations still works.
    f.request("Charlie", "Delta", now = T0 + 30); f.accept("Delta", "Charlie", T0 + 30)
    f.relations.dissolveAlliance(f.leader("Charlie"), "c", T0 + 31, DAY)
    val charlieId = f.id("Charlie")
    f.teams.disband(Actor(f.leader("Charlie"), "c"), null, T0 + 32)
    check("disbanded team's cooldown row deleted", f.activeRowsReferencing(charlieId) == 0)
    check("the surviving team keeps its own voluntary cooldown", f.snap().cooldownUntil(f.id("Delta"), T0 + 32) == T0 + 31 + DAY)

    // Startup repair of a stale reference (a team whose row was disbanded outside the store).
    f.request("Delta", "Foxtrot", now = T0 + 40 + DAY)
    db.execute("UPDATE lifesteal_teams SET status = 'DISBANDED' WHERE id = ?", f.id("Foxtrot"))
    val repaired = f.relations.repairOrphans(T0 + 41 + DAY)
    check("repair removes relationships of teams that no longer exist", repaired.map { it.teamId } == listOf(f.id("Foxtrot")) && f.activeRowsReferencing(f.id("Foxtrot")) == 0)
    check("repair is idempotent", f.relations.repairOrphans(T0 + 42 + DAY).isEmpty())
}

// ── Leadership ───────────────────────────────────────────────

private fun leadership() = withDb("leaders") { db, _ ->
    val f = Fixture(db)
    val newLeader = UUID.randomUUID()
    f.team("Alpha", listOf(newLeader)); f.team("Bravo"); f.team("Charlie"); f.team("Delta")
    f.request("Alpha", "Bravo")
    f.request("Charlie", "Alpha")
    f.relations.declareRival(f.leader("Alpha"), "a", f.id("Delta"), T0)
    val oldLeader = f.leader("Alpha")
    check("transfer", f.teams.transfer(Actor(oldLeader, "a"), null, newLeader, T0 + 1) is LifestealTeamStore.TransferResult.Transferred)
    val snap = f.snap()
    check("pending requests survive a leader change", snap.pending(f.id("Alpha"), f.id("Bravo"), T0 + 1) != null && snap.pending(f.id("Charlie"), f.id("Alpha"), T0 + 1) != null)
    check("rivalry survives a leader change", snap.rivalOf(f.id("Alpha"))?.target == f.id("Delta"))
    check("former leader can't accept", refusal(f.relations.acceptAlliance(oldLeader, "a", f.id("Charlie"), T0 + 2)) == Refusal.NOT_LEADER)
    check("former leader can't withdraw the rivalry", refusal(f.relations.withdrawRival(oldLeader, "a", T0 + 2)) == Refusal.NOT_LEADER)
    check("former leader can't cancel", refusal(f.relations.cancelAlliance(oldLeader, "a", f.id("Bravo"), T0 + 2)) == Refusal.NOT_LEADER)
    check("new leader can accept", f.relations.acceptAlliance(newLeader, "n", f.id("Charlie"), T0 + 3) is Result.Allied)
    check("alliance preserved through a second transfer", f.teams.transfer(Actor(newLeader, "n"), null, oldLeader, T0 + 4) is LifestealTeamStore.TransferResult.Transferred &&
        f.snap().allied(f.id("Alpha"), f.id("Charlie")))
    check("leader again can dissolve", f.relations.dissolveAlliance(oldLeader, "a", T0 + 5, DAY) is Result.Dissolved)
    val until = f.snap().cooldownUntil(f.id("Alpha"), T0 + 5)
    f.teams.transfer(Actor(oldLeader, "a"), null, newLeader, T0 + 6)
    check("cooldown unchanged by a leader change", f.snap().cooldownUntil(f.id("Alpha"), T0 + 6) == until)
    check("new leader is still on cooldown (no bypass)", refusal(f.relations.requestAlliance(newLeader, "n", f.id("Bravo"), T0 + 7, DAY)) == Refusal.OWN_COOLDOWN)
}

// ── Restarts ─────────────────────────────────────────────────

private fun restarts() {
    val dir = Files.createTempDirectory("joshymc-alliance-restart").toFile()
    try {
        val names = HashMap<String, String>()
        val leaders = HashMap<String, UUID>()
        run {
            val db = DatabaseManager(dir, quietLogger()); db.start()
            val f = Fixture(db)
            for (n in listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo")) f.team(n)
            f.request("Alpha", "Bravo"); f.accept("Bravo", "Alpha")
            f.request("Charlie", "Delta")
            f.request("Echo", "Delta", now = T0 + 2_000); f.relations.declineAlliance(f.leader("Delta"), "d", f.id("Echo"), T0 + 2_000)
            f.relations.declareRival(f.leader("Echo"), "e", f.id("Alpha"), T0)
            names.putAll(f.ids); leaders.putAll(f.leaders)
            db.shutdown()
        }
        val db = DatabaseManager(dir, quietLogger()); db.start()
        try {
            val f = Fixture(db) // createTables again: IF NOT EXISTS, nothing duplicated
            f.ids.putAll(names); f.leaders.putAll(leaders)
            val snap = f.snap()
            check("alliance survives a restart", snap.allied(names["Alpha"]!!, names["Bravo"]!!) && snap.alliances.size == 1 && f.relations.slotCount() == 2)
            check("request survives a restart with its original deadline", snap.pending(names["Charlie"]!!, names["Delta"]!!, T0)?.expiresAt == T0 + DAY)
            check("rivalry survives a restart", snap.rivalOf(names["Echo"]!!)?.target == names["Alpha"])
            check("requests keep counting during downtime: expired after 24h of real time", refusal(f.accept("Delta", "Charlie", now = T0 + DAY + 1)) == Refusal.EXPIRED)
            check("the decline wait survives a restart", refusal(f.request("Echo", "Delta", now = T0 + 3_000)) == Refusal.RECENTLY_REFUSED)
            f.relations.dissolveAlliance(leaders["Alpha"]!!, "a", T0 + 10, DAY)
        } finally {
            db.shutdown()
        }
        val db2 = DatabaseManager(dir, quietLogger()); db2.start()
        try {
            val f = Fixture(db2)
            check("cooldown survives a restart", f.snap().cooldownUntil(names["Alpha"]!!, T0 + 11) == T0 + 10 + DAY)
            f.ids.putAll(names); f.leaders.putAll(leaders)
            check("cooldown still blocks after a restart", refusal(f.request("Alpha", "Charlie", now = T0 + 12)) == Refusal.OWN_COOLDOWN)
            check("cooldown expires correctly after downtime", f.request("Alpha", "Charlie", now = T0 + 10 + DAY) is Result.RequestSent)
        } finally {
            db2.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

// ── Concurrency ──────────────────────────────────────────────

private fun <T> race(n: Int, task: (Int) -> T): List<T> {
    val pool = Executors.newFixedThreadPool(n)
    val start = CountDownLatch(1)
    val futures = (0 until n).map { i -> pool.submit<T> { start.await(); task(i) } }
    start.countDown()
    val results = futures.map { runCatching { it.get(30, TimeUnit.SECONDS) }.getOrNull() }
    pool.shutdown()
    @Suppress("UNCHECKED_CAST")
    return results as List<T>
}

private fun concurrency() = withDb("race") { db, _ ->
    val f = Fixture(db)
    for (n in listOf("Hub", "S1", "S2", "S3", "S4", "S5")) f.team(n)
    for (s in listOf("S1", "S2", "S3", "S4", "S5")) f.request(s, "Hub")
    val accepted = race(5) { i -> f.accept("Hub", "S${i + 1}", T0 + 1) }
    check("simultaneous acceptances create exactly one alliance", accepted.count { it is Result.Allied } == 1 && f.snap().alliances.size == 1 && f.relations.slotCount() == 2)
    check("the others were refused or invalidated", accepted.count { it is Result.Refused } == 4)

    val ally = f.snap().allyOf(f.id("Hub"))!!
    val allyName = f.ids.entries.first { it.value == ally }.key
    val dissolves = race(6) { i -> f.relations.dissolveAlliance(if (i % 2 == 0) f.leader("Hub") else f.leader(allyName), "x", T0 + 2, DAY) }
    check("simultaneous dissolutions end it once", dissolves.count { it is Result.Dissolved } == 1)
    check("cooldown started once", db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_team_audit WHERE action = 'ALLY_DISSOLVE'") { it.getInt("n") } == 2)

    // Rivalry declaration racing an acceptance between the same pair: never both.
    val g = Fixture(db)
    for (n in listOf("P1", "P2")) g.team(n)
    g.request("P1", "P2")
    val mixed = race(2) { i -> if (i == 0) g.accept("P2", "P1", T0 + 3) else g.relations.declareRival(g.leader("P1"), "p", g.id("P2"), T0 + 3) }
    val snap = g.snap()
    val both = snap.allied(g.id("P1"), g.id("P2")) && snap.rivalryBetween(g.id("P1"), g.id("P2")) != null
    check("accept vs rivalry race: exactly one wins", !both && mixed.count { it is Result.Allied || it is Result.RivalDeclared } == 1)

    // Disband racing an acceptance: no alliance may reference the disbanded team.
    val h = Fixture(db)
    for (n in listOf("Q1", "Q2")) h.team(n)
    h.request("Q1", "Q2")
    race(2) { i -> if (i == 0) h.accept("Q2", "Q1", T0 + 4) else h.teams.disband(Actor(h.leader("Q1"), "q"), null, T0 + 4) }
    check("disband vs accept race leaves nothing orphaned", h.activeRowsReferencing(h.id("Q1")) == 0 && h.snap().allyOf(h.id("Q2")) == null)

    // Many duplicate requests at once: one pending row.
    val k = Fixture(db)
    for (n in listOf("R1", "R2")) k.team(n)
    val dupes = race(8) { k.request("R1", "R2", T0 + 5) }
    check("duplicate requests at once: one request", dupes.count { it is Result.RequestSent } == 1 && k.snap().requests.count { it.source == k.id("R1") } == 1)
}

// ── Wiring (source checks) ───────────────────────────────────

private fun wiring() {
    val manager = source("lifesteal/teams/LifestealTeamManager.kt")
    val relations = source("lifesteal/teams/LifestealTeamRelations.kt")
    val menu = source("lifesteal/teams/TeamRelationsMenu.kt")
    val command = source("lifesteal/teams/LifestealTeamCommand.kt")
    val commands = source("manager/CommandManager.kt")
    val listener = source("lifesteal/teams/LifestealTeamCombatListener.kt")
    val pluginYml = resourceText("plugin.yml")

    check("ranked match sides decide before alliances", manager.indexOf("rankedSides(a, b)?.let") in 0 until manager.indexOf("TeamCombatRules.withAlliance"))
    check("alliances only count while enabled and loaded", "if (!alliancesInForce) return relation" in manager)
    check("protection policy follows the alliance settings", "allyProtection = it.alliances.enabled && !it.alliances.friendlyFire" in manager)
    check("relationship cache replaced after every commit", "if (result.isSuccess) refreshRelationsOnWorker()" in manager)
    check("disband cleanup runs inside the disband transaction", "relations?.cleanupDisbanded(id, actor.id, now)" in source("lifesteal/teams/LifestealTeamStore.kt"))
    check("startup repairs orphans and loads relations", "relationStore.repairOrphans(now)" in manager && "relationSnapshot = relationStore.snapshot()" in manager)
    check("combat listener no longer skips allies when team friendly fire is on", "if (policy.friendlyFire && !policy.allyProtection) return" in listener)
    check("alliance acceptance refused during combat", "teams.inCombat(it)" in relations && "COMBAT_MESSAGE" in relations)
    check("/ac respects gamemode mutes", "punishmentManager.isMutedIn(player.uniqueId, ServerGamemode.LIFESTEAL)" in relations)
    check("/ac respects the global chat mute", "plugin.chatManager.isMuted" in relations)
    check("/ac uses the profanity filter", "ProfanityFilter.censor(message)" in relations)
    check("/ac uses the repeated-message rule", "spam.record(" in relations)
    check("/ac honours /ignore", "IgnoreCommand.isIgnoring(uuid, player.uniqueId)" in relations)
    check("/ac re-reads the alliance right before delivery", "val current = teams.relationSnapshot" in relations)
    check("/ac only reaches players in Lifesteal", "!teams.isLifesteal(target)" in relations)
    check("/ac never broadcasts", "Bukkit.broadcast" !in relations && "getOnlinePlayers" !in relations)
    check("/ac is in plugin.yml", "\n  ac:\n" in pluginYml)
    check("/ac only registered where Lifesteal is hosted", "plugin.lifestealTeamManager.hosted" in commands && "unregisterCommand(\"ac\")" in commands)
    check("/ac is Lifesteal-scoped", com.liam.joshymc.gamemode.GamemodeAssignments.COMMANDS["ac"] == com.liam.joshymc.gamemode.GamemodeScope.LIFESTEAL)
    check("/team ally and /team rival are Lifesteal team subcommands", "\"ally\", \"alliance\" -> ally(" in command && "\"rival\", \"rivalry\" -> rival(" in command)
    check("GUI actions call the same re-checked actions", listOf("relations.acceptAlliance(", "relations.declineAlliance(", "relations.cancelAlliance(",
        "relations.dissolveAlliance(", "relations.declareRival(", "relations.withdrawRival(", "relations.requestAlliance(").all { it in menu })
    check("GUI re-reads teams by id when clicked", "teams.team(sourceId)" in menu && "teams.team(targetId)" in menu)
    check("GUI leader buttons re-check the current leader", "if (current.leader != p.uniqueId) return@setItem GuiStyle.errorSound(p)" in menu)
    check("profiles use the team cache as the member source", "team.members" in menu)
    check("GUIs use the shared GUI framework", "plugin.guiManager.open(" in menu && "CustomGui(" in menu)
    check("leader actions resolve exact team names only", "teams.teamByName(name)" in relations && "startsWith" !in relations)
    // Land / container isolation: nothing outside the team package reads alliances.
    val outside = File("src/main/kotlin/com/liam/joshymc").walkTopDown()
        .filter { it.isFile && it.extension == "kt" && !it.path.contains("/lifesteal/teams/") }
        .filter { "relationSnapshot" in it.readText() || "TeamRelationStore" in it.readText() || ".relations.allyOf" in it.readText() }
        .map { it.name }.toList()
    check("no claim, container or permission code grants allies access", outside.isEmpty())
    // Survival isolation.
    val survival = source("command/TeamCommand.kt") + source("manager/TeamManager.kt")
    check("Survival teams never see alliances", "alliance" !in survival.lowercase() && "rival" !in survival.lowercase())
    check("relationship tables only created by the Lifesteal team manager", "relationStore.createTables()" in manager &&
        File("src/main/kotlin/com/liam/joshymc").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .count { "lifesteal_team_alliances" in it.readText() } == 1)
    check("no new admin bypass permission", "joshymc.lifesteal.team.ally" !in pluginYml && "alliance.bypass" !in pluginYml)
    check("no server-wide announcement of relationship changes", "Bukkit.broadcast" !in relations && "broadcastAnnouncement" !in relations)
}
