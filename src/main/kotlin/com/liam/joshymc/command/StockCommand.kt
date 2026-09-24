package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.StockMarketManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/stock promote <ticker>` — pays [StockMarketManager.promotionCost] to broadcast a
 * server-wide advertisement for an existing stock. Purely an advertisement: never touches
 * price, market cap, shares outstanding, holdings, or the pricing algorithm (see
 * [StockMarketManager.promoteStock]).
 */
class StockCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty() || !args[0].equals("promote", ignoreCase = true)) {
            plugin.commsManager.send(
                sender,
                Component.text("Usage: /$label promote <ticker>", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        if (!sender.hasPermission("joshymc.stock.promote")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val ticker = args.getOrNull(1)
        if (ticker == null) {
            plugin.commsManager.send(
                sender,
                Component.text("Usage: /$label promote <ticker>", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        when (val outcome = plugin.stockMarketManager.promoteStock(sender, ticker)) {
            is StockMarketManager.PromoteOutcome.Failure ->
                plugin.commsManager.send(sender, Component.text(outcome.message, NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)

            is StockMarketManager.PromoteOutcome.OnCooldown -> plugin.commsManager.send(
                sender,
                Component.text("You can promote another stock in ${formatCooldown(outcome.remainingMs)}.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )

            is StockMarketManager.PromoteOutcome.Success -> {
                plugin.commsManager.send(
                    sender,
                    Component.text("You promoted ${outcome.stock.name} (${outcome.stock.ticker}) for ${plugin.economyManager.format(plugin.stockMarketManager.promotionCost)}.", NamedTextColor.GREEN),
                    CommunicationsManager.Category.ECONOMY
                )
                broadcastPromotion(sender, outcome.stock)
            }
        }
        return true
    }

    private fun broadcastPromotion(player: Player, stock: StockMarketManager.Stock) {
        plugin.commsManager.broadcast(
            Component.text("[STOCK PROMOTION] ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.text(player.name, NamedTextColor.WHITE))
                .append(Component.text(" is promoting ", NamedTextColor.YELLOW))
                .append(Component.text("${stock.name} (${stock.ticker})", NamedTextColor.GOLD))
                .append(Component.text(" — currently ", NamedTextColor.YELLOW))
                .append(Component.text(plugin.economyManager.formatStockPrice(stock.price), NamedTextColor.GREEN))
                .append(Component.text("/share!", NamedTextColor.YELLOW)),
            CommunicationsManager.Category.ECONOMY
        )
        plugin.commsManager.broadcast(
            Component.text("  Check it out with ", NamedTextColor.GRAY)
                .append(Component.text("/invest", NamedTextColor.YELLOW))
                .append(Component.text(".", NamedTextColor.GRAY)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun formatCooldown(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("promote").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("promote", ignoreCase = true)) {
                plugin.stockMarketManager.getAllStocks()
                    .map { it.ticker }
                    .filter { it.startsWith(args[1], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }
}
