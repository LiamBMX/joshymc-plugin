package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class DiscordCommand(private val plugin: Joshymc) : CommandExecutor {

    private val inviteUrl = "https://discord.gg/joshymc"

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        sender.sendMessage(Component.empty())
        sender.sendMessage(
            Component.text("  Join our Discord: ", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
                .append(
                    Component.text(inviteUrl, NamedTextColor.AQUA)
                        .decoration(TextDecoration.UNDERLINED, true)
                        .decoration(TextDecoration.ITALIC, false)
                        .clickEvent(ClickEvent.openUrl(inviteUrl))
                        .hoverEvent(HoverEvent.showText(
                            Component.text("Click to open Discord invite", NamedTextColor.GRAY)
                        ))
                )
        )
        sender.sendMessage(Component.empty())
        return true
    }
}
