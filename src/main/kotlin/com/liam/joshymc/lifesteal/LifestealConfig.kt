package com.liam.joshymc.lifesteal

/**
 * Lifesteal gameplay settings (issue #1336), read from `lifesteal.yml` on servers that run
 * Lifesteal. Hearts and PvP heart stealing (issue #1341, `lifesteal/hearts/`) read [Hearts] and
 * [Pvp]; teams, the launch progression (issue #1352), and later Ranked Team Wars and seasons read
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
    val combatLogging: CombatLogging = CombatLogging(),
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

    /**
     * Team sizes per side: 2 = 2v2 ... 5 = 5v5. Sorted, distinct. [countdownSeconds] and
     * [maxMatchMinutes] are the match lifecycle settings of issue #1361 (`lifesteal/ranked/`): the
     * countdown after everyone is in the arena, and the time limit after which a match is cancelled
     * without a winner (infrastructure safety, not a ranked rule).
     */
    data class RankedTeamWars(
        val teamSizes: List<Int> = listOf(2, 3, 4, 5),
        val countdownSeconds: Int = 10,
        val maxMatchMinutes: Int = 20,
    )

    data class Seasons(val lengthMonths: Int = 3)

    /**
     * Launch progression (issue #1352, `lifesteal/progression/`), counted in elapsed hours from the
     * season's launch time, never calendar dates: Day 0 = launch, grace ends at +24h (Day 1),
     * Ranked Team Wars unlock at +72h (Day 3), the End at +168h (Day 7). Always
     * `grace <= ranked <= end`. [launchTimestamp] (UTC millis) only seeds a database that has no
     * season yet; the stored season is authoritative after that.
     */
    data class LaunchProgression(
        val enabled: Boolean = true,
        val launchTimestamp: Long? = null,
        val gracePeriodHours: Int = 24,
        val rankedTeamWarsUnlockAfterHours: Int = 72,
        val endUnlockAfterHours: Int = 168,
        val announcements: ProgressionAnnouncements = ProgressionAnnouncements(),
        /** One chat line on join while the season hasn't reached normal PvP yet. */
        val joinReminder: Boolean = true,
    )

    /** `&` colour codes. Broadcast to Lifesteal players only, under the announcement header. */
    data class ProgressionAnnouncements(
        val enabled: Boolean = true,
        val launch: String = "&a&lLifesteal is open!\n&7The &e24-hour grace period &7has started: no PvP and no heart stealing until it ends.",
        val graceEnded: String = "&c&lThe grace period is over!\n&7PvP is on and kills now steal hearts.",
        val rankedUnlocked: String = "&6&lRanked Team Wars are unlocked!",
        /** Used instead of [rankedUnlocked] while no ranked system is installed and enabled. */
        val rankedUnlockedUnavailable: String = "&6&lRanked Team Wars are unlocked by schedule.\n&7They open as soon as the ranked system is ready.",
        val endUnlocked: String = "&5&lThe End is now open!\n&7End portals work from now on.",
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

    /**
     * Combat logging (issue #1350, `lifesteal/combat/`). The combat-tag duration is not here: it is
     * the existing `config.yml > combat.tag-duration-seconds` (the blueprint defines none). The
     * blueprint defines no combat-log penalty, so [heartPenalty] can only be [CombatLogHeartPenalty.NONE]
     * and the only consequence is the combat-log NPC every server already had ([leaveNpc]).
     */
    data class CombatLogging(
        /** Record combat-log incidents and apply the Lifesteal disconnect rules. */
        val enabled: Boolean = true,
        /** Counted disconnects still leave the existing combat-log NPC (unchanged behaviour). */
        val leaveNpc: Boolean = true,
        /** Connection timeouts count as combat logs (Paper can't tell a pulled cable from a crash). */
        val countTimeouts: Boolean = true,
        val heartPenalty: CombatLogHeartPenalty = CombatLogHeartPenalty.NONE,
        /** Chat lines when combat starts and ends (the action-bar countdown is always shown). */
        val notifications: Boolean = true,
        /** Incidents older than this are deleted at startup (1..3650 days). */
        val retentionDays: Int = 90,
        /** One console line per incident. */
        val diagnosticLogging: Boolean = false,
        val messages: CombatMessages = CombatMessages(),
    )

    /** `&` colour codes. */
    data class CombatMessages(
        val tagged: String = "&cYou are now in combat! Do not disconnect.",
        val expired: String = "&aYou are no longer in combat.",
    )

    companion object {
        val DEFAULTS = LifestealSettings()
    }
}

/**
 * Heart penalties for combat logging (issue #1350). Blueprint v1.1 defines none, so [NONE] is the
 * only value; a later approved penalty is added here (and applied through the heart store, which
 * keeps the 1-heart minimum) instead of being invented.
 */
enum class CombatLogHeartPenalty(val id: String) {
    NONE("none");

    companion object {
        fun fromId(raw: String?): CombatLogHeartPenalty? = entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
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
        val countdown = int("ranked-team-wars.countdown-seconds", d.rankedTeamWars.countdownSeconds, 3, 60, "seconds of countdown before a ranked match starts")
        val maxMatch = int("ranked-team-wars.max-match-minutes", d.rankedTeamWars.maxMatchMinutes, 1, 120, "minutes before an unfinished ranked match is cancelled without a winner")

        val months = int("seasons.length-months", d.seasons.lengthMonths, 1, MAX_SEASON_MONTHS, "season length in months")

        val progression = progression(get, months * MIN_DAYS_PER_MONTH * 24, problems)

        val heartItems = heartItems(get, problems)
        val combatLogging = combatLogging(get, problems)

        if (problems.any { it.severe }) return Result(null, problems)
        return Result(
            LifestealSettings(
                hearts = LifestealSettings.Hearts(starting, maximum, minimum),
                pvp = LifestealSettings.Pvp(cooldown),
                teams = teams,
                rankedTeamWars = LifestealSettings.RankedTeamWars(sizes, countdown, maxMatch),
                seasons = LifestealSettings.Seasons(months),
                progression = progression,
                heartItems = heartItems,
                combatLogging = combatLogging,
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

    /** The day-numbered keys of issue #1336 ("day 1 = launch day"), replaced by hours from launch in issue #1352. */
    private val LEGACY_UNLOCK_DAYS = listOf(
        Triple("progression.ranked-team-wars-unlock-day", "progression.ranked-team-wars-unlock-after-hours", 3),
        Triple("progression.end-unlock-day", "progression.end-unlock-after-hours", 7),
    )

    /**
     * The `progression` section (issue #1352). Hours are elapsed time from the launch and must keep
     * grace <= ranked <= end inside the shortest season ([maxHours]). The launch timestamp must be
     * a quoted ISO-8601 time with an offset; anything else is severe (never guessed).
     */
    private fun progression(get: (String) -> Any?, maxHours: Int, problems: MutableList<Problem>): LifestealSettings.LaunchProgression {
        val d = LifestealSettings.LaunchProgression()
        if (get("progression") == null) {
            problems += Problem(false, "progression is not set; using the default launch schedule (no launch time).")
            return d
        }
        fun bool(path: String, default: Boolean): Boolean = when (val raw = get(path)) {
            null -> { problems += Problem(false, "$path is not set; using the default $default."); default }
            is Boolean -> raw
            else -> { problems += Problem(true, "$path '$raw' must be true or false."); default }
        }
        fun hours(path: String, default: Int, min: Int, max: Int, meaning: String): Int {
            val raw = get(path)
            if (raw == null) {
                problems += Problem(false, "$path is not set; using the default $default.")
                return default
            }
            val value = wholeNumber(raw)
            if (value == null || value < min || value > max) {
                problems += Problem(true, "$path '$raw' must be a whole number of hours from $min to $max ($meaning).")
                return default
            }
            return value.toInt()
        }
        fun text(path: String, default: String): String = when (val raw = get(path)) {
            null -> { problems += Problem(false, "$path is not set; using the default."); default }
            is String -> if (raw.isBlank()) { problems += Problem(true, "$path is empty."); default } else raw
            else -> { problems += Problem(true, "$path must be text."); default }
        }

        val enabled = bool("progression.enabled", d.enabled)
        val launch = when (val raw = get("progression.launch-timestamp")) {
            null -> null
            is String -> if (raw.isBlank()) null else com.liam.joshymc.lifesteal.progression.ProgressionRules.parseTimestamp(raw) ?: run {
                problems += Problem(
                    true, "progression.launch-timestamp '$raw' is not a valid launch time. Use an ISO-8601 time with an offset in quotes, " +
                        "e.g. \"2026-12-01T18:00:00Z\" or \"2026-12-01T13:00:00-05:00\" (or leave it empty)."
                )
                null
            }
            is java.util.Date -> {
                problems += Problem(true, "progression.launch-timestamp must be in quotes (YAML read it as a date without knowing its time zone), e.g. \"2026-12-01T18:00:00Z\".")
                null
            }
            else -> { problems += Problem(true, "progression.launch-timestamp must be a quoted ISO-8601 time or empty."); null }
        }
        val grace = hours("progression.grace-period-hours", d.gracePeriodHours, 0, minOf(MAX_GRACE_HOURS, maxHours), "hours of launch grace period; 0 = none")
        for ((legacy, replacement, legacyDefault) in LEGACY_UNLOCK_DAYS) {
            val old = get(legacy) ?: continue
            when {
                get(replacement) != null -> problems += Problem(false, "$legacy is no longer read (it is replaced by $replacement); remove it.")
                wholeNumber(old) == legacyDefault.toLong() -> problems += Problem(
                    false, "$legacy is no longer read: the schedule now counts hours from launch. Using $replacement's default; replace the old key."
                )
                else -> problems += Problem(
                    true, "$legacy ($old) is no longer read and counted days from \"day 1 = launch day\"; set $replacement (hours after launch) instead and remove it."
                )
            }
        }
        val ranked = hours("progression.ranked-team-wars-unlock-after-hours", d.rankedTeamWarsUnlockAfterHours, 0, maxHours, "hours after launch Ranked Team Wars unlock")
        val end = hours("progression.end-unlock-after-hours", d.endUnlockAfterHours, 0, maxHours, "hours after launch the End unlocks")
        if (ranked < grace) problems += Problem(true, "progression.ranked-team-wars-unlock-after-hours ($ranked) is before the grace period ends ($grace).")
        if (end < ranked) problems += Problem(true, "progression.end-unlock-after-hours ($end) is before Ranked Team Wars unlock ($ranked).")

        val a = d.announcements
        val announcements = if (get("progression.announcements") == null) {
            problems += Problem(false, "progression.announcements is not set; using the default milestone announcements.")
            a
        } else LifestealSettings.ProgressionAnnouncements(
            enabled = bool("progression.announcements.enabled", a.enabled),
            launch = text("progression.announcements.launch", a.launch),
            graceEnded = text("progression.announcements.grace-ended", a.graceEnded),
            rankedUnlocked = text("progression.announcements.ranked-unlocked", a.rankedUnlocked),
            rankedUnlockedUnavailable = text("progression.announcements.ranked-unlocked-unavailable", a.rankedUnlockedUnavailable),
            endUnlocked = text("progression.announcements.end-unlocked", a.endUnlocked),
        )
        val reminder = bool("progression.join-reminder", d.joinReminder)
        return LifestealSettings.LaunchProgression(enabled, launch, grace, ranked, end, announcements, reminder)
    }

    const val MAX_RETENTION_DAYS = 3_650

    /**
     * The `combat-logging` section (issue #1350). A file from before it existed has no section: one
     * warning and the defaults. An unknown heart penalty is severe (never guessed).
     */
    private fun combatLogging(get: (String) -> Any?, problems: MutableList<Problem>): LifestealSettings.CombatLogging {
        val d = LifestealSettings.CombatLogging()
        if (get("combat-logging") == null) {
            problems += Problem(false, "combat-logging is not set; using the default combat-logging settings.")
            return d
        }
        fun bool(key: String, default: Boolean): Boolean {
            val path = "combat-logging.$key"
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
        val enabled = bool("enabled", d.enabled)
        val npc = bool("leave-npc", d.leaveNpc)
        val timeouts = bool("count-timeouts", d.countTimeouts)
        val penalty = when (val raw = get("combat-logging.heart-penalty")) {
            null -> { problems += Problem(false, "combat-logging.heart-penalty is not set; using none."); d.heartPenalty }
            else -> CombatLogHeartPenalty.fromId(raw.toString()) ?: run {
                problems += Problem(
                    true, "combat-logging.heart-penalty '$raw' is not allowed: blueprint v1.1 defines no combat-log heart penalty, so the only value is none."
                )
                d.heartPenalty
            }
        }
        val notifications = bool("notifications", d.notifications)
        val retention = when (val raw = get("combat-logging.retention-days")) {
            null -> { problems += Problem(false, "combat-logging.retention-days is not set; using the default ${d.retentionDays}."); d.retentionDays }
            else -> {
                val value = wholeNumber(raw)
                if (value == null || value < 1 || value > MAX_RETENTION_DAYS) {
                    problems += Problem(true, "combat-logging.retention-days '$raw' must be a whole number from 1 to $MAX_RETENTION_DAYS.")
                    d.retentionDays
                } else value.toInt()
            }
        }
        val diagnostics = bool("diagnostic-logging", d.diagnosticLogging)
        val messages = LifestealSettings.CombatMessages(
            tagged = text("combat-logging.messages.tagged", d.messages.tagged),
            expired = text("combat-logging.messages.expired", d.messages.expired),
        )
        return LifestealSettings.CombatLogging(enabled, npc, timeouts, penalty, notifications, retention, diagnostics, messages)
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
