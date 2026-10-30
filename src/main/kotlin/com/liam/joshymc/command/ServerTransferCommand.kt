package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.network.transfer.TransferDestination
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/lobby` (`/hub`), `/survival`, `/lifesteal` (issue #1331): sends the player to that network
 * server through the proxy. One instance per command; every check lives in the TransferManager.
 */
class ServerTransferCommand(
    private val plugin: Joshymc,
    private val destination: TransferDestination,
) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        plugin.transferManager.request(sender, destination)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()
}
