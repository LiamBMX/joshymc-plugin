package com.liam.joshymc.lifesteal.progression

import com.liam.joshymc.lifesteal.LifestealSettings
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Lifesteal launch progression (issue #1352), Bukkit-free. Everything is derived from one stored
 * launch time (epoch milliseconds, UTC) plus the schedule in `lifesteal.yml > progression`, and
 * from the milestones already recorded for the season (a recorded milestone stays reached, so a
 * clock going back never relocks anything). [LifestealProgressionManager] feeds it live data and
 * `scripts/LifestealProgressionCheck.kt` tests it with fixed clocks.
 *
 * The approved schedule, counted in elapsed real time from the launch, never calendar dates:
 * launch (grace starts), +24h grace ends, +72h Ranked Team Wars unlock, +168h the End unlocks.
 */
object ProgressionRules {

    /** The first season's id. Later seasons (a later phase) get their own ids and rows. */
    const val FIRST_SEASON_ID = "season-1"

    private val SEASON_ID = Regex("[a-z0-9][a-z0-9-]{0,31}")

    fun validSeasonId(id: String?): Boolean = id != null && SEASON_ID.matches(id)

    const val HOUR_MILLIS = 3_600_000L

    /** The four approved milestones, in order. No others exist. */
    enum class Milestone(val id: String, val label: String) {
        LAUNCH("launch", "Launch"),
        GRACE_ENDED("grace_ended", "Grace period ends"),
        RANKED_UNLOCKED("ranked_unlocked", "Ranked Team Wars unlock"),
        END_UNLOCKED("end_unlocked", "The End unlocks");

        companion object {
            fun fromId(id: String?): Milestone? = entries.firstOrNull { it.id == id }
        }
    }

    /** Hours after the launch for each milestone (validated by the config parser: grace <= ranked <= end). */
    data class Schedule(val graceHours: Int, val rankedHours: Int, val endHours: Int) {
        init {
            require(graceHours >= 0 && rankedHours >= graceHours && endHours >= rankedHours) { "progression must satisfy 0 <= grace <= ranked <= end" }
        }

        fun offsetMillis(milestone: Milestone): Long = when (milestone) {
            Milestone.LAUNCH -> 0L
            Milestone.GRACE_ENDED -> graceHours * HOUR_MILLIS
            Milestone.RANKED_UNLOCKED -> rankedHours * HOUR_MILLIS
            Milestone.END_UNLOCKED -> endHours * HOUR_MILLIS
        }

        companion object {
            fun of(p: LifestealSettings.LaunchProgression) = Schedule(p.gracePeriodHours, p.rankedTeamWarsUnlockAfterHours, p.endUnlockAfterHours)
        }
    }

    /** Where the season stands. Overlapping feature checks are on [Progress], not this label. */
    enum class Phase(val label: String) {
        /** `progression.enabled: false`: no launch schedule, nothing is held back on this server. */
        DISABLED("No launch schedule"),
        /** No launch time has been set yet. */
        NOT_SCHEDULED("Not launched yet"),
        /** A launch time is set and still ahead. */
        PRE_LAUNCH("Launching soon"),
        GRACE_PERIOD("Grace period"),
        NORMAL_GAMEPLAY("Normal gameplay"),
        RANKED_UNLOCKED("Ranked Team Wars unlocked"),
        END_UNLOCKED("The End unlocked"),
        /** The state couldn't be read (invalid lifesteal.yml, database problem): restricted actions are denied. */
        UNKNOWN("Unavailable"),
    }

    /** What the heart system may do with a PvP kill right now. */
    enum class HeartWindow {
        /** Normal Lifesteal PvP: eligible kills move hearts. */
        OPEN,
        /** Not launched yet (or no launch set): no heart moves. */
        NOT_LAUNCHED,
        /** Inside the grace period: no heart moves. */
        GRACE_PERIOD,
        /** The progression state is unknown: no heart moves. */
        UNKNOWN,
    }

    /** The season as stored. */
    data class Season(val id: String, val launchAt: Long, val version: Int)

    /**
     * One evaluation at [now]. [reached] is time-derived or recorded; a later milestone being
     * reached implies every earlier one (so states can't contradict each other).
     */
    data class Progress(
        val phase: Phase,
        val now: Long,
        val season: Season?,
        val schedule: Schedule?,
        val reached: Set<Milestone>,
    ) {
        val launched: Boolean get() = Milestone.LAUNCH in reached
        fun isReached(milestone: Milestone): Boolean = milestone in reached

        /** When [milestone] is scheduled (UTC millis), or null without a launch time. */
        fun at(milestone: Milestone): Long? {
            val s = season ?: return null
            val schedule = schedule ?: return null
            return s.launchAt + schedule.offsetMillis(milestone)
        }

        /** Time left until [milestone]: 0 once reached, null when unknown (no launch time / state unknown). */
        fun remaining(milestone: Milestone): Long? {
            if (isReached(milestone)) return 0L
            val at = at(milestone) ?: return null
            return (at - now).coerceAtLeast(0L)
        }

        /** Hostile PvP between players is blocked (before launch, during grace, and while unknown). */
        val pvpProtected: Boolean get() = phase != Phase.DISABLED && !isReached(Milestone.GRACE_ENDED)

        val endLocked: Boolean get() = phase != Phase.DISABLED && !isReached(Milestone.END_UNLOCKED)

        /** Ranked Team Wars unlocked by schedule (not whether the ranked system exists). */
        val rankedUnlocked: Boolean get() = phase == Phase.DISABLED || isReached(Milestone.RANKED_UNLOCKED)

        val heartWindow: HeartWindow get() = when {
            phase == Phase.DISABLED -> HeartWindow.OPEN
            phase == Phase.UNKNOWN -> HeartWindow.UNKNOWN
            !launched -> HeartWindow.NOT_LAUNCHED
            !isReached(Milestone.GRACE_ENDED) -> HeartWindow.GRACE_PERIOD
            else -> HeartWindow.OPEN
        }
    }

    fun disabled(now: Long) = Progress(Phase.DISABLED, now, null, null, Milestone.entries.toSet())
    fun unknown(now: Long, season: Season? = null, schedule: Schedule? = null) = Progress(Phase.UNKNOWN, now, season, schedule, emptySet())

    /**
     * The progress of [season] at [now]. [recorded] are the milestones stored for this season (only
     * this season's: another season's rows never count). Null [season] = not scheduled.
     */
    fun evaluate(season: Season?, schedule: Schedule, now: Long, recorded: Set<Milestone>): Progress {
        if (season == null) return Progress(Phase.NOT_SCHEDULED, now, null, schedule, emptySet())
        var latest = -1
        for (m in Milestone.entries) {
            val byTime = now >= season.launchAt + schedule.offsetMillis(m)
            if (byTime || m in recorded) latest = m.ordinal
        }
        val reached = Milestone.entries.filter { it.ordinal <= latest }.toSet()
        val phase = when {
            Milestone.END_UNLOCKED in reached -> Phase.END_UNLOCKED
            Milestone.RANKED_UNLOCKED in reached -> Phase.RANKED_UNLOCKED
            Milestone.GRACE_ENDED in reached -> Phase.NORMAL_GAMEPLAY
            Milestone.LAUNCH in reached -> Phase.GRACE_PERIOD
            else -> Phase.PRE_LAUNCH
        }
        return Progress(phase, now, season, schedule, reached)
    }

    /** The millis of the next milestone not yet reached, or null when all are reached / no launch. */
    fun nextMilestoneAt(progress: Progress): Long? =
        Milestone.entries.firstOrNull { !progress.isReached(it) }?.let { progress.at(it) }

    // ── Ranked Team Wars ────────────────────────────────────

    enum class RankedStatus(val label: String) {
        /** Not unlocked by the schedule yet (or the state is unknown). */
        LOCKED("Locked"),
        /** Unlocked by the schedule, but no ranked system is installed or enabled. */
        UNLOCKED_UNAVAILABLE("Unlocked by schedule, not available yet"),
        /** Unlocked and a ranked system says it is enabled. */
        AVAILABLE("Available"),
    }

    fun rankedStatus(progress: Progress, systemAvailable: Boolean): RankedStatus = when {
        progress.phase == Phase.UNKNOWN || !progress.rankedUnlocked -> RankedStatus.LOCKED
        !systemAvailable -> RankedStatus.UNLOCKED_UNAVAILABLE
        else -> RankedStatus.AVAILABLE
    }

    // ── Announcements ───────────────────────────────────────

    /**
     * Which one milestone to announce, given everything [reached] and what was already
     * [announced]: only the latest newly reached one, so a restart after days offline posts one
     * line instead of a backlog. Null when nothing new is reached.
     */
    fun announcementFor(reached: Set<Milestone>, announced: Set<Milestone>): Milestone? =
        Milestone.entries.lastOrNull { it in reached && it !in announced }

    // ── Admin changes ───────────────────────────────────────

    sealed interface ScheduleDecision {
        /** A new season with this launch time. */
        data class Create(val launchAt: Long) : ScheduleDecision
        /** Move a launch that hasn't happened yet. */
        data class Move(val from: Long, val to: Long) : ScheduleDecision
        data class Refused(val reason: String) : ScheduleDecision
    }

    /** `/lifesteal admin launch schedule <timestamp>`: only future times, and never once launched. */
    fun schedule(current: Progress, requested: Long, now: Long): ScheduleDecision {
        if (current.phase == Phase.DISABLED) return ScheduleDecision.Refused("Launch progression is disabled in lifesteal.yml (progression.enabled).")
        if (current.phase == Phase.UNKNOWN) return ScheduleDecision.Refused("The progression state can't be read right now; see the console.")
        if (current.launched) return ScheduleDecision.Refused("This season has already launched; its launch time can't be changed.")
        if (requested <= now) return ScheduleDecision.Refused("The launch time must be in the future. Use /lifesteal admin launch activate to launch now.")
        val season = current.season ?: return ScheduleDecision.Create(requested)
        if (season.launchAt == requested) return ScheduleDecision.Refused("The launch is already scheduled for that time.")
        return ScheduleDecision.Move(season.launchAt, requested)
    }

    /** `/lifesteal admin launch activate`: launch now, once. */
    fun activate(current: Progress, now: Long): ScheduleDecision {
        if (current.phase == Phase.DISABLED) return ScheduleDecision.Refused("Launch progression is disabled in lifesteal.yml (progression.enabled).")
        if (current.phase == Phase.UNKNOWN) return ScheduleDecision.Refused("The progression state can't be read right now; see the console.")
        if (current.launched) return ScheduleDecision.Refused("This season has already launched.")
        val season = current.season ?: return ScheduleDecision.Create(now)
        return ScheduleDecision.Move(season.launchAt, now)
    }

    // ── Timestamps ──────────────────────────────────────────

    /** Earliest and latest launch times accepted (guards typos like year 20266). */
    val MIN_TIMESTAMP: Long = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
    val MAX_TIMESTAMP: Long = Instant.parse("2100-01-01T00:00:00Z").toEpochMilli()

    /**
     * An unambiguous ISO-8601 timestamp with an explicit offset (`2026-12-01T18:00:00Z`,
     * `2026-12-01T13:00:00-05:00`), as UTC millis. A local time without offset is refused
     * (ambiguous), as is anything outside [MIN_TIMESTAMP]..[MAX_TIMESTAMP].
     */
    fun parseTimestamp(raw: String?): Long? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val millis = try {
            OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            return null
        }
        return millis.takeIf { it in MIN_TIMESTAMP until MAX_TIMESTAMP }
    }

    fun formatUtc(millis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis - millis % 1000))

    /** Time left for players: `6d 23h`, `5h 12m`, `12m 30s`, `45s`. */
    fun formatRemaining(millis: Long): String {
        val totalSeconds = ((millis + 999) / 1000).coerceAtLeast(0)
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }
}
