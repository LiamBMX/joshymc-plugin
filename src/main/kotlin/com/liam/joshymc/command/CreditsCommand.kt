package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
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

    private val adminBalance = AdminBalanceCommand(plugin)

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

        val sub = args[0].lowercase()
        val rest = args.drop(1)

        // Removing or setting someone's Credits is operator-only and works offline (issue #1162).
        when (sub) {
            "take", "remove" -> {
                adminBalance.handle(sender, AdminBalanceCommand.Currency.CREDITS, AdminBalanceCommand.Operation.REMOVE, "/credits $sub <player> <amount>", rest)
                return true
            }
            "set" -> {
                adminBalance.handle(sender, AdminBalanceCommand.Currency.CREDITS, AdminBalanceCommand.Operation.SET, "/credits set <player> <amount>", rest)
                return true
            }
            "reset" -> {
                adminBalance.handleReset(sender, AdminBalanceCommand.Currency.CREDITS, "/credits reset <player>", rest)
                return true
            }
        }

        if (!sender.hasPermission("joshymc.credits")) {
            Msg.noPermission(sender)
            return true
        }

        if (args.size < 3) {
            Msg.usage(sender, "/credits $sub <player> <amount>")
            return true
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            Msg.playerNotFound(sender)
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
            else -> {
                Msg.usage(sender, "/credits <balance|bal|pay|give|take|set|reset> [player] [amount]")
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
                Msg.playerNotFound(sender)
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
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.credits.pay")) {
            plugin.commsManager.send(sender, Component.text("You don't have permission to pay credits.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (args.size < 3) {
            Msg.usage(sender, "/credits pay <amount> <player>", CommunicationsManager.Category.ECONOMY)
            return true
        }

        val amount = args[1].toDoubleOrNull()
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(sender, Component.text("Invalid amount.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val target = Bukkit.getPlayer(args[2])
        if (target == null) {
            Msg.playerNotFound(sender, category = CommunicationsManager.Category.ECONOMY)
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
                if (sender.hasPermission("joshymc.credits")) add("give")
                if (adminBalance.canUse(sender)) addAll(listOf("take", "remove", "set", "reset"))
            }.filter { it.startsWith(args[0].lowercase()) }
            2 -> {
                if (sub in ADMIN_SUBS && !adminBalance.canUse(sender)) return emptyList()
                if (sub == "give" && !sender.hasPermission("joshymc.credits")) return emptyList()
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
                } else if ((sub == "give" && sender.hasPermission("joshymc.credits")) ||
                    (sub in ADMIN_SUBS && sub != "reset" && adminBalance.canUse(sender))) {
                    listOf("1", "5", "10", "100").filter { it.startsWith(args[2]) }
                } else emptyList()
            }
            else -> emptyList()
        }
    }

    companion object {
        private val ADMIN_SUBS = setOf("take", "remove", "set", "reset")
    }
}
