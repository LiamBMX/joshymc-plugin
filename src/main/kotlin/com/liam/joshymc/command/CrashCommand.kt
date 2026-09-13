package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.casino.crash.CrashGui
import com.liam.joshymc.manager.CasinoManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/crash` (issue #743) — direct entry point into the same Crash GUI offered from the
 * `/casino` hub. Routes through [CrashGui], a live view into the single shared round
 * tracked by [CasinoManager]'s Crash backend, so no duplicate round state is created.
 */
class CrashCommand(private val plugin: Joshymc) : CommandExecutor {

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
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.CRASH)) {
            plugin.commsManager.send(sender, Component.text("Crash is currently disabled.", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return true
        }

        CrashGui.open(plugin, sender)
        return true
    }
}
