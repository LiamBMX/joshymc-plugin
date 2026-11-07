package com.liam.joshymc.flight

/**
 * How a Fly Voucher turns into LuckPerms flight (issue #1408). Bukkit-free.
 *
 * A voucher grants the temporary LuckPerms node `joshymc.fly = true` with no contexts, the
 * same node as `/lp user <uuid> permission settemp joshymc.fly true <duration>`. LuckPerms
 * stores and expires it, so it survives restarts and runs out while the player is offline.
 *
 * Stacking: the player's own unexpired temporary `joshymc.fly` nodes (global context) are
 * replaced by one node that ends [addSeconds] after the latest of them (or after now). So
 * 2h left + a 1h voucher = one node with 3h left, never two overlapping nodes. Every time is
 * in whole epoch seconds, which is what LuckPerms stores; "now" is rounded up so a voucher
 * never grants less than it says.
 */
object FlyVoucherGrants {

    /** The only permission node a voucher ever writes. */
    const val NODE = "joshymc.fly"

    /**
     * One of the player's own `joshymc.fly` nodes with no contexts. [expiresAt] is epoch
     * seconds, null for a permanent node.
     */
    data class FlyNode(val value: Boolean, val expiresAt: Long?)

    sealed interface Plan {
        /** Replace the player's temporary fly nodes with one that ends at [expiresAt]. */
        data class Grant(val expiresAt: Long, val previousExpiresAt: Long?) : Plan
        /** They already have temporary flight until at least [expiresAt]: nothing to write. */
        data class Unchanged(val expiresAt: Long) : Plan
        /** A permanent `joshymc.fly = true` node: a voucher would add nothing. */
        data object Permanent : Plan
        /** Staff set `joshymc.fly = false` on the player: never override it. */
        data object Denied : Plan
        /** Invalid duration, or the result would pass the maximum balance or overflow. */
        data object TooLong : Plan
    }

    fun nowSeconds(nowMillis: Long): Long = Math.floorDiv(nowMillis + 999, 1000L)

    private fun live(nodes: List<FlyNode>, nowSec: Long): List<FlyNode> =
        nodes.filter { it.expiresAt == null || it.expiresAt > nowSec }

    private fun blocker(nodes: List<FlyNode>): Plan? = when {
        nodes.any { !it.value } -> Plan.Denied
        nodes.any { it.value && it.expiresAt == null } -> Plan.Permanent
        else -> null
    }

    /** The latest unexpired temporary `joshymc.fly = true` expiry, or null. */
    fun currentExpiry(nodes: List<FlyNode>, nowSec: Long): Long? =
        live(nodes, nowSec).filter { it.value }.mapNotNull { it.expiresAt }.maxOrNull()

    /**
     * Redeeming a voucher of [addSeconds]. [carryOverUntil] is the end of an older JoshyMC
     * balance (issue #1304) not yet moved into LuckPerms, so its time isn't lost either.
     */
    fun extend(nodes: List<FlyNode>, addSeconds: Long, nowSec: Long, carryOverUntil: Long? = null): Plan {
        if (addSeconds <= 0 || addSeconds > SurvivalFlight.MAX_VOUCHER_SECONDS) return Plan.TooLong
        val current = live(nodes, nowSec)
        blocker(current)?.let { return it }
        val previous = currentExpiry(current, nowSec)
        val base = maxOf(nowSec, previous ?: nowSec, carryOverUntil ?: nowSec)
        val end = try { Math.addExact(base, addSeconds) } catch (_: ArithmeticException) { return Plan.TooLong }
        if ((end - nowSec) * 1000 > SurvivalFlight.MAX_BALANCE_MILLIS) return Plan.TooLong
        return Plan.Grant(end, previous)
    }

    /**
     * Moving an older JoshyMC flight balance (issue #1304) into LuckPerms: flight until at
     * least [untilSec], never shortening what they already have. Repeating it changes nothing,
     * so it is safe to retry after a crash.
     */
    fun until(nodes: List<FlyNode>, untilSec: Long, nowSec: Long): Plan {
        val current = live(nodes, nowSec)
        blocker(current)?.let { return it }
        val previous = currentExpiry(current, nowSec)
        if (untilSec <= nowSec) return Plan.Unchanged(previous ?: untilSec)
        if (previous != null && previous >= untilSec) return Plan.Unchanged(previous)
        if ((untilSec - nowSec) * 1000 > SurvivalFlight.MAX_BALANCE_MILLIS) return Plan.TooLong
        return Plan.Grant(untilSec, previous)
    }

    /** Which existing nodes a [Plan.Grant] replaces: the temporary `true` ones (expired leftovers too). */
    fun replaced(node: FlyNode): Boolean = node.value && node.expiresAt != null

    enum class Recovery {
        /** LuckPerms has the planned flight: the redemption went through. */
        COMPLETED,
        /** LuckPerms doesn't have it and it would still be running: it never happened, the voucher is usable again. */
        RELEASE,
        /** Can't tell (the planned end has already passed): left for staff, never retried automatically. */
        INTERRUPTED
    }

    /**
     * Settling a redemption whose outcome wasn't seen (crash, or LuckPerms reporting an error):
     * [plannedExpiresAt] is what the redemption was going to grant, [currentExpiresAt] what
     * LuckPerms holds now.
     */
    fun recover(plannedExpiresAt: Long, currentExpiresAt: Long?, nowSec: Long): Recovery = when {
        currentExpiresAt != null && currentExpiresAt >= plannedExpiresAt -> Recovery.COMPLETED
        plannedExpiresAt > nowSec -> Recovery.RELEASE
        else -> Recovery.INTERRUPTED
    }

    /** The LuckPerms command this grant is equivalent to, for logs. Only ever built from a UUID and a validated number. */
    fun commandEquivalent(uuid: java.util.UUID, seconds: Long): String =
        "lp user $uuid permission settemp $NODE true ${seconds}s"
}
