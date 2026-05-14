package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import java.util.LinkedList
import java.util.UUID

class VeinminerListener(private val plugin: Joshymc) : Listener {

    private val activeMiners = mutableSetOf<UUID>()

    private val ORE_BLOCKS = setOf(
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE,
        Material.ANCIENT_DEBRIS
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        if (!player.isSneaking) return
        if (block.type !in ORE_BLOCKS) return
        if (!player.hasPermission("joshymc.veinminer")) return
        if (!plugin.settingsManager.getSetting(player, "veinminer")) return
        if (activeMiners.contains(player.uniqueId)) return

        val tool = player.inventory.itemInMainHand
        if (tool.type.isAir) return

        val oreType = block.type
        val maxBlocks = plugin.config.getInt("mining.veinminer-max-blocks", 64)

        val vein = findVein(block, oreType, maxBlocks)
        if (vein.isEmpty()) return

        activeMiners.add(player.uniqueId)

        val shouldAutoSmelt = player.hasPermission("joshymc.autosmelt") &&
                plugin.settingsManager.getSetting(player, "autosmelt")

        try {
            for (veinBlock in vein) {
                // Tell QuestManager about the break BEFORE we change the block
                // so MINE_ORE / BREAK_BLOCK quests count every vein hit.
                plugin.questManager.recordBlockBreak(player, veinBlock)
                plugin.dailyQuestManager.recordBlockBreak(player, veinBlock)

                if (shouldAutoSmelt) {
                    // Get drops, smelt them, drop manually, then remove the block
                    val drops = veinBlock.getDrops(tool, player)
                    AutoSmeltListener.smeltDrops(drops)
                    val loc = veinBlock.location.add(0.5, 0.5, 0.5)
                    for (drop in drops) {
                        veinBlock.world.dropItemNaturally(loc, drop)
                    }
                    // Give XP that would normally drop
                    veinBlock.type = org.bukkit.Material.AIR
                } else {
                    veinBlock.breakNaturally(tool)
                }
                applyDurability(player, tool)
                if (isToolBroken(tool)) break
            }
        } finally {
            activeMiners.remove(player.uniqueId)
        }
    }

    private fun findVein(origin: Block, oreType: Material, maxBlocks: Int): List<Block> {
        val visited = mutableSetOf(origin)
        val queue = LinkedList<Block>()
        val result = mutableListOf<Block>()

        // Seed with neighbors of the origin block
        for (neighbor in getNeighbors(origin)) {
            if (neighbor.type == oreType && neighbor !in visited) {
                visited.add(neighbor)
                queue.add(neighbor)
            }
        }

        while (queue.isNotEmpty() && result.size < maxBlocks) {
            val current = queue.poll()
            result.add(current)

            for (neighbor in getNeighbors(current)) {
                if (neighbor.type == oreType && neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        return result
    }

    private fun getNeighbors(block: Block): List<Block> {
        return listOf(
            block.getRelative(1, 0, 0),
            block.getRelative(-1, 0, 0),
            block.getRelative(0, 1, 0),
            block.getRelative(0, -1, 0),
            block.getRelative(0, 0, 1),
            block.getRelative(0, 0, -1)
        )
    }

    private fun applyDurability(player: Player, tool: ItemStack) {
        val meta = tool.itemMeta ?: return
        if (meta.isUnbreakable) return

        // Don't damage custom unbreakable items
        if (plugin.itemManager.isCustomItem(tool, "void_drill") ||
            plugin.itemManager.isCustomItem(tool, "void_drill_5x5")) return

        if (meta is Damageable) {
            meta.damage = meta.damage + 1
            tool.itemMeta = meta

            // Break tool if durability exceeded
            if (meta.damage >= tool.type.maxDurability) {
                player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            }
        }
    }

    private fun isToolBroken(tool: ItemStack): Boolean {
        return tool.type.isAir
    }
}
