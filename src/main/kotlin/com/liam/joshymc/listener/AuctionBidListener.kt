package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class AuctionBidListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val listing = plugin.auctionManager.pendingBidInputs.remove(player.uniqueId) ?: return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (raw.equals("cancel", ignoreCase = true)) {
            plugin.commsManager.send(player, Component.text("Bid cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }

        val amount = plugin.economyManager.parseAmount(raw)
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid amount. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // placeBid runs on the main thread to interact with the DB and inventory safely
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.auctionManager.placeBid(player, listing.id, amount)
        })
    }
}
