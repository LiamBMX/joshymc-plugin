package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class CreateKitCommand(private val plugin: Joshymc) : CommandExecutor {

    private val nameRegex = Regex("^[a-zA-Z0-9_]{1,20}$")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.createkit")) {
            Msg.noPermission(sender)
            return true
        }

        if (args.isEmpty()) {
            Msg.usage(sender, "/createkit <name>")
            return true
        }

        val name = args[0].lowercase()

        if (!nameRegex.matches(name)) {
            plugin.commsManager.send(sender, Component.text("Kit name must be alphanumeric/underscores, max 20 characters.", NamedTextColor.RED))
            return true
        }

        if (plugin.kitManager.getKit(name) != null) {
            plugin.commsManager.send(sender, Component.text("Kit '$name' already exists. Delete it first.", NamedTextColor.RED))
            return true
        }

        plugin.kitManager.openCreateKitGui(sender, name)
        return true
    }
}
