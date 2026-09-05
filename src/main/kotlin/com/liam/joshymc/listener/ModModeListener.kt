package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Protects the 9 Moderator Mode tools from being dropped, stored, traded,
 * crafted with, or otherwise leaked into normal survival gameplay, and wires
 * each tool's right-click behavior to [com.liam.joshymc.manager.ModModeManager].
 */
class ModModeListener(private val plugin: Joshymc) : Listener {

    companion object {
        private val SELF_TOOL_IDS = setOf("modmode_rtp", "modmode_vanish", "modmode_spectator")
        private val TARGET_TOOL_IDS = setOf(
            "modmode_punish", "modmode_freeze", "modmode_totemguard",
            "modmode_invsee", "modmode_ecsee", "modmode_vault"
        )
    }

    // ---- Tool activation ----

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action == Action.PHYSICAL) return

        val player = event.player
        val id = plugin.itemManager.getCustomItemId(player.inventory.itemInMainHand) ?: return
        if (id !in SELF_TOOL_IDS) return

        // Always cancel default vanilla behavior for these items (throw/place/etc.), even
        // if Moderator Mode has somehow been left off, since the item shouldn't act normally.
        event.isCancelled = true
        if (!plugin.modModeManager.isModMode(player)) return

        when (id) {
            "modmode_rtp" -> plugin.modModeManager.randomTeleport(player)
            "modmode_vanish" -> plugin.modModeManager.toggleVanish(player)
            "modmode_spectator" -> plugin.modModeManager.toggleSpectator(player)
        }
    }

    @EventHandler
    fun onInteractEntity(event: PlayerInteractAtEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val target = event.rightClicked as? Player ?: return
        val player = event.player
        val id = plugin.itemManager.getCustomItemId(player.inventory.itemInMainHand) ?: return
        if (id !in TARGET_TOOL_IDS) return

        event.isCancelled = true
        if (!plugin.modModeManager.isModMode(player)) return

        when (id) {
            "modmode_punish" -> plugin.modModeManager.openPunish(player, target)
            "modmode_freeze" -> plugin.modModeManager.toggleFreeze(player, target)
            "modmode_totemguard" -> plugin.modModeManager.showTotemGuard(player, target)
            "modmode_invsee" -> plugin.modModeManager.openInvsee(player, target)
            "modmode_ecsee" -> plugin.modModeManager.openEnderChest(player, target)
            "modmode_vault" -> plugin.modModeManager.openVault(player, target)
        }
    }

    // ---- Item protection ----

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (plugin.modModeManager.isModTool(event.itemDrop.itemStack)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (!plugin.modModeManager.isModMode(event.player)) return
        if (plugin.modModeManager.isModTool(event.mainHandItem) || plugin.modModeManager.isModTool(event.offHandItem)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onResurrect(event: EntityResurrectEvent) {
        val entity = event.entity as? Player ?: return
        if (plugin.modModeManager.isModMode(entity)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        plugin.modModeManager.handleDeath(event)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.modModeManager.handleJoin(event.player)
    }

    /**
     * Blocks any click that would move a Moderator Mode tool out of the player's own
     * inventory — into a chest/hopper/crafting-grid/trade GUI/etc. Rearranging the tools
     * within the player's own inventory (hotbar swaps) is left alone since it's harmless.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!plugin.modModeManager.isModMode(player)) return

        val hotbarItem = if (event.click == ClickType.NUMBER_KEY) player.inventory.getItem(event.hotbarButton) else null
        val involvesTool = plugin.modModeManager.isModTool(event.currentItem) ||
            plugin.modModeManager.isModTool(event.cursor) ||
            plugin.modModeManager.isModTool(hotbarItem)
        if (!involvesTool) return

        val clickedInv = event.clickedInventory
        val isOwnInventory = clickedInv != null && clickedInv == player.inventory
        val shiftMovesOut = event.isShiftClick && isOwnInventory && event.view.topInventory != player.inventory

        if (!isOwnInventory || shiftMovesOut) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!plugin.modModeManager.isModMode(player)) return
        if (!plugin.modModeManager.isModTool(event.oldCursor)) return

        val topSize = event.view.topInventory.size
        if (event.rawSlots.any { it < topSize }) {
            event.isCancelled = true
        }
    }
}
