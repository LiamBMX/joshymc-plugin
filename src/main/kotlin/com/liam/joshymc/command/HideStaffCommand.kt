package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.HideStaffManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class HideStaffCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission(HideStaffManager.PERM)) {
            plugin.commsManager.send(
                sender,
                Component.text("You must be Admin or higher to use this command.", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
            return true
        }

        val enabled = plugin.hideStaffManager.toggle(sender)
        if (enabled) {
            plugin.commsManager.send(sender, Component.text("Active Mod Mode staff are now hidden.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(sender, Component.text("Active Mod Mode staff are now visible.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
        }
        return true
    }
}
