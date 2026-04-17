package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class UnlinkCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!plugin.linkManager.isLinked(sender.uniqueId)) {
            plugin.commsManager.send(sender, Component.text("You don't have a linked Discord account.", NamedTextColor.RED))
            return true
        }

        plugin.linkManager.unlink(sender.uniqueId)
        plugin.commsManager.send(sender, Component.text("Discord account unlinked.", NamedTextColor.GREEN))
        return true
    }
}
