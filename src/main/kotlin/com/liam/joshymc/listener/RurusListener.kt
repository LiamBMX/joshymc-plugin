package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.Rurus
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * The Rurus' secret (issue #1205): eating one gives the Saturation status effect, level I for
 * 6 seconds by default (`rurus.hidden-effect` in config.yml). Vanilla Saturation tops up hunger
 * and saturation every tick while it runs, which is the point; nothing here touches the food
 * values directly. Food, hunger and consuming the item stay vanilla Cookie behaviour; this only
 * adds the effect, with no message or extra particles, so nothing gives it away beforehand.
 */
class RurusListener(private val plugin: Joshymc) : Listener {

    /**
     * MONITOR + ignoreCancelled: runs once per finished bite that no other plugin cancelled.
     * Interrupted eating and plain right-clicks never fire this event. The effect goes through
     * vanilla's merge rules (addPotionEffect without force), so a stronger or longer Saturation
     * the player already has is never weakened: a higher level keeps running, a weaker one
     * resumes when this one ends, and eating another Rurus at the same level refreshes it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!plugin.itemManager.isCustomItem(event.item, Rurus.ID)) return
        val player = event.player
        // Refilling hunger mid-round is an edge in hosted games; a Rurus eaten there stays a plain cookie.
        if (plugin.eventManager.games.running?.isPlaying(player) == true) return

        val config = plugin.config
        if (!config.getBoolean("rurus.hidden-effect.enabled", true)) return
        val duration = config.getInt("rurus.hidden-effect.duration-ticks", DEFAULT_DURATION_TICKS)
            .coerceIn(1, MAX_DURATION_TICKS)
        val amplifier = config.getInt("rurus.hidden-effect.amplifier", DEFAULT_AMPLIFIER)
            .coerceIn(0, MAX_AMPLIFIER)
        player.addPotionEffect(PotionEffect(PotionEffectType.SATURATION, duration, amplifier, false, false, true))
    }

    companion object {
        const val DEFAULT_DURATION_TICKS = 120 // 6 seconds
        const val DEFAULT_AMPLIFIER = 0        // zero-indexed: Saturation I

        /** Saturation refills hunger every tick, so a candy should never hand out a long one. */
        const val MAX_AMPLIFIER = 4
        const val MAX_DURATION_TICKS = 20 * 60
    }
}
