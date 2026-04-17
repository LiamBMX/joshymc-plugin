package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class PayCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.pay")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /pay <player> <amount>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (target.uniqueId == sender.uniqueId) {
            plugin.commsManager.send(sender, Component.text("You cannot pay yourself.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val amount = plugin.economyManager.parseAmount(args[1])
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(sender, Component.text("Invalid amount. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (amount < 1.0) {
            plugin.commsManager.send(sender, Component.text("Minimum payment is ${plugin.economyManager.format(1.0)}.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val success = plugin.economyManager.withdraw(sender.uniqueId, amount)
        if (!success) {
            plugin.commsManager.send(sender, Component.text("Insufficient funds.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        plugin.economyManager.deposit(target.uniqueId, amount)

        val formatted = plugin.economyManager.format(amount)
        plugin.commsManager.send(
            sender,
            Component.text("You sent ", NamedTextColor.GREEN)
                .append(Component.text(formatted, NamedTextColor.GOLD))
                .append(Component.text(" to ", NamedTextColor.GREEN))
                .append(Component.text(target.name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )

        plugin.commsManager.send(
            target,
            Component.text("You received ", NamedTextColor.GREEN)
                .append(Component.text(formatted, NamedTextColor.GOLD))
                .append(Component.text(" from ", NamedTextColor.GREEN))
                .append(Component.text(sender.name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> Bukkit.getOnlinePlayers().map { it.name }
                .filter { it.lowercase().startsWith(args[0].lowercase()) }
                .filter { it != sender.name }
            2 -> listOf("100", "1000", "10000").filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
    }
}
