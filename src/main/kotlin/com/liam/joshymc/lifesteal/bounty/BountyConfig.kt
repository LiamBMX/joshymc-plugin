package com.liam.joshymc.lifesteal.bounty

/**
 * `lifesteal.yml > bounties` (issue #1401), Bukkit-free. Read separately from the rest of
 * lifesteal.yml (like the ranked divisions), so a mistake here only turns bounties off and never
 * pauses hearts or teams. A key left out uses the default (a warning); a wrong value is severe and
 * makes the whole section invalid: bounties then refuse new contributions and claims, while the
 * escrow that already exists keeps being expired, refunded and paid out (see [BountySettings]).
 */
data class BountySettings(
    /** false = no new contributions and no new claims. Stored bounties, pending payouts and refunds are kept and still processed. */
    val enabled: Boolean = true,
    val minimumContributionCents: Long = 1_000_000L,
    /** Applied to newly created bounties only: an active bounty keeps the expiration stored when it was created. */
    val expirationMillis: Long = 7L * 24 * 3_600_000L,
    val claimCooldownMillis: Long = 24L * 3_600_000L,
    /** How often due expirations, refunds, payouts and notifications are processed. */
    val sweepIntervalSeconds: Int = 60,
    /** Failed payouts/refunds are retried at most this often. */
    val retryDelaySeconds: Int = 300,
    val antiAbuse: AntiAbuse = AntiAbuse(),
    val gui: Gui = Gui(),
    val messages: Messages = Messages(),
) {
    data class AntiAbuse(
        /** Kills of the same victim by the same killer inside [repeatWindowMillis] that count as suspicious (logged, never blocked). */
        val repeatKillThreshold: Int = 3,
        val repeatWindowMillis: Long = 24L * 3_600_000L,
        /** Log claims where killer and victim have shared a connection (AltManager's hashed addresses). Logged only, never blocked. */
        val logSharedConnections: Boolean = true,
        /** One console line per suspicious claim (always stored for `/bounty admin suspicious`). */
        val consoleLogging: Boolean = true,
    )

    data class Gui(
        val listTitle: String = "&8Lifesteal Bounties",
        val contributeTitle: String = "&8Contribute to Bounty",
        val confirmTitle: String = "&8Confirm Contribution",
        val leaderboardTitle: String = "&8Bounty Hunters",
        /** The amount buttons of the contribution screen, in coins. Values below the minimum are skipped. */
        val presetCents: List<Long> = listOf(1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L, 25_000_000L, 100_000_000L),
    )

    data class Messages(
        val created: String = "&c&l[BOUNTY] &7A &6{amount} &7bounty has been placed on &c{target}&7!",
        val claimed: String = "&c&l[BOUNTY] &e{hunter} &7eliminated &c{target} &7and claimed &6{amount}&7!",
        val targetNotified: String = "&cA bounty of &6{amount} &chas been placed on you! &7It expires in &f{expires}&7. Check &e/bounty&7.",
        val hunterPaid: String = "&aYou claimed the bounty on &c{target} &aand received &6{amount}&a!",
        val refunded: String = "&7The bounty on &c{target} &7expired. Your contribution of &6{amount} &7was refunded.",
        val contributed: String = "&aYou contributed &6{amount} &ato the bounty on &c{target}&a. Total: &6{total}&a.",
    )

    companion object {
        val DEFAULTS = BountySettings()
    }
}

object BountyConfig {

    data class Problem(val severe: Boolean, val message: String)

    data class Result(val settings: BountySettings?, val problems: List<Problem>) {
        val valid: Boolean get() = settings != null
    }

    const val MAX_EXPIRATION_HOURS = 24 * 365
    const val MAX_COOLDOWN_HOURS = 24 * 365

    /**
     * [get] reads a path below `bounties.` (null when missing). Every number is range-checked, so
     * no configured value can overflow a timestamp (`now + expiration`) or an amount.
     */
    fun parse(get: (String) -> Any?): Result {
        val problems = mutableListOf<Problem>()
        val d = BountySettings.DEFAULTS

        fun missing(path: String, default: Any) {
            problems += Problem(false, "bounties.$path is missing; using $default.")
        }

        fun bool(path: String, default: Boolean): Boolean = when (val raw = get(path)) {
            null -> { missing(path, default); default }
            is Boolean -> raw
            else -> { problems += Problem(true, "bounties.$path must be true or false (got '$raw')."); default }
        }

        fun int(path: String, default: Int, min: Int, max: Int): Int {
            val raw = get(path) ?: run { missing(path, default); return default }
            val value = when (raw) {
                is Int -> raw
                is Long -> raw.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                is Short -> raw.toInt()
                else -> null
            }
            if (value == null || value !in min..max) {
                problems += Problem(true, "bounties.$path must be a whole number from $min to $max (got '$raw').")
                return default
            }
            return value
        }

        fun string(path: String, default: String): String = when (val raw = get(path)) {
            null -> { missing(path, "the default text"); default }
            is String -> raw
            else -> { problems += Problem(true, "bounties.$path must be text (got '$raw')."); default }
        }

        val enabled = bool("enabled", d.enabled)
        val minimum = get("minimum-contribution").let { raw ->
            if (raw == null) { missing("minimum-contribution", "10000"); d.minimumContributionCents }
            else BountyMoney.configCents(raw) ?: run {
                problems += Problem(true, "bounties.minimum-contribution must be a positive amount of at most ${BountyMoney.format(BountyMoney.MAX_CENTS)} with at most two decimals (got '$raw').")
                d.minimumContributionCents
            }
        }
        val expirationHours = int("expiration-hours", 168, 1, MAX_EXPIRATION_HOURS)
        val cooldownHours = int("claim-cooldown-hours", 24, 0, MAX_COOLDOWN_HOURS)
        val sweep = int("sweep-interval-seconds", d.sweepIntervalSeconds, 10, 3600)
        val retry = int("retry-delay-seconds", d.retryDelaySeconds, 30, 86_400)

        val antiAbuse = BountySettings.AntiAbuse(
            repeatKillThreshold = int("anti-abuse.repeat-kill-threshold", d.antiAbuse.repeatKillThreshold, 2, 1000),
            repeatWindowMillis = int("anti-abuse.repeat-kill-window-hours", 24, 1, 24 * 30) * 3_600_000L,
            logSharedConnections = bool("anti-abuse.log-shared-connections", d.antiAbuse.logSharedConnections),
            consoleLogging = bool("anti-abuse.console-logging", d.antiAbuse.consoleLogging),
        )

        val presets = when (val raw = get("gui.contribution-presets")) {
            null -> { missing("gui.contribution-presets", "the default buttons"); d.gui.presetCents }
            is List<*> -> {
                val parsed = raw.map { BountyMoney.configCents(it) }
                if (parsed.any { it == null } || parsed.isEmpty() || parsed.size > 7) {
                    problems += Problem(true, "bounties.gui.contribution-presets must be a list of 1 to 7 positive amounts (got '$raw').")
                    d.gui.presetCents
                } else parsed.filterNotNull().distinct().sorted()
            }
            else -> { problems += Problem(true, "bounties.gui.contribution-presets must be a list of amounts (got '$raw')."); d.gui.presetCents }
        }
        val gui = BountySettings.Gui(
            listTitle = string("gui.list-title", d.gui.listTitle),
            contributeTitle = string("gui.contribute-title", d.gui.contributeTitle),
            confirmTitle = string("gui.confirm-title", d.gui.confirmTitle),
            leaderboardTitle = string("gui.leaderboard-title", d.gui.leaderboardTitle),
            presetCents = presets,
        )
        val messages = BountySettings.Messages(
            created = string("messages.created", d.messages.created),
            claimed = string("messages.claimed", d.messages.claimed),
            targetNotified = string("messages.target-notified", d.messages.targetNotified),
            hunterPaid = string("messages.hunter-paid", d.messages.hunterPaid),
            refunded = string("messages.refunded", d.messages.refunded),
            contributed = string("messages.contributed", d.messages.contributed),
        )

        val settings = BountySettings(
            enabled = enabled,
            minimumContributionCents = minimum,
            expirationMillis = expirationHours * 3_600_000L,
            claimCooldownMillis = cooldownHours * 3_600_000L,
            sweepIntervalSeconds = sweep,
            retryDelaySeconds = retry,
            antiAbuse = antiAbuse,
            gui = gui,
            messages = messages,
        )
        return Result(if (problems.any { it.severe }) null else settings, problems)
    }
}
