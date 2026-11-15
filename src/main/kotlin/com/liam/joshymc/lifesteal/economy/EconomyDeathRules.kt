package com.liam.joshymc.lifesteal.economy

import com.liam.joshymc.lifesteal.hearts.HeartRules
import java.util.UUID

/**
 * Who gets the coins and the bounty for one real Lifesteal player death (issue #1548,
 * LS-ECO-04 and LS-ECO-07), Bukkit-free. [EconomyDeathAttribution] feeds it a copy taken at
 * LOWEST of the PlayerDeathEvent and reads the answer at MONITOR, once per death, for both PvP
 * coin stealing and bounty claims, so the two always agree on the killer and on "ranked".
 *
 * Read-only reuse of the heart system's policy: [HeartRules.attribute] decides who the direct
 * killer is and [HeartRules.combatTagKiller] (issue #1461) decides the combat-tag credit. The
 * heart rules and store are not changed.
 */
object EconomyDeathRules {

    /**
     * The victim's state copied at LOWEST of their death, before CombatListener clears the tag
     * (NORMAL) and the ranked death handler takes them out of their match (HIGHEST). [tick] makes
     * sure a copy is only used by the death it was taken for.
     */
    data class Capture(
        val tick: Int,
        /** Wall-clock time of the copy, the "now" the hits' ages are measured against. */
        val at: Long,
        /** The victim's combat tag expiry (null = not tagged). */
        val tagExpiresAt: Long?,
        /** Eligible PvP hits the combat tag recorded on the victim (`CombatManager.recentPvpAttackers`). */
        val hits: List<HeartRules.RecentHit>,
        /** The ranked match holding the victim (null = none). */
        val rankedMatch: String?,
        /** The victim was being handled as a combat logger. */
        val combatLogged: Boolean,
    )

    /**
     * The ranked match a death belongs to (null = an ordinary death). The victim's LOWEST copy
     * (same tick only) wins, so a defeat at HIGHEST can't turn a ranked death into ordinary PvP;
     * otherwise the live lookups ([victimHeld], [killerHeld]) and the killer's own copy from a death
     * earlier in the same tick (a killer who was defeated a moment before their victim died, e.g.
     * by a projectile already in flight) are enough. A stale copy never makes a later death ranked.
     */
    fun rankedMatch(victim: Capture?, killer: Capture?, currentTick: Int, victimHeld: String?, killerHeld: String?): String? =
        victim?.takeIf { it.tick == currentTick }?.rankedMatch
            ?: victimHeld
            ?: killer?.takeIf { it.tick == currentTick }?.rankedMatch
            ?: killerHeld

    /** Whether [rankedMatch] may move PvP coins: never while `pvp-coins.ranked-enabled` is false. */
    fun rankedCoinsAllowed(rankedMatch: String?, rankedEnabled: Boolean): Boolean = rankedMatch == null || rankedEnabled

    /** The economy's view of one death. [killer] is null unless [attribution] is [HeartRules.Attribution.PVP_KILL]. */
    data class Resolution(
        val victim: UUID,
        val tick: Int,
        val attribution: HeartRules.Attribution,
        val killer: UUID?,
        val rankedMatch: String?,
        /** The killer was credited through the victim's combat tag (environmental or self-inflicted final damage). */
        val viaCombatTag: Boolean,
        /** The final damage type without the `minecraft:` prefix, for logs. */
        val cause: String?,
    )

    /** `/kill`: never credited to anybody (the heart system's rule). */
    const val GENERIC_KILL = "minecraft:generic_kill"

    /**
     * Decides the economy killer of one death.
     *
     * [direct] is [HeartRules.attribute] of the final damage's causing player [directKiller]. A
     * real PvP kill keeps its killer. An [HeartRules.Attribution.ENVIRONMENT] or
     * [HeartRules.Attribution.SELF] death of a still combat-tagged victim is credited through
     * [HeartRules.combatTagKiller] to the most recent attacker of that tag who passes [eligible]
     * (a valid Lifesteal killer whose competitive eligibility holds); never for a combat logger, a
     * ranked match death or `/kill`. Without valid attribution nobody is credited. [reattribute]
     * re-runs [HeartRules.attribute] for the credited attacker.
     */
    fun resolve(
        victim: UUID,
        direct: HeartRules.Attribution,
        directKiller: UUID?,
        capture: Capture?,
        currentTick: Int,
        now: Long,
        tagMillis: Long,
        rankedMatch: String?,
        combatLoggedNow: Boolean,
        damageType: String?,
        eligible: (UUID) -> Boolean,
        reattribute: (UUID) -> HeartRules.Attribution,
    ): Resolution {
        val cause = damageType?.removePrefix("minecraft:")
        if (direct == HeartRules.Attribution.PVP_KILL && directKiller != null) {
            return Resolution(victim, currentTick, direct, directKiller, rankedMatch, viaCombatTag = false, cause = cause)
        }
        val tag = capture?.takeIf { it.tick == currentTick }
        val credited = HeartRules.combatTagKiller(
            direct = direct,
            victim = victim,
            tagExpiresAt = tag?.tagExpiresAt,
            hits = tag?.hits.orEmpty(),
            now = tag?.at ?: now,
            tagMillis = tagMillis,
            excluded = combatLoggedNow || tag?.combatLogged == true || rankedMatch != null || damageType == GENERIC_KILL,
            eligible = eligible,
        )
        if (credited != null) {
            val attribution = reattribute(credited)
            if (attribution == HeartRules.Attribution.PVP_KILL) {
                return Resolution(victim, currentTick, attribution, credited, rankedMatch, viaCombatTag = true, cause = cause)
            }
        }
        return Resolution(victim, currentTick, direct, null, rankedMatch, viaCombatTag = false, cause = cause)
    }
}
