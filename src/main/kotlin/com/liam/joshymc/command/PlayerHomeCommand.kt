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

class PlayerHomeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.phome")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /phome <player> <home>", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        val targetName = args[0]
        val homeName = args[1].lowercase()

        val offline = Bukkit.getOfflinePlayer(targetName)
        if (!offline.hasPlayedBefore()) {
            plugin.commsManager.send(sender, Component.text("Player '$targetName' has never played on this server.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        val uuid = offline.uniqueId.toString()
        val location = plugin.warpManager.getHome(uuid, homeName)
        if (location == null) {
            plugin.commsManager.send(sender, Component.text("$targetName has no home named '$homeName'.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
            return true
        }

        BackCommand.lastLocations[sender.uniqueId] = sender.location
        sender.teleport(location)
        plugin.commsManager.send(sender, Component.text("Teleported to ${offline.name ?: targetName}'s home '$homeName'.", NamedTextColor.GREEN), CommunicationsManager.Category.HOME)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.phome")) return emptyList()

        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(prefix) }
        }

        if (args.size == 2) {
            val targetName = args[0]
            val prefix = args[1].lowercase()
            val offline = Bukkit.getOfflinePlayer(targetName)
            if (!offline.hasPlayedBefore()) return emptyList()
            return plugin.warpManager.getHomes(offline.uniqueId.toString()).filter { it.startsWith(prefix) }
        }

        return emptyList()
    }
}
