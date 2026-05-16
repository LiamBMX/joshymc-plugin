package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ResurgeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.resurge")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase()

        when (sub) {
            "help" -> showHelp(sender)
            "balance", "bal" -> showBalance(sender)
            "top", "leaderboard", "lb" -> showLeaderboard(sender)
            "confirm" -> doResurge(sender)
            else -> showStatus(sender)
        }
        return true
    }

    private fun showHelp(player: Player) {
        plugin.commsManager.send(player, Component.text("─── What is Resurge? ───", TextColor.color(0xFFAA00)))
        plugin.commsManager.send(player, Component.text("  Resurge is a prestige system. Once you meet", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("  the requirements, you can reset your skills &", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("  quests in exchange for rewards and a harder", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("  challenge on your next playthrough.", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text(" ", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("─── Requirements ───", TextColor.color(0xFFAA00)))
        plugin.commsManager.send(player, Component.text("  • All 9 skills at the required level", NamedTextColor.YELLOW)
            .append(Component.text(" (level × 5 per Resurge)", NamedTextColor.GRAY)))
        plugin.commsManager.send(player, Component.text("    Skills: Mining, Farming, Combat, Fishing,", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("    Woodcutting, Excavation, Enchanting,", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("    Alchemy, Taming", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("  • Complete all Mining, Fishing & Farming quests", NamedTextColor.YELLOW))
        plugin.commsManager.send(player, Component.text(" ", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("─── Rewards ───", TextColor.color(0xFFAA00)))
        plugin.commsManager.send(player, Component.text("  • $10,000,000 cash", NamedTextColor.GREEN))
        plugin.commsManager.send(player, Component.text("  • 1 Resurge Key ", NamedTextColor.GREEN)
            .append(Component.text("(2 keys on milestones: 5, 10, 15…)", NamedTextColor.GRAY)))
        plugin.commsManager.send(player, Component.text("  • Quest difficulty +20% per Resurge", NamedTextColor.RED))
        plugin.commsManager.send(player, Component.text(" ", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("─── Commands ───", TextColor.color(0xFFAA00)))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  §e/resurge §7— check your status & requirements"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  §e/resurge confirm §7— Resurge when ready"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  §e/resurge top §7— view the leaderboard"))
    }

    private fun showBalance(player: Player) {
        val count = plugin.resurgeManager.getCount(player.uniqueId)
        plugin.commsManager.send(
            player,
            Component.text("Your Resurge level: ", NamedTextColor.GRAY)
                .append(Component.text(count.toString(), TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.BOLD, true))
        )
    }

    private fun showLeaderboard(player: Player) {
        val top = plugin.resurgeManager.getTopPlayers(10)
        plugin.commsManager.send(player, Component.text("─── Resurge Leaderboard ───", TextColor.color(0xFFAA00)))
        if (top.isEmpty()) {
            plugin.commsManager.send(player, Component.text("  No players have resurged yet.", NamedTextColor.GRAY))
            return
        }
        top.forEachIndexed { i, (name, count) ->
            val medal = when (i) {
                0 -> "§6#1 "
                1 -> "§7#2 "
                2 -> "§c#3 "
                else -> "§8#${i + 1} "
            }
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  ${medal}§f$name §7— §6$count Resurges"))
        }
    }

    private fun showStatus(player: Player) {
        val uuid = player.uniqueId
        val count = plugin.resurgeManager.getCount(uuid)
        val nextLevel = count + 1
        val requiredSkillLevel = plugin.resurgeManager.getRequiredSkillLevel(uuid)
        val multiplierPct = ((plugin.resurgeManager.getMultiplier(uuid) - 1.0) * 100).toInt()

        plugin.commsManager.send(player, Component.text("─── Resurge ───", TextColor.color(0xFFAA00)))
        plugin.commsManager.send(player, Component.text("  Current Resurge: ", NamedTextColor.GRAY)
            .append(Component.text(count.toString(), TextColor.color(0xFFAA00))))
        plugin.commsManager.send(player, Component.text("  Next Resurge: ", NamedTextColor.GRAY)
            .append(Component.text(nextLevel.toString(), NamedTextColor.YELLOW)))
        if (multiplierPct > 0) {
            plugin.commsManager.send(player, Component.text("  Quest difficulty: ", NamedTextColor.GRAY)
                .append(Component.text("+${multiplierPct}% harder", NamedTextColor.RED)))
        }
        plugin.commsManager.send(player, Component.text("  Required skill level: ", NamedTextColor.GRAY)
            .append(Component.text(requiredSkillLevel.toString(), NamedTextColor.AQUA)))

        val missing = plugin.resurgeManager.getMissingRequirements(uuid)
        if (missing.isEmpty()) {
            plugin.commsManager.send(player, Component.text("  ✔ All requirements met!", NamedTextColor.GREEN))
            plugin.commsManager.send(player, Component.text("  Type /resurge confirm to Resurge!", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true))
        } else {
            plugin.commsManager.send(player, Component.text("  Remaining requirements:", NamedTextColor.RED))
            missing.forEach { req ->
                plugin.commsManager.send(player, Component.text("    ✗ $req", NamedTextColor.RED))
            }
        }
    }

    private fun doResurge(player: Player) {
        val success = plugin.resurgeManager.resurge(player)
        if (!success) {
            plugin.commsManager.send(player, Component.text("You don't meet the requirements to Resurge yet. Check /resurge for details.", NamedTextColor.RED))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("help", "balance", "top", "confirm").filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
