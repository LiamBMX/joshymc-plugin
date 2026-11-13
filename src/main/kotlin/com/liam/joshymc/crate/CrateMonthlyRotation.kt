package com.liam.joshymc.crate

import java.time.Instant
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Monthly crate rotation (issue #1520), the Bukkit-free part, on top of the model rotation of
 * issue #1460 ([CrateModels.Monthly]):
 *
 * - The **effective month** is an admin override while one is active, else the calendar month
 *   read in `monthly.timezone` (America/New_York by default, a real zone, so DST is handled).
 *   It drives both the monthly model and, when `monthly.crate-ids` maps that month, which crate
 *   a rotating crate location opens ([crateFor]).
 * - An override names one month and optionally an expiry (epoch milliseconds, UTC). It is never
 *   applied past its expiry, so a restart after it ran out uses the calendar month.
 * - The next check is the earlier of the next month start and the override's expiry, never more
 *   than [CrateModels.MAX_CHECK_DELAY_MILLIS] away ([checkDelayMillis]); nothing runs per tick.
 */
object CrateMonthlyRotation {

    /** Longest override an admin may set with a duration (a little over a year). */
    const val MAX_OVERRIDE_MILLIS = 366L * 24L * 60L * 60L * 1000L

    /** Shortest override duration (anything shorter is a typo). */
    const val MIN_OVERRIDE_MILLIS = 60L * 1000L

    /** A manual month choice. [expiresAt] null = until an admin resets it. */
    data class Override(
        val month: Month,
        val setAt: Long,
        val expiresAt: Long?,
        val setBy: String,
    ) {
        fun activeAt(now: Instant): Boolean = expiresAt == null || now.toEpochMilli() < expiresAt
    }

    /** The month rotating crates use at [now]: an active [override], else the calendar month in [zone]. */
    fun effectiveMonth(now: Instant, zone: ZoneId, override: Override?): Month =
        override?.takeIf { it.activeAt(now) }?.month ?: CrateModels.monthAt(now, zone)

    /** True when [override] is set and still running at [now]. */
    fun overrideActive(now: Instant, override: Override?): Boolean = override != null && override.activeAt(now)

    /** True when [override] is set but ran out (it should be cleared, and the calendar month used). */
    fun overrideExpired(now: Instant, override: Override?): Boolean = override != null && !override.activeAt(now)

    /**
     * The earliest instant the effective month can change after [now]: the next month start in
     * [zone], or the running override's expiry when that comes first.
     */
    fun nextChange(now: Instant, zone: ZoneId, override: Override?): Instant {
        val monthStart = CrateModels.nextMonthStart(now, zone)
        val expiry = override?.takeIf { it.activeAt(now) }?.expiresAt?.let(Instant::ofEpochMilli) ?: return monthStart
        return if (expiry.isBefore(monthStart)) expiry else monthStart
    }

    /** How long to wait before the next check: just past [nextChange], at least 1s, at most an hour. */
    fun checkDelayMillis(now: Instant, zone: ZoneId, override: Override?): Long {
        val until = nextChange(now, zone, override).toEpochMilli() - now.toEpochMilli() + 1000L
        return until.coerceIn(1000L, CrateModels.MAX_CHECK_DELAY_MILLIS)
    }

    // ── Override expiry input ──────────────────────────────────────────

    sealed interface ExpiryParse {
        /** [expiresAt] null = no expiry. */
        data class Ok(val expiresAt: Long?) : ExpiryParse
        data class Invalid(val message: String) : ExpiryParse
    }

    private val DURATION = Regex("^(?:(\\d{1,4})d)?(?:(\\d{1,5})h)?(?:(\\d{1,6})m)?$")

    /**
     * An override's expiry from admin input, relative to [now]:
     * - missing, `never` or `none`: no expiry;
     * - `month-end` (or `end-of-month`, `eom`): the next month start in [zone] (midnight, local time);
     * - a duration `7d`, `12h`, `1d12h`, `90m` (largest unit first): that long from now, 1m..366d.
     */
    fun parseExpiry(text: String?, now: Instant, zone: ZoneId): ExpiryParse {
        val t = text?.trim()?.lowercase().orEmpty()
        if (t.isEmpty() || t == "never" || t == "none") return ExpiryParse.Ok(null)
        if (t == "month-end" || t == "end-of-month" || t == "eom") {
            return ExpiryParse.Ok(CrateModels.nextMonthStart(now, zone).toEpochMilli())
        }
        val m = DURATION.matchEntire(t)
        if (m == null || m.groupValues.drop(1).all { it.isEmpty() }) {
            return ExpiryParse.Invalid("Use a duration like 7d, 12h or 1d12h, 'month-end', or 'never'.")
        }
        val (d, h, min) = m.groupValues.drop(1).map { it.ifEmpty { "0" }.toLong() }
        val millis = ((d * 24L + h) * 60L + min) * 60_000L
        if (millis < MIN_OVERRIDE_MILLIS) return ExpiryParse.Invalid("The duration must be at least 1 minute.")
        if (millis > MAX_OVERRIDE_MILLIS) return ExpiryParse.Invalid("The duration can be at most 366 days.")
        return ExpiryParse.Ok(now.toEpochMilli() + millis)
    }

    private val ADMIN_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

    /** An instant for admins, in the rotation's [zone] (e.g. `2026-12-01 00:00 EST`). */
    fun formatInstant(at: Instant, zone: ZoneId): String = at.atZone(zone).format(ADMIN_TIME)

    fun monthName(month: Month): String = month.name.lowercase().replaceFirstChar { it.uppercase() }

    // ── Which crate a rotating location opens ──────────────────────────

    enum class CrateSource {
        /** The location's own crate: it doesn't rotate, or the month maps no crate (look-only rotation). */
        OWN,
        /** The crate `monthly.crate-ids` maps to the effective month. */
        MONTHLY,
        /** The month maps a crate that doesn't exist: the location's own crate instead, never a guess. */
        FALLBACK,
    }

    data class CrateChoice(val crateId: String, val source: CrateSource, val note: String?)

    /**
     * The crate a location registered as [locationCrateId] opens in [month]. [find] returns the
     * real crate id for a written id (exact, alias or case-insensitive match) or null when there
     * is no such crate; [canonical] resolves aliases for the rotation list.
     */
    fun crateFor(
        settings: CrateModels.Settings,
        locationCrateId: String,
        month: Month,
        find: (String) -> String?,
        canonical: (String) -> String = { it },
    ): CrateChoice {
        if (!CrateModels.rotates(settings, locationCrateId, canonical)) return CrateChoice(locationCrateId, CrateSource.OWN, null)
        val mapped = settings.monthly.crateIdFor(month)
            ?: return CrateChoice(locationCrateId, CrateSource.OWN, "no ${CrateModels.monthId(month)} crate mapped")
        val real = find(mapped)
            ?: return CrateChoice(locationCrateId, CrateSource.FALLBACK, "monthly.crate-ids.${CrateModels.monthId(month)} is '$mapped', which is not a crate")
        return CrateChoice(real, CrateSource.MONTHLY, null)
    }

    /** Every crate id `monthly.crate-ids` maps (resolved through [find]); months that name no crate are skipped. */
    fun mappedCrateIds(settings: CrateModels.Settings, find: (String) -> String?): Set<String> =
        if (!settings.monthly.active) emptySet() else settings.monthly.crateIds.values.mapNotNull(find).toSet()

    /**
     * Why an admin can't switch rotating crates to [month] right now, or null when they can:
     * rotation must be on with at least one existing rotating crate, the month's model must be
     * set and in the bundled pack, and a crate mapped to that month must exist.
     */
    fun overrideProblem(
        settings: CrateModels.Settings,
        month: Month,
        modelAvailable: (String) -> Boolean,
        find: (String) -> String?,
    ): String? {
        val m = settings.monthly
        if (!m.enabled) return "monthly rotation is off (crates.custom-models.monthly.enabled)"
        if (m.crates.isEmpty()) return "no crate rotates (crates.custom-models.monthly.crates is empty)"
        if (m.crates.none { find(it) != null }) return "none of the rotating crates (${m.crates.sorted().joinToString()}) exist"
        val key = m.modelFor(month) ?: return "no ${CrateModels.monthId(month)} model is set (crates.custom-models.monthly.models)"
        if (!modelAvailable(key)) return "the ${CrateModels.monthId(month)} model $key is not in the bundled resource pack"
        m.crateIdFor(month)?.let { mapped ->
            if (find(mapped) == null) return "monthly.crate-ids.${CrateModels.monthId(month)} is '$mapped', which is not a crate"
        }
        return null
    }
}
