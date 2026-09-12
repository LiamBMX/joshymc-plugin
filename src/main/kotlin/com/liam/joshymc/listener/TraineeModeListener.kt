package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.TraineeModeManager
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Protects the 6 Trainee Mode tools from being dropped, stored, traded,
 * crafted with, or otherwise leaked into normal survival gameplay, and wires
 * each tool's right-click behavior to [TraineeModeManager]. Mirrors
 * ModModeListener's protection pattern.
 */
class TraineeModeListener(private val plugin: Joshymc) : Listener {

    companion object {
        private val SELF_TOOL_IDS = setOf("trainee_tp", "trainee_staffchat", "trainee_reports")
        private val TARGET_TOOL_IDS = setOf("trainee_inspector", "trainee_invsee", "trainee_history")
    }

    // ---- Tool activation ----

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action == Action.PHYSICAL) return

        val player = event.player
        val id = plugin.itemManager.getCustomItemId(player.inventory.itemInMainHand) ?: return
        if (id !in TraineeModeManager.HOTBAR_ITEM_IDS) return

        // Always cancel default vanilla behavior for every Trainee Mode tool (block
        // placement for Chest/Redstone Torch, book-editing for the Writable Book, etc.)
        event.isCancelled = true
        if (!plugin.traineeModeManager.isTraineeMode(player)) return
        if (id !in SELF_TOOL_IDS) return

        when (id) {
            "trainee_tp" -> plugin.traineeModeManager.openTeleportMenu(player)
            "trainee_staffchat" -> plugin.traineeModeManager.toggleStaffChat(player)
            "trainee_reports" -> plugin.traineeModeManager.openReports(player)
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
        if (!plugin.traineeModeManager.isTraineeMode(player)) return

        when (id) {
            "trainee_inspector" -> plugin.traineeModeManager.openPlayerInspector(player, target)
            "trainee_invsee" -> plugin.traineeModeManager.openInventoryInspector(player, target)
            "trainee_history" -> plugin.traineeModeManager.openPunishmentHistory(player, target)
        }
    }

    // ---- Item protection ----

    @EventHandler
    fun onDrop(event: PlayerDropItemEvent) {
        if (plugin.traineeModeManager.isTraineeTool(event.itemDrop.itemStack)) {
            event.isCancelled = true
        }
    }

    /** Defense-in-depth: the block-based tools (Chest/Redstone Torch) must never
     *  place their underlying block, even if something bypasses [onInteract]. */
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (plugin.traineeModeManager.isTraineeTool(event.itemInHand)) {
            event.isCancelled = true
        }
    }

    /** Trainee Mode players must never pick up world items. */
    @EventHandler
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (plugin.traineeModeManager.isTraineeMode(player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (!plugin.traineeModeManager.isTraineeMode(event.player)) return
        if (plugin.traineeModeManager.isTraineeTool(event.mainHandItem) || plugin.traineeModeManager.isTraineeTool(event.offHandItem)) {
            event.isCancelled = true
        }
    }

    /** Trainee Mode is observation-only — a Trainee should never be able to deal
     *  damage (melee or projectile) while it's active, per the issue spec. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val source = event.damager
        val attacker = source as? Player ?: (source as? Projectile)?.shooter as? Player ?: return
        if (plugin.traineeModeManager.isTraineeMode(attacker)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: PlayerDeathEvent) {
        plugin.traineeModeManager.handleDeath(event)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        plugin.traineeModeManager.handleJoin(event.player)
    }

    /**
     * While Trainee Mode is active, the player's own inventory (hotbar + storage +
     * armor + offhand) is frozen against player-driven clicks — same rationale as
     * ModModeListener: the toolbar is a fixed loadout, not a normal inventory.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!plugin.traineeModeManager.isTraineeMode(player)) return

        if (event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
            return
        }

        if (event.click == ClickType.NUMBER_KEY || event.click == ClickType.SWAP_OFFHAND) {
            event.isCancelled = true
            return
        }

        if (plugin.traineeModeManager.isTraineeTool(event.cursor) || event.isShiftClick) {
            event.isCancelled = true
            return
        }

        if (event.clickedInventory == player.inventory) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!plugin.traineeModeManager.isTraineeMode(player)) return

        val topSize = event.view.topInventory.size
        if (plugin.traineeModeManager.isTraineeTool(event.oldCursor) || event.rawSlots.any { it >= topSize }) {
            event.isCancelled = true
        }
    }
}
