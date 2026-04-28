package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.QuestCategory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class QuestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // The reset subcommand is intentionally console-only (or admin) so it
        // can't be used by players. We handle it here BEFORE the players-only
        // check so the console can run it.
        if (args.isNotEmpty() && args[0].equals("reset", ignoreCase = true)) {
            handleReset(sender, args)
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

        if (args.isEmpty()) {
            plugin.questManager.openCategoryMenu(sender)
            return true
        }

        when (args[0].lowercase()) {
            "list" -> handleList(sender, args)
            "progress" -> handleProgress(sender)
            "info" -> handleInfo(sender, args)
            else -> {
                plugin.commsManager.send(sender, Component.text("Usage: /quests [list|progress|info]", NamedTextColor.RED))
            }
        }

        return true
    }

    private fun handleReset(sender: CommandSender, args: Array<out String>) {
        // Console always allowed; players require admin permission.
        if (sender is Player && !sender.hasPermission("joshymc.quests.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /quests reset <player> [quest_id|all]", NamedTextColor.RED))
            return
        }

        val name = args[1]
        val target = plugin.server.getOfflinePlayer(name)
        if (!target.hasPlayedBefore() && target.player == null) {
            sender.sendMessage(Component.text("Unknown player '$name'.", NamedTextColor.RED))
            return
        }

        val uuid = target.uniqueId
        val scope = args.getOrNull(2)?.lowercase() ?: "all"

        if (scope == "all") {
            val removed = plugin.questManager.resetAllProgress(uuid)
            sender.sendMessage(Component.text("Reset all quest progress for ${target.name ?: name} ($removed entries removed).", NamedTextColor.GREEN))
            target.player?.let { online ->
                plugin.commsManager.send(online, Component.text("Your quest progress has been reset.", NamedTextColor.YELLOW))
            }
        } else {
            val quest = plugin.questManager.getAllQuests().find { it.id.equals(scope, ignoreCase = true) }
            if (quest == null) {
                sender.sendMessage(Component.text("Unknown quest id '$scope'.", NamedTextColor.RED))
                return
            }
            val removed = plugin.questManager.resetQuestProgress(uuid, quest.id)
            if (removed) {
                sender.sendMessage(Component.text("Reset quest '${quest.id}' for ${target.name ?: name}.", NamedTextColor.GREEN))
            } else {
                sender.sendMessage(Component.text("${target.name ?: name} had no progress on '${quest.id}'.", NamedTextColor.YELLOW))
            }
        }
    }

    private fun handleList(player: Player, args: Array<out String>) {
        val quests = plugin.questManager.getAllQuests()
        val uuid = player.uniqueId

        if (args.size < 2) {
            // Show summary of all categories
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&e--- Quest Categories ---"))
            for (category in QuestCategory.entries) {
                val categoryQuests = quests.filter { it.category == category }
                if (categoryQuests.isEmpty()) continue
                val completed = categoryQuests.count { plugin.questManager.isCompleted(uuid, it.id) }
                val total = categoryQuests.size
                plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
                    "  &6${category.displayName}&7: &a$completed&7/&a$total &7completed"
                ))
            }
            return
        }

        val categoryName = args[1].lowercase()
        val category = QuestCategory.entries.find { it.name.lowercase() == categoryName }
        if (category == null) {
            plugin.commsManager.send(player, Component.text("Unknown category '$categoryName'.", NamedTextColor.RED))
            val available = QuestCategory.entries.joinToString(", ") { it.name.lowercase() }
            plugin.commsManager.send(player, Component.text("Available: $available", NamedTextColor.GRAY))
            return
        }

        val categoryQuests = quests.filter { it.category == category }
        if (categoryQuests.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No quests in ${category.displayName}.", NamedTextColor.GRAY))
            return
        }

        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&e--- ${category.displayName} Quests ---"))
        for (quest in categoryQuests) {
            val progress = plugin.questManager.getPlayerProgress(uuid, quest.id)
            val status = when {
                progress.claimedReward -> "&a✔"
                progress.completed -> "&e★"
                else -> "&7○"
            }
            val progressText = "&7(${progress.progress}/${quest.amount})"
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
                "  $status &f${quest.name} $progressText"
            ))
        }
    }

    private fun handleProgress(player: Player) {
        val quests = plugin.questManager.getAllQuests()
        val uuid = player.uniqueId
        val totalCompleted = plugin.questManager.getCompletedCount(uuid)
        val totalQuests = quests.size
        val percent = if (totalQuests > 0) (totalCompleted * 100) / totalQuests else 0

        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&e--- Quest Progress ---"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "  &7Completed: &a$totalCompleted&7/&a$totalQuests &7($percent%)"
        ))

        // Build category lines, 3 per row
        val categories = QuestCategory.entries
        val parts = categories.map { category ->
            val categoryQuests = quests.filter { it.category == category }
            val completed = categoryQuests.count { plugin.questManager.isCompleted(uuid, it.id) }
            val total = categoryQuests.size
            "&6${category.displayName}&7: &a$completed&7/&a$total"
        }

        val lines = parts.chunked(3)
        for (chunk in lines) {
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
                "  " + chunk.joinToString(" &8| ")
            ))
        }
    }

    private fun handleInfo(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /quests info <quest_id>", NamedTextColor.RED))
            return
        }

        val questId = args[1].lowercase()
        val quest = plugin.questManager.getAllQuests().find { it.id == questId }
        if (quest == null) {
            plugin.commsManager.send(player, Component.text("Unknown quest '$questId'.", NamedTextColor.RED))
            return
        }

        val uuid = player.uniqueId
        val progress = plugin.questManager.getPlayerProgress(uuid, quest.id)
        val percent = if (quest.amount > 0) (progress.progress * 100) / quest.amount else 0
        val bar = progressBar(progress.progress, quest.amount)

        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&e--- ${quest.name} ---"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  &7${quest.description}"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "  &7Difficulty: ${quest.difficulty.color}${quest.difficulty.displayName}"
        ))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "  &7Progress: &a${progress.progress}&7/&a${quest.amount} &7($percent%)"
        ))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  $bar"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  &7Rewards: &6${quest.rewards}"))
    }

    private fun progressBar(current: Int, max: Int): String {
        val bars = 10
        val filled = ((current.toDouble() / max) * bars).toInt().coerceIn(0, bars)
        return "&a" + "█".repeat(filled) + "&7" + "░".repeat(bars - filled)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val isAdmin = sender !is Player || sender.hasPermission("joshymc.quests.admin")
        val canBrowse = sender is Player && sender.hasPermission("joshymc.quests")
        if (!isAdmin && !canBrowse) return emptyList()

        if (args.size == 1) {
            val subs = mutableListOf("list", "progress", "info")
            if (isAdmin) subs.add("reset")
            return subs.filter { it.startsWith(args[0], ignoreCase = true) }
        }

        if (args.size == 2) {
            val sub = args[0].lowercase()
            val prefix = args[1].lowercase()

            if (sub == "list") {
                return QuestCategory.entries
                    .map { it.name.lowercase() }
                    .filter { it.startsWith(prefix) }
            }

            if (sub == "info") {
                return plugin.questManager.getAllQuests()
                    .map { it.id }
                    .filter { it.startsWith(prefix) }
                    .take(30)
            }

            if (sub == "reset" && isAdmin) {
                return plugin.server.onlinePlayers
                    .map { it.name }
                    .filter { it.startsWith(args[1], ignoreCase = true) }
            }
        }

        if (args.size == 3 && args[0].equals("reset", ignoreCase = true) && isAdmin) {
            val prefix = args[2].lowercase()
            val ids = plugin.questManager.getAllQuests().map { it.id }
            return (listOf("all") + ids).filter { it.startsWith(prefix) }.take(30)
        }

        return emptyList()
    }
}

class RewardsCommand(private val plugin: Joshymc) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }

        val unclaimed = plugin.questManager.getUnclaimedCount(sender.uniqueId)
        if (unclaimed == 0) {
            plugin.commsManager.send(sender, Component.text("No rewards to claim. Complete quests to earn rewards!", NamedTextColor.GRAY))
            return true
        }

        val claimed = plugin.questManager.claimAllRewards(sender)
        plugin.commsManager.send(sender, Component.text("Claimed $claimed reward${if (claimed != 1) "s" else ""}!", NamedTextColor.GREEN))
        return true
    }
}
