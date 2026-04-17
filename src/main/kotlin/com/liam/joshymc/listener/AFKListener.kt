package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent

class AFKListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.afkManager.handleQuit(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        // Only trigger on actual block-level movement, not head rotation
        if (event.from.blockX == event.to.blockX &&
            event.from.blockY == event.to.blockY &&
            event.from.blockZ == event.to.blockZ
        ) return

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
