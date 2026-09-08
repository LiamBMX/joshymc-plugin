package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.KitManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Handles clicks/drags/close/quit for the /editkit GUI (a raw inventory, not a GuiManager CustomGui). */
class EditKitListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    fun onClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = plugin.kitManager.getEditSession(player) ?: return
        if (event.view.topInventory != session.inventory) return

        val clickedTop = event.clickedInventory == session.inventory
        if (!clickedTop) return // moving items in/out of the player's own inventory is fine

        val slot = event.rawSlot
        if (slot in KitManager.EDIT_CONTROL_SLOTS) {
            // Control buttons can never be picked up, swapped, or dragged into the saved kit.
            event.isCancelled = true
            when (slot) {
                KitManager.EDIT_SAVE_SLOT -> plugin.kitManager.handleEditSave(player)
                KitManager.EDIT_CANCEL_SLOT -> plugin.kitManager.handleEditCancel(player)
                KitManager.EDIT_RELOAD_SLOT -> plugin.kitManager.handleEditReload(player)
            }
            return
        }

        // Content slot: allow free editing, but block double-click "collect similar" so it
        // can't scoop a matching item off of the (occupied) control row by coincidence.
        if (event.click == ClickType.DOUBLE_CLICK) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = plugin.kitManager.getEditSession(player) ?: return
        val topSize = session.inventory.size
        if (event.rawSlots.any { it < topSize && it in KitManager.EDIT_CONTROL_SLOTS }) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = plugin.kitManager.getEditSession(player) ?: return
        if (event.inventory != session.inventory) return
        // Closing without clicking Save/Cancel discards changes (session is already gone if Save/Cancel ran).
        plugin.kitManager.discardEditSession(player, closeInventory = false)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.kitManager.discardEditSession(event.player, closeInventory = false)
    }
}
