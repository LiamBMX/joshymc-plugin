package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.bounty.BountyListGui
import com.liam.joshymc.gui.bounty.BountyMainGui
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
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
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.bounty")) {
            Msg.noPermission(sender)
            return true
        }

        if (args.isEmpty()) {
            BountyMainGui.open(plugin, sender)
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
            Msg.usage(player, "/bounty set <player> <amount>")
            return
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            Msg.playerNotFound(player)
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
            plugin.teamManager.announceBountyPlaced(player, target, amount)
        } else {
            plugin.commsManager.send(player, Component.text("Could not place bounty. Insufficient funds.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
        }
    }

    private fun handleList(player: Player) {
        BountyListGui.open(plugin, player)
    }

    private fun handleCancel(player: Player, args: Array<out String>) {
        if (!player.hasPermission("joshymc.bounty.cancel")) {
            Msg.noPermission(player)
            return
        }

        if (args.size < 2) {
            Msg.usage(player, "/bounty cancel <id>")
            return
        }

        val id = args[1].toIntOrNull()
        if (id == null) {
            plugin.commsManager.send(player, Component.text("Invalid bounty ID.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.teamManager.cancelBounty(id)) {
            plugin.commsManager.send(
                player,
                Component.text("Bounty #$id cancelled. Money refunded.", NamedTextColor.GREEN),
                CommunicationsManager.Category.DEFAULT
            )
        } else {
            plugin.commsManager.send(player, Component.text("Could not cancel bounty. It may not exist anymore.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
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
