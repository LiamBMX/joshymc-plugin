package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerItemHeldEvent

class WorthListener(private val plugin: Joshymc) : Listener {

    @EventHandler
    fun onItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        val item = player.inventory.getItem(event.newSlot) ?: return
        if (item.type == Material.AIR) return

        val material = item.type
        val sellPrice = plugin.marketManager.getCurrentSellPrice(material) ?: return

        val name = material.name.lowercase().replace('_', ' ')
        val formatted = plugin.economyManager.format(sellPrice)

        player.sendActionBar(
            Component.text(name, NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .append(
                    Component.text(" — Sell: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
                .append(
                    Component.text(formatted, NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false)
                )
        )
    }
}
