package com.liam.joshymc.gamemode

import com.liam.joshymc.Joshymc
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerCommandSendEvent
import org.bukkit.event.server.ServerCommandEvent

/**
 * Typed-command checks and command-list hiding for gamemode locks (issue #1289).
 * JoshyMC's own commands are additionally gated in their executor, see [GamemodeLockManager].
 */
class GamemodeLockListener(private val plugin: Joshymc) : Listener {

    private val locks get() = plugin.gamemodeLockManager

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        val command = locks.resolve(firstWord(event.message) ?: return) ?: return
        if (locks.canUse(event.player, command)) return
        event.isCancelled = true
        locks.deny(event.player, command)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        val command = locks.resolve(firstWord(event.command) ?: return) ?: return
        if (locks.canUse(event.sender, command)) return
        event.isCancelled = true
        locks.deny(event.sender, command)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onCommandSend(event: PlayerCommandSendEvent) {
        val hidden = locks.hiddenLabels(event.player)
        if (hidden.isNotEmpty()) event.commands.removeAll(hidden)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (locks.hasWorldOverrides) event.player.updateCommands()
    }

    private fun firstWord(line: String): String? =
        line.trimStart().removePrefix("/").substringBefore(' ').takeIf { it.isNotEmpty() }
}
