package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatFormatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        event.renderer(ChatRenderer.viewerUnaware { source, _, message ->
            plugin.commsManager.formatChat(source, message)
        })
    }
}
