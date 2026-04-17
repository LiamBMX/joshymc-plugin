package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.inventory.ItemStack

class AutoSmeltListener(private val plugin: Joshymc) : Listener {

    companion object {
        val SMELT_MAP = mapOf(
            Material.RAW_IRON to Material.IRON_INGOT,
            Material.RAW_GOLD to Material.GOLD_INGOT,
            Material.RAW_COPPER to Material.COPPER_INGOT,
            Material.ANCIENT_DEBRIS to Material.NETHERITE_SCRAP
        )

        /**
         * Converts a list of drops in-place, replacing raw ores with smelted results.
         */
        fun smeltDrops(drops: MutableCollection<ItemStack>) {
            val iterator = drops.iterator()
            val toAdd = mutableListOf<ItemStack>()
            val toRemove = mutableListOf<ItemStack>()
            for (drop in drops) {
                val smelted = SMELT_MAP[drop.type]
                if (smelted != null) {
                    drop.type = smelted
                }
            }
        }
    }

    private val ORE_BLOCKS = setOf(
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.ANCIENT_DEBRIS
    )

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockDrop(event: BlockDropItemEvent) {
        val player = event.player
        val blockType = event.blockState.type

        if (blockType !in ORE_BLOCKS) return
        if (!player.hasPermission("joshymc.autosmelt")) return
        if (!plugin.settingsManager.getSetting(player, "autosmelt")) return

        for (item in event.items) {
            val stack = item.itemStack
            val smelted = SMELT_MAP[stack.type] ?: continue
            item.itemStack = ItemStack(smelted, stack.amount)
        }
    }
}
