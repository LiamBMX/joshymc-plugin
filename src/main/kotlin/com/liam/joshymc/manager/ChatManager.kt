package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

class ChatManager(private val plugin: Joshymc) : Listener {

    companion object {
        const val PERM_MUTE_BYPASS = "joshymc.chat.mute.bypass"
        const val PERM_CLEARING = "joshymc.chat.clearing"
        const val CLEARING_SETTING_KEY = "chat_clearing"
    }

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
        if (event.player.hasPermission(PERM_MUTE_BYPASS)) return

        event.isCancelled = true
        plugin.commsManager.send(
            event.player,
            Component.text("Chat is currently muted.", NamedTextColor.RED),
            CommunicationsManager.Category.ADMIN
        )
    }

    /** Whether this staff member's screen gets cleared by /chat clear. Persisted via SettingsManager, defaults to ON. */
    fun isClearingEnabled(player: Player): Boolean = plugin.settingsManager.getSetting(player, CLEARING_SETTING_KEY)

    fun setClearingEnabled(player: Player, value: Boolean) {
        plugin.settingsManager.setSetting(player, CLEARING_SETTING_KEY, value)
    }
}
