package com.liam.joshymc.lifesteal.hearts

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.block.Crafter
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryType
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

    /**
     * Issue #1369: the Crafter block crafts without a PrepareItemCraftEvent, so a hopper-fed Heart
     * item could still become a beacon (the heart is gone, its serial stays ISSUED forever).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCrafter(event: CrafterCraftEvent) {
        val crafter = event.block.state as? Crafter ?: return
        if (crafter.inventory.contents.any { hearts.items.isHeart(it) }) event.isCancelled = true
    }

    /**
     * Issue #1369: a beacon takes a Nether Star as payment, which would use a Heart item up.
     * Heart items can't be put into a beacon's payment slot (click, shift-click, hotbar key, drag).
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBeaconClick(event: InventoryClickEvent) {
        val top = event.view.topInventory
        if (top.type != InventoryType.BEACON) return
        val intoBeacon = when {
            event.isShiftClick -> event.clickedInventory != top && hearts.items.isHeart(event.currentItem)
            event.clickedInventory != top -> false
            event.click == ClickType.NUMBER_KEY ->
                hearts.items.isHeart(event.whoClicked.inventory.getItem(event.hotbarButton))
            event.click == ClickType.SWAP_OFFHAND -> hearts.items.isHeart(event.whoClicked.inventory.itemInOffHand)
            else -> hearts.items.isHeart(event.cursor)
        }
        if (intoBeacon) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBeaconDrag(event: InventoryDragEvent) {
        val top = event.view.topInventory
        if (top.type != InventoryType.BEACON || !hearts.items.isHeart(event.oldCursor)) return
        if (event.rawSlots.any { it < top.size }) event.isCancelled = true
    }
}
