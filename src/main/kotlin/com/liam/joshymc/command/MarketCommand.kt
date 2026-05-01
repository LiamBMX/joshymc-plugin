package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class MarketCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            // /market with no args opens the shop
            plugin.serverShopManager.openMainMenu(sender)
            return true
        }

        when (args[0].lowercase()) {
            "price" -> handlePrice(sender, args)
            "trends" -> handleTrends(sender)
            "info" -> handleInfo(sender)
            else -> {
                plugin.commsManager.send(
                    sender,
                    Component.text("Usage: /market [price <item> | trends | info]", NamedTextColor.RED),
                    CommunicationsManager.Category.ECONOMY
                )
            }
        }
        return true
    }

    private fun handlePrice(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(
                player,
                Component.text("Usage: /market price <item>", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val materialName = args.drop(1).joinToString("_").uppercase()
        val material = Material.matchMaterial(materialName)

        if (material == null) {
            plugin.commsManager.send(
                player,
                Component.text("Unknown item: ${args.drop(1).joinToString(" ")}", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val buyPrice = plugin.marketManager.getCurrentBuyPrice(material) ?: 0.0
        val sellPrice = plugin.marketManager.getCurrentSellPrice(material) ?: 0.0

        if (buyPrice <= 0.0 && sellPrice <= 0.0) {
            plugin.commsManager.send(
                player,
                Component.text("${formatMaterial(material)} is not traded on the market.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val trend = plugin.marketManager.getTrend(material)
        val multiplier = plugin.marketManager.getMultiplier(material)
        val percentChange = ((multiplier - 1.0) * 100).toInt()
        val sign = if (percentChange >= 0) "+" else ""
        val trendText = "${trend.legacyCode}${trend.symbol} $sign$percentChange%"

        val message = "&f${formatMaterial(material)} &7— " +
            "&fBuy: &e${plugin.economyManager.format(buyPrice)} &7($trendText&7) &8| " +
            "&fSell: &e${plugin.economyManager.format(sellPrice)} &7($trendText&7)"

        plugin.commsManager.send(
            player,
            plugin.commsManager.parseLegacy(message),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun handleTrends(player: Player) {
        val movers = plugin.marketManager.getTopMovers().take(10)

        if (movers.isEmpty()) {
            plugin.commsManager.send(
                player,
                Component.text("No market data available yet.", NamedTextColor.GRAY),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        plugin.commsManager.send(
            player,
            Component.text("--- Market Trends ---", NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD),
            CommunicationsManager.Category.ECONOMY
        )

        movers.forEachIndexed { index, (material, multiplier) ->
            val trend = plugin.marketManager.getTrend(material)
            val percentChange = ((multiplier - 1.0) * 100).toInt()
            val sign = if (percentChange >= 0) "+" else ""
            val multiplierFormatted = String.format("%.2f", multiplier)

            val line = "  &7${index + 1}. &f${formatMaterial(material)} " +
                "${trend.legacyCode}${trend.symbol} &7${multiplierFormatted}x &8($sign$percentChange%)"

            player.sendMessage(plugin.commsManager.parseLegacy(line))
        }
    }

    private fun handleInfo(player: Player) {
        plugin.commsManager.send(
            player,
            Component.text("--- Stock Market ---", NamedTextColor.GOLD)
                .decorate(TextDecoration.BOLD),
            CommunicationsManager.Category.ECONOMY
        )

        val lines = listOf(
            "&7Prices change based on supply and demand!",
            "  &7- High demand (lots of buying) → prices go &aUP",
            "  &7- High supply (lots of selling) → prices go &cDOWN",
            "  &7- Prices update every &f5 minutes",
            "  &7- Activity is tracked over &f24 hours",
            "",
            "&7Use &e/market &7to browse, &e/market price <item> &7to check prices."
        )

        for (line in lines) {
            player.sendMessage(plugin.commsManager.parseLegacy(line))
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (sender !is Player) return emptyList()

        return when (args.size) {
            1 -> listOf("price", "trends", "info")
                .filter { it.startsWith(args[0], ignoreCase = true) }

            2 -> when {
                args[0].equals("price", ignoreCase = true) -> {
                    val prefix = args[1].lowercase()
                    Material.entries
                        .filter { it.isItem && !it.isAir }
                        .map { it.name.lowercase() }
                        .filter { it.startsWith(prefix) }
                        .take(30)
                }
                else -> emptyList()
            }

            else -> emptyList()
        }
    }

    private fun formatMaterial(material: Material): String {
        return material.name.lowercase()
            .split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercaseChar() }
            }
    }
}
