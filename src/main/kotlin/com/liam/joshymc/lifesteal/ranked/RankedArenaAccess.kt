package com.liam.joshymc.lifesteal.ranked

import java.util.UUID

/**
 * Issue #1524: the Bukkit-free rules for who may be inside a Ranked Team Wars arena in use, what
 * happens to anyone else, and which harm reaches a match from outside it. The listeners
 * ([LifestealRankedListener], [RankedArenaGuardListener]) and the access sweep in
 * [LifestealRankedManager] feed it facts; nothing here touches a server.
 *
 * An arena is **protected** while a match holds players in it (PREPARING through FINISHING) or a
 * queue proposal reserves it ([Zone]). Both come from in-memory state, so a cancel, a completed
 * match or a restart never leaves an arena locked.
 */
object RankedArenaAccess {

    const val MSG_ENTRY = "A ranked match is using this arena."
    const val MSG_MOVED = "A ranked match is using this arena, so you were moved out of it."
    const val MSG_HELD = "A ranked match is using this arena. You can't affect it, and you'll be moved out once you're out of combat."

    /** How long after a relocation attempt the next one for the same player waits (no teleport loops). */
    const val RETRY_MILLIS = 5_000L

    /** A protected arena: [matchId] is null while only a queue proposal reserves it ([reservedFor] = its players). */
    data class Zone(val arenaId: String, val matchId: String?, val reservedFor: Set<UUID> = emptySet())

    /** Who someone standing in (or walking into) a protected arena is, for that arena. */
    enum class Occupant {
        /** Held by the match using this arena. */
        PARTICIPANT,
        /** A player of the queue proposal reserving this arena (about to be teleported in). */
        RESERVED_PLAYER,
        /** Anybody else: other players, spectating staff, players of another match. */
        OUTSIDER,
    }

    /** [playerMatchId] is the match that holds the player (null = none). */
    fun occupant(zone: Zone, uuid: UUID, playerMatchId: String?): Occupant = when {
        zone.matchId != null && playerMatchId == zone.matchId -> Occupant.PARTICIPANT
        zone.matchId == null && uuid in zone.reservedFor -> Occupant.RESERVED_PLAYER
        else -> Occupant.OUTSIDER
    }

    /** Walking, riding or teleporting into a protected arena is refused for outsiders only. */
    fun entryRefused(occupant: Occupant): Boolean = occupant == Occupant.OUTSIDER

    /** What the sweep does with someone found inside a protected arena. */
    enum class Relocation {
        /** Allowed to be there (participant, reserved player), or nothing to do right now (dead, being moved). */
        STAY,
        /** Teleport them to the destination. */
        MOVE,
        /**
         * They can't be moved safely (combat-tagged: moving them would end their fight; or no safe
         * destination): they stay, and every interference rule keeps refusing them.
         */
        DENY_ONLY,
        /** A relocation was tried a moment ago; wait [RETRY_MILLIS] before the next one. */
        WAIT,
    }

    data class RelocationFacts(
        val occupant: Occupant,
        val dead: Boolean,
        /** This system is already moving them (an expected teleport, a restore in progress). */
        val beingMoved: Boolean,
        /** [com.liam.joshymc.teleport.CombatTeleportGuard.problem]: null when they may be teleported. */
        val teleportProblem: String?,
        /** A safe destination outside every protected arena exists right now. */
        val hasDestination: Boolean,
        /** When the last relocation of this player was tried, or null. */
        val lastAttemptAt: Long?,
        val now: Long,
    )

    /** Fixed order: authorized, nothing to do, combat (never an escape), retry window, destination. */
    fun relocation(f: RelocationFacts): Relocation = when {
        !entryRefused(f.occupant) -> Relocation.STAY
        f.dead || f.beingMoved -> Relocation.STAY
        f.teleportProblem != null -> Relocation.DENY_ONLY
        f.lastAttemptAt != null && f.now - f.lastAttemptAt < RETRY_MILLIS -> Relocation.WAIT
        !f.hasDestination -> Relocation.DENY_ONLY
        else -> Relocation.MOVE
    }

    /**
     * A destination for the sweep: usable when it is outside every protected arena (so the move
     * can't loop) and [SafeSpots][com.liam.joshymc.recovery.SafeSpots] found no problem.
     */
    fun destinationUsable(insideProtectedArena: Boolean, safetyProblem: String?): Boolean = !insideProtectedArena && safetyProblem == null

    // ── Harm ────────────────────────────────────────────────

    /** Who is behind damage, a potion, a cloud or fire, as the listener resolved it. */
    enum class Source {
        /** No player behind it (fall, lava, a wild mob, a dispenser). */
        NONE,
        /** The victim themself. */
        SELF,
        /** Another player fighting in the victim's ACTIVE match ([LifestealRankedManager.isMatchFight]). */
        SAME_FIGHT,
        /** Any other online player (outsiders, spectators, players of another match, defeated players). */
        OTHER_PLAYER,
        /**
         * A tamed mob (or its projectile) whose owner isn't online: no player can be checked, so for
         * a match it counts as an outsider, never as environmental damage.
         */
        ABSENT_OWNER,
    }

    data class HarmFacts(
        val source: Source,
        val victimSpectator: Boolean,
        val attackerSpectator: Boolean,
        /** The state of the match holding the victim, or null when no match holds them. */
        val victimMatchState: MatchState?,
        /** A match holds the attacker. */
        val attackerHeld: Boolean,
    )

    /**
     * Whether harm to a player is refused. Spectators are out of the game entirely; a held victim
     * takes nothing before the fight and, in the fight, only environmental harm, their own and
     * harm from the same fight; a held attacker reaches nobody outside their match. Everything
     * else is ordinary Lifesteal and left alone.
     */
    fun harmBlocked(f: HarmFacts): Boolean {
        val byPlayer = f.source == Source.SAME_FIGHT || f.source == Source.OTHER_PLAYER
        if (f.victimSpectator) return true
        if (byPlayer && f.attackerSpectator) return true
        if (f.victimMatchState != null) {
            if (f.victimMatchState != MatchState.ACTIVE) return true
            return when (f.source) {
                Source.NONE, Source.SELF, Source.SAME_FIGHT -> false
                Source.OTHER_PLAYER, Source.ABSENT_OWNER -> true
            }
        }
        return byPlayer && f.attackerHeld
    }

    /**
     * A tamed mob of [ownerInFight] = whether its owner fights in the victim's match: it may only
     * go after a held participant when its owner does (a participant's own pet in the fight).
     * Mobs of outsiders, spectators and absent owners never target participants or spectators.
     */
    fun petTargetRefused(victimHeld: Boolean, victimSpectator: Boolean, ownerInFight: Boolean): Boolean =
        victimSpectator || (victimHeld && !ownerInFight)

    /**
     * Damage to a non-player entity inside a protected arena (End Crystals, mounts, armor stands,
     * mobs): refused from spectators everywhere and from anyone who isn't a participant of the
     * match using that arena (an outsider shooting a crystal from over the boundary). Harm with no
     * player behind it is left to the match.
     */
    fun entityHarmRefused(source: Source, attackerSpectator: Boolean, attackerParticipantOfZone: Boolean, victimInZone: Boolean): Boolean {
        if (source == Source.NONE) return false
        if (attackerSpectator && source != Source.SELF) return true
        if (!victimInZone) return false
        if (source == Source.ABSENT_OWNER) return true
        return !attackerParticipantOfZone
    }

    /** Spawn reasons a player (or their dispenser) causes; refused inside a protected arena. Natural spawns stay. */
    val PLAYER_SPAWN_REASONS: Set<String> = setOf(
        "SPAWNER_EGG", "DISPENSE_EGG", "EGG", "BUCKET",
        "BUILD_IRONGOLEM", "BUILD_SNOWMAN", "BUILD_WITHER", "BUILD_COPPERGOLEM",
        "CURED", "BREEDING", "SHEARED", "DUPLICATION", "ENDER_PEARL",
    )

    fun spawnRefused(reason: String, inZone: Boolean): Boolean = inZone && reason in PLAYER_SPAWN_REASONS

    /**
     * A non-player entity teleporting or going through a portal ([fromInZone] / [toInZone] for one
     * and the same protected arena): it can't arrive from outside (pets following an outsider,
     * mobs sent through portals, endermen and shulkers hopping in). Inside the arena they move as usual.
     */
    fun entityArrivalRefused(fromInZone: Boolean, toInZone: Boolean): Boolean = toInZone && !fromInZone
}
