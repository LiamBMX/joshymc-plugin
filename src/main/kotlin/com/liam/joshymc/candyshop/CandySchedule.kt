package com.liam.joshymc.candyshop

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * The Candy Shop's daily opening times (issue #1211). No Bukkit, so it can be checked on its own.
 *
 * Rules:
 *  - exactly [OPENINGS_PER_DAY] openings per calendar day in the shop's zone, each lasting [WINDOW];
 *  - every start is a wall-clock quarter hour (minute 00, 15, 30 or 45) of that day;
 *  - consecutive starts are at least [MIN_GAP] apart in real elapsed time, including the previous
 *    day's last opening and the next day's first one when that day already exists.
 *
 * Daylight saving: candidate starts are real instants, so gaps are measured in real time. Wall-clock
 * times skipped by a spring-forward gap don't exist that day and are never picked; times repeated by
 * a fall-back overlap are offered once (the earlier offset), so a day never gets the same label twice.
 *
 * Generation is uniform over every valid schedule and never retries: it counts the valid ways to
 * finish a schedule from each candidate (a small dynamic program over at most 96 candidates) and
 * walks the candidates once, taking each with exactly the probability that keeps the result uniform.
 */
object CandySchedule {

    const val OPENINGS_PER_DAY = 4
    const val SLOT_MINUTES = 15
    val WINDOW: Duration = Duration.ofMinutes(30)
    val MIN_GAP: Duration = Duration.ofMinutes(180)

    /** Every quarter-hour wall-clock start of [day] in [zone], each real instant once, in order. */
    fun candidateStarts(day: LocalDate, zone: ZoneId): List<Instant> {
        val out = ArrayList<Instant>(100)
        val rules = zone.rules
        for (q in 0 until 24 * 60 / SLOT_MINUTES) {
            val local = day.atTime(q * SLOT_MINUTES / 60, q * SLOT_MINUTES % 60)
            // Empty in a spring-forward gap: that wall-clock time doesn't happen today.
            if (rules.getValidOffsets(local).isEmpty()) continue
            out += ZonedDateTime.ofLocal(local, zone, null).withEarlierOffsetAtOverlap().toInstant()
        }
        return out.distinct().sorted()
    }

    /**
     * Picks [OPENINGS_PER_DAY] starts for [day], at least [MIN_GAP] after [previousStart] (the last
     * opening before this day) and at least [MIN_GAP] before [nextStart] (the next day's first
     * opening, when it was already generated). Returns null only when no valid schedule exists.
     */
    fun generate(
        day: LocalDate,
        zone: ZoneId,
        previousStart: Instant?,
        nextStart: Instant?,
        random: Random = Random.Default,
    ): List<Instant>? {
        val c = candidateStarts(day, zone).filter { start ->
            (previousStart == null || !Duration.between(previousStart, start).minus(MIN_GAP).isNegative) &&
                (nextStart == null || !Duration.between(start, nextStart).minus(MIN_GAP).isNegative)
        }
        val m = c.size
        val n = OPENINGS_PER_DAY

        // next[i]: the first candidate index at least MIN_GAP after candidate i (m when none).
        val next = IntArray(m)
        var j = 0
        for (i in 0 until m) {
            if (j < i + 1) j = i + 1
            while (j < m && Duration.between(c[i], c[j]).minus(MIN_GAP).isNegative) j++
            next[i] = j
        }

        // ways[k][i]: how many valid ways there are to choose k more starts from c[i..].
        val ways = Array(n + 1) { LongArray(m + 1) }
        for (i in 0..m) ways[0][i] = 1
        for (k in 1..n) {
            for (i in m - 1 downTo 0) ways[k][i] = ways[k][i + 1] + ways[k - 1][next[i]]
        }
        if (ways[n][0] == 0L) return null

        val picks = ArrayList<Instant>(n)
        var i = 0
        var k = n
        while (k > 0) {
            // ways[k][i] > 0 holds here, so this always terminates with k picks.
            val withThis = ways[k - 1][next[i]]
            if (random.nextLong(ways[k][i]) < withThis) {
                picks += c[i]
                i = next[i]
                k--
            } else {
                i++
            }
        }
        return picks
    }

    /** Everything wrong with [starts] as the schedule of [day]; empty when it follows every rule. */
    fun problems(
        day: LocalDate,
        zone: ZoneId,
        starts: List<Instant>,
        previousStart: Instant? = null,
        nextStart: Instant? = null,
    ): List<String> {
        val out = mutableListOf<String>()
        if (starts.size != OPENINGS_PER_DAY) out += "has ${starts.size} openings instead of $OPENINGS_PER_DAY"
        val valid = candidateStarts(day, zone).toSet()
        for (s in starts) {
            if (s !in valid) out += "${ZonedDateTime.ofInstant(s, zone)} is not a quarter hour of $day"
        }
        val all = listOfNotNull(previousStart) + starts.sorted() + listOfNotNull(nextStart)
        for (idx in 1 until all.size) {
            val gap = Duration.between(all[idx - 1], all[idx])
            if (gap.minus(MIN_GAP).isNegative) out += "openings ${all[idx - 1]} and ${all[idx]} are only ${gap.toMinutes()} minutes apart"
        }
        return out
    }
}
