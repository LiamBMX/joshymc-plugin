package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.util.ProfanityFilter
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class MinecraftChatListener(private val plugin: Joshymc) : Listener {

    private val plainSerializer = PlainTextComponentSerializer.plainText()
    private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()

    /**
     * Run BEFORE other chat handlers (LOW priority) so profanity is censored
     * out of the message before formatting/Discord get to it. Bypassable by
     * `joshymc.chat.profanity.bypass` for staff who need to discuss reports.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChatProfanity(event: AsyncChatEvent) {
        val player = event.player
        if (player.hasPermission("joshymc.chat.profanity.bypass")) return

        val plain = plainSerializer.serialize(event.message())
        if (!ProfanityFilter.contains(plain)) return

        // Replace the message component with the censored version so the
        // chat format (rank prefix, nickname, etc.) is preserved.
        val censored = ProfanityFilter.censor(plain)
        event.message(Component.text(censored))
        plugin.commsManager.send(player,
            Component.text("Your message contained banned words and was censored.", NamedTextColor.RED)
        )
    }

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
