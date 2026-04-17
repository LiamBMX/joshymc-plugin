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

class BalanceCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.balance")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (args.isNotEmpty()) {
            val target = Bukkit.getPlayer(args[0])
            if (target == null) {
                plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return true
            }
            val balance = plugin.economyManager.getBalance(target)
            plugin.commsManager.send(
                sender,
                Component.text(target.name, NamedTextColor.WHITE)
                    .append(Component.text("'s balance: ", NamedTextColor.GRAY))
                    .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD)),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        val balance = plugin.economyManager.getBalance(sender)
        plugin.commsManager.send(
            sender,
            Component.text("Your balance: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD)),
            CommunicationsManager.Category.ECONOMY
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
