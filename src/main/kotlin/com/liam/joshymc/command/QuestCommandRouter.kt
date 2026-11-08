package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.quests.LifestealQuestCommand
import com.liam.joshymc.util.Msg
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/quests` on every gamemode (issue #1425), the same pattern as [BountyCommandRouter]. Survival
 * and Lifesteal each have their own quest system behind the same command, so the command is
 * NETWORK-scoped and this router picks the system by where the sender is:
 *
 *  - In Lifesteal on a server that hosts Lifesteal: the Lifesteal quests ([LifestealQuestCommand],
 *    `joshymc.lifesteal.quests.use`). It never falls back to the Survival quests, even while the
 *    Lifesteal quests are paused, so progress is never shared between the gamemodes.
 *  - Everywhere else: the unchanged Survival [QuestCycleCommand], behind exactly the checks it had
 *    before: the SURVIVAL scope (with the gamemode gate's message) and `joshymc.quests`.
 */
class QuestCommandRouter(
    private val plugin: Joshymc,
    val survival: QuestCycleCommand,
    val lifesteal: LifestealQuestCommand,
) : CommandExecutor, TabCompleter {

    private fun routesToLifesteal(sender: CommandSender): Boolean =
        plugin.lifestealQuestManager.hosted && plugin.gamemodeLockManager.gamemodeOf(sender) == ServerGamemode.LIFESTEAL

    /** Null when the Survival quest command may run for [sender]; otherwise why not. */
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
        /** The Survival quest permission, unchanged. */
        const val SURVIVAL_PERMISSION = "joshymc.quests"
        /** Bukkit's own check on `/quests`: either system's basic permission (`;` = any of them). */
        const val COMMAND_PERMISSION = "joshymc.quests;joshymc.lifesteal.quests.use;joshymc.lifesteal.quests.admin"
    }
}
