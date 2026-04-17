package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class DeleteKitCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.deletekit")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /deletekit <name>", NamedTextColor.RED))
            return true
        }

        val name = args[0].lowercase()

        if (plugin.kitManager.deleteKit(name)) {
            plugin.commsManager.send(sender, Component.text("Kit '$name' deleted.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(sender, Component.text("Kit '$name' not found.", NamedTextColor.RED))
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return plugin.kitManager.getKitNames().filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
