package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/withdrawheart` (issue #1344, Lifesteal only, scope LIFESTEAL): turns one permanent heart into
 * one Heart item. Every check and the transaction are in [LifestealHeartManager.withdraw]; on a
 * server or world without Lifesteal it only says so.
 */
class WithdrawHeartCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission(PERMISSION)) {
            Msg.noPermission(sender)
            return true
        }
        if (args.isNotEmpty()) {
            Msg.usage(sender, "/withdrawheart")
            return true
        }
        plugin.lifestealHeartManager.withdraw(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()

    companion object {
        const val PERMISSION = "joshymc.lifesteal.withdraw"
    }
}
