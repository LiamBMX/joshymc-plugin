package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.hearts.LifestealHeartsCommand
import com.liam.joshymc.network.transfer.TransferDestination
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/lobby` (`/hub`), `/survival`, `/lifesteal` (issue #1331): sends the player to that network
 * server through the proxy. One instance per command; every check lives in the TransferManager.
 *
 * `/lifesteal hearts ...` (issue #1341) is the admin heart command, handled by
 * [LifestealHeartsCommand]; plain `/lifesteal` (and any other arguments) still transfers.
 */
class ServerTransferCommand(
    private val plugin: Joshymc,
    private val destination: TransferDestination,
) : CommandExecutor, TabCompleter {

    private val hearts = if (destination == TransferDestination.LIFESTEAL) LifestealHeartsCommand(plugin) else null

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (hearts != null && args.firstOrNull()?.equals("hearts", ignoreCase = true) == true) {
            hearts.execute(sender, args.drop(1))
            return true
        }
        plugin.transferManager.request(sender, destination)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (hearts == null || args.isEmpty() || !hearts.visibleTo(sender)) return emptyList()
        if (args.size == 1) return if ("hearts".startsWith(args[0].lowercase())) listOf("hearts") else emptyList()
        if (!args[0].equals("hearts", ignoreCase = true)) return emptyList()
        return hearts.complete(sender, args.drop(1))
    }
}
