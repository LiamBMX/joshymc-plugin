package com.liam.joshymc.gui.stock

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.StockMarketManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.math.ceil

/**
 * "My Investments" GUI (section 17) — every stock the player currently owns, with more
 * detail than the public Trading GUI: avg. buy price, cost basis, value, P/L $ + %,
 * market cap, and 24h trend.
 */
object StockPortfolioGui {

    private const val PAGE_SIZE = 45

    fun build(plugin: Joshymc, player: Player, page: Int): CustomGui {
        val market = plugin.stockMarketManager
        val holdings = market.getHoldingsForPlayer(player.uniqueId)
        val rows = holdings.mapNotNull { h -> market.getStock(h.ticker)?.let { it to h } }
            .sortedByDescending { (stock, holding) -> holding.shares * stock.price }

        val gui = CustomGui(Component.text("My Investments", NamedTextColor.GOLD), 54)

        if (rows.isEmpty()) {
            gui.setItem(
                22,
                StockGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Investments Yet", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("Buy shares from the Trade menu to see them here.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(49, StockGuiUtil.item(Material.NETHER_STAR, Component.text("Back to Home", NamedTextColor.AQUA))) { p, _ ->
                plugin.guiManager.open(p, StockHomeGui.build(plugin, p))
            }
            return gui
        }

        val totalPages = maxOf(1, ceil(rows.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageItems = rows.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, pair) in pageItems.withIndex()) {
            val (stock, holding) = pair
            gui.setItem(index, buildHoldingIcon(plugin, stock, holding)) { p, event ->
                when {
                    event.isLeftClick -> StockTradingGui.startBuy(plugin, p, stock)
                    event.isRightClick -> StockTradingGui.startSell(plugin, p, stock)
                }
            }
        }

        for (slot in 45..53) gui.setItem(slot, StockGuiUtil.filler(Material.BLACK_STAINED_GLASS_PANE))

        if (clampedPage > 0) {
            gui.setItem(45, StockGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                plugin.guiManager.open(p, build(plugin, p, clampedPage - 1))
            }
        }

        gui.setItem(46, StockGuiUtil.item(Material.NETHER_STAR, Component.text("Back to Home", NamedTextColor.AQUA))) { p, _ ->
            plugin.guiManager.open(p, StockHomeGui.build(plugin, p))
        }

        gui.setItem(52, StockGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1} / $totalPages", NamedTextColor.WHITE)))

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, StockGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                plugin.guiManager.open(p, build(plugin, p, clampedPage + 1))
            }
        }

        return gui
    }

    private fun buildHoldingIcon(plugin: Joshymc, stock: StockMarketManager.Stock, holding: StockMarketManager.Holding): ItemStack {
        val market = plugin.stockMarketManager
        val econ = plugin.economyManager
        val stats = market.getMarketStats(stock)
        val value = holding.shares * stock.price
        val (pl, plPercent) = market.unrealizedPL(holding, stock.price)
        val avgBuy = if (holding.shares > StockMarketManager.EPSILON) holding.costBasis / holding.shares else 0.0

        val lore = mutableListOf<Component>()
        lore.add(Component.empty())
        lore.add(Component.text("Price: ", NamedTextColor.GRAY).append(Component.text(econ.formatStockPrice(stock.price), NamedTextColor.WHITE)))
        lore.add(Component.text("Shares: ", NamedTextColor.GRAY).append(Component.text(econ.formatShort(holding.shares), NamedTextColor.WHITE)))
        lore.add(Component.text("Avg. Buy: ", NamedTextColor.GRAY).append(Component.text(econ.format(avgBuy), NamedTextColor.WHITE)))
        lore.add(Component.empty())
        lore.add(Component.text("Invested: ", NamedTextColor.GRAY).append(Component.text(StockGuiUtil.money(holding.costBasis, econ::formatShort), NamedTextColor.WHITE)))
        lore.add(Component.text("Value: ", NamedTextColor.GRAY).append(Component.text(StockGuiUtil.money(value, econ::formatShort), NamedTextColor.WHITE)))
        lore.add(
            Component.text("P/L: ", NamedTextColor.GRAY)
                .append(Component.text(StockGuiUtil.moneyDelta(pl, econ::formatShort) + " (${StockGuiUtil.pct(plPercent)})", StockGuiUtil.plColor(pl)))
        )
        lore.add(Component.empty())
        lore.add(Component.text("Market Cap: ", NamedTextColor.GRAY).append(Component.text(StockGuiUtil.money(market.getMarketCap(stock), econ::formatShort), NamedTextColor.WHITE)))
        lore.add(
            Component.text("24h: ", NamedTextColor.GRAY)
                .append(Component.text("${StockGuiUtil.pct(stats.changePercent24h)} ${stats.trend.arrow}", StockGuiUtil.trendColor(stats.trend)))
        )
        lore.add(Component.empty())
        lore.add(Component.text("LEFT CLICK: ", NamedTextColor.GREEN).append(Component.text("Buy More", NamedTextColor.WHITE)))
        lore.add(Component.text("RIGHT CLICK: ", NamedTextColor.RED).append(Component.text("Sell", NamedTextColor.WHITE)))

        return StockGuiUtil.item(
            stock.icon,
            Component.text("${stock.name} ", NamedTextColor.GOLD).append(Component.text("[${stock.ticker}]", NamedTextColor.GRAY)),
            lore
        )
    }
}
