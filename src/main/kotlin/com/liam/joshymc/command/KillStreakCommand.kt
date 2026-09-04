package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class KillStreakCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubcommands = listOf("set", "reset", "sethighest")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.killstreak")) {
            reply(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (!plugin.killStreakManager.enabled) {
            reply(sender, Component.text("Kill streaks are currently disabled.", NamedTextColor.RED))
            return true
        }

        if (args.isNotEmpty() && args[0].lowercase() in adminSubcommands) {
            return handleAdmin(sender, args)
        }

        val target = if (args.isNotEmpty()) {
            resolveTarget(args[0]) ?: run {
                reply(sender, Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
        } else {
            if (sender !is Player) {
                sender.sendMessage(Component.text("Usage: /killstreak <player>", NamedTextColor.RED))
                return true
            }
            sender.name to sender.uniqueId
        }

        showStreak(sender, target.first, target.second)
        return true
    }

    private fun resolveTarget(name: String): Pair<String, UUID>? {
        val online = Bukkit.getPlayerExact(name)
        if (online != null) return online.name to online.uniqueId
        val cached = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return (cached.name ?: name) to cached.uniqueId
    }

    private fun showStreak(sender: CommandSender, targetName: String, targetUuid: UUID) {
        val manager = plugin.killStreakManager
        val current = manager.getCurrentStreak(targetUuid)
        val highest = manager.getHighestStreak(targetUuid)
        val next = manager.nextMilestone(current)

        val lines = mutableListOf<Component>()
        lines.add(Component.text("$targetName's Kill Streak", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
        lines.add(Component.text("Current: ", NamedTextColor.GRAY).append(Component.text(current, NamedTextColor.WHITE)))
        lines.add(Component.text("Highest: ", NamedTextColor.GRAY).append(Component.text(highest, NamedTextColor.WHITE)))
        lines.add(
            if (next != null) {
                Component.text("Next Milestone: ", NamedTextColor.GRAY)
                    .append(Component.text(next, NamedTextColor.WHITE))
                    .append(Component.text(" (${next - current} kills remaining)", NamedTextColor.DARK_GRAY))
            } else {
                Component.text("Next Milestone: None — all milestones reached!", NamedTextColor.DARK_GRAY)
            }
        )

        if (manager.isBountyActive(targetUuid)) {
            val bounty = manager.currentBountyAmount(targetUuid)
            lines.add(Component.text("BOUNTY ACTIVE", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            lines.add(
                Component.text("Current Bounty: ", NamedTextColor.GRAY)
                    .append(Component.text("$${plugin.economyManager.formatShort(bounty)}", NamedTextColor.GREEN))
            )
        }

        lines.forEach { reply(sender, it) }
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.killstreak.admin")) {
            reply(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args[0].lowercase()
        if (args.size < 2) {
            val usage = if (sub == "reset") "/killstreak reset <player>" else "/killstreak $sub <player> <amount>"
            reply(sender, Component.text("Usage: $usage", NamedTextColor.RED))
            return true
        }

        val target = resolveTarget(args[1])
        if (target == null) {
            reply(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        val (targetName, targetUuid) = target

        when (sub) {
            "reset" -> {
                plugin.killStreakManager.resetStreak(targetUuid)
                reply(sender, Component.text("Reset $targetName's current Kill Streak.", NamedTextColor.GREEN))
            }
            "set" -> {
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (amount == null || amount < 0) {
                    reply(sender, Component.text("Usage: /killstreak set <player> <amount>", NamedTextColor.RED))
                    return true
                }
                plugin.killStreakManager.setCurrentStreak(targetUuid, amount)
                reply(sender, Component.text("Set $targetName's current Kill Streak to $amount.", NamedTextColor.GREEN))
            }
            "sethighest" -> {
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (amount == null || amount < 0) {
                    reply(sender, Component.text("Usage: /killstreak sethighest <player> <amount>", NamedTextColor.RED))
                    return true
                }
                plugin.killStreakManager.setHighestStreak(targetUuid, amount)
                reply(sender, Component.text("Set $targetName's highest Kill Streak to $amount.", NamedTextColor.GREEN))
            }
        }
        return true
    }

    private fun reply(sender: CommandSender, message: Component) {
        if (sender is Player) {
            plugin.commsManager.send(sender, message, CommunicationsManager.Category.COMBAT)
        } else {
            sender.sendMessage(message)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val options = if (sender.hasPermission("joshymc.killstreak.admin")) {
                Bukkit.getOnlinePlayers().map { it.name } + adminSubcommands
            } else {
                Bukkit.getOnlinePlayers().map { it.name }
            }
            return options.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() in adminSubcommands) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }
}
