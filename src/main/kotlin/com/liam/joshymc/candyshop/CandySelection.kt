package com.liam.joshymc.candyshop

import kotlin.random.Random

/**
 * Picks the Candy Shop's stock (issue #1211). No Bukkit, so it can be checked on its own.
 *
 * Each pool entry's chance is a per-opening INCLUSION probability: a 1% candy should be in about
 * 1 opening in 100, not win 1% of one weighted draw. With exactly [SLOTS] distinct candies per
 * opening, the inclusion probabilities of the whole pool always add up to exactly [SLOTS]
 * (300%), so the configured chances can only all be exact when they add up to 300%.
 *
 * [Mode.EXACT] requires that: any other total is reported as a problem and no stock is picked.
 * [Mode.NORMALIZE] (the default) scales every chance by the same factor so they add up to 300%,
 * capping at 100% and handing the excess to the others (the standard "water-filling" fix). When
 * the total already is 300% nothing changes. The effective chances are always reported, so an
 * adjusted pool never claims its configured numbers are exact.
 *
 * Sampling is systematic probability-proportional-to-size sampling (Madow, 1949): the entries are
 * shuffled, laid end to end as intervals as long as their inclusion probability on [0, 3), and
 * the entries under the points u, u+1 and u+2 (one uniform u in [0, 1)) are taken. A point lands
 * in an interval with probability equal to its length, and no interval is longer than 1, so every
 * entry is included with exactly its probability, the three picks are always distinct, and it
 * takes one random number and one pass: no retries.
 */
object CandySelection {

    const val SLOTS = 3
    private const val TOTAL = SLOTS.toDouble()

    /** How far (in percentage points) an EXACT pool may be from 300% and still count as exact. */
    const val EXACT_TOLERANCE_PERCENT = 0.01

    enum class Mode { EXACT, NORMALIZE }

    /** One pool entry: its stable id and its configured chance in percent (0 < chance <= 100). */
    data class Candidate(val entryId: Int, val chancePercent: Double)

    /**
     * The result of checking a pool. [inclusion] maps entry id to its effective inclusion
     * probability (0..1, summing to 3) and is empty when [problems] is not.
     */
    data class Plan(
        val inclusion: Map<Int, Double>,
        val problems: List<String>,
        val configuredTotalPercent: Double,
        val mode: Mode,
    ) {
        val feasible: Boolean get() = problems.isEmpty()

        /** True when some effective chance differs from what was configured. */
        val adjusted: Boolean get() = feasible && kotlin.math.abs(configuredTotalPercent - TOTAL * 100) > EXACT_TOLERANCE_PERCENT
    }

    fun plan(candidates: List<Candidate>, mode: Mode): Plan {
        val problems = mutableListOf<String>()
        val total = candidates.sumOf { it.chancePercent }
        if (candidates.map { it.entryId }.toSet().size != candidates.size) problems += "The pool lists the same entry twice."
        for (c in candidates) {
            if (!c.chancePercent.isFinite() || c.chancePercent <= 0.0 || c.chancePercent > 100.0) {
                problems += "Entry #${c.entryId} has an invalid chance (${c.chancePercent}%); it must be above 0 and at most 100."
            }
        }
        if (candidates.size < SLOTS) {
            problems += "Only ${candidates.size} enabled candies are in the pool; every opening needs $SLOTS different ones."
        }
        if (problems.isNotEmpty()) return Plan(emptyMap(), problems, total, mode)

        val inclusion = when (mode) {
            Mode.EXACT -> {
                if (kotlin.math.abs(total - TOTAL * 100) > EXACT_TOLERANCE_PERCENT) {
                    val fmt = "%.2f".format(total)
                    return Plan(
                        emptyMap(),
                        listOf("Chances add up to $fmt%. With $SLOTS candies per opening they must add up to exactly 300% in exact mode."),
                        total, mode,
                    )
                }
                candidates.associate { it.entryId to it.chancePercent / 100.0 }
            }
            Mode.NORMALIZE -> waterFill(candidates)
        }
        return Plan(inclusion, emptyList(), total, mode)
    }

    /**
     * Inclusion probabilities proportional to the configured chances, capped at 1, summing to 3.
     * The biggest entries are capped one at a time (at most [SLOTS] - 1 of them can ever be), and
     * the rest share what's left in proportion: bounded, no iteration to convergence.
     */
    private fun waterFill(candidates: List<Candidate>): Map<Int, Double> {
        val sorted = candidates.sortedByDescending { it.chancePercent }
        var capped = 0
        while (capped < SLOTS) {
            val restWeight = sorted.drop(capped).sumOf { it.chancePercent }
            val scale = (TOTAL - capped) / restWeight
            if (sorted[capped].chancePercent * scale > 1.0 + 1e-12) capped++ else break
        }
        val restWeight = sorted.drop(capped).sumOf { it.chancePercent }
        val scale = (TOTAL - capped) / restWeight
        return sorted.mapIndexed { idx, c ->
            c.entryId to (if (idx < capped) 1.0 else (c.chancePercent * scale).coerceAtMost(1.0))
        }.toMap()
    }

    /** Draws [SLOTS] distinct entry ids from a feasible [plan]. */
    fun sample(plan: Plan, random: Random = Random.Default): List<Int> {
        require(plan.feasible) { "Cannot sample an infeasible pool: ${plan.problems}" }
        val order = plan.inclusion.keys.sorted().shuffled(random)
        val sum = order.sumOf { plan.inclusion.getValue(it) }
        // Stretch the intervals by sum/3 (1 up to rounding) so the last one ends exactly at 3.
        val ends = DoubleArray(order.size)
        var acc = 0.0
        for ((i, id) in order.withIndex()) {
            acc += plan.inclusion.getValue(id) * TOTAL / sum
            ends[i] = acc
        }
        ends[ends.size - 1] = TOTAL

        val u = random.nextDouble()
        val picked = LinkedHashSet<Int>()
        for (k in 0 until SLOTS) {
            val point = u + k
            var idx = ends.indexOfFirst { point < it }
            if (idx < 0) idx = ends.size - 1
            // Only floating-point rounding can put two points in one interval; take the next free entry.
            while (order[idx] in picked) idx = (idx + 1) % order.size
            picked += order[idx]
        }
        return picked.toList()
    }
}
