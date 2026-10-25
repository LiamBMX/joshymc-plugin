package com.liam.joshymc.eventshop

import kotlin.math.ln
import kotlin.random.Random

/**
 * Picks a rotation's listings (issue #1299). No Bukkit.
 *
 * Weighted sampling without replacement (Efraimidis–Spirakis): every candidate draws the key
 * `ln(u) / weight` with `u` uniform in (0, 1], and the [count] largest keys win, in key order.
 * A listing of weight 2 is twice as likely as one of weight 1 to be the first pick; equal weights
 * give a uniform pick. Ids are distinct, so a rotation can never list the same listing twice.
 * When there are fewer than [count] candidates, every one is returned (the documented
 * underfilled fallback); nothing is ever invented to fill the gap.
 */
object EventShopSelection {

    const val SLOTS = 7

    data class Candidate(val listingId: Int, val weight: Int)

    fun pick(candidates: List<Candidate>, count: Int = SLOTS, random: Random = Random.Default): List<Int> {
        val distinct = candidates.filter { it.weight > 0 }.distinctBy { it.listingId }
        return distinct
            .map { it.listingId to ln(1.0 - random.nextDouble()) / it.weight }
            .sortedByDescending { it.second }
            .take(count)
            .map { it.first }
    }
}
