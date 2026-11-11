// Checks the Ranked Team Wars spectate fix (issue #1511, audit finding D-L7): the Bukkit-free
// RankedSpectateRules and the wiring that puts CombatTeleportGuard, the permission check and the
// destination check in front of the spectator teleport. The Gradle build has no test source set,
// so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/ranked-spectate-check.init.gradle.kts rankedSpectateCheck
//
// Exits non-zero on the first failed check. Live-server steps: docs/lifesteal-ranked.md, "Spectators".

import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.RankedSpectateRules
import com.liam.joshymc.lifesteal.ranked.RankedSpectateRules.Facts
import com.liam.joshymc.teleport.CombatTeleportRules
import java.io.File
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

/** A request that passes every rule. */
private val OK = Facts(
    matchId = "rm-1", state = MatchState.ACTIVE, arenaId = "desert", participant = false, pendingRestore = false,
    dead = false, teleportProblem = null, hasSpectatorPoint = true, worldLoaded = true,
)

private fun between(text: String, start: String, end: String): String {
    val a = text.indexOf(start)
    val b = text.indexOf(end, a + start.length)
    check("found '$start'", a >= 0 && b > a)
    return text.substring(a, b)
}

fun main() {
    // ── Rules ───────────────────────────────────────────────
    // 1. An authorized, untagged, free player watching a running match.
    check("valid request allowed", RankedSpectateRules.problem(OK) == null)
    check("valid arrival allowed", RankedSpectateRules.arrivalProblem(OK, chunkLoaded = true, destinationProblem = null) == null)
    for (s in listOf(MatchState.CREATED, MatchState.VALIDATING, MatchState.PREPARING, MatchState.COUNTDOWN, MatchState.ACTIVE)) {
        check("$s is spectatable", RankedSpectateRules.spectatable(s) && RankedSpectateRules.problem(OK.copy(state = s)) == null)
    }

    // 3/4. Combat-tagged: CombatTeleportGuard's own message, before anything else about the match.
    val tagged = OK.copy(teleportProblem = CombatTeleportRules.MSG_IN_COMBAT)
    check("tagged refused", RankedSpectateRules.problem(tagged) == CombatTeleportRules.MSG_IN_COMBAT)
    check("tagged refused even for a bad match id", RankedSpectateRules.problem(tagged.copy(state = null)) == CombatTeleportRules.MSG_IN_COMBAT)
    check("tagged refused at arrival", RankedSpectateRules.arrivalProblem(tagged, true, null) == CombatTeleportRules.MSG_IN_COMBAT)

    // 7. Participants of a match (this one or another).
    check("participant refused", RankedSpectateRules.problem(OK.copy(participant = true)) == RankedSpectateRules.MSG_PARTICIPANT)
    // 8. A ranked inventory still waiting to be restored.
    check("pending restore refused", RankedSpectateRules.problem(OK.copy(pendingRestore = true)) == RankedSpectateRules.MSG_PENDING_RESTORE)
    check("dead refused", RankedSpectateRules.problem(OK.copy(dead = true)) == RankedSpectateRules.MSG_DEAD)

    // 9/10. Unknown, cancelled, completed, finishing.
    check("unknown id refused", RankedSpectateRules.problem(OK.copy(state = null, matchId = "nope")) == RankedSpectateRules.noMatch("nope"))
    check("cancelled refused", RankedSpectateRules.problem(OK.copy(state = MatchState.CANCELLED)) == RankedSpectateRules.noMatch("rm-1"))
    check("completed refused", RankedSpectateRules.problem(OK.copy(state = MatchState.COMPLETED)) == RankedSpectateRules.noMatch("rm-1"))
    check("finishing refused", RankedSpectateRules.problem(OK.copy(state = MatchState.FINISHING)) == RankedSpectateRules.MSG_FINISHING)

    // 11. Unavailable destination.
    check("no spectator point refused", RankedSpectateRules.problem(OK.copy(hasSpectatorPoint = false)) == RankedSpectateRules.noPoint("desert"))
    check("world not loaded refused", RankedSpectateRules.problem(OK.copy(worldLoaded = false)) == RankedSpectateRules.MSG_WORLD)
    check("chunk load failure refused", RankedSpectateRules.arrivalProblem(OK, chunkLoaded = false, destinationProblem = null) == RankedSpectateRules.MSG_CHUNK)
    check("unsafe point refused", RankedSpectateRules.arrivalProblem(OK, true, "dangerous floor") == RankedSpectateRules.unsafe("dangerous floor"))
    check("a match that ended while loading is refused at arrival",
        RankedSpectateRules.arrivalProblem(OK.copy(state = MatchState.CANCELLED), true, null) == RankedSpectateRules.noMatch("rm-1"))
    check("tagged while loading is refused at arrival", RankedSpectateRules.arrivalProblem(tagged, true, null) != null)

    // ── Wiring ──────────────────────────────────────────────
    val manager = source("lifesteal/ranked/LifestealRankedManager.kt")
    val command = source("lifesteal/ranked/LifestealRankedCommand.kt")
    val guard = source("teleport/CombatTeleportGuard.kt")
    val pluginYml = File("src/main/resources/plugin.yml").readText()

    val spectate = between(manager, "fun spectate(staff: Player", "fun unspectate(")
    val facts = between(manager, "private fun spectateFacts(", "fun spectate(staff: Player")

    // CombatTeleportGuard is the combat check (19: the guard itself still blocks tagged/held players).
    check("facts use CombatTeleportGuard.problem", facts.contains("CombatTeleportGuard.problem(plugin, player)"))
    check("facts read pending restores", facts.contains("hasPendingRestore(player.uniqueId)"))
    check("facts read participation", facts.contains("participant = holds(player)"))
    check("guard.blocks goes through problem", between(guard, "fun blocks(", "fun problem(").contains("problem(plugin, player)"))
    check("guard still refuses held players", between(guard, "fun problem(", "const val MSG_RANKED_MATCH").contains("lifestealRankedManager.holds(player)"))
    check("guard still refuses tagged players", guard.contains("CombatTeleportRules.allowsRequest(plugin.combatManager.isTagged(player))"))

    // 5/6. Nothing happens before both checks pass; the tag is never touched.
    val firstCheck = spectate.indexOf("RankedSpectateRules.problem(")
    val arrival = spectate.indexOf("RankedSpectateRules.arrivalProblem(")
    val tp = spectate.indexOf("teleport(player, target)")
    val register = spectate.indexOf("spectating[uuid] = match.id")
    check("checked before the chunk load", firstCheck in 0 until spectate.indexOf("getChunkAtAsync"))
    check("re-checked before the teleport", arrival in 0 until tp)
    check("destination checked with SafeSpots", spectate.contains("SafeSpots.problem(target)"))
    check("registered only after the teleport succeeded", register > tp && spectate.indexOf("ok != true") in tp until register)
    check("spectate never touches the combat tag", !Regex("untag|clearTag|combatManager").containsMatchIn(spectate))
    // 15/16. Spectating never changes rosters, hearts or ratings.
    check("spectate never touches rosters, hearts or Elo", !Regex("roster|heart|rating|transferPvp", RegexOption.IGNORE_CASE).containsMatchIn(spectate))
    check("spectators still take and deal no damage", manager.contains("inOtherMatch = holds(uuid) || isSpectator(uuid)") &&
        source("lifesteal/ranked/LifestealRankedListener.kt").contains("if (ranked.isSpectator(victim.uniqueId)) return true"))

    // 10. A match that ended while the teleport ran is never watched (same object, still spectatable).
    check("same match object re-checked", spectate.contains("matches[matchId] === match") && spectate.contains("matches[matchId] !== match"))

    // 18. No duplicate registrations: one request at a time, the old registration removed first.
    check("one request at a time", spectate.contains("RankedSpectateRules.MSG_IN_PROGRESS"))
    check("old registration removed before the new one",
        spectate.indexOf("spectating.remove(uuid)?.let { matches[it]?.spectators?.remove(uuid) }") in 0 until register)
    check("match spectators are a set", manager.contains("val spectators = LinkedHashSet<UUID>()"))

    // 12-14. Exit, end of match, disconnect, shutdown.
    val unspectate = between(manager, "fun unspectate(", "// ── Records")
    check("unspectate removes both entries", unspectate.contains("spectating.remove(staff.uniqueId)") && unspectate.contains("spectators?.remove(staff.uniqueId)"))
    val release = between(manager, "private fun release(", "private fun broadcast(")
    check("match end releases spectators", release.contains("spectating.remove(uuid)") && release.contains("match.spectators.clear()"))
    val quit = between(manager, "fun onQuit(", "val match = matchOf(uuid)")
    check("quit drops spectating and pending requests", quit.contains("spectating.remove(uuid)") && quit.contains("spectateRequests.remove(uuid)"))
    val stop = between(manager, "fun stop()", "// ── Match creation")
    check("shutdown clears spectators and requests", stop.contains("spectating.clear()") && stop.contains("spectateRequests.clear()"))
    check("a request cleared by shutdown/quit does nothing", spectate.contains("if (!spectateRequests.remove(uuid)) return@Runnable"))

    // 2/17. The permission is checked in the executor (every alias and the joshymc: name reach it).
    val execute = between(command, "fun execute(sender: CommandSender", "// ── Arenas")
    check("spectate needs admin or spectate permission",
        execute.contains("LifestealRankedManager.ADMIN_PERMISSION") && execute.contains("spectateOnly && sender.hasPermission(LifestealRankedManager.SPECTATE_PERMISSION)"))
    check("permission checked before routing", execute.indexOf("Msg.noPermission(sender)") in 0 until execute.indexOf("ranked(sender, sub, rest)"))
    val specPerm = between(pluginYml, "  joshymc.lifesteal.ranked.spectate:\n    description", "  joshymc.lifesteal.ranks.use:")
    check("spectate permission stays op-only", specPerm.contains("default: op"))
    check("command reports the async result", command.contains("ranked.spectate(player, id) { problem ->"))

    println("RankedSpectateCheck: $checks checks passed.")
}
