package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.LotteryManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class LotteryCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "buy" -> handleBuy(sender, args)
            "prize", "pool" -> handlePrize(sender)
            "tickets", "info" -> handleInfo(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handleBuy(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(
                player,
                Component.text("Usage: /lottery buy <amount>", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val count = args[1].toIntOrNull()
        if (count == null || count <= 0) {
            plugin.commsManager.send(
                player,
                Component.text("Please enter a valid whole number of tickets.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val cost = LotteryManager.TICKET_COST * count
        val success = plugin.lotteryManager.buyTickets(player.uniqueId, count)
        if (!success) {
            plugin.commsManager.send(
                player,
                Component.text("You need ", NamedTextColor.RED)
                    .append(Component.text(plugin.economyManager.format(cost), NamedTextColor.GOLD))
                    .append(Component.text(" to buy $count ticket(s).", NamedTextColor.RED)),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        val total = plugin.lotteryManager.getTicketCount(player.uniqueId)
        val prize = plugin.lotteryManager.getPrize()
        plugin.commsManager.send(
            player,
            Component.text("You bought ", NamedTextColor.GREEN)
                .append(Component.text("$count ticket(s)", NamedTextColor.GOLD))
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(cost), NamedTextColor.GOLD))
                .append(Component.text(". You now hold ", NamedTextColor.GREEN))
                .append(Component.text("$total ticket(s)", NamedTextColor.GOLD))
                .append(Component.text(". Prize pool: ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(prize), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun handlePrize(player: Player) {
        val prize = plugin.lotteryManager.getPrize()
        val totalTickets = plugin.lotteryManager.getTotalTickets()
        val minutesLeft = plugin.lotteryManager.getMinutesUntilDraw()

        plugin.commsManager.send(
            player,
            Component.text("Current prize pool: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(prize), NamedTextColor.GREEN))
                .append(Component.text(" ($totalTickets ticket(s) sold, draw in ${minutesLeft}m)", NamedTextColor.DARK_GRAY)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun handleInfo(player: Player) {
        val myTickets = plugin.lotteryManager.getTicketCount(player.uniqueId)
        val totalTickets = plugin.lotteryManager.getTotalTickets()
        val prize = plugin.lotteryManager.getPrize()
        val minutesLeft = plugin.lotteryManager.getMinutesUntilDraw()

        plugin.commsManager.send(
            player,
            Component.text("Your tickets: ", NamedTextColor.GRAY)
                .append(Component.text("$myTickets", NamedTextColor.GOLD))
                .append(Component.text(" / $totalTickets total | Prize: ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(prize), NamedTextColor.GREEN))
                .append(Component.text(" | Draw in ${minutesLeft}m", NamedTextColor.DARK_GRAY)),
            CommunicationsManager.Category.ECONOMY
        )
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(
            player,
            Component.text("Usage: /lottery <buy <amount>|prize|tickets>", NamedTextColor.RED),
            CommunicationsManager.Category.ECONOMY
        )
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("buy", "prize", "tickets").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("buy", ignoreCase = true)) listOf("1", "5", "10", "50").filter { it.startsWith(args[1]) }
            else emptyList()
            else -> emptyList()
        }
    }
}
