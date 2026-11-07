package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/ac <message>` (alias `/alliancechat`): Lifesteal alliance chat (issue #1405). Only registered
 * where the server hosts Lifesteal (CommandManager releases it elsewhere, so Survival never has it),
 * LIFESTEAL-scoped in `GamemodeAssignments`. Every rule (Lifesteal world, permission, alliance,
 * mutes, filtering, recipients) lives in [LifestealTeamRelations.allianceChat].
 */
class AllianceChatCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (args.isEmpty()) {
            Msg.usage(sender, "/$label <message>")
            return true
        }
        plugin.lifestealTeamManager.relations.allianceChat(sender, args.joinToString(" "))
        return true
    }

    /** Chat text: never suggests player or team names. */
    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()
}
