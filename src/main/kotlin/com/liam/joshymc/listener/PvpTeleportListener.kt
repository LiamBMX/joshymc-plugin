package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.PvpTeleportGuard
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause
import org.bukkit.event.server.ServerCommandEvent

/**
 * Event-level half of the PvP world teleport lock (issue #1075) — see
 * [PvpTeleportGuard]. Cancels player-controlled teleports that cross the PvP
 * world boundary no matter which command, alias, plugin or delayed task
 * issued them. Portals, respawns, dismounts and other non-player causes are
 * left alone, as is all ordinary movement.
 */
class PvpTeleportListener(private val plugin: Joshymc) : Listener {

    companion object {
        // SPECTATE is left out: only staff can be in spectator mode.
        private val PLAYER_CAUSES = setOf(
            TeleportCause.COMMAND,
            TeleportCause.PLUGIN,
            TeleportCause.ENDER_PEARL,
        )
    }

    // LOWEST so the issuer is recorded before any command executor runs.
    @EventHandler(priority = EventPriority.LOWEST)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        PvpTeleportGuard.onCommandIssued(event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onServerCommand(event: ServerCommandEvent) {
        PvpTeleportGuard.onCommandIssued(event.sender)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (event.cause !in PLAYER_CAUSES) return
        if (!PvpTeleportGuard.isEnabled(plugin)) return
        val fromPvp = PvpTeleportGuard.isPvpWorld(event.from.world)
        val toPvp = PvpTeleportGuard.isPvpWorld(event.to.world)
        if (fromPvp == toPvp) return

        val player = event.player
        if (PvpTeleportGuard.isForced() || PvpTeleportGuard.canBypass(player)) return
        if (plugin.modModeManager.isModMode(player)) return

        event.isCancelled = true
        PvpTeleportGuard.notify(plugin, player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        PvpTeleportGuard.clear(event.player)
    }
}
