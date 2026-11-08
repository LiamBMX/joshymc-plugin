package com.liam.joshymc.lifesteal.social

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Lifesteal social bookkeeping (issue #1430): friend join/leave notices and chat-channel routing.
 * Only registered where Lifesteal is hosted, and every handler also checks the player's world.
 */
class LifestealSocialListener(private val plugin: Joshymc) : Listener {

    private val social get() = plugin.lifestealSocialManager

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        social.onJoin(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        social.onQuit(event.player)
    }

    /**
     * A player talking in a private channel (Team/Alliance/Local). HIGH + ignoreCancelled: mutes,
     * the global chat mute, staff chat and the repeat rule (LOWEST..NORMAL) already ran and the
     * profanity filter (LOW) already censored the text. The event is cancelled before formatting
     * (HIGHEST) and the Discord bridge / chat games (MONITOR, ignoreCancelled), so nothing leaks into
     * public chat; delivery happens on the main thread with a fresh membership check.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!social.routesChat(player)) return
        val channel = social.channelOf(player.uniqueId)
        event.isCancelled = true
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        Bukkit.getScheduler().runTask(plugin, Runnable { social.speak(player, channel, text, fromChat = true) })
    }
}
