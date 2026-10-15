package com.liam.joshymc.link

/**
 * Failed link-code attempts (issue #1155).
 *
 * Per Discord account: [LinkSettings.maxFailedAttempts] wrong codes inside one
 * [LinkSettings.attemptCooldownMs] window lock that account out for the cooldown, after
 * which it starts fresh, so nobody is ever locked out for good. A correct code clears the
 * account's record.
 *
 * Globally: more than [LinkSettings.globalMaxFailuresPerMinute] wrong codes across all
 * accounts in a minute pauses code checks until the oldest of them is a minute old, which
 * caps what a swarm of accounts can guess. 0 turns the global cap off.
 *
 * Entries are dropped once their window and lockout are over. Since failures stop being
 * recorded while the global cap is hit, at most `globalMax * cooldown minutes` accounts are
 * tracked at once. All methods are synchronized.
 */
class LinkAttemptLimiter {

    sealed interface Decision {
        data object Allowed : Decision
        data class LockedOut(val retryAfterMs: Long) : Decision
        data class Busy(val retryAfterMs: Long) : Decision
    }

    private class Record(var failures: Int, var windowStart: Long, var lockedUntil: Long)

    private val records = HashMap<String, Record>()
    private val recentFailures = ArrayDeque<Long>()

    @Synchronized
    fun check(discordId: String, now: Long, settings: LinkSettings): Decision {
        prune(now, settings)
        val record = records[discordId]
        if (record != null && record.lockedUntil > now) return Decision.LockedOut(record.lockedUntil - now)
        val globalMax = settings.globalMaxFailuresPerMinute
        if (globalMax > 0 && recentFailures.size >= globalMax) {
            return Decision.Busy((recentFailures.first() + GLOBAL_WINDOW_MS - now).coerceAtLeast(1))
        }
        return Decision.Allowed
    }

    @Synchronized
    fun recordFailure(discordId: String, now: Long, settings: LinkSettings) {
        recentFailures.addLast(now)
        val record = records[discordId]
        if (record == null || now - record.windowStart >= settings.attemptCooldownMs) {
            records[discordId] = Record(1, now, 0).also { lockIfOver(it, now, settings) }
            return
        }
        record.failures++
        lockIfOver(record, now, settings)
    }

    @Synchronized
    fun recordSuccess(discordId: String) {
        records.remove(discordId)
    }

    @Synchronized
    fun trackedAccounts(): Int = records.size

    private fun lockIfOver(record: Record, now: Long, settings: LinkSettings) {
        if (record.failures >= settings.maxFailedAttempts) {
            record.lockedUntil = now + settings.attemptCooldownMs
            record.failures = 0
            record.windowStart = now
        }
    }

    private fun prune(now: Long, settings: LinkSettings) {
        while (recentFailures.isNotEmpty() && now - recentFailures.first() >= GLOBAL_WINDOW_MS) recentFailures.removeFirst()
        records.values.removeIf { it.lockedUntil <= now && now - it.windowStart >= settings.attemptCooldownMs }
    }

    companion object {
        const val GLOBAL_WINDOW_MS = 60_000L
    }
}
