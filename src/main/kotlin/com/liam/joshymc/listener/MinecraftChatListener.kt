package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class MinecraftChatListener(private val plugin: Joshymc) : Listener {

    private val plainSerializer = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        plugin.discordManager.sendChat(event.player.name, plainSerializer.serialize(event.message()))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.discordManager.sendPlayerJoin(event.player.name, event.player.uniqueId.toString())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        plugin.discordManager.sendPlayerLeave(event.player.name, event.player.uniqueId.toString())
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) {
        val text = event.deathMessage()?.let { plainSerializer.serialize(it) } ?: "${event.player.name} died"
        plugin.discordManager.send(":skull: $text")
    }
}
