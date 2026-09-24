package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class GiveawayChatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val type = plugin.giveawayManager.pendingChatPrompts.remove(player.uniqueId) ?: return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        // Hop back to the main thread — DB/economy/inventory access isn't safe from the
        // async chat event thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.giveawayManager.handleChatInput(player, type, raw)
        })
    }
}
