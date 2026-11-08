package com.liam.joshymc.advent

import com.liam.joshymc.advent.AdventRules.RewardLine
import com.liam.joshymc.gamemode.ServerGamemode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Everything `advent-calendar.yml` configures (issue #1424). */
data class AdventSettings(
    val enabled: Boolean,
    val schedule: AdventRules.Schedule,
    val catchUpEnabled: Boolean,
    val catchUpCost: Double,
    val calendars: Map<ServerGamemode, GamemodeCalendar>,
    /** Placeholder id → mapping (`cratekey <crate>`, ...); blank = not set up yet. */
    val placeholders: Map<String, String>,
    val texts: AdventTexts,
) {
    fun calendar(gamemode: ServerGamemode): GamemodeCalendar? = calendars[gamemode]
}

/**
 * One gamemode's rewards: [default] is used every year unless [years] has that year's own calendar,
 * so the event returns each December without edits and a future December can differ.
 */
data class GamemodeCalendar(
    val enabled: Boolean,
    val default: Map<Int, List<RewardLine>>?,
    val years: Map<Int, Map<Int, List<RewardLine>>>,
) {
    fun rewardsFor(year: Int): Map<Int, List<RewardLine>>? = years[year] ?: default
}

/** Player-facing text; `&` colour codes. */
data class AdventTexts(
    val title: String,
    val christmasDayName: String,
    val disabled: String,
    val notStarted: String,
    val ended: String,
    val closed: String,
    val claimed: String,
    val christmas: String,
)

/**
 * Parses `advent-calendar.yml`, Bukkit-free. Any mistake (bad date, timezone, cost, reward line,
 * a calendar without all 25 days) is a problem and [Result.settings] is null: the calendar then
 * stays closed until it is fixed. Claims and deliveries already recorded are never touched.
 */
object AdventConfig {

    data class Result(val settings: AdventSettings?, val problems: List<String>)

    interface Source {
        fun get(path: String): Any?
        fun keys(path: String): List<String>?
    }

    const val MAX_CATCH_UP_COST = 1_000_000.0
    private val PLACEHOLDER_KEY = Regex("^[a-z0-9_]{1,64}$")

    val DEFAULT_TEXTS = AdventTexts(
        title = "&4&l✦ &2&lAdvent Calendar &4&l✦",
        christmasDayName = "&6&lChristmas Grand Bundle",
        disabled = "&cThe Advent Calendar is currently disabled. Check back soon!",
        notStarted = "&eThe Advent Calendar opens on {date}. See you then!",
        ended = "&eThe Advent Calendar has ended for this year. It returns on {date}!",
        closed = "&eThe Advent Calendar isn't open right now.",
        claimed = "&aYou opened your &fDecember {day} &apresent: &f{rewards}&a!",
        christmas = "&6&l✦ Merry Christmas! &eYou opened the &6Christmas Grand Bundle&e: &f{rewards}&e!",
    )

    fun parse(source: Source): Result {
        val problems = mutableListOf<String>()

        fun bool(path: String, default: Boolean): Boolean = when (val raw = source.get(path)) {
            null -> default
            is Boolean -> raw
            else -> { problems += "$path must be true or false (got '$raw')."; default }
        }

        fun int(path: String, default: Int, range: IntRange): Int {
            val raw = source.get(path) ?: return default
            val value = (raw as? Number)?.takeIf { it !is Double && it !is Float }?.toInt()
            if (value == null || value !in range) {
                problems += "$path must be a whole number from ${range.first} to ${range.last} (got '$raw')."
                return default
            }
            return value
        }

        fun string(path: String, default: String): String = when (val raw = source.get(path)) {
            null -> default
            is String -> raw
            else -> { problems += "$path must be text (got '$raw')."; default }
        }

        val enabled = bool("enabled", true)

        // ── Schedule ──
        val zoneText = string("schedule.timezone", "America/New_York")
        val zone = try {
            ZoneId.of(zoneText)
        } catch (e: DateTimeException) {
            problems += "schedule.timezone '$zoneText' is not a timezone id (e.g. America/New_York)."
            ZoneId.of("America/New_York")
        }
        val schedule = AdventRules.Schedule(
            automatic = bool("schedule.automatic", true),
            zone = zone,
            startMonth = int("schedule.start-month", 12, 1..12),
            startDay = int("schedule.start-day", 1, 1..31),
            endMonth = int("schedule.end-month", 12, 1..12),
            endDay = int("schedule.end-day", 25, 1..31),
            overrideEnabled = bool("manual-override.enabled", false),
            overrideYear = int("manual-override.year", 0, 0..9999).also {
                if (it in 1..1999) problems += "manual-override.year must be 0 (the current year) or a year from 2000 (got $it)."
            },
            overrideDay = int("manual-override.current-day", 0, 0..AdventRules.DAYS + 1),
        )
        // Same span in a leap year and a normal one, so February dates can't shift the days.
        for (year in listOf(2027, 2028)) {
            val start = try { schedule.start(year) } catch (e: DateTimeException) { null }
            val end = try { schedule.end(year) } catch (e: DateTimeException) { null }
            if (start == null || end == null) {
                problems += "schedule start/end must be real dates every year (no February 29)."
                break
            }
            val span = ChronoUnit.DAYS.between(start, end) + 1
            if (span != AdventRules.DAYS.toLong()) {
                problems += "schedule must span exactly ${AdventRules.DAYS} days in one year (start ${start.month} ${start.dayOfMonth} to end ${end.month} ${end.dayOfMonth} is $span)."
                break
            }
        }

        // ── Catch-up ──
        val catchUpEnabled = bool("missed-days.enabled", true)
        val catchUpCost = when (val raw = source.get("missed-days.cost-credits")) {
            null -> 25.0
            is Number -> raw.toDouble().takeIf { it.isFinite() && it >= 0.0 && it <= MAX_CATCH_UP_COST }
                ?.let { Math.round(it * 10.0) / 10.0 }
                ?: run { problems += "missed-days.cost-credits must be a number from 0 to $MAX_CATCH_UP_COST (got '$raw')."; 25.0 }
            else -> { problems += "missed-days.cost-credits must be a number (got '$raw')."; 25.0 }
        }

        // ── Calendars ──
        val calendars = mutableMapOf<ServerGamemode, GamemodeCalendar>()
        for (gm in ServerGamemode.entries) {
            val base = "rewards.${gm.id}"
            if (source.keys(base) == null) continue
            val gmEnabled = bool("$base.enabled", true)
            val default = if (source.keys("$base.default") != null) calendar("$base.default", source, problems) else null
            val years = mutableMapOf<Int, Map<Int, List<RewardLine>>>()
            for (key in source.keys("$base.years").orEmpty()) {
                val year = key.toIntOrNull()
                if (year == null || year !in 2000..9999) {
                    problems += "$base.years.$key: use a year such as 2027."
                    continue
                }
                calendar("$base.years.$key", source, problems)?.let { years[year] = it }
            }
            if (default == null && years.isEmpty()) problems += "$base has no default calendar and no years."
            calendars[gm] = GamemodeCalendar(gmEnabled, default, years)
        }
        if (calendars.isEmpty()) problems += "rewards has no survival or lifesteal section."

        // ── Placeholders ──
        val placeholders = mutableMapOf<String, String>()
        for (key in source.keys("placeholders").orEmpty()) {
            if (!PLACEHOLDER_KEY.matches(key)) {
                problems += "placeholders.$key: ids use a-z, 0-9 and _."
                continue
            }
            val mapping = when (val raw = source.get("placeholders.$key")) {
                null -> ""
                is String -> raw.trim()
                else -> { problems += "placeholders.$key must be text such as \"cratekey <crate id>\" or \"\"."; "" }
            }
            if (mapping.isNotEmpty()) {
                val check = AdventRules.resolvePlaceholder(mapping, 1)
                if (check is AdventRules.Parsed.Error) {
                    problems += "placeholders.$key: ${check.message} (use cratekey <crate>, chattagvoucher <tag>, tokens, or \"\")."
                    continue
                }
            }
            placeholders[key] = mapping
        }

        val d = DEFAULT_TEXTS
        val texts = AdventTexts(
            title = string("gui.title", d.title),
            christmasDayName = string("gui.christmas-day-name", d.christmasDayName),
            disabled = string("messages.disabled", d.disabled),
            notStarted = string("messages.not-started", d.notStarted),
            ended = string("messages.ended", d.ended),
            closed = string("messages.closed", d.closed),
            claimed = string("messages.claimed", d.claimed),
            christmas = string("messages.christmas", d.christmas),
        )

        if (problems.isNotEmpty()) return Result(null, problems)
        return Result(AdventSettings(enabled, schedule, catchUpEnabled, catchUpCost, calendars, placeholders, texts), problems)
    }

    /** All 25 days, each with at least one valid reward line. */
    private fun calendar(path: String, source: Source, problems: MutableList<String>): Map<Int, List<RewardLine>>? {
        val days = mutableMapOf<Int, List<RewardLine>>()
        var ok = true
        for (key in source.keys(path).orEmpty()) {
            val day = key.toIntOrNull()
            if (day == null || day !in 1..AdventRules.DAYS) {
                problems += "$path.$key: days are numbered 1 to ${AdventRules.DAYS}."
                ok = false
            }
        }
        for (day in 1..AdventRules.DAYS) {
            val raw = source.get("$path.$day")
            val lines = when (raw) {
                is String -> listOf(raw)
                is List<*> -> raw.map { it?.toString() ?: "" }
                null -> { problems += "$path.$day is missing (every calendar needs all ${AdventRules.DAYS} days)."; ok = false; continue }
                else -> { problems += "$path.$day must be a list of reward lines."; ok = false; continue }
            }
            if (lines.isEmpty()) { problems += "$path.$day has no rewards."; ok = false; continue }
            val parsed = mutableListOf<RewardLine>()
            for (line in lines) {
                when (val p = AdventRules.parseLine(line)) {
                    is AdventRules.Parsed.Ok -> parsed += p.value
                    is AdventRules.Parsed.Error -> { problems += "$path.$day: ${p.message}"; ok = false }
                }
            }
            days[day] = parsed
        }
        return if (ok) days else null
    }

    /** Used by the checks: the date of [day] for [year] under the default December schedule. */
    fun defaultDate(year: Int, day: Int): LocalDate = LocalDate.of(year, 12, day)
}
