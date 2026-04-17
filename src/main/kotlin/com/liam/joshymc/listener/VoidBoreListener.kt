package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.BlockUtil
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.scheduler.BukkitRunnable

class VoidBoreListener(private val plugin: Joshymc) : Listener {

    companion object {
        private const val DRILL_INTERVAL = 3L
    }

    private enum class BoreType(val itemId: String, val label: String) {
        SINGLE("void_bore", "1x1"),
        FIVE("void_bore_5x5", "5x5"),
        CHUNK("void_bore_chunk", "Chunk"),
    }

    @EventHandler
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand

        val boreType = BoreType.entries.firstOrNull { plugin.itemManager.isCustomItem(item, it.itemId) } ?: return

        event.isCancelled = true

        val clickedBlock = event.clickedBlock ?: return

            // Void bores only work inside your own claim
        val claim = plugin.claimManager.getClaimAt(clickedBlock.location)
        if (claim == null || !plugin.claimManager.canAccess(player, clickedBlock.location)) {
            player.sendMessage(Component.text("Void Bores can only be used inside your own claim.", NamedTextColor.RED))
            return
        }

        // For 5x5 and chunk bores: verify the ENTIRE bore area is within claims the player owns
        val boreRadius = when (boreType) {
            BoreType.SINGLE -> 0
            BoreType.FIVE -> 2
            BoreType.CHUNK -> -1 // special case
        }

        if (boreType == BoreType.CHUNK) {
            // Check all 4 corners of the chunk
            val chunk = clickedBlock.chunk
            val cx = chunk.x * 16; val cz = chunk.z * 16
            val corners = listOf(
                org.bukkit.Location(clickedBlock.world, cx.toDouble(), 0.0, cz.toDouble()),
                org.bukkit.Location(clickedBlock.world, (cx + 15).toDouble(), 0.0, cz.toDouble()),
                org.bukkit.Location(clickedBlock.world, cx.toDouble(), 0.0, (cz + 15).toDouble()),
                org.bukkit.Location(clickedBlock.world, (cx + 15).toDouble(), 0.0, (cz + 15).toDouble())
            )
            for (corner in corners) {
                val cornerClaim = plugin.claimManager.getClaimAt(corner)
                if (cornerClaim == null || !plugin.claimManager.canAccess(player, corner)) {
                    player.sendMessage(Component.text("Your claim must fully cover the bore area. The chunk extends outside your claim.", NamedTextColor.RED))
                    return
                }
            }
        } else if (boreRadius > 0) {
            // Check all 4 corners of the bore area
            val cx = clickedBlock.x; val cz = clickedBlock.z
            val corners = listOf(
                org.bukkit.Location(clickedBlock.world, (cx - boreRadius).toDouble(), 0.0, (cz - boreRadius).toDouble()),
                org.bukkit.Location(clickedBlock.world, (cx + boreRadius).toDouble(), 0.0, (cz - boreRadius).toDouble()),
                org.bukkit.Location(clickedBlock.world, (cx - boreRadius).toDouble(), 0.0, (cz + boreRadius).toDouble()),
                org.bukkit.Location(clickedBlock.world, (cx + boreRadius).toDouble(), 0.0, (cz + boreRadius).toDouble())
            )
            for (corner in corners) {
                val cornerClaim = plugin.claimManager.getClaimAt(corner)
                if (cornerClaim == null || !plugin.claimManager.canAccess(player, corner)) {
                    player.sendMessage(Component.text("Your claim must fully cover the bore area.", NamedTextColor.RED))
                    return
                }
            }
        }

        val drillStart = if (clickedBlock.type != Material.AIR && !BlockUtil.UNBREAKABLE.contains(clickedBlock.type)) {
            clickedBlock
        } else {
            clickedBlock.getRelative(BlockFace.DOWN)
        }

        // Consume one item
        if (item.amount > 1) {
            item.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }

        player.sendMessage(
            Component.text("Bore [${boreType.label}] deployed.", TextColor.color(0x55FFFF))
        )

        player.world.playSound(drillStart.location, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.0f, 0.5f)

        when (boreType) {
            BoreType.SINGLE -> startColumnBore(player.uniqueId, drillStart, 0, boreType.label)
            BoreType.FIVE -> startAreaBore(player.uniqueId, drillStart, 2, boreType.label)
            BoreType.CHUNK -> startChunkBore(player.uniqueId, drillStart, boreType.label)
        }
    }

    /**
     * Bores a single column (radius=0) or a square shaft (radius=1 for 3x3, radius=2 for 5x5).
     */
    private fun startColumnBore(playerId: java.util.UUID, startBlock: Block, radius: Int, label: String) {
        val world = startBlock.world
        val cx = startBlock.x
        val cz = startBlock.z
        var currentY = startBlock.y

        object : BukkitRunnable() {
            var blocksDestroyed = 0

            override fun run() {
                if (currentY < world.minHeight) {
                    finish(playerId, world, cx, currentY + 1, cz, blocksDestroyed, label)
                    cancel()
                    return
                }

                // Clear everything that isn't bedrock, keep going through mixed bedrock layers
                for (dx in -radius..radius) {
                    for (dz in -radius..radius) {
                        val block = world.getBlockAt(cx + dx, currentY, cz + dz)
                        if (BlockUtil.isMineable(block.type)) {
                            if (radius == 0) {
                                spawnBreakEffects(block, blocksDestroyed)
                            } else if (blocksDestroyed % 5 == 0) {
                                spawnLightEffects(block)
                            }
                            block.setType(Material.AIR, false)
                            blocksDestroyed++
                        }
                    }
                }

                currentY--
            }
        }.runTaskTimer(plugin, 5L, DRILL_INTERVAL)
    }

    private fun startAreaBore(playerId: java.util.UUID, startBlock: Block, radius: Int, label: String) {
        startColumnBore(playerId, startBlock, radius, label)
    }

    /**
     * Bores the exact chunk the block is in, from click height to bedrock.
     */
    private fun startChunkBore(playerId: java.util.UUID, startBlock: Block, label: String) {
        val world = startBlock.world
        val chunk = startBlock.chunk
        val chunkX = chunk.x * 16
        val chunkZ = chunk.z * 16
        var currentY = startBlock.y

        object : BukkitRunnable() {
            var blocksDestroyed = 0

            override fun run() {
                if (currentY < world.minHeight) {
                    finish(playerId, world, chunkX + 8, currentY + 1, chunkZ + 8, blocksDestroyed, label)
                    cancel()
                    return
                }

                // Clear everything that isn't bedrock on this layer, all the way to minHeight
                for (dx in 0..15) {
                    for (dz in 0..15) {
                        val block = world.getBlockAt(chunkX + dx, currentY, chunkZ + dz)
                        if (BlockUtil.isMineable(block.type)) {
                            block.setType(Material.AIR, false)
                            blocksDestroyed++
                        }
                    }
                }

                // Seal the edges — replace any liquid flowing in from neighboring chunks
                for (dx in 0..15) {
                    for (dz in 0..15) {
                        if (dx == 0 || dx == 15 || dz == 0 || dz == 15) {
                            val block = world.getBlockAt(chunkX + dx, currentY, chunkZ + dz)
                            if (block.type == Material.WATER || block.type == Material.LAVA) {
                                block.setType(Material.AIR, false)
                            }
                        }
                    }
                }

                // Subtle sound every 5 layers
                if ((startBlock.y - currentY) % 5 == 0) {
                    val centerLoc = org.bukkit.Location(world, chunkX + 8.0, currentY + 0.5, chunkZ + 8.0)
                    world.playSound(centerLoc, Sound.BLOCK_PISTON_EXTEND, 0.3f, 1.4f)
                }

                currentY--
            }
        }.runTaskTimer(plugin, 5L, 2L) // 2 ticks per layer instead of 1
    }

    private fun spawnLightEffects(block: Block) {
        val loc = block.location.add(0.5, 0.5, 0.5)
        block.world.spawnParticle(Particle.SMOKE, loc, 3, 0.2, 0.2, 0.2, 0.01)
        block.world.playSound(loc, Sound.BLOCK_PISTON_EXTEND, 0.3f, 1.6f)
    }

    private fun spawnBreakEffects(block: Block, count: Int) {
        val loc = block.location.add(0.5, 0.5, 0.5)
        val world = block.world

        world.spawnParticle(Particle.BLOCK, loc, 12, 0.3, 0.3, 0.3, 0.05, block.blockData)
        world.spawnParticle(Particle.FLAME, loc, 4, 0.15, 0.2, 0.15, 0.02)

        when (count % 3) {
            0 -> world.playSound(loc, Sound.BLOCK_PISTON_EXTEND, 0.5f, 1.4f)
            1 -> world.playSound(loc, Sound.ENTITY_IRON_GOLEM_DAMAGE, 0.3f, 1.8f)
            2 -> world.playSound(loc, Sound.BLOCK_GRINDSTONE_USE, 0.4f, 1.6f)
        }

        if (count > 0 && count % 10 == 0) {
            world.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.3f, 2.0f)
        }
    }

    private fun finish(
        playerId: java.util.UUID,
        world: org.bukkit.World,
        x: Int, y: Int, z: Int,
        blocksDestroyed: Int,
        label: String,
    ) {
        val impactLoc = org.bukkit.Location(world, x + 0.5, y + 1.0, z + 0.5)
        world.spawnParticle(Particle.EXPLOSION, impactLoc, 3, 0.2, 0.1, 0.2, 0.01)
        world.playSound(impactLoc, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 0.6f)
        world.playSound(impactLoc, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.5f)

        val player = plugin.server.getPlayer(playerId)
        player?.sendMessage(
            Component.text("Bore [$label] hit bedrock at Y=$y ", NamedTextColor.GRAY)
                .append(Component.text("($blocksDestroyed blocks)", TextColor.color(0x55FFFF)))
        )
    }
}
