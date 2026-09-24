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
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Chat-input capture for the "Create Coinflip" amount prompt (issue #642).
 * Mirrors AuctionBidListener/StockTradeChatListener: LOWEST priority, cancel
 * the chat event, pull plain text, hand off to the manager on the main thread.
 */
class CoinflipChatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val expiresAt = plugin.coinflipManager.pendingCreateInputs.remove(player.uniqueId) ?: return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (raw.equals("cancel", ignoreCase = true)) {
            plugin.commsManager.send(player, Component.text("Coinflip creation cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }
        if (plugin.coinflipManager.isExpired(expiresAt)) {
            plugin.commsManager.send(player, Component.text("Your request timed out. Please try again.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val amount = plugin.economyManager.parseAmount(raw)
        if (amount == null || amount <= 0.0) {
            plugin.commsManager.send(player, Component.text("Invalid amount. Use numbers like 100000, 100k, 1m", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // createCoinflip touches the DB and economy — run on the main thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.coinflipManager.createCoinflip(player, amount)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.coinflipManager.pendingCreateInputs.remove(event.player.uniqueId)
    }
}
