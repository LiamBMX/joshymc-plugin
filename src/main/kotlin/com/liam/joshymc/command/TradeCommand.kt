package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TradeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.trade")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /trade <player|accept|deny> [player]", NamedTextColor.RED))
            return true
        }

        when (args[0].lowercase()) {
            "accept" -> {
                plugin.tradeManager.acceptRequest(sender, args.getOrNull(1))
                return true
            }
            "deny" -> {
                plugin.tradeManager.denyRequest(sender, args.getOrNull(1))
                return true
            }
        }

        val target = plugin.server.getPlayer(args[0])
        if (target == null) {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }

        plugin.tradeManager.sendRequest(sender, target)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val prefix = args[0].lowercase()
            val options = plugin.server.onlinePlayers
                .map { it.name }
                .filter { it != (sender as? Player)?.name } + listOf("accept", "deny")
            return options.filter { it.lowercase().startsWith(prefix) }
        }
        if (args.size == 2 && (args[0].equals("accept", ignoreCase = true) || args[0].equals("deny", ignoreCase = true))) {
            val prefix = args[1].lowercase()
            return plugin.server.onlinePlayers
                .map { it.name }
                .filter { it.lowercase().startsWith(prefix) }
                .filter { it != (sender as? Player)?.name }
        }
        return emptyList()
    }
}
