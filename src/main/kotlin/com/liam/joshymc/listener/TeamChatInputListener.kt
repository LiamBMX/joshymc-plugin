package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.team.TeamGuiInput
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** Captures chat input for the /team GUI's create/rename/deposit/withdraw prompts (issue #523). */
class TeamChatInputListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!TeamGuiInput.isAwaiting(player.uniqueId)) return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message())

        plugin.server.scheduler.runTask(plugin, Runnable {
            TeamGuiInput.handleChatInput(plugin, player, raw)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        TeamGuiInput.clear(event.player.uniqueId)
    }
}
