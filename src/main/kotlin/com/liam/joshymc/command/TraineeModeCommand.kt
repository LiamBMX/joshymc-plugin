package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.TraineeModeManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TraineeModeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission(TraineeModeManager.PERM_BASE)) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "on" -> plugin.traineeModeManager.enable(sender)
            "off" -> plugin.traineeModeManager.disable(sender)
            null -> plugin.traineeModeManager.toggle(sender)
            else -> {
                plugin.commsManager.send(sender, Component.text("Usage: /traineemode [on|off]", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender.hasPermission(TraineeModeManager.PERM_BASE)) {
            return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
