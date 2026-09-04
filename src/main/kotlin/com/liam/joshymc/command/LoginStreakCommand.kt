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

class LoginStreakCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminSubcommands = listOf("set", "reset", "complete", "playtime", "grace", "reload")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.loginstreak")) {
            reply(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (!plugin.loginStreakManager.enabled) {
            reply(sender, Component.text("Login streaks are currently disabled.", NamedTextColor.RED))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase()

        if (sub != null && sub in adminSubcommands) {
            return handleAdmin(sender, args)
        }

        if (sub == "info") {
            if (sender !is Player) {
                sender.sendMessage(Component.text("Usage: /loginstreak info", NamedTextColor.RED))
                return true
            }
            showInfo(sender, sender.name, sender.uniqueId)
            return true
        }

        if (sub != null) {
            val target = resolveTarget(args[0]) ?: run {
                reply(sender, Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
            showInfo(sender, target.first, target.second)
            return true
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Usage: /loginstreak [player|info|set|reset|complete|playtime|grace|reload]", NamedTextColor.RED))
            return true
        }
        plugin.loginStreakManager.openGui(sender)
        return true
    }

    private fun resolveTarget(name: String): Pair<String, UUID>? {
        val online = Bukkit.getPlayerExact(name)
        if (online != null) return online.name to online.uniqueId
        val cached = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return (cached.name ?: name) to cached.uniqueId
    }

    private fun showInfo(sender: CommandSender, targetName: String, targetUuid: UUID) {
        val (current, longest) = plugin.loginStreakManager.getStreakInfo(targetUuid)
        reply(sender, Component.text("$targetName's Login Streak", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
        reply(sender, Component.text("Current: ", NamedTextColor.GRAY).append(Component.text("$current Days", NamedTextColor.WHITE)))
        reply(sender, Component.text("Longest: ", NamedTextColor.GRAY).append(Component.text("$longest Days", NamedTextColor.WHITE)))
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.loginstreak.admin")) {
            reply(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args[0].lowercase()

        if (sub == "reload") {
            plugin.loginStreakManager.reloadSettings()
            reply(sender, Component.text("Login Streak settings reloaded.", NamedTextColor.GREEN))
            return true
        }

        if (args.size < 2) {
            reply(sender, Component.text("Usage: /loginstreak $sub <player>${usageSuffix(sub)}", NamedTextColor.RED))
            return true
        }

        val targetPlayer = Bukkit.getPlayerExact(args[1])
        val targetOffline = resolveTarget(args[1])
        if (targetOffline == null) {
            reply(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }
        val (targetName, targetUuid) = targetOffline

        when (sub) {
            "set" -> {
                val amount = args.getOrNull(2)?.toIntOrNull()
                if (amount == null || amount < 0) {
                    reply(sender, Component.text("Usage: /loginstreak set <player> <days>", NamedTextColor.RED))
                    return true
                }
                plugin.loginStreakManager.setCurrentStreak(targetUuid, amount)
                reply(sender, Component.text("Set $targetName's current Login Streak to $amount.", NamedTextColor.GREEN))
            }
            "reset" -> {
                plugin.loginStreakManager.resetCurrentStreak(targetUuid)
                reply(sender, Component.text("Reset $targetName's current Login Streak.", NamedTextColor.GREEN))
            }
            "complete" -> {
                if (targetPlayer == null) {
                    reply(sender, Component.text("$targetName must be online to force-complete today's login.", NamedTextColor.RED))
                    return true
                }
                val done = plugin.loginStreakManager.forceCompleteToday(targetPlayer)
                reply(
                    sender,
                    if (done) Component.text("Completed $targetName's Login for today.", NamedTextColor.GREEN)
                    else Component.text("$targetName has already completed today's Login.", NamedTextColor.YELLOW)
                )
            }
            "playtime" -> {
                if (targetPlayer == null) {
                    reply(sender, Component.text("$targetName must be online to set today's playtime.", NamedTextColor.RED))
                    return true
                }
                val minutes = args.getOrNull(2)?.toIntOrNull()
                if (minutes == null || minutes < 0) {
                    reply(sender, Component.text("Usage: /loginstreak playtime <player> <minutes>", NamedTextColor.RED))
                    return true
                }
                plugin.loginStreakManager.setTodayPlaytimeMinutes(targetPlayer, minutes)
                reply(sender, Component.text("Set $targetName's today playtime to ${minutes}m.", NamedTextColor.GREEN))
            }
            "grace" -> {
                val state = args.getOrNull(2)?.lowercase()
                if (state != "available" && state != "used") {
                    reply(sender, Component.text("Usage: /loginstreak grace <player> <available|used>", NamedTextColor.RED))
                    return true
                }
                plugin.loginStreakManager.setGrace(targetUuid, state == "available")
                reply(sender, Component.text("Set $targetName's Grace to $state.", NamedTextColor.GREEN))
            }
        }
        return true
    }

    private fun usageSuffix(sub: String): String = when (sub) {
        "set" -> " <days>"
        "playtime" -> " <minutes>"
        "grace" -> " <available|used>"
        else -> ""
    }

    private fun reply(sender: CommandSender, message: Component) {
        if (sender is Player) {
            plugin.commsManager.send(sender, message, CommunicationsManager.Category.ECONOMY)
        } else {
            sender.sendMessage(message)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val options = mutableListOf("info")
            options += Bukkit.getOnlinePlayers().map { it.name }
            if (sender.hasPermission("joshymc.loginstreak.admin")) options += adminSubcommands
            return options.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].lowercase() in adminSubcommands && args[0].lowercase() != "reload") {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && args[0].lowercase() == "grace") {
            return listOf("available", "used").filter { it.startsWith(args[2], ignoreCase = true) }
        }
        return emptyList()
    }
}
