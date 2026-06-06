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

            if (args.size < 3) {
                plugin.commsManager.send(sender, Component.text("Usage: /ah bid <starting price> <time>  (e.g. 5m, 30m, 1h, 2h)", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            val price = plugin.economyManager.parseAmount(args[1])
            if (price == null || price <= 0) {
                plugin.commsManager.send(sender, Component.text("Invalid price. Use numbers like 100, 10k, 1.5m", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            val durationMs = parseDurationMs(args[2])
            if (durationMs == null) {
                plugin.commsManager.send(sender, Component.text("Invalid time. Use formats like 5m, 30m, 1h, 2h.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }
            if (durationMs < MIN_BID_DURATION_MS) {
                plugin.commsManager.send(sender, Component.text("Minimum bid duration is 5 minutes.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }
            if (durationMs > MAX_BID_DURATION_MS) {
                plugin.commsManager.send(sender, Component.text("Maximum bid duration is 2 hours.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }

            plugin.auctionManager.listBidItem(sender, price, durationMs)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("notify", ignoreCase = true)) {
            if (args.size < 2 || !args[1].equals("on", ignoreCase = true) && !args[1].equals("off", ignoreCase = true)) {
                plugin.commsManager.send(sender, Component.text("Usage: /ah notify <on|off>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                return true
            }
            plugin.auctionManager.setNotifications(sender, args[1].equals("on", ignoreCase = true))
            return true
        }

        // No args or unknown subcommand -> open GUI
        plugin.auctionManager.openMainGui(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("sell", "bid", "notify").filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("notify", ignoreCase = true)) {
            return listOf("on", "off").filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 3 && args[0].equals("bid", ignoreCase = true)) {
            return listOf("5m", "15m", "30m", "1h", "2h").filter { it.startsWith(args[2].lowercase()) }
        }
        return emptyList()
    }

    companion object {
        private const val MIN_BID_DURATION_MS = 5 * 60_000L
        private const val MAX_BID_DURATION_MS = 2 * 3_600_000L
    }

    private fun parseDurationMs(input: String): Long? {
        val lower = input.lowercase()
        return when {
            lower.endsWith("h") -> lower.dropLast(1).toLongOrNull()?.let { it * 3_600_000L }
            lower.endsWith("m") -> lower.dropLast(1).toLongOrNull()?.let { it * 60_000L }
            else -> lower.toLongOrNull()?.let { it * 60_000L }
        }
    }
}
