package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.Msg
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
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission("joshymc.fakebase.create")) {
            Msg.noPermission(sender)
            return true
        }
        if (args.isEmpty() || !args[0].equals("create", ignoreCase = true)) {
            Msg.usage(sender, "/fakebase create")
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
