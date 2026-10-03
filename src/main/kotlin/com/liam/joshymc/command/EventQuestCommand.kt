package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * /eventquests (/eq) — opens the Event Quests GUI for everyone.
 *
 * Admin (joshymc.eventquests.admin, also usable from console):
 *   /eq admin reload | progress <player> <event> | reset <player> <event>
 * Reset needs the same command repeated within 30s to confirm.
 */
class EventQuestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubs = listOf("reload", "progress", "reset")

    private fun msg(sender: CommandSender, text: String, color: NamedTextColor) {
        val c = Component.text(text, color)
        if (sender is Player) plugin.commsManager.send(sender, c) else sender.sendMessage(c)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("admin", true)) {
            if (!sender.hasPermission("joshymc.eventquests.admin")) {
                msg(sender, "No permission.", NamedTextColor.RED)
                return true
            }
            handleAdmin(sender, args)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.eventquests")) {
            msg(sender, "No permission.", NamedTextColor.RED)
            return true
        }
        plugin.eventQuestManager.openGui(sender)
        return true
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>) {
        val mgr = plugin.eventQuestManager
        when (args.getOrNull(1)?.lowercase()) {
            "reload" -> {
                val count = mgr.reload()
                msg(sender, "Reloaded event-quests.yml ($count event(s) loaded). Player progress was not changed.", NamedTextColor.GREEN)
            }
            "progress", "reset" -> {
                val sub = args[1].lowercase()
                if (args.size < 4) {
                    msg(sender, "Usage: /eq admin $sub <player> <event>", NamedTextColor.RED)
                    return
                }
                val target = Bukkit.getOfflinePlayer(args[2])
                if (!target.isOnline && !target.hasPlayedBefore()) {
                    msg(sender, "Unknown player '${args[2]}'.", NamedTextColor.RED)
                    return
                }
                val event = mgr.getEvent(args[3])
                if (event == null) {
                    msg(sender, "Unknown event '${args[3]}'.", NamedTextColor.RED)
                    return
                }
                val name = target.name ?: args[2]
                if (sub == "progress") {
                    msg(sender, "--- ${event.name}: $name ---", NamedTextColor.GOLD)
                    mgr.describeProgress(target.uniqueId, event).forEach { msg(sender, "  $it", NamedTextColor.GRAY) }
                } else if (mgr.requestReset(sender.name, target.uniqueId, event)) {
                    msg(sender, "Reset '${event.id}' progress and reward claim for $name.", NamedTextColor.GREEN)
                } else {
                    msg(sender, "This wipes $name's progress AND reward claim for '${event.id}'. Run the same command again within 30s to confirm.", NamedTextColor.YELLOW)
                }
            }
            else -> msg(sender, "Usage: /eq admin <reload|progress|reset>", NamedTextColor.RED)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.eventquests.admin")) return emptyList()
        val playerSub = args.size >= 2 && args[1].lowercase() in setOf("progress", "reset")
        return when (args.size) {
            1 -> listOf("admin").filter { it.startsWith(args[0], true) }
            2 -> if (args[0].equals("admin", true)) adminSubs.filter { it.startsWith(args[1], true) } else emptyList()
            3 -> if (playerSub) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) } else emptyList()
            4 -> if (playerSub) plugin.eventQuestManager.eventIds().filter { it.startsWith(args[3], true) } else emptyList()
            else -> emptyList()
        }
    }
}
