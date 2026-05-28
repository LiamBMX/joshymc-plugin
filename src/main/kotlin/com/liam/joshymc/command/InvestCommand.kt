package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

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
            sendBalance(sender, args)
            return true
        }

        when (args[0].lowercase()) {
            "deposit" -> handleDeposit(sender, args)
            "withdraw" -> handleWithdraw(sender, args)
            "balance", "bal" -> sendBalance(sender, args)
            "balancetop", "baltop" -> sendBalanceTop(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun sendBalance(player: Player, args: Array<out String>) {
        if (args.size >= 2) {
            val targetName = args[1]
            val target = Bukkit.getOfflinePlayerIfCached(targetName)
                ?: Bukkit.getPlayer(targetName)?.let { Bukkit.getOfflinePlayer(it.uniqueId) }
            if (target == null || target.name == null) {
                plugin.commsManager.send(player, Component.text("Player '$targetName' not found.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return
            }
            val balance = plugin.investManager.getBalance(target.uniqueId)
            plugin.commsManager.send(
                player,
                Component.text("${target.name}'s investment balance: ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD)),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val balance = plugin.investManager.getBalance(player.uniqueId)
        plugin.commsManager.send(
            player,
            Component.text("Investment balance: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD))
                .append(Component.text(" (0.25% interest/hour)", NamedTextColor.DARK_GRAY)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun sendBalanceTop(player: Player) {
        val topBalances = plugin.investManager.getTopBalances(10)

        if (topBalances.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No investments recorded yet.", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
            return
        }

        plugin.commsManager.send(
            player,
            Component.text("Top Investments", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.ECONOMY
        )

        for ((index, pair) in topBalances.withIndex()) {
            val (uuidStr, balance) = pair
            val name = try {
                Bukkit.getOfflinePlayer(UUID.fromString(uuidStr)).name ?: "Unknown"
            } catch (e: Exception) {
                "Unknown"
            }
            player.sendMessage(
                Component.text(" ${index + 1}. ", NamedTextColor.GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD))
            )
        }
    }

    private fun handleDeposit(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /invest deposit <amount|all>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val amount = if (args[1].equals("all", ignoreCase = true)) {
            plugin.economyManager.getBalance(player.uniqueId)
        } else {
            plugin.economyManager.parseAmount(args[1])
        }

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
            plugin.commsManager.send(player, Component.text("Usage: /invest withdraw <amount|all>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val amount = if (args[1].equals("all", ignoreCase = true)) {
            plugin.investManager.getBalance(player.uniqueId)
        } else {
            plugin.economyManager.parseAmount(args[1])
        }

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
        plugin.commsManager.send(player, Component.text("Usage: /invest <deposit|withdraw|balance|baltop> [amount|player]", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("deposit", "withdraw", "balance", "baltop").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "deposit", "withdraw" -> listOf("all", "100", "1000", "10000").filter { it.startsWith(args[1].lowercase()) }
                "balance", "bal" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
