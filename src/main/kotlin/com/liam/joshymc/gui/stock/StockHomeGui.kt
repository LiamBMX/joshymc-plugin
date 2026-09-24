package com.liam.joshymc.gui.stock

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * `/invest` home GUI — 3 entry points: Trade, Create Your Own, My Investments.
 */
object StockHomeGui {

    fun build(plugin: Joshymc, player: Player): CustomGui {
        val gui = CustomGui(Component.text("Stock Market", NamedTextColor.GOLD), 27)
        gui.border(StockGuiUtil.filler(Material.BLACK_STAINED_GLASS_PANE))

        gui.setItem(
            11,
            StockGuiUtil.item(
                Material.GOLD_INGOT,
                Component.text("Trade", NamedTextColor.YELLOW),
                listOf(
                    Component.empty(),
                    Component.text("Browse, buy, and sell public stocks.", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to open", NamedTextColor.GREEN),
                )
            )
        ) { p, _ -> plugin.guiManager.open(p, StockTradingGui.build(plugin, p, StockTradingGui.SORT_DEFAULT, 0)) }

        gui.setItem(
            13,
            StockGuiUtil.item(
                Material.ANVIL,
                Component.text("Create Your Own", NamedTextColor.AQUA),
                listOf(
                    Component.empty(),
                    Component.text("Launch a new public stock.", NamedTextColor.GRAY),
                    Component.text("Cost: ${StockGuiUtil.money(plugin.stockMarketManager.stockCreationCost, plugin.economyManager::formatShort)}", NamedTextColor.GOLD),
                    Component.empty(),
                    Component.text("Click to open", NamedTextColor.GREEN),
                )
            )
        ) { p, _ -> plugin.guiManager.open(p, StockCreateGui.build(plugin, p)) }

        gui.setItem(
            15,
            StockGuiUtil.item(
                Material.CHEST,
                Component.text("My Investments", NamedTextColor.LIGHT_PURPLE),
                listOf(
                    Component.empty(),
                    Component.text("View the stocks you currently own.", NamedTextColor.GRAY),
                    Component.empty(),
                    Component.text("Click to open", NamedTextColor.GREEN),
                )
            )
        ) { p, _ -> plugin.guiManager.open(p, StockPortfolioGui.build(plugin, p, 0)) }

        return gui
    }
}
