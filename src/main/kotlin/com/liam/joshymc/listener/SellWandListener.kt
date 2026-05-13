package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.SellWandCommand
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Chest
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

class SellWandListener(private val plugin: Joshymc) : Listener {

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val item = player.inventory.itemInMainHand

        if (!plugin.itemManager.isCustomItem(item, "sell_wand")) return

        // Cancel immediately to prevent chest from opening
        event.isCancelled = true

        val block = event.clickedBlock ?: return
        if (block.type != Material.CHEST && block.type != Material.TRAPPED_CHEST) {
            plugin.commsManager.send(
                player,
                Component.text("Right-click a chest to use the Sell Wand.", NamedTextColor.GRAY),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        if (!player.hasPermission("joshymc.sellwand.use")) {
            plugin.commsManager.send(
                player,
                Component.text("You don't have permission to use this.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        // Read multiplier and uses from PDC
        val multiplier = SellWandCommand.getMultiplier(plugin, item)
        val usesLeft = SellWandCommand.getUses(plugin, item)

        if (usesLeft <= 0) {
            plugin.commsManager.send(
                player,
                Component.text("This Sell Wand has no uses remaining.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val chest = block.state as? Chest ?: return
        val inventory = chest.inventory

        var totalEarned = 0.0
        val breakdown = mutableMapOf<Material, Int>()

        for (i in 0 until inventory.size) {
            val slot = inventory.getItem(i) ?: continue
            val price = plugin.serverShopManager.getSellPrice(slot.type) ?: 0.0
            if (price <= 0) continue

            totalEarned += price * multiplier * slot.amount
            breakdown[slot.type] = (breakdown[slot.type] ?: 0) + slot.amount
            inventory.setItem(i, null)
        }

        if (totalEarned <= 0) {
            plugin.commsManager.send(
                player,
                Component.text("This chest has nothing to sell.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        plugin.economyManager.deposit(player.uniqueId, totalEarned)

        for ((material, amount) in breakdown) {
            plugin.marketManager.recordTransaction(material, "SELL", amount)
        }

        val formatted = plugin.economyManager.format(totalEarned)
        val multiplierText = if (multiplier != 1.0) " (${multiplier}x)" else ""
        plugin.commsManager.send(
            player,
            Component.text("Sold chest contents for ", NamedTextColor.GREEN)
                .append(Component.text(formatted, NamedTextColor.GOLD))
                .append(Component.text(multiplierText, NamedTextColor.YELLOW)),
            CommunicationsManager.Category.ECONOMY
        )

        for ((material, amount) in breakdown) {
            plugin.commsManager.send(
                player,
                Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("${amount}x ", NamedTextColor.WHITE))
                    .append(Component.text(material.name.lowercase().replace('_', ' '), NamedTextColor.GRAY)),
                CommunicationsManager.Category.ECONOMY
            )
        }

        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)

        // Decrement uses — destroy wand if none left
        val remaining = SellWandCommand.decrementUses(plugin, item)
        if (remaining <= 0) {
            player.inventory.setItemInMainHand(null)
            plugin.commsManager.send(
                player,
                Component.text("Your Sell Wand has been consumed.", NamedTextColor.GRAY),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f)
        }
    }
}
