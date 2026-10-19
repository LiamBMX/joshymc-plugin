package com.liam.joshymc.communityevent.game

import java.util.UUID
import kotlin.random.Random

/**
 * Sharks and Minnows roles, crossings and placements (issues #1189, #1247). Plain Kotlin with
 * no Bukkit types; [SharksGame] drives it from the main thread.
 *
 * Everyone starts as a Minnow except the starting Sharks. In a crossing every Minnow is
 * CROSSING until they reach the finish (SAFE) or are caught (CAUGHT), each once. A caught Minnow
 * becomes a Shark at once and can catch the Minnows still crossing; they stay a Shark for the
 * rest of the game. Minnows who made it cross again in the next crossing, until one Minnow is
 * left after a crossing they finished (1st place) or none are left (no 1st place).
 *
 * Placements are decided per crossing, never by the order catches were processed in: everyone
 * caught in the same crossing shares one place, the place right after the Minnows still left
 * when that crossing ended ("competition" ranking, so a tie of two at 2nd leaves no 3rd). Without
 * a Minnow who finished the game nobody is 1st, so a group that would be 1st is 2nd instead.
 * Only places 1 to 3 are recorded. Players who leave the event while still a Minnow get no
 * place; places already recorded are kept when a player leaves.
 */
class SharksRoster(private val random: Random = Random.Default) {

    enum class Role { SHARK, MINNOW }

    enum class MinnowState { CROSSING, SAFE, CAUGHT }

    /** What a finished crossing (or the game between crossings) means. */
    sealed interface Outcome {
        /** Two or more Minnows are left (or one who still has to cross): another crossing follows. */
        data class Continue(val minnows: List<UUID>) : Outcome
        /** The last Minnow finished their crossing: 1st place. */
        data class Winner(val winner: UUID) : Outcome
        /** No Minnow can finish: no 1st place. [lastCaught] are the Minnows caught in the final crossing. */
        data class NoWinner(val lastCaught: List<UUID>) : Outcome
    }

    private val roles = LinkedHashMap<UUID, Role>()
    private val crossing = LinkedHashMap<UUID, MinnowState>()

    /** Caught in the current (or just finished) crossing, in catch order. Kept when they leave, for their place. */
    private val caughtThisCrossing = LinkedHashSet<UUID>()

    /** Recorded places (1, 2 or 3). Never cleared mid-game, so leaving or reconnecting can't change them. */
    private val places = LinkedHashMap<UUID, Int>()

    /** Number of crossings started so far. */
    var crossingNumber: Int = 0
        private set

    /** True between [beginCrossing] and [endCrossing]. */
    var inCrossing: Boolean = false
        private set

    val sharks: List<UUID> get() = roles.filterValues { it == Role.SHARK }.keys.toList()
    val minnows: List<UUID> get() = roles.filterValues { it == Role.MINNOW }.keys.toList()
    val everyone: List<UUID> get() = roles.keys.toList()

    /** Minnows of the current crossing still on their way. */
    val stillCrossing: List<UUID> get() = crossing.filterValues { it == MinnowState.CROSSING }.keys.toList()
    val safe: List<UUID> get() = crossing.filterValues { it == MinnowState.SAFE }.keys.toList()

    /** Minnows caught in the current (or just finished) crossing, in catch order (now Sharks). */
    val caught: List<UUID> get() = caughtThisCrossing.toList()

    fun roleOf(uuid: UUID): Role? = roles[uuid]
    fun stateOf(uuid: UUID): MinnowState? = crossing[uuid]
    fun isShark(uuid: UUID): Boolean = roles[uuid] == Role.SHARK

    /** [uuid]'s recorded place (1-3), or null. */
    fun placeOf(uuid: UUID): Int? = places[uuid]

    /** Players holding [place], in the order they were recorded. */
    fun placed(place: Int): List<UUID> = places.filterValues { it == place }.keys.toList()

    /**
     * Assigns roles for a new game: [startingSharks] random Sharks (at least 1, and always
     * leaving at least two Minnows when there are three or more players), everyone else a Minnow.
     */
    fun assign(players: Collection<UUID>, startingSharks: Int) {
        val distinct = players.distinct()
        require(distinct.size >= 2) { "Sharks and Minnows needs at least 2 players" }
        roles.clear(); crossing.clear(); caughtThisCrossing.clear(); places.clear()
        crossingNumber = 0
        inCrossing = false
        val sharkCount = sharkCountFor(distinct.size, startingSharks)
        val picked = distinct.shuffled(random).take(sharkCount).toSet()
        for (uuid in distinct) roles[uuid] = if (uuid in picked) Role.SHARK else Role.MINNOW
    }

    /**
     * Starts a crossing with every Minnow CROSSING. With no Shark left (they all left the event),
     * one random Minnow becomes the Shark first. Returns false when there's nobody to cross.
     */
    fun beginCrossing(): Boolean {
        if (inCrossing) return false
        if (sharks.isEmpty() && minnows.size >= 2) roles[minnows.random(random)] = Role.SHARK
        val runners = minnows
        if (runners.isEmpty() || sharks.isEmpty()) return false
        crossing.clear()
        caughtThisCrossing.clear()
        runners.forEach { crossing[it] = MinnowState.CROSSING }
        crossingNumber++
        inCrossing = true
        return true
    }

    /** Undoes a [beginCrossing] that couldn't start (nobody moved yet). */
    fun abortCrossing() {
        if (!inCrossing || crossing.values.any { it != MinnowState.CROSSING }) return
        crossing.clear()
        crossingNumber--
        inCrossing = false
    }

    /**
     * [shark] tags [minnow]. True only for a Shark tagging a Minnow who is still crossing, once;
     * the Minnow is a Shark from then on.
     */
    fun catch(shark: UUID, minnow: UUID): Boolean {
        if (!inCrossing || roles[shark] != Role.SHARK || shark == minnow) return false
        return markCaught(minnow)
    }

    /** A crossing Minnow is out without a Shark (time ran out, fell, wasn't ready). Once. */
    fun forceCatch(minnow: UUID): Boolean {
        if (!inCrossing) return false
        return markCaught(minnow)
    }

    private fun markCaught(minnow: UUID): Boolean {
        if (crossing[minnow] != MinnowState.CROSSING || roles[minnow] != Role.MINNOW) return false
        crossing[minnow] = MinnowState.CAUGHT
        roles[minnow] = Role.SHARK
        caughtThisCrossing += minnow
        return true
    }

    /** [minnow] reached the finish zone. True the first time only; they can't be caught this crossing. */
    fun reachSafety(minnow: UUID): Boolean {
        if (!inCrossing || crossing[minnow] != MinnowState.CROSSING) return false
        crossing[minnow] = MinnowState.SAFE
        return true
    }

    /** Every Minnow of the crossing is safe or caught (or left). */
    val crossingDone: Boolean get() = inCrossing && crossing.values.none { it == MinnowState.CROSSING }

    /**
     * Ends the crossing once: Minnows still crossing (only when it is ended early) are caught,
     * the crossing's catches get their place, and the result follows from the Minnows left.
     * Returns null when no crossing is running (a repeat call).
     */
    fun endCrossing(): Outcome? {
        if (!inCrossing) return null
        stillCrossing.forEach { markCaught(it) }
        inCrossing = false
        val left = minnows
        val group = caughtThisCrossing.toList()
        if (group.isNotEmpty()) {
            // Everyone caught this crossing ranks right behind the Minnows still in; never 1st without a finish.
            val place = maxOf(left.size + 1, 2)
            if (place <= 3) group.forEach { places.putIfAbsent(it, place) }
        }
        return when (left.size) {
            0 -> Outcome.NoWinner(group)
            // Every Minnow left at the end of a crossing finished it (SAFE), so this one made the final crossing.
            1 -> left.first().takeIf { crossing[it] == MinnowState.SAFE }
                ?.let { places[it] = 1; Outcome.Winner(it) }
                ?: Outcome.Continue(left)
            else -> Outcome.Continue(left)
        }
    }

    /**
     * The game between crossings (e.g. after someone left): no Minnow left, or a last Minnow with
     * no Shark to cross against, ends it with no 1st place. A last Minnow with Sharks left must
     * still finish a crossing; becoming the last Minnow isn't a win.
     */
    fun standing(): Outcome {
        val left = minnows
        return when {
            left.isEmpty() -> Outcome.NoWinner(emptyList())
            left.size == 1 && sharks.isEmpty() -> Outcome.NoWinner(emptyList())
            else -> Outcome.Continue(left)
        }
    }

    /** [uuid] left the event: no longer a Shark or Minnow. Their recorded place, if any, stays. True when they were in the game. */
    fun withdraw(uuid: UUID): Boolean {
        crossing.remove(uuid)
        return roles.remove(uuid) != null
    }

    companion object {
        /** Starting Sharks: the configured count, but at least 1 and leaving at least two Minnows when possible. */
        fun sharkCountFor(players: Int, configured: Int): Int {
            val most = if (players >= 3) players - 2 else 1
            return configured.coerceIn(1, most.coerceAtLeast(1))
        }
    }
}
