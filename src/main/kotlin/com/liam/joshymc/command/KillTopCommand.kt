package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.combat.KillTopGui
import com.liam.joshymc.gui.stats.KillTopGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class KillTopCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.killtop")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.COMBAT)
            return true
        }

        KillTopGui.open(plugin, sender)
        return true
    }
}
