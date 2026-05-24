package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class InvestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.invest")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (args.isEmpty()) {
            sendBalance(sender)
            return true
        }

        when (args[0].lowercase()) {
            "deposit" -> handleDeposit(sender, args)
            "withdraw" -> handleWithdraw(sender, args)
            "balance", "bal" -> sendBalance(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun sendBalance(player: Player) {
        val balance = plugin.investManager.getBalance(player.uniqueId)
        plugin.commsManager.send(
            player,
            Component.text("Investment balance: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD))
                .append(Component.text(" (0.25% interest/hour)", NamedTextColor.DARK_GRAY)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun handleDeposit(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /invest deposit <amount>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val amount = plugin.economyManager.parseAmount(args[1])
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid amount. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        if (!plugin.economyManager.has(player.uniqueId, amount)) {
            plugin.commsManager.send(player, Component.text("You don't have enough money.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        plugin.economyManager.withdraw(player.uniqueId, amount)
        plugin.investManager.deposit(player.uniqueId, amount)

        plugin.commsManager.send(
            player,
            Component.text("Deposited ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                .append(Component.text(" into your investment account.", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun handleWithdraw(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /invest withdraw <amount>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val amount = plugin.economyManager.parseAmount(args[1])
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid amount. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val success = plugin.investManager.withdraw(player.uniqueId, amount)
        if (!success) {
            plugin.commsManager.send(player, Component.text("You don't have enough in your investment account.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        plugin.economyManager.deposit(player.uniqueId, amount)

        plugin.commsManager.send(
            player,
            Component.text("Withdrew ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                .append(Component.text(" from your investment account.", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(player, Component.text("Usage: /invest <deposit|withdraw|balance> <amount>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("deposit", "withdraw", "balance").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "deposit", "withdraw" -> listOf("100", "1000", "10000").filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
