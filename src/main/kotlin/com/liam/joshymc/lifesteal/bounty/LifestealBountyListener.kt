package com.liam.joshymc.lifesteal.bounty

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Lifesteal bounty events (issue #1401). ListenerManager registers this only where Lifesteal is
 * hosted. Nothing here changes a death, its drops, hearts or ordinary PvP: the death handler only
 * reads the event at MONITOR (after the heart system and every other plugin decided).
 */
class LifestealBountyListener(private val plugin: Joshymc) : Listener {

    private val manager: LifestealBountyManager get() = plugin.lifestealBountyManager

    /** MONITOR + ignoreCancelled: only deaths that really happen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) = manager.onDeath(event)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = manager.onJoin(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (manager.isLifesteal(event.player) && !manager.isLifesteal(event.from)) manager.deliverNotifications(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = manager.onQuit(event.player)

    /** Bounty menu prompts (target name, custom amount): the typed line never reaches chat. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!manager.menu.isAwaiting(player.uniqueId)) return
        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message())
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) manager.menu.handleInput(player, raw) })
    }
}
