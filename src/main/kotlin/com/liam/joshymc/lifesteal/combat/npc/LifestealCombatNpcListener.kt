package com.liam.joshymc.lifesteal.combat.npc

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityTeleportEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.EntitiesLoadEvent

/**
 * Lifesteal Combat NPC events (issue #1402). ListenerManager registers this only where this server
 * hosts Lifesteal; NPCs only exist in Lifesteal worlds, so every handler is a no-op elsewhere.
 */
class LifestealCombatNpcListener(private val npcs: LifestealCombatNpcManager) : Listener {

    /** First, before any other join handler can touch the inventory. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onJoin(event: PlayerJoinEvent) = npcs.onJoin(event.player)

    /** Death: drops are cleared here (the row's items drop instead) and the death is processed once. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) = npcs.onDeath(event)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRemoved(event: EntityRemoveFromWorldEvent) = npcs.onRemoved(event.entity)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        for (entity in event.entities) npcs.removeIfGhost(entity)
    }

    /** Damage toggles for mobs and the environment (player hits are decided at HIGHEST below). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (npcs.sessionOf(event.entity) == null) return
        val causing = event.damageSource.causingEntity
        if (causing is Player) return
        if (!npcs.otherDamageAllowed(fromMob = causing != null)) event.isCancelled = true
    }

    /**
     * Player hits on a Combat NPC, decided last: allowed hits are un-cancelled, so claims, region
     * flags and PvP toggles can't make a combat logger's NPC safe (the narrow safe-zone exception:
     * only this entity, only damage). Denied hits (owner, spectators, Mod Mode, Lifesteal teammates
     * with friendly fire off, player damage turned off) are cancelled.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPlayerHit(event: EntityDamageByEntityEvent) {
        val attacker = event.damageSource.causingEntity as? Player ?: return
        when (npcs.playerHit(event.entity, attacker)) {
            LifestealCombatNpcManager.HitDecision.NOT_NPC -> return
            LifestealCombatNpcManager.HitDecision.ALLOW -> event.isCancelled = false
            LifestealCombatNpcManager.HitDecision.DENY -> event.isCancelled = true
        }
    }

    // ── Passive and locked: no interaction can reach the NPC's items ──

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteract(event: PlayerInteractEntityEvent) {
        if (npcs.isCombatNpc(event.rightClicked)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInteractAt(event: PlayerInteractAtEntityEvent) {
        if (npcs.isCombatNpc(event.rightClicked)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPickup(event: EntityPickupItemEvent) {
        if (npcs.isCombatNpc(event.entity)) event.isCancelled = true
    }

    /** It stays in its world (a portal would move it away from its chunk ticket and its owner's world). */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPortal(event: EntityPortalEvent) {
        if (npcs.isCombatNpc(event.entity)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onTeleport(event: EntityTeleportEvent) {
        if (npcs.isCombatNpc(event.entity)) event.isCancelled = true
    }
}
