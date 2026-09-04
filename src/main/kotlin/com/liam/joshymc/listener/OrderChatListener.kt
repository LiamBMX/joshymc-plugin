package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/** Captures chat input for the multi-step /orders "Create Order" flow and custom sell amounts. */
class OrderChatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val uuid = player.uniqueId

        val awaitingCreation = plugin.orderManager.pendingCreations.containsKey(uuid)
        val awaitingCustomSell = plugin.orderManager.pendingCustomSell.containsKey(uuid)
        if (!awaitingCreation && !awaitingCustomSell) return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        // Order creation/fulfillment touch the DB and inventory, so run on the main thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            if (awaitingCreation) {
                plugin.orderManager.handleCreateChatInput(player, raw)
            } else {
                plugin.orderManager.handleCustomSellInput(player, raw)
            }
        })
    }
}
