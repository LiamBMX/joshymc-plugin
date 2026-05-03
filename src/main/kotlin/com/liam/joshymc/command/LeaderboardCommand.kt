package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.LeaderboardManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/leaderboard create <id> <type> [count]` — drop a leaderboard hologram
 *   at your feet. Types: money, kills, deaths, playtime, quests.
 * `/leaderboard delete <id>` — remove the hologram + entry.
 * `/leaderboard list` — list registered leaderboards.
 * `/leaderboard refresh [id]` — force a refresh now (defaults to all).
 */
class LeaderboardCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.leaderboard")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "create" -> handleCreate(sender, args)
            "delete", "remove" -> handleDelete(sender, args)
            "list" -> handleList(sender)
            "refresh" -> handleRefresh(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("/leaderboard create must be run by a player.", NamedTextColor.RED))
            return
        }
        val id = args.getOrNull(1)
        val typeArg = args.getOrNull(2)
        val count = args.getOrNull(3)?.toIntOrNull() ?: 10
        if (id == null || typeArg == null) {
            sender.sendMessage(Component.text("Usage: /leaderboard create <id> <type> [count]", NamedTextColor.RED))
            return
        }
        val type = LeaderboardManager.Type.entries.firstOrNull { it.name.equals(typeArg, ignoreCase = true) }
        if (type == null) {
            sender.sendMessage(Component.text("Unknown type. Available: ${LeaderboardManager.Type.entries.joinToString(", ") { it.name.lowercase() }}", NamedTextColor.RED))
            return
        }
        val ok = plugin.leaderboardManager.create(id, type, player.location, count.coerceIn(1, 25))
        if (!ok) {
            sender.sendMessage(Component.text("Leaderboard with id '$id' already exists.", NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text("Leaderboard '$id' (${type.displayName}) created.", NamedTextColor.GREEN))
        }
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val id = args.getOrNull(1) ?: run {
            sender.sendMessage(Component.text("Usage: /leaderboard delete <id>", NamedTextColor.RED))
            return
        }
        val ok = plugin.leaderboardManager.delete(id)
        if (!ok) {
            sender.sendMessage(Component.text("No leaderboard with that id.", NamedTextColor.RED))
        } else {
            sender.sendMessage(Component.text("Leaderboard '$id' deleted.", NamedTextColor.GREEN))
        }
    }

    private fun handleList(sender: CommandSender) {
        val list = plugin.leaderboardManager.list()
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("No leaderboards configured.", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(Component.text("Leaderboards (${list.size}):", NamedTextColor.GOLD))
        for (entry in list) {
            sender.sendMessage(
                Component.text("  ${entry.id}", NamedTextColor.YELLOW)
                    .append(Component.text("  ${entry.type.name.lowercase()}", NamedTextColor.GRAY))
                    .append(Component.text("  top ${entry.topN}", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun handleRefresh(sender: CommandSender, args: Array<out String>) {
        plugin.leaderboardManager.refreshAll()
        sender.sendMessage(Component.text("Refreshed all leaderboards.", NamedTextColor.GREEN))
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Leaderboard commands:", NamedTextColor.GOLD))
        sender.sendMessage(Component.text("  /leaderboard create <id> <type> [count]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /leaderboard delete <id>", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /leaderboard list", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /leaderboard refresh", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  Types: money, kills, deaths, playtime, quests", NamedTextColor.DARK_GRAY))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.leaderboard")) return emptyList()
        return when (args.size) {
            1 -> listOf("create", "delete", "list", "refresh").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "delete", "remove" -> plugin.leaderboardManager.list().map { it.id }
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "create" -> LeaderboardManager.Type.entries.map { it.name.lowercase() }
                    .filter { it.startsWith(args[2], ignoreCase = true) }
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "create" -> listOf("5", "10", "15", "20").filter { it.startsWith(args[3]) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
