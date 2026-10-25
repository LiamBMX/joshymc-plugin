package com.liam.joshymc.punish

import com.liam.joshymc.gamemode.ServerGamemode
import org.bukkit.configuration.ConfigurationSection
import java.util.logging.Logger

/**
 * Reads punish-rules.yml (issue #1298) on top of [PunishRules.DEFAULTS]. Ids, keywords,
 * categories and staff levels always come from code; a rule's name, tiers, gamemodes and window
 * may be overridden. Anything invalid keeps the built-in value and is logged.
 */
object PunishRuleConfig {

    data class Loaded(val registry: PunishRuleRegistry, val settings: PunishSettings, val problems: Int)

    fun load(root: ConfigurationSection, logger: Logger): Loaded {
        var problems = 0
        fun problem(message: String) {
            problems++
            logger.warning("[Punish] punish-rules.yml: $message")
        }

        val s = root.getConfigurationSection("settings")
        val defaults = PunishSettings()
        fun positiveLong(path: String, unit: Long, fallback: Long): Long {
            if (s == null || !s.contains(path)) return fallback
            val value = s.getDouble(path, -1.0)
            if (value <= 0) { problem("settings.$path must be positive; using the default."); return fallback }
            return (value * unit).toLong()
        }
        val settings = PunishSettings(
            minorWindowMs = positiveLong("escalation-window-days.minor", PunishDurations.DAY, defaults.minorWindowMs),
            seriousWindowMs = positiveLong("escalation-window-days.serious", PunishDurations.DAY, defaults.seriousWindowMs),
            confirmationExpiryMs = positiveLong("confirmation-expiry-seconds", PunishDurations.SECOND, defaults.confirmationExpiryMs),
            emergencyNetworkMaxMs = positiveLong("emergency-network-max-hours", PunishDurations.HOUR, defaults.emergencyNetworkMaxMs),
            emergencyReviewMs = positiveLong("emergency-review-hours", PunishDurations.HOUR, defaults.emergencyReviewMs),
            requireEvidenceForBans = s?.getBoolean("require-evidence-for-bans", defaults.requireEvidenceForBans) ?: defaults.requireEvidenceForBans,
        )

        val section = root.getConfigurationSection("rules")
        val known = PunishRules.DEFAULTS.associateBy { it.keyword }
        section?.getKeys(false)?.filter { it !in known }?.forEach { problem("unknown rule '$it' ignored (rules can't be added, only tuned).") }

        val rules = PunishRules.DEFAULTS.map { rule ->
            val r = section?.getConfigurationSection(rule.keyword) ?: return@map rule
            var result = rule

            r.getString("name")?.trim()?.let { name ->
                if (name.isEmpty()) problem("${rule.keyword}.name is blank; keeping \"${rule.displayName}\".")
                else result = result.copy(displayName = name)
            }

            if (r.contains("tiers")) {
                if (rule.informational) {
                    problem("${rule.keyword} is informational and can't have tiers.")
                } else {
                    val parsed = PunishRules.parseTiers(r.getStringList("tiers"))
                    if (parsed == null) problem("${rule.keyword}.tiers must be exactly five valid tiers (W, K, PB, M10m, B7d, +B7d, EXT, W|B1d); keeping the defaults.")
                    else result = result.copy(tiers = parsed)
                }
            }

            if (r.contains("gamemodes")) {
                val raw = r.getStringList("gamemodes")
                val modes = raw.mapNotNull { ServerGamemode.fromId(it) }.toSet()
                if (modes.isEmpty() || modes.size != raw.size) problem("${rule.keyword}.gamemodes must list survival and/or lifesteal; keeping both.")
                else result = result.copy(gamemodes = modes)
            }

            r.getString("window")?.let { raw ->
                val window = EscalationWindow.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                if (window == null) problem("${rule.keyword}.window must be minor or serious.")
                else result = result.copy(window = window)
            }
            result
        }
        return Loaded(PunishRuleRegistry(rules), settings, problems)
    }
}
