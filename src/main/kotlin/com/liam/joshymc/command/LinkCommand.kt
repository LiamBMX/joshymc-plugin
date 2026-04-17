package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.link.LinkGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class LinkCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (plugin.linkManager.isLinked(sender.uniqueId)) {
            plugin.commsManager.send(sender, plugin.commsManager.parseLegacy("&7You're already linked. Use &b/unlink &7to unlink."))
            return true
        }

        if (plugin.discordManager.jda == null) {
            plugin.commsManager.send(sender, Component.text("Discord bot is not connected.", NamedTextColor.RED))
            return true
        }

        val pending = plugin.linkManager.generateCode(sender.uniqueId, sender.name)
        LinkGui.openCodeGui(sender, pending.code)

        return true
    }
}
