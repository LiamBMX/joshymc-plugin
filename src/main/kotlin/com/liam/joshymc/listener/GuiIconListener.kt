package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.GuiIcon
import org.bukkit.block.Crafter
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.CrafterCraftEvent
import org.bukkit.event.inventory.FurnaceBurnEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.inventory.ItemStack

/**
 * The shared GUI icons (issue #1355) are Sticks underneath. Inside menus they are never
 * takeable (GuiManager cancels clicks on the GUI), but a copy handed out with `/joshymc give`
 * must not turn into torches or furnace fuel. Ordinary sticks are untouched.
 */
class GuiIconListener(private val plugin: Joshymc) : Listener {

    private fun isIcon(stack: ItemStack?): Boolean {
        val id = plugin.itemManager.getCustomItemId(stack) ?: return false
        return GuiIcon.fromItemId(id) != null
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        if (event.inventory.matrix.any { isIcon(it) }) event.inventory.result = null
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCrafter(event: CrafterCraftEvent) {
        val crafter = event.block.state as? Crafter ?: return
        if (crafter.inventory.contents.any { isIcon(it) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFuel(event: FurnaceBurnEvent) {
        if (isIcon(event.fuel)) event.isCancelled = true
    }
}
