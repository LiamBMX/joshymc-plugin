package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CombatManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class PvpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.pvp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.COMBAT)
            return true
        }

        // Can't toggle while in combat
        if (plugin.combatManager.isTagged(sender)) {
            plugin.commsManager.send(sender, Component.text("You cannot toggle PvP while in combat!", NamedTextColor.RED), CommunicationsManager.Category.COMBAT)
            return true
        }

        val explicit = args.getOrNull(0)?.lowercase()
        val newValue = when (explicit) {
            "on" -> true
            "off" -> false
            else -> !plugin.settingsManager.getSetting(sender, CombatManager.PVP_SETTING_KEY)
        }

        plugin.settingsManager.setSetting(sender, CombatManager.PVP_SETTING_KEY, newValue)

        val status = if (newValue) Component.text("enabled", NamedTextColor.GREEN)
        else Component.text("disabled", NamedTextColor.RED)
        plugin.commsManager.send(sender, Component.text("PvP ", NamedTextColor.GRAY).append(status), CommunicationsManager.Category.COMBAT)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}
