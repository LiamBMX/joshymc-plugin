package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent

class TradeInteractListener(private val plugin: Joshymc) : Listener {

    @EventHandler
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        val player = event.player
        val target = event.rightClicked as? Player ?: return

        if (!player.isSneaking) return
        if (!player.hasPermission("joshymc.trade")) return

        plugin.tradeManager.sendRequest(player, target)
    }
}
