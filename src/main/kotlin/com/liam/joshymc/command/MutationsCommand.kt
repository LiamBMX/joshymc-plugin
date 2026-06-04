package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.MutationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class MutationsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.isOp && !sender.hasPermission("joshymc.mutations")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val sub = args.getOrNull(0)?.lowercase()

        when (sub) {
            "catalyst" -> {
                val seconds = args.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }
                if (seconds == null) {
                    sender.sendMessage(Component.text("Usage: /mutations catalyst <seconds>", NamedTextColor.YELLOW))
                    return true
                }
                plugin.mutationsManager.startEvent(MutationsManager.MutationEvent.CATALYST, seconds)
                sender.sendMessage(
                    Component.text("Catalyst Event started for ${formatDuration(seconds)}.", NamedTextColor.GREEN)
                )
            }
            "stop" -> {
                if (!plugin.mutationsManager.isEventActive()) {
                    sender.sendMessage(Component.text("No mutations event is active.", NamedTextColor.RED))
                    return true
                }
                plugin.mutationsManager.stopEvent(announce = true)
                sender.sendMessage(Component.text("Mutations event stopped.", NamedTextColor.YELLOW))
            }
            "status" -> {
                val event = plugin.mutationsManager.getActiveEvent()
                if (event == null) {
                    sender.sendMessage(Component.text("No mutations event is currently active.", NamedTextColor.GRAY))
                } else {
                    sender.sendMessage(
                        Component.text("Active event: ", NamedTextColor.YELLOW)
                            .append(Component.text(event.displayName, NamedTextColor.GOLD))
                    )
                }
            }
            else -> {
                sender.sendMessage(
                    Component.text("Usage: /mutations <catalyst <seconds>|stop|status>", NamedTextColor.YELLOW)
                )
            }
        }

        return true
    }

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<out String>
    ): List<String> {
        if (!sender.isOp && !sender.hasPermission("joshymc.mutations")) return emptyList()
        return when (args.size) {
            1 -> listOf("catalyst", "stop", "status").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> if (args[0].equals("catalyst", ignoreCase = true))
                listOf("300", "600", "1200", "3600").filter { it.startsWith(args[1]) }
            else emptyList()
            else -> emptyList()
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${s}s"
        }
    }
}
