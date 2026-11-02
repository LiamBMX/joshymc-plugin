package com.liam.joshymc.lifesteal.combat

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Lifesteal combat-log bookkeeping (issue #1350). ListenerManager registers it only where this
 * server hosts Lifesteal. Tagging and the quit decision itself run through CombatListener ->
 * CombatManager -> [LifestealCombatManager] (its tag observer); this only supplies the kick cause
 * (Paper reports it on the kick event, not on the quit) and cleans up after the quit.
 */
class LifestealCombatListener(private val combat: LifestealCombatManager) : Listener {

    /** MONITOR + ignoreCancelled: only kicks that really happen (StuckRecovery may cancel flying kicks). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) = combat.onKick(event.player, event.cause.name)

    /** After CombatListener's quit handler (NORMAL) has asked for the decision. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = combat.onQuitCleanup(event.player.uniqueId)
}
