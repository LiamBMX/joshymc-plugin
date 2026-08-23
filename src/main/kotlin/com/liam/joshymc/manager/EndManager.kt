package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerTeleportEvent

class EndManager(private val plugin: Joshymc) : Listener {

    var isOpen: Boolean = true
        private set

    fun start() {
        isOpen = plugin.config.getBoolean("end.open", true)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun setOpen(open: Boolean) {
        isOpen = open
        plugin.config.set("end.open", open)
        plugin.saveConfig()
    }

    // Stronghold end portals stay non-solid whether or not we cancel the
    // teleport, so cancelling PlayerPortalEvent is all "phasing through"
    // requires — the player just keeps walking through the frame instead of
    // being pulled into the End.
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPortal(event: PlayerPortalEvent) {
        if (isOpen) return
        if (event.cause != PlayerTeleportEvent.TeleportCause.END_PORTAL) return
        if (event.player.hasPermission("joshymc.end.bypass")) return

        // Only block entering the End — players already inside must always
        // be able to leave via the exit portal.
        if (event.from.world?.environment == World.Environment.THE_END) return

        event.isCancelled = true
        plugin.commsManager.send(
            event.player,
            Component.text("The End is currently closed.", NamedTextColor.RED),
            CommunicationsManager.Category.TELEPORT
        )
    }
}
