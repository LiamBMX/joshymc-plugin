package com.liam.joshymc.advent

import com.liam.joshymc.manager.PurchaseRewards
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The Christmas Advent Calendar's rules (issue #1424), Bukkit-free: when the calendar is open,
 * which day is "today" in the event timezone, what state each present is in for a player, and how
 * reward lines are read. Nothing here touches the database or the server; the manager feeds it
 * the clock, the settings and what the store knows.
 */
object AdventRules {

    /** Presents in one calendar (December 1 through December 25). */
    const val DAYS = 25

    /** The schedule part of `advent-calendar.yml`. */
    data class Schedule(
        val automatic: Boolean,
        val zone: ZoneId,
        val startMonth: Int,
        val startDay: Int,
        val endMonth: Int,
        val endDay: Int,
        /** `manual-override.enabled`: open outside the dates. Never bypasses claims, costs or future-day locks. */
        val overrideEnabled: Boolean,
        /** `manual-override.year`: the calendar year to run while overriding; 0 = the current year in [zone]. */
        val overrideYear: Int = 0,
        /** `manual-override.current-day`: which day counts as "today" while overriding; 0 = by date. */
        val overrideDay: Int = 0,
    ) {
        fun start(year: Int): LocalDate = LocalDate.of(year, startMonth, startDay)
        fun end(year: Int): LocalDate = LocalDate.of(year, endMonth, endDay)

        /** The calendar date of [day] (1..25) in [year]. */
        fun dateOf(year: Int, day: Int): LocalDate = start(year).plusDays((day - 1).toLong())

        /** Midnight (start of day) in the event timezone when [day] of [year] unlocks. */
        fun unlockAt(year: Int, day: Int): ZonedDateTime = dateOf(year, day).atStartOfDay(zone)
    }

    /** Whether the calendar can be opened right now, and which day is "today". */
    sealed interface Availability {
        /** `enabled: false` (or the file is invalid). Always wins over everything else. */
        data object Disabled : Availability

        /** Outside the dates (or `schedule.automatic: false` without an override). [opensAt] is null when only staff can open it. */
        data class Closed(val reason: ClosedReason, val opensAt: ZonedDateTime?) : Availability

        /**
         * Open for [year]. [today] is 0..26: the present of day [today] is today's free one, earlier
         * days are past (catch-up), later ones locked. 0 = nothing unlocked yet, 26 = every day passed.
         */
        data class Open(val year: Int, val today: Int, val manual: Boolean, val nextUnlockAt: ZonedDateTime?) : Availability
    }

    enum class ClosedReason { NOT_STARTED, ENDED, MANUAL_SCHEDULE }

    /**
     * Precedence: `enabled` first (false = disabled, whatever else is set), then
     * `manual-override.enabled` (opens it outside the dates), then the automatic December dates.
     * With `schedule.automatic: false` and no override it stays closed.
     */
    fun availability(enabled: Boolean, schedule: Schedule, now: Instant): Availability {
        if (!enabled) return Availability.Disabled
        val date = now.atZone(schedule.zone).toLocalDate()
        val year = date.year

        if (schedule.overrideEnabled) {
            val calendarYear = if (schedule.overrideYear > 0) schedule.overrideYear else year
            val today = if (schedule.overrideDay > 0) schedule.overrideDay else dateIndex(schedule, calendarYear, date)
            return Availability.Open(calendarYear, today, manual = true, nextUnlockAt = nextUnlock(schedule, calendarYear, today, byDate = schedule.overrideDay == 0))
        }
        if (!schedule.automatic) return Availability.Closed(ClosedReason.MANUAL_SCHEDULE, null)

        val start = schedule.start(year)
        val end = schedule.end(year)
        return when {
            date.isBefore(start) -> Availability.Closed(ClosedReason.NOT_STARTED, start.atStartOfDay(schedule.zone))
            date.isAfter(end) -> Availability.Closed(ClosedReason.ENDED, schedule.start(year + 1).atStartOfDay(schedule.zone))
            else -> {
                val today = (ChronoUnit.DAYS.between(start, date) + 1).toInt()
                Availability.Open(year, today, manual = false, nextUnlockAt = nextUnlock(schedule, year, today, byDate = true))
            }
        }
    }

    /** [date]'s day number in [calendarYear]'s calendar: 0 before it starts, 26 after it ended. */
    fun dateIndex(schedule: Schedule, calendarYear: Int, date: LocalDate): Int {
        val start = schedule.start(calendarYear)
        val end = schedule.end(calendarYear)
        return when {
            date.isBefore(start) -> 0
            date.isAfter(end) -> DAYS + 1
            else -> (ChronoUnit.DAYS.between(start, date) + 1).toInt()
        }
    }

    private fun nextUnlock(schedule: Schedule, year: Int, today: Int, byDate: Boolean): ZonedDateTime? =
        if (!byDate || today >= DAYS) null else schedule.unlockAt(year, today + 1)

    /** One player's view of one present. */
    enum class DayState {
        CLAIMED,
        /** Today's present: free. */
        AVAILABLE,
        /** A past day the player can still recover for Credits. */
        MISSED,
        /** A past day staff made recoverable for free (e.g. after an outage). */
        MISSED_FREE,
        /** A past day that can't be recovered (catch-up turned off). */
        MISSED_CLOSED,
        LOCKED,
    }

    fun dayState(day: Int, today: Int, claimed: Boolean, catchUpEnabled: Boolean, freePass: Boolean): DayState = when {
        claimed -> DayState.CLAIMED
        day > today -> DayState.LOCKED
        day == today -> DayState.AVAILABLE
        freePass -> DayState.MISSED_FREE
        catchUpEnabled -> DayState.MISSED
        else -> DayState.MISSED_CLOSED
    }

    /** Credits a claim in [state] costs, or null when it can't be claimed at all. */
    fun price(state: DayState, catchUpCost: Double): Double? = when (state) {
        DayState.AVAILABLE, DayState.MISSED_FREE -> 0.0
        DayState.MISSED -> catchUpCost
        DayState.CLAIMED, DayState.MISSED_CLOSED, DayState.LOCKED -> null
    }

    /** How a claim was paid for, stored on the claim row. */
    enum class ClaimType { TODAY, CATCH_UP, FREE_PASS }

    fun claimType(state: DayState): ClaimType? = when (state) {
        DayState.AVAILABLE -> ClaimType.TODAY
        DayState.MISSED -> ClaimType.CATCH_UP
        DayState.MISSED_FREE -> ClaimType.FREE_PASS
        else -> null
    }

    // ── Reward lines ─────────────────────────────────────────

    /** Reward kinds a calendar day may hand out (no ranks or vouchers that grant ranks/money). */
    val DAY_KINDS = setOf(
        PurchaseRewards.Kind.COINS,
        PurchaseRewards.Kind.CREDITS,
        PurchaseRewards.Kind.TOKENS,
        PurchaseRewards.Kind.CRATE_KEY,
        PurchaseRewards.Kind.CHAT_TAG_VOUCHER,
    )

    /** Item kinds a placeholder may be mapped to once its reward exists on the server. */
    val PLACEHOLDER_KINDS = setOf(
        PurchaseRewards.Kind.TOKENS,
        PurchaseRewards.Kind.CRATE_KEY,
        PurchaseRewards.Kind.CHAT_TAG_VOUCHER,
    )

    /** One reward of a day, as written in `advent-calendar.yml`. */
    sealed interface RewardLine {
        /** An existing reward (`coins 50000`, `cratekey cabin 1`, `chattagvoucher snowflake 1`, ...). */
        data class Grant(val spec: PurchaseRewards.Spec) : RewardLine

        /**
         * `pending <placeholder> <quantity> <label...>`: a reward that doesn't exist on the server yet
         * (a collectible without art, an unnamed premium crate). Claiming records it; it is delivered
         * once `placeholders.<id>` maps it to a real reward. `{year}` in the id/label is the calendar year.
         */
        data class Pending(val placeholderId: String, val quantity: Int, val label: String) : RewardLine
    }

    sealed interface Parsed<out T> {
        data class Ok<T>(val value: T) : Parsed<T>
        data class Error(val message: String) : Parsed<Nothing>
    }

    private val PLACEHOLDER_ID = Regex("^[a-z0-9_]*(\\{year\\}[a-z0-9_]*)*$")

    /** Syntax only: crates and tags are checked against the server when someone claims. */
    private val ANY_CATALOG = object : PurchaseRewards.Catalog {
        override fun crateExists(id: String) = true
        override fun moneyVoucherExists(id: String) = true
        override fun chatTagVoucherExists(id: String) = true
    }

    fun parseLine(raw: String): Parsed<RewardLine> {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Parsed.Error("empty reward line")
        if (words[0].equals("pending", ignoreCase = true)) {
            if (words.size < 4) return Parsed.Error("'$raw': use pending <placeholder> <quantity> <label>")
            val id = words[1].lowercase(Locale.ROOT)
            if (id.isEmpty() || !PLACEHOLDER_ID.matches(id)) return Parsed.Error("'$raw': placeholder ids use a-z, 0-9, _ and {year}")
            val qty = when (val q = PurchaseRewards.quantity(words[2])) {
                is PurchaseRewards.Result.Ok -> q.value
                is PurchaseRewards.Result.Error -> return Parsed.Error("'$raw': ${q.message}")
            }
            return Parsed.Ok(RewardLine.Pending(id, qty, words.drop(3).joinToString(" ")))
        }
        return when (val spec = parseSpec(words, DAY_KINDS)) {
            is Parsed.Ok -> Parsed.Ok(RewardLine.Grant(spec.value))
            is Parsed.Error -> Parsed.Error("'$raw': ${spec.message}")
        }
    }

    /** A placeholder mapping (`cratekey premium`, `chattagvoucher christmas`, `tokens`) for [quantity] units. */
    fun resolvePlaceholder(mapping: String, quantity: Int): Parsed<PurchaseRewards.Spec> {
        val words = mapping.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Parsed.Error("not configured yet")
        return parseSpec(words + quantity.toString(), PLACEHOLDER_KINDS)
    }

    private fun parseSpec(words: List<String>, allowed: Set<PurchaseRewards.Kind>): Parsed<PurchaseRewards.Spec> {
        val kind = PurchaseRewards.Kind.of(words[0])
        if (kind == null || kind !in allowed) {
            return Parsed.Error("unsupported reward type '${words[0]}' (use ${allowed.joinToString(", ") { it.id }}${if (allowed == DAY_KINDS) " or pending" else ""})")
        }
        return when (val p = PurchaseRewards.parse(words, ANY_CATALOG)) {
            is PurchaseRewards.Result.Error -> Parsed.Error(p.message)
            // PurchaseRewards reads one extra word as a store reference; a calendar line has none.
            is PurchaseRewards.Result.Ok -> if (p.value.reference != null) Parsed.Error("too many words") else Parsed.Ok(p.value.spec)
        }
    }

    /** `{year}` in a placeholder id or label. */
    fun expandYear(text: String, year: Int): String = text.replace("{year}", year.toString())

    /** Coins and Credits a calendar hands out in total (for the docs and the checks). */
    fun currencyTotals(calendar: Map<Int, List<RewardLine>>): Pair<Double, Double> {
        var coins = 0.0
        var credits = 0.0
        for (line in calendar.values.flatten()) {
            val spec = (line as? RewardLine.Grant)?.spec ?: continue
            when (spec.kind) {
                PurchaseRewards.Kind.COINS -> coins += spec.param.toDouble()
                PurchaseRewards.Kind.CREDITS -> credits += spec.param.toDouble()
                else -> {}
            }
        }
        return coins to credits
    }
}
