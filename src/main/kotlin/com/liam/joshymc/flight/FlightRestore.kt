package com.liam.joshymc.flight

/**
 * Bringing a player's own `/fly` back after a reconnect, restart or gamemode switch (issue #1470).
 * Bukkit-free.
 *
 * The cause: vanilla stores `mayfly`/`flying` in the player file, but when a player is loaded in
 * Survival or Adventure the server applies that gamemode's default abilities, which turns
 * `mayfly` off again. Switching Creative/Spectator back to Survival does the same. JoshyMC never
 * put `/fly` back, so a player who logged out mid-air fell when they came back.
 *
 * The fix stores only the player's choice (the last `/fly` toggle, by UUID). It never grants
 * flight by itself: [decide] restores it only when the player may use `/fly` *right now*, where
 * they are (permission or temporary flight, not combat-tagged, not in an arena, event world or
 * ranked match, the world allows it). Restoring only ever turns flight on; a player who turned
 * `/fly` off, or never used it, is left alone.
 */
object FlightRestore {

    /** Why flight can't come back right now. Mirrors the checks `/fly` makes. */
    enum class Block {
        /** No `joshymc.fly` and no Survival flight time (expired, revoked, downgraded). */
        NO_PERMISSION,
        COMBAT_TAGGED,
        PVP_ARENA,
        /** The pvp world without `joshymc.fly.pvp`, or spawn without `joshymc.spawn.fly`. */
        WORLD_RESTRICTED,
        /** `/fly` (Survival scope) isn't allowed in this world while a gamemode is locked. */
        GAMEMODE_LOCKED,
        EVENT_WORLD,
        RANKED_MATCH,
    }

    data class Input(
        /** The saved `/fly` choice: true = on, false = off, null = never toggled (no row). */
        val preference: Boolean?,
        /** Creative/Spectator fly by themselves; never touched. */
        val creativeOrSpectator: Boolean,
        /** Flight is already on (another source, or vanilla kept it). */
        val alreadyAllowed: Boolean,
        /** Null when the player may use `/fly` here right now. */
        val block: Block?,
        /** Non-solid, non-liquid blocks straight below the player's feet, capped at [MAX_GAP]. */
        val gapBelow: Int,
    )

    sealed interface Decision {
        /** Nothing to do. */
        data object Leave : Decision
        /** Turn flight back on; [flying] when nothing is under the player, so they don't drop. */
        data class Restore(val flying: Boolean) : Decision
        /**
         * Flight was on but can't come back ([reason]) and the player is high enough to get hurt:
         * no flight, only the one-time fall-damage protection in [FALL_PROTECTION_MILLIS].
         */
        data class ProtectFall(val reason: Block) : Decision
        /** Flight was on but can't come back; the player is (close to) the ground. */
        data class Denied(val reason: Block) : Decision
    }

    /** How far below the feet [Input.gapBelow] looks. */
    const val MAX_GAP = 4

    /** A gap this big (or more) is a fall that hurts (vanilla: more than 3 blocks). */
    const val DANGEROUS_GAP = 4

    /** How long the reconnect fall protection lasts at most (it is used up by the first fall). */
    const val FALL_PROTECTION_MILLIS = 15_000L

    /** Rechecks after join: the ticks at which flight is checked again in case something reset it. */
    val RECHECK_TICKS = listOf(2L, 20L)

    fun decide(input: Input): Decision {
        if (input.creativeOrSpectator) return Decision.Leave
        if (input.preference != true) return Decision.Leave
        if (input.alreadyAllowed) return Decision.Leave
        val block = input.block
        if (block == null) return Decision.Restore(flying = input.gapBelow >= 1)
        return if (input.gapBelow >= DANGEROUS_GAP) Decision.ProtectFall(block) else Decision.Denied(block)
    }

    /**
     * Whether a fall-damage hit is cancelled by the reconnect protection: only while it hasn't
     * expired and only when the player isn't in combat (so it can't help in a fight).
     */
    fun cancelsFall(protectedUntil: Long?, now: Long, combatTagged: Boolean): Boolean =
        protectedUntil != null && now < protectedUntil && !combatTagged

    /** Whether a toggle needs a write: only when the stored choice differs. */
    fun needsWrite(stored: Boolean?, newValue: Boolean): Boolean = stored != newValue
}
