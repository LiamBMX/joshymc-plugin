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

/**
 * `/sj <on|off>`: whether the sender (a `joshymc.silentjoin` staff member) sees
 * other silent staff's private join/leave notifications. Stored as a hidden setting.
 */
class SilentJoinCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can use /sj.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission(VanishCommand.SILENT_JOIN_PERMISSION)) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        val newValue = when (args.singleOrNull()?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> {
                plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cUsage: /sj <on|off>"), CommunicationsManager.Category.ADMIN)
                return true
            }
        }

        plugin.settingsManager.setSetting(sender, VanishCommand.SILENT_JOIN_NOTIFY_SETTING_KEY, newValue)

        val message = if (newValue) "&aSilent join/leave notifications enabled." else "&cSilent join/leave notifications disabled."
        plugin.commsManager.send(sender, plugin.commsManager.parseLegacy(message), CommunicationsManager.Category.ADMIN)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender.hasPermission(VanishCommand.SILENT_JOIN_PERMISSION)) {
            return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
