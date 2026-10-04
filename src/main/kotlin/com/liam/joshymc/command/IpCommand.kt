package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class IpCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val ip = plugin.config.getString("server-info.ip") ?: "play.joshymc.net"
        val port = plugin.config.getInt("server-info.port", 25565)
        val comms = plugin.commsManager

        sender.sendMessage(comms.parseLegacy("&6&lJOSHYMC SERVER INFORMATION"))
        sender.sendMessage(comms.parseLegacy("&8&m-----------------------------"))
        sender.sendMessage(comms.parseLegacy("&eIP: &f$ip"))
        sender.sendMessage(comms.parseLegacy("&ePort: &f$port"))
        sender.sendMessage(comms.parseLegacy("&8&m-----------------------------"))
        return true
    }
}
