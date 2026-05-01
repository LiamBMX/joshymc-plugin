package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

class AFKListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.afkManager.handleQuit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.afkManager.handleJoin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        // Distance-squared threshold instead of block-level check. Standing
        // on a block in the AFK void world produces tiny floating-point Y
        // drift on every physics tick, which would cross block boundaries
        // (y=64.999 ↔ 65.001) and falsely cancel AFK whenever the player
        // happened to turn their camera on the same tick.
        // 0.04 = a 0.2-block radius — well above gravity wobble, well below
        // any intentional walk/jump distance.
        val dx = event.to.x - event.from.x
        val dy = event.to.y - event.from.y
        val dz = event.to.z - event.from.z
        if (dx * dx + dy * dy + dz * dz < 0.04) return

        val player = event.player
        if (!plugin.afkManager.isAfk(player)) return

        // Ignore teleport-caused movement (AFK manager sets a flag during teleports)
        if (plugin.afkManager.isTeleporting(player)) return

        plugin.afkManager.setAfk(player, false)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (plugin.afkManager.isAfk(player)) {
            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.afkManager.setAfk(player, false)
            })
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val player = event.player
        if (event.message.lowercase().startsWith("/afk")) return
        if (plugin.afkManager.isAfk(player)) {
            plugin.afkManager.setAfk(player, false)
        }
    }
}
