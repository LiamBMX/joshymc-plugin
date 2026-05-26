package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.BoosterManager
import com.liam.joshymc.manager.PunishmentManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class BoosterCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.booster")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase()

        if (sub == "list" || sub == null) {
            showActiveBoosters(sender)
            return true
        }

        val type = when (sub) {
            "sellmulti" -> BoosterManager.BoosterType.SELL
            "skillmulti" -> BoosterManager.BoosterType.SKILL
            "questmulti" -> BoosterManager.BoosterType.QUEST
            else -> {
                sendUsage(sender)
                return true
            }
        }

        if (type == BoosterManager.BoosterType.SELL) {
            // /booster sellmulti <mult> <category> <time>
            if (args.size < 4) { sendUsage(sender); return true }
            val multiplier = args[1].toDoubleOrNull()
            if (multiplier == null || multiplier <= 0) {
                sender.sendMessage(Component.text("Multiplier must be a positive number.", NamedTextColor.RED))
                return true
            }
            val categoryArg = args[2].lowercase()
            val sellCategory = when (categoryArg) {
                "crops" -> BoosterManager.SellCategory.CROPS
                "ores" -> BoosterManager.SellCategory.ORES
                "animal" -> BoosterManager.SellCategory.ANIMAL
                "mobs" -> BoosterManager.SellCategory.MOBS
                "random" -> BoosterManager.SellCategory.entries.random()
                else -> {
                    sender.sendMessage(Component.text("Invalid category. Choose: crops, ores, animal, mobs, random", NamedTextColor.RED))
                    return true
                }
            }
            val durationMs = PunishmentManager.parseDuration(args[3])
            if (durationMs == null || durationMs <= 0) {
                sender.sendMessage(Component.text("Invalid duration. Examples: 30m, 1h, 2h30m", NamedTextColor.RED))
                return true
            }
            plugin.boosterManager.activate(type, multiplier, durationMs, sellCategory)
            sender.sendMessage(
                Component.text("Sell booster (${sellCategory.displayName}) ${multiplier}x activated for ${plugin.boosterManager.formatDuration(durationMs)}!", NamedTextColor.GREEN)
            )
        } else {
            // /booster skillmulti <mult> <time>  OR  /booster questmulti <mult> <time>
            if (args.size < 3) { sendUsage(sender); return true }
            val multiplier = args[1].toDoubleOrNull()
            if (multiplier == null || multiplier <= 0) {
                sender.sendMessage(Component.text("Multiplier must be a positive number.", NamedTextColor.RED))
                return true
            }
            val durationMs = PunishmentManager.parseDuration(args[2])
            if (durationMs == null || durationMs <= 0) {
                sender.sendMessage(Component.text("Invalid duration. Examples: 30m, 1h, 2h30m", NamedTextColor.RED))
                return true
            }
            plugin.boosterManager.activate(type, multiplier, durationMs)
            sender.sendMessage(
                Component.text("${type.displayName} booster ${multiplier}x activated for ${plugin.boosterManager.formatDuration(durationMs)}!", NamedTextColor.GREEN)
            )
        }

        return true
    }

    private fun showActiveBoosters(sender: CommandSender) {
        val active = plugin.boosterManager.getActiveBoosters()
        if (active.isEmpty()) {
            sender.sendMessage(Component.text("No active boosters.", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(
            Component.text("Active Boosters:", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
        )
        for (b in active) {
            val catText = if (b.sellCategory != null) " (${b.sellCategory.displayName})" else ""
            val remaining = plugin.boosterManager.formatDuration(b.remainingMs())
            sender.sendMessage(
                Component.text("  ").append(
                    Component.text("${b.type.displayName}$catText", NamedTextColor.YELLOW)
                ).append(
                    Component.text(" ${b.multiplier}x", NamedTextColor.GOLD)
                ).append(
                    Component.text(" — $remaining remaining", NamedTextColor.GRAY)
                )
            )
        }
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(
            Component.text("Usage:", NamedTextColor.YELLOW).append(Component.newline())
                .append(Component.text("  /booster sellmulti <mult> <crops|ores|animal|mobs|random> <time>", NamedTextColor.GOLD)).append(Component.newline())
                .append(Component.text("  /booster skillmulti <mult> <time>", NamedTextColor.GOLD)).append(Component.newline())
                .append(Component.text("  /booster questmulti <mult> <time>", NamedTextColor.GOLD)).append(Component.newline())
                .append(Component.text("  /booster list", NamedTextColor.GOLD))
        )
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.booster")) return emptyList()
        return when (args.size) {
            1 -> listOf("sellmulti", "skillmulti", "questmulti", "list").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "sellmulti", "skillmulti", "questmulti" -> listOf("1.5", "2", "3").filter { it.startsWith(args[1]) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "sellmulti" -> listOf("crops", "ores", "animal", "mobs", "random").filter { it.startsWith(args[2], ignoreCase = true) }
                "skillmulti", "questmulti" -> listOf("30m", "1h", "2h", "6h").filter { it.startsWith(args[2], ignoreCase = true) }
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "sellmulti" -> listOf("30m", "1h", "2h", "6h").filter { it.startsWith(args[3], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
