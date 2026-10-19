package com.liam.joshymc.communityevent.game

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection

/**
 * `hosted-events.<type>` in config.yml (issues #1187, #1189). Read once when a round or match
 * starts, never per tick.
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

/** `hosted-events.sharks` (issue #1189). */
data class SharksSettings(
    val countdownSeconds: Int,
    val minPlayers: Int,
    val startingSharks: Int,
    /** 0 = no limit; Minnows still crossing when it runs out are caught. */
    val crossingTimeLimitSeconds: Int,
    /** Hits from the same Shark closer together than this are ignored (stops a double tag on lag). */
    val catchCooldownMs: Long,
    /** Seconds after a crossing before players are moved back to their spawns for the next one. */
    val transitionDelaySeconds: Int,
    /** Seconds after the line-up before the next crossing starts by itself. 0 = staff start it with /event sharks next. */
    val autoNextSeconds: Int,
) {
    companion object {
        fun read(root: ConfigurationSection?): SharksSettings {
            val c = root?.getConfigurationSection("sharks")
            return SharksSettings(
                countdownSeconds = (c?.getInt("countdown-seconds", 5) ?: 5).coerceIn(1, 30),
                minPlayers = (c?.getInt("min-players", 3) ?: 3).coerceAtLeast(2),
                startingSharks = (c?.getInt("starting-sharks", 1) ?: 1).coerceAtLeast(1),
                crossingTimeLimitSeconds = (c?.getInt("crossing-time-limit-seconds", 60) ?: 60).coerceIn(0, 3600),
                catchCooldownMs = (c?.getLong("catch-cooldown-ms", 300L) ?: 300L).coerceIn(0L, 5_000L),
                transitionDelaySeconds = (c?.getInt("round-transition-delay-seconds", 3) ?: 3).coerceIn(0, 60),
                autoNextSeconds = (c?.getInt("auto-next-seconds", 5) ?: 5).coerceIn(0, 120),
            )
        }
    }
}

/** `hosted-events.cpvp` (issue #1189). */
data class CrystalPvpSettings(
    val countdownSeconds: Int,
    val minPlayers: Int,
    /** 0 = no limit; at the limit the healthier competitor wins, equal health is a draw. */
    val matchTimeLimitSeconds: Int,
    /** A draw (both out in the same tick, or equal health at the time limit): rematch, or both are out. */
    val rematchOnDraw: Boolean,
    /** Blocks competitors may place and break inside the Arena Boundary. */
    val allowedBlocks: Set<Material>,
    val maxArenaBlocks: Int,
    val restoreDelaySeconds: Int,
) {
    companion object {
        fun read(root: ConfigurationSection?): CrystalPvpSettings {
            val c = root?.getConfigurationSection("cpvp")
            val names = if (c != null && c.isList("allowed-blocks")) c.getStringList("allowed-blocks") else listOf("OBSIDIAN")
            val blocks = names
                .mapNotNull { name -> Material.matchMaterial(name)?.takeIf { it.isBlock && !it.isAir } }
                .toSet()
            return CrystalPvpSettings(
                countdownSeconds = (c?.getInt("countdown-seconds", 5) ?: 5).coerceIn(1, 30),
                minPlayers = (c?.getInt("min-players", 2) ?: 2).coerceAtLeast(2),
                matchTimeLimitSeconds = (c?.getInt("match-time-limit-seconds", 0) ?: 0).coerceIn(0, 3600),
                rematchOnDraw = !(c?.getString("on-draw", "rematch") ?: "rematch").equals("both-out", ignoreCase = true),
                allowedBlocks = blocks,
                maxArenaBlocks = (c?.getInt("restore.max-blocks", 100_000) ?: 100_000).coerceIn(1, 500_000),
                restoreDelaySeconds = (c?.getInt("restore.delay-seconds", 2) ?: 2).coerceIn(0, 60),
            )
        }
    }
}

private fun material(name: String?, fallback: Material): Material =
    name?.let { Material.matchMaterial(it) }?.takeIf { it.isItem && !it.isAir } ?: fallback
