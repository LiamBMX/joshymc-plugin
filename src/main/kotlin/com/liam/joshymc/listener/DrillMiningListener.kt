package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BlockUtil
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent

class DrillMiningListener(private val plugin: Joshymc) : Listener {

    private var isProcessing = false

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val item = player.inventory.itemInMainHand

        val radius = when {
            plugin.itemManager.isCustomItem(item, "void_drill") -> 1
            plugin.itemManager.isCustomItem(item, "void_drill_5x5") -> 2
            plugin.itemManager.isCustomItem(item, "excavator") -> 1  // 3x3 shovel
            else -> return
        }

        if (isProcessing) return

        val origin = event.block
        val face = getTargetBlockFace(player)

        val blocksToBreak = getGridBlocks(origin, face, radius).filter {
            it != origin && BlockUtil.isMineable(it.type) && plugin.claimManager.canAccess(player, it.location)
        }

        if (blocksToBreak.isEmpty()) return

        isProcessing = true

        player.world.playSound(origin.location, Sound.BLOCK_PISTON_EXTEND, 0.8f, 1.6f)

        val chunks = blocksToBreak.chunked(3.coerceAtLeast(blocksToBreak.size / 3))

        chunks.forEachIndexed { index, chunk ->
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                for (block in chunk) {
                    block.world.spawnParticle(
                        Particle.BLOCK,
                        block.location.add(0.5, 0.5, 0.5),
                        12, 0.3, 0.3, 0.3, 0.05,
                        block.blockData
                    )
                    block.breakNaturally(item)
                }

                if (index == chunks.size / 2) {
                    player.world.playSound(origin.location, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.6f, 1.8f)
                }

                if (index == chunks.lastIndex) {
                    player.world.playSound(origin.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
                    isProcessing = false
                }
            }, (index * 2).toLong())
        }

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            isProcessing = false
        }, ((chunks.size * 2) + 5).toLong())
    }

    private fun getTargetBlockFace(player: org.bukkit.entity.Player): BlockFace {
        val pitch = player.location.pitch
        if (pitch < -45) return BlockFace.UP
        if (pitch > 45) return BlockFace.DOWN

        val yaw = ((player.location.yaw % 360) + 360) % 360
        return when {
            yaw < 45 || yaw >= 315 -> BlockFace.SOUTH
            yaw < 135 -> BlockFace.WEST
            yaw < 225 -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }

    private fun getGridBlocks(origin: Block, face: BlockFace, radius: Int): List<Block> {
        val blocks = mutableListOf<Block>()

        val (axis1, axis2) = when (face) {
            BlockFace.UP, BlockFace.DOWN -> Pair(BlockFace.EAST, BlockFace.SOUTH)
            BlockFace.NORTH, BlockFace.SOUTH -> Pair(BlockFace.EAST, BlockFace.UP)
            BlockFace.EAST, BlockFace.WEST -> Pair(BlockFace.SOUTH, BlockFace.UP)
            else -> Pair(BlockFace.EAST, BlockFace.UP)
        }

        for (a in -radius..radius) {
            for (b in -radius..radius) {
                val block = origin.getRelative(
                    axis1.modX * a + axis2.modX * b,
                    axis1.modY * a + axis2.modY * b,
                    axis1.modZ * a + axis2.modZ * b
                )
                blocks.add(block)
            }
        }

        return blocks
    }
}
