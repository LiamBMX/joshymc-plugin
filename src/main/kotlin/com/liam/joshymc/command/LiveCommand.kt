package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class LiveCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private enum class Platform(val label: String, val url: String) {
        TIKTOK("TikTok", "https://www.tiktok.com/@tbjoshy/live"),
        TWITCH("Twitch", "https://www.twitch.tv/tbjoshy")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.live")) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED))
            return true
        }

        val platform = args.getOrNull(0)?.let { arg -> Platform.entries.find { it.name.equals(arg, ignoreCase = true) } }
        if (args.size != 1 || platform == null) {
            sender.sendMessage(Component.text("Usage: /live <tiktok/twitch>", NamedTextColor.RED))
            return true
        }

        val message = Component.text("Joshy is LIVE on ${platform.label}! ", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.ITALIC, false)
            .append(
                Component.text("Click here to watch.", NamedTextColor.AQUA)
                    .decoration(TextDecoration.UNDERLINED, true)
                    .decoration(TextDecoration.ITALIC, false)
                    .clickEvent(ClickEvent.openUrl(platform.url))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to open ${platform.label}", NamedTextColor.GRAY)))
            )

        Bukkit.broadcast(message)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return Platform.entries.map { it.name.lowercase() }.filter { it.startsWith(args[0].lowercase(), ignoreCase = true) }
        }
        return emptyList()
    }
}
