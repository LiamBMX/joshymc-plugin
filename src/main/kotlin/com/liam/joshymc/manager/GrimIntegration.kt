package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Auto-installs the JoshyMC violation bridge into Grim AntiCheat's
 * `punishments.yml` so admins don't have to hand-edit it. Detects Grim at
 * startup, drops a single command into the `default` punishment group's
 * threshold-1 list, and never touches anything else.
 *
 * Idempotent — if the bridge entry is already present we skip silently.
 * Disable by setting `integrations.grim.auto-install: false` in JoshyMC's
 * config.yml.
 *
 * Recommended over reflection-based Grim API hooks because Grim's internal
 * API changes between releases; the punishments.yml schema is stable.
 */
class GrimIntegration(private val plugin: Joshymc) {

    private companion object {
        const val MARKER = "jmc-violation"
        const val BRIDGE_COMMAND = "jmc-violation log %player% %check_name% %vl% Grim"
    }

    fun start() {
        if (!plugin.config.getBoolean("integrations.grim.auto-install", true)) {
            plugin.logger.info("[Grim] Auto-install disabled in config (integrations.grim.auto-install).")
            return
        }

        val grim = Bukkit.getPluginManager().getPlugin("GrimAC")
        if (grim == null) {
            plugin.logger.info("[Grim] Grim not detected — skipping integration.")
            return
        }
        if (!grim.isEnabled) {
            plugin.logger.info("[Grim] Grim found but disabled — skipping integration.")
            return
        }

        try {
            installBridge(grim.dataFolder)
        } catch (e: Exception) {
            plugin.logger.warning("[Grim] Failed to auto-install bridge: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun installBridge(grimDataFolder: File) {
        val file = File(grimDataFolder, "punishments.yml")
        if (!file.exists()) {
            plugin.logger.warning("[Grim] punishments.yml not found at ${file.absolutePath} — Grim may not have generated its config yet.")
            return
        }

        val cfg = YamlConfiguration.loadConfiguration(file)

        // Find the "default" punishment group (Grim's stock layout). If a
        // server admin renamed it we play it safe and bail rather than guess.
        val groupName = listOf("default", "Default").firstOrNull { cfg.isConfigurationSection(it) }
        if (groupName == null) {
            plugin.logger.warning("[Grim] No 'default' punishment group found in punishments.yml. Add the bridge manually:")
            plugin.logger.warning("[Grim]   commands: { 1: ['$BRIDGE_COMMAND'] }")
            return
        }

        val group = cfg.getConfigurationSection(groupName)!!
        val commandsSection = group.getConfigurationSection("commands") ?: group.createSection("commands")

        // Pull the threshold-1 command list (the entry that fires on every
        // single violation, BEFORE any kick threshold). Grim represents it as
        // either a number key or a string key depending on YAML quirks.
        val existing = commandsSection.getStringList("1").toMutableList()

        if (existing.any { it.contains(MARKER) }) {
            plugin.logger.info("[Grim] Bridge already installed — skipping.")
            return
        }

        existing.add(BRIDGE_COMMAND)
        commandsSection.set("1", existing)

        cfg.save(file)
        plugin.logger.info("[Grim] Auto-installed bridge into Grim/$groupName/commands/1.")
        plugin.logger.info("[Grim] Grim flags will now appear in /admin → Top Violators.")
    }
}
