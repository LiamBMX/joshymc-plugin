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

class TreeFellerListener(private val plugin: Joshymc) : Listener {

    private val activeFellers = mutableSetOf<UUID>()

    private val LOG_BLOCKS: Set<Material> = Material.entries.filter { mat ->
        val name = mat.name
        name.endsWith("_LOG") || name.endsWith("_WOOD") ||
            mat == Material.CRIMSON_STEM || mat == Material.WARPED_STEM ||
            mat == Material.STRIPPED_CRIMSON_STEM || mat == Material.STRIPPED_WARPED_STEM ||
            mat == Material.CRIMSON_HYPHAE || mat == Material.WARPED_HYPHAE ||
            mat == Material.STRIPPED_CRIMSON_HYPHAE || mat == Material.STRIPPED_WARPED_HYPHAE
    }.toSet()

    private val LEAF_BLOCKS: Set<Material> = Material.entries.filter { mat ->
        mat.name.endsWith("_LEAVES")
    }.toSet()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val block = event.block

        val isLumberjackAxe = plugin.itemManager.isCustomItem(player.inventory.itemInMainHand, "lumberjacks_axe")
        if (!player.isSneaking && !isLumberjackAxe) return
        if (block.type !in LOG_BLOCKS) return
        if (!player.hasPermission("joshymc.treefeller")) return
        if (!plugin.settingsManager.getSetting(player, "treefeller")) return
        if (activeFellers.contains(player.uniqueId)) return

        val tool = player.inventory.itemInMainHand
        if (tool.type.isAir) return

        val logType = block.type
        val maxLogs = plugin.config.getInt("mining.treefeller-max-logs", 128)

        val (logs, leaves) = findTree(block, logType, maxLogs)
        if (logs.isEmpty()) return

        activeFellers.add(player.uniqueId)

        try {
            // Break logs first (apply durability)
            for (log in logs) {
                log.breakNaturally(tool)
                applyDurability(player, tool)
                if (isToolBroken(tool)) break
            }

            // Break leaves (no durability cost)
            for (leaf in leaves) {
                if (leaf.type in LEAF_BLOCKS) {
                    leaf.breakNaturally(tool)
                }
            }
        } finally {
            activeFellers.remove(player.uniqueId)
        }
    }

    private fun findTree(origin: Block, logType: Material, maxLogs: Int): Pair<List<Block>, List<Block>> {
        val visitedLogs = mutableSetOf(origin)
        val queue = LinkedList<Block>()
        val logs = mutableListOf<Block>()

        // Seed with neighbors of the origin (26 directions for logs — includes diagonals)
        for (neighbor in getDiagonalNeighbors(origin)) {
            if (neighbor.type == logType && neighbor !in visitedLogs) {
                visitedLogs.add(neighbor)
                queue.add(neighbor)
            }
        }

        // BFS for connected logs
        while (queue.isNotEmpty() && logs.size < maxLogs) {
            val current = queue.poll()
            logs.add(current)

            for (neighbor in getDiagonalNeighbors(current)) {
                if (neighbor.type == logType && neighbor !in visitedLogs) {
                    visitedLogs.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        // Find leaves within 6 blocks of any broken log
        val allLogs = visitedLogs // includes origin + all found logs
        val leaves = findConnectedLeaves(allLogs)

        return Pair(logs, leaves)
    }

    private fun findConnectedLeaves(logPositions: Set<Block>): List<Block> {
        val visitedLeaves = mutableSetOf<Block>()
        val queue = LinkedList<Block>()
        val leaves = mutableListOf<Block>()

        // Start BFS from all log blocks, looking for adjacent leaves
        for (log in logPositions) {
            for (neighbor in getCardinalNeighbors(log)) {
                if (neighbor.type in LEAF_BLOCKS && neighbor !in visitedLeaves) {
                    visitedLeaves.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        // BFS through leaves, max 6 blocks from any log
        while (queue.isNotEmpty()) {
            val current = queue.poll()

            // Check distance to nearest log
            val nearestLogDist = logPositions.minOf { log ->
                maxOf(
                    kotlin.math.abs(current.x - log.x),
                    kotlin.math.abs(current.y - log.y),
                    kotlin.math.abs(current.z - log.z)
                )
            }
            if (nearestLogDist > 6) continue

            leaves.add(current)

            for (neighbor in getCardinalNeighbors(current)) {
                if (neighbor.type in LEAF_BLOCKS && neighbor !in visitedLeaves) {
                    visitedLeaves.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        return leaves
    }

    /**
     * 26 directions (6 cardinal + 12 edge + 8 corner) for tree logs,
     * since branches can be diagonal.
     */
    private fun getDiagonalNeighbors(block: Block): List<Block> {
        val neighbors = mutableListOf<Block>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                for (dz in -1..1) {
                    if (dx == 0 && dy == 0 && dz == 0) continue
                    neighbors.add(block.getRelative(dx, dy, dz))
                }
            }
        }
        return neighbors
    }

    /** 6 cardinal directions for leaf traversal. */
    private fun getCardinalNeighbors(block: Block): List<Block> {
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

            if (meta.damage >= tool.type.maxDurability) {
                player.inventory.setItemInMainHand(ItemStack(Material.AIR))
            }
        }
    }

    private fun isToolBroken(tool: ItemStack): Boolean {
        return tool.type.isAir
    }
}
