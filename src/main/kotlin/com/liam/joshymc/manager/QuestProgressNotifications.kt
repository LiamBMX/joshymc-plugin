package com.liam.joshymc.manager

/**
 * Bukkit-free numbers shown for one quest's progress (issue #1278), shared by the
 * /quests GUI, the quest details chat lines, the admin progress listing and the
 * action-bar notifications so they all agree.
 */
object QuestProgressDisplay {
    const val BAR_CELLS = 10

    /**
     * Progress as shown against [target]. Exactly [target] once completed; before that
     * it stays below [target], so a quest whose target dropped under its progress (a
     * quest booster started) never reads as done until it really completes.
     */
    fun shownProgress(progress: Int, target: Int, completed: Boolean): Int =
        if (completed) target.coerceAtLeast(0) else progress.coerceIn(0, (target - 1).coerceAtLeast(0))

    /** Filled cells of the progress bar: all [BAR_CELLS] only once the quest is completed. */
    fun filledCells(progress: Int, target: Int, completed: Boolean): Int {
        if (completed) return BAR_CELLS
        if (target <= 0) return 0
        return ((progress.toDouble() / target) * BAR_CELLS).toInt().coerceIn(0, BAR_CELLS - 1)
    }
}

/**
 * Coalesces quest progress notifications per player (issue #1278). Every increment
 * calls [offer] with the quest it moved; [due] hands out at most one notification per
 * player per [intervalMs], always for the most recently moved quest, so a Void Drill
 * or Tree Feller breaking dozens of blocks produces one action-bar update, not one
 * per block. Bukkit-free and not thread-safe: use it from the main thread only.
 */
class QuestProgressThrottle<K>(private val intervalMs: Long) {

    private val pending = LinkedHashMap<K, String>()
    private val lastSent = HashMap<K, Long>()

    /** Records that [questId] moved for [key]; replaces any older pending quest. */
    fun offer(key: K, questId: String) {
        pending.remove(key)
        pending[key] = questId
    }

    /** Drops the pending notification for [key] if it is about [questId] (the quest just completed). */
    fun discard(key: K, questId: String) {
        if (pending[key] == questId) pending.remove(key)
    }

    /** Pending notifications whose player hasn't been notified within the interval, oldest offer first. */
    fun due(nowMs: Long): List<Pair<K, String>> =
        pending.entries
            .filter { (key, _) -> lastSent[key]?.let { nowMs - it >= intervalMs } ?: true }
            .map { it.key to it.value }

    /** The notification for [key] was shown (or deliberately skipped): clear it and start the interval. */
    fun markSent(key: K, nowMs: Long) {
        pending.remove(key)
        lastSent[key] = nowMs
    }

    /** Clears the pending notification without starting the interval. */
    fun clear(key: K) {
        pending.remove(key)
    }

    /** Forgets everything about [key] (player quit). */
    fun forget(key: K) {
        pending.remove(key)
        lastSent.remove(key)
    }

    fun forgetAll() {
        pending.clear()
        lastSent.clear()
    }

    fun hasPending(key: K): Boolean = key in pending
}
