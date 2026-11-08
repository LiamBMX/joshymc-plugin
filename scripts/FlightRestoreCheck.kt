// Checks /fly persistence across reconnects and restarts (issue #1470): the Bukkit-free rules in
// FlightRestore and the wiring that stores the choice and restores it. The Gradle build has no
// test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/flight-restore-check.init.gradle.kts flightRestoreCheck
//
// No server is started. Exits non-zero on the first failed check.

import com.liam.joshymc.flight.FlightRestore
import com.liam.joshymc.flight.FlightRestore.Block
import com.liam.joshymc.flight.FlightRestore.Decision
import com.liam.joshymc.flight.FlightRestore.Input
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

private fun input(
    preference: Boolean? = true,
    creative: Boolean = false,
    allowed: Boolean = false,
    block: Block? = null,
    gap: Int = 0,
) = Input(preference, creative, allowed, block, gap)

fun main() {
    // 1-3, 6: enabled /fly, reconnect (or restart: the choice is stored) → flight comes back.
    check("on the ground: flight back, not flying", FlightRestore.decide(input(gap = 0)) == Decision.Restore(flying = false))
    check("airborne: flight back and flying, so no drop", FlightRestore.decide(input(gap = 1)) == Decision.Restore(flying = true))
    check("high up: flight back and flying", FlightRestore.decide(input(gap = FlightRestore.MAX_GAP)) == Decision.Restore(flying = true))

    // 4-5: turned /fly off → stays grounded; never toggled (old players) → untouched.
    check("turned off stays off", FlightRestore.decide(input(preference = false, gap = 4)) == Decision.Leave)
    check("no saved choice is untouched", FlightRestore.decide(input(preference = null, gap = 4)) == Decision.Leave)

    // 7-9, 14, 16: no flight without access, wherever it's restricted.
    for (block in Block.entries) {
        check("$block on the ground: no flight", FlightRestore.decide(input(block = block, gap = 0)) == Decision.Denied(block))
        check("$block high up: only fall protection", FlightRestore.decide(input(block = block, gap = FlightRestore.DANGEROUS_GAP)) == Decision.ProtectFall(block))
        check("$block never restores", FlightRestore.decide(input(block = block, gap = 1)) !is Decision.Restore)
    }
    check("a short drop isn't protected", FlightRestore.decide(input(block = Block.NO_PERMISSION, gap = FlightRestore.DANGEROUS_GAP - 1)) is Decision.Denied)

    // 11-12: Creative/Spectator and flight from another source are never touched.
    check("creative untouched", FlightRestore.decide(input(creative = true, gap = 4)) == Decision.Leave)
    check("already allowed untouched (no flicker)", FlightRestore.decide(input(allowed = true, gap = 4)) == Decision.Leave)

    // 13: repeated toggles/reconnects write only on a change.
    check("first toggle writes", FlightRestore.needsWrite(null, true))
    check("same value doesn't write", !FlightRestore.needsWrite(true, true) && !FlightRestore.needsWrite(false, false))
    check("changed value writes", FlightRestore.needsWrite(true, false) && FlightRestore.needsWrite(false, true))

    // 15: the fall protection is narrow.
    check("protection cancels a fall in its window", FlightRestore.cancelsFall(1_000L, 999L, combatTagged = false))
    check("protection expires", !FlightRestore.cancelsFall(1_000L, 1_000L, combatTagged = false))
    check("protection never helps in combat", !FlightRestore.cancelsFall(1_000L, 0L, combatTagged = true))
    check("no protection without an entry", !FlightRestore.cancelsFall(null, 0L, combatTagged = false))
    check("protection is short", FlightRestore.FALL_PROTECTION_MILLIS in 1L..30_000L)

    // Wiring.
    val manager = source("flight/FlightTimeManager.kt")
    check("choice stored by UUID", "CREATE TABLE IF NOT EXISTS player_flight_preferences" in manager && "uuid TEXT PRIMARY KEY" in manager)
    check("write only on change", "FlightRestore.needsWrite(preferences[uuid], enabled)" in manager)
    check("restore needs /fly access", "!player.hasPermission(\"joshymc.fly\") && !hasTemporaryFlight(player)" in manager)
    check("restore blocked in combat, arenas, events, ranked",
        listOf("combatManager.isTagged(player)", "playersInArena.containsKey", "isInEventWorld(player)", "lifestealRankedManager.holds(player)").all { it in manager })
    check("restore respects the pvp and spawn world rules",
        "PvpWorldFlightListener.PERMISSION" in manager && "SpawnWorldManager.FLY_PERMISSION" in manager)
    check("restore respects gamemode locks", "isAllowed(GamemodeScope.SURVIVAL, player.world)" in manager)
    val restore = manager.substringAfter("fun restoreFlight(player: Player, protectFall: Boolean)").substringBefore("fun restoreFlightOnJoin")
    check("restore never turns flight off", "allowFlight = false" !in restore && "isFlying = false" !in restore)
    check("restore never teleports", "teleport" !in restore)
    check("shutdown doesn't clear the choice", "player_flight_preferences" !in manager.substringAfter("fun stop()").substringBefore("// ── Balance"))

    val listener = source("listener/FlightTimeListener.kt")
    val join = listener.substringAfter("fun onJoin(").substringBefore("@EventHandler")
    check("join restores after flight time is settled",
        join.indexOf("flightTimeManager.onJoin(") in 0 until join.indexOf("restoreFlightOnJoin("))
    check("only fall damage is protected", "DamageCause.FALL" in listener)
    check("gamemode switches restore", "PlayerGameModeChangeEvent" in listener)

    val fly = source("command/EssentialsCommands.kt")
    check("/fly records the choice", "flightTimeManager.setFlightPreference(target.uniqueId, target.allowFlight)" in fly)

    println("OK: $checks flight restore checks passed.")
}
