package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/quests` for senders in Lifesteal (issue #1425), reached through
 * [com.liam.joshymc.command.QuestCommandRouter]:
 *  - `/quests`: the quest menu (`joshymc.lifesteal.quests.use`, default true).
 *  - `/quests admin status | view <player>` (`joshymc.lifesteal.quests.admin`, op): read-only.
 */
class LifestealQuestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.lifestealQuestManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.firstOrNull()?.equals("admin", ignoreCase = true) == true) {
            if (!sender.hasPermission(ADMIN)) return Msg.noPermission(sender).let { true }
            admin(sender, args.drop(1))
            return true
        }
        val player = sender as? Player ?: return Msg.playersOnly(sender).let { true }
        if (!player.hasPermission(USE)) return Msg.noPermission(player).let { true }
        manager.menu.openMain(player)
        return true
    }

    private fun admin(sender: CommandSender, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "status" -> {
                val s = manager.settings
                info(sender,"Lifesteal quests: ${when {
                    !manager.hosted -> "not hosted here"
                    !manager.economyIsLifesteal -> "off (this server's balances aren't Lifesteal's)"
                    s == null -> "paused (invalid ${LifestealQuestManager.FILE_NAME})"
                    !s.enabled -> "disabled"
                    else -> "running"
                }}.")
                if (s != null) {
                    val now = System.currentTimeMillis()
                    for (period in QuestPeriod.entries) {
                        val cycle = QuestCycles.current(period, now, s)
                        info(sender,"${period.label}: ${s.pool(period).count { it.enabled }} enabled quests, cycle ${cycle.id}, resets in ${QuestCycles.formatRemaining(cycle.remainingMillis(now))}.")
                    }
                }
                val problems = manager.problems
                if (problems.isNotEmpty()) info(sender,"${problems.size} config notice(s) (${problems.count { it.severe }} severe); see the console.")
            }
            "view" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/quests admin view <player>").let { }
                val target = Bukkit.getPlayerExact(name) ?: return Msg.playerNotFound(sender, name).let { }
                val book = manager.book(target) ?: return Msg.error(sender, "${target.name}'s quests aren't loaded (not in Lifesteal, or still loading).").let { }
                for ((period, board) in book.boards) {
                    info(sender,"${period.label} (${board.cycle.id}), rerolls used: ${board.usage.free} free / ${board.usage.paid} paid")
                    for (live in board.quests.sortedBy { it.row.slot }) {
                        val scale = live.row.objective.scale
                        info(sender," #${live.id} ${live.row.questId}: ${QuestProgress.displayed(live.progress, scale)}/${QuestProgress.displayed(live.row.target, scale)} ${live.state}")
                    }
                }
            }
            else -> Msg.usage(sender, "/quests admin <status|view <player>>")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission(ADMIN)) return emptyList()
        return when (args.size) {
            1 -> listOf("admin").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("admin", true)) listOf("status", "view").filter { it.startsWith(args[1], ignoreCase = true) } else emptyList()
            3 -> if (args[1].equals("view", true)) Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) } else emptyList()
            else -> emptyList()
        }
    }

    private fun info(sender: CommandSender, text: String) = Msg.reply(sender, Component.text(text, NamedTextColor.GRAY))

    companion object {
        const val USE = "joshymc.lifesteal.quests.use"
        const val ADMIN = "joshymc.lifesteal.quests.admin"
    }
}
