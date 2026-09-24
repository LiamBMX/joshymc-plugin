package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.IgnoreCommand
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatFormatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        event.renderer(ChatRenderer.viewerUnaware { source, _, message ->
            plugin.commsManager.formatChat(source, message)
        })
        val senderUuid = event.player.uniqueId
        event.viewers().removeIf { viewer ->
            viewer is Player && IgnoreCommand.isIgnoring(viewer.uniqueId, senderUuid)
        }
    }
}
