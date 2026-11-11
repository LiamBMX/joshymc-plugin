package com.liam.joshymc.lifesteal.bounty

import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import java.util.UUID

/**
 * Bounty decisions that need no database (issue #1401), Bukkit-free. The database-backed checks
 * (active bounty, expiration, stored team membership, cooldown) are in [BountyStore], inside the
 * same transaction that moves the money.
 */
object BountyRules {

    // ── Contributions ───────────────────────────────────────

    /** Why [cents] can't be contributed (before any database work), or null. */
    fun amountProblem(cents: Long, minimumCents: Long): String? = when {
        cents <= 0L -> "The amount must be more than zero."
        cents < minimumCents -> "The minimum contribution is ${BountyMoney.format(minimumCents)}."
        cents > BountyMoney.MAX_CENTS -> "That amount is too large to process."
        else -> null
    }

    /** Player text for an amount that didn't parse. */
    fun parseProblem(parsed: BountyMoney.Parsed, minimumCents: Long): String? = when (parsed) {
        is BountyMoney.Parsed.Ok -> amountProblem(parsed.cents, minimumCents)
        BountyMoney.Parsed.Invalid -> "That isn't a valid amount. Use a number like 10000, 25k or 1.5m."
        BountyMoney.Parsed.Negative -> "The amount can't be negative."
        BountyMoney.Parsed.Zero -> "The amount must be more than zero."
        BountyMoney.Parsed.TooPrecise -> "Amounts can have at most two decimals."
        BountyMoney.Parsed.TooLarge -> "That amount is too large to process."
    }

    // ── Claims ──────────────────────────────────────────────

    /** Team relation at the moment of death, from the Lifesteal team service. */
    enum class Team { NOT_TEAMMATES, TEAMMATES, RECENT_TEAMMATES, UNKNOWN }

    /** Everything about a death the claim rules look at, captured on the main thread when it happens. */
    data class Death(
        val victim: UUID,
        /** `damageSource.causingEntity` when it is a player (the same attribution the heart system uses). */
        val causingPlayer: UUID?,
        val killerIsNpc: Boolean = false,
        val killerOnline: Boolean = true,
        val victimInLifesteal: Boolean = true,
        val killerInLifesteal: Boolean = true,
        /** Either player is in the event world, a hosted event's running session or the old mini-games. */
        val inEvent: Boolean = false,
        /** Either player is inside a Survival PvP arena zone (ArenaManager). */
        val inArena: Boolean = false,
        /** Either player is held by a Ranked Team Wars match (participant or spectator). */
        val inRankedMatch: Boolean = false,
        /** The death or the killer is inside a Ranked Team Wars arena boundary (even without a match). */
        val inRankedArena: Boolean = false,
        val team: Team = Team.NOT_TEAMMATES,
        /**
         * Issue #1487 (D-H4): the killer's competitive eligibility (Creative, Spectator, God Mode,
         * Vanish, Mod Mode, over-limit enchantments, now or in earlier hits on this victim).
         */
        val attacker: CompetitiveEligibility.Verdict = CompetitiveEligibility.Verdict.Eligible,
    )

    enum class Refusal(val player: String?) {
        /** Not a player kill (environment, mobs, suicide): nobody to tell. */
        NOT_PLAYER_KILL(null),
        SELF(null),
        NPC(null),
        NOT_LIFESTEAL(null),
        EVENT("Bounties can't be claimed in events."),
        ARENA("Bounties can't be claimed in PvP arenas."),
        RANKED("Bounties can't be claimed in Ranked Team Wars."),
        TEAMMATE("You can't claim a bounty on a teammate."),
        TEAM_UNKNOWN("The bounty couldn't be claimed right now (team data unavailable). It stays active."),
        /** Issue #1487: the tracker tells the killer (throttled), so no second message here. */
        INELIGIBLE_ATTACKER(null),
    }

    sealed interface Eligibility {
        data class Eligible(val killer: UUID) : Eligibility
        data class Refused(val reason: Refusal, val killer: UUID?) : Eligibility
    }

    /**
     * Whether [d] is an ordinary Lifesteal PvP kill that may claim a bounty. Checked in a fixed order;
     * the first failing rule decides. A refusal never consumes the bounty.
     */
    fun eligibility(d: Death): Eligibility {
        val killer = d.causingPlayer ?: return Eligibility.Refused(Refusal.NOT_PLAYER_KILL, null)
        if (killer == d.victim) return Eligibility.Refused(Refusal.SELF, killer)
        if (d.killerIsNpc) return Eligibility.Refused(Refusal.NPC, killer)
        if (!d.killerOnline) return Eligibility.Refused(Refusal.NOT_PLAYER_KILL, killer)
        if (!d.victimInLifesteal || !d.killerInLifesteal) return Eligibility.Refused(Refusal.NOT_LIFESTEAL, killer)
        if (d.attacker is CompetitiveEligibility.Verdict.Ineligible) return Eligibility.Refused(Refusal.INELIGIBLE_ATTACKER, killer)
        if (d.inRankedMatch || d.inRankedArena) return Eligibility.Refused(Refusal.RANKED, killer)
        if (d.inEvent) return Eligibility.Refused(Refusal.EVENT, killer)
        if (d.inArena) return Eligibility.Refused(Refusal.ARENA, killer)
        return when (d.team) {
            Team.TEAMMATES, Team.RECENT_TEAMMATES -> Eligibility.Refused(Refusal.TEAMMATE, killer)
            Team.UNKNOWN -> Eligibility.Refused(Refusal.TEAM_UNKNOWN, killer)
            Team.NOT_TEAMMATES -> Eligibility.Eligible(killer)
        }
    }

    /** Time left on a killer-target cooldown (0 = none). */
    fun cooldownRemaining(lastClaimAt: Long?, now: Long, cooldownMillis: Long): Long {
        if (lastClaimAt == null || cooldownMillis <= 0L) return 0L
        val until = lastClaimAt + cooldownMillis
        return if (now < until) until - now else 0L
    }

    /** Suspicious when this pair already reached [threshold] - 1 earlier kills inside the window (this one makes [threshold]). */
    fun repeatedKills(earlierKills: Int, threshold: Int): Boolean = earlierKills + 1 >= threshold

    // ── Presentation ────────────────────────────────────────

    /** Highest total first; equal totals by creation (oldest first), then id, so the order never flickers. */
    val ORDER: Comparator<BountyStore.ActiveBounty> =
        compareByDescending<BountyStore.ActiveBounty> { it.totalCents }.thenBy { it.createdAt }.thenBy { it.id }

    fun pageCount(items: Int, perPage: Int): Int = if (items <= 0) 1 else (items + perPage - 1) / perPage

    fun clampPage(page: Int, items: Int, perPage: Int): Int = page.coerceIn(0, pageCount(items, perPage) - 1)

    fun <T> page(list: List<T>, page: Int, perPage: Int): List<T> {
        val p = clampPage(page, list.size, perPage)
        return list.drop(p * perPage).take(perPage)
    }

    /** `6d 23h`, `4h 10m`, `12m`, `< 1m`; "Expired" at or below zero. */
    fun formatRemaining(millis: Long): String {
        if (millis <= 0L) return "Expired"
        val minutes = millis / 60_000L
        if (minutes < 1) return "< 1m"
        val days = minutes / (24 * 60)
        val hours = (minutes / 60) % 24
        val mins = minutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }

    /** Replaces `{key}` placeholders in a message template. */
    fun fill(template: String, values: Map<String, String>): String =
        values.entries.fold(template) { acc, (k, v) -> acc.replace("{$k}", v) }
}
