package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** Repairs the Discord verification group on join, after a missed or failed sync (issue #1213). */
class DiscordGroupListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.linkManager.groupSync.reconcile(event.player.uniqueId)
    }
}
