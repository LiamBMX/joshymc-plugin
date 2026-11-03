package com.liam.joshymc.teleport

/**
 * Bukkit-free combat rules for player-initiated teleports (issue #1356).
 *
 * The combat tag itself is [com.liam.joshymc.manager.CombatManager]'s, the one tag both
 * Survival and Lifesteal use, so these rules hold on both gamemodes. A teleport is checked
 * when it is requested and, for delayed (warmup) teleports, on every countdown step and again
 * right before it runs: a player who gets tagged mid-countdown is never moved.
 */
object CombatTeleportRules {

    const val MSG_IN_COMBAT = "You cannot teleport while in combat!"
    const val MSG_ENTERED_COMBAT = "Teleport cancelled — you entered combat!"

    /** What a delayed teleport does on one countdown step. */
    enum class WarmupStep {
        /** Keep counting down. */
        CONTINUE,
        /** The warmup is over and nothing stopped it: teleport now. */
        TELEPORT,
        /** The player left; drop the task silently. */
        CANCEL_OFFLINE,
        /** The player moved off their block. */
        CANCEL_MOVED,
        /** The player was combat-tagged during the warmup. */
        CANCEL_COMBAT,
    }

    /** True when a player-initiated teleport may be requested. */
    fun allowsRequest(tagged: Boolean): Boolean = !tagged

    /**
     * One step of a warmup. Checked in a fixed order: offline, moved, combat, then whether the
     * countdown is over. Combat is checked on every step, including the last one, so the
     * teleport can never run for a tagged player.
     */
    fun warmupStep(online: Boolean, moved: Boolean, tagged: Boolean, finished: Boolean): WarmupStep = when {
        !online -> WarmupStep.CANCEL_OFFLINE
        moved -> WarmupStep.CANCEL_MOVED
        tagged -> WarmupStep.CANCEL_COMBAT
        finished -> WarmupStep.TELEPORT
        else -> WarmupStep.CONTINUE
    }
}
