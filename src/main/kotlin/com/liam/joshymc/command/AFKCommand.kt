package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class AFKCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.afk")) {
            Msg.noPermission(sender, CommunicationsManager.Category.AFK)
            return true
        }

        // Deny if in combat
        if (plugin.combatManager.isTagged(sender)) {
            plugin.commsManager.send(sender, Component.text("You cannot go AFK while in combat!", NamedTextColor.RED), CommunicationsManager.Category.AFK)
            return true
        }

        plugin.afkManager.toggleAfk(sender)
        return true
    }
}
