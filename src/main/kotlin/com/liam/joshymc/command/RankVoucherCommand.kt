package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.RankVoucherManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/rankvoucher give <player> <rank> [quantity]` — admin command that hands
 * out physical, non-stackable Rank Voucher items (issue #595) for the seven
 * purchasable tiers only (scout, pathfinder, lumberjack, trailblazer,
 * voyager, ranger, pioneer). Staff ranks are never voucher-supported.
 * Redeeming a voucher (right-click) requires no permission; only giving one
 * does, gated behind `joshymc.rankvoucher.admin`.
 */
class RankVoucherCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.rankvoucher.admin")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty() || args[0].lowercase() != "give") {
            sendUsage(sender)
            return true
        }

        val targetName = args.getOrNull(1)
        val rankArg = args.getOrNull(2)?.lowercase()
        if (targetName == null || rankArg == null) {
            sendUsage(sender)
            return true
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            sender.sendMessage(Component.text("Player '$targetName' is not online.", NamedTextColor.RED))
            return true
        }

        if (!plugin.rankVoucherManager.isSupportedRank(rankArg)) {
            sender.sendMessage(
                Component.text(
                    "Unsupported rank. Valid ranks: ${plugin.rankVoucherManager.getSupportedRankIds().joinToString(", ")}",
                    NamedTextColor.RED
                )
            )
            return true
        }

        val quantityArg = args.getOrNull(3)
        val quantity = if (quantityArg == null) 1 else quantityArg.toIntOrNull()
        if (quantity == null || quantity <= 0 || quantity > RankVoucherManager.MAX_QUANTITY) {
            sender.sendMessage(Component.text("Quantity must be a positive whole number (max ${RankVoucherManager.MAX_QUANTITY}).", NamedTextColor.RED))
            return true
        }

        plugin.rankVoucherManager.give(target, rankArg, quantity)
        val rankName = plugin.rankVoucherManager.displayName(rankArg)
        val label2 = "$rankName Rank Voucher${if (quantity == 1) "" else "s"}"

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
        sender.sendMessage(Component.text("Usage: /rankvoucher give <player> <rank> [quantity]", NamedTextColor.RED))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.rankvoucher.admin")) return emptyList()
        return when (args.size) {
            1 -> listOf("give").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].lowercase() == "give") Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            3 -> if (args[0].lowercase() == "give") plugin.rankVoucherManager.getSupportedRankIds().filter { it.startsWith(args[2].lowercase()) } else emptyList()
            else -> emptyList()
        }
    }
}
