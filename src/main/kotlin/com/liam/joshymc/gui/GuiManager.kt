package com.liam.joshymc.gui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GuiManager : Listener {

    companion object {
        /** Anti-dupe: cooldown between clicks to prevent rapid-fire transaction exploits */
        private const val CLICK_COOLDOWN_MS = 200L
    }

    private val openGuis = ConcurrentHashMap<UUID, CustomGui>()
    private val lastClick = ConcurrentHashMap<UUID, Long>()

    fun open(player: Player, gui: CustomGui) {
        // Set the new GUI first so the close event for the old inventory
        // sees the new GUI and doesn't remove it
        openGuis[player.uniqueId] = gui
        player.openInventory(gui.inventory)
    }

    fun getOpenGui(player: Player): CustomGui? = openGuis[player.uniqueId]

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val gui = openGuis[player.uniqueId] ?: return

        // Only handle if this is actually our GUI's inventory
        if (event.inventory != gui.inventory) return

        // ALWAYS cancel in custom GUIs to prevent item theft
        event.isCancelled = true

        val slot = event.rawSlot
        if (slot < 0 || slot >= gui.inventory.size) return

        // Anti-dupe: enforce click cooldown to prevent rapid-fire exploits
        val now = System.currentTimeMillis()
        val last = lastClick.put(player.uniqueId, now) ?: 0L
        if (now - last < CLICK_COOLDOWN_MS) return

        gui.clickHandlers[slot]?.invoke(player, event)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val gui = openGuis[player.uniqueId] ?: return
        if (event.inventory == gui.inventory) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val gui = openGuis[player.uniqueId] ?: return

        // Only remove if the closing inventory is the currently tracked GUI.
        // If a new GUI was opened (page navigation), the tracked GUI is already
        // the new one, so we should NOT remove it.
        if (event.inventory == gui.inventory) {
            openGuis.remove(player.uniqueId)
            lastClick.remove(player.uniqueId)
            gui.onClose?.invoke(player)
        }
    }
}

class CustomGui(
    val title: Component,
    val size: Int,
    val inventory: Inventory = Bukkit.createInventory(null, size, title)
) {
    val clickHandlers = mutableMapOf<Int, (Player, InventoryClickEvent) -> Unit>()
    var onClose: ((Player) -> Unit)? = null

    fun setItem(slot: Int, item: ItemStack, onClick: ((Player, InventoryClickEvent) -> Unit)? = null) {
        inventory.setItem(slot, item)
        if (onClick != null) {
            clickHandlers[slot] = onClick
        }
    }

    fun fill(item: ItemStack) {
        for (i in 0 until size) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, item)
            }
        }
    }

    fun border(item: ItemStack) {
        for (i in 0 until 9) { inventory.setItem(i, item); inventory.setItem(size - 9 + i, item) }
        for (row in 1 until size / 9 - 1) { inventory.setItem(row * 9, item); inventory.setItem(row * 9 + 8, item) }
    }
}
