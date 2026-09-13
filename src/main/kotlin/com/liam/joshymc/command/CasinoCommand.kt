package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.casino.CasinoMainGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/casino` (issue #727) — the single public entry point into every Casino game.
 * No `/mines`, `/roulette`, `/crash`, or `/towers` commands are registered; those
 * games are only reachable through this GUI.
 */
class CasinoCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.casino")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return true
        }
        if (!plugin.isFeatureEnabled("casino") || !plugin.casinoManager.enabled) {
            plugin.commsManager.send(sender, Component.text("Casino is currently disabled.", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return true
        }

        CasinoMainGui.open(plugin, sender)
        return true
    }
}
