// Checks the combat lock on player-initiated teleports (issue #1356): the Bukkit-free rules for
// requests and warmups, and that every player teleport path found in the audit consults the
// combat tag before it moves the player. The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/combat-teleport-check.init.gradle.kts combatTeleportCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.teleport.CombatTeleportRules
import com.liam.joshymc.teleport.CombatTeleportRules.WarmupStep
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

private const val SRC = "src/main/kotlin/com/liam/joshymc"

private fun source(path: String): String = File("$SRC/$path").readText()

/** The body of `fun <name>(` up to the next top-level-ish `fun ` (good enough for these files). */
private fun body(text: String, name: String): String {
    val start = text.indexOf("fun $name(")
    check("found fun $name", start >= 0)
    val next = text.indexOf("\n    fun ", start + 1).let { a ->
        val b = text.indexOf("\n    private fun ", start + 1)
        listOf(a, b).filter { it > 0 }.minOrNull() ?: text.length
    }
    return text.substring(start, next)
}

/** True when [guard] appears in [code] before the first occurrence of [action]. */
private fun before(code: String, guard: String, action: String): Boolean {
    val g = code.indexOf(guard)
    val a = code.indexOf(action)
    return g >= 0 && a >= 0 && g < a
}

fun main() {
    // ── Messages ───────────────────────────────────────────
    check("request message", CombatTeleportRules.MSG_IN_COMBAT == "You cannot teleport while in combat!")
    check("cancel message", CombatTeleportRules.MSG_ENTERED_COMBAT == "Teleport cancelled — you entered combat!")

    // ── Requests (e.g. /team home in and out of combat) ────
    check("tagged player can't request a teleport", !CombatTeleportRules.allowsRequest(tagged = true))
    check("untagged player can request a teleport", CombatTeleportRules.allowsRequest(tagged = false))
    // A tag that expired is reported as untagged by CombatManager.isTagged, so it's allowed again.
    check("expired tag allows teleporting", CombatTeleportRules.allowsRequest(tagged = false))

    // ── Warmups ────────────────────────────────────────────
    fun step(online: Boolean = true, moved: Boolean = false, tagged: Boolean = false, finished: Boolean = false) =
        CombatTeleportRules.warmupStep(online, moved, tagged, finished)
    check("countdown continues out of combat", step() == WarmupStep.CONTINUE)
    check("requested before combat, never tagged: teleports", step(finished = true) == WarmupStep.TELEPORT)
    check("combat starting mid-countdown cancels", step(tagged = true) == WarmupStep.CANCEL_COMBAT)
    check("tagged at the last step never teleports", step(tagged = true, finished = true) == WarmupStep.CANCEL_COMBAT)
    check("moving cancels", step(moved = true) == WarmupStep.CANCEL_MOVED)
    check("moving wins over combat (one message)", step(moved = true, tagged = true) == WarmupStep.CANCEL_MOVED)
    check("disconnecting during the warmup cancels silently", step(online = false, tagged = true, finished = true) == WarmupStep.CANCEL_OFFLINE)
    for (online in listOf(true, false)) for (moved in listOf(true, false)) for (finished in listOf(true, false)) {
        check("tagged never teleports ($online/$moved/$finished)", step(online, moved, tagged = true, finished = finished) != WarmupStep.TELEPORT)
    }

    // ── Wiring: every audited path checks combat before moving the player ──
    val team = body(source("command/TeamCommand.kt"), "handleHome")
    check("/team home checks combat before teleporting", before(team, "CombatTeleportGuard.blocks(plugin, player)", "player.teleport(home)"))
    check("/team home checks combat before the PvP-world guard", before(team, "CombatTeleportGuard.blocks", "PvpTeleportGuard.blocks"))
    // Aliases (/t, /teams), `joshymc:team` and the GUI button all reach this one handler.
    check("team GUI home button runs /team home", source("gui/team/TeamHomeGui.kt").contains("dispatchCommand(p, \"team home\")"))
    check("team router hands Survival senders to TeamCommand", source("command/TeamCommandRouter.kt").contains("TeamCommand"))

    val claim = body(source("gui/claim/ClaimManagementGui.kt"), "teleportToClaim")
    check("claim GUI teleport checks combat", before(claim, "CombatTeleportGuard.blocks(plugin, player)", "player.teleport(destination)"))

    val resource = source("manager/ResourceWorldManager.kt")
    check("/resource checks combat on request", before(body(resource, "teleportToResource"), "CombatTeleportGuard.blocks", "findSafeSpot("))
    val search = body(resource, "findSafeSpot")
    check("/resource fallback re-checks combat", before(search, "enteredCombat(player)", "player.teleport(world.spawnLocation)"))
    check("/resource safe spot re-checks combat", before(search.substringAfter("val safeLoc"), "enteredCombat(player)", "player.teleport(safeLoc)"))

    val rtp = body(source("command/RtpCommand.kt"), "startWarmup")
    val rtpWarmup = rtp.substringAfter("if (skipWarmup) {").substringAfter("return\n        }")
    check("/rtp warmup checks combat before starting", before(rtpWarmup, "CombatTeleportGuard.blocks", "BukkitRunnable"))
    check("/rtp warmup checks combat every tick", before(rtpWarmup, "CombatTeleportRules.warmupStep", "player.teleport(destination)"))

    val essentials = source("command/EssentialsCommands.kt")
    check("checkAndApply uses the combat guard", body(essentials, "checkAndApply").contains("CombatTeleportGuard.blocks(plugin, player)"))
    val warmup = body(essentials, "teleportWithWarmup")
    check("warmup checks combat on request (also for staff without warmup)", before(warmup, "CombatTeleportGuard.blocks", "teleportNow("))
    check("warmup checks combat on every step", before(warmup, "CombatTeleportRules.warmupStep", "WarmupStep.TELEPORT ->"))
    check("warmup is one tracked task", warmup.contains("scheduleSyncRepeatingTask") && !warmup.contains("scheduleSyncDelayedTask"))

    // Commands that already used checkAndApply keep doing so.
    for ((file, label) in listOf(
        "command/HomeCommand.kt" to "/home", "command/SpawnCommand.kt" to "/spawn", "command/WarpCommand.kt" to "/warp",
        "command/PlayerWarpCommand.kt" to "/pwarp", "command/RtpCommand.kt" to "/rtp", "command/TpaCommand.kt" to "/tpa",
    )) check("$label still runs checkAndApply", source(file).contains("TeleportChecks.checkAndApply("))

    // Network transfers (/lobby, /hub, /survival, /lifesteal) are refused while tagged.
    check("transfers block combat-tagged players",
        source("network/transfer/TransferManager.kt").contains("plugin.combatManager.isTagged(player) }) list += TransferBlocker.COMBAT_TAGGED"))

    // Lifesteal shares CombatManager's tag; it has no team home or team teleport to guard.
    val lifestealTeams = File("$SRC/lifesteal/teams").listFiles()!!.filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    check("Lifesteal teams have no teleport", !lifestealTeams.contains(".teleport(") && !lifestealTeams.contains("teleportAsync("))

    println("Combat teleport checks passed ($checks checks).")
}
