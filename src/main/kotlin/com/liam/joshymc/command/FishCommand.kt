package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class FishCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.fishingManager.openCollection(sender)
            return true
        }

        when (args[0].lowercase()) {
            "stats" -> plugin.fishingManager.showStats(sender)
            "leaderboard", "lb", "top" -> plugin.fishingManager.showLeaderboard(sender)
            else -> {
                plugin.commsManager.send(sender,
                    Component.text("Usage: /fish [stats|leaderboard]", NamedTextColor.GRAY)
                )
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("stats", "leaderboard").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
