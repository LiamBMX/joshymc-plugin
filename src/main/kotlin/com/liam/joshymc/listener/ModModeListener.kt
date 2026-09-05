package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ModModeManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityResurrectEvent
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
        if (id !in ModModeManager.HOTBAR_ITEM_IDS) return

        // Always cancel default vanilla behavior for every Moderator Mode tool (block
        // placement for the block-based tools like Ender Chest/Chest/Barrel/Packed Ice,
        // throwing, etc.), even if Moderator Mode has somehow been left off, since none
        // of these items should ever act like their vanilla counterpart.
        event.isCancelled = true
        if (!plugin.modModeManager.isModMode(player)) return
        if (id !in SELF_TOOL_IDS) return

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

    /** Defense-in-depth: the block-based tools (Ender Chest/Chest/Barrel/Packed Ice) must
     *  never place their underlying block, even if something bypasses [onInteract]. */
    @EventHandler
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (plugin.modModeManager.isModTool(event.itemInHand)) {
            event.isCancelled = true
        }
    }

    /** Moderator Mode players must never pick up world items — the item stays put for
     *  everyone else, it's simply invisible to this listener's owner. */
    @EventHandler
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (plugin.modModeManager.isModMode(player)) {
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
     * The Moderator Mode toolbar is a fixed 9-slot loadout, not a normal inventory —
     * while active, the player's own inventory (hotbar + storage + armor + offhand, all
     * exposed via the same [org.bukkit.inventory.PlayerInventory] instance) is completely
     * frozen against player-driven clicks. This blocks rearranging/removing tools, moving
     * items in from another open inventory (e.g. an invsee/ecsee target), and swapping
     * tools into armor/offhand — while still allowing edit-permission staff to click
     * around freely inside a *target's* inventory (the top inventory in that case).
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!plugin.modModeManager.isModMode(player)) return

        // Double-click "collect to cursor" gathers matching stacks from BOTH the top and
        // bottom inventory, regardless of which slot was actually clicked.
        if (event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
            return
        }

        // Number-key and swap-offhand clicks swap the hovered slot with a slot in the
        // player's own inventory (a hotbar slot, or the offhand) that isn't necessarily
        // the clicked inventory — so these must always be blocked outright for Mod Mode.
        if (event.click == ClickType.NUMBER_KEY || event.click == ClickType.SWAP_OFFHAND) {
            event.isCancelled = true
            return
        }

        // Never let a tool leave the cursor into anywhere, and never let shift-click move
        // an item from another open inventory into the Mod Mode inventory (or vice versa).
        if (plugin.modModeManager.isModTool(event.cursor) || event.isShiftClick) {
            event.isCancelled = true
            return
        }

        // The player's own inventory (bottom) is fully frozen — no rearranging tools, no
        // swapping in items dragged from elsewhere via the cursor.
        if (event.clickedInventory == player.inventory) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!plugin.modModeManager.isModMode(player)) return

        // Block any drag that touches the player's own inventory, or that carries a tool
        // (a tool should never reach the cursor in the first place, but block regardless).
        val topSize = event.view.topInventory.size
        if (plugin.modModeManager.isModTool(event.oldCursor) || event.rawSlots.any { it >= topSize }) {
            event.isCancelled = true
        }
    }
}
