package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.casino.towers.TowersSetupGui
import com.liam.joshymc.manager.CasinoManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/towers` (issue #743) — direct entry point into the same Towers GUI/session offered
 * from the `/casino` hub. Routes through [TowersSetupGui], so active sessions, bet
 * rules, and stats all stay backed by [CasinoManager] with no duplicated state.
 */
class TowersCommand(private val plugin: Joshymc) : CommandExecutor {

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
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.TOWERS)) {
            plugin.commsManager.send(sender, Component.text("Towers is currently disabled.", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return true
        }

        TowersSetupGui.open(plugin, sender)
        return true
    }
}
