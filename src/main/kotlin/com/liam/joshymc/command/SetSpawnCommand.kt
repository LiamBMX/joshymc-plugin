package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SetSpawnCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.setspawn")) {
            plugin.commsManager.send(sender, Component.text("You don't have permission to do that.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        plugin.warpManager.setSpawn(sender.location)
        plugin.commsManager.send(sender, Component.text("Spawn set.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        return true
    }
}
