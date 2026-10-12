package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.ModModeManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ModModeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission(ModModeManager.PERM_BASE)) {
            Msg.noPermission(sender, CommunicationsManager.Category.ADMIN)
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "on" -> plugin.modModeManager.enable(sender)
            "off" -> plugin.modModeManager.disable(sender)
            null -> plugin.modModeManager.toggle(sender)
            else -> {
                Msg.usage(sender, "/modmode [on|off]", CommunicationsManager.Category.ADMIN)
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender.hasPermission(ModModeManager.PERM_BASE)) {
            return listOf("on", "off").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
