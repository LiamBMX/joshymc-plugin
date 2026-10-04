package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.CreditsCoinflipManager
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent

/** Chat-input capture for the Credits Coinflip "Create" wager prompt (issue #1031). */
class CreditsCoinflipChatListener(private val plugin: Joshymc) : Listener {

    private fun reply(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.DEFAULT)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        val expiresAt = plugin.creditsCoinflipManager.pendingCreateInputs.remove(player.uniqueId) ?: return

        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message()).trim()

        if (raw.equals("cancel", ignoreCase = true)) {
            reply(player, "Credits Coinflip creation cancelled.", NamedTextColor.GRAY)
            return
        }
        if (plugin.creditsCoinflipManager.isExpired(expiresAt)) {
            reply(player, "Your request timed out. Please try again.", NamedTextColor.RED)
            return
        }

        val wager = CreditsCoinflipManager.parseWager(raw)
        if (wager == null || wager <= 0L) {
            reply(player, "Invalid amount. Use a whole number of Credits, e.g. 500.", NamedTextColor.RED)
            return
        }

        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.creditsCoinflipManager.createFlip(player, wager)
        })
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        plugin.creditsCoinflipManager.pendingCreateInputs.remove(event.player.uniqueId)
    }
}
