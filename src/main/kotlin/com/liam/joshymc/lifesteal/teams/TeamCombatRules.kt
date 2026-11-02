package com.liam.joshymc.lifesteal.teams

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifesteal team PvP rules (issue #1348), Bukkit-free. [LifestealTeamManager] feeds them its
 * membership cache, [LifestealTeamCombatListener] applies the damage decisions and
 * [com.liam.joshymc.lifesteal.hearts.LifestealHeartManager] the heart-transfer ones;
 * `scripts/LifestealTeamCombatCheck.kt` tests them without a server.
 *
 * The blueprint defines no team combat rule, so the policy is `lifesteal.yml > teams.friendly-fire`
 * ([Policy]); nothing else (no bonus, no extra cooldown) is added.
 */
object TeamCombatRules {

    /** `teams.friendly-fire.enabled` and `teams.friendly-fire.heart-transfers`. */
    data class Policy(val friendlyFire: Boolean, val teammateHeartTransfers: Boolean)

    enum class Relation {
        /** Not teammates (different teams, one or both without a team, or the same player). */
        NOT_TEAMMATES,
        /** In the same team right now. */
        TEAMMATES,
        /**
         * Were in the same team until a moment ago ([TeamCombatMemory]): one left, was removed, or
         * the team was disbanded within the combat-tag duration. Treated exactly like [TEAMMATES],
         * so an arrow fired before a team change, a potion cloud or a kill right after `/team leave`
         * is still judged by the relationship the fight started with.
         */
        RECENT_TEAMMATES,
        /**
         * Membership can't be read (the team system failed to load). Never taken as proof that the
         * two are on different teams: damage goes through as ordinary PvP (nobody is invented as a
         * teammate), but no heart is transferred.
         */
        UNKNOWN,
    }

    val Relation.teammates: Boolean get() = this == Relation.TEAMMATES || this == Relation.RECENT_TEAMMATES

    /**
     * How [a] and [b] relate. [available] = the membership cache is loaded. [current] is a player's
     * team id now, [recent] the team ids they left within the grace window (still valid at [now]).
     */
    fun relation(
        a: UUID,
        b: UUID,
        available: Boolean,
        current: (UUID) -> String?,
        recent: (UUID) -> List<String>,
    ): Relation {
        if (a == b) return Relation.NOT_TEAMMATES
        if (!available) return Relation.UNKNOWN
        val teamA = current(a)
        val teamB = current(b)
        if (teamA != null && teamA == teamB) return Relation.TEAMMATES
        val recentA = recent(a)
        val recentB = recent(b)
        if (recentA.isEmpty() && recentB.isEmpty()) return Relation.NOT_TEAMMATES
        if (teamB != null && teamB in recentA) return Relation.RECENT_TEAMMATES
        if (teamA != null && teamA in recentB) return Relation.RECENT_TEAMMATES
        if (recentA.any { it in recentB }) return Relation.RECENT_TEAMMATES
        return Relation.NOT_TEAMMATES
    }

    enum class DamageDecision {
        /** Leave the damage alone (other rules still apply). */
        ALLOW,
        /** A teammate's damage with friendly fire off: cancel it. */
        BLOCK_FRIENDLY_FIRE,
    }

    /**
     * Damage from [attacker] (the player the game credits: melee attacker, shooter, TNT igniter,
     * crystal breaker, potion thrower) to the player [victim]. [attacker] null = no player caused it
     * (fall, lava, mobs, ...): always [DamageDecision.ALLOW], so teammates never get immunity to
     * anything that isn't a teammate's attack. [policy] null = lifesteal.yml couldn't be read:
     * nothing is cancelled.
     */
    fun damage(attacker: UUID?, victim: UUID, relation: Relation, policy: Policy?): DamageDecision = when {
        attacker == null || attacker == victim -> DamageDecision.ALLOW
        policy == null || policy.friendlyFire -> DamageDecision.ALLOW
        relation.teammates -> DamageDecision.BLOCK_FRIENDLY_FIRE
        else -> DamageDecision.ALLOW
    }

    enum class HeartCheck {
        /** Not teammates: the ordinary heart rules decide. */
        ELIGIBLE,
        /** Teammates (or just were) and the policy doesn't allow teammate heart transfers. */
        TEAMMATES,
        /** Membership unknown, or the policy couldn't be read: no transfer, nothing guessed. */
        UNKNOWN,
    }

    /**
     * Whether a kill between these two may go on to the heart rules. Teammates only with friendly
     * fire on **and** `heart-transfers: true`; otherwise never, whatever slipped through.
     */
    fun heartCheck(relation: Relation, policy: Policy?): HeartCheck = when {
        relation == Relation.UNKNOWN || policy == null -> HeartCheck.UNKNOWN
        !relation.teammates -> HeartCheck.ELIGIBLE
        policy.friendlyFire && policy.teammateHeartTransfers -> HeartCheck.ELIGIBLE
        else -> HeartCheck.TEAMMATES
    }

    /**
     * Whether a potion with these effect categories is held back from a teammate: any harmful
     * effect (poison, harming, weakness, slowness, ...). Purely beneficial or neutral potions still
     * reach teammates (healing a teammate is fine).
     */
    fun blocksPotion(anyHarmful: Boolean, relation: Relation, policy: Policy?): Boolean =
        anyHarmful && policy != null && !policy.friendlyFire && relation.teammates

    // ── Team changes during combat ──────────────────────────

    /**
     * The membership changes refused while any player they affect is combat-tagged (the existing
     * CombatManager tag), so a fight can't be ended or started by joining, leaving, kicking or
     * disbanding. Leadership transfers and invitations don't change who is a teammate, so they
     * are never refused. Staff `/team admin` actions aren't players' combat moves and aren't
     * refused either; the grace window ([Relation.RECENT_TEAMMATES]) still applies to them.
     */
    enum class TeamChange { ACCEPT, LEAVE, KICK, DISBAND }

    /** Player-facing refusal for [change]. */
    fun combatMessage(change: TeamChange): String = when (change) {
        TeamChange.ACCEPT -> "You can't join a team while in combat."
        TeamChange.LEAVE -> "You can't leave your team while in combat."
        TeamChange.KICK -> "You can't remove a member while you or they are in combat."
        TeamChange.DISBAND -> "You can't disband your team while a member is in combat."
    }
}

/**
 * Who left which team recently (issue #1348), so in-flight and lingering attacks are judged by the
 * relationship the fight started with ([TeamCombatRules.Relation.RECENT_TEAMMATES]). Bounded: at
 * most [perPlayer] entries per player, every entry expires after the grace window, [sweep] drops
 * expired entries (called every second) and [clear] empties it on reload. Entries survive the
 * player quitting, so a rapid disconnect and rejoin can't shorten the window. Thread-safe.
 */
class TeamCombatMemory(private val perPlayer: Int = 4) {

    data class Former(val teamId: String, val until: Long)

    private val entries = ConcurrentHashMap<UUID, List<Former>>()

    /** [uuid] stopped being a member of [teamId] at [now]; remembered until [now] + [graceMillis]. */
    fun record(uuid: UUID, teamId: String, now: Long, graceMillis: Long) {
        if (graceMillis <= 0) return
        val until = now + graceMillis
        entries.compute(uuid) { _, old ->
            val kept = old.orEmpty().filter { it.until > now && it.teamId != teamId }
            (kept + Former(teamId, until)).takeLast(perPlayer)
        }
    }

    /** Team ids [uuid] left whose grace window is still open at [now]. */
    fun recent(uuid: UUID, now: Long): List<String> {
        val list = entries[uuid] ?: return emptyList()
        var result: MutableList<String>? = null
        for (former in list) {
            if (former.until > now) (result ?: ArrayList<String>(list.size).also { result = it }).add(former.teamId)
        }
        return result ?: emptyList()
    }

    fun sweep(now: Long) {
        for (uuid in entries.keys) {
            entries.computeIfPresent(uuid) { _, list -> list.filter { it.until > now }.ifEmpty { null } }
        }
    }

    fun clear() = entries.clear()

    val size: Int get() = entries.size
}
