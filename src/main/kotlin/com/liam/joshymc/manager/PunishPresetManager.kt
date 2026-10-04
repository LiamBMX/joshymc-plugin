package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.UUID

/**
 * Loads `punishment-presets.yml` and resolves which punishment a preset gives a player.
 * Escalation is driven only by the config plus the player's existing punishment history.
 */
class PunishPresetManager(private val plugin: Joshymc) {

    enum class PresetType(val commandType: String, val needsDuration: Boolean) {
        WARN("warn", false),
        MUTE("tempmute", true),
        TEMPBAN("tempban", true),
        BAN("ban", false);

        val permission get() = "joshymc.punish.$commandType"
    }

    data class Punishment(val type: PresetType, val duration: String?)

    data class Preset(
        val id: String,
        val name: String,
        val material: Material,
        val base: Punishment,
        /** offense number (1-based) -> punishment; empty when no escalation is configured */
        val escalation: Map<Int, Punishment>
    )

    private var presets: List<Preset> = emptyList()

    fun start() {
        val file = plugin.configFile("punishment-presets.yml")
        if (!file.exists()) plugin.saveResource("punishment-presets.yml", false)
        presets = load(YamlConfiguration.loadConfiguration(file))
    }

    /** Re-reads the file; returns the number of enabled presets loaded. */
    fun reload(): Int {
        start()
        return presets.size
    }

    fun getEnabledPresets(): List<Preset> = presets

    private fun load(config: YamlConfiguration): List<Preset> {
        val section = config.getConfigurationSection("presets") ?: return emptyList()
        val result = mutableListOf<Preset>()
        for (id in section.getKeys(false)) {
            val s = section.getConfigurationSection(id) ?: continue
            if (!s.getBoolean("enabled", false)) continue
            val name = s.getString("name")?.trim().orEmpty()
            if (name.isEmpty()) { warn(id, "missing 'name'"); continue }
            val base = parsePunishment(s, null) ?: run { warn(id, "invalid or missing type/duration"); continue }

            val escalation = mutableMapOf<Int, Punishment>()
            var bad = false
            s.getConfigurationSection("escalation")?.let { esc ->
                for (key in esc.getKeys(false)) {
                    val level = key.toIntOrNull()?.takeIf { it >= 1 }
                    val ls = esc.getConfigurationSection(key)
                    val p = if (level != null && ls != null) parsePunishment(ls, base) else null
                    if (level == null || p == null) { warn(id, "invalid escalation level '$key'"); bad = true; break }
                    escalation[level] = p
                }
            }
            if (bad) continue

            val material = s.getString("material")?.let { Material.matchMaterial(it) } ?: Material.PAPER
            result.add(Preset(id, name, material, base, escalation))
        }
        return result
    }

    private fun parsePunishment(s: ConfigurationSection, fallback: Punishment?): Punishment? {
        val type = s.getString("type")?.let { t -> PresetType.entries.firstOrNull { it.name.equals(t.trim(), true) } }
            ?: fallback?.type ?: return null
        if (!type.needsDuration) return Punishment(type, null)
        val duration = s.getString("duration")?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallback?.takeIf { it.type == type }?.duration
        if (duration == null || PunishmentManager.parseDuration(duration) == null) return null
        return Punishment(type, duration)
    }

    private fun warn(id: String, why: String) =
        plugin.logger.warning("[PunishPresets] Skipping preset '$id': $why")

    /** Number of earlier punishments in the player's history issued under this preset's reason. */
    fun priorOffenses(target: UUID, preset: Preset): Int =
        plugin.punishmentManager.getHistory(target).count {
            it.reason.equals(preset.name, ignoreCase = true) &&
                it.type in setOf("WARN", "MUTE", "TEMPMUTE", "BAN", "TEMPBAN")
        }

    /** The punishment to apply: the matching escalation level (capped at the highest) or the base one. */
    fun resolve(target: UUID, preset: Preset): Punishment {
        if (preset.escalation.isEmpty()) return preset.base
        val level = priorOffenses(target, preset) + 1
        val key = preset.escalation.keys.filter { it <= level }.maxOrNull() ?: return preset.base
        return preset.escalation.getValue(key)
    }
}
