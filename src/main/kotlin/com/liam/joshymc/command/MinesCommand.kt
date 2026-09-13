package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.casino.mines.MinesBoardGui
import com.liam.joshymc.gui.casino.mines.MinesSetupGui
import com.liam.joshymc.manager.CasinoManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * `/mines [bet]` (issue #743, optional bet arg added in #746) — direct entry point
 * into the same Mines GUI/session offered from the `/casino` hub. Routes through
 * [MinesSetupGui], so active sessions, bet rules, and stats all stay backed by
 * [CasinoManager] with no duplicated state. An active session always wins over the
 * argument — it just resumes the board, wager untouched.
 */
class MinesCommand(private val plugin: Joshymc) : CommandExecutor {

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
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.MINES)) {
            plugin.commsManager.send(sender, Component.text("Mines is currently disabled.", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return true
        }

        var presetBet: Double? = null
        if (args.isNotEmpty()) {
            val result = plugin.casinoManager.parseCommandBet(sender, CasinoManager.Game.MINES, args[0])
            if (result.error != null) {
                plugin.commsManager.send(sender, Component.text(result.error, NamedTextColor.RED), CommunicationsManager.Category.CASINO)
                return true
            }
            presetBet = result.amount
        }

        if (plugin.casinoMinesManager.hasActiveSession(sender.uniqueId)) {
            MinesBoardGui.open(plugin, sender)
        } else if (presetBet != null) {
            MinesSetupGui.openWithBet(plugin, sender, presetBet)
        } else {
            MinesSetupGui.open(plugin, sender)
        }
        return true
    }
}
