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
 * `/sj <on|off> [public|private]`: whether the sender (a `joshymc.silentjoin` staff
 * member) sees other silent staff's private join/leave notifications, and optionally
 * (with `joshymc.silentjoin.visibility`) whether their own join/leave is public or
 * private. Both are stored as hidden settings.
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

        val newValue = when (args.getOrNull(0)?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }
        // Optional second argument: null = leave own join visibility unchanged.
        val makePrivate = when (args.getOrNull(1)?.lowercase()) {
            null -> null
            "private" -> true
            "public" -> false
            else -> return usage(sender)
        }
        if (newValue == null || args.size > 2) return usage(sender)

        // Validate before changing anything, so a denied visibility change
        // doesn't half-apply the notification toggle either.
        if (makePrivate != null && !sender.hasPermission(VanishCommand.SILENT_JOIN_VISIBILITY_PERMISSION)) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cYou do not have permission to change your join visibility."), CommunicationsManager.Category.ADMIN)
            return true
        }

        plugin.settingsManager.setSetting(sender, VanishCommand.SILENT_JOIN_NOTIFY_SETTING_KEY, newValue)

        val message = if (newValue) "&aSilent join/leave notifications enabled." else "&cSilent join/leave notifications disabled."
        plugin.commsManager.send(sender, plugin.commsManager.parseLegacy(message), CommunicationsManager.Category.ADMIN)

        if (makePrivate != null) {
            plugin.settingsManager.setSetting(sender, VanishCommand.SILENT_JOIN_PRIVATE_SETTING_KEY, makePrivate)
            val visibility = if (makePrivate) "&eYour join visibility: PRIVATE" else "&aYour join visibility: PUBLIC"
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy(visibility), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    private fun usage(sender: Player): Boolean {
        plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cUsage: /sj <on|off> [public|private]"), CommunicationsManager.Category.ADMIN)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission(VanishCommand.SILENT_JOIN_PERMISSION)) return emptyList()
        if (args.size == 1) {
            return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && sender.hasPermission(VanishCommand.SILENT_JOIN_VISIBILITY_PERMISSION)) {
            return listOf("public", "private").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
