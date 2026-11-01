package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** `/freetag`: claim the free [JoshyMC] Chat Tag Voucher, once per player (issue #1339, Survival only). */
class FreeTagCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission("joshymc.freetag.use")) {
            Msg.noPermission(sender)
            return true
        }
        plugin.freeTagManager.claim(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()
}
