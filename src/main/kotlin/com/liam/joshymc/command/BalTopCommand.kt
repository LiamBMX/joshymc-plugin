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
import org.bukkit.entity.Player
import java.util.UUID

class BalTopCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.baltop")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val topBalances = plugin.economyManager.getTopBalances(10)

        if (topBalances.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("No balances recorded yet.", NamedTextColor.GRAY), CommunicationsManager.Category.ECONOMY)
            return true
        }

        plugin.commsManager.send(
            sender,
            Component.text("Top Balances", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.ECONOMY
        )

        for ((index, pair) in topBalances.withIndex()) {
            val (uuidStr, balance) = pair
            val offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(uuidStr))
            val name = offlinePlayer.name ?: "Unknown"

            sender.sendMessage(
                Component.text(" ${index + 1}. ", NamedTextColor.GRAY)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD))
            )
        }

        return true
    }
}
