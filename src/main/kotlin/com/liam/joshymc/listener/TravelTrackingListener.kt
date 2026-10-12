package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent

/**
 * The one place walking distance is measured for quests (issue #1126). Each
 * movement's horizontal distance is computed once here and handed to both quest
 * engines, which keep their own exempt rules, caps and accumulators: Event Quests
 * still drop deltas over 8 blocks, Daily/Weekly quests don't.
 *
 * Teleports never reach this: PlayerTeleportEvent has its own handler list.
 */
class TravelTrackingListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        // Rotation-only and purely vertical moves never count as travel.
        if (from.x == to.x && from.z == to.z) return
        if (from.world != to.world) return
        val player = event.player
        if (player.isFlying || player.isGliding || player.isInsideVehicle) return

        val dx = to.x - from.x
        val dz = to.z - from.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        if (dist < 0.01) return

        plugin.questCycleManager.recordTravel(player, dist)
        plugin.eventQuestManager.recordTravel(player, dist)
    }
}
