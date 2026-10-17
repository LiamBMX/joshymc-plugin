package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.CandyCorn
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * The Candy Corn's secret (issue #1206): eating one gives Regeneration V for 2 seconds by
 * default (`candy-corn.hidden-effect` in config.yml), the real status effect, not instant
 * healing. Food, hunger and consuming the item stay vanilla Cookie behaviour; this only adds
 * the effect, with no message or extra particles, so nothing gives it away beforehand.
 */
class CandyCornListener(private val plugin: Joshymc) : Listener {

    /**
     * MONITOR + ignoreCancelled: runs once per finished bite that no other plugin cancelled.
     * Interrupted eating and plain right-clicks never fire this event. The effect goes through
     * vanilla's merge rules (addPotionEffect without force), so a stronger or longer
     * Regeneration the player already has is never weakened: a higher level keeps running, a
     * weaker one resumes when this one ends, and eating another at the same level refreshes it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!plugin.itemManager.isCustomItem(event.item, CandyCorn.ID)) return
        val player = event.player
        // Healing mid-round is an edge in hosted games; candy corn eaten there stays a plain cookie.
        if (plugin.eventManager.games.running?.isPlaying(player) == true) return

        val config = plugin.config
        if (!config.getBoolean("candy-corn.hidden-effect.enabled", true)) return
        val duration = config.getInt("candy-corn.hidden-effect.duration-ticks", DEFAULT_DURATION_TICKS)
            .coerceIn(1, MAX_DURATION_TICKS)
        val amplifier = config.getInt("candy-corn.hidden-effect.amplifier", DEFAULT_AMPLIFIER)
            .coerceIn(0, MAX_AMPLIFIER)
        player.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, duration, amplifier, false, false, true))
    }

    companion object {
        const val DEFAULT_DURATION_TICKS = 40 // 2 seconds
        const val DEFAULT_AMPLIFIER = 4       // zero-indexed: Regeneration V

        /** Regeneration V heals fast, so a candy should never hand out a long one. */
        const val MAX_AMPLIFIER = 4
        const val MAX_DURATION_TICKS = 20 * 60
    }
}
