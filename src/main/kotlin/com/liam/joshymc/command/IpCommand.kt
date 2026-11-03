package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.ServerConnectionInfo
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class IpCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val info = ServerConnectionInfo.from(plugin.config)
        val comms = plugin.commsManager

        sender.sendMessage(comms.parseLegacy("&6&lJOSHYMC SERVER INFORMATION"))
        sender.sendMessage(comms.parseLegacy("&8&m-----------------------------"))
        sender.sendMessage(comms.parseLegacy("&eIP: &f${info.ip}"))
        sender.sendMessage(comms.parseLegacy("&eJava Port: &f${info.javaPort}"))
        sender.sendMessage(comms.parseLegacy("&eBedrock Port: &f${info.bedrockPort}"))
        sender.sendMessage(comms.parseLegacy("&8&m-----------------------------"))
        return true
    }
}
