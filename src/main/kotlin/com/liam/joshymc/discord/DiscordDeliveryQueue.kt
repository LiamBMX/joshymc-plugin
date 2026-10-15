package com.liam.joshymc.discord

/**
 * Bounded buffer for outgoing Discord messages (issue #1159). Messages raised while the bot
 * is still connecting (startup, reload, or a failed connection being retried) wait here and
 * are handed to JDA once it is ready, instead of being dropped.
 *
 * Two classes of message share the buffer, in submit order:
 * - **alerts** (anything addressed to its own channel: staff alerts, reports, anti-dupe,
 *   combat alerts, kill logs, mining logs) are kept until delivered or pushed out by newer alerts;
 * - **routine** chat-bridge traffic expires after [routineMaxAgeMs], so a long outage
 *   doesn't replay stale chat once the bot is back.
 *
 * When full, the oldest routine message is evicted first; only when the buffer holds nothing
 * but alerts does a new alert evict the oldest alert, and a new routine message is refused.
 * Every discard is counted for [takeDiscards]. Thread-safe; never blocks beyond its own lock.
 * Punishment logs never come through here: they have their own durable outbox.
 */
class DiscordDeliveryQueue<T>(
    private val capacity: () -> Int,
    private val routineMaxAgeMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private class Entry<T>(val item: T, val alert: Boolean, val queuedAt: Long)

    /** What was thrown away since the last [takeDiscards]. */
    data class Discards(val routineOverflow: Int, val alertOverflow: Int, val routineExpired: Int) {
        val total: Int get() = routineOverflow + alertOverflow + routineExpired
    }

    private val entries = ArrayDeque<Entry<T>>()
    private var routineOverflow = 0
    private var alertOverflow = 0
    private var routineExpired = 0

    /** Queues [item]; false when it was refused (buffer full of alerts and [alert] is false). */
    @Synchronized
    fun offer(item: T, alert: Boolean): Boolean {
        val max = capacity().coerceAtLeast(1)
        while (entries.size >= max) {
            val oldestRoutine = entries.indexOfFirst { !it.alert }
            when {
                oldestRoutine >= 0 -> { entries.removeAt(oldestRoutine); routineOverflow++ }
                alert -> { entries.removeFirst(); alertOverflow++ }
                else -> { routineOverflow++; return false }
            }
        }
        entries.addLast(Entry(item, alert, clock()))
        return true
    }

    /** Removes and returns everything queued, oldest first, minus routine messages past their age. */
    @Synchronized
    fun drain(): List<T> {
        if (entries.isEmpty()) return emptyList()
        val now = clock()
        val maxAge = routineMaxAgeMs()
        val out = ArrayList<T>(entries.size)
        for (entry in entries) {
            if (!entry.alert && maxAge > 0 && now - entry.queuedAt > maxAge) routineExpired++ else out.add(entry.item)
        }
        entries.clear()
        return out
    }

    /** Empties the buffer, returning (alerts, routine) counts of what was discarded. */
    @Synchronized
    fun clear(): Pair<Int, Int> {
        val alerts = entries.count { it.alert }
        val routine = entries.size - alerts
        entries.clear()
        return alerts to routine
    }

    @get:Synchronized
    val size: Int get() = entries.size

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Discard counters since the last call (then reset), or null when nothing was discarded. */
    @Synchronized
    fun takeDiscards(): Discards? {
        val discards = Discards(routineOverflow, alertOverflow, routineExpired)
        routineOverflow = 0
        alertOverflow = 0
        routineExpired = 0
        return discards.takeIf { it.total > 0 }
    }
}
