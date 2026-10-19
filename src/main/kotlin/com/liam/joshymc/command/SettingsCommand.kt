package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SettingsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        // No args — open GUI
        if (args.isEmpty()) {
            plugin.settingsManager.openGui(sender)
            return true
        }

        val settingName = args[0].lowercase()
        val settings = plugin.settingsManager.getVisibleSettings(sender)
        val setting = settings.find { it.key.equals(settingName, ignoreCase = true) ||
            it.displayName.replace(" ", "").equals(settingName, ignoreCase = true) ||
            it.displayName.replace(" ", "_").equals(settingName, ignoreCase = true) }

        if (setting == null) {
            plugin.commsManager.send(sender,
                Component.text("Unknown setting '$settingName'.", NamedTextColor.RED),
                CommunicationsManager.Category.SETTINGS
            )
            val available = settings
                .filter { it.permission == null || sender.hasPermission(it.permission!!) }
                .joinToString(", ") { it.key }
            plugin.commsManager.send(sender,
                Component.text("Available: $available", NamedTextColor.GRAY),
                CommunicationsManager.Category.SETTINGS
            )
            return true
        }

        if (setting.permission != null && !sender.hasPermission(setting.permission!!)) {
            plugin.commsManager.send(sender,
                Component.text("No permission.", NamedTextColor.RED),
                CommunicationsManager.Category.SETTINGS
            )
            return true
        }

        // If they specify on/off, set it. Otherwise toggle.
        val explicit = args.getOrNull(1)?.lowercase()
        val newValue = when (explicit) {
            "on", "true", "enable" -> true
            "off", "false", "disable" -> false
            else -> !plugin.settingsManager.getSetting(sender, setting.key)
        }

        if (newValue && !plugin.settingsManager.getSetting(sender, setting.key)) {
            val blocked = setting.enableBlockedReason?.invoke(sender)
            if (blocked != null) {
                Msg.error(sender, blocked, CommunicationsManager.Category.SETTINGS)
                return true
            }
        }

        plugin.settingsManager.setSetting(sender, setting.key, newValue)
        setting.onToggle?.invoke(sender, newValue)

        val status = if (newValue) Component.text("enabled", NamedTextColor.GREEN)
        else Component.text("disabled", NamedTextColor.RED)
        plugin.commsManager.send(sender,
            Component.text("${setting.displayName} ", NamedTextColor.GRAY).append(status),
            CommunicationsManager.Category.SETTINGS
        )
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()

        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return plugin.settingsManager.getVisibleSettings(sender)
                .map { it.key }
                .filter { it.startsWith(prefix) }
        }
        if (args.size == 2) {
            return listOf("on", "off").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
