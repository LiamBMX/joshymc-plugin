package com.liam.joshymc.lifesteal

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bank.BankConfig
import com.liam.joshymc.lifesteal.bank.BankSettings
import com.liam.joshymc.lifesteal.bank.CoinStealSettings
import com.liam.joshymc.lifesteal.bounty.BountyConfig
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorConfig
import com.liam.joshymc.lifesteal.ranked.stats.DivisionConfig
import com.liam.joshymc.lifesteal.teams.progression.TeamProgressionConfig
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * Loads `lifesteal.yml` (issue #1336) on servers that run Lifesteal, and only there: a Survival
 * server never creates or reads the file. Lifesteal systems (the heart system, issue #1341) read
 * [settings] and must refuse to start while it is null ([status] INVALID). This loader itself
 * has no database tables; the hearts' tables are in `lifesteal/hearts/HeartStore`.
 */
class LifestealConfigManager(private val plugin: Joshymc) {

    enum class Status(val label: String) {
        /** This server runs no Lifesteal world, so the file is neither created nor read. */
        NOT_HOSTED("not used on this server"),
        READY("loaded"),
        INVALID("invalid, see the console"),
    }

    @Volatile var status: Status = Status.NOT_HOSTED
        private set
    /** The validated settings, or null when not hosted or invalid. */
    @Volatile var settings: LifestealSettings? = null
        private set
    @Volatile var problems: List<LifestealConfigParser.Problem> = emptyList()
        private set
    /**
     * Issue #1367: `ranked-team-wars.divisions` / `.leaderboards`, read separately so a mistake in
     * them only turns divisions / leaderboards off (logged) and never pauses the rest of Lifesteal.
     */
    @Volatile var divisions: DivisionConfig.Result = DivisionConfig.Result(null, DivisionConfig.Status.DISABLED, emptyList())
        private set
    @Volatile var leaderboards: DivisionConfig.LeaderboardResult = DivisionConfig.LeaderboardResult(null, emptyList())
        private set
    /**
     * Issue #1401: `bounties`, read separately as well: a mistake there only stops new bounty
     * contributions and claims (existing escrow is still expired, refunded and paid out).
     */
    @Volatile var bounties: BountyConfig.Result = BountyConfig.Result(null, emptyList())
        private set
    /**
     * Issue #1426: `team-progression` (Team Levels, upgrades, team bank, objectives, milestones), read
     * separately too: a mistake there only pauses Team Upgrades, never hearts or teams.
     */
    @Volatile var teamProgression: TeamProgressionConfig.Result = TeamProgressionConfig.Result(null, emptyList())
        private set
    /**
     * Issue #1427: `bank` and `pvp-coins`, each read on its own: a mistake in one only pauses
     * deposits (bank) or coin stealing (pvp-coins), never hearts or the other section.
     */
    @Volatile var bank: BankConfig.Result<BankSettings> = BankConfig.Result(null, emptyList())
        private set
    @Volatile var pvpCoins: BankConfig.Result<CoinStealSettings> = BankConfig.Result(null, emptyList())
        private set
    /** Issue #1474: `ranked-team-wars.monitoring`, read on its own; null settings = monitoring off, nothing else affected. */
    @Volatile var rankedMonitoring: RankedMonitorConfig.Result = RankedMonitorConfig.Result(null, emptyList())
        private set

    /** Called at startup and on every reload, after the network identity is loaded. */
    fun load() {
        if (ServerGamemode.LIFESTEAL !in plugin.networkIdentityManager.hostedGamemodes) {
            status = Status.NOT_HOSTED
            settings = null
            problems = emptyList()
            divisions = DivisionConfig.Result(null, DivisionConfig.Status.DISABLED, emptyList())
            leaderboards = DivisionConfig.LeaderboardResult(null, emptyList())
            bounties = BountyConfig.Result(null, emptyList())
            teamProgression = TeamProgressionConfig.Result(null, emptyList())
            bank = BankConfig.Result(null, emptyList())
            pvpCoins = BankConfig.Result(null, emptyList())
            rankedMonitoring = RankedMonitorConfig.Result(null, emptyList())
            return
        }
        try {
            val file = plugin.configFile(CONFIG_FILE)
            if (!file.exists()) {
                plugin.saveResource(CONFIG_FILE, false)
                plugin.logger.info("[Lifesteal] Created $CONFIG_FILE with the approved defaults.")
            }
            val yaml = YamlConfiguration()
            yaml.load(file) // Throws on broken YAML, unlike loadConfiguration, which would read it as empty.
            val result = LifestealConfigParser.parse { path -> yaml.get(path, null) }
            problems = result.problems
            settings = result.settings
            status = if (result.settings != null) Status.READY else Status.INVALID
            loadRankedDisplay(yaml, result.settings)
            loadBounties(yaml)
            loadTeamProgression(yaml)
            loadBank(yaml)
            loadRankedMonitoring(yaml)
        } catch (e: Exception) {
            problems = listOf(LifestealConfigParser.Problem(true, "Could not read $CONFIG_FILE: ${e.message}"))
            settings = null
            status = Status.INVALID
            bounties = BountyConfig.Result(null, listOf(BountyConfig.Problem(true, "Could not read $CONFIG_FILE: ${e.message}")))
            teamProgression = TeamProgressionConfig.Result(null, listOf(TeamProgressionConfig.Problem(true, "Could not read $CONFIG_FILE: ${e.message}")))
            bank = BankConfig.Result(null, listOf(BankConfig.Problem(true, "Could not read $CONFIG_FILE: ${e.message}")))
            pvpCoins = BankConfig.Result(null, listOf(BankConfig.Problem(true, "Could not read $CONFIG_FILE: ${e.message}")))
            rankedMonitoring = RankedMonitorConfig.Result(null, listOf(RankedMonitorConfig.Problem(true, "Could not read $CONFIG_FILE: ${e.message}")))
        }

        for (problem in problems) {
            if (problem.severe) plugin.logger.severe("[Lifesteal] $CONFIG_FILE: ${problem.message}")
            else plugin.logger.warning("[Lifesteal] $CONFIG_FILE: ${problem.message}")
        }
        val loaded = settings
        if (loaded == null) {
            plugin.logger.severe("[Lifesteal] $CONFIG_FILE is invalid; Lifesteal settings are unavailable until it is fixed (then /joshymc reload).")
        } else {
            plugin.logger.info(
                "[Lifesteal] Settings loaded: hearts ${loaded.hearts.starting} (${loaded.hearts.minimum}-${loaded.hearts.maximum}), " +
                    "teams up to ${loaded.teams.maxSize}, seasons ${loaded.seasons.lengthMonths} months."
            )
        }
    }

    /**
     * Divisions and leaderboard settings (issue #1367). A lifesteal.yml from before them has no
     * section: the bundled file's section is used (logged), so thresholds only ever come from a
     * config file, never from code.
     */
    private fun loadRankedDisplay(yaml: YamlConfiguration, loaded: LifestealSettings?) {
        val bundled by lazy {
            plugin.getResource(CONFIG_FILE)?.reader(Charsets.UTF_8)?.use { YamlConfiguration.loadConfiguration(it) }
        }
        fun pick(path: String): YamlConfiguration? = if (yaml.get(path, null) != null) yaml else bundled?.also {
            plugin.logger.warning("[Lifesteal] $CONFIG_FILE has no $path section; using the bundled defaults (copy the section from the plugin's $CONFIG_FILE to change it).")
        }
        val eloMinimum = (loaded ?: LifestealSettings.DEFAULTS).rankedTeamWars.elo.minimumRating
        val d = pick("ranked-team-wars.divisions")
        divisions = if (d == null) DivisionConfig.Result(null, DivisionConfig.Status.INVALID, listOf(DivisionConfig.Problem(true, "no ranked-team-wars.divisions section found.")))
        else DivisionConfig.parse({ d.get(it, null) }, { (d.get(it, null) as? ConfigurationSection)?.getKeys(false)?.toList() }, eloMinimum)
        val l = pick("ranked-team-wars.leaderboards")
        leaderboards = if (l == null) DivisionConfig.LeaderboardResult(null, listOf(DivisionConfig.Problem(true, "no ranked-team-wars.leaderboards section found.")))
        else DivisionConfig.parseLeaderboards({ l.get(it, null) })
        for (problem in divisions.problems + leaderboards.problems) {
            if (problem.severe) plugin.logger.severe("[Lifesteal] $CONFIG_FILE: ${problem.message}")
            else plugin.logger.warning("[Lifesteal] $CONFIG_FILE: ${problem.message}")
        }
        if (divisions.status == DivisionConfig.Status.INVALID) plugin.logger.severe("[Lifesteal] Ranked divisions are unavailable until ranked-team-wars.divisions is fixed (ratings are untouched).")
        if (leaderboards.settings == null) plugin.logger.severe("[Lifesteal] Ranked leaderboards are unavailable until ranked-team-wars.leaderboards is fixed (ratings are untouched).")
    }

    /**
     * Bounty settings (issue #1401). A lifesteal.yml from before them has no `bounties` section: the
     * bundled file's section is used (logged), like the ranked divisions.
     */
    private fun loadBounties(yaml: YamlConfiguration) {
        val source = if (yaml.get("bounties", null) != null) yaml else {
            plugin.logger.warning("[Lifesteal] $CONFIG_FILE has no bounties section; using the bundled defaults (copy the section from the plugin's $CONFIG_FILE to change it).")
            plugin.getResource(CONFIG_FILE)?.reader(Charsets.UTF_8)?.use { YamlConfiguration.loadConfiguration(it) }
        }
        bounties = if (source == null) BountyConfig.Result(null, listOf(BountyConfig.Problem(true, "no bounties section found.")))
        else BountyConfig.parse { path -> source.get("bounties.$path", null) }
        for (problem in bounties.problems) {
            if (problem.severe) plugin.logger.severe("[Lifesteal] $CONFIG_FILE: ${problem.message}")
            else plugin.logger.warning("[Lifesteal] $CONFIG_FILE: ${problem.message}")
        }
    }

    /**
     * Team progression settings (issue #1426). A lifesteal.yml from before them has no
     * `team-progression` section: the bundled file's section is used (logged), like bounties.
     * Sections inside it are handed to the Bukkit-free parser as plain maps.
     */
    private fun loadTeamProgression(yaml: YamlConfiguration) {
        val source = if (yaml.get(TEAM_PROGRESSION, null) != null) yaml else {
            plugin.logger.warning("[Lifesteal] $CONFIG_FILE has no $TEAM_PROGRESSION section; using the bundled defaults (copy the section from the plugin's $CONFIG_FILE to change it).")
            plugin.getResource(CONFIG_FILE)?.reader(Charsets.UTF_8)?.use { YamlConfiguration.loadConfiguration(it) }
        }
        teamProgression = if (source == null) TeamProgressionConfig.Result(null, listOf(TeamProgressionConfig.Problem(true, "no $TEAM_PROGRESSION section found.")))
        else TeamProgressionConfig.parse { path ->
            when (val value = source.get("$TEAM_PROGRESSION.$path", null)) {
                is ConfigurationSection -> value.getValues(false)
                else -> value
            }
        }
        for (problem in teamProgression.problems) {
            if (problem.severe) plugin.logger.severe("[Lifesteal] $CONFIG_FILE: ${problem.message}")
            else plugin.logger.warning("[Lifesteal] $CONFIG_FILE: ${problem.message}")
        }
    }

    /**
     * Bank and PvP coin settings (issue #1427). A lifesteal.yml from before them has no section:
     * the bundled file's section is used (logged), like the bounties.
     */
    private fun loadBank(yaml: YamlConfiguration) {
        val bundled by lazy { plugin.getResource(CONFIG_FILE)?.reader(Charsets.UTF_8)?.use { YamlConfiguration.loadConfiguration(it) } }
        fun pick(section: String): YamlConfiguration? = if (yaml.get(section, null) != null) yaml else {
            plugin.logger.warning("[Lifesteal] $CONFIG_FILE has no $section section; using the bundled defaults (copy the section from the plugin's $CONFIG_FILE to change it).")
            bundled
        }
        val b = pick("bank")
        bank = if (b == null) BankConfig.Result(null, listOf(BankConfig.Problem(true, "no bank section found.")))
        else BankConfig.parseBank { path -> b.get("bank.$path", null) }
        val c = pick("pvp-coins")
        pvpCoins = if (c == null) BankConfig.Result(null, listOf(BankConfig.Problem(true, "no pvp-coins section found.")))
        else BankConfig.parseCoinSteal { path -> c.get("pvp-coins.$path", null) }
        for (problem in bank.problems + pvpCoins.problems) {
            if (problem.severe) plugin.logger.severe("[Lifesteal] $CONFIG_FILE: ${problem.message}")
            else plugin.logger.warning("[Lifesteal] $CONFIG_FILE: ${problem.message}")
        }
    }

    /**
     * Ranked anti-farming monitoring (issue #1474). A lifesteal.yml from before it has no section:
     * the bundled file's section is used (logged), like the bounties. Invalid = monitoring off only.
     */
    private fun loadRankedMonitoring(yaml: YamlConfiguration) {
        val base = RankedMonitorConfig.BASE
        val source = if (yaml.get(base, null) != null) yaml else {
            plugin.logger.warning("[Lifesteal] $CONFIG_FILE has no $base section; using the bundled defaults (copy the section from the plugin's $CONFIG_FILE to change it).")
            plugin.getResource(CONFIG_FILE)?.reader(Charsets.UTF_8)?.use { YamlConfiguration.loadConfiguration(it) }
        }
        rankedMonitoring = if (source == null) RankedMonitorConfig.Result(null, listOf(RankedMonitorConfig.Problem(true, "no $base section found.")))
        else RankedMonitorConfig.parse { path -> source.get(if (path.isEmpty()) base else "$base.$path", null) }
        for (problem in rankedMonitoring.problems) {
            if (problem.severe) plugin.logger.severe("[Lifesteal] $CONFIG_FILE: ${problem.message}")
            else plugin.logger.warning("[Lifesteal] $CONFIG_FILE: ${problem.message}")
        }
    }

    companion object {
        const val CONFIG_FILE = "lifesteal.yml"
        private const val TEAM_PROGRESSION = "team-progression"
    }
}
