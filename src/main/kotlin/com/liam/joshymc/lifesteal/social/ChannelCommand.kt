package com.liam.joshymc.lifesteal.social

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.social.SocialRules.Channel
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/channel` (alias `/ch`, issue #1430, Lifesteal only):
 * - `/channel` shows the current channel and which ones are available,
 * - `/channel <global|team|alliance|local>` switches where typed chat goes (saved),
 * - `/channel <team|alliance|local> <message>` sends one message there without switching.
 * Alliance chat is the existing `/ac` path; Global is ordinary public chat.
 */
class ChannelCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val social get() = plugin.lifestealSocialManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (args.isEmpty()) {
            show(sender)
            return true
        }
        val channel = Channel.parse(args[0]) ?: run {
            Msg.usage(sender, "/$label <global|team|alliance|local> [message]")
            return true
        }
        if (args.size == 1) social.setChannel(sender, channel)
        else social.speak(sender, channel, args.drop(1).joinToString(" "), fromChat = false)
        return true
    }

    private fun show(player: Player) {
        social.channelProblem(player, Channel.GLOBAL)?.let { return social.refuse(player, it) }
        val current = social.channelOf(player.uniqueId)
        social.send(player, Component.text("You're talking in ${current.label} chat.", NamedTextColor.GOLD))
        for (channel in Channel.entries) {
            val problem = social.channelProblem(player, channel)
            val line = Component.text("  ${channel.label}", if (problem == null) NamedTextColor.GREEN else NamedTextColor.GRAY)
                .append(Component.text(if (problem == null) " - /channel ${channel.name.lowercase()}" else " - $problem", NamedTextColor.GRAY))
            player.sendMessage(line)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> =
        if (args.size == 1) Channel.entries.map { it.name.lowercase() }.filter { it.startsWith(args[0], ignoreCase = true) } else emptyList()
}
