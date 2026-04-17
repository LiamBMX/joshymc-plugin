package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SetWarpCommand(private val plugin: Joshymc) : CommandExecutor {

    companion object {
        private val NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,20}$")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.setwarp")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /setwarp <name>", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        val name = args[0].lowercase()

        if (!NAME_REGEX.matches(name)) {
            plugin.commsManager.send(sender, Component.text("Warp names must be alphanumeric/underscores, max 20 characters.", NamedTextColor.RED), CommunicationsManager.Category.WARP)
            return true
        }

        plugin.warpManager.setWarp(name, sender.uniqueId.toString(), sender.location)
        plugin.commsManager.send(sender, Component.text("Server warp '$name' created.", NamedTextColor.GREEN), CommunicationsManager.Category.WARP)
        return true
    }
}
