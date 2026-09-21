package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ModModeManager
import io.papermc.paper.event.player.PlayerPickBlockEvent
import io.papermc.paper.event.player.PlayerPickEntityEvent
import io.papermc.paper.event.player.PlayerPickItemEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Protects the 8 Moderator Mode tools from being dropped, stored, traded,
 * crafted with, or otherwise leaked into normal survival gameplay, and wires
 * each tool's right-click behavior to [com.liam.joshymc.manager.ModModeManager].
 */
class ModModeListener(private val plugin: Joshymc) : Listener {

    companion object {
        private val SELF_TOOL_IDS = setOf("modmode_rtp", "modmode_vanish", "modmode_spectator")
        private val TARGET_TOOL_IDS = setOf(
            "modmode_punish", "modmode_freeze",
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

        // The Vanish tool must only toggle on a right-click — left-click (and other
        // non-right-click actions already excluded above via Action.PHYSICAL) should
        // do nothing rather than flipping vanish state.
        if (id == "modmode_vanish" && event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) {
            return
        }

        when (id) {
            "modmode_rtp" -> plugin.modModeManager.randomTeleport(player)
            "modmode_vanish" -> plugin.modModeManager.toggleVanish(player)
            "modmode_spectator" -> plugin.modModeManager.toggleSpectator(player)
        }
    }

    /**
     * Right-clicking any entity (player or mob) never reaches [onInteract] — Bukkit routes
     * it through here instead, before falling through to [PlayerInteractAtEntityEvent]. The
     * self tools (RTP/Vanish/Spectator) need to fire on entity clicks too, not just air/block,
     * so they're handled here and cancelled up front to stop that fallthrough from also firing
     * a second toggle via [onInteractEntity].
     */
    @EventHandler
    fun onInteractEntitySelf(event: PlayerInteractEntityEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val id = plugin.itemManager.getCustomItemId(player.inventory.itemInMainHand) ?: return
        if (id !in SELF_TOOL_IDS) return

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

    /** Moderator Mode staff must never break world blocks, regardless of gamemode
     *  (Creative would otherwise allow instant-break), rank, permissions, world, or tool. */
    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (!plugin.modModeManager.isModMode(player)) return
        event.isCancelled = true
        plugin.commsManager.sendActionBar(player, Component.text("You cannot break blocks while in staff mode.", NamedTextColor.RED))
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

    /**
     * Pick Block must not let Moderator Mode staff copy/select a block, entity, or item
     * into their hotbar — staff only carry the fixed Mod Mode toolbar. Paper splits pick
     * block into three dedicated events depending on what's targeted.
     */
    @EventHandler
    fun onPickBlock(event: PlayerPickBlockEvent) {
        if (plugin.modModeManager.isModMode(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPickEntity(event: PlayerPickEntityEvent) {
        if (plugin.modModeManager.isModMode(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPickItem(event: PlayerPickItemEvent) {
        if (plugin.modModeManager.isModMode(event.player)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (!event.isSneaking) return
        plugin.modModeManager.handleSpectatorSneak(event.player)
    }

    /**
     * Moderator Mode staff must never be able to damage other players — melee,
     * fists, bows/crossbows/tridents, or any other thrown/shot projectile —
     * even inside a PvP-enabled world or an Arena. Runs at MONITOR so it has
     * the final say after ArenaManager's HIGHEST-priority same-arena PvP
     * override (which un-cancels the event for two players sharing an arena).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val source = event.damager
        val attacker = source as? Player ?: (source as? Projectile)?.shooter as? Player ?: return
        if (plugin.modModeManager.isModMode(attacker)) {
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
    /**
     * Moderator Mode runs staff in Creative purely for flight/noclip convenience — the
     * Creative menu must never hand out items. [InventoryCreativeEvent] is Bukkit's dedicated
     * event for every Creative-only inventory action (taking an item from the creative tabs,
     * middle-click pick-block/clone of world blocks or entities, and dropping a creative-menu
     * item to trash it), and it has its own HandlerList — a generic [InventoryClickEvent]
     * listener does NOT receive it, so it must be handled separately.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onCreativeInventory(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        if (plugin.modModeManager.isModMode(player)) {
            event.isCancelled = true
        }
    }

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
