package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class FakeBaseCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("Players only.")
            return true
        }
        if (!sender.hasPermission("joshymc.fakebase.create")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty() || !args[0].equals("create", ignoreCase = true)) {
            plugin.commsManager.send(sender, Component.text("Usage: /fakebase create", NamedTextColor.RED))
            return true
        }

        plugin.fakeBaseManager.giveWand(sender)
        plugin.commsManager.send(sender, Component.text("Fake base selection wand given.", NamedTextColor.GREEN))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("create").filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}
