package com.liam.joshymc.lifesteal.ranked

/**
 * Bukkit-free rules for `/lifesteal admin ranked spectate` (issue #1511, audit finding D-L7).
 *
 * Spectating teleports the player to the arena's spectator point, so it is a player-initiated
 * teleport like any other: the combat check is [com.liam.joshymc.teleport.CombatTeleportGuard]'s
 * (passed in as [Facts.teleportProblem]), never a second combat-tag system. The request is
 * checked once before the destination chunk loads and again, with fresh facts, right before
 * the teleport; nothing (location, spectator state, combat tag) changes when either refuses.
 */
object RankedSpectateRules {

    const val MSG_PARTICIPANT = "You're playing in a match."
    const val MSG_PENDING_RESTORE = "Your pre-match inventory is still waiting to be restored; you can't spectate until it is."
    const val MSG_DEAD = "You can't spectate while dead."
    const val MSG_FINISHING = "That match is finishing."
    const val MSG_WORLD = "The arena world isn't loaded."
    const val MSG_IN_PROGRESS = "You already have a spectate request in progress."
    const val MSG_CHUNK = "The spectator point couldn't be loaded."
    const val MSG_TELEPORT_FAILED = "The teleport to the spectator point failed."

    fun noMatch(id: String) = "No running match '$id'."
    fun noPoint(arenaId: String) = "Arena '$arenaId' has no spectator point (setspectator)."
    fun unsafe(reason: String) = "The spectator point isn't safe ($reason). Move it with setspectator."

    /** Matches that can be watched: running and not yet finishing or over. */
    fun spectatable(state: MatchState): Boolean = !state.terminal && state != MatchState.FINISHING

    data class Facts(
        val matchId: String,
        /** Null when no open match has this id. */
        val state: MatchState?,
        val arenaId: String?,
        /** The player is held by a ranked match (a participant). */
        val participant: Boolean,
        /** The player has a ranked inventory snapshot waiting to be restored. */
        val pendingRestore: Boolean,
        val dead: Boolean,
        /** [com.liam.joshymc.teleport.CombatTeleportGuard.problem]: null when the player may teleport. */
        val teleportProblem: String?,
        val hasSpectatorPoint: Boolean,
        val worldLoaded: Boolean,
    )

    /** Null when the request may go ahead; otherwise the message for the player. Fixed order. */
    fun problem(f: Facts): String? = when {
        f.participant -> MSG_PARTICIPANT
        f.pendingRestore -> MSG_PENDING_RESTORE
        f.teleportProblem != null -> f.teleportProblem
        f.dead -> MSG_DEAD
        f.state == null -> noMatch(f.matchId)
        !spectatable(f.state) -> if (f.state == MatchState.FINISHING) MSG_FINISHING else noMatch(f.matchId)
        !f.hasSpectatorPoint -> noPoint(f.arenaId ?: f.matchId)
        !f.worldLoaded -> MSG_WORLD
        else -> null
    }

    /**
     * The last check before the teleport, once the destination chunk has loaded: [facts] read
     * again now, [chunkLoaded] whether the load succeeded, [destinationProblem] the
     * [com.liam.joshymc.recovery.SafeSpots] verdict for the spectator point.
     */
    fun arrivalProblem(facts: Facts, chunkLoaded: Boolean, destinationProblem: String?): String? =
        problem(facts) ?: when {
            !chunkLoaded -> MSG_CHUNK
            destinationProblem != null -> unsafe(destinationProblem)
            else -> null
        }
}
