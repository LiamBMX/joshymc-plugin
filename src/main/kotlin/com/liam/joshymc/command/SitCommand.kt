package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.listener.GSitListener
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class SitCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.sit")) {
            Msg.noPermission(sender)
            return true
        }

        if (sender.vehicle != null) {
            plugin.commsManager.send(sender, Component.text("You are already sitting!", NamedTextColor.RED))
            return true
        }

        @Suppress("DEPRECATION")
        val onGround = sender.isOnGround
        if (!onGround) {
            plugin.commsManager.send(sender, Component.text("You must be on the ground.", NamedTextColor.RED))
            return true
        }

        // Sit at the player's current location, slightly raised to sit on surface
        val seatLoc = sender.location.clone().add(0.0, 0.1, 0.0)
        GSitListener.sit(sender, seatLoc)
        return true
    }
}
