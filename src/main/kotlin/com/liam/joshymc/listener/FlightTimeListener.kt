package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Keeps [com.liam.joshymc.flight.FlightTimeManager] in step with players (issue #1304):
 * loads their Survival flight time on join (settling a grace that ran out while they were
 * away), forgets it on quit, and takes Survival flight away when they enter a non-Survival
 * world.
 *
 * Issue #1470: also puts a player's own `/fly` back after a reconnect/restart, a switch back to
 * Survival or a world change, when they still may fly there (see
 * [com.liam.joshymc.flight.FlightRestore]), and cancels the one reconnect fall it couldn't
 * prevent.
 */
class FlightTimeListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // Issue #1408: settle Fly Voucher redemptions a crash left PENDING first, from what
        // LuckPerms holds, then mirror LuckPerms' flight expiry.
        plugin.flyVoucherManager.recoverPending(event.player)
        plugin.flightTimeManager.onJoin(event.player)
        // Issue #1470: after the flight time is settled, so expired time isn't restored.
        plugin.flightTimeManager.loadPreference(event.player.uniqueId)
        plugin.flightTimeManager.restoreFlightOnJoin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.flightTimeManager.unload(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        plugin.flightTimeManager.stripOutsideSurvival(event.player)
        plugin.flightTimeManager.restoreFlightLater(event.player)
    }

    /** Vanilla turns flight off when a player is switched to Survival/Adventure (issue #1470). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        if (event.newGameMode != GameMode.SURVIVAL && event.newGameMode != GameMode.ADVENTURE) return
        plugin.flightTimeManager.restoreFlightLater(event.player)
    }

    /** The reconnect fall protection: only FALL damage, once, briefly, never while combat-tagged. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFall(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        if (plugin.flightTimeManager.consumeFallProtection(player)) event.isCancelled = true
    }
}
