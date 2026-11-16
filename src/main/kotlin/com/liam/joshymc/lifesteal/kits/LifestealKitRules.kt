package com.liam.joshymc.lifesteal.kits

/**
 * Issue #1577: the Bukkit-free rules of the Lifesteal kit claim (`KitManager.claimLifestealKit`).
 *
 * - **When a claim is refused** ([refusal]): fixed order, first match wins. Everything that could
 *   lose, roll back or duplicate the items is refused: dead, Mod Mode, a Ranked match (held,
 *   spectating or a restore still pending), the event world or an event inventory operation that
 *   isn't settled, and the combat tag (configurable in lifesteal-ranks.yml).
 * - **One-time kits** (kits.yml `one-time: true`, Lifesteal kits only): claimable once per player,
 *   ever. The claim row in `kit_cooldowns` is never moved again ([ONE_TIME] as the cooldown makes the
 *   conditional reservation never match an existing row), and no cooldown bypass reopens it.
 * - **Bundled defaults** ([seedable]): bundled Lifesteal kits missing from an existing kits.yml are
 *   added once; their names are recorded so a kit the staff later deletes is never re-added.
 */
object LifestealKitRules {

    /** The cooldown of a one-time kit: no reservation can ever move its claim row. */
    const val ONE_TIME: Long = Long.MAX_VALUE

    /** kits.yml list of bundled kits already added once (never re-added after a delete). */
    const val SEEDED_KEY = "seeded-defaults"

    data class State(
        val dead: Boolean,
        val modMode: Boolean,
        val rankedHeld: Boolean,
        val rankedSpectating: Boolean,
        val rankedRestorePending: Boolean,
        val inEventWorld: Boolean,
        val eventInventoryUnsettled: Boolean,
        val combatTagged: Boolean,
        val refuseWhileCombatTagged: Boolean,
    )

    enum class Refusal(val message: String) {
        DEAD("You can't claim kits while dead."),
        MOD_MODE("Leave Mod Mode to claim kits."),
        RANKED_MATCH("You can't claim kits during a Ranked match."),
        RANKED_SPECTATING("You can't claim kits while spectating a Ranked match."),
        RANKED_RESTORE("Your inventory is still being restored after a Ranked match. Try again in a moment."),
        EVENT_WORLD("You can't claim kits in the event world."),
        EVENT_INVENTORY("Your inventory is still being restored after an event. Try again in a moment."),
        COMBAT("You can't claim kits while in combat."),
    }

    fun refusal(state: State): Refusal? = when {
        state.dead -> Refusal.DEAD
        state.modMode -> Refusal.MOD_MODE
        state.rankedHeld -> Refusal.RANKED_MATCH
        state.rankedSpectating -> Refusal.RANKED_SPECTATING
        state.rankedRestorePending -> Refusal.RANKED_RESTORE
        state.inEventWorld -> Refusal.EVENT_WORLD
        state.eventInventoryUnsettled -> Refusal.EVENT_INVENTORY
        state.combatTagged && state.refuseWhileCombatTagged -> Refusal.COMBAT
        else -> null
    }

    /**
     * The cooldown a claim reserves: [ONE_TIME] for a one-time kit (bypass never applies), 0 with a
     * cooldown bypass, else [cooldownMillis].
     */
    fun reservedCooldown(oneTime: Boolean, bypass: Boolean, cooldownMillis: Long): Long = when {
        oneTime -> ONE_TIME
        bypass -> 0L
        else -> cooldownMillis.coerceAtLeast(0L)
    }

    /** Time left before the next claim: [claimedAt] null = never claimed; a claimed one-time kit = [ONE_TIME]. */
    fun remaining(claimedAt: Long?, oneTime: Boolean, cooldownMillis: Long, now: Long): Long {
        if (claimedAt == null) return 0L
        if (oneTime) return ONE_TIME
        val left = cooldownMillis - (now - claimedAt)
        return if (left > 0) left else 0L
    }

    /** The bundled kits to add to an existing kits.yml: missing there and never added before (case-insensitive). */
    fun seedable(bundled: Collection<String>, existing: Collection<String>, seeded: Collection<String>): List<String> {
        val have = (existing + seeded).map { it.lowercase() }.toSet()
        return bundled.filter { it.lowercase() !in have }
    }
}
