// Checks the Ranked Team Wars team roster / ownership lock (issue #1530): the Bukkit-free
// RankedTeamLock rules and the wiring that puts them in front of every Lifesteal team change
// (player, GUI and /team admin paths), plus the existing roster-snapshot attribution they rely on.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/ranked-team-lock-check.init.gradle.kts rankedTeamLockCheck
//
// Exits non-zero on the first failed check. Live-server steps: docs/lifesteal-ranked-beta.md, section N.

import com.liam.joshymc.lifesteal.ranked.MatchParticipant
import com.liam.joshymc.lifesteal.ranked.MatchRoster
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.teams.RankedTeamLock
import com.liam.joshymc.lifesteal.teams.RankedTeamLock.Change
import com.liam.joshymc.lifesteal.teams.RankedTeamLock.Facts
import com.liam.joshymc.lifesteal.teams.RankedTeamLock.Stage
import java.io.File
import java.util.UUID
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()

private fun between(text: String, start: String, end: String): String {
    val a = text.indexOf(start)
    val b = text.indexOf(end, a + start.length)
    check("found '$start'", a >= 0 && b > a)
    return text.substring(a, b)
}

private fun refused(change: Change, facts: Facts, admin: Boolean = false) = RankedTeamLock.refusal(change, facts, admin) != null

fun main() {
    val player = UUID.randomUUID()
    val free = Facts()

    // ── Ownership transfers (1-7) ───────────────────────────
    check("1. transfer outside ranked allowed", !refused(Change.TRANSFER, free))
    check("1. admin transfer outside ranked allowed", !refused(Change.TRANSFER, free, admin = true))
    for (stage in Stage.entries) {
        check("2-6. transfer refused at $stage", refused(Change.TRANSFER, Facts(team = stage)))
        check("7. admin transfer refused at $stage", refused(Change.TRANSFER, Facts(team = stage), admin = true))
    }
    val inMatch = RankedTeamLock.refusal(Change.TRANSFER, Facts(team = Stage.MATCH))!!
    check("transfer message is understandable", inMatch.startsWith("Team ownership can't be changed during a ranked match."))
    val adminMsg = RankedTeamLock.refusal(Change.TRANSFER, Facts(team = Stage.MATCH, matchId = "rm-7"), admin = true)!!
    check("admin message points at the cancel workflow", adminMsg.contains("/lifesteal admin ranked cancel rm-7 confirm"))
    check("queued message points at /ranked leave", RankedTeamLock.refusal(Change.TRANSFER, Facts(team = Stage.QUEUED))!!.contains("/ranked leave"))
    check("admin queued message points at cancelqueue", RankedTeamLock.refusal(Change.TRANSFER, Facts(team = Stage.CONFIRMING), admin = true)!!.contains("cancelqueue"))

    // ── Membership (8-15) ───────────────────────────────────
    for (stage in listOf(Stage.MATCH, Stage.PREPARING)) {
        check("8. join refused at $stage", refused(Change.ACCEPT, Facts(team = stage)))
        check("12. disband refused at $stage", refused(Change.DISBAND, Facts(team = stage)))
        check("12. admin disband refused at $stage", refused(Change.DISBAND, Facts(team = stage), admin = true))
    }
    for (stage in listOf(Stage.CONFIRMING, Stage.QUEUED)) {
        // The queue's own revalidation cancels the entry (hardProblem), so these stay allowed.
        check("queued team: join allowed at $stage", !refused(Change.ACCEPT, Facts(team = stage)))
        check("queued team: disband allowed at $stage (ends the entry)", !refused(Change.DISBAND, Facts(team = stage)))
        check("queued player leaving only cancels the entry at $stage", !refused(Change.LEAVE, Facts(team = stage)))
    }
    check("9. registered participant can't leave", refused(Change.LEAVE, Facts(team = Stage.MATCH, rosterPlayers = setOf(player))))
    check("10. registered participant can't be kicked", refused(Change.KICK, Facts(team = Stage.MATCH, rosterPlayers = setOf(player))))
    check("10. kick refused even when the team stage is unknown", refused(Change.KICK, Facts(rosterPlayers = setOf(player))))
    check("non-participant member may leave during the team's match", !refused(Change.LEAVE, Facts(team = Stage.MATCH)))
    check("non-participant member may be kicked during the team's match", !refused(Change.KICK, Facts(team = Stage.MATCH)))
    check("15. unrelated teams are free", Change.entries.none { refused(it, free) })
    check("leave message", RankedTeamLock.refusal(Change.LEAVE, Facts(rosterPlayers = setOf(player)))!!.contains("registered in a ranked match"))
    check("strongest picks the most binding stage", RankedTeamLock.strongest(Stage.QUEUED, null, Stage.PREPARING, Stage.CONFIRMING) == Stage.PREPARING)
    check("strongest of nothing is null", RankedTeamLock.strongest(null, null) == null)

    // ── Wiring: every team change goes through the guard (the GUI calls the same actions) ──
    val manager = source("lifesteal/teams/LifestealTeamManager.kt")
    check("transfer guarded", between(manager, "fun transfer(", "fun disband(").contains("RankedTeamLock.Change.TRANSFER"))
    check("disband guarded", between(manager, "fun disband(", "// ── Admin").contains("RankedTeamLock.Change.DISBAND"))
    check("accept guarded", between(manager, "fun accept(", "fun deny(").contains("RankedTeamLock.Change.ACCEPT"))
    check("leave guarded", between(manager, "fun leave(", "private fun leaderLeaveMessage").contains("RankedTeamLock.Change.LEAVE"))
    check("kick guarded", between(manager, "fun kick(", "fun transfer(").contains("RankedTeamLock.Change.KICK"))
    val adminTransfer = between(manager, "fun adminTransfer(", "fun canAdmin(")
    check("7. admin transfer guarded (admin = true)", adminTransfer.contains("RankedTeamLock.Change.TRANSFER, team.id, emptyList(), admin = true"))
    check("26. admin transfer refuses before anything is submitted", adminTransfer.indexOf("RankedTeamLock.Change.TRANSFER") < adminTransfer.indexOf("submit("))
    check("admin refusal audited", adminTransfer.contains("refuseAdmin(") && manager.contains("AdminAudit.log(plugin, sender, \"LIFESTEAL_TEAM_ADMIN_"))
    val adminDisband = between(manager, "fun adminDisband(", "fun adminTransfer(")
    check("admin disband guarded", adminDisband.contains("RankedTeamLock.Change.DISBAND") && adminDisband.indexOf("RankedTeamLock") < adminDisband.indexOf("submit("))
    check("player transfer refuses before the confirm prompt", between(manager, "fun transfer(", "fun disband(").let { it.indexOf("RankedTeamLock") < it.indexOf("if (!confirmed)") })
    check("no bypass permission", !File("src/main/kotlin/com/liam/joshymc/lifesteal/teams/RankedTeamLock.kt").readText().contains("hasPermission"))
    val menu = source("lifesteal/teams/LifestealTeamMenu.kt")
    check("GUI transfer goes through the manager action", menu.contains("teams.transfer(it, name, confirmed = true)"))
    check("GUI kick/disband go through the manager actions", menu.contains("teams.kick(it, name)") && menu.contains("teams.disband(it, confirmed = true)"))
    val teamStore = source("lifesteal/teams/LifestealTeamStore.kt")
    check("store transfers are only called from the manager", teamStore.contains("fun transfer(actor: Actor"))

    // ── Lifecycle sources: no stored lock, released with the match / entry (22-25) ──
    val ranked = source("lifesteal/ranked/LifestealRankedManager.kt")
    val rosterMatchOf = between(ranked, "fun rosterMatchOf(", "/** Matches whose arena")
    check("roster lookup ignores finished matches", rosterMatchOf.contains("!it.state.terminal"))
    check("roster lookup covers defeated participants (roster, not held)", rosterMatchOf.contains("it.roster.of(uuid) != null"))
    check("teamMatch ignores finished matches", between(ranked, "fun teamMatch(", "fun rosterMatchOf(").contains("!it.state.terminal"))
    check("23. FINISHING still counts as open (released only at COMPLETED)", !MatchState.FINISHING.terminal && MatchState.COMPLETED.terminal && MatchState.CANCELLED.terminal)
    check("all live states lock", MatchState.entries.filter { !it.terminal }.containsAll(listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN, MatchState.ACTIVE, MatchState.FINISHING)))
    val queue = source("lifesteal/ranked/queue/LifestealRankedQueueManager.kt")
    val stage = between(queue, "fun teamLockStage(", "private fun stageOf(")
    check("queue stage ignores ended entries", stage.contains("!it.state.terminal"))
    check("queue stage covers staff test proposals", stage.contains("tests.proposals()"))
    val stageOf = between(queue, "private fun stageOf(", "/** Seconds before")
    check("terminal queue states release", stageOf.contains("QueueState.MATCHED, QueueState.CANCELLED, QueueState.EXPIRED -> null"))
    val lock = source("lifesteal/teams/RankedTeamLock.kt")
    check("25. nothing persisted (no table)", !lock.contains("CREATE TABLE") && !manager.contains("ranked_team_lock"))

    // ── Attribution comes from the immutable match roster (16-21, 27, 28) ──
    val a1 = UUID.randomUUID(); val a2 = UUID.randomUUID(); val b1 = UUID.randomUUID(); val b2 = UUID.randomUUID()
    val roster = MatchRoster(listOf(
        MatchParticipant(a1, com.liam.joshymc.lifesteal.ranked.Side.A, "team-a"),
        MatchParticipant(a2, com.liam.joshymc.lifesteal.ranked.Side.A, "team-a"),
        MatchParticipant(b1, com.liam.joshymc.lifesteal.ranked.Side.B, "team-b"),
        MatchParticipant(b2, com.liam.joshymc.lifesteal.ranked.Side.B, "team-b"),
    ))
    check("16. roster keeps its UUIDs", roster.uuids == listOf(a1, a2, b1, b2))
    check("17. each participant carries the team id it was registered with", roster.side(com.liam.joshymc.lifesteal.ranked.Side.A).all { it.teamId == "team-a" })
    check("21. sides come from the roster", roster.sameSide(a1, a2) == true && roster.sameSide(a1, b1) == false)
    val defeated = roster.defeat(a1, com.liam.joshymc.lifesteal.ranked.DefeatReason.DEATH)
    check("20. a defeated participant keeps their side", defeated != null && defeated.sameSide(a1, a2) == true && defeated.of(a1) != null)
    check("17. match keeps the team snapshot it was created with", Regex("val teamA: LifestealTeamStore.Team").containsMatchIn(ranked) && Regex("val teamB: LifestealTeamStore.Team").containsMatchIn(ranked))
    check("21. combat relation asks the match roster first", manager.indexOf("rankedSides(a, b)?.let") in 0 until manager.indexOf("TeamCombatRules.relation(a, b"))

    println("Ranked team lock checks passed ($checks checks).")
}
