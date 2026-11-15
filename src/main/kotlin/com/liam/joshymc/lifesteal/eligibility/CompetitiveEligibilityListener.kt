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
 * hosted. Only observes (LOWEST read + MONITOR record of hits that weren't cancelled): it never
 * changes damage, launches or deaths.
 */
class CompetitiveEligibilityListener(private val plugin: Joshymc) : Listener {

    private companion object {
        /** Damage events are handled one at a time; anything left over (an exception mid-event) is dropped. */
        const val MAX_PENDING = 64
    }

    private val tracker: CompetitiveEligibilityTracker get() = plugin.competitiveEligibility

    /**
     * Issue #1551: the attacker's movement state when the hit starts. `CombatManager.tag` turns a
     * Survival player's flight off during this same event (at MONITOR), so by [onDamage] a player
     * who hit while flying would already read as grounded. Kept per event, only until [onDamage].
     */
    private val movementAtHit = java.util.IdentityHashMap<EntityDamageByEntityEvent, Set<CompetitiveEligibility.Reason>>()

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDamageStart(event: EntityDamageByEntityEvent) {
        val attacker = event.damageSource.causingEntity as? Player ?: return
        if (plugin.gamemodeLockManager.gamemodeOf(event.entity.world) != ServerGamemode.LIFESTEAL) return
        val reasons = tracker.movementReasons(attacker)
        if (reasons.isEmpty()) return
        if (movementAtHit.size > MAX_PENDING) movementAtHit.clear()
        movementAtHit[event] = reasons
    }

    /** Every hit that landed on a Lifesteal player or Combat NPC, attributed like the heart system (causing entity). */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val before = movementAtHit.remove(event).orEmpty()
        if (event.isCancelled) return
        val attacker = event.damageSource.causingEntity as? Player ?: return
        val entity = event.entity
        if (plugin.gamemodeLockManager.gamemodeOf(entity.world) != ServerGamemode.LIFESTEAL) return
        val victim = (entity as? Player)?.uniqueId
            ?: runCatching { plugin.lifestealCombatNpcManager.ownerOf(entity) }.getOrNull()
            ?: return
        val direct = event.damager.takeIf { it.uniqueId != attacker.uniqueId }
        tracker.onHit(attacker, victim, direct, before)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        if (plugin.gamemodeLockManager.gamemodeOf(shooter.world) != ServerGamemode.LIFESTEAL) return
        tracker.stampProjectile(event.entity, shooter)
    }
}
