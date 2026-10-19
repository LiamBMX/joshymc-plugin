package com.liam.joshymc.communityevent

/**
 * Timing rules of the automatic registration window (issue #1248), Bukkit-free.
 *
 * The window is a [System.nanoTime] deadline, so it lasts five real minutes even when the
 * server lags; the scheduler only decides how often it is checked.
 */
object RegistrationCountdown {
    /** How long registration stays open before the event starts by itself. */
    const val REGISTRATION_SECONDS = 300L

    /** Remaining seconds that get a broadcast, largest first. Opening (300) is announced by `/event start` itself. */
    val ANNOUNCE_AT: List<Long> = listOf(180L, 60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L)

    private const val NANOS_PER_SECOND = 1_000_000_000L

    fun deadline(now: Long, seconds: Long = REGISTRATION_SECONDS): Long = now + seconds * NANOS_PER_SECOND

    /** Whole seconds left until [deadline], rounded up; 0 once it has passed. */
    fun remainingSeconds(deadline: Long, now: Long): Long {
        val left = deadline - now
        if (left <= 0) return 0
        return (left + NANOS_PER_SECOND - 1) / NANOS_PER_SECOND
    }

    /**
     * The announcement due with [remaining] seconds left, given the last one made ([lastAnnounced],
     * starting at [REGISTRATION_SECONDS]), or null. When a lag spike skips several marks only the
     * lowest one passed is announced, so players never get a burst of stale countdown lines.
     */
    fun due(remaining: Long, lastAnnounced: Long): Long? =
        ANNOUNCE_AT.filter { it in remaining until lastAnnounced }.minOrNull()

    /** "5 minutes", "1 minute", "2m 30s", "30 seconds", "1 second". */
    fun describe(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 60 && s % 60 == 0L -> plural(s / 60, "minute")
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> plural(s, "second")
        }
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) "1 $unit" else "$n ${unit}s"
}
