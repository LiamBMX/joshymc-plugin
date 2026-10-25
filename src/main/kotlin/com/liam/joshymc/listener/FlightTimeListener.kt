package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Keeps [com.liam.joshymc.flight.FlightTimeManager] in step with players (issue #1304):
 * loads their Survival flight time on join (settling a grace that ran out while they were
 * away), forgets it on quit, and takes Survival flight away when they enter a non-Survival
 * world.
 */
class FlightTimeListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.flightTimeManager.onJoin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.flightTimeManager.unload(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        plugin.flightTimeManager.stripOutsideSurvival(event.player)
    }
}
