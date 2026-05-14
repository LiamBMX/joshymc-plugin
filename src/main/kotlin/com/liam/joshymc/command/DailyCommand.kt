package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DailyCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.daily")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isNotEmpty() && args[0].equals("claim", ignoreCase = true)) {
            val claimed = plugin.dailyQuestManager.claimAllDailyRewards(sender)
            if (claimed > 0) {
                plugin.commsManager.send(sender, Component.text("Claimed $claimed daily reward${if (claimed != 1) "s" else ""}!", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(sender, Component.text("No daily rewards ready to claim.", NamedTextColor.GRAY))
            }
            return true
        }

        plugin.dailyQuestManager.openDailyGui(sender)
        return true
    }
}
