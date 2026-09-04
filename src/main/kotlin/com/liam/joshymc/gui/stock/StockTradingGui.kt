package com.liam.joshymc.gui.stock

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.StockMarketManager
import com.liam.joshymc.manager.StockPricingEngine
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import kotlin.math.ceil

/**
 * Public "Trade" GUI — lists ALL public stocks (never auto-deletes inactive ones),
 * paginated, with a sort-cycle control preserved across page navigation. Also hosts
 * the large-transaction confirmation GUI (section 26) and the static buy/sell
 * execution helpers used by both GUI clicks and the chat-input listener.
 */
object StockTradingGui {

    val SORT_DEFAULT = StockMarketManager.SortMode.HIGHEST_MARKET_CAP
    private const val PAGE_SIZE = 45

    fun build(plugin: Joshymc, player: Player, sort: StockMarketManager.SortMode, page: Int): CustomGui {
        val market = plugin.stockMarketManager
        val stocks = market.getAllStocks()
        val statsByTicker = stocks.associateBy({ it.ticker }, { market.getMarketStats(it) })

        val sorted = when (sort) {
            StockMarketManager.SortMode.MOST_HOLDERS -> stocks.sortedByDescending { market.getHolderCount(it.ticker) }
            StockMarketManager.SortMode.MOST_ACTIVE -> stocks.sortedByDescending { statsByTicker.getValue(it.ticker).volume24h }
            StockMarketManager.SortMode.HIGHEST_MARKET_CAP -> stocks.sortedByDescending { market.getMarketCap(it) }
            StockMarketManager.SortMode.LEAST_ACTIVE -> stocks.sortedBy { statsByTicker.getValue(it.ticker).volume24h }
        }

        val totalPages = maxOf(1, ceil(sorted.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageItems = sorted.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        val gui = CustomGui(Component.text("Trade Stocks", NamedTextColor.GOLD), 54)

        for ((index, stock) in pageItems.withIndex()) {
            val stats = statsByTicker.getValue(stock.ticker)
            gui.setItem(index, buildStockIcon(plugin, player, stock, stats)) { p, event ->
                when {
                    event.isLeftClick -> startBuy(plugin, p, stock)
                    event.isRightClick -> startSell(plugin, p, stock)
                }
            }
        }

        // Control row
        for (slot in 45..53) gui.setItem(slot, StockGuiUtil.filler(Material.BLACK_STAINED_GLASS_PANE))

        if (clampedPage > 0) {
            gui.setItem(45, StockGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                plugin.guiManager.open(p, build(plugin, p, sort, clampedPage - 1))
            }
        }

        gui.setItem(46, StockGuiUtil.item(Material.NETHER_STAR, Component.text("Back to Home", NamedTextColor.AQUA))) { p, _ ->
            plugin.guiManager.open(p, StockHomeGui.build(plugin, p))
        }

        gui.setItem(
            49,
            StockGuiUtil.item(
                Material.HOPPER,
                Component.text("Sort: ${sort.displayName}", NamedTextColor.LIGHT_PURPLE),
                listOf(Component.empty(), Component.text("Click to change sorting", NamedTextColor.GRAY))
            )
        ) { p, _ -> plugin.guiManager.open(p, build(plugin, p, sort.next(), 0)) }

        gui.setItem(
            52,
            StockGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1} / $totalPages", NamedTextColor.WHITE))
        )

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, StockGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                plugin.guiManager.open(p, build(plugin, p, sort, clampedPage + 1))
            }
        }

        return gui
    }

    private fun buildStockIcon(plugin: Joshymc, player: Player, stock: StockMarketManager.Stock, stats: StockMarketManager.MarketStats): org.bukkit.inventory.ItemStack {
        val market = plugin.stockMarketManager
        val econ = plugin.economyManager
        val marketCap = market.getMarketCap(stock)
        val holders = market.getHolderCount(stock.ticker)

        val lore = mutableListOf<Component>()
        lore.add(Component.empty())
        lore.add(Component.text("Price: ", NamedTextColor.GRAY).append(Component.text(econ.format(stock.price), NamedTextColor.WHITE)))
        lore.add(Component.text("Market Cap: ", NamedTextColor.GRAY).append(Component.text(StockGuiUtil.money(marketCap, econ::formatShort), NamedTextColor.WHITE)))
        lore.add(Component.text("Holders: ", NamedTextColor.GRAY).append(Component.text(holders.toString(), NamedTextColor.WHITE)))
        lore.add(
            Component.text("24h: ", NamedTextColor.GRAY)
                .append(Component.text("${StockGuiUtil.pct(stats.changePercent24h)} ${stats.trend.arrow}", StockGuiUtil.trendColor(stats.trend)))
        )

        val holding = market.getHolding(stock.ticker, player.uniqueId)
        if (holding != null && holding.shares > StockMarketManager.EPSILON) {
            val value = holding.shares * stock.price
            val (pl, plPercent) = market.unrealizedPL(holding, stock.price)
            lore.add(Component.empty())
            lore.add(Component.text("Your Value: ", NamedTextColor.GRAY).append(Component.text(StockGuiUtil.money(value, econ::formatShort), NamedTextColor.WHITE)))
            lore.add(
                Component.text("P/L: ", NamedTextColor.GRAY)
                    .append(Component.text(StockGuiUtil.moneyDelta(pl, econ::formatShort) + " (${StockGuiUtil.pct(plPercent)})", StockGuiUtil.plColor(pl)))
            )
        }

        lore.add(Component.empty())
        lore.add(Component.text("LEFT CLICK: ", NamedTextColor.GREEN).append(Component.text("Buy", NamedTextColor.WHITE)))
        lore.add(Component.text("RIGHT CLICK: ", NamedTextColor.RED).append(Component.text("Sell", NamedTextColor.WHITE)))

        return StockGuiUtil.item(
            stock.icon,
            Component.text("${stock.name} ", NamedTextColor.GOLD).append(Component.text("[${stock.ticker}]", NamedTextColor.GRAY)),
            lore
        )
    }

    // ── Buy/Sell chat-flow triggers ─────────────────────────────────

    fun startBuy(plugin: Joshymc, player: Player, stock: StockMarketManager.Stock) {
        plugin.stockMarketManager.setPendingBuy(player.uniqueId, stock.ticker)
        player.closeInventory()
        send(plugin, player, "How much would you like to invest in ${stock.name}?", NamedTextColor.YELLOW)
        send(plugin, player, "Type an amount or 'cancel'.", NamedTextColor.GRAY)
    }

    fun startSell(plugin: Joshymc, player: Player, stock: StockMarketManager.Stock) {
        val holding = plugin.stockMarketManager.getHolding(stock.ticker, player.uniqueId)
        if (holding == null || holding.shares <= StockMarketManager.EPSILON) {
            send(plugin, player, "You don't own shares in this stock.", NamedTextColor.RED)
            return
        }
        plugin.stockMarketManager.setPendingSell(player.uniqueId, stock.ticker)
        player.closeInventory()
        send(plugin, player, "How much of your ${stock.name} investment would you like to sell?", NamedTextColor.YELLOW)
        send(plugin, player, "Type a dollar amount, 'all', or 'cancel'.", NamedTextColor.GRAY)
    }

    // ── Execution + result messages (shared by GUI confirm + chat listener) ──

    fun executeBuyAndNotify(plugin: Joshymc, player: Player, ticker: String, amount: Double) {
        val outcome = plugin.stockMarketManager.buyStock(player, ticker, amount)
        notifyOutcome(plugin, player, outcome)
    }

    fun executeSellAndNotify(plugin: Joshymc, player: Player, ticker: String, amount: Double) {
        val outcome = plugin.stockMarketManager.sellStock(player, ticker, amount)
        notifyOutcome(plugin, player, outcome)
    }

    private fun notifyOutcome(plugin: Joshymc, player: Player, outcome: StockMarketManager.TradeOutcome) {
        val econ = plugin.economyManager
        when (outcome) {
            is StockMarketManager.TradeOutcome.BuySuccess -> {
                send(plugin, player, "Investment Successful", NamedTextColor.GOLD)
                send(plugin, player, "Invested: ${StockGuiUtil.money(outcome.dollarAmount, econ::formatShort)}", NamedTextColor.WHITE)
                send(plugin, player, "Shares: ${econ.formatShort(outcome.sharesMinted)}", NamedTextColor.WHITE)
                send(plugin, player, "Avg. Price: ${econ.format(outcome.avgExecutionPrice)}", NamedTextColor.WHITE)
                send(plugin, player, "New Price: ${econ.format(outcome.newPrice)}", NamedTextColor.WHITE)
            }
            is StockMarketManager.TradeOutcome.SellSuccess -> {
                send(plugin, player, "Sale Successful", NamedTextColor.GOLD)
                send(plugin, player, "Sold: ${StockGuiUtil.money(outcome.dollarAmount, econ::formatShort)}", NamedTextColor.WHITE)
                send(plugin, player, "Shares Sold: ${econ.formatShort(outcome.sharesSold)}", NamedTextColor.WHITE)
                send(plugin, player, "Realized P/L: ${StockGuiUtil.moneyDelta(outcome.realizedPL, econ::formatShort)}", StockGuiUtil.plColor(outcome.realizedPL))
                send(plugin, player, "Remaining: ${StockGuiUtil.money(outcome.remainingValue, econ::formatShort)}", NamedTextColor.WHITE)
                send(plugin, player, "New Price: ${econ.format(outcome.newPrice)}", NamedTextColor.WHITE)
            }
            is StockMarketManager.TradeOutcome.Failure -> {
                send(plugin, player, outcome.message, NamedTextColor.RED)
            }
        }
    }

    private fun send(plugin: Joshymc, player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.ECONOMY)
    }

    // ── Large-transaction confirmation GUI (section 26) ─────────────

    fun openTradeConfirm(plugin: Joshymc, player: Player, stock: StockMarketManager.Stock, dollarAmount: Double, isBuy: Boolean) {
        val market = plugin.stockMarketManager
        val econ = plugin.economyManager

        val preview: Pair<Double, Double> = if (isBuy) {
            val r = StockPricingEngine.computeBuy(stock.price, stock.sharesOutstanding, dollarAmount, market.maxSingleTradeImpact, market.minLiquidityFloor)
            r.sharesMinted to r.impactFraction
        } else {
            val holding = market.getHolding(stock.ticker, player.uniqueId)
            val r = StockPricingEngine.computeSell(
                stock.price, stock.sharesOutstanding, dollarAmount,
                holding?.shares ?: 0.0, holding?.costBasis ?: 0.0,
                market.maxSingleTradeImpact, market.minLiquidityFloor
            )
            r.sharesRemoved to r.impactFraction
        }
        val (estShares, impact) = preview
        val impactPercent = impact * 100.0 * (if (isBuy) 1.0 else -1.0)

        market.pendingTradeConfirmations[player.uniqueId] = StockMarketManager.PendingTradeConfirmation(
            stock.ticker, dollarAmount, isBuy, System.currentTimeMillis() + 60_000L
        )

        val gui = CustomGui(Component.text(if (isBuy) "Confirm Purchase" else "Confirm Sale", NamedTextColor.GOLD), 27)
        gui.border(StockGuiUtil.filler(Material.BLACK_STAINED_GLASS_PANE))

        gui.setItem(
            13,
            StockGuiUtil.item(
                stock.icon,
                Component.text("${stock.name} [${stock.ticker}]", NamedTextColor.GOLD),
                listOf(
                    Component.empty(),
                    Component.text(if (isBuy) "Investment: " else "Sale: ", NamedTextColor.GRAY)
                        .append(Component.text(StockGuiUtil.money(dollarAmount, econ::formatShort), NamedTextColor.WHITE)),
                    Component.text("Estimated Shares: ", NamedTextColor.GRAY)
                        .append(Component.text(econ.formatShort(estShares), NamedTextColor.WHITE)),
                    Component.text("Estimated Price Impact: ", NamedTextColor.GRAY)
                        .append(Component.text(StockGuiUtil.pct(impactPercent), StockGuiUtil.plColor(if (isBuy) 1.0 else -1.0))),
                )
            )
        )

        gui.setItem(
            11,
            StockGuiUtil.item(Material.LIME_CONCRETE, Component.text("CONFIRM", NamedTextColor.GREEN))
        ) { p, _ ->
            val pending = market.pendingTradeConfirmations.remove(p.uniqueId)
            p.closeInventory()
            if (pending == null || market.isExpired(pending.expiresAt)) {
                send(plugin, p, "That confirmation expired. Please try again.", NamedTextColor.RED)
                return@setItem
            }
            if (pending.isBuy) {
                executeBuyAndNotify(plugin, p, pending.ticker, pending.dollarAmount)
            } else {
                executeSellAndNotify(plugin, p, pending.ticker, pending.dollarAmount)
            }
        }

        gui.setItem(
            15,
            StockGuiUtil.item(Material.RED_CONCRETE, Component.text("CANCEL", NamedTextColor.RED))
        ) { p, _ ->
            market.pendingTradeConfirmations.remove(p.uniqueId)
            p.closeInventory()
            send(plugin, p, if (isBuy) "Purchase cancelled." else "Sale cancelled.", NamedTextColor.GRAY)
        }

        plugin.guiManager.open(player, gui)
    }
}
