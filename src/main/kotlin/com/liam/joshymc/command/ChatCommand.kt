package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
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

        when (args.getOrNull(0)?.lowercase()) {
            "mute" -> setMuted(sender, true)
            "unmute" -> setMuted(sender, false)
            "clear" -> clearChat(sender)
            else -> sendMessage(sender, Component.text("Usage: /chat <mute|unmute|clear>", NamedTextColor.RED))
        }
        return true
    }

    private fun setMuted(sender: CommandSender, muted: Boolean) {
        plugin.chatManager.setMuted(muted)

        val status = if (muted) Component.text("muted", NamedTextColor.RED) else Component.text("unmuted", NamedTextColor.GREEN)
        plugin.commsManager.broadcast(
            Component.text("Chat has been ", NamedTextColor.GRAY).append(status).append(Component.text(".", NamedTextColor.GRAY)),
            CommunicationsManager.Category.ADMIN
        )
    }

    private fun clearChat(sender: CommandSender) {
        val blankLine = Component.text(" ")
        repeat(CLEAR_LINE_COUNT) {
            Bukkit.getOnlinePlayers().forEach { it.sendMessage(blankLine) }
        }

        val staffName = if (sender is Player) sender.name else "Console"
        val template = plugin.config.getString("chat.clear-message", DEFAULT_CLEAR_MESSAGE) ?: DEFAULT_CLEAR_MESSAGE
        val message = plugin.commsManager.parseLegacy(template.replace("{player}", staffName))
        plugin.commsManager.broadcast(message, CommunicationsManager.Category.ADMIN)
    }

    companion object {
        private const val CLEAR_LINE_COUNT = 100
        private const val DEFAULT_CLEAR_MESSAGE = "&7Chat has been cleared by &f{player}&7."
    }

    private fun sendMessage(sender: CommandSender, message: Component) {
        if (sender is Player) plugin.commsManager.send(sender, message, CommunicationsManager.Category.ADMIN)
        else sender.sendMessage(message)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("mute", "unmute", "clear").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}
