package com.liam.joshymc.util

/**
 * Per-key alert cooldown that groups what it holds back (issue #1153).
 *
 * The first alert for a key goes out at once and opens a cooldown window. Alerts for the
 * same key inside that window are counted (the latest one is kept) instead of sent. Once
 * the window has passed, [drainDue] hands back one grouped follow-up per key that held
 * anything back, and that follow-up opens a fresh window, so a key never produces more
 * than one message per cooldown however hard it is hammered. Keys that go quiet for a
 * whole window are dropped, so the map only ever holds keys active in the last cooldown.
 *
 * Pure logic with an injected clock value; thread-safe.
 */
class AlertThrottle<K : Any, E : Any>(private val cooldownMs: Long) {

    data class Summary<K, E>(val key: K, val suppressed: Int, val latest: E, val windowMs: Long)

    private class Window<E>(var openedAt: Long, var suppressed: Int = 0, var latest: E? = null)

    private val windows = HashMap<K, Window<E>>()

    /**
     * Returns null if [event] was folded into the open window (don't send it). Otherwise
     * [event] should be sent now, and the result is how many held-back detections from an
     * already-expired window it absorbs (0 normally), so the sent alert can mention them
     * instead of a separate follow-up going out right behind it.
     */
    @Synchronized
    fun offer(key: K, event: E, now: Long): Int? {
        val window = windows[key]
        if (window == null || now - window.openedAt >= cooldownMs) {
            windows[key] = Window(now)
            return window?.suppressed ?: 0
        }
        window.suppressed++
        window.latest = event
        return null
    }

    /** Grouped follow-ups whose window has ended, plus cleanup of keys that went quiet. */
    @Synchronized
    fun drainDue(now: Long): List<Summary<K, E>> {
        val due = mutableListOf<Summary<K, E>>()
        val iter = windows.entries.iterator()
        while (iter.hasNext()) {
            val (key, window) = iter.next()
            if (now - window.openedAt < cooldownMs) continue
            val latest = window.latest
            if (window.suppressed > 0 && latest != null) {
                due += Summary(key, window.suppressed, latest, now - window.openedAt)
                // The follow-up counts as this key's alert for the next window.
                window.openedAt = now
                window.suppressed = 0
                window.latest = null
            } else {
                iter.remove()
            }
        }
        return due
    }

    /** Everything still held back, regardless of window (used on stop/reload), and forgets all keys. */
    @Synchronized
    fun drainAll(now: Long): List<Summary<K, E>> {
        val all = windows.mapNotNull { (key, window) ->
            val latest = window.latest ?: return@mapNotNull null
            if (window.suppressed > 0) Summary(key, window.suppressed, latest, now - window.openedAt) else null
        }
        windows.clear()
        return all
    }

    @Synchronized
    fun size(): Int = windows.size
}
