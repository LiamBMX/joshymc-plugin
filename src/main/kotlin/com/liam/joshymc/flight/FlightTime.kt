package com.liam.joshymc.flight

import java.time.Duration
import java.time.Instant

/**
 * What `/flytime` reports (issue #1303). Bukkit-free.
 *
 * JoshyMC has no flight timer of its own: flight access is the `joshymc.fly` permission
 * (the `/fly` command), granted through LuckPerms. Temporary flight is a LuckPerms node
 * with an expiry (`permission settemp joshymc.fly true 2h`, or a timed parent group that
 * grants it), so LuckPerms' expiry is the only timer and is read, never changed.
 */
object FlightTime {

    /** Permission nodes that grant `/fly`. */
    val FLY_NODES = setOf("joshymc.fly", "joshymc.*", "*")

    sealed interface Access {
        data object Permanent : Access
        data class Temporary(val remaining: Duration) : Access
        data object None : Access
    }

    /**
     * [hasFlight] is the live permission check (it already applies negations and contexts).
     * [grants] holds the expiry of every way the player gets a fly node (null = never
     * expires), or null when LuckPerms can't be read. A grant with no known source
     * (op, permission defaults, another permissions plugin) has no expiry, so it's permanent.
     * One permanent grant beats any temporary one; otherwise the latest expiry counts.
     */
    fun resolve(hasFlight: Boolean, grants: List<Instant?>?, now: Instant): Access {
        if (!hasFlight) return Access.None
        if (grants.isNullOrEmpty() || grants.any { it == null }) return Access.Permanent
        val remaining = Duration.between(now, grants.filterNotNull().max())
        return if (remaining.isNegative || remaining.isZero) Access.None else Access.Temporary(remaining)
    }

    /** The earlier of two expiries, where null means "never expires". */
    fun earlier(a: Instant?, b: Instant?): Instant? = when {
        a == null -> b
        b == null -> a
        else -> minOf(a, b)
    }

    /**
     * `3d 5h 20m`, `2h 35m`, `45m`, `5m 30s`, `30s`. Zero units are left out and seconds
     * only show under an hour. Partial seconds round up, so time that's still left never
     * reads as `0s`.
     */
    fun format(remaining: Duration): String {
        val nanos = remaining.toNanos().coerceAtLeast(0)
        var seconds = nanos / 1_000_000_000 + if (nanos % 1_000_000_000 > 0) 1 else 0
        if (seconds <= 0) return "0s"
        val days = seconds / 86_400; seconds %= 86_400
        val hours = seconds / 3_600; seconds %= 3_600
        val minutes = seconds / 60; seconds %= 60
        val parts = mutableListOf<String>()
        if (days > 0) parts += "${days}d"
        if (hours > 0) parts += "${hours}h"
        if (minutes > 0) parts += "${minutes}m"
        if (seconds > 0 && days == 0L && hours == 0L) parts += "${seconds}s"
        return parts.joinToString(" ")
    }
}
