package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class GiftCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.gift")) {
            Msg.noPermission(sender)
            return true
        }

        if (!plugin.isFeatureEnabled("gift")) {
            plugin.commsManager.send(sender, Component.text("Gifting is currently disabled.", NamedTextColor.RED))
            return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "mailbox" -> {
                if (!sender.hasPermission("joshymc.gift.mailbox")) {
                    Msg.noPermission(sender)
                    return true
                }
                plugin.giftManager.openMailboxGui(sender)
            }
            "sent" -> plugin.giftManager.openSentGui(sender)
            "send" -> {
                if (!sender.hasPermission("joshymc.gift.send")) {
                    Msg.noPermission(sender)
                    return true
                }
                val name = args.getOrNull(1)
                if (name == null) {
                    Msg.usage(sender, "/gift send <player>")
                    return true
                }
                val target = plugin.giftManager.resolveKnownPlayer(name)
                if (target == null) {
                    plugin.commsManager.send(sender, Component.text("No known player named '$name' has played on this server.", NamedTextColor.RED))
                    return true
                }
                plugin.giftManager.beginComposition(sender, target)
            }
            else -> plugin.giftManager.openMainGui(sender)
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> {
                val subs = mutableListOf("sent")
                if (sender.hasPermission("joshymc.gift.mailbox")) subs.add("mailbox")
                if (sender.hasPermission("joshymc.gift.send")) subs.add("send")
                subs.filter { it.startsWith(args[0].lowercase()) }
            }
            2 -> if (args[0].equals("send", ignoreCase = true) && sender.hasPermission("joshymc.gift.send")) {
                Bukkit.getOnlinePlayers().map { it.name }
                    .filter { it.lowercase().startsWith(args[1].lowercase()) }
                    .filter { it != sender.name }
            } else emptyList()
            else -> emptyList()
        }
    }
}
