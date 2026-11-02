package com.liam.joshymc.lifesteal.hearts

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Lifesteal heart events (issue #1341). ListenerManager registers this only on servers that run
 * Lifesteal; every handler hands off to [LifestealHeartManager], which also checks the world.
 */
class LifestealHeartListener(private val hearts: LifestealHeartManager) : Listener {

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) = hearts.sync(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = hearts.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = hearts.sync(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPostRespawn(event: PlayerPostRespawnEvent) = hearts.onPostRespawn(event.player)

    /** MONITOR + ignoreCancelled: only deaths that really happen (another plugin may cancel it). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) = hearts.onDeath(event)

    /**
     * Heart item redemption (issue #1344): right-click with a Heart item. Paper fires this once per
     * hand, so only one hand is handled per click: the main hand when it holds a Heart item, else
     * the off hand. Right-clicking an interactable block (chest, door, ...) still uses the block
     * unless the player sneaks. Not ignoreCancelled: right-clicking air arrives cancelled.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val hand = event.hand ?: return
        if (!hearts.items.isHeart(event.item)) return
        val player = event.player
        event.setUseItemInHand(Event.Result.DENY)
        if (hand == EquipmentSlot.OFF_HAND && hearts.items.isHeart(player.inventory.itemInMainHand)) return
        val block = event.clickedBlock
        if (event.action == Action.RIGHT_CLICK_BLOCK && block != null && block.type.isInteractable && !player.isSneaking) return
        hearts.redeem(player, hand)
    }

    /** Heart items are never crafting ingredients (a Nether Star is part of the beacon recipe). */
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCraft(event: PrepareItemCraftEvent) {
        if (event.inventory.matrix.any { hearts.items.isHeart(it) }) event.inventory.result = null
    }
}
