package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent

/**
 * Flight in the "pvp" world is gated purely on `joshymc.fly.pvp` — no rank or
 * staff-mode exemptions. FlyCommand handles the `/fly` command itself; this
 * listener catches every other way a player could end up flying there
 * (world change, teleport/warp/portal/spawn, reconnect, respawn).
 */
class PvpWorldFlightListener(private val plugin: Joshymc) : Listener {

    companion object {
        const val WORLD_NAME = "pvp"
        const val PERMISSION = "joshymc.fly.pvp"
    }

    private fun stripFlightIfUnauthorized(player: Player) {
        if (player.world.name != WORLD_NAME) return
        if (player.gameMode == GameMode.SPECTATOR) return
        if (player.hasPermission(PERMISSION)) return
        if (!player.allowFlight && !player.isFlying) return

        player.allowFlight = false
        player.isFlying = false
        plugin.commsManager.send(player, Component.text("Flight disabled — you don't have permission to fly in the PvP world.", NamedTextColor.RED))
    }

    // Covers normal world change, teleport, warp, /spawn, and portals — all of
    // these fire this event when the destination world differs from the source.
    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        stripFlightIfUnauthorized(event.player)
    }

    // Reconnecting already inside the pvp world.
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (player.world.name != WORLD_NAME) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) stripFlightIfUnauthorized(player)
        })
    }

    // Respawning into the pvp world (e.g. a bed/anchor spawn point set there).
    @EventHandler
    fun onRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        if (event.respawnLocation.world?.name != WORLD_NAME) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) stripFlightIfUnauthorized(player)
        })
    }
}
