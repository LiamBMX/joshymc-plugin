package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

class StaffChatListener(private val plugin: Joshymc) : Listener {

    private val plainSerializer = PlainTextComponentSerializer.plainText()

    /**
     * Runs before ChatFormatListener/MinecraftChatListener (which sit at HIGHEST/MONITOR)
     * so a redirected message never reaches public formatting, Discord, or chat games.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!plugin.staffChatManager.isEnabled(player)) return

        event.isCancelled = true
        val plain = plainSerializer.serialize(event.message())
        plugin.staffChatManager.sendMessage(player, plain)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.staffChatManager.handleQuit(event.player.uniqueId)
    }
}
