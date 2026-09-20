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

class MessagesCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        val newValue = when (args.getOrNull(0)?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> {
                plugin.commsManager.send(sender, Component.text("Usage: /messages <on|off>", NamedTextColor.RED))
                return true
            }
        }

        plugin.settingsManager.setSetting(sender, CommunicationsManager.PERSONAL_MESSAGES_SETTING_KEY, newValue)

        val status = if (newValue) "enabled" else "disabled"
        plugin.commsManager.send(sender, Component.text("Personal messages are now $status.", NamedTextColor.GREEN))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}
