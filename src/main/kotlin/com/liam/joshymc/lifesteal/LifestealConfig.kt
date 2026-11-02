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
    val heartItems: HeartItems = HeartItems(),
) {
    /** Hearts are whole hearts (2 health points each). */
    data class Hearts(val starting: Int = 10, val maximum: Int = 20, val minimum: Int = 1)

    /** Same-victim heart-farming cooldown: a killer gains no heart from the same victim again within it. */
    data class Pvp(val sameVictimCooldownMinutes: Int = 30)

    /**
     * Lifesteal teams (issue #1346, `lifesteal/teams/`). [maxSize] can never exceed
     * [LifestealRules.MAX_TEAM_MEMBERS] (the blueprint's five-player limit; the team store and a
     * database trigger enforce it too). Names are letters, digits and `_`, compared by their
     * lower-case canonical form; [reservedNames] adds to the built-in protected names.
     */
    data class Teams(
        val maxSize: Int = 5,
        val enabled: Boolean = true,
        val inviteExpirationSeconds: Int = 60,
        val allowCreation: Boolean = true,
        val allowDisbanding: Boolean = true,
        val nameMinLength: Int = 3,
        val nameMaxLength: Int = 16,
        val reservedNames: List<String> = emptyList(),
        /**
         * `teams.friendly-fire.enabled` (issue #1348). The blueprint defines no team PvP rule, so
         * this is a setting: false (default) = teammates can't damage each other, true = they can.
         */
        val friendlyFire: Boolean = false,
        /**
         * `teams.friendly-fire.heart-transfers` (issue #1348): whether killing a teammate can steal
         * a heart. Only used while [friendlyFire] is true. The blueprint leaves it open (a gameplay
         * decision still needing approval), so the conservative default is false.
         */
        val teammateHeartTransfers: Boolean = false,
    )

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

    /**
     * Physical Heart items (issue #1344): `/withdrawheart` and right-click redemption. The
     * blueprint doesn't define the item's look or any safeguard timing, so those are settings;
     * the heart limits always come from [Hearts] (nothing here can let the final heart go).
     */
    data class HeartItems(
        val withdrawEnabled: Boolean = true,
        val redeemEnabled: Boolean = true,
        /** `&` colour codes. Only new items use it; existing items are identified by their serial. */
        val displayName: String = "&c&lHeart",
        /** Optional item model (`namespace:path`) for a resource-pack look, or null for the plain item. */
        val itemModel: String? = null,
        val glint: Boolean = true,
        /** Minimum time between two withdrawals or two redemptions by one player. */
        val actionCooldownMillis: Int = 500,
        val messages: HeartItemMessages = HeartItemMessages(),
    )

    /** `&` colour codes; `{maximum}` and `{hearts}` are filled in as plain text. */
    data class HeartItemMessages(
        val withdrawn: String = "&aYou withdrew &c1 Heart&a!",
        val redeemed: String = "&aYou redeemed &c1 Heart&a!",
        val finalHeart: String = "&cYou cannot withdraw your final heart!",
        val maximum: String = "&cYou have already reached the maximum of {maximum} hearts!",
        val inventoryFull: String = "&cYou need inventory space to withdraw a heart!",
        val redeemFailed: String = "&cUnable to redeem this heart. Please try again.",
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
    /** The approved five-player team limit; `teams.max-size` may lower it, never raise it (issue #1346). */
    const val MAX_TEAM_MEMBERS = 5
    /** Teams have exactly one leader and plain members: the blueprint defines no officer rank. */
    const val TEAM_OFFICERS = false
}

/** Reads and validates `lifesteal.yml`, Bukkit-free. */
object LifestealConfigParser {

    /** The `config-version` this build reads. Raise it (and migrate) when the layout changes. */
    const val CONFIG_VERSION = 1

    const val MAX_HEARTS = 100
    /** 100 before issue #1346; the approved blueprint limit is five players. */
    const val MAX_TEAM_SIZE = LifestealRules.MAX_TEAM_MEMBERS
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

        val maxTeam = int("teams.max-size", d.teams.maxSize, 1, MAX_TEAM_SIZE, "most players in one team; the approved limit is $MAX_TEAM_SIZE")
        val teams = teams(get, maxTeam, problems)

        val sizes = teamSizes(get("ranked-team-wars.team-sizes"), maxTeam, d.rankedTeamWars.teamSizes, problems)

        val months = int("seasons.length-months", d.seasons.lengthMonths, 1, MAX_SEASON_MONTHS, "season length in months")

        val grace = int("progression.grace-period-hours", d.progression.gracePeriodHours, 0, MAX_GRACE_HOURS, "hours of launch grace period; 0 = none")
        val shortestSeasonDays = months * MIN_DAYS_PER_MONTH
        val rankedDay = int("progression.ranked-team-wars-unlock-day", d.progression.rankedTeamWarsUnlockDay, 1, Int.MAX_VALUE, "season day Ranked Team Wars unlock; day 1 = launch day")
        val endDay = int("progression.end-unlock-day", d.progression.endUnlockDay, 1, Int.MAX_VALUE, "season day the End unlocks; day 1 = launch day")
        if (rankedDay > shortestSeasonDays) problems += Problem(true, "progression.ranked-team-wars-unlock-day ($rankedDay) is after the end of a $months-month season.")
        if (endDay > shortestSeasonDays) problems += Problem(true, "progression.end-unlock-day ($endDay) is after the end of a $months-month season.")

        val heartItems = heartItems(get, problems)

        if (problems.any { it.severe }) return Result(null, problems)
        return Result(
            LifestealSettings(
                hearts = LifestealSettings.Hearts(starting, maximum, minimum),
                pvp = LifestealSettings.Pvp(cooldown),
                teams = teams,
                rankedTeamWars = LifestealSettings.RankedTeamWars(sizes),
                seasons = LifestealSettings.Seasons(months),
                progression = LifestealSettings.LaunchProgression(grace, rankedDay, endDay),
                heartItems = heartItems,
            ),
            problems
        )
    }

    const val MAX_ACTION_COOLDOWN_MILLIS = 60_000
    private val ITEM_MODEL = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    /**
     * The `heart-items` section (issue #1344). A file from before it existed has no section at all:
     * that is one warning and the defaults, not one warning per key.
     */
    private fun heartItems(get: (String) -> Any?, problems: MutableList<Problem>): LifestealSettings.HeartItems {
        val d = LifestealSettings.HeartItems()
        if (get("heart-items") == null) {
            problems += Problem(false, "heart-items is not set; using the default Heart item settings.")
            return d
        }
        fun bool(key: String, default: Boolean): Boolean {
            val path = "heart-items.$key"
            return when (val raw = get(path)) {
                null -> { problems += Problem(false, "$path is not set; using the default $default."); default }
                is Boolean -> raw
                else -> { problems += Problem(true, "$path '$raw' must be true or false."); default }
            }
        }
        fun text(path: String, default: String): String = when (val raw = get(path)) {
            null -> { problems += Problem(false, "$path is not set; using the default."); default }
            is String -> if (raw.isBlank()) { problems += Problem(true, "$path is empty."); default } else raw
            else -> { problems += Problem(true, "$path must be text."); default }
        }

        val withdraw = bool("withdraw-enabled", d.withdrawEnabled)
        val redeem = bool("redeem-enabled", d.redeemEnabled)
        val name = text("heart-items.display-name", d.displayName)
        val model = when (val raw = get("heart-items.item-model")) {
            null -> null
            is String -> raw.trim().ifEmpty { null }?.let {
                if (ITEM_MODEL.matches(it)) it
                else { problems += Problem(true, "heart-items.item-model '$it' is not a namespace:path item model (or leave it empty)."); null }
            }
            else -> { problems += Problem(true, "heart-items.item-model must be text (namespace:path) or empty."); null }
        }
        val glint = bool("glint", d.glint)
        val cooldown = when (val raw = get("heart-items.action-cooldown-millis")) {
            null -> { problems += Problem(false, "heart-items.action-cooldown-millis is not set; using the default ${d.actionCooldownMillis}."); d.actionCooldownMillis }
            else -> {
                val value = wholeNumber(raw)
                if (value == null || value < 0 || value > MAX_ACTION_COOLDOWN_MILLIS) {
                    problems += Problem(true, "heart-items.action-cooldown-millis '$raw' must be a whole number from 0 to $MAX_ACTION_COOLDOWN_MILLIS.")
                    d.actionCooldownMillis
                } else value.toInt()
            }
        }
        val m = d.messages
        val messages = LifestealSettings.HeartItemMessages(
            withdrawn = text("heart-items.messages.withdrawn", m.withdrawn),
            redeemed = text("heart-items.messages.redeemed", m.redeemed),
            finalHeart = text("heart-items.messages.final-heart", m.finalHeart),
            maximum = text("heart-items.messages.maximum", m.maximum),
            inventoryFull = text("heart-items.messages.inventory-full", m.inventoryFull),
            redeemFailed = text("heart-items.messages.redeem-failed", m.redeemFailed),
        )
        return LifestealSettings.HeartItems(withdraw, redeem, name, model, glint, cooldown, messages)
    }

    const val MIN_INVITE_SECONDS = 10
    const val MAX_INVITE_SECONDS = 3_600
    /** Longest team name allowed at all (chat, GUI titles and lists stay readable). */
    const val MAX_TEAM_NAME_LENGTH = 16
    private val TEAM_KEYS = listOf(
        "enabled", "invite-expiration-seconds", "allow-creation", "allow-disbanding", "name-min-length", "name-max-length", "reserved-names",
        "friendly-fire.enabled", "friendly-fire.heart-transfers",
    )

    /**
     * The team-system keys of `teams` (issue #1346). A file from before them only has `max-size`:
     * that is one warning listing the missing keys, not one per key.
     */
    private fun teams(get: (String) -> Any?, maxSize: Int, problems: MutableList<Problem>): LifestealSettings.Teams {
        val d = LifestealSettings.Teams()
        val missing = TEAM_KEYS.filter { get("teams.$it") == null }
        if (missing.isNotEmpty()) {
            problems += Problem(false, "teams: ${missing.joinToString()} not set; using the default team settings for them.")
        }
        fun bool(key: String, default: Boolean): Boolean = when (val raw = get("teams.$key")) {
            null -> default
            is Boolean -> raw
            else -> { problems += Problem(true, "teams.$key '$raw' must be true or false."); default }
        }
        fun number(key: String, default: Int, min: Int, max: Int, meaning: String): Int {
            val raw = get("teams.$key") ?: return default
            val value = wholeNumber(raw)
            if (value == null || value < min || value > max) {
                problems += Problem(true, "teams.$key '$raw' must be a whole number from $min to $max ($meaning).")
                return default
            }
            return value.toInt()
        }
        val enabled = bool("enabled", d.enabled)
        val invite = number("invite-expiration-seconds", d.inviteExpirationSeconds, MIN_INVITE_SECONDS, MAX_INVITE_SECONDS, "seconds before a team invitation expires")
        val creation = bool("allow-creation", d.allowCreation)
        val disbanding = bool("allow-disbanding", d.allowDisbanding)
        val minLength = number("name-min-length", d.nameMinLength, 1, MAX_TEAM_NAME_LENGTH, "shortest team name")
        val maxLength = number("name-max-length", d.nameMaxLength, 1, MAX_TEAM_NAME_LENGTH, "longest team name")
        if (minLength > maxLength) problems += Problem(true, "teams.name-min-length ($minLength) is more than teams.name-max-length ($maxLength).")
        val reserved = when (val raw = get("teams.reserved-names")) {
            null -> d.reservedNames
            is List<*> -> raw.mapNotNull { entry ->
                val text = (entry as? String)?.trim()
                if (text.isNullOrEmpty()) {
                    problems += Problem(true, "teams.reserved-names entry '$entry' must be a name.")
                    null
                } else text
            }
            else -> { problems += Problem(true, "teams.reserved-names must be a list of names."); d.reservedNames }
        }
        val friendlyFire = bool("friendly-fire.enabled", d.friendlyFire)
        val heartTransfers = bool("friendly-fire.heart-transfers", d.teammateHeartTransfers)
        if (heartTransfers && !friendlyFire) {
            problems += Problem(false, "teams.friendly-fire.heart-transfers is true but friendly fire is off, so it has no effect (teammates can't hurt each other).")
        }
        return LifestealSettings.Teams(maxSize, enabled, invite, creation, disbanding, minLength, maxLength, reserved, friendlyFire, heartTransfers)
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
