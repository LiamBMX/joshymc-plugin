package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bounty.LifestealBountyCommand
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/bounty` on every gamemode (issue #1401), the same pattern as [TeamCommandRouter]. Survival
 * and Lifesteal each have a bounty system behind the same command, so the command is
 * NETWORK-scoped and this router picks the system by where the sender is:
 *
 *  - In Lifesteal on a server that hosts Lifesteal: the Lifesteal bounty system
 *    ([LifestealBountyCommand], `joshymc.lifesteal.bounty.use`). It never falls back to the
 *    Survival bounties, even while Lifesteal bounties are paused.
 *  - Everywhere else: the unchanged Survival [BountyCommand], behind exactly the checks it had
 *    before: the SURVIVAL scope (with the gamemode gate's message) and `joshymc.bounty`.
 */
class BountyCommandRouter(
    private val plugin: Joshymc,
    val survival: BountyCommand,
    val lifesteal: LifestealBountyCommand,
) : CommandExecutor, TabCompleter {

    private fun routesToLifesteal(sender: CommandSender): Boolean =
        plugin.lifestealBountyManager.hosted && plugin.gamemodeLockManager.gamemodeOf(sender) == ServerGamemode.LIFESTEAL

    /** Null when the Survival bounty command may run for [sender]; otherwise why not. */
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
        /** The Survival bounty permission, unchanged. */
        const val SURVIVAL_PERMISSION = "joshymc.bounty"
        /** Bukkit's own check on `/bounty`: either system's basic permission (`;` = any of them). */
        const val COMMAND_PERMISSION = "joshymc.bounty;joshymc.lifesteal.bounty.use;joshymc.lifesteal.bounty.admin"
    }
}
