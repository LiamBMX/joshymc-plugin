package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class BountyCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.bounty")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "set" -> handleSet(sender, args)
            "list" -> handleList(sender)
            "cancel" -> handleCancel(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(player, Component.text("Bounty Commands:", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
        val commands = listOf(
            "/bounty set <player> <amount>" to "Place a bounty",
            "/bounty list" to "List all active bounties",
            "/bounty cancel <id>" to "Cancel your bounty (refund)"
        )
        commands.forEach { (cmd, desc) ->
            player.sendMessage(
                Component.text(" $cmd ", NamedTextColor.GRAY)
                    .append(Component.text("- $desc", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun handleSet(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /bounty set <player> <amount>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            plugin.commsManager.send(player, Component.text("Player not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (target == player) {
            plugin.commsManager.send(player, Component.text("You cannot place a bounty on yourself.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val amount = plugin.economyManager.parseAmount(args[2])
        if (amount == null || amount <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid amount.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (!plugin.economyManager.has(player.uniqueId, amount)) {
            plugin.commsManager.send(player, Component.text("You don't have enough money.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.placeBounty(player.uniqueId, player.name, target.uniqueId, target.name, amount)) {
            plugin.commsManager.send(
                player,
                Component.text("Placed a ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GREEN))
                    .append(Component.text(" bounty on ", NamedTextColor.GRAY))
                    .append(Component.text(target.name, NamedTextColor.WHITE)),
                CommunicationsManager.Category.DEFAULT
            )

            // Broadcast to all players
            Bukkit.getOnlinePlayers().forEach { p ->
                if (p != player) {
                    plugin.commsManager.send(
                        p,
                        Component.text(player.name, NamedTextColor.WHITE)
                            .append(Component.text(" placed a ", NamedTextColor.GRAY))
                            .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GREEN))
                            .append(Component.text(" bounty on ", NamedTextColor.GRAY))
                            .append(Component.text(target.name, NamedTextColor.RED)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Could not place bounty. Insufficient funds.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleList(player: Player) {
        val bounties = plugin.teamManager.getBounties()
        if (bounties.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No active bounties.", NamedTextColor.GRAY), CommunicationsManager.Category.DEFAULT)
            return
        }

        player.sendMessage(Component.text("--- Active Bounties ---", NamedTextColor.GREEN))
        bounties.forEach { bounty ->
            player.sendMessage(
                Component.text(" #${bounty.id} ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(bounty.targetName, NamedTextColor.RED))
                    .append(Component.text(" - ", NamedTextColor.GRAY))
                    .append(Component.text(plugin.economyManager.format(bounty.amount), NamedTextColor.GREEN))
                    .append(Component.text(" (by ${bounty.placedByName})", NamedTextColor.DARK_GRAY))
            )
        }

        // Show totals per player
        val grouped = bounties.groupBy { it.targetName }
        if (grouped.any { it.value.size > 1 }) {
            player.sendMessage(Component.text("--- Totals ---", NamedTextColor.GREEN))
            grouped.forEach { (name, playerBounties) ->
                val total = playerBounties.sumOf { it.amount }
                player.sendMessage(
                    Component.text(" $name", NamedTextColor.WHITE)
                        .append(Component.text(": ", NamedTextColor.GRAY))
                        .append(Component.text(plugin.economyManager.format(total), NamedTextColor.GREEN))
                )
            }
        }
    }

    private fun handleCancel(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /bounty cancel <id>", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val id = args[1].toIntOrNull()
        if (id == null) {
            plugin.commsManager.send(player, Component.text("Invalid bounty ID.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.cancelBounty(id, player.uniqueId)) {
            plugin.commsManager.send(
                player,
                Component.text("Bounty #$id cancelled. Money refunded.", NamedTextColor.GREEN),
                CommunicationsManager.Category.DEFAULT
            )
        } else {
            plugin.commsManager.send(player, Component.text("Could not cancel bounty. It may not exist or you didn't place it.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()

        if (args.size == 1) {
            return listOf("set", "list", "cancel")
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }

        if (args.size == 2) {
            return when (args[0].lowercase()) {
                "set" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
        }

        return emptyList()
    }
}
