package com.liam.joshymc.communityevent.game

import java.util.UUID

/**
 * Lifecycle of one Spleef round or Sumo match (issue #1187). It lives inside a RUNNING
 * [com.liam.joshymc.communityevent.EventSession] and never replaces the session's own state:
 * WAITING (between rounds) -> COUNTDOWN -> ACTIVE -> ENDING -> WAITING again, or COMPLETE
 * once the event has an overall result.
 */
enum class RoundState(val label: String) {
    WAITING("Waiting"),
    COUNTDOWN("Countdown"),
    ACTIVE("Active"),
    ENDING("Ending"),
    COMPLETE("Complete");

    /** A round is in progress: players are on the floor/platform and must not be disturbed. */
    val inProgress: Boolean get() = this == COUNTDOWN || this == ACTIVE

    fun canMoveTo(next: RoundState): Boolean = when (this) {
        WAITING -> next == COUNTDOWN || next == COMPLETE
        COUNTDOWN -> next == ACTIVE || next == ENDING || next == COMPLETE
        ACTIVE -> next == ENDING || next == COMPLETE
        ENDING -> next == WAITING || next == COMPLETE
        COMPLETE -> false
    }
}

/**
 * Shared round bookkeeping for Spleef and Sumo: the round number, who is still in the round,
 * who was knocked out (each player once), the round winner and the overall winner. Plain
 * Kotlin with no Bukkit types; the games drive it from the main thread.
 */
class RoundTracker {
    var state: RoundState = RoundState.WAITING
        private set
    var round: Int = 0
        private set
    var roundWinner: UUID? = null
        private set
    var overallWinner: UUID? = null
        private set

    private val alive = LinkedHashSet<UUID>()
    private val out = LinkedHashSet<UUID>()

    /** Players still in the current round, in the order they started. */
    val active: List<UUID> get() = alive.toList()

    /** Players knocked out of the current round, in elimination order. */
    val eliminated: List<UUID> get() = out.toList()

    /** Round wins per player across the whole event. */
    val wins: MutableMap<UUID, Int> = LinkedHashMap()

    fun isAlive(uuid: UUID): Boolean = uuid in alive

    /** Was [uuid] in the current round (alive or already out)? */
    fun isInRound(uuid: UUID): Boolean = uuid in alive || uuid in out

    /** WAITING -> COUNTDOWN with a fresh roster. Duplicates are ignored. */
    fun begin(players: Collection<UUID>): Boolean {
        if (!state.canMoveTo(RoundState.COUNTDOWN) || players.isEmpty()) return false
        alive.clear(); out.clear()
        alive.addAll(players)
        roundWinner = null
        round++
        state = RoundState.COUNTDOWN
        return true
    }

    /** COUNTDOWN -> ACTIVE. */
    fun go(): Boolean = move(RoundState.ACTIVE)

    /** Knocks [uuid] out of the round. True only the first time, and only while a round is in progress. */
    fun eliminate(uuid: UUID): Boolean {
        if (!state.inProgress || !alive.remove(uuid)) return false
        out.add(uuid)
        return true
    }

    /** Ends the round (-> ENDING) with [winner], or no winner for a draw. */
    fun end(winner: UUID?): Boolean {
        if (!move(RoundState.ENDING)) return false
        roundWinner = winner
        if (winner != null) wins[winner] = (wins[winner] ?: 0) + 1
        return true
    }

    /** ENDING -> WAITING, ready for the next round. */
    fun ready(): Boolean = move(RoundState.WAITING)

    /** The event has its result. [winner] is null when nobody won. */
    fun complete(winner: UUID?): Boolean {
        if (state == RoundState.COMPLETE) return false
        state = RoundState.COMPLETE
        overallWinner = winner
        return true
    }

    /** The single player with the most round wins, or null when nobody won or it's a tie. */
    fun leader(): UUID? {
        val best = wins.values.maxOrNull() ?: return null
        val top = wins.filterValues { it == best }.keys
        return top.singleOrNull()
    }

    private fun move(next: RoundState): Boolean {
        if (!state.canMoveTo(next)) return false
        state = next
        return true
    }
}
