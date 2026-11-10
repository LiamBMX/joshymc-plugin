package com.liam.joshymc.lifesteal.eligibility

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.ProjectileLaunchEvent

/**
 * Issue #1487 (D-H4): feeds [CompetitiveEligibilityTracker]. Registered only where Lifesteal is
 * hosted. Only observes (MONITOR, ignoreCancelled): it never changes damage, launches or deaths.
 */
class CompetitiveEligibilityListener(private val plugin: Joshymc) : Listener {

    private val tracker: CompetitiveEligibilityTracker get() = plugin.competitiveEligibility

    /** Every hit that landed on a Lifesteal player or Combat NPC, attributed like the heart system (causing entity). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val attacker = event.damageSource.causingEntity as? Player ?: return
        val entity = event.entity
        if (plugin.gamemodeLockManager.gamemodeOf(entity.world) != ServerGamemode.LIFESTEAL) return
        val victim = (entity as? Player)?.uniqueId
            ?: runCatching { plugin.lifestealCombatNpcManager.ownerOf(entity) }.getOrNull()
            ?: return
        val direct = event.damager.takeIf { it.uniqueId != attacker.uniqueId }
        tracker.onHit(attacker, victim, direct)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        if (plugin.gamemodeLockManager.gamemodeOf(shooter.world) != ServerGamemode.LIFESTEAL) return
        tracker.stampProjectile(event.entity, shooter)
    }
}
