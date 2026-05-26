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

class TokensCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        const val TOKEN_VALUE = 100_000.0
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()

        return when (sub) {
            "give" -> handleGive(sender, args)
            "sell" -> handleSell(sender, args)
            else -> {
                sendUsage(sender)
                true
            }
        }
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.tokens.give")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /tokens give <amount> <player>", NamedTextColor.YELLOW))
            return true
        }
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("Amount must be a positive integer.", NamedTextColor.RED))
            return true
        }
        val target = Bukkit.getPlayer(args[2])
        if (target == null) {
            sender.sendMessage(Component.text("Player '${args[2]}' is not online.", NamedTextColor.RED))
            return true
        }

        val tokenItem = plugin.itemManager.getItem("token")?.createItemStack(amount)
        if (tokenItem == null) {
            sender.sendMessage(Component.text("Token item not found. Contact an administrator.", NamedTextColor.RED))
            return true
        }

        val overflow = target.inventory.addItem(tokenItem)
        if (overflow.isNotEmpty()) {
            target.world.dropItemNaturally(target.location, overflow.values.first())
        }

        target.sendMessage(
            Component.text("You received ", NamedTextColor.GOLD)
                .append(Component.text("$amount token(s)", NamedTextColor.YELLOW))
                .append(Component.text(".", NamedTextColor.GOLD))
        )
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text("$amount token(s)", NamedTextColor.YELLOW))
                .append(Component.text(" to ${target.name}.", NamedTextColor.GREEN))
        )
        return true
    }

    private fun handleSell(sender: CommandSender, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can sell tokens.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.tokens.sell")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /tokens sell <amount>", NamedTextColor.YELLOW))
            return true
        }
        val amount = args[1].toIntOrNull()
        if (amount == null || amount <= 0) {
            sender.sendMessage(Component.text("Amount must be a positive integer.", NamedTextColor.RED))
            return true
        }

        val held = countTokens(sender)
        if (held < amount) {
            sender.sendMessage(
                Component.text("You only have $held token(s) in your inventory.", NamedTextColor.RED)
            )
            return true
        }

        removeTokens(sender, amount)
        val payout = amount * TOKEN_VALUE
        plugin.economyManager.deposit(sender.uniqueId, payout)

        sender.sendMessage(
            Component.text("Sold ", NamedTextColor.GREEN)
                .append(Component.text("$amount token(s)", NamedTextColor.YELLOW))
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(payout), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
        return true
    }

    private fun countTokens(player: Player): Int {
        return player.inventory.contents
            .orEmpty()
            .filterNotNull()
            .filter { plugin.itemManager.isCustomItem(it, "token") }
            .sumOf { it.amount }
    }

    private fun removeTokens(player: Player, amount: Int) {
        var remaining = amount
        val contents = player.inventory.contents ?: return
        for (i in contents.indices) {
            val stack = contents[i] ?: continue
            if (!plugin.itemManager.isCustomItem(stack, "token")) continue
            if (stack.amount <= remaining) {
                remaining -= stack.amount
                player.inventory.setItem(i, null)
            } else {
                stack.amount -= remaining
                remaining = 0
            }
            if (remaining <= 0) break
        }
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(
            Component.text("Usage:", NamedTextColor.YELLOW).append(Component.newline())
                .append(Component.text("  /tokens give <amount> <player>", NamedTextColor.GOLD)).append(Component.newline())
                .append(Component.text("  /tokens sell <amount>", NamedTextColor.GOLD))
        )
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> buildList {
                if (sender.hasPermission("joshymc.tokens.give")) add("give")
                if (sender.hasPermission("joshymc.tokens.sell")) add("sell")
            }.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "give" -> listOf("1", "5", "10").filter { it.startsWith(args[1]) }
                "sell" -> listOf("1", "5", "10").filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
            3 -> if (args[0].lowercase() == "give" && sender.hasPermission("joshymc.tokens.give")) {
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }
}
