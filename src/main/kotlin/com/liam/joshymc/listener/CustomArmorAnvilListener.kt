package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.PrepareAnvilEvent

class CustomArmorAnvilListener(private val plugin: Joshymc) : Listener {

    private val ARMOR_SUFFIXES = setOf("_helmet", "_chestplate", "_leggings", "_boots")

    @EventHandler
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val left = event.inventory.firstItem ?: return
        val right = event.inventory.secondItem ?: return

        val leftId = plugin.itemManager.getCustomItemId(left) ?: return
        val rightId = plugin.itemManager.getCustomItemId(right) ?: return

        if (leftId != rightId) return
        if (ARMOR_SUFFIXES.none { leftId.endsWith(it) }) return

        event.result = left.clone()
        event.inventory.repairCost = 0
    }
}
