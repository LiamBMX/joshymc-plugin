package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.RotatingBossBarManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** `/serverbar <on|off>`: shows or hides the rotating promo BossBar for the sender only. */
class ServerBarCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Only players can use /serverbar.", NamedTextColor.RED))
            return true
        }

        val newValue = when (args.singleOrNull()?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> {
                plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&cUsage: /serverbar <on|off>"), CommunicationsManager.Category.SETTINGS)
                return true
            }
        }

        plugin.settingsManager.setSetting(sender, RotatingBossBarManager.SETTING_KEY, newValue)
        plugin.rotatingBossBarManager.setVisible(sender, newValue)

        val message = if (newValue) "&aServer bar has been enabled." else "&cServer bar has been disabled."
        plugin.commsManager.send(sender, plugin.commsManager.parseLegacy(message), CommunicationsManager.Category.SETTINGS)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}
