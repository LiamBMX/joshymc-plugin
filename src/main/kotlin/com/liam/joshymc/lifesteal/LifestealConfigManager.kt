package com.liam.joshymc.lifesteal

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.ranked.stats.DivisionConfig
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

    /** Called at startup and on every reload, after the network identity is loaded. */
    fun load() {
        if (ServerGamemode.LIFESTEAL !in plugin.networkIdentityManager.hostedGamemodes) {
            status = Status.NOT_HOSTED
            settings = null
            problems = emptyList()
            divisions = DivisionConfig.Result(null, DivisionConfig.Status.DISABLED, emptyList())
            leaderboards = DivisionConfig.LeaderboardResult(null, emptyList())
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
        } catch (e: Exception) {
            problems = listOf(LifestealConfigParser.Problem(true, "Could not read $CONFIG_FILE: ${e.message}"))
            settings = null
            status = Status.INVALID
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

    companion object {
        const val CONFIG_FILE = "lifesteal.yml"
    }
}
