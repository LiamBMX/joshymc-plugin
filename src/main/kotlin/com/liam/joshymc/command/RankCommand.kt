package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class RankCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.rank")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "set" -> handleSet(sender, args)
            "remove" -> handleRemove(sender, args)
            "list" -> handleList(sender)
            "check" -> handleCheck(sender, args)
            else -> showHelp(sender)
        }

        return true
    }

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.set")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1)
        val rankId = args.getOrNull(2)?.lowercase()

        if (playerName == null || rankId == null) {
            sender.sendMessage(Component.text("Usage: /rank set <player> <rank>", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        val rank = plugin.rankManager.getRank(rankId)
        if (rank == null) {
            sender.sendMessage(Component.text("Unknown rank: $rankId. Use /rank list to see available ranks.", NamedTextColor.RED))
            return
        }

        plugin.rankManager.setPlayerRank(target.uniqueId, rankId)

        val tagDisplay = plugin.commsManager.parseLegacy(rank.displayTag)
        if (sender is Player) {
            plugin.commsManager.send(
                sender,
                Component.text("Set ", NamedTextColor.GREEN)
                    .append(Component.text(playerName, NamedTextColor.WHITE))
                    .append(Component.text("'s rank to ", NamedTextColor.GREEN))
                    .append(tagDisplay)
            )
        }

        val onlineTarget = Bukkit.getPlayer(playerName)
        if (onlineTarget != null && onlineTarget != sender) {
            plugin.commsManager.send(
                onlineTarget,
                Component.text("Your rank has been set to ", NamedTextColor.GREEN)
                    .append(tagDisplay)
            )
        }
    }

    private fun handleRemove(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.rank.set")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val playerName = args.getOrNull(1)
        if (playerName == null) {
            sender.sendMessage(Component.text("Usage: /rank remove <player>", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getPlayer(playerName) ?: Bukkit.getOfflinePlayer(playerName)
        plugin.rankManager.setPlayerRank(target.uniqueId, null)

        sender.sendMessage(
            Component.text("Removed rank from ", NamedTextColor.GREEN)
                .append(Component.text(playerName, NamedTextColor.WHITE))
        )
    }

    private fun handleList(sender: CommandSender) {
        val ranks = plugin.rankManager.getAllRanks()
        val gold = TextColor.color(0xFFD700)

        val msg = Component.text()
            .append(Component.text("--- Ranks (${ranks.size}) ---", gold).decoration(TextDecoration.BOLD, true))

        for (rank in ranks) {
            val tagDisplay = plugin.commsManager.parseLegacy(rank.displayTag)
            msg.append(Component.newline())
                .append(Component.text("  ${rank.id}", NamedTextColor.GRAY))
                .append(Component.text(" → ", NamedTextColor.DARK_GRAY))
                .append(tagDisplay)
                .append(Component.text(" (weight: ${rank.weight})", NamedTextColor.DARK_GRAY))
        }

        sender.sendMessage(msg.build())
    }

    private fun handleCheck(sender: CommandSender, args: Array<out String>) {
        val playerName = args.getOrNull(1) ?: (sender as? Player)?.name
        if (playerName == null) {
            sender.sendMessage(Component.text("Usage: /rank check <player>", NamedTextColor.RED))
            return
        }

        val target = Bukkit.getPlayer(playerName)
        if (target == null) {
            sender.sendMessage(Component.text("Player not online.", NamedTextColor.RED))
            return
        }

        val rank = plugin.rankManager.getPlayerRank(target)
        if (rank == null) {
            sender.sendMessage(
                Component.text(playerName, NamedTextColor.WHITE)
                    .append(Component.text(" has no rank.", NamedTextColor.GRAY))
            )
        } else {
            val tagDisplay = plugin.commsManager.parseLegacy(rank.displayTag)
            sender.sendMessage(
                Component.text(playerName, NamedTextColor.WHITE)
                    .append(Component.text("'s rank: ", NamedTextColor.GRAY))
                    .append(tagDisplay)
            )
        }
    }

    private fun showHelp(sender: CommandSender) {
        val gold = TextColor.color(0xFFD700)
        val msg = Component.text()
            .append(Component.text("--- Ranks ---", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("/rank set <player> <rank>", NamedTextColor.YELLOW))
            .append(Component.text(" — Assign a rank", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank remove <player>", NamedTextColor.YELLOW))
            .append(Component.text(" — Remove a player's rank", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank list", NamedTextColor.YELLOW))
            .append(Component.text(" — Show all ranks", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/rank check [player]", NamedTextColor.YELLOW))
            .append(Component.text(" — Check a player's rank", NamedTextColor.GRAY))

        sender.sendMessage(msg.build())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("set", "remove", "list", "check").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "set", "remove", "check" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "set" -> plugin.rankManager.getRankIds().filter { it.startsWith(args[2].lowercase()) }.toList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
