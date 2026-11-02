package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.teams.LifestealTeamCommand
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/team` (aliases `/t`, `/teams`) on every gamemode (issue #1346). Survival and Lifesteal both
 * have a team system with the same command, so the command itself is NETWORK-scoped and this
 * router picks the system by where the sender is (`gamemodeLockManager.gamemodeOf(sender)`):
 *
 *  - In Lifesteal (a Lifesteal world, or the console of a Lifesteal server) on a server that
 *    hosts Lifesteal: the Lifesteal team system ([LifestealTeamCommand]). It never falls back to
 *    Survival teams, even while Lifesteal teams are paused.
 *  - Everywhere else: the unchanged Survival [TeamCommand], behind exactly the checks it had
 *    before: the SURVIVAL scope (refused outside Survival while Survival is locked, with the
 *    same message as the gamemode gate) and the `joshymc.team` permission.
 */
class TeamCommandRouter(
    private val plugin: Joshymc,
    val survival: TeamCommand,
    val lifesteal: LifestealTeamCommand,
) : CommandExecutor, TabCompleter {

    private fun routesToLifesteal(sender: CommandSender): Boolean =
        plugin.lifestealTeamManager.hosted && plugin.gamemodeLockManager.gamemodeOf(sender) == ServerGamemode.LIFESTEAL

    /** Null when the Survival team command may run for [sender]; otherwise why not. */
    private fun survivalRefusal(sender: CommandSender): String? = when {
        !plugin.gamemodeLockManager.isAllowed(GamemodeScope.SURVIVAL, sender) -> "That command is only available on ${ServerGamemode.SURVIVAL.displayName}."
        !sender.hasPermission(SURVIVAL_PERMISSION) -> Msg.NO_PERMISSION
        else -> null
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (routesToLifesteal(sender)) return lifesteal.onCommand(sender, command, label, args)
        survivalRefusal(sender)?.let {
            Msg.error(sender, it)
            return true
        }
        return survival.onCommand(sender, command, label, args)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String>? {
        if (routesToLifesteal(sender)) return lifesteal.onTabComplete(sender, command, label, args)
        if (survivalRefusal(sender) != null) return emptyList()
        return survival.onTabComplete(sender, command, label, args)
    }

    companion object {
        /** The Survival team permission, unchanged. */
        const val SURVIVAL_PERMISSION = "joshymc.team"
        /** Bukkit's own check on `/team`: either system's basic permission (`;` = any of them). */
        const val COMMAND_PERMISSION = "joshymc.team;joshymc.lifesteal.team.use"
    }
}
