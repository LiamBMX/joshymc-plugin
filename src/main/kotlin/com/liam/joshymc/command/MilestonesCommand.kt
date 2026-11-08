package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.milestone.MilestoneCategory
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** `/milestones [category]` (issue #1420, Survival only): opens the Milestones menu. */
class MilestonesCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission("joshymc.milestones.use")) {
            Msg.noPermission(sender)
            return true
        }
        if (plugin.gamemodeLockManager.gamemodeOf(sender) != ServerGamemode.SURVIVAL) {
            Msg.error(sender, "Milestones are only available in Survival.")
            return true
        }
        val manager = plugin.milestoneManager
        if (!manager.running) {
            Msg.error(sender, "Milestones are disabled right now.")
            return true
        }
        val category = args.firstOrNull()?.let { MilestoneCategory.fromId(it) }
        if (args.isNotEmpty() && category == null) {
            Msg.usage(sender, "/milestones [${MilestoneCategory.entries.joinToString("|") { it.id }}]")
            return true
        }
        if (category != null) manager.menus.openCategory(sender, category, 0) else manager.menus.openMain(sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        if (args.size == 1) MilestoneCategory.entries.map { it.id }.filter { it.startsWith(args[0].lowercase()) } else emptyList()
}
