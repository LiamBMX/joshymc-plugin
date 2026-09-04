package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.stock.StockCreateGui
import com.liam.joshymc.gui.stock.StockTradingGui
import com.liam.joshymc.manager.CommunicationsManager
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Chat-input capture for the /invest stock market's three pending-input flows:
 * stock creation name, buy dollar amount, sell dollar amount ("all" supported).
 * Mirrors AuctionBidListener's structure: LOWEST priority, cancel the chat event,
 * pull plain text, hand off to the manager on the main thread.
 */
class StockTradeChatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val market = plugin.stockMarketManager

        val pendingCreate = market.pendingCreateNameInputs.remove(player.uniqueId)
        if (pendingCreate != null) {
            event.isCancelled = true
            handleCreateNameInput(player, raw(event), pendingCreate.expiresAt)
            return
        }

        val pendingBuy = market.pendingBuyInputs.remove(player.uniqueId)
        if (pendingBuy != null) {
            event.isCancelled = true
            handleBuyInput(player, raw(event), pendingBuy.ticker, pendingBuy.expiresAt)
            return
        }

        val pendingSell = market.pendingSellInputs.remove(player.uniqueId)
        if (pendingSell != null) {
            event.isCancelled = true
            handleSellInput(player, raw(event), pendingSell.ticker, pendingSell.expiresAt)
            return
        }
    }

    private fun raw(event: AsyncChatEvent): String =
        PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

    private fun handleCreateNameInput(player: Player, raw: String, expiresAt: Long) {
        val market = plugin.stockMarketManager

        if (raw.equals("cancel", ignoreCase = true)) {
            send(player, "Stock creation cancelled.", NamedTextColor.GRAY)
            return
        }
        if (market.isExpired(expiresAt)) {
            send(player, "Your request timed out. Please try again.", NamedTextColor.RED)
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            val error = market.prepareStockCreation(player, raw)
            if (error != null) {
                send(player, error, NamedTextColor.RED)
                return@Runnable
            }
            plugin.guiManager.open(player, StockCreateGui.buildConfirmGui(plugin, player))
        })
    }

    private fun handleBuyInput(player: Player, raw: String, ticker: String, expiresAt: Long) {
        val market = plugin.stockMarketManager

        if (raw.equals("cancel", ignoreCase = true)) {
            send(player, "Purchase cancelled.", NamedTextColor.GRAY)
            return
        }
        if (market.isExpired(expiresAt)) {
            send(player, "Your request timed out. Please try again.", NamedTextColor.RED)
            return
        }

        val amount = plugin.economyManager.parseAmount(raw)
        if (amount == null || amount <= 0.0) {
            send(player, "Invalid amount. Use numbers like 1000, 10k, 1.5m.", NamedTextColor.RED)
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            val stock = market.getStock(ticker)
            if (stock == null) {
                send(player, "That stock no longer exists.", NamedTextColor.RED)
                return@Runnable
            }

            val error = market.checkBuyAmount(player, amount)
            if (error != null) {
                send(player, error, NamedTextColor.RED)
                return@Runnable
            }

            if (market.needsConfirmation(amount)) {
                StockTradingGui.openTradeConfirm(plugin, player, stock, amount, isBuy = true)
            } else {
                StockTradingGui.executeBuyAndNotify(plugin, player, ticker, amount)
            }
        })
    }

    private fun handleSellInput(player: Player, raw: String, ticker: String, expiresAt: Long) {
        val market = plugin.stockMarketManager

        if (raw.equals("cancel", ignoreCase = true)) {
            send(player, "Sale cancelled.", NamedTextColor.GRAY)
            return
        }
        if (market.isExpired(expiresAt)) {
            send(player, "Your request timed out. Please try again.", NamedTextColor.RED)
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            val stock = market.getStock(ticker)
            if (stock == null) {
                send(player, "That stock no longer exists.", NamedTextColor.RED)
                return@Runnable
            }
            val holding = market.getHolding(ticker, player.uniqueId)

            val amount = if (raw.equals("all", ignoreCase = true)) {
                (holding?.shares ?: 0.0) * stock.price
            } else {
                plugin.economyManager.parseAmount(raw)
            }

            if (amount == null || amount <= 0.0) {
                send(player, "Invalid amount. Use numbers like 1000, 10k, 1.5m, or 'all'.", NamedTextColor.RED)
                return@Runnable
            }

            val error = market.checkSellAmount(holding, amount)
            if (error != null) {
                send(player, error, NamedTextColor.RED)
                return@Runnable
            }

            val clamped = amount.coerceAtMost((holding?.shares ?: 0.0) * stock.price)

            if (market.needsConfirmation(clamped)) {
                StockTradingGui.openTradeConfirm(plugin, player, stock, clamped, isBuy = false)
            } else {
                StockTradingGui.executeSellAndNotify(plugin, player, ticker, clamped)
            }
        })
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.ECONOMY)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.stockMarketManager.clearAllPending(event.player.uniqueId)
    }
}
