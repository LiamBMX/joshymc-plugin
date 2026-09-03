package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatManager(private val plugin: Joshymc) : Listener {

    var isMuted: Boolean = false
        private set

    fun start() {
        isMuted = plugin.config.getBoolean("chat.muted", false)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        plugin.config.set("chat.muted", muted)
        plugin.saveConfig()
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!isMuted) return
        if (event.player.hasPermission("joshymc.chat.mod.bypass")) return

        event.isCancelled = true
        plugin.commsManager.send(
            event.player,
            Component.text("Chat is currently muted.", NamedTextColor.RED),
            CommunicationsManager.Category.ADMIN
        )
    }
}
