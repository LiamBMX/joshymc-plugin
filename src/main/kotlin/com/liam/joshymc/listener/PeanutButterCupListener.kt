package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.PeanutButterCup
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * The Peanut Butter Cup's secret (issue #1198): eating one gives Speed III for 6 seconds by
 * default (`peanut-butter-cup.hidden-effect` in config.yml). Food, hunger and consuming the
 * item stay vanilla Cookie behaviour; this only adds the effect, with no message or extra
 * particles, so nothing gives it away before it kicks in.
 */
class PeanutButterCupListener(private val plugin: Joshymc) : Listener {

    /**
     * MONITOR + ignoreCancelled: runs once per finished bite that no other plugin cancelled.
     * The effect goes through vanilla's merge rules (addPotionEffect without force), so a
     * stronger or longer Speed the player already has is never weakened: a higher level
     * keeps running, and a weaker one resumes when this one ends.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!plugin.itemManager.isCustomItem(event.item, PeanutButterCup.ID)) return
        val player = event.player
        // Hosted games strip Speed as an unfair effect; a cup eaten mid-game stays a plain cookie.
        if (plugin.eventManager.games.running?.isPlaying(player) == true) return

        val config = plugin.config
        if (!config.getBoolean("peanut-butter-cup.hidden-effect.enabled", true)) return
        val duration = config.getInt("peanut-butter-cup.hidden-effect.duration-ticks", DEFAULT_DURATION_TICKS)
            .coerceAtLeast(1)
        val amplifier = config.getInt("peanut-butter-cup.hidden-effect.amplifier", DEFAULT_AMPLIFIER)
            .coerceIn(0, 255)
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, duration, amplifier, false, false, true))
    }

    companion object {
        const val DEFAULT_DURATION_TICKS = 120 // 6 seconds
        const val DEFAULT_AMPLIFIER = 2       // zero-indexed: Speed III
    }
}
