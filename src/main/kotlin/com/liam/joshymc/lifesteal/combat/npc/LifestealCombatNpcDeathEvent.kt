package com.liam.joshymc.lifesteal.combat.npc

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import java.util.UUID

/**
 * Fired once per committed Combat NPC death (issue #1402), on the main thread, after the death is
 * recorded, the items have dropped and hearts/statistics ran. It is the integration point for a
 * future Lifesteal bounty system: JoshyMC has no Lifesteal bounty service yet, so nothing listens
 * and no bounty is paid. A listener must do its own eligibility checks (teammates, alts, its
 * cooldowns) and dedupe on [sessionId], which is unique per NPC and never fired twice by JoshyMC.
 *
 * Not cancellable: the death has already happened.
 */
class LifestealCombatNpcDeathEvent(
    /** The NPC session id (unique per NPC; the dedupe key for any reward). */
    val sessionId: String,
    /** The disconnected player who died (offline). */
    val victim: UUID,
    val victimName: String,
    /** The player the final damage came from, or null for environmental/mob deaths. */
    val killer: Player?,
    /** Where the NPC died (the drops are there). */
    val location: Location,
    /** The final damage's type key (e.g. `minecraft:player_attack`). */
    val damageType: String?,
) : Event() {

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
