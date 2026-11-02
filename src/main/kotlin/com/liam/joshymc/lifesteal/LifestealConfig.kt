package com.liam.joshymc.lifesteal

/**
 * Lifesteal gameplay settings (issue #1336), read from `lifesteal.yml` on servers that run
 * Lifesteal. Hearts and PvP heart stealing (issue #1341, `lifesteal/hearts/`) read [Hearts] and
 * [Pvp]; teams, Ranked Team Wars, seasons and the launch unlocks are later issues, which read
 * these values (and [LifestealRules]) instead of adding their own. Bukkit-free.
 *
 * Defaults are the approved Lifesteal Master Blueprint v1.1 values.
 */
data class LifestealSettings(
    val hearts: Hearts = Hearts(),
    val pvp: Pvp = Pvp(),
    val teams: Teams = Teams(),
    val rankedTeamWars: RankedTeamWars = RankedTeamWars(),
    val seasons: Seasons = Seasons(),
    val progression: LaunchProgression = LaunchProgression(),
) {
    /** Hearts are whole hearts (2 health points each). */
    data class Hearts(val starting: Int = 10, val maximum: Int = 20, val minimum: Int = 1)

    /** Same-victim heart-farming cooldown: a killer gains no heart from the same victim again within it. */
    data class Pvp(val sameVictimCooldownMinutes: Int = 30)

    data class Teams(val maxSize: Int = 5)

    /** Team sizes per side: 2 = 2v2 ... 5 = 5v5. Sorted, distinct. */
    data class RankedTeamWars(val teamSizes: List<Int> = listOf(2, 3, 4, 5))

    data class Seasons(val lengthMonths: Int = 3)

    /**
     * Launch progression, counted from the season launch. Day 1 is the launch day, so
     * "unlocks on day 3" means 48 hours after launch.
     */
    data class LaunchProgression(
        val gracePeriodHours: Int = 24,
        val rankedTeamWarsUnlockDay: Int = 3,
        val endUnlockDay: Int = 7,
    )

    companion object {
        val DEFAULTS = LifestealSettings()
    }
}

/**
 * Approved rules that are deliberately not configurable (blueprint v1.1). Future Lifesteal
 * systems must follow them; making one a setting would add a mechanic the blueprint excludes.
 */
object LifestealRules {
    /** No permanent elimination: a player can never be banned or removed for running out of hearts. */
    const val PERMANENT_ELIMINATION = false
    /** No revival system (nothing to revive from). */
    const val REVIVAL = false
    /** A player can't lose their final heart: hearts never go below [LifestealSettings.Hearts.minimum]. */
    const val CAN_LOSE_FINAL_HEART = false
    /** Killing a player who has exactly 1 heart awards and drops no heart. */
    const val ONE_HEART_VICTIM_GIVES_HEART = false
    /** Ranked Team Wars keep individual and team Elo separately. */
    const val SEPARATE_INDIVIDUAL_AND_TEAM_ELO = true
    /** Players fight Ranked Team Wars with their own Lifesteal gear (no kits). */
    const val RANKED_USES_OWN_EQUIPMENT = true
    /** Inventory and equipment are preserved and restored around a ranked match. */
    const val RANKED_RESTORES_INVENTORY = true
    /** Hearts can still be lost in ranked matches. */
    const val RANKED_HEARTS_AT_RISK = true
}

/** Reads and validates `lifesteal.yml`, Bukkit-free. */
object LifestealConfigParser {

    /** The `config-version` this build reads. Raise it (and migrate) when the layout changes. */
    const val CONFIG_VERSION = 1

    const val MAX_HEARTS = 100
    const val MAX_TEAM_SIZE = 100
    const val MAX_COOLDOWN_MINUTES = 525_600 // one year
    const val MAX_SEASON_MONTHS = 24
    const val MAX_GRACE_HOURS = 720 // 30 days
    /** The shortest a season can be, in days (February); unlock days must fall inside it. */
    const val MIN_DAYS_PER_MONTH = 28

    data class Problem(val severe: Boolean, val message: String)

    /** [settings] is null whenever any problem is severe: future features must not guess. */
    data class Result(val settings: LifestealSettings?, val problems: List<Problem>)

    /**
     * [get] returns the value at a dotted path (`hearts.starting`), or null when the key is
     * missing. Missing keys use the approved default (a warning); wrong values are severe.
     */
    fun parse(get: (String) -> Any?): Result {
        val problems = mutableListOf<Problem>()
        val d = LifestealSettings.DEFAULTS

        when (val version = get("config-version")) {
            null -> problems += Problem(false, "config-version is not set; reading it as version $CONFIG_VERSION.")
            else -> {
                val v = wholeNumber(version)
                if (v == null || v < 1) problems += Problem(true, "config-version '$version' is not a version number.")
                else if (v > CONFIG_VERSION) problems += Problem(
                    true, "config-version $v is newer than this JoshyMC build understands ($CONFIG_VERSION). Update the plugin or restore the matching lifesteal.yml."
                )
            }
        }

        fun int(path: String, default: Int, min: Int, max: Int, meaning: String): Int {
            val raw = get(path)
            if (raw == null) {
                problems += Problem(false, "$path is not set; using the default $default.")
                return default
            }
            val value = wholeNumber(raw)
            if (value == null) {
                problems += Problem(true, "$path '$raw' is not a whole number ($meaning).")
                return default
            }
            if (value < min || value > max) {
                problems += Problem(true, "$path is $value; it must be between $min and $max ($meaning).")
                return default
            }
            return value.toInt()
        }

        val starting = int("hearts.starting", d.hearts.starting, 1, MAX_HEARTS, "hearts a new player starts with")
        val maximum = int("hearts.maximum", d.hearts.maximum, 1, MAX_HEARTS, "most hearts a player can have")
        val minimum = int("hearts.minimum", d.hearts.minimum, 1, MAX_HEARTS, "hearts a player can never drop below; at least 1, players never lose their final heart")
        if (starting > maximum) problems += Problem(true, "hearts.starting ($starting) is more than hearts.maximum ($maximum).")
        if (minimum > starting) problems += Problem(true, "hearts.minimum ($minimum) is more than hearts.starting ($starting).")

        val cooldown = int("pvp.same-victim-cooldown-minutes", d.pvp.sameVictimCooldownMinutes, 0, MAX_COOLDOWN_MINUTES, "minutes before killing the same victim can give a heart again")

        val maxTeam = int("teams.max-size", d.teams.maxSize, 1, MAX_TEAM_SIZE, "most players in one team")

        val sizes = teamSizes(get("ranked-team-wars.team-sizes"), maxTeam, d.rankedTeamWars.teamSizes, problems)

        val months = int("seasons.length-months", d.seasons.lengthMonths, 1, MAX_SEASON_MONTHS, "season length in months")

        val grace = int("progression.grace-period-hours", d.progression.gracePeriodHours, 0, MAX_GRACE_HOURS, "hours of launch grace period; 0 = none")
        val shortestSeasonDays = months * MIN_DAYS_PER_MONTH
        val rankedDay = int("progression.ranked-team-wars-unlock-day", d.progression.rankedTeamWarsUnlockDay, 1, Int.MAX_VALUE, "season day Ranked Team Wars unlock; day 1 = launch day")
        val endDay = int("progression.end-unlock-day", d.progression.endUnlockDay, 1, Int.MAX_VALUE, "season day the End unlocks; day 1 = launch day")
        if (rankedDay > shortestSeasonDays) problems += Problem(true, "progression.ranked-team-wars-unlock-day ($rankedDay) is after the end of a $months-month season.")
        if (endDay > shortestSeasonDays) problems += Problem(true, "progression.end-unlock-day ($endDay) is after the end of a $months-month season.")

        if (problems.any { it.severe }) return Result(null, problems)
        return Result(
            LifestealSettings(
                hearts = LifestealSettings.Hearts(starting, maximum, minimum),
                pvp = LifestealSettings.Pvp(cooldown),
                teams = LifestealSettings.Teams(maxTeam),
                rankedTeamWars = LifestealSettings.RankedTeamWars(sizes),
                seasons = LifestealSettings.Seasons(months),
                progression = LifestealSettings.LaunchProgression(grace, rankedDay, endDay),
            ),
            problems
        )
    }

    private fun teamSizes(raw: Any?, maxTeam: Int, default: List<Int>, problems: MutableList<Problem>): List<Int> {
        val path = "ranked-team-wars.team-sizes"
        if (raw == null) {
            problems += Problem(false, "$path is not set; using the default ${default.joinToString()}.")
            return default
        }
        val list = raw as? List<*>
        if (list == null || list.isEmpty()) {
            problems += Problem(true, "$path must be a list of team sizes, e.g. [2, 3, 4, 5] for 2v2 to 5v5.")
            return default
        }
        val sizes = mutableListOf<Int>()
        for (entry in list) {
            val size = entry?.let { wholeNumber(it) }
            when {
                size == null -> problems += Problem(true, "$path entry '$entry' is not a whole number.")
                size < 2 || size > maxTeam -> problems += Problem(
                    true, "$path entry $size must be between 2 and teams.max-size ($maxTeam): a ranked team can't be bigger than a team."
                )
                size.toInt() in sizes -> problems += Problem(true, "$path lists ${size}v$size twice.")
                else -> sizes += size.toInt()
            }
        }
        return sizes.sorted()
    }

    /** Whole numbers only: 10 and 10.0 are fine, 10.5, "ten" and true are not. Strings that hold a number count. */
    private fun wholeNumber(raw: Any): Long? = when (raw) {
        is Int -> raw.toLong()
        is Long -> raw
        is Short -> raw.toLong()
        is Byte -> raw.toLong()
        is Double -> raw.takeIf { it.isFinite() && it == Math.floor(it) && kotlin.math.abs(it) < 1e15 }?.toLong()
        is Float -> raw.toDouble().takeIf { it.isFinite() && it == Math.floor(it) && kotlin.math.abs(it) < 1e15 }?.toLong()
        is String -> raw.trim().toLongOrNull()
        else -> null
    }
}
