package com.liam.joshymc.communityevent.game

import java.util.UUID
import kotlin.random.Random

/**
 * Single-elimination Sumo bracket (issue #1187). Plain Kotlin with no Bukkit types.
 *
 * Each bracket round shuffles the players still in, pairs them off in order and gives the
 * odd one out a bye (preferring someone who hasn't had one). Winners and byes move on to
 * the next bracket round until one player is left. Only one match is ever in progress, and
 * a player who lost or withdrew never comes back.
 */
class SumoBracket(entrants: Collection<UUID>, private val random: Random = Random.Default) {

    data class Match(val first: UUID, val second: UUID, val bracketRound: Int)

    data class Result(val winner: UUID?, val loser: UUID?, val bracketRound: Int)

    sealed interface Next {
        /** A match to play. [byes] advanced without playing while it was picked. */
        data class Play(val match: Match, val byes: List<UUID>) : Next
        /** The bracket is decided. [champion] is null when nobody is left. */
        data class Decided(val champion: UUID?, val byes: List<UUID>) : Next
        /** A match is already in progress. */
        data object Busy : Next
    }

    var bracketRound: Int = 1
        private set

    /** The match being played, or null between matches. */
    var current: Match? = null
        private set

    private val waiting = ArrayDeque<UUID>()
    private val advanced = ArrayList<UUID>()
    private val out = LinkedHashSet<UUID>()
    private val hadBye = HashSet<UUID>()
    private val results = ArrayList<Result>()

    init {
        waiting.addAll(arrange(entrants.distinct()))
    }

    /** Players who still have a match to play this bracket round, in order. */
    val queue: List<UUID> get() = waiting.toList()

    /** Players through to the next bracket round. */
    val through: List<UUID> get() = advanced.toList()

    /** Everyone still in the tournament. */
    val remaining: List<UUID> get() = buildList {
        current?.let { add(it.first); add(it.second) }
        addAll(waiting); addAll(advanced)
    }

    val eliminated: List<UUID> get() = out.toList()

    val completed: List<Result> get() = results.toList()

    fun isOut(uuid: UUID): Boolean = uuid in out

    /** The champion once the bracket is decided, else null. */
    val champion: UUID?
        get() = when {
            current != null -> null
            waiting.isEmpty() && advanced.size == 1 -> advanced.first()
            advanced.isEmpty() && waiting.size == 1 -> waiting.first()
            else -> null
        }

    /** Nobody is left at all (everyone withdrew). */
    val isEmpty: Boolean get() = current == null && waiting.isEmpty() && advanced.isEmpty()

    /** Picks the next match, handing out byes and starting new bracket rounds as needed. */
    fun next(): Next {
        if (current != null) return Next.Busy
        val byes = ArrayList<UUID>()
        while (true) {
            champion?.let { return Next.Decided(it, byes) }
            if (isEmpty) return Next.Decided(null, byes)
            when {
                waiting.size >= 2 -> {
                    val match = Match(waiting.removeFirst(), waiting.removeFirst(), bracketRound)
                    current = match
                    return Next.Play(match, byes)
                }
                waiting.size == 1 -> {
                    val lucky = waiting.removeFirst()
                    advanced.add(lucky)
                    hadBye.add(lucky)
                    byes.add(lucky)
                }
                else -> {
                    bracketRound++
                    waiting.addAll(arrange(advanced))
                    advanced.clear()
                }
            }
        }
    }

    /** Records the current match's result. [winner] must be one of its two players. */
    fun win(winner: UUID): Boolean {
        val match = current ?: return false
        val loser = when (winner) {
            match.first -> match.second
            match.second -> match.first
            else -> return false
        }
        current = null
        advanced.add(winner)
        out.add(loser)
        results.add(Result(winner, loser, match.bracketRound))
        return true
    }

    /** Both players of the current match are out (both withdrew). */
    fun bothOut(): Boolean {
        val match = current ?: return false
        current = null
        out.add(match.first); out.add(match.second)
        results.add(Result(null, null, match.bracketRound))
        return true
    }

    /** The current match had no result (both fell together): it goes back to the front of the queue. */
    fun replay(): Boolean {
        val match = current ?: return false
        current = null
        waiting.addFirst(match.second)
        waiting.addFirst(match.first)
        return true
    }

    /**
     * Takes [uuid] out of the tournament while they're waiting. Returns false when they're in
     * the current match (the caller ends the match instead) or already out.
     */
    fun withdraw(uuid: UUID): Boolean {
        val match = current
        if (match != null && (match.first == uuid || match.second == uuid)) return false
        val removed = waiting.remove(uuid) or advanced.remove(uuid)
        if (removed) out.add(uuid)
        return removed
    }

    /** Shuffles for fairness; with an odd count, the last slot (the bye) goes to someone without one yet. */
    private fun arrange(players: List<UUID>): List<UUID> {
        val list = players.shuffled(random).toMutableList()
        if (list.size % 2 == 1 && list.last() in hadBye) {
            val swap = list.indexOfFirst { it !in hadBye }
            if (swap >= 0) list[swap] = list.last().also { list[list.size - 1] = list[swap] }
        }
        return list
    }
}
