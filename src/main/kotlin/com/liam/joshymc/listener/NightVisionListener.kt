package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.NightVisionCommand
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent

class NightVisionListener(private val plugin: Joshymc) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!player.hasPermission("joshymc.nightvision")) return

        val enabled = plugin.settingsManager.getSetting(player, NightVisionCommand.SETTING_KEY)
        if (enabled) {
            NightVisionCommand.applyNightVision(player, true)
        }
    }

    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        if (!player.hasPermission("joshymc.nightvision")) return

        val enabled = plugin.settingsManager.getSetting(player, NightVisionCommand.SETTING_KEY)
        if (enabled) {
            // Delay 1 tick to apply after respawn is fully processed
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline) NightVisionCommand.applyNightVision(player, true)
            }, 1L)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.settingsManager.evictCache(event.player.uniqueId)
    }
}
