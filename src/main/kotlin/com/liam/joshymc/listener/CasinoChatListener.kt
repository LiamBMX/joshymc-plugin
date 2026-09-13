package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Chat-input capture for the shared `/casino` bet-amount prompt (issue #727).
 * Mirrors CoinflipChatListener: LOWEST priority, cancel the chat event, pull plain
 * text, hand off to the pending callback on the main thread.
 */
class CasinoChatListener(private val plugin: Joshymc) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val pending = plugin.casinoManager.pendingBetInputs.remove(player.uniqueId) ?: return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (raw.equals("cancel", ignoreCase = true)) {
            plugin.commsManager.send(player, Component.text("Casino bet cancelled.", NamedTextColor.GRAY), CommunicationsManager.Category.CASINO)
            return
        }
        if (plugin.casinoManager.isExpired(pending.expiresAt)) {
            plugin.commsManager.send(player, Component.text("Your request timed out. Please try again.", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return
        }

        val amount = plugin.economyManager.parseAmount(raw)
        if (amount == null || amount <= 0.0) {
            plugin.commsManager.send(player, Component.text("Invalid amount. Use numbers like 100000, 100k, 1m", NamedTextColor.RED), CommunicationsManager.Category.CASINO)
            return
        }

        // Withdraws/persists — run on the main thread.
        plugin.server.scheduler.runTask(plugin, Runnable {
            pending.onAmount(player, amount)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.casinoManager.pendingBetInputs.remove(event.player.uniqueId)
        plugin.casinoMinesManager.handleDisconnect(event.player.uniqueId)
        plugin.casinoTowersManager.handleDisconnect(event.player.uniqueId)
    }
}
