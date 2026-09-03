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

class ChatCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.chat.admin")) {
            sendMessage(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val muted = when (args.getOrNull(0)?.lowercase()) {
            "mute" -> true
            "unmute" -> false
            else -> {
                sendMessage(sender, Component.text("Usage: /chat <mute|unmute>", NamedTextColor.RED))
                return true
            }
        }

        plugin.chatManager.setMuted(muted)

        val status = if (muted) Component.text("muted", NamedTextColor.RED) else Component.text("unmuted", NamedTextColor.GREEN)
        plugin.commsManager.broadcast(
            Component.text("Chat has been ", NamedTextColor.GRAY).append(status).append(Component.text(".", NamedTextColor.GRAY)),
            CommunicationsManager.Category.ADMIN
        )
        return true
    }

    private fun sendMessage(sender: CommandSender, message: Component) {
        if (sender is Player) plugin.commsManager.send(sender, message, CommunicationsManager.Category.ADMIN)
        else sender.sendMessage(message)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("mute", "unmute").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
