package com.liam.joshymc.communityevent.game

import java.util.UUID
import kotlin.random.Random

/**
 * Sharks and Minnows roles and crossings (issue #1189). Plain Kotlin with no Bukkit types;
 * [SharksGame] drives it from the main thread.
 *
 * Everyone starts as a Minnow except the starting Sharks. In a crossing every Minnow is
 * CROSSING until they reach the finish (SAFE) or a Shark tags them (CAUGHT), each once.
 * Caught Minnows only become Sharks when the crossing ends ([endCrossing]), never mid-crossing.
 */
class SharksRoster(private val random: Random = Random.Default) {

    enum class Role { SHARK, MINNOW }

    enum class MinnowState { CROSSING, SAFE, CAUGHT }

    /** What a finished crossing means for the game. */
    sealed interface Outcome {
        /** Two or more Minnows are left: another crossing follows. */
        data class Continue(val minnows: List<UUID>) : Outcome
        /** Exactly one Minnow is left: they win. */
        data class Winner(val winner: UUID) : Outcome
        /** No Minnow is left. [lastStanding] are the Minnows caught in the final crossing (tied). */
        data class NoWinner(val lastStanding: List<UUID>) : Outcome
    }

    private val roles = LinkedHashMap<UUID, Role>()
    private val crossing = LinkedHashMap<UUID, MinnowState>()

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

    /** Minnows caught in the current (or just finished) crossing, in catch order. */
    val caught: List<UUID> get() = crossing.filterValues { it == MinnowState.CAUGHT }.keys.toList()

    fun roleOf(uuid: UUID): Role? = roles[uuid]
    fun stateOf(uuid: UUID): MinnowState? = crossing[uuid]
    fun isShark(uuid: UUID): Boolean = roles[uuid] == Role.SHARK

    /**
     * Assigns roles for a new game: [startingSharks] random Sharks (at least 1, and always
     * leaving at least two Minnows when there are three or more players), everyone else a Minnow.
     */
    fun assign(players: Collection<UUID>, startingSharks: Int) {
        val distinct = players.distinct()
        require(distinct.size >= 2) { "Sharks and Minnows needs at least 2 players" }
        roles.clear(); crossing.clear()
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
        runners.forEach { crossing[it] = MinnowState.CROSSING }
        crossingNumber++
        inCrossing = true
        return true
    }

    /** [shark] tags [minnow]. True only for a Shark tagging a Minnow who is still crossing, once. */
    fun catch(shark: UUID, minnow: UUID): Boolean {
        if (!inCrossing || roles[shark] != Role.SHARK) return false
        if (crossing[minnow] != MinnowState.CROSSING) return false
        crossing[minnow] = MinnowState.CAUGHT
        return true
    }

    /** A crossing Minnow is out without a Shark (time ran out, fell, wasn't ready). Once. */
    fun forceCatch(minnow: UUID): Boolean {
        if (!inCrossing || crossing[minnow] != MinnowState.CROSSING) return false
        crossing[minnow] = MinnowState.CAUGHT
        return true
    }

    /** [minnow] reached the finish zone. True the first time only. */
    fun reachSafety(minnow: UUID): Boolean {
        if (!inCrossing || crossing[minnow] != MinnowState.CROSSING) return false
        crossing[minnow] = MinnowState.SAFE
        return true
    }

    /** Every Minnow is safe or caught. */
    val crossingDone: Boolean get() = inCrossing && crossing.values.none { it == MinnowState.CROSSING }

    /**
     * Ends the crossing: caught Minnows become Sharks, Minnows still crossing (only when staff
     * end it early) are caught, and the result is decided by how many Minnows are left.
     */
    fun endCrossing(): Outcome? {
        if (!inCrossing) return null
        crossing.replaceAll { _, state -> if (state == MinnowState.CROSSING) MinnowState.CAUGHT else state }
        val caughtNow = caught
        caughtNow.forEach { if (it in roles) roles[it] = Role.SHARK }
        inCrossing = false
        return outcome(caughtNow)
    }

    /** The result as things stand between crossings (e.g. after someone left). */
    fun standing(): Outcome = outcome(emptyList())

    private fun outcome(lastCaught: List<UUID>): Outcome {
        val left = minnows
        return when (left.size) {
            0 -> Outcome.NoWinner(lastCaught)
            1 -> Outcome.Winner(left.first())
            else -> Outcome.Continue(left)
        }
    }

    /** [uuid] left the event: gone from every list. True when they were in the game. */
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
