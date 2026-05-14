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

class AuctionCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.ah")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("sell", ignoreCase = true)) {
            if (!sender.hasPermission("joshymc.ah.sell")) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            if (args.size < 2) {
                plugin.commsManager.send(sender, Component.text("Usage: /ah sell <price>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            val price = plugin.economyManager.parseAmount(args[1])
            if (price == null || price <= 0) {
                plugin.commsManager.send(sender, Component.text("Invalid price. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            plugin.auctionManager.listItem(sender, price)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("bid", ignoreCase = true)) {
            if (!sender.hasPermission("joshymc.ah.sell")) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            if (args.size < 2) {
                plugin.commsManager.send(sender, Component.text("Usage: /ah bid <starting price>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            val price = plugin.economyManager.parseAmount(args[1])
            if (price == null || price <= 0) {
                plugin.commsManager.send(sender, Component.text("Invalid price. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            plugin.auctionManager.listBidItem(sender, price)
            return true
        }

        // No args or unknown subcommand -> open GUI
        plugin.auctionManager.openMainGui(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("sell", "bid").filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
