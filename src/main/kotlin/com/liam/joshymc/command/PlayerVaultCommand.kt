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

class PlayerVaultCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.pv")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        // Admin: /pv <number> <username>
        if (args.size >= 2 && sender.hasPermission("joshymc.admin.pv")) {
            val number = args[0].toIntOrNull()
            if (number == null || number < 1) {
                plugin.commsManager.send(
                    sender,
                    Component.text("Usage: /pv <number> <username>", NamedTextColor.RED),
                    CommunicationsManager.Category.DEFAULT
                )
                return true
            }
            val targetName = args[1]
            val target = Bukkit.getOfflinePlayer(targetName)
            if (!target.hasPlayedBefore()) {
                plugin.commsManager.send(sender, Component.text("Player '$targetName' not found.", NamedTextColor.RED))
                return true
            }
            plugin.storageManager.openVaultAsAdmin(sender, target.uniqueId, target.name ?: targetName, number)
            return true
        }

        if (args.isNotEmpty()) {
            val number = args[0].toIntOrNull()
            if (number == null || number < 1) {
                plugin.commsManager.send(
                    sender,
                    Component.text("Usage: /pv [number]", NamedTextColor.RED),
                    CommunicationsManager.Category.DEFAULT
                )
                return true
            }
            plugin.storageManager.openVault(sender, number)
            return true
        }

        // No args: if player has more than 1 vault, show selector; otherwise open vault #1
        val maxVaults = plugin.storageManager.getMaxVaults(sender)
        if (maxVaults > 1) {
            plugin.storageManager.openVaultSelector(sender)
        } else {
            plugin.storageManager.openVault(sender, 1)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        if (args.size == 1) {
            val max = plugin.storageManager.getMaxVaults(sender)
            val prefix = args[0]
            return (1..max).map { it.toString() }.filter { it.startsWith(prefix) }
        }
        if (args.size == 2 && sender.hasPermission("joshymc.admin.pv")) {
            val prefix = args[1].lowercase()
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(prefix) }
        }
        return emptyList()
    }
}
