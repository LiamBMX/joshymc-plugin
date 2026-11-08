package com.liam.joshymc.lifesteal.bank

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.combat.npc.LifestealCombatNpcDeathEvent
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Lifesteal bank events (issue #1427). ListenerManager registers this only where Lifesteal is
 * hosted. Deaths are only read at MONITOR (after the heart system and every other plugin
 * decided): coin stealing never changes the death, its drops or hearts.
 */
class LifestealBankListener(private val plugin: Joshymc) : Listener {

    private val manager: LifestealBankManager get() = plugin.lifestealBankManager

    /** MONITOR + ignoreCancelled: only deaths that really happen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) = manager.onDeath(event)

    /** A Combat NPC death is its offline owner's death (issue #1402), fired once per NPC. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onCombatNpcDeath(event: LifestealCombatNpcDeathEvent) =
        manager.onCombatNpcDeath(event.sessionId, event.victim, event.victimName, event.killer, manager.isLifesteal(event.location.world))

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = manager.onJoin(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = manager.onQuit(event.player)

    /** "Custom Amount" prompts: the typed line never reaches chat. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!manager.menu.isAwaiting(player.uniqueId)) return
        event.isCancelled = true
        val raw = PlainTextComponentSerializer.plainText().serialize(event.message())
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) manager.menu.handleInput(player, raw) })
    }
}
