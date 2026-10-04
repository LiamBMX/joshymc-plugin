package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class CreditsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty() || args[0].equals("balance", ignoreCase = true) || args[0].equals("bal", ignoreCase = true)) {
            return handleBalance(sender, args)
        }

        if (args[0].equals("pay", ignoreCase = true)) {
            return handlePay(sender, args)
        }

        if (args[0].equals("baltop", ignoreCase = true)) {
            return handleBaltop(sender)
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
                sender.sendMessage(Component.text("Usage: /credits <balance|bal|pay|give|take|reset> [player] [amount]", NamedTextColor.RED))
            }
        }

        return true
    }

    private fun handleBalance(sender: CommandSender, args: Array<out String>): Boolean {
        // args is either empty or ["balance", <player?>]
        val targetName = if (args.size >= 2) args[1] else null

        val target: Pair<String, UUID>? = if (targetName != null) {
            resolveTarget(targetName)
        } else if (sender is Player) {
            sender.name to sender.uniqueId
        } else {
            null
        }
        if (target == null) {
            if (targetName == null) {
                sender.sendMessage(Component.text("Console must specify a player: /credits balance <player>", NamedTextColor.RED))
            } else {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED))
            }
            return true
        }
        val (targetDisplayName, targetUuid) = target

        val isSelf = sender is Player && sender.uniqueId == targetUuid

        if (!isSelf && !sender.hasPermission("joshymc.credits.balance")) {
            val message = Component.text("No permission to view another player's credits.", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, message, CommunicationsManager.Category.ECONOMY) else sender.sendMessage(message)
            return true
        }

        val balance = plugin.creditsManager.format(plugin.creditsManager.getBalance(targetUuid))
        val message = if (isSelf) {
            Component.text("Your credits: ", NamedTextColor.GRAY)
                .append(Component.text(balance, NamedTextColor.AQUA))
        } else {
            Component.text(targetDisplayName, NamedTextColor.WHITE)
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

    private fun handleBaltop(sender: CommandSender): Boolean {
        fun reply(c: Component) {
            if (sender is Player) plugin.commsManager.send(sender, c, CommunicationsManager.Category.ECONOMY) else sender.sendMessage(c)
        }
        if (!sender.hasPermission("joshymc.credits.baltop")) {
            reply(Component.text("You don't have permission to view the credits leaderboard.", NamedTextColor.RED))
            return true
        }
        val top = plugin.creditsManager.getTopBalances(10)
        reply(Component.text("CREDITS LEADERBOARD", NamedTextColor.AQUA, TextDecoration.BOLD))
        reply(Component.text("Top 10 Players by Credits", NamedTextColor.GRAY))
        if (top.isEmpty()) {
            reply(Component.text("No one has any credits yet.", NamedTextColor.GRAY))
            return true
        }
        top.forEachIndexed { i, (uuid, bal) ->
            val name = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
            val rankColor = if (i % 2 == 0) NamedTextColor.GOLD else NamedTextColor.GRAY
            reply(
                Component.text("#${i + 1} ", rankColor)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text("${plugin.creditsManager.format(bal)} Credits", NamedTextColor.AQUA))
            )
        }
        return true
    }

    private fun resolveTarget(name: String): Pair<String, UUID>? {
        val online = Bukkit.getPlayerExact(name)
        if (online != null) return online.name to online.uniqueId
        val cached = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return (cached.name ?: name) to cached.uniqueId
    }

    private fun handlePay(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.credits.pay")) {
            plugin.commsManager.send(sender, Component.text("You don't have permission to pay credits.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (args.size < 3) {
            plugin.commsManager.send(sender, Component.text("Usage: /credits pay <amount> <player>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val amount = args[1].toDoubleOrNull()
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(sender, Component.text("Invalid amount.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val target = Bukkit.getPlayer(args[2])
        if (target == null) {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (target.uniqueId == sender.uniqueId) {
            plugin.commsManager.send(sender, Component.text("You cannot pay yourself.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (!plugin.settingsManager.getSetting(target, "credit_payments")) {
            plugin.commsManager.send(sender, Component.text("${target.name} has Credit payments disabled.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val success = plugin.creditsManager.withdraw(sender.uniqueId, amount)
        if (!success) {
            plugin.commsManager.send(sender, Component.text("You don't have enough credits.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        plugin.creditsManager.deposit(target.uniqueId, amount)

        val formatted = plugin.creditsManager.format(amount)
        plugin.commsManager.send(
            sender,
            Component.text("You sent ", NamedTextColor.GREEN)
                .append(Component.text(formatted, NamedTextColor.AQUA))
                .append(Component.text(" credits to ", NamedTextColor.GREEN))
                .append(Component.text(target.name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )

        plugin.commsManager.send(
            target,
            Component.text("You received ", NamedTextColor.GREEN)
                .append(Component.text(formatted, NamedTextColor.AQUA))
                .append(Component.text(" credits from ", NamedTextColor.GREEN))
                .append(Component.text(sender.name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val sub = args.getOrNull(0)?.lowercase()
        return when (args.size) {
            1 -> buildList {
                add("balance"); add("bal")
                if (sender.hasPermission("joshymc.credits.baltop")) add("baltop")
                if (sender.hasPermission("joshymc.credits.pay")) add("pay")
                if (sender.hasPermission("joshymc.credits")) addAll(listOf("give", "take", "reset"))
            }.filter { it.startsWith(args[0].lowercase()) }
            2 -> {
                if (sub == "pay") {
                    if (!sender.hasPermission("joshymc.credits.pay")) return emptyList()
                    listOf("1", "5", "10", "100").filter { it.startsWith(args[1]) }
                } else {
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
                }
            }
            3 -> {
                if (sub == "pay") {
                    if (!sender.hasPermission("joshymc.credits.pay")) return emptyList()
                    Bukkit.getOnlinePlayers().map { it.name }
                        .filter { it != sender.name }
                        .filter { it.lowercase().startsWith(args[2].lowercase()) }
                } else if (sub != "reset" && sub != "balance" && sub != "bal") {
                    listOf("1", "5", "10", "100").filter { it.startsWith(args[2]) }
                } else emptyList()
            }
            else -> emptyList()
        }
    }
}
