package com.liam.joshymc.lifesteal.teams.progression

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Team Level math (issue #1426), Bukkit-free. A team stores only its **total** Team XP; the level
 * is always derived from it, so there is no separate level counter that could drift or be bumped
 * on its own. Teams start at Level 1 and there is no maximum.
 */
object TeamLevels {

    /** A level loop never runs longer than this (level 1,000,000 needs ~10^12 XP at the default curve). */
    const val MAX_LEVEL = 1_000_000

    /** XP needed to go from [level] to [level] + 1: round(base × level^exponent), at least 1. */
    fun xpForNext(level: Int, levels: TeamProgressionSettings.Levels): Long {
        val raw = levels.base * level.toDouble().pow(levels.exponent)
        if (!raw.isFinite() || raw >= Long.MAX_VALUE / 4) return Long.MAX_VALUE / 4
        return raw.roundToLong().coerceAtLeast(1)
    }

    data class Progress(val level: Int, val xpIntoLevel: Long, val xpForNext: Long, val totalXp: Long) {
        /** 0.0..1.0 towards the next level. */
        val fraction: Double get() = if (xpForNext <= 0) 0.0 else (xpIntoLevel.toDouble() / xpForNext).coerceIn(0.0, 1.0)
    }

    fun progress(totalXp: Long, levels: TeamProgressionSettings.Levels): Progress {
        var level = 1
        var remaining = totalXp.coerceAtLeast(0)
        while (level < MAX_LEVEL) {
            val need = xpForNext(level, levels)
            if (remaining < need) return Progress(level, remaining, need, totalXp)
            remaining -= need
            level++
        }
        return Progress(level, remaining, xpForNext(level, levels), totalXp)
    }

    fun levelFor(totalXp: Long, levels: TeamProgressionSettings.Levels): Int = progress(totalXp, levels).level

    /** Total XP a team needs to reach [level] from 0 XP. */
    fun totalFor(level: Int, levels: TeamProgressionSettings.Levels): Long {
        var sum = 0L
        for (l in 1 until level.coerceIn(1, MAX_LEVEL)) {
            sum += xpForNext(l, levels)
            if (sum >= Long.MAX_VALUE / 4) return Long.MAX_VALUE / 4
        }
        return sum
    }

    fun isMilestone(level: Int, m: TeamProgressionSettings.Milestones): Boolean {
        if (level in m.levels) return true
        val last = m.levels.maxOrNull() ?: return false
        return m.everyAfter > 0 && level > last && (level - last) % m.everyAfter == 0
    }

    /** Milestone levels from 2 up to [upTo] (inclusive), ascending. */
    fun milestonesUpTo(upTo: Int, m: TeamProgressionSettings.Milestones): List<Int> {
        val out = m.levels.filter { it <= upTo }.toMutableList()
        val last = m.levels.maxOrNull() ?: return out
        if (m.everyAfter > 0) {
            var next = last + m.everyAfter
            while (next <= upTo && out.size < 10_000) { out += next; next += m.everyAfter }
        }
        return out
    }

    /** The next [count] milestone levels above [after]. */
    fun nextMilestones(after: Int, count: Int, m: TeamProgressionSettings.Milestones): List<Int> {
        val out = m.levels.filter { it > after }.take(count).toMutableList()
        val last = m.levels.maxOrNull() ?: return out
        if (m.everyAfter > 0) {
            var next = last + m.everyAfter
            while (next <= after) next += m.everyAfter
            while (out.size < count) { out += next; next += m.everyAfter }
        }
        return out
    }

    fun rewardFor(level: Int, m: TeamProgressionSettings.Milestones): TeamProgressionSettings.Reward =
        m.rewards[level] ?: m.defaultReward
}

/** What a team's purchased tiers give (issue #1426). Read from the stored tiers and the current settings. */
data class TeamPerks(
    val storageSlots: Int,
    val extraHomes: Int,
    val bankCapacityCents: Long,
    val xpBonusPercent: Int,
    val colorCount: Int,
    val utility: Set<UtilityFeature>,
) {
    /** Team Utility unlocks, in tier order. */
    enum class UtilityFeature(val label: String) {
        CONTRIBUTIONS("Member contributions"),
        ACTIVITY_LOG("Team XP activity log"),
        SOURCE_BREAKDOWN("XP by source"),
    }

    companion object {
        fun of(tiers: Map<UpgradeCategory, Int>, s: TeamProgressionSettings): TeamPerks {
            fun bought(category: UpgradeCategory): List<TeamProgressionSettings.Tier> =
                s.category(category).tiers.take((tiers[category] ?: 0).coerceAtLeast(0))
            val economyCents = bought(UpgradeCategory.ECONOMY).sumOf { it.value.toLong() * 100 }
            val utilityLevel = bought(UpgradeCategory.UTILITY).maxOfOrNull { it.value } ?: 0
            return TeamPerks(
                storageSlots = bought(UpgradeCategory.STORAGE).sumOf { it.value },
                extraHomes = bought(UpgradeCategory.HOME).sumOf { it.value },
                bankCapacityCents = (s.bank.baseCapacityCents + economyCents).coerceAtMost(com.liam.joshymc.lifesteal.bounty.BountyMoney.MAX_CENTS),
                // Values are totals (2, 4, ... 10), never summed, and never above the hard cap.
                xpBonusPercent = (bought(UpgradeCategory.PROGRESSION).maxOfOrNull { it.value } ?: 0).coerceIn(0, TeamProgressionConfig.MAX_XP_BONUS_PERCENT),
                colorCount = (bought(UpgradeCategory.COSMETICS).maxOfOrNull { it.value } ?: 0).coerceAtMost(s.cosmetics.colors.size),
                utility = UtilityFeature.entries.take(utilityLevel.coerceIn(0, UtilityFeature.entries.size)).toSet(),
            )
        }
    }
}

/** Team colours and titles (cosmetic only). */
object TeamStyle {
    /** Adventure named colours a team may use (no black/dark grey: unreadable in menus). */
    val COLOR_NAMES: List<String> = listOf(
        "gold", "yellow", "aqua", "dark_aqua", "green", "dark_green", "blue", "dark_blue",
        "light_purple", "dark_purple", "red", "dark_red", "white", "gray",
    )
    const val MAX_TITLE_LENGTH = 20
    private val TITLE = Regex("^[A-Za-z0-9][A-Za-z0-9 ]{0,${MAX_TITLE_LENGTH - 1}}$")

    fun validTitle(title: String): Boolean = TITLE.matches(title)

    fun label(color: String): String = color.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}

/** Calendar keys for daily caps and objective cycles, in the configured zone. */
object TeamProgressCalendar {
    fun day(now: Long, zone: ZoneId): String = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    fun week(now: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return "%d-W%02d".format(date.get(IsoFields.WEEK_BASED_YEAR), date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
    }

    fun cycle(cycle: ObjectiveCycle, now: Long, zone: ZoneId, seasonId: String): String = when (cycle) {
        ObjectiveCycle.WEEKLY -> "week:${week(now, zone)}"
        ObjectiveCycle.SEASON -> "season:$seasonId"
        ObjectiveCycle.NEVER -> "permanent"
    }

    /** Millis until the next weekly reset (Monday 00:00 in [zone]). */
    fun untilWeekEnd(now: Long, zone: ZoneId): Long {
        val zoned = Instant.ofEpochMilli(now).atZone(zone)
        var next = zoned.toLocalDate().plusDays(1)
        while (next.dayOfWeek != DayOfWeek.MONDAY) next = next.plusDays(1)
        return next.atStartOfDay(zone).toInstant().toEpochMilli() - now
    }
}

/**
 * Exploration distance (issue #1426), Bukkit-free and per player. The manager samples each
 * player's position every few seconds; a sample only counts horizontal distance that is
 * plausible walking/riding/flying speed (a jump of more than [maxStep] blocks is a teleport and
 * counts nothing). Distance is only credited per window when the player actually got somewhere:
 * if the straight-line displacement over the window is under [minDisplacementRatio] of the path,
 * the player was circling (AFK pools, minecart loops) and the window is dropped.
 */
class ExplorationMeter(
    private val maxStep: Double = 60.0,
    private val windowSamples: Int = 12,
    private val minDisplacementRatio: Double = 0.35,
) {
    private var world: String? = null
    private var lastX = 0.0
    private var lastZ = 0.0
    private var startX = 0.0
    private var startZ = 0.0
    private var path = 0.0
    private var samples = 0
    /** Blocks credited and not yet turned into XP. */
    var credited = 0.0
        private set

    /** [eligible] false (AFK, flying in creative, in a vehicle the server moves, ...) resets the window. */
    fun sample(worldId: String, x: Double, z: Double, eligible: Boolean) {
        if (!eligible || worldId != world) { reset(worldId, x, z); return }
        val dx = x - lastX
        val dz = z - lastZ
        val step = Math.sqrt(dx * dx + dz * dz)
        lastX = x; lastZ = z
        if (step > maxStep) { reset(worldId, x, z); return }
        path += step
        samples++
        if (samples >= windowSamples) {
            val ddx = x - startX
            val ddz = z - startZ
            val displacement = Math.sqrt(ddx * ddx + ddz * ddz)
            if (path > 0 && displacement >= path * minDisplacementRatio) credited += path
            startX = x; startZ = z; path = 0.0; samples = 0
        }
    }

    /** Takes whole blocks out of [credited]. */
    fun take(): Long {
        val whole = credited.toLong()
        credited -= whole
        return whole
    }

    private fun reset(worldId: String, x: Double, z: Double) {
        world = worldId; lastX = x; lastZ = z; startX = x; startZ = z; path = 0.0; samples = 0
    }
}
