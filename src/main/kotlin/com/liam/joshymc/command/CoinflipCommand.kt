package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.coinflip.CoinflipMainGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CoinflipCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("admin", ignoreCase = true)) {
            if (!sender.hasPermission("joshymc.coinflip.admin")) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                return true
            }
            if (args.size < 3 || !args[1].equals("cancel", ignoreCase = true)) {
                sender.sendMessage(Component.text("Usage: /coinflip admin cancel <id>", NamedTextColor.RED))
                return true
            }
            val id = args[2].toIntOrNull()
            if (id == null) {
                sender.sendMessage(Component.text("Invalid Coinflip id.", NamedTextColor.RED))
                return true
            }
            plugin.coinflipManager.adminCancel(sender, id)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.coinflip")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("notify", ignoreCase = true)) {
            val choice = args.getOrNull(1)
            if (choice == null || (!choice.equals("on", ignoreCase = true) && !choice.equals("off", ignoreCase = true))) {
                plugin.commsManager.send(sender, Component.text("Usage: /cf notify <on/off>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }
            val enabled = choice.equals("on", ignoreCase = true)
            plugin.coinflipManager.setNotifyEnabled(sender, enabled)
            val message = if (enabled) "Coinflip notifications are now enabled." else "Coinflip notifications are now disabled."
            plugin.commsManager.send(sender, Component.text(message, NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("create", ignoreCase = true)) {
            if (args.size < 2) {
                plugin.commsManager.send(sender, Component.text("Usage: /coinflip create <amount>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }
            val amount = plugin.economyManager.parseAmount(args[1])
            if (amount == null || amount <= 0.0) {
                plugin.commsManager.send(sender, Component.text("Invalid amount. Use numbers like 100000, 100k, 1m", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }
            plugin.coinflipManager.createCoinflip(sender, amount)
            return true
        }

        // No args or unknown subcommand -> open the browsing GUI directly.
        CoinflipMainGui.open(plugin, sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val subs = mutableListOf("create", "notify")
            if (sender.hasPermission("joshymc.coinflip.admin")) subs.add("admin")
            return subs.filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("admin", ignoreCase = true) && sender.hasPermission("joshymc.coinflip.admin")) {
            return listOf("cancel").filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("notify", ignoreCase = true)) {
            return listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
        }
        return emptyList()
    }
}
