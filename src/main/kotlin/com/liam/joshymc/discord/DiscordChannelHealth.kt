package com.liam.joshymc.discord

/**
 * Per-channel delivery problems (issue #1161), so a missing, deleted or locked-down channel
 * logs one warning when its problem starts or changes, and one line when it works again,
 * instead of a warning for every queued message. Messages dropped meanwhile are counted and
 * reported with the recovery. Thread-safe: the flush task and JDA callbacks both report here.
 */
class DiscordChannelHealth(
    private val warn: (String) -> Unit,
    private val info: (String) -> Unit
) {

    private class State(val problem: String, var dropped: Int)

    private val states = HashMap<String, State>()

    /** [channelId] ([label]) can't take messages because of [problem]; [dropped] messages were lost to it. */
    @Synchronized
    fun problem(channelId: String, label: String, problem: String, dropped: Int = 0) {
        val current = states[channelId]
        if (current != null && current.problem == problem) {
            current.dropped += dropped
            return
        }
        states[channelId] = State(problem, (current?.dropped ?: 0) + dropped)
        warn("[Discord] $label ($channelId): $problem. Messages for it are dropped until this is fixed; this is logged again only if the problem changes.")
    }

    /** Counts messages dropped for an already-reported problem without logging again. */
    @Synchronized
    fun dropped(channelId: String, count: Int) {
        if (count > 0) states[channelId]?.let { it.dropped += count }
    }

    /** [channelId] accepted a message (or resolved fine): logs the recovery if it had a problem. */
    @Synchronized
    fun ok(channelId: String, label: String) {
        val previous = states.remove(channelId) ?: return
        val lost = if (previous.dropped > 0) " (${previous.dropped} message(s) were dropped while it wasn't)" else ""
        info("[Discord] $label ($channelId) is reachable again$lost.")
    }

    @Synchronized
    fun hasProblem(channelId: String): Boolean = channelId in states

    /** Forgets every problem, e.g. when a new bot connection starts. */
    @Synchronized
    fun clear() = states.clear()
}
