package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.JosheesBar
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * Joshy's Bar's secret (issue #1204): eating one gives Strength III for 9 seconds by
 * default (`joshees-bar.hidden-effect` in config.yml). Food, hunger and consuming the item
 * stay vanilla Cookie behaviour; this only adds the effect, with no message or extra
 * particles, so nothing gives it away before it kicks in.
 */
class JosheesBarListener(private val plugin: Joshymc) : Listener {

    /**
     * MONITOR + ignoreCancelled: runs once per finished bite that no other plugin cancelled.
     * Interrupted eating and plain right-clicks never fire this event. The effect goes through
     * vanilla's merge rules (addPotionEffect without force), so a stronger or longer Strength
     * the player already has is never weakened: a higher level keeps running, a weaker one
     * resumes when this one ends, and eating another bar at the same level refreshes it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!plugin.itemManager.isCustomItem(event.item, JosheesBar.ID)) return
        val player = event.player
        // Hosted games strip Strength as an unfair effect; a bar eaten mid-game stays a plain cookie.
        if (plugin.eventManager.games.running?.isPlaying(player) == true) return

        val config = plugin.config
        if (!config.getBoolean("joshees-bar.hidden-effect.enabled", true)) return
        val duration = config.getInt("joshees-bar.hidden-effect.duration-ticks", DEFAULT_DURATION_TICKS)
            .coerceIn(1, MAX_DURATION_TICKS)
        val amplifier = config.getInt("joshees-bar.hidden-effect.amplifier", DEFAULT_AMPLIFIER)
            .coerceIn(0, MAX_AMPLIFIER)
        player.addPotionEffect(PotionEffect(PotionEffectType.STRENGTH, duration, amplifier, false, false, true))
    }

    companion object {
        const val DEFAULT_DURATION_TICKS = 180 // 9 seconds
        const val DEFAULT_AMPLIFIER = 2       // zero-indexed: Strength III

        /** A candy should never become a lasting buff or a one-hit-kill. */
        const val MAX_AMPLIFIER = 4
        const val MAX_DURATION_TICKS = 20 * 60 * 5
    }
}
