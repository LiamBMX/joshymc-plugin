package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class AnnounceCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.announce")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /bc <message> or /bc <template>", NamedTextColor.RED))
            return true
        }

        // Check if first arg matches a template name
        val templates = plugin.announcementManager.getTemplates()
        val templateMatch = templates[args[0].lowercase()]

        if (templateMatch != null && args.size == 1) {
            // Send the template
            plugin.announcementManager.broadcast(templateMatch)
        } else {
            // Send the raw message
            plugin.announcementManager.broadcast(args.joinToString(" "))
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return plugin.announcementManager.getTemplates().keys
                .filter { it.startsWith(args[0].lowercase()) }
                .toList()
        }
        return emptyList()
    }
}
