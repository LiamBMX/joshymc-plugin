package com.liam.joshymc.lifesteal.hearts

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Lifesteal heart events (issue #1341). ListenerManager registers this only on servers that run
 * Lifesteal; every handler hands off to [LifestealHeartManager], which also checks the world.
 */
class LifestealHeartListener(private val hearts: LifestealHeartManager) : Listener {

    @EventHandler(priority = EventPriority.LOW)
    fun onJoin(event: PlayerJoinEvent) = hearts.sync(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = hearts.onQuit(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) = hearts.sync(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPostRespawn(event: PlayerPostRespawnEvent) = hearts.onPostRespawn(event.player)

    /** MONITOR + ignoreCancelled: only deaths that really happen (another plugin may cancel it). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) = hearts.onDeath(event)
}
