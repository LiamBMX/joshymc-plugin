package com.liam.joshymc.lifesteal.teams.progression

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.world.ChunkUnloadEvent

/**
 * Team XP mining (issue #1426). Read-only at MONITOR with ignoreCancelled, so a break or place that
 * a claim, WorldGuard or anything else refused never counts. Registered only where Lifesteal is
 * hosted; the manager also checks the player's world is a Lifesteal one.
 */
class TeamProgressionListener(private val manager: LifestealTeamProgressionManager) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (!manager.active) return
        // Player-placed ores never count as mined (silk-touch and replace loops).
        manager.placed.mark(event.blockPlaced)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        manager.onBlockBroken(event.player, event.block)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        manager.placed.forget(event.chunk)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        manager.onQuit(event.player)
    }
}
