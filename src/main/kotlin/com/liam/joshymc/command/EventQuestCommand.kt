package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.EventObjective
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
 *   /eq admin create <set> | additem hand <set> | setquest <set> <type> [target] <amount>
 * builds a 2-10 quest questline in-game (stored in event-quests-custom.yml).
 *   /eq admin repeatable <set> <true|false> [days] | cooldown <set> <days>
 * makes a set repeatable after a real-time cooldown (default 30 days), or one-time again.
 */
class EventQuestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubs = listOf("reload", "progress", "reset", "create", "additem", "setquest", "repeatable", "cooldown")

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
            "create" -> {
                val name = args.getOrNull(2)
                if (name == null) { msg(sender, "Usage: /eq admin create <quest-set-name>", NamedTextColor.RED); return }
                val err = mgr.createQuestline(name)
                if (err != null) msg(sender, err, NamedTextColor.RED)
                else msg(sender, "Created quest set '${name.lowercase()}'. Add 2-10 quests with /eq admin setquest and a reward with /eq admin additem hand.", NamedTextColor.GREEN)
            }
            "additem" -> {
                if (args.size != 4 || !args[2].equals("hand", true)) {
                    msg(sender, "Usage: /eq admin additem hand <quest-set-name>", NamedTextColor.RED)
                    return
                }
                if (sender !is Player) { msg(sender, "Players only.", NamedTextColor.RED); return }
                val err = mgr.setQuestlineReward(args[3], sender.inventory.itemInMainHand.clone())
                if (err != null) msg(sender, err, NamedTextColor.RED)
                else msg(sender, "Saved your held item as the reward for '${args[3].lowercase()}'. ${mgr.questlineStatus(args[3])}", NamedTextColor.GREEN)
            }
            "setquest" -> {
                val usage = "Usage: /eq admin setquest <quest-set-name> <type> [target] <amount>"
                if (args.size !in 5..6) { msg(sender, usage, NamedTextColor.RED); return }
                val type = EventObjective.entries.firstOrNull { it.name.equals(args[3], true) }
                if (type == null) {
                    msg(sender, "Unknown type '${args[3]}'. Types: ${EventObjective.entries.joinToString(", ") { it.name.lowercase() }}", NamedTextColor.RED)
                    return
                }
                val amount = args.last().toIntOrNull()
                if (amount == null) { msg(sender, usage, NamedTextColor.RED); return }
                val target = if (args.size == 6) args[4] else null
                val err = mgr.addQuestlineQuest(args[2], type, target, amount)
                if (err != null) msg(sender, err, NamedTextColor.RED)
                else msg(sender, "Added ${type.name} x$amount${target?.let { " ($it)" } ?: ""} to '${args[2].lowercase()}'. ${mgr.questlineStatus(args[2])}", NamedTextColor.GREEN)
            }
            "repeatable" -> {
                val usage = "Usage: /eq admin repeatable <quest-set-name> <true|false> [cooldown-days]"
                val repeatable = args.getOrNull(3)?.lowercase()?.toBooleanStrictOrNull()
                val days = args.getOrNull(4)?.toIntOrNull()
                if (args.size !in 4..5 || repeatable == null || (args.size == 5 && days == null)) { msg(sender, usage, NamedTextColor.RED); return }
                val err = mgr.setRepeatable(args[2], repeatable, days)
                if (err != null) msg(sender, err, NamedTextColor.RED)
                else msg(sender, "'${args[2].lowercase()}' is now ${mgr.repeatSummary(args[2])}. Player progress was not changed.", NamedTextColor.GREEN)
            }
            "cooldown" -> {
                val days = args.getOrNull(3)?.toIntOrNull()
                if (args.size != 4 || days == null) { msg(sender, "Usage: /eq admin cooldown <quest-set-name> <days>", NamedTextColor.RED); return }
                val err = mgr.setRepeatable(args[2], null, days)
                if (err != null) msg(sender, err, NamedTextColor.RED)
                else msg(sender, "'${args[2].lowercase()}' cooldown set to $days day(s); it is ${mgr.repeatSummary(args[2])}.", NamedTextColor.GREEN)
            }
            else -> msg(sender, "Usage: /eq admin <reload|progress|reset|create|additem|setquest|repeatable|cooldown>", NamedTextColor.RED)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.eventquests.admin")) return emptyList()
        val isAdmin = args.isNotEmpty() && args[0].equals("admin", true)
        val sub = args.getOrNull(1)?.lowercase()
        val playerSub = sub == "progress" || sub == "reset"
        val mgr = plugin.eventQuestManager
        val last = args.last()
        val options: List<String> = when {
            args.size == 1 -> listOf("admin")
            !isAdmin -> emptyList()
            args.size == 2 -> adminSubs
            playerSub && args.size == 3 -> Bukkit.getOnlinePlayers().map { it.name }
            playerSub && args.size == 4 -> mgr.eventIds()
            sub == "additem" && args.size == 3 -> listOf("hand")
            sub == "additem" && args.size == 4 -> mgr.customQuestlineIds()
            sub == "setquest" && args.size == 3 -> mgr.customQuestlineIds()
            sub == "setquest" && args.size == 4 -> EventObjective.entries.map { it.name.lowercase() }
            sub == "setquest" && args.size == 5 -> {
                val type = EventObjective.entries.firstOrNull { it.name.equals(args[3], true) }
                if (type == null) emptyList() else mgr.targetSuggestions(type).map { it.lowercase() } + "<amount>"
            }
            sub == "setquest" && args.size == 6 -> listOf("<amount>")
            (sub == "repeatable" || sub == "cooldown") && args.size == 3 -> (mgr.eventIds() + mgr.customQuestlineIds()).distinct()
            sub == "repeatable" && args.size == 4 -> listOf("true", "false")
            sub == "repeatable" && args.size == 5 -> listOf("30")
            sub == "cooldown" && args.size == 4 -> listOf("30")
            else -> emptyList()
        }
        return options.filter { it.startsWith(last, true) }.take(50)
    }
}
