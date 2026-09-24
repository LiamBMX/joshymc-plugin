package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.CreditVoucherManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/creditvoucher give <player> <amount> [quantity]` — admin command that hands
 * out physical, non-stackable Credit Voucher items (issue #595). Redeeming a
 * voucher (right-click) requires no permission; only giving one does, gated
 * behind `joshymc.creditvoucher.admin`.
 */
class CreditVoucherCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.creditvoucher.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty() || args[0].lowercase() != "give") {
            sendUsage(sender)
            return true
        }

        val targetName = args.getOrNull(1)
        val amountArg = args.getOrNull(2)
        if (targetName == null || amountArg == null) {
            sendUsage(sender)
            return true
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            sender.sendMessage(Component.text("Player '$targetName' is not online.", NamedTextColor.RED))
            return true
        }

        val amount = amountArg.toIntOrNull()
        if (amount == null || amount <= 0 || amount > CreditVoucherManager.MAX_AMOUNT) {
            sender.sendMessage(Component.text("Amount must be a positive whole number (max ${CreditVoucherManager.MAX_AMOUNT}).", NamedTextColor.RED))
            return true
        }

        val quantityArg = args.getOrNull(3)
        val quantity = if (quantityArg == null) 1 else quantityArg.toIntOrNull()
        if (quantity == null || quantity <= 0 || quantity > CreditVoucherManager.MAX_QUANTITY) {
            sender.sendMessage(Component.text("Quantity must be a positive whole number (max ${CreditVoucherManager.MAX_QUANTITY}).", NamedTextColor.RED))
            return true
        }

        plugin.creditVoucherManager.give(target, amount, quantity)

        val label2 = "$amount Credit Voucher${if (quantity == 1) "" else "s"}"
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text("${target.name} ", NamedTextColor.WHITE))
                .append(Component.text("${quantity}x ", NamedTextColor.AQUA))
                .append(Component.text(label2, NamedTextColor.GREEN))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
        if (sender != target) {
            plugin.commsManager.send(
                target,
                Component.text("You received ", NamedTextColor.GREEN)
                    .append(Component.text("${quantity}x ", NamedTextColor.AQUA))
                    .append(Component.text(label2, NamedTextColor.GREEN))
                    .append(Component.text(" from an admin.", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        }
        return true
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage: /creditvoucher give <player> <amount> [quantity]", NamedTextColor.RED))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.creditvoucher.admin")) return emptyList()
        return when (args.size) {
            1 -> listOf("give").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].lowercase() == "give") Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            else -> emptyList()
        }
    }
}
