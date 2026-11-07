// Checks Fly Voucher → LuckPerms flight (issue #1408): the Bukkit-free grant/stacking/recovery
// rules in FlyVoucherGrants, voucher signatures and durations, and the wiring that keeps the
// voucher until LuckPerms confirmed. The Gradle build has no test source set, so it runs
// through an init script, from the repo root:
//
//   ./gradlew -I scripts/fly-voucher-check.init.gradle.kts flyVoucherCheck
//
// No LuckPerms or server is started: the LuckPerms node list is modelled with
// FlyVoucherGrants.FlyNode, exactly what LuckPermsFlyGrant hands the rules.
// Exits non-zero on the first failed check.

import com.liam.joshymc.flight.FlightTime
import com.liam.joshymc.flight.FlyVoucherGrants
import com.liam.joshymc.flight.FlyVoucherGrants.FlyNode
import com.liam.joshymc.flight.FlyVoucherGrants.Plan
import com.liam.joshymc.flight.FlyVoucherGrants.Recovery
import com.liam.joshymc.flight.SurvivalFlight
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeScope
import java.io.File
import java.time.Duration
import java.time.Instant
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

private const val H = 3_600L
private const val D = 86_400L

/** What LuckPermsFlyGrant does with a Grant: drop the replaced nodes, add one new temporary true node. */
private fun apply(nodes: List<FlyNode>, plan: Plan): List<FlyNode> = when (plan) {
    is Plan.Grant -> nodes.filterNot { FlyVoucherGrants.replaced(it) } + FlyNode(true, plan.expiresAt)
    else -> nodes
}

private fun source(path: String) = File("src/main/kotlin/com/liam/joshymc/$path").readText()

fun main() {
    val now = 1_800_000_000L

    // ── Node and command ───────────────────────────────────
    check("the only node is joshymc.fly", FlyVoucherGrants.NODE == "joshymc.fly")
    val who = UUID.fromString("00000000-0000-0000-0000-000000000001")
    check("command equivalent is settemp ... true", FlyVoucherGrants.commandEquivalent(who, 3600) ==
        "lp user 00000000-0000-0000-0000-000000000001 permission settemp joshymc.fly true 3600s")
    check("now rounds up to whole seconds", FlyVoucherGrants.nowSeconds(1_001) == 2L && FlyVoucherGrants.nowSeconds(1_000) == 1L)

    // ── First voucher ──────────────────────────────────────
    val first = FlyVoucherGrants.extend(emptyList(), H, now)
    check("1h voucher grants 1h", first == Plan.Grant(now + H, null))
    check("1d voucher grants 1d", FlyVoucherGrants.extend(emptyList(), D, now) == Plan.Grant(now + D, null))
    check("7d voucher grants 7d", FlyVoucherGrants.extend(emptyList(), 7 * D, now) == Plan.Grant(now + 7 * D, null))
    check("1h30m voucher grants 1h30m", FlyVoucherGrants.extend(emptyList(), 5_400, now) == Plan.Grant(now + 5_400, null))
    val afterFirst = apply(emptyList(), first)
    check("the grant is temporary and true", afterFirst == listOf(FlyNode(true, now + H)))
    check("no permanent node is ever created", afterFirst.none { it.expiresAt == null })

    // ── Stacking ───────────────────────────────────────────
    val twoHoursLeft = listOf(FlyNode(true, now + 2 * H))
    val stacked = FlyVoucherGrants.extend(twoHoursLeft, H, now)
    check("2h left + 1h voucher = 3h", stacked == Plan.Grant(now + 3 * H, now + 2 * H))
    val afterStack = apply(twoHoursLeft, stacked)
    check("stacking leaves one node, not two overlapping", afterStack == listOf(FlyNode(true, now + 3 * H)))
    var nodes = emptyList<FlyNode>()
    repeat(5) { nodes = apply(nodes, FlyVoucherGrants.extend(nodes, H, now)) }
    check("five 1h vouchers in a row = 5h, one node", nodes == listOf(FlyNode(true, now + 5 * H)))
    val overlapping = listOf(FlyNode(true, now + H), FlyNode(true, now + 4 * H), FlyNode(true, now + 2 * H))
    check("stacks on the latest of overlapping nodes", (FlyVoucherGrants.extend(overlapping, H, now) as Plan.Grant).expiresAt == now + 5 * H)
    check("overlapping nodes are merged into one", apply(overlapping, FlyVoucherGrants.extend(overlapping, H, now)).size == 1)
    val expired = listOf(FlyNode(true, now - 10))
    check("expired leftovers aren't stacked on", FlyVoucherGrants.extend(expired, H, now) == Plan.Grant(now + H, null))
    check("expired leftovers are cleaned up", apply(expired, FlyVoucherGrants.extend(expired, H, now)) == listOf(FlyNode(true, now + H)))
    check("never less than advertised", (FlyVoucherGrants.extend(twoHoursLeft, 90, now) as Plan.Grant).expiresAt - now >= 2 * H + 90)
    check("old JoshyMC balance carried over", FlyVoucherGrants.extend(emptyList(), H, now, carryOverUntil = now + 2 * H) == Plan.Grant(now + 3 * H, null))
    check("longer LuckPerms node wins over the old balance", (FlyVoucherGrants.extend(listOf(FlyNode(true, now + 5 * H)), H, now, now + 2 * H) as Plan.Grant).expiresAt == now + 6 * H)
    check("an ended old balance adds nothing", FlyVoucherGrants.extend(emptyList(), H, now, carryOverUntil = now - 100) == Plan.Grant(now + H, null))

    // ── Refusals ───────────────────────────────────────────
    check("permanent flight refuses (voucher kept)", FlyVoucherGrants.extend(listOf(FlyNode(true, null)), H, now) == Plan.Permanent)
    check("permanent + temporary refuses", FlyVoucherGrants.extend(listOf(FlyNode(true, null), FlyNode(true, now + H)), H, now) == Plan.Permanent)
    check("staff deny is never overridden", FlyVoucherGrants.extend(listOf(FlyNode(false, null)), H, now) == Plan.Denied)
    check("temporary deny is never overridden", FlyVoucherGrants.extend(listOf(FlyNode(false, now + H)), H, now) == Plan.Denied)
    check("expired deny no longer applies", FlyVoucherGrants.extend(listOf(FlyNode(false, now - 1)), H, now) == Plan.Grant(now + H, null))
    check("zero duration refused", FlyVoucherGrants.extend(emptyList(), 0, now) == Plan.TooLong)
    check("negative duration refused", FlyVoucherGrants.extend(emptyList(), -H, now) == Plan.TooLong)
    check("over the voucher maximum refused", FlyVoucherGrants.extend(emptyList(), SurvivalFlight.MAX_VOUCHER_SECONDS + 1, now) == Plan.TooLong)
    check("the voucher maximum itself is fine", FlyVoucherGrants.extend(emptyList(), SurvivalFlight.MAX_VOUCHER_SECONDS, now) is Plan.Grant)
    val nearMax = listOf(FlyNode(true, now + SurvivalFlight.MAX_BALANCE_MILLIS / 1000 - 10))
    check("passing the balance maximum refused", FlyVoucherGrants.extend(nearMax, H, now) == Plan.TooLong)
    check("overflow refused", FlyVoucherGrants.extend(listOf(FlyNode(true, Long.MAX_VALUE - 5)), H, now) == Plan.TooLong)
    check("replaced: temporary true only", FlyVoucherGrants.replaced(FlyNode(true, now)) &&
        !FlyVoucherGrants.replaced(FlyNode(true, null)) && !FlyVoucherGrants.replaced(FlyNode(false, now)))

    // ── Moving old balances in (idempotent) ────────────────
    val moved = FlyVoucherGrants.until(emptyList(), now + 2 * H, now)
    check("old balance becomes a node until its end", moved == Plan.Grant(now + 2 * H, null))
    check("moving it twice changes nothing", FlyVoucherGrants.until(apply(emptyList(), moved), now + 2 * H, now) == Plan.Unchanged(now + 2 * H))
    check("never shortens a longer node", FlyVoucherGrants.until(listOf(FlyNode(true, now + 5 * H)), now + 2 * H, now) == Plan.Unchanged(now + 5 * H))
    check("extends a shorter node", FlyVoucherGrants.until(listOf(FlyNode(true, now + H)), now + 2 * H, now) == Plan.Grant(now + 2 * H, now + H))
    check("an already finished balance writes nothing", FlyVoucherGrants.until(emptyList(), now - 1, now) is Plan.Unchanged)
    check("moving respects a staff deny", FlyVoucherGrants.until(listOf(FlyNode(false, null)), now + H, now) == Plan.Denied)
    check("moving with permanent flight writes nothing", FlyVoucherGrants.until(listOf(FlyNode(true, null)), now + H, now) == Plan.Permanent)

    // ── Crash recovery ─────────────────────────────────────
    check("LuckPerms has it: completed", FlyVoucherGrants.recover(now + 3 * H, now + 3 * H, now) == Recovery.COMPLETED)
    check("LuckPerms has more: completed", FlyVoucherGrants.recover(now + 3 * H, now + 4 * H, now) == Recovery.COMPLETED)
    check("LuckPerms has the old time only: released", FlyVoucherGrants.recover(now + 3 * H, now + 2 * H, now) == Recovery.RELEASE)
    check("LuckPerms has nothing: released", FlyVoucherGrants.recover(now + H, null, now) == Recovery.RELEASE)
    check("planned end already passed: interrupted, never resent", FlyVoucherGrants.recover(now - 1, null, now) == Recovery.INTERRUPTED)
    check("current expiry ignores expired and deny nodes", FlyVoucherGrants.currentExpiry(listOf(FlyNode(true, now - 5), FlyNode(false, now + 9 * H), FlyNode(true, now + H)), now) == now + H)

    // ── /flytime and the landing grace follow LuckPerms ────
    val t = Instant.ofEpochSecond(now)
    check("timed node = temporary access", FlightTime.resolve(true, listOf(t.plusSeconds(H)), t) == FlightTime.Access.Temporary(Duration.ofSeconds(H)))
    check("expired node LuckPerms hasn't removed yet = no time left, not permanent",
        FlightTime.resolve(true, listOf(t.minusSeconds(2)), t) == FlightTime.Access.None)
    check("no permission = none", FlightTime.resolve(false, listOf(t.plusSeconds(H)), t) == FlightTime.Access.None)
    check("rank grant without expiry = permanent", FlightTime.resolve(true, listOf(null, t.plusSeconds(H)), t) == FlightTime.Access.Permanent)
    check("grace follows the stored end", SurvivalFlight.phase(now * 1000, false, now * 1000 + 1) is SurvivalFlight.Phase.Grace)

    // ── Voucher items: signed duration ─────────────────────
    val secret = ByteArray(32) { it.toByte() }
    val payload = SurvivalFlight.signedPayload(1, "11111111-1111-1111-1111-111111111111", H)
    val sig = SurvivalFlight.sign(secret, payload)
    check("signed voucher verifies", SurvivalFlight.verify(secret, payload, sig))
    check("edited duration is rejected", !SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(1, "11111111-1111-1111-1111-111111111111", 7 * D), sig))
    check("other voucher uuid is rejected", !SurvivalFlight.verify(secret, SurvivalFlight.signedPayload(1, "22222222-2222-2222-2222-222222222222", H), sig))
    check("unsigned voucher is rejected", !SurvivalFlight.verify(secret, payload, null))
    for ((text, seconds) in listOf("1h" to H, "1d" to D, "7d" to 7 * D, "30m" to 1_800L, "1h30m" to 5_400L)) {
        check("duration $text parses", SurvivalFlight.parseDuration(text) == SurvivalFlight.DurationParse.Ok(seconds))
    }
    for (bad in listOf("0m", "-1h", "1.5h", "10", "", "366d", "999999999999d", "1h; op me")) {
        check("duration '$bad' refused", SurvivalFlight.parseDuration(bad) is SurvivalFlight.DurationParse.Invalid)
    }

    // ── Wiring ─────────────────────────────────────────────
    check("/fly stays Survival scope (Lifesteal untouched)", GamemodeAssignments.COMMANDS["fly"] == GamemodeScope.SURVIVAL)
    check("/flytime stays Survival scope", GamemodeAssignments.COMMANDS["flytime"] == GamemodeScope.SURVIVAL)
    val writer = source("flight/LuckPermsFlyGrant.kt")
    check("writer goes through modifyUser (load + save, by UUID)", "getMethod(\"modifyUser\", UUID::class.java" in writer)
    check("writer only builds joshymc.fly", "builder.invoke(null, FlyVoucherGrants.NODE)" in writer && writer.split("builder.invoke(").size == 2)
    check("writer never touches groups or contexts", "InheritanceNode" !in writer && "withContext" !in writer && "parent" !in writer.lowercase().replace("parent groups", ""))
    check("writer always writes an expiry", "builderExpiry.invoke(" in writer)
    val manager = source("flight/FlyVoucherManager.kt")
    check("voucher reserved PENDING before LuckPerms", manager.indexOf("'\$PENDING')") in 0 until manager.indexOf("lp.extend("))
    check("voucher taken only after LuckPerms confirmed", manager.indexOf("takeVoucher(player, voucher.voucherUuid)") > manager.indexOf("private fun complete("))
    check("failed grant releases the reservation", "if (!handedOff && reservedUuid != null) release(reservedUuid)" in manager)
    check("one redemption per player at a time", "plugin.flightTimeManager.tryLock(uuid)" in manager)
    check("Survival only", "gamemodeOf(player) != ServerGamemode.SURVIVAL" in manager)
    check("no console command is dispatched", "dispatchCommand" !in manager && "dispatchCommand" !in writer)
    val listener = source("listener/FlyVoucherListener.kt")
    check("listener filtered to the main hand", "if (event.hand != EquipmentSlot.HAND) return" in listener)
    check("listener no longer consumes the item itself", "setItemInMainHand" !in listener)
    check("join settles pending redemptions", "flyVoucherManager.recoverPending(event.player)" in source("listener/FlightTimeListener.kt"))

    println("OK: $checks fly voucher checks passed.")
}
