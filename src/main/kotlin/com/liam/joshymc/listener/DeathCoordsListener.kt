package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import kotlin.random.Random

class DeathCoordsListener(private val plugin: Joshymc) : Listener {

    companion object {
        const val SETTING_KEY = "death_coords"
        const val RANDOM_SETTING_KEY = "random_coords"
        private const val RANDOM_RANGE = 29_999_984
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val player = event.player
        if (!player.hasPermission("joshymc.deathcoords")) return
        if (!plugin.settingsManager.getSetting(player, SETTING_KEY)) return

        val loc = player.location
        val randomize = player.hasPermission("joshymc.randomcoords") &&
            plugin.settingsManager.getSetting(player, RANDOM_SETTING_KEY)
        val x = if (randomize) Random.nextInt(-RANDOM_RANGE, RANDOM_RANGE) else loc.blockX
        val z = if (randomize) Random.nextInt(-RANDOM_RANGE, RANDOM_RANGE) else loc.blockZ
        val coords = "$x ${loc.blockY} $z"
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
