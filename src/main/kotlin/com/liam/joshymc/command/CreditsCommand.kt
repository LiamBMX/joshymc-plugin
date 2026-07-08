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

class CreditsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args[0].equals("balance", ignoreCase = true)) {
            return handleBalance(sender, args)
        }

        if (!sender.hasPermission("joshymc.credits")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args[0].lowercase()

        if (sub == "reset") {
            if (args.size < 2) {
                sender.sendMessage(Component.text("Usage: /credits reset <player>", NamedTextColor.RED))
                return true
            }
            val target = Bukkit.getPlayer(args[1])
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
            plugin.creditsManager.setBalance(target.uniqueId, 0.0)
            sender.sendMessage(
                Component.text("Reset ", NamedTextColor.GREEN)
                    .append(Component.text(target.name, NamedTextColor.WHITE))
                    .append(Component.text("'s credits to ", NamedTextColor.GREEN))
                    .append(Component.text(plugin.creditsManager.format(0.0), NamedTextColor.AQUA))
            )
            return true
        }

        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /credits $sub <player> <amount>", NamedTextColor.RED))
            return true
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            return true
        }

        val amount = args[2].toDoubleOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("Invalid amount.", NamedTextColor.RED))
            return true
        }

        when (sub) {
            "give" -> {
                plugin.creditsManager.deposit(target.uniqueId, amount)
                sender.sendMessage(
                    Component.text("Gave ", NamedTextColor.GREEN)
                        .append(Component.text(plugin.creditsManager.format(amount), NamedTextColor.AQUA))
                        .append(Component.text(" credits to ", NamedTextColor.GREEN))
                        .append(Component.text(target.name, NamedTextColor.WHITE))
                )
            }
            "take" -> {
                val success = plugin.creditsManager.withdraw(target.uniqueId, amount)
                if (!success) {
                    sender.sendMessage(
                        Component.text(target.name, NamedTextColor.WHITE)
                            .append(Component.text(" does not have enough credits.", NamedTextColor.RED))
                    )
                } else {
                    sender.sendMessage(
                        Component.text("Took ", NamedTextColor.GREEN)
                            .append(Component.text(plugin.creditsManager.format(amount), NamedTextColor.AQUA))
                            .append(Component.text(" credits from ", NamedTextColor.GREEN))
                            .append(Component.text(target.name, NamedTextColor.WHITE))
                    )
                }
            }
            else -> {
                sender.sendMessage(Component.text("Usage: /credits <balance|give|take|reset> [player] [amount]", NamedTextColor.RED))
            }
        }

        return true
    }

    private fun handleBalance(sender: CommandSender, args: Array<out String>): Boolean {
        // args is either empty or ["balance", <player?>]
        val targetName = if (args.size >= 2) args[1] else null

        val target: Player? = if (targetName != null) Bukkit.getPlayer(targetName) else sender as? Player
        if (target == null) {
            if (targetName == null) {
                sender.sendMessage(Component.text("Console must specify a player: /credits balance <player>", NamedTextColor.RED))
            } else {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            }
            return true
        }

        val isSelf = sender is Player && sender.uniqueId == target.uniqueId

        if (!isSelf && !sender.hasPermission("joshymc.credits")) {
            val message = Component.text("No permission to view another player's credits.", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, message, CommunicationsManager.Category.ECONOMY) else sender.sendMessage(message)
            return true
        }

        val balance = plugin.creditsManager.format(plugin.creditsManager.getBalance(target))
        val message = if (isSelf) {
            Component.text("Your credits: ", NamedTextColor.GRAY)
                .append(Component.text(balance, NamedTextColor.AQUA))
        } else {
            Component.text(target.name, NamedTextColor.WHITE)
                .append(Component.text("'s credits: ", NamedTextColor.GRAY))
                .append(Component.text(balance, NamedTextColor.AQUA))
        }
        if (sender is Player) {
            plugin.commsManager.send(sender, message, CommunicationsManager.Category.ECONOMY)
        } else {
            sender.sendMessage(message)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("balance", "give", "take", "reset").filter { it.startsWith(args[0].lowercase()) }
            2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
            3 -> {
                if (args[0].lowercase() != "reset" && args[0].lowercase() != "balance") {
                    listOf("1", "5", "10", "100").filter { it.startsWith(args[2]) }
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
