package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.StaffTagManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StaffTagCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val fmt = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a z")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.stafftag.admin")) {
            msg(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        val mgr = plugin.staffTagManager
        val sub = args.getOrNull(0)?.lowercase()
        if ((sub != "add" && sub != "remove") || args.size < 3) {
            msg(sender, Component.text("Usage: /stafftag <add|remove> SOTM <player> [duration]", NamedTextColor.RED))
            return true
        }
        if (!args[1].equals(StaffTagManager.SOTM, ignoreCase = true)) {
            msg(sender, Component.text("Unknown staff tag '${args[1]}'. Available: SOTM", NamedTextColor.RED))
            return true
        }
        if (!mgr.isEnabled()) {
            msg(sender, Component.text("The SOTM tag is disabled in config.yml.", NamedTextColor.RED))
            return true
        }
        val target = resolve(args[2])
        if (target == null) {
            msg(sender, Component.text("Unknown player '${args[2]}'.", NamedTextColor.RED))
            return true
        }
        val name = target.name ?: args[2]

        if (sub == "add") {
            val durationArg = (args.getOrNull(3) ?: mgr.getDefaultDuration()).lowercase()
            val ms = mgr.parseDuration(durationArg)
            if (ms == null) {
                msg(sender, Component.text(
                    "Invalid duration '$durationArg'. Allowed: ${mgr.getAllowedDurations().joinToString(", ")}", NamedTextColor.RED))
                return true
            }
            val expiry = mgr.awardSotm(target.uniqueId, ms)
            val until = fmt.format(Instant.ofEpochMilli(expiry).atZone(ZoneId.of("America/New_York")))
            msg(sender, Component.text("Awarded SOTM to $name for $durationArg (expires $until).", NamedTextColor.GREEN))
            target.player?.let {
                plugin.commsManager.send(it, Component.text("You were awarded the Staff of the Month tag for $durationArg!", NamedTextColor.GOLD))
            }
        } else if (mgr.removeSotm(target.uniqueId)) {
            msg(sender, Component.text("Removed SOTM from $name.", NamedTextColor.GREEN))
        } else {
            msg(sender, Component.text("$name doesn't have the SOTM tag.", NamedTextColor.RED))
        }
        return true
    }

    // commsManager.send only takes players; the console is allowed to run this command too.
    private fun msg(sender: CommandSender, message: Component) {
        if (sender is Player) plugin.commsManager.send(sender, message) else sender.sendMessage(message)
    }

    private fun resolve(name: String): OfflinePlayer? =
        Bukkit.getPlayerExact(name) ?: Bukkit.getOfflinePlayerIfCached(name)

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.stafftag.admin")) return emptyList()
        val prefix = args.last().lowercase()
        val options = when (args.size) {
            1 -> listOf("add", "remove")
            2 -> listOf(StaffTagManager.SOTM)
            3 -> Bukkit.getOnlinePlayers().map { it.name } + Bukkit.getOfflinePlayers().mapNotNull { it.name }
            4 -> if (args[0].equals("add", true)) plugin.staffTagManager.getAllowedDurations() else emptyList()
            else -> emptyList()
        }
        return options.distinct().filter { it.lowercase().startsWith(prefix) }
    }
}
