package com.liam.joshymc.eventshop

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Event Shop's daily opening times (issue #1299). No Bukkit, so it can be checked on its own.
 *
 * The schedule is [OPENINGS_PER_DAY] wall-clock start times (config.yml `event-shop.schedule`),
 * each on a :00 or :30 boundary, each window lasting exactly [WINDOW] of real time. [parse]
 * rejects anything else with a reason per problem instead of guessing:
 *  - not exactly six times, a time that isn't `H:mm`/`HH:mm`, a minute other than 00 or 30;
 *  - the same time twice, or two windows that overlap, including the last window of a day running
 *    past midnight into the first window of the next day.
 *
 * Daylight saving (deterministic, every window is still exactly [WINDOW] long):
 *  - a start time skipped by a spring-forward gap opens at the first instant after the gap
 *    (02:00 → 03:00 in America/New_York), which is `ZonedDateTime.ofLocal`'s rule;
 *  - a start time repeated by a fall-back overlap opens at its first occurrence (earlier offset);
 *  - if that makes a window start before the previous one of the same day has ended (a valid
 *    schedule with back-to-back windows around a transition), it starts when the previous one ends.
 *
 * A window belongs to the calendar day of its wall-clock start, and its slot is its 1-based
 * position in the sorted schedule. `day#slot` is its key: a day has at most six keys, so a
 * restart or reload can never create a seventh scheduled window, or the same one twice.
 */
object EventShopSchedule {

    const val OPENINGS_PER_DAY = 6
    val WINDOW: Duration = Duration.ofHours(2)
    const val ALIGN_MINUTES = 30

    val DEFAULT_TIMES: List<String> = listOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00")

    /** A parsed schedule: [times] sorted, valid when [problems] is empty. */
    data class Parsed(val times: List<LocalTime>, val problems: List<String>) {
        val valid: Boolean get() = problems.isEmpty()
    }

    data class Window(val day: LocalDate, val slot: Int, val localStart: LocalTime, val start: Instant, val end: Instant) {
        val key: String get() = "$day#$slot"
        fun isActiveAt(now: Instant): Boolean = !now.isBefore(start) && now.isBefore(end)
    }

    fun parse(raw: List<String>): Parsed {
        val problems = mutableListOf<String>()
        if (raw.size != OPENINGS_PER_DAY) problems += "the schedule has ${raw.size} opening times; it needs exactly $OPENINGS_PER_DAY"
        val times = mutableListOf<LocalTime>()
        for (entry in raw) {
            val text = entry.trim()
            val time = parseTime(text)
            when {
                time == null -> problems += "'$text' is not a time (use HH:mm, e.g. 16:00 or 4:30)"
                time.minute % ALIGN_MINUTES != 0 || time.second != 0 -> problems += "'$text' is not on a :00 or :30 boundary"
                time in times -> problems += "'$text' is listed twice"
                else -> times += time
            }
        }
        times.sort()
        val window = WINDOW.toMinutes()
        for (i in times.indices) {
            val startMin = minuteOfDay(times[i])
            // The last window is compared with the first one of the next day.
            val wraps = i == times.lastIndex
            val other = if (wraps) times.first() else times[i + 1]
            val nextMin = minuteOfDay(other) + if (wraps) 24 * 60 else 0
            if (nextMin - startMin < window) {
                problems += "the ${fmt(times[i])} window (2 hours) overlaps the ${fmt(other)} window" +
                    if (wraps) " of the next day" else ""
            }
        }
        return Parsed(times, problems)
    }

    private fun minuteOfDay(time: LocalTime): Int = time.hour * 60 + time.minute

    private fun parseTime(text: String): LocalTime? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").matchEntire(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return LocalTime.of(h, min)
    }

    fun fmt(time: LocalTime): String = "%02d:%02d".format(time.hour, time.minute)

    /** [day]'s windows for the sorted, valid [times], in order (see the class doc for DST rules). */
    fun windowsForDay(day: LocalDate, zone: ZoneId, times: List<LocalTime>): List<Window> {
        val out = ArrayList<Window>(times.size)
        var previousEnd: Instant? = null
        times.sorted().forEachIndexed { idx, local ->
            var start = ZonedDateTime.ofLocal(day.atTime(local), zone, null).withEarlierOffsetAtOverlap().toInstant()
            previousEnd?.let { if (start.isBefore(it)) start = it }
            val end = start.plus(WINDOW)
            out += Window(day, idx + 1, local, start, end)
            previousEnd = end
        }
        return out
    }

    /** Every window that could be running at [now]: yesterday's (one may run past midnight) and today's. */
    fun windowsAround(now: Instant, zone: ZoneId, times: List<LocalTime>, daysAhead: Int = 0): List<Window> {
        val today = LocalDate.ofInstant(now, zone)
        return (-1..daysAhead).flatMap { windowsForDay(today.plusDays(it.toLong()), zone, times) }
    }

    /** The scheduled window running at [now], if any. */
    fun windowAt(now: Instant, zone: ZoneId, times: List<LocalTime>): Window? =
        windowsAround(now, zone, times).lastOrNull { it.isActiveAt(now) }

    /** The first window starting after [now] (looks two days ahead). */
    fun nextWindowAfter(now: Instant, zone: ZoneId, times: List<LocalTime>): Window? =
        windowsAround(now, zone, times, daysAhead = 2).firstOrNull { it.start.isAfter(now) }
}
