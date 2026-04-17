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

class DelHomeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        val name = (args.getOrNull(0) ?: "home").lowercase()
        val uuid = sender.uniqueId.toString()

        if (plugin.warpManager.deleteHome(uuid, name)) {
            plugin.commsManager.send(sender, Component.text("Home '$name' deleted.", NamedTextColor.GREEN), CommunicationsManager.Category.HOME)
        } else {
            plugin.commsManager.send(sender, Component.text("Home '$name' not found.", NamedTextColor.RED), CommunicationsManager.Category.HOME)
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender is Player) {
            val prefix = args[0].lowercase()
            return plugin.warpManager.getHomes(sender.uniqueId.toString()).filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
