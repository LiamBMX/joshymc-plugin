package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class EditKitCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.editkit")) {
            Msg.noPermission(sender)
            return true
        }

        if (args.isEmpty()) {
            Msg.usage(sender, "/editkit <name>")
            return true
        }

        val name = args[0].lowercase()
        plugin.kitManager.openEditKitGui(sender, name)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender.hasPermission("joshymc.editkit")) {
            val prefix = args[0].lowercase()
            return plugin.kitManager.getKitNames().filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
