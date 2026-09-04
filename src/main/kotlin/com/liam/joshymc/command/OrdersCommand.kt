package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class OrdersCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("admin", ignoreCase = true)) {
            return handleAdmin(sender, args)
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.orders")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        plugin.orderManager.openMainGui(sender)
        return true
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.orders.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /orders admin <view|info|cancel|remove|reload> ...", NamedTextColor.RED))
            return true
        }

        when (args[1].lowercase()) {
            "reload" -> {
                plugin.orderManager.reloadValues()
                sender.sendMessage(Component.text("Buy Orders configuration reloaded.", NamedTextColor.GREEN))
            }

            "view" -> {
                if (args.size < 3) {
                    sender.sendMessage(Component.text("Usage: /orders admin view <player>", NamedTextColor.RED))
                    return true
                }
                val target = Bukkit.getPlayer(args[2])
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found (must be online).", NamedTextColor.RED))
                    return true
                }
                val orders = plugin.orderManager.getPlayerOrders(target.uniqueId)
                if (orders.isEmpty()) {
                    sender.sendMessage(Component.text("${target.name} has no active Buy Orders.", NamedTextColor.YELLOW))
                    return true
                }
                sender.sendMessage(Component.text("${target.name}'s active Buy Orders:", NamedTextColor.GOLD))
                for (order in orders) {
                    sender.sendMessage(
                        Component.text("  #${order.id} ", NamedTextColor.WHITE)
                            .append(Component.text("${order.remainingQty}/${order.originalQty}x ${order.item.type} @ ${plugin.economyManager.format(order.pricePerItem)} each (escrow ${plugin.economyManager.format(order.remainingEscrow)})", NamedTextColor.GRAY))
                    )
                }
            }

            "info" -> {
                val id = args.getOrNull(2)?.toIntOrNull()
                if (id == null) {
                    sender.sendMessage(Component.text("Usage: /orders admin info <id>", NamedTextColor.RED))
                    return true
                }
                val order = plugin.orderManager.getOrderById(id)
                if (order == null) {
                    sender.sendMessage(Component.text("Order #$id not found.", NamedTextColor.RED))
                    return true
                }
                sender.sendMessage(Component.text("Order #${order.id}", NamedTextColor.GOLD))
                sender.sendMessage(Component.text("  Buyer: ${order.buyerName} (${order.buyerUuid})", NamedTextColor.GRAY))
                sender.sendMessage(Component.text("  Item: ${order.item.type} — ${order.remainingQty}/${order.originalQty} remaining", NamedTextColor.GRAY))
                sender.sendMessage(Component.text("  Price Each: ${plugin.economyManager.format(order.pricePerItem)}", NamedTextColor.GRAY))
                sender.sendMessage(Component.text("  Escrow Remaining: ${plugin.economyManager.format(order.remainingEscrow)} / ${plugin.economyManager.format(order.originalEscrow)}", NamedTextColor.GRAY))
                sender.sendMessage(Component.text("  Expires: ${java.time.Instant.ofEpochMilli(order.expiresAt)}", NamedTextColor.GRAY))
            }

            "cancel", "remove" -> {
                val id = args.getOrNull(2)?.toIntOrNull()
                if (id == null) {
                    sender.sendMessage(Component.text("Usage: /orders admin ${args[1].lowercase()} <id>", NamedTextColor.RED))
                    return true
                }
                val order = plugin.orderManager.adminForceCancel(sender.name, id)
                if (order == null) {
                    sender.sendMessage(Component.text("Order #$id not found.", NamedTextColor.RED))
                    return true
                }
                sender.sendMessage(
                    Component.text("Removed order #$id (${order.buyerName}'s ${order.originalQty}x ${order.item.type}). Refunded ${plugin.economyManager.format(order.remainingEscrow)}.", NamedTextColor.GREEN)
                )
            }

            else -> sender.sendMessage(Component.text("Usage: /orders admin <view|info|cancel|remove|reload> ...", NamedTextColor.RED))
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("admin").filter { it.startsWith(args[0].lowercase()) }
        }
        if (args.size == 2 && args[0].equals("admin", ignoreCase = true)) {
            return listOf("view", "info", "cancel", "remove", "reload").filter { it.startsWith(args[1].lowercase()) }
        }
        if (args.size == 3 && args[0].equals("admin", ignoreCase = true) && args[1].equals("view", ignoreCase = true)) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
        }
        return emptyList()
    }
}
