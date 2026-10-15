package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class EcoCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminBalance = AdminBalanceCommand(plugin)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()
        val rest = args.drop(1)

        // Removing or setting someone's balance is operator-only and works offline (issue #1162).
        when (sub) {
            "take", "remove" -> {
                adminBalance.handle(sender, AdminBalanceCommand.Currency.COINS, AdminBalanceCommand.Operation.REMOVE, "/eco $sub <player> <amount>", rest)
                return true
            }
            "set" -> {
                adminBalance.handle(sender, AdminBalanceCommand.Currency.COINS, AdminBalanceCommand.Operation.SET, "/eco set <player> <amount>", rest)
                return true
            }
            "reset" -> {
                adminBalance.handleReset(sender, AdminBalanceCommand.Currency.COINS, "/eco reset <player>", rest)
                return true
            }
        }

        if (!sender.hasPermission("joshymc.eco")) {
            plugin.commsManager.send(sender as? org.bukkit.entity.Player ?: run {
                Msg.noPermission(sender)
                return true
            }, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (sub != "give") {
            Msg.usage(sender, "/eco <give|take|set|reset> <player> <amount>")
            return true
        }

        if (args.size < 3) {
            Msg.usage(sender, "/eco $sub <player> <amount>")
            return true
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            Msg.playerNotFound(sender)
            return true
        }

        val amount = plugin.economyManager.parseAmount(args[2])
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("Invalid amount. Use numbers like 100, 10k, 1.5m, 1b", NamedTextColor.RED))
            return true
        }

        plugin.economyManager.deposit(target.uniqueId, amount)
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                .append(Component.text(" to ", NamedTextColor.GREEN))
                .append(Component.text(target.name, NamedTextColor.WHITE))
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val sub = args.getOrNull(0)?.lowercase()
        val isAdminSub = sub in setOf("take", "remove", "set", "reset")
        if (args.size >= 2 && (if (isAdminSub) !adminBalance.canUse(sender) else !sender.hasPermission("joshymc.eco"))) return emptyList()
        return when (args.size) {
            1 -> buildList {
                if (sender.hasPermission("joshymc.eco")) add("give")
                if (adminBalance.canUse(sender)) addAll(listOf("take", "remove", "set", "reset"))
            }.filter { it.startsWith(args[0].lowercase()) }
            2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
            3 -> {
                if (sub != "reset") {
                    listOf("100", "1000", "10000", "100000").filter { it.startsWith(args[2]) }
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
