package com.liam.joshymc.lifesteal

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
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

    /** Called at startup and on every reload, after the network identity is loaded. */
    fun load() {
        if (ServerGamemode.LIFESTEAL !in plugin.networkIdentityManager.hostedGamemodes) {
            status = Status.NOT_HOSTED
            settings = null
            problems = emptyList()
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

    companion object {
        const val CONFIG_FILE = "lifesteal.yml"
    }
}
