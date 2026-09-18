package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.StaffChatManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class StaffChatCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission(StaffChatManager.PERM)) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("view", ignoreCase = true)) {
            if (!sender.hasPermission(StaffChatManager.PERM_VIEW)) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                return true
            }

            when (args.getOrNull(1)?.lowercase()) {
                "on" -> {
                    plugin.staffChatManager.setViewEnabled(sender, true)
                    plugin.commsManager.send(sender, Component.text("Staff Chat viewing is now enabled.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
                }
                "off" -> {
                    plugin.staffChatManager.setViewEnabled(sender, false)
                    plugin.commsManager.send(sender, Component.text("Staff Chat viewing is now disabled.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
                }
                else -> {
                    plugin.commsManager.send(sender, Component.text("Usage: /staffchat view <on/off>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                }
            }
            return true
        }

        plugin.staffChatManager.toggle(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String>? {
        if (sender !is Player || !sender.hasPermission(StaffChatManager.PERM)) return emptyList()
        return when (args.size) {
            1 -> listOf("view").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("view", ignoreCase = true)) {
                listOf("on", "off").filter { it.startsWith(args[1], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }
}
