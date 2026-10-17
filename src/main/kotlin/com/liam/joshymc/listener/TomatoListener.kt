package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.Tomato
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Snowball
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileHitEvent

/**
 * Thrown Tomatoes (issue #1195). A Tomato is a Snowball, so vanilla does the throw (speed,
 * gravity, using one up outside Creative) and the client draws the tomato model in flight,
 * because the projectile carries a copy of the thrown stack. Every protection that already
 * covers snowballs covers tomatoes too: claim PROJECTILES checks, PvP toggles, the event world
 * and hosted games all see an ordinary Snowball.
 *
 * This listener only tells tomatoes apart by the custom item id on the projectile's item and:
 *  - zeroes their damage (vanilla snowballs still hurt blazes), so a hit never hurts anything;
 *  - adds a small splat of red juice and green leaf bits with a wet squish where one lands.
 * The splat is only particles and a sound, nothing is placed or spawned, and it is capped per
 * tick so a crowd throwing at once stays cheap.
 */
class TomatoListener(private val plugin: Joshymc) : Listener {

    companion object {
        /** Most splats drawn per server tick; extra impacts that tick land silently. */
        private const val MAX_SPLATS_PER_TICK = 6
        private val JUICE = Particle.DustOptions(Color.fromRGB(0xD9, 0x2A, 0x1C), 1.1f)
        private val JUICE_DARK = Particle.DustOptions(Color.fromRGB(0x84, 0x11, 0x1D), 0.9f)
        private val LEAF = Particle.DustOptions(Color.fromRGB(0x46, 0x95, 0x37), 0.8f)
    }

    private var splatTick = -1
    private var splatsThisTick = 0

    private fun isTomato(projectile: Any): Boolean =
        projectile is Snowball && plugin.itemManager.isCustomItem(projectile.item, Tomato.ID)

    /** Harmless: whatever the target, a tomato hit deals no damage (knockback stays vanilla). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTomatoDamage(event: EntityDamageByEntityEvent) {
        if (!isTomato(event.damager)) return
        event.damage = 0.0
    }

    /**
     * MONITOR + ignoreCancelled: a hit that a protection refused (e.g. a claim that blocks
     * projectiles cancels it and removes the snowball) shows no splat.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTomatoHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        if (!isTomato(projectile)) return
        val tick = plugin.server.currentTick
        if (tick != splatTick) {
            splatTick = tick
            splatsThisTick = 0
        }
        if (++splatsThisTick > MAX_SPLATS_PER_TICK) return
        splat(event.hitEntity?.let { it.location.add(0.0, it.height * 0.6, 0.0) } ?: projectile.location)
    }

    private fun splat(at: Location) {
        val world = at.world ?: return
        world.spawnParticle(Particle.DUST, at, 10, 0.2, 0.2, 0.2, 0.0, JUICE)
        world.spawnParticle(Particle.DUST, at, 5, 0.25, 0.15, 0.25, 0.0, JUICE_DARK)
        world.spawnParticle(Particle.DUST, at, 3, 0.15, 0.2, 0.15, 0.0, LEAF)
        world.playSound(at, Sound.ENTITY_SLIME_SQUISH_SMALL, SoundCategory.PLAYERS, 0.7f,
            1.25f + (Math.random() * 0.3).toFloat())
    }
}
