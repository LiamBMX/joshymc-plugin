package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.Location
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent

class GSitListener(private val plugin: Joshymc) : Listener {

    companion object {
        const val SETTING_KEY = "gsit"
        const val SIT_TAG = "joshymc_sit"

        /**
         * Spawns a sit armor stand and mounts the player on it.
         * seatY should be the Y coordinate where the player's butt goes.
         */
        fun sit(player: Player, location: Location) {
            if (player.vehicle != null) return

            val seat = location.world.spawn(location, ArmorStand::class.java) { stand ->
                stand.isInvisible = true
                stand.isMarker = true
                stand.isSmall = true
                stand.setGravity(false)
                stand.isInvulnerable = true
                stand.addScoreboardTag(SIT_TAG)
            }

            seat.addPassenger(player)
        }
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        if (!player.hasPermission("joshymc.gsit")) return
        if (!plugin.settingsManager.getSetting(player, SETTING_KEY)) return
        if (player.isSneaking) return
        if (event.item != null) return
        if (player.vehicle != null) return

        val block = event.clickedBlock ?: return
        val data = block.blockData

        // Determine seat surface height relative to block Y
        val surfaceOffset = when {
            data is Stairs && data.half == Bisected.Half.BOTTOM -> 0.5
            data is Slab && data.type == Slab.Type.BOTTOM -> 0.5
            else -> return
        }

        // Check if someone is already sitting here
        val blockCenter = block.location.add(0.5, surfaceOffset, 0.5)
        val alreadyOccupied = block.world.getNearbyEntities(blockCenter, 0.5, 1.0, 0.5).any {
            it is ArmorStand && it.scoreboardTags.contains(SIT_TAG)
        }
        if (alreadyOccupied) return

        event.isCancelled = true

        // Marker armor stand: player rides at the armor stand's Y position.
        // We want the player to appear sitting on the surface.
        val seatLoc = block.location.add(0.5, surfaceOffset + 0.1, 0.5)
        sit(player, seatLoc)
    }

    @EventHandler
    fun onDismount(event: EntityDismountEvent) {
        val stand = event.dismounted
        if (stand is ArmorStand && stand.scoreboardTags.contains(SIT_TAG)) {
            stand.remove()

            // Teleport player up 1 block so they don't phase into the block
            val player = event.entity
            if (player is Player) {
                val loc = player.location.clone().add(0.0, 1.0, 0.0)
                plugin.server.scheduler.runTask(plugin, Runnable {
                    player.teleport(loc)
                })
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        removeSeat(event.player)
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        removeSeat(event.player)
    }

    @EventHandler
    fun onTeleport(event: PlayerTeleportEvent) {
        removeSeat(event.player)
    }

    private fun removeSeat(player: Player) {
        val vehicle = player.vehicle
        if (vehicle is ArmorStand && vehicle.scoreboardTags.contains(SIT_TAG)) {
            vehicle.remove()
        }
    }
}
