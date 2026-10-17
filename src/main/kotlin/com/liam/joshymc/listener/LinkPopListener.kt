package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.LinkPopColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/**
 * The Link Pops' secret (issue #1207): eating one gives a short Resistance whose level and
 * length depend on the colour (`link-pops.<colour>.hidden-effect` in config.yml, defaults in
 * [DEFAULTS]). Food, hunger and consuming the item stay vanilla Cookie behaviour; this only
 * adds the effect, with no message or extra particles, so nothing gives it away beforehand.
 */
class LinkPopListener(private val plugin: Joshymc) : Listener {

    /**
     * MONITOR + ignoreCancelled: runs once per finished bite that no other plugin cancelled.
     * Interrupted eating and plain right-clicks never fire this event. The effect goes through
     * vanilla's merge rules (addPotionEffect without force), so a stronger or longer Resistance
     * the player already has is never weakened: a higher level keeps running, a weaker one
     * resumes when this one ends, and the same level is only ever extended.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val color = LinkPopColor.fromItemId(plugin.itemManager.getCustomItemId(event.item)) ?: return
        val player = event.player
        // Hosted games strip Resistance as an unfair effect; a Link Pop eaten mid-game stays a plain cookie.
        if (plugin.eventManager.games.running?.isPlaying(player) == true) return

        val effect = DEFAULTS.getValue(color)
        val path = "link-pops.${color.key}.hidden-effect"
        val config = plugin.config
        if (!config.getBoolean("$path.enabled", true)) return
        val duration = config.getInt("$path.duration-ticks", effect.durationTicks).coerceIn(1, MAX_DURATION_TICKS)
        val amplifier = config.getInt("$path.amplifier", effect.amplifier).coerceIn(0, MAX_AMPLIFIER)
        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, duration, amplifier, false, false, true))
    }

    data class HiddenEffect(val amplifier: Int, val durationTicks: Int)

    companion object {
        /** Zero-indexed amplifiers: 0 = Resistance I, 2 = Resistance III. */
        val DEFAULTS = mapOf(
            LinkPopColor.RED to HiddenEffect(amplifier = 0, durationTicks = 100),    // 5 seconds
            LinkPopColor.BLUE to HiddenEffect(amplifier = 0, durationTicks = 120),   // 6 seconds
            LinkPopColor.GREEN to HiddenEffect(amplifier = 1, durationTicks = 140),  // 7 seconds
            LinkPopColor.PURPLE to HiddenEffect(amplifier = 2, durationTicks = 140), // 7 seconds
            LinkPopColor.ORANGE to HiddenEffect(amplifier = 2, durationTicks = 220), // 11 seconds
        )

        /** Resistance V and up is full damage immunity, and a candy should never last forever. */
        const val MAX_AMPLIFIER = 3
        const val MAX_DURATION_TICKS = 20 * 60 * 5
    }
}
