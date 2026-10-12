package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/jrestart` (issue #1102): starts the automatic restart countdown right away.
 * Goes through [com.liam.joshymc.manager.AutoRestartManager] so manual and scheduled
 * restarts share one countdown and can never overlap.
 */
class JRestartCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.jrestart")) {
            reply(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (!plugin.autoRestartManager.triggerRestart()) {
            reply(sender, Component.text("A restart is already in progress.", NamedTextColor.RED))
            return true
        }

        plugin.logger.info("[AutoRestart] Manual restart started by ${sender.name}.")
        reply(sender, Component.text("Restart sequence started.", NamedTextColor.GREEN))
        return true
    }

    private fun reply(sender: CommandSender, message: Component) {
        if (sender is Player) plugin.commsManager.send(sender, message) else sender.sendMessage(message)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        emptyList()
}
