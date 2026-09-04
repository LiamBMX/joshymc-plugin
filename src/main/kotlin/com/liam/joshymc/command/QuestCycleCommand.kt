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
 * /quests — opens the unified Daily / Weekly / Quest Master GUI.
 * /daily is a plain alias into the same GUI (see DailyCommand).
 *
 * Admin subcommands (joshymc.quests.admin, also usable from console):
 *   /quests forcedaily | forceweekly | rotation | progress <player> |
 *           setprogress <player> <quest_id> <amount> | complete <player> <quest_id> | reload
 */
class QuestCycleCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubs = listOf("forcedaily", "forceweekly", "rotation", "progress", "setprogress", "complete", "reload")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].lowercase() in adminSubs) {
            if (sender is Player && !sender.hasPermission("joshymc.quests.admin")) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                return true
            }
            handleAdmin(sender, args)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.quests")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        plugin.questCycleManager.openGui(sender)
        return true
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>) {
        val mgr = plugin.questCycleManager
        when (args[0].lowercase()) {
            "forcedaily" -> {
                mgr.forceDailyReset()
                sender.sendMessage(Component.text("Forced a Daily quest re-roll.", NamedTextColor.GREEN))
            }
            "forceweekly" -> {
                mgr.forceWeeklyReset()
                sender.sendMessage(Component.text("Forced a Weekly quest re-roll.", NamedTextColor.GREEN))
            }
            "reload" -> {
                mgr.reloadDefinitions()
                sender.sendMessage(Component.text("Reloaded quest-cycle.yml and config settings.", NamedTextColor.GREEN))
            }
            "rotation" -> {
                sender.sendMessage(Component.text("--- Daily Rotation (${mgr.dailyCycleId()}) ---", NamedTextColor.GOLD))
                for (q in mgr.getDailyPool()) sender.sendMessage(Component.text("  ${q.id}: ${q.name} (${q.type})", NamedTextColor.GRAY))
                sender.sendMessage(Component.text("--- Weekly Rotation (${mgr.weeklyCycleId()}) ---", NamedTextColor.AQUA))
                for (q in mgr.getWeeklyPool()) sender.sendMessage(Component.text("  ${q.id}: ${q.name}", NamedTextColor.GRAY))
            }
            "progress" -> {
                if (args.size < 2) { sender.sendMessage(Component.text("Usage: /quests progress <player>", NamedTextColor.RED)); return }
                val target = Bukkit.getOfflinePlayer(args[1])
                val uuid = target.uniqueId
                sender.sendMessage(Component.text("--- Quest Progress: ${target.name ?: args[1]} ---", NamedTextColor.GOLD))
                for (q in mgr.getDailyPool()) {
                    val p = mgr.getProgress(uuid, q)
                    sender.sendMessage(Component.text("  [Daily] ${q.name}: ${p.progress}/${q.amount} ${if (p.completed) "✔" else ""}", NamedTextColor.GRAY))
                }
                for (q in mgr.getWeeklyPool()) {
                    val p = mgr.getProgress(uuid, q)
                    sender.sendMessage(Component.text("  [Weekly] ${q.name}: ${p.progress}/${q.amount} ${if (p.completed) "✔" else ""}", NamedTextColor.GRAY))
                }
                val qm = mgr.getQuestMasterProgress(uuid, mgr.weeklyCycleId())
                sender.sendMessage(Component.text("  [Quest Master] Daily Sets: ${qm.dailySets}, Weekly Complete: ${qm.weeklyComplete}, Rewarded: ${qm.rewarded}", NamedTextColor.LIGHT_PURPLE))
            }
            "setprogress" -> {
                if (args.size < 4) { sender.sendMessage(Component.text("Usage: /quests setprogress <player> <quest_id> <amount>", NamedTextColor.RED)); return }
                val target = Bukkit.getOfflinePlayer(args[1])
                val amount = args[3].toIntOrNull()
                if (amount == null) { sender.sendMessage(Component.text("Amount must be a number.", NamedTextColor.RED)); return }
                if (mgr.setProgress(target.uniqueId, args[2], amount)) {
                    sender.sendMessage(Component.text("Set progress for ${target.name ?: args[1]} on '${args[2]}' to $amount.", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("Unknown quest id '${args[2]}'.", NamedTextColor.RED))
                }
            }
            "complete" -> {
                if (args.size < 3) { sender.sendMessage(Component.text("Usage: /quests complete <player> <quest_id>", NamedTextColor.RED)); return }
                val target = Bukkit.getPlayer(args[1])
                if (target == null) { sender.sendMessage(Component.text("Player must be online.", NamedTextColor.RED)); return }
                if (mgr.completeQuest(target, args[2])) {
                    sender.sendMessage(Component.text("Completed '${args[2]}' for ${target.name}.", NamedTextColor.GREEN))
                } else {
                    sender.sendMessage(Component.text("Unknown quest id '${args[2]}'.", NamedTextColor.RED))
                }
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val isAdmin = sender !is Player || sender.hasPermission("joshymc.quests.admin")
        if (!isAdmin) return emptyList()

        if (args.size == 1) return adminSubs.filter { it.startsWith(args[0], ignoreCase = true) }

        val sub = args[0].lowercase()
        if (args.size == 2 && sub in setOf("progress", "setprogress", "complete")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && sub in setOf("setprogress", "complete")) {
            return plugin.questCycleManager.allQuestIds().filter { it.startsWith(args[2], ignoreCase = true) }.take(30)
        }
        return emptyList()
    }
}
