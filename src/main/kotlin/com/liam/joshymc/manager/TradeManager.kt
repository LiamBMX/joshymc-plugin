package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TradeManager(private val plugin: Joshymc) : Listener {

    companion object {
        val DIVIDER_SLOTS = setOf(4, 13, 22, 31, 40, 49)
        const val P1_CONFIRM_SLOT = 0
        const val P2_CONFIRM_SLOT = 8
        val P1_SLOTS = setOf(
            1, 2, 3,
            9, 10, 11, 12,
            18, 19, 20, 21,
            27, 28, 29, 30,
            36, 37, 38, 39,
            45, 46, 47, 48
        )
        val P2_SLOTS = setOf(
            5, 6, 7,
            14, 15, 16, 17,
            23, 24, 25, 26,
            32, 33, 34, 35,
            41, 42, 43, 44,
            50, 51, 52, 53
        )
    }

    data class ActiveTrade(
        val player1: UUID,
        val player2: UUID,
        val inventory: Inventory,
        var p1Confirmed: Boolean = false,
        var p2Confirmed: Boolean = false,
        var countdownTaskId: Int = -1,
        var countdownValue: Int = -1,
        var completing: Boolean = false // Anti-dupe: prevents double-execution
    )

    data class TradeRequest(
        val sender: UUID,
        val target: UUID,
        val expiresAt: Long
    )

    private val activeTrades = ConcurrentHashMap<UUID, ActiveTrade>()
    private val pendingRequests = ConcurrentHashMap<UUID, TradeRequest>()

    // ---- Trade request system ----

    fun sendRequest(sender: Player, target: Player) {
        if (sender.uniqueId == target.uniqueId) {
            plugin.commsManager.send(sender, Component.text("You cannot trade with yourself.", NamedTextColor.RED))
            return
        }

        if (target.hasMetadata("NPC")) {
            plugin.commsManager.send(sender, Component.text("You cannot trade with NPCs.", NamedTextColor.RED))
            return
        }

        if (activeTrades.containsKey(sender.uniqueId)) {
            plugin.commsManager.send(sender, Component.text("You are already in a trade.", NamedTextColor.RED))
            return
        }

        if (activeTrades.containsKey(target.uniqueId)) {
            plugin.commsManager.send(sender, Component.text("That player is already in a trade.", NamedTextColor.RED))
            return
        }

        if (plugin.combatManager.isTagged(sender)) {
            plugin.commsManager.send(sender, Component.text("You cannot trade while in combat.", NamedTextColor.RED))
            return
        }

        if (plugin.combatManager.isTagged(target)) {
            plugin.commsManager.send(sender, Component.text("That player is in combat.", NamedTextColor.RED))
            return
        }

        // Check if target already has a pending request FROM sender
        val existing = pendingRequests[target.uniqueId]
        if (existing != null && existing.sender == sender.uniqueId && System.currentTimeMillis() < existing.expiresAt) {
            plugin.commsManager.send(sender, Component.text("You already sent a trade request to that player.", NamedTextColor.RED))
            return
        }

        // Check if sender has a pending request FROM target — accept it
        val incoming = pendingRequests[sender.uniqueId]
        if (incoming != null && incoming.sender == target.uniqueId && System.currentTimeMillis() < incoming.expiresAt) {
            pendingRequests.remove(sender.uniqueId)
            startTrade(target, sender)
            return
        }

        // Send new request
        pendingRequests[target.uniqueId] = TradeRequest(sender.uniqueId, target.uniqueId, System.currentTimeMillis() + 30_000)

        plugin.commsManager.send(sender, Component.text("Trade request sent to ${target.name}.", NamedTextColor.GREEN))

        val clickable = Component.text("[Click to accept]", NamedTextColor.GREEN, TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/trade ${sender.name}"))

        plugin.commsManager.send(
            target,
            Component.text("${sender.name} wants to trade! ", NamedTextColor.YELLOW).append(clickable)
        )

        // Schedule expiry cleanup
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
            val req = pendingRequests[target.uniqueId]
            if (req != null && req.sender == sender.uniqueId) {
                pendingRequests.remove(target.uniqueId)
            }
        }, 600L) // 30 seconds
    }

    // ---- Trade lifecycle ----

    fun startTrade(player1: Player, player2: Player) {
        val title = Component.text("Trade: ${player1.name} \u2194 ${player2.name}")
        val inventory = Bukkit.createInventory(null, 54, title)

        // Set up divider panes
        val dividerPane = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text(" ")) }
        }
        for (slot in DIVIDER_SLOTS) {
            inventory.setItem(slot, dividerPane)
        }

        // Set up confirm buttons
        inventory.setItem(P1_CONFIRM_SLOT, createUnconfirmedButton())
        inventory.setItem(P2_CONFIRM_SLOT, createUnconfirmedButton())

        val trade = ActiveTrade(player1.uniqueId, player2.uniqueId, inventory)
        activeTrades[player1.uniqueId] = trade
        activeTrades[player2.uniqueId] = trade

        player1.openInventory(inventory)
        player2.openInventory(inventory)

        plugin.commsManager.send(player1, Component.text("Trade opened with ${player2.name}.", NamedTextColor.GREEN))
        plugin.commsManager.send(player2, Component.text("Trade opened with ${player1.name}.", NamedTextColor.GREEN))
    }

    fun cancelTrade(trade: ActiveTrade) {
        // Anti-dupe: if already completing/cancelled, do nothing
        if (trade.completing) return
        trade.completing = true

        // Cancel countdown if running
        if (trade.countdownTaskId != -1) {
            plugin.server.scheduler.cancelTask(trade.countdownTaskId)
            trade.countdownTaskId = -1
        }

        // Remove from active trades FIRST to prevent re-entry
        activeTrades.remove(trade.player1)
        activeTrades.remove(trade.player2)

        val p1 = plugin.server.getPlayer(trade.player1)
        val p2 = plugin.server.getPlayer(trade.player2)

        // Return items
        returnItems(p1, trade.inventory, P1_SLOTS)
        returnItems(p2, trade.inventory, P2_SLOTS)

        // Close inventories (schedule to avoid modifying during event)
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
            if (p1 != null && p1.openInventory.topInventory == trade.inventory) {
                p1.closeInventory()
            }
            if (p2 != null && p2.openInventory.topInventory == trade.inventory) {
                p2.closeInventory()
            }
        }, 1L)

        p1?.let { plugin.commsManager.send(it, Component.text("Trade cancelled.", NamedTextColor.RED)) }
        p2?.let { plugin.commsManager.send(it, Component.text("Trade cancelled.", NamedTextColor.RED)) }
    }

    private fun executeTrade(trade: ActiveTrade) {
        // Anti-dupe: if already completing/cancelled, do nothing
        if (trade.completing) return
        trade.completing = true

        if (trade.countdownTaskId != -1) {
            plugin.server.scheduler.cancelTask(trade.countdownTaskId)
            trade.countdownTaskId = -1
        }

        // Remove from active trades FIRST to prevent re-entry from close events
        activeTrades.remove(trade.player1)
        activeTrades.remove(trade.player2)

        val p1 = plugin.server.getPlayer(trade.player1)
        val p2 = plugin.server.getPlayer(trade.player2)

        // Collect items from each side
        val p1Items = P1_SLOTS.mapNotNull { trade.inventory.getItem(it)?.clone() }
        val p2Items = P2_SLOTS.mapNotNull { trade.inventory.getItem(it)?.clone() }

        // Clear the trade inventory so close event doesn't double-return
        for (slot in P1_SLOTS + P2_SLOTS) {
            trade.inventory.setItem(slot, null)
        }

        // Give P1 the items from P2's side, and vice versa
        giveItems(p1, p2Items)
        giveItems(p2, p1Items)

        // Close inventories
        plugin.server.scheduler.scheduleSyncDelayedTask(plugin, Runnable {
            if (p1 != null && p1.openInventory.topInventory == trade.inventory) {
                p1.closeInventory()
            }
            if (p2 != null && p2.openInventory.topInventory == trade.inventory) {
                p2.closeInventory()
            }
        }, 1L)

        p1?.let { plugin.commsManager.send(it, Component.text("Trade completed!", NamedTextColor.GREEN)) }
        p2?.let { plugin.commsManager.send(it, Component.text("Trade completed!", NamedTextColor.GREEN)) }
    }

    // ---- Confirm / countdown logic ----

    private fun handleConfirmClick(player: Player, trade: ActiveTrade) {
        val isP1 = player.uniqueId == trade.player1

        if (isP1) trade.p1Confirmed = true else trade.p2Confirmed = true

        // Update the clicked player's confirm button to confirmed
        val slot = if (isP1) P1_CONFIRM_SLOT else P2_CONFIRM_SLOT
        trade.inventory.setItem(slot, createConfirmedButton())

        if (trade.p1Confirmed && trade.p2Confirmed) {
            startCountdown(trade)
        } else {
            plugin.commsManager.send(player, Component.text("Confirmed! Waiting for other player...", NamedTextColor.GREEN))
        }
    }

    private fun startCountdown(trade: ActiveTrade) {
        trade.countdownValue = 5

        updateCountdownDisplay(trade)

        trade.countdownTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            trade.countdownValue--

            if (trade.countdownValue <= 0) {
                plugin.server.scheduler.cancelTask(trade.countdownTaskId)
                trade.countdownTaskId = -1
                executeTrade(trade)
                return@Runnable
            }

            updateCountdownDisplay(trade)
        }, 20L, 20L)
    }

    private fun cancelCountdown(trade: ActiveTrade) {
        if (trade.countdownTaskId != -1) {
            plugin.server.scheduler.cancelTask(trade.countdownTaskId)
            trade.countdownTaskId = -1
            trade.countdownValue = -1
        }

        trade.p1Confirmed = false
        trade.p2Confirmed = false

        trade.inventory.setItem(P1_CONFIRM_SLOT, createUnconfirmedButton())
        trade.inventory.setItem(P2_CONFIRM_SLOT, createUnconfirmedButton())

        val p1 = plugin.server.getPlayer(trade.player1)
        val p2 = plugin.server.getPlayer(trade.player2)
        p1?.let { plugin.commsManager.send(it, Component.text("Trade confirmation reset.", NamedTextColor.YELLOW)) }
        p2?.let { plugin.commsManager.send(it, Component.text("Trade confirmation reset.", NamedTextColor.YELLOW)) }
    }

    private fun updateCountdownDisplay(trade: ActiveTrade) {
        val countdownPane = createCountdownButton(trade.countdownValue)
        trade.inventory.setItem(P1_CONFIRM_SLOT, countdownPane.clone())
        trade.inventory.setItem(P2_CONFIRM_SLOT, countdownPane.clone())
    }

    // ---- Button creation ----

    private fun createUnconfirmedButton(): ItemStack {
        return ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text("Click to Confirm", NamedTextColor.RED)) }
        }
    }

    private fun createConfirmedButton(): ItemStack {
        return ItemStack(Material.LIME_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text("Confirmed! Waiting...", NamedTextColor.GREEN)) }
        }
    }

    private fun createCountdownButton(seconds: Int): ItemStack {
        return ItemStack(Material.GREEN_STAINED_GLASS_PANE, seconds).apply {
            editMeta { it.displayName(Component.text("$seconds...", NamedTextColor.GREEN)) }
        }
    }

    // ---- Item helpers ----

    private fun returnItems(player: Player?, inventory: Inventory, slots: Set<Int>) {
        if (player == null) return
        for (slot in slots) {
            val item = inventory.getItem(slot) ?: continue
            inventory.setItem(slot, null)
            val leftover = player.inventory.addItem(item)
            for ((_, drop) in leftover) {
                player.world.dropItemNaturally(player.location, drop)
            }
        }
    }

    private fun giveItems(player: Player?, items: List<ItemStack>) {
        if (player == null) return
        for (item in items) {
            val leftover = player.inventory.addItem(item)
            for ((_, drop) in leftover) {
                player.world.dropItemNaturally(player.location, drop)
            }
        }
    }

    // ---- Query helpers ----

    fun isTrading(player: Player): Boolean = activeTrades.containsKey(player.uniqueId)

    fun getTradeForPlayer(player: Player): ActiveTrade? = activeTrades[player.uniqueId]

    private fun isTradeInventory(inventory: Inventory): Boolean {
        return activeTrades.values.any { it.inventory == inventory }
    }

    private fun getTradeByInventory(inventory: Inventory): ActiveTrade? {
        return activeTrades.values.firstOrNull { it.inventory == inventory }
    }

    // ---- Event handlers ----

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val trade = getTradeByInventory(event.inventory) ?: return

        val clickedInventory = event.clickedInventory ?: return

        // Allow clicks in player's own inventory (bottom) for shift-click handling below
        if (clickedInventory != trade.inventory) {
            // If shift-clicking from bottom inventory into trade inventory
            if (event.isShiftClick) {
                val isP1 = player.uniqueId == trade.player1
                val allowedSlots = if (isP1) P1_SLOTS else P2_SLOTS

                // Check if there's room on their side
                val hasRoom = allowedSlots.any { trade.inventory.getItem(it) == null || trade.inventory.getItem(it)?.type == Material.AIR }
                if (!hasRoom) {
                    event.isCancelled = true
                    return
                }

                // Cancel default shift-click and manually place the item
                event.isCancelled = true
                val itemToMove = event.currentItem ?: return
                for (slot in allowedSlots) {
                    val existing = trade.inventory.getItem(slot)
                    if (existing == null || existing.type == Material.AIR) {
                        trade.inventory.setItem(slot, itemToMove.clone())
                        event.currentItem = null
                        break
                    }
                }

                // If countdown was active, cancel it
                if (trade.countdownTaskId != -1) {
                    cancelCountdown(trade)
                } else if (trade.p1Confirmed || trade.p2Confirmed) {
                    // Reset confirmations if either was confirmed
                    cancelCountdown(trade)
                }
                return
            }
            return
        }

        val slot = event.rawSlot

        // Divider — always cancel
        if (slot in DIVIDER_SLOTS) {
            event.isCancelled = true
            return
        }

        val isP1 = player.uniqueId == trade.player1

        // Confirm button clicks
        if (slot == P1_CONFIRM_SLOT || slot == P2_CONFIRM_SLOT) {
            event.isCancelled = true

            // During countdown, either player can click to cancel
            if (trade.countdownTaskId != -1) {
                cancelCountdown(trade)
                return
            }

            // Only allow clicking your own confirm button
            if (isP1 && slot == P1_CONFIRM_SLOT && !trade.p1Confirmed) {
                handleConfirmClick(player, trade)
            } else if (!isP1 && slot == P2_CONFIRM_SLOT && !trade.p2Confirmed) {
                handleConfirmClick(player, trade)
            }
            return
        }

        // Prevent clicking the other player's side
        if (isP1 && slot in P2_SLOTS) {
            event.isCancelled = true
            return
        }
        if (!isP1 && slot in P1_SLOTS) {
            event.isCancelled = true
            return
        }

        // Item was changed in a trade slot — cancel countdown / reset confirmations
        if (trade.countdownTaskId != -1) {
            cancelCountdown(trade)
        } else if (trade.p1Confirmed || trade.p2Confirmed) {
            cancelCountdown(trade)
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        val trade = getTradeByInventory(event.inventory) ?: return
        val player = event.whoClicked as? Player ?: return
        val isP1 = player.uniqueId == trade.player1

        val allowedSlots = if (isP1) P1_SLOTS else P2_SLOTS

        // Cancel if any dragged slot is not in the player's allowed slots
        for (slot in event.rawSlots) {
            if (slot < 54 && slot !in allowedSlots) {
                event.isCancelled = true
                return
            }
        }

        // If drag is valid but countdown is active, cancel countdown
        if (trade.countdownTaskId != -1) {
            cancelCountdown(trade)
        } else if (trade.p1Confirmed || trade.p2Confirmed) {
            cancelCountdown(trade)
        }
    }

    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val trade = activeTrades[player.uniqueId] ?: return

        // Only cancel if the trade is still active (not already being cleaned up by executeTrade)
        if (!trade.completing) {
            cancelTrade(trade)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        val trade = activeTrades[player.uniqueId] ?: return

        // Force-cancel trade on disconnect to prevent item loss/dupe
        if (!trade.completing) {
            cancelTrade(trade)
        }
    }
}
