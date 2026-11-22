package com.liam.joshymc.lifesteal.hearts

import com.liam.joshymc.Joshymc
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/hearts give <player> <amount>` (issue #1606, Lifesteal only, scope LIFESTEAL, staff only via
 * `joshymc.lifesteal.hearts.give`): gives physical, stackable Heart items. It never changes the
 * target's permanent hearts or max health (that is `/lifesteal hearts set|add|remove`). Only
 * registered where Lifesteal is hosted, so another plugin's `/hearts` keeps working elsewhere.
 * Every check, the transaction and the audit are in [LifestealHeartsCommand.giveStackable].
 */
class HeartsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val hearts = LifestealHeartsCommand(plugin)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        hearts.giveStackable(sender, args.toList())
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        hearts.completeStackable(sender, args.toList())
}
