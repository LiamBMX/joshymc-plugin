package com.liam.joshymc.communityevent.game

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/**
 * `hosted-events.spleef` / `hosted-events.sumo` in config.yml (issue #1187). Read once when a
 * round or match starts, never per tick.
 */
data class SpleefSettings(
    val countdownSeconds: Int,
    val minPlayers: Int,
    /** 0 = rounds continue until staff end the event. */
    val maxRounds: Int,
    /** 0 = no limit. */
    val roundTimeLimitSeconds: Int,
    val shovel: Material,
    val efficiency: Int,
    val maxFloorBlocks: Int,
    val restoreAfterRound: Boolean,
    val restoreDelaySeconds: Int,
) {
    companion object {
        fun read(root: ConfigurationSection?): SpleefSettings {
            val c = root?.getConfigurationSection("spleef")
            return SpleefSettings(
                countdownSeconds = (c?.getInt("countdown-seconds", 5) ?: 5).coerceIn(1, 30),
                minPlayers = (c?.getInt("min-players", 2) ?: 2).coerceAtLeast(2),
                maxRounds = (c?.getInt("max-rounds", 1) ?: 1).coerceAtLeast(0),
                roundTimeLimitSeconds = (c?.getInt("round-time-limit-seconds", 0) ?: 0).coerceAtLeast(0),
                shovel = material(c?.getString("shovel.material"), Material.DIAMOND_SHOVEL),
                efficiency = (c?.getInt("shovel.efficiency", 5) ?: 5).coerceIn(0, 10),
                maxFloorBlocks = (c?.getInt("floor.max-blocks", 50_000) ?: 50_000).coerceIn(1, 500_000),
                restoreAfterRound = c?.getBoolean("floor.restore-after-round", true) ?: true,
                restoreDelaySeconds = (c?.getInt("floor.restore-delay-seconds", 3) ?: 3).coerceIn(0, 60),
            )
        }
    }
}

data class SumoSettings(
    val countdownSeconds: Int,
    val minPlayers: Int,
    /** Items each competitor gets for a match; empty = bare hands. */
    val loadout: List<Material>,
    val knockbackHorizontal: Double,
    val knockbackVertical: Double,
    val hitCooldownMs: Long,
    /** 0 = staff start every match with /event sumo next. */
    val autoNextSeconds: Int,
) {
    companion object {
        fun read(root: ConfigurationSection?): SumoSettings {
            val c = root?.getConfigurationSection("sumo")
            return SumoSettings(
                countdownSeconds = (c?.getInt("countdown-seconds", 3) ?: 3).coerceIn(1, 30),
                minPlayers = (c?.getInt("min-players", 2) ?: 2).coerceAtLeast(2),
                loadout = (c?.getStringList("loadout") ?: emptyList())
                    .mapNotNull { name -> Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir } }
                    .take(9),
                knockbackHorizontal = (c?.getDouble("knockback.horizontal", 0.42) ?: 0.42).coerceIn(0.0, 3.0),
                knockbackVertical = (c?.getDouble("knockback.vertical", 0.36) ?: 0.36).coerceIn(0.0, 2.0),
                hitCooldownMs = (c?.getLong("knockback.hit-cooldown-ms", 450L) ?: 450L).coerceIn(0L, 5_000L),
                autoNextSeconds = (c?.getInt("auto-next-seconds", 0) ?: 0).coerceIn(0, 120),
            )
        }
    }
}

private fun material(name: String?, fallback: Material): Material =
    name?.let { Material.matchMaterial(it) }?.takeIf { it.isItem && !it.isAir } ?: fallback
