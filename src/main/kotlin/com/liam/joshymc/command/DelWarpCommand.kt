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

class DelWarpCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.delwarp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /delwarp <name>", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        val name = args[0].lowercase()

        if (plugin.warpManager.forceDeleteWarp(name)) {
            plugin.commsManager.send(sender, Component.text("Server warp '$name' deleted.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
        } else {
            plugin.commsManager.send(sender, Component.text("Warp '$name' not found.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            return plugin.warpManager.getAllWarps().map { it.name }.filter { it.startsWith(prefix) }
        }
        return emptyList()
    }
}
