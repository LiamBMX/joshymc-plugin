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

class EcoCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.eco")) {
            plugin.commsManager.send(sender as? org.bukkit.entity.Player ?: run {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                return true
            }, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /eco <give|take|set|reset> <player> <amount>", NamedTextColor.RED))
            return true
        }

        val sub = args[0].lowercase()

        if (sub == "reset") {
            if (args.size < 2) {
                sender.sendMessage(Component.text("Usage: /eco reset <player>", NamedTextColor.RED))
                return true
            }
            val target = Bukkit.getPlayer(args[1])
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
            plugin.economyManager.setBalance(target.uniqueId, 0.0)
            sender.sendMessage(
                Component.text("Reset ", NamedTextColor.GREEN)
                    .append(Component.text(target.name, NamedTextColor.WHITE))
                    .append(Component.text("'s balance to ", NamedTextColor.GREEN))
                    .append(Component.text(plugin.economyManager.format(0.0), NamedTextColor.GOLD))
            )
            return true
        }

        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /eco $sub <player> <amount>", NamedTextColor.RED))
            return true
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            return true
        }

        val amount = plugin.economyManager.parseAmount(args[2])
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("Invalid amount. Use numbers like 100, 10k, 1.5m, 1b", NamedTextColor.RED))
            return true
        }

        when (sub) {
            "give" -> {
                plugin.economyManager.deposit(target.uniqueId, amount)
                sender.sendMessage(
                    Component.text("Gave ", NamedTextColor.GREEN)
                        .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                        .append(Component.text(" to ", NamedTextColor.GREEN))
                        .append(Component.text(target.name, NamedTextColor.WHITE))
                )
            }
            "take" -> {
                val success = plugin.economyManager.withdraw(target.uniqueId, amount)
                if (!success) {
                    sender.sendMessage(
                        Component.text(target.name, NamedTextColor.WHITE)
                            .append(Component.text(" does not have enough funds.", NamedTextColor.RED))
                    )
                } else {
                    sender.sendMessage(
                        Component.text("Took ", NamedTextColor.GREEN)
                            .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                            .append(Component.text(" from ", NamedTextColor.GREEN))
                            .append(Component.text(target.name, NamedTextColor.WHITE))
                    )
                }
            }
            "set" -> {
                plugin.economyManager.setBalance(target.uniqueId, amount)
                sender.sendMessage(
                    Component.text("Set ", NamedTextColor.GREEN)
                        .append(Component.text(target.name, NamedTextColor.WHITE))
                        .append(Component.text("'s balance to ", NamedTextColor.GREEN))
                        .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                )
            }
            else -> {
                sender.sendMessage(Component.text("Usage: /eco <give|take|set|reset> <player> <amount>", NamedTextColor.RED))
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("give", "take", "set", "reset").filter { it.startsWith(args[0].lowercase()) }
            2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
            3 -> {
                if (args[0].lowercase() != "reset") {
                    listOf("100", "1000", "10000", "100000").filter { it.startsWith(args[2]) }
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
