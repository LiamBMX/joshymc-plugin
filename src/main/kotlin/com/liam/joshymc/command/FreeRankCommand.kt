package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** `/freerank`: claim the free starter rank of the current gamemode (issue #1295). */
class FreeRankCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission("joshymc.freerank")) {
            Msg.noPermission(sender)
            return true
        }
        plugin.freeRankManager.claim(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()
}
