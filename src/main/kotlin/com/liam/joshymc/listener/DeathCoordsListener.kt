package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent

class DeathCoordsListener(private val plugin: Joshymc) : Listener {

    companion object {
        const val SETTING_KEY = "death_coords"
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        if (!player.hasPermission("joshymc.deathcoords")) return
        if (!plugin.settingsManager.getSetting(player, SETTING_KEY)) return

        val loc = player.location
        val coords = "${loc.blockX} ${loc.blockY} ${loc.blockZ}"
        val world = loc.world.name

        plugin.commsManager.send(player,
            Component.text("You died at ", NamedTextColor.GRAY)
                .append(
                    Component.text(coords, NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.copyToClipboard(coords))
                )
                .append(Component.text(" in ", NamedTextColor.GRAY))
                .append(Component.text(world, NamedTextColor.AQUA))
                .append(Component.text(" (click to copy)", NamedTextColor.DARK_GRAY))
        )
    }
}
