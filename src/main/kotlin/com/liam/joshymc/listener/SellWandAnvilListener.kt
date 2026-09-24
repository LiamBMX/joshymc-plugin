package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.SellWandCommand
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent

class SellWandAnvilListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val left = event.inventory.firstItem ?: return
        val right = event.inventory.secondItem ?: return

        if (!plugin.itemManager.isCustomItem(left, "sell_wand")) return
        if (!plugin.itemManager.isCustomItem(right, "sell_wand")) return

        val leftMultiplier = SellWandCommand.getMultiplier(plugin, left)
        val rightMultiplier = SellWandCommand.getMultiplier(plugin, right)
        if (leftMultiplier != rightMultiplier) return

        val combinedUses = SellWandCommand.getUses(plugin, left) + SellWandCommand.getUses(plugin, right)

        event.result = SellWandCommand.createSellWand(plugin, leftMultiplier, combinedUses)
        event.inventory.repairCost = 0
    }
}
