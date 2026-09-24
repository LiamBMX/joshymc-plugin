package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * Parent command for per-dimension admin controls, e.g. `/dimension end <open|close>`.
 * Structured as `/dimension <dimension> <action>` so more dimensions can be added later
 * without a rewrite.
 */
class DimensionCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val dimensions = listOf("end")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (args.getOrNull(0)?.lowercase()) {
            "end" -> handleEnd(sender, args)
            else -> sendMessage(sender, Component.text("Usage: /dimension end <open|close>", NamedTextColor.RED))
        }
        return true
    }

    private fun handleEnd(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.end.admin")) {
            sendMessage(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val open = when (args.getOrNull(1)?.lowercase()) {
            "open" -> true
            "close" -> false
            else -> {
                sendMessage(sender, Component.text("Usage: /dimension end <open|close>", NamedTextColor.RED))
                return
            }
        }

        plugin.endManager.setOpen(open)

        val status = if (open) Component.text("opened", NamedTextColor.GREEN) else Component.text("closed", NamedTextColor.RED)
        sendMessage(sender, Component.text("The End has been ", NamedTextColor.GRAY).append(status).append(Component.text(".", NamedTextColor.GRAY)))
    }

    private fun sendMessage(sender: CommandSender, message: Component) {
        if (sender is Player) plugin.commsManager.send(sender, message, CommunicationsManager.Category.ADMIN)
        else sender.sendMessage(message)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> dimensions.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("end", ignoreCase = true)) {
                listOf("open", "close").filter { it.startsWith(args[1], ignoreCase = true) }
            } else emptyList()
            else -> emptyList()
        }
    }
}
