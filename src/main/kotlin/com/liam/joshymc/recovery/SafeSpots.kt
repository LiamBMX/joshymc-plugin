package com.liam.joshymc.recovery

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace

/**
 * Block-level "can a player stand here" checks for stuck-player recovery (issue #1241). Only
 * reads chunks that are already loaded, so it never loads or generates terrain itself: callers
 * load the destination with getChunkAtAsync first.
 */
object SafeSpots {

    /** Blocks that hurt, trap or move a player standing in or on them. */
    private val HAZARDS = setOf(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK,
        Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.CACTUS, Material.SWEET_BERRY_BUSH,
        Material.WITHER_ROSE, Material.POWDER_SNOW, Material.POINTED_DRIPSTONE, Material.COBWEB,
        Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY,
    )

    /** Null when a player can stand at [loc]; otherwise a short reason for the log. */
    fun problem(loc: Location): String? {
        val world = loc.world ?: return "world not loaded"
        if (!loc.x.isFinite() || !loc.y.isFinite() || !loc.z.isFinite()) return "invalid coordinates"
        if (!world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return "chunk not loaded"
        if (loc.blockY <= world.minHeight || loc.blockY >= world.maxHeight - 2) return "outside build height"
        if (!world.worldBorder.isInside(loc)) return "outside the world border"
        val feet = world.getBlockAt(loc.blockX, loc.blockY, loc.blockZ)
        if (!isClear(feet) || !isClear(feet.getRelative(BlockFace.UP))) return "would suffocate or be hurt"
        val ground = feet.getRelative(BlockFace.DOWN)
        if (ground.isLiquid || ground.type in HAZARDS) return "dangerous floor"
        if (!ground.type.isSolid) return "no floor"
        return null
    }

    /** One block above the highest block at [loc]'s column, keeping its rotation. The chunk must be loaded. */
    fun surfaceAbove(loc: Location): Location? {
        val world = loc.world ?: return null
        if (!world.isChunkLoaded(loc.blockX shr 4, loc.blockZ shr 4)) return null
        val top = world.getHighestBlockAt(loc.blockX, loc.blockZ)
        return Location(world, loc.blockX + 0.5, top.y + 1.0, loc.blockZ + 0.5, loc.yaw, loc.pitch)
    }

    /** Plain air at the player's feet and head: nothing to climb, swim in or get caught on. */
    fun isOpenAir(block: Block): Boolean = block.type.isAir

    private fun isClear(block: Block): Boolean = block.isPassable && !block.isLiquid && block.type !in HAZARDS
}
