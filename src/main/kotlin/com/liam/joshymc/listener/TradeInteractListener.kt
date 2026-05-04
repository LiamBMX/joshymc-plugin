package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.inventory.EquipmentSlot

class TradeInteractListener(private val plugin: Joshymc) : Listener {

    @EventHandler
    fun onInteractAtEntity(event: PlayerInteractAtEntityEvent) {
        // PlayerInteractAtEntityEvent fires once per hand (main + off-hand) on
        // every shift+right-click, so without this guard sendRequest would run
        // twice and the player would get two "cannot trade in combat" messages.
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val target = event.rightClicked as? Player ?: return

        if (!player.isSneaking) return
        if (!player.hasPermission("joshymc.trade")) return

        plugin.tradeManager.sendRequest(player, target)
    }
}
