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

class KitCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.kit")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        // /kit <name> — claim directly
        if (args.isNotEmpty()) {
            val name = args[0].lowercase()
            if (plugin.kitManager.getKit(name) == null) {
                plugin.commsManager.send(sender, Component.text("Kit '$name' not found.", NamedTextColor.RED))
                return true
            }
            plugin.kitManager.claimKit(sender, name)
            return true
        }

        // /kit — open GUI
        plugin.kitManager.openKitGui(sender)
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
