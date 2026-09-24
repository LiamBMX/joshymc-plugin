package com.liam.joshymc.gui.stock

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.StockMarketManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * "Create Your Own" GUI (section 9-12). Two stages: the info/start screen here, and a
 * confirmation screen (built after a valid name is captured via chat) showing the
 * generated ticker before the $1M charge is applied.
 */
object StockCreateGui {

    fun build(plugin: Joshymc, player: Player): CustomGui {
        val market = plugin.stockMarketManager
        val gui = CustomGui(Component.text("Create a Stock", NamedTextColor.GOLD), 27)
        gui.border(StockGuiUtil.filler(Material.BLACK_STAINED_GLASS_PANE))

        gui.setItem(
            13,
            StockGuiUtil.item(
                Material.PAPER,
                Component.text("Create a New Stock", NamedTextColor.AQUA),
                listOf(
                    Component.empty(),
                    Component.text("Cost: ", NamedTextColor.GRAY)
                        .append(Component.text(StockGuiUtil.money(market.stockCreationCost, plugin.economyManager::formatShort), NamedTextColor.GOLD)),
                    Component.text("Starting Price: ", NamedTextColor.GRAY)
                        .append(Component.text(plugin.economyManager.format(market.defaultStockPrice), NamedTextColor.WHITE)),
                    Component.text("Initial Shares: ", NamedTextColor.GRAY)
                        .append(Component.text(plugin.economyManager.formatShort(market.initialShares), NamedTextColor.WHITE)),
                    Component.empty(),
                    Component.text("Your ticker is generated automatically", NamedTextColor.DARK_GRAY),
                    Component.text("from your stock's name.", NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    Component.text("Click to enter a name", NamedTextColor.GREEN),
                )
            )
        ) { p, _ ->
            if (!plugin.economyManager.has(p.uniqueId, market.stockCreationCost)) {
                plugin.commsManager.send(
                    p,
                    Component.text("You need ${plugin.economyManager.format(market.stockCreationCost)} to create a stock.", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
                return@setItem
            }
            market.setPendingCreateName(p.uniqueId)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("What would you like to name your stock?", NamedTextColor.YELLOW), CommunicationsManager.Category.ECONOMY)
            plugin.commsManager.send(p, Component.text("Type a name or 'cancel'.", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
        }

        gui.setItem(22, StockGuiUtil.item(Material.NETHER_STAR, Component.text("Back to Home", NamedTextColor.AQUA))) { p, _ ->
            plugin.guiManager.open(p, StockHomeGui.build(plugin, p))
        }

        return gui
    }

    /** Built after a valid name has been captured via chat (see StockTradeChatListener). */
    fun buildConfirmGui(plugin: Joshymc, player: Player): CustomGui {
        val market = plugin.stockMarketManager
        val pending = market.getPendingCreation(player.uniqueId)

        val gui = CustomGui(Component.text("Confirm Stock Creation", NamedTextColor.GOLD), 27)
        gui.border(StockGuiUtil.filler(Material.BLACK_STAINED_GLASS_PANE))

        if (pending == null) {
            gui.setItem(
                13,
                StockGuiUtil.item(
                    Material.BARRIER,
                    Component.text("Request Expired", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("Please start again.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(22, StockGuiUtil.item(Material.NETHER_STAR, Component.text("Back to Home", NamedTextColor.AQUA))) { p, _ ->
                plugin.guiManager.open(p, StockHomeGui.build(plugin, p))
            }
            return gui
        }

        gui.setItem(
            13,
            StockGuiUtil.item(
                Material.PAPER,
                Component.text(pending.name, NamedTextColor.GOLD).append(Component.text(" [${pending.ticker}]", NamedTextColor.GRAY)),
                listOf(
                    Component.empty(),
                    Component.text("Cost: ", NamedTextColor.GRAY)
                        .append(Component.text(StockGuiUtil.money(market.stockCreationCost, plugin.economyManager::formatShort), NamedTextColor.GOLD)),
                    Component.text("Starting Price: ", NamedTextColor.GRAY)
                        .append(Component.text(plugin.economyManager.format(market.defaultStockPrice), NamedTextColor.WHITE)),
                    Component.text("Initial Shares: ", NamedTextColor.GRAY)
                        .append(Component.text(plugin.economyManager.formatShort(market.initialShares), NamedTextColor.WHITE)),
                )
            )
        )

        gui.setItem(11, StockGuiUtil.item(Material.LIME_CONCRETE, Component.text("CONFIRM", NamedTextColor.GREEN))) { p, _ ->
            p.closeInventory()
            when (val outcome = market.finalizeStockCreation(p)) {
                is StockMarketManager.CreateOutcome.Success -> {
                    plugin.commsManager.send(
                        p,
                        Component.text("Stock created! ", NamedTextColor.GREEN)
                            .append(Component.text("${outcome.stock.name} [${outcome.stock.ticker}]", NamedTextColor.GOLD)),
                        CommunicationsManager.Category.ECONOMY
                    )
                }
                is StockMarketManager.CreateOutcome.Failure -> {
                    plugin.commsManager.send(p, Component.text(outcome.message, NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                }
            }
        }

        gui.setItem(15, StockGuiUtil.item(Material.RED_CONCRETE, Component.text("CANCEL", NamedTextColor.RED))) { p, _ ->
            market.pendingStockConfirmations.remove(p.uniqueId)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Stock creation cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
        }

        return gui
    }
}
