package com.liam.joshymc.lifesteal.teams

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Session bookkeeping for Lifesteal teams (issue #1346). ListenerManager registers this only on
 * servers that run Lifesteal. Membership itself never depends on being online: it lives in the
 * database and the cache, so reconnecting can't duplicate or drop it.
 */
class LifestealTeamListener(private val teams: LifestealTeamManager) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = teams.onJoin(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = teams.onQuit(event.player)
}
