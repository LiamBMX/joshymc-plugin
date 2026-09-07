package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.ScoreboardManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ScoreboardCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        val explicit = args.getOrNull(0)?.lowercase()
        val newValue = when (explicit) {
            "on" -> true
            "off" -> false
            else -> !plugin.settingsManager.getSetting(sender, ScoreboardManager.SCOREBOARD_SETTING_KEY)
        }

        plugin.settingsManager.setSetting(sender, ScoreboardManager.SCOREBOARD_SETTING_KEY, newValue)
        plugin.scoreboardManager.refreshSidebar(sender)

        val status = if (newValue) Component.text("enabled", NamedTextColor.GREEN)
        else Component.text("disabled", NamedTextColor.RED)
        plugin.commsManager.send(sender, Component.text("Scoreboard ", NamedTextColor.GRAY).append(status), CommunicationsManager.Category.SETTINGS)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}
