package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

// ── /alts ───────────────────────────────────────────────────

class AltsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.alts")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /alts <player>", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val staffUuid = (sender as? Player)?.uniqueId
        plugin.altManager.logLookup(staffUuid, sender.name, target.first, target.second)

        val alts = plugin.altManager.findAlts(target.first)
        if (alts.isEmpty()) {
            sender.sendMessage(Component.text("No known alternate accounts found for ${target.second}.", NamedTextColor.GRAY))
            return true
        }

        sender.sendMessage(Component.text("Possible alts for ${target.second}:", NamedTextColor.GOLD))
        for (alt in alts) {
            sender.sendMessage(Component.text("- ${alt.name}", NamedTextColor.YELLOW))
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}
