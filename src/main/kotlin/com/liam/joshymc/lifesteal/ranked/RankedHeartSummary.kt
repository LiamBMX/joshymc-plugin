package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.lifesteal.hearts.HeartRules
import java.util.UUID

/**
 * Issue #1474: what each heart decision of one ranked match did, for the end-of-match summary.
 * Bukkit-free and presentation only: an [Entry] is built from the [HeartRules.Decision] the
 * normal heart service (`HeartStore.transferPvp`) already returned for that kill or forfeit, so
 * nothing here decides, re-runs or changes a heart. Kept on the in-memory match (bounded by
 * [MAX_ENTRIES]) and dropped with it.
 *
 * Players see names and the public reason only (cooldown, last heart, maximum, teammates, ...);
 * nothing about connections, alts or staff reviews ever appears here.
 */
object RankedHeartSummary {

    /** Hard cap per match: a 5v5 has at most 9 deaths plus forfeits, so this is never reached in play. */
    const val MAX_ENTRIES = 24

    /** Lines shown before the rest is folded into "... and N more". */
    const val MAX_LINES = 12

    /** [ENVIRONMENT] (issue #1478): an environmental/self death credited to the opponent who hit the victim last. */
    enum class Source { KILL, FORFEIT, ENVIRONMENT }

    enum class Outcome(val moved: Boolean, val label: String) {
        STOLEN(true, "heart stolen"),
        VICTIM_LOST(true, "a heart was lost"),
        LAST_HEART(false, "no heart, victim was on their last heart"),
        KILLER_AT_MAXIMUM(false, "no heart, killer is at the maximum"),
        COOLDOWN(false, "no heart, same-victim cooldown"),
        TEAMMATES(false, "no heart, teammates"),
        ALLIES(false, "no heart, allies"),
        TEAM_UNKNOWN(false, "no heart, team data unavailable"),
        GRACE_PERIOD(false, "no heart, grace period"),
        NOT_LAUNCHED(false, "no heart, season not launched"),
        PROGRESSION_UNKNOWN(false, "no heart, season state unknown"),
        /** Issue #1487: the killer used staff abilities or over-limit enchantments in the fight. */
        INELIGIBLE_ATTACKER(false, "no heart, staff abilities or over-limit gear used"),
        /** The heart service couldn't evaluate it (missing record, rolled-back error): nothing moved. */
        UNAVAILABLE(false, "no heart, heart data unavailable");

        companion object {
            fun of(decision: HeartRules.Decision): Outcome = when (decision) {
                is HeartRules.Decision.Transfer -> STOLEN
                is HeartRules.Decision.VictimLosesOnly -> VICTIM_LOST
                HeartRules.Decision.VictimAtMinimum -> LAST_HEART
                HeartRules.Decision.KillerAtMaximum -> KILLER_AT_MAXIMUM
                is HeartRules.Decision.Cooldown -> COOLDOWN
                HeartRules.Decision.Teammates -> TEAMMATES
                HeartRules.Decision.Allies -> ALLIES
                HeartRules.Decision.TeamUnknown -> TEAM_UNKNOWN
                HeartRules.Decision.GracePeriod -> GRACE_PERIOD
                HeartRules.Decision.NotLaunched -> NOT_LAUNCHED
                HeartRules.Decision.ProgressionUnknown -> PROGRESSION_UNKNOWN
                is HeartRules.Decision.IneligibleAttacker -> INELIGIBLE_ATTACKER
            }
        }
    }

    data class Entry(
        val killer: UUID,
        val killerName: String,
        val victim: UUID,
        val victimName: String,
        val outcome: Outcome,
        val source: Source = Source.KILL,
    )

    data class Line(val text: String, val moved: Boolean)

    /**
     * Adds [entry] unless the same killer/victim/source is already listed (a repeated death
     * event or retry reports the same decision twice; the heart service deduped it already) or
     * the list is full. Returns the new list.
     */
    fun add(entries: List<Entry>, entry: Entry): List<Entry> {
        if (entries.size >= MAX_ENTRIES) return entries
        if (entries.any { it.victim == entry.victim && it.killer == entry.killer && it.source == entry.source }) return entries
        return entries + entry
    }

    /** One line per decision, in the order they happened. */
    fun line(entry: Entry): Line = when (entry.source) {
        Source.KILL -> Line("${entry.killerName} killed ${entry.victimName}: ${entry.outcome.label}", entry.outcome.moved)
        Source.FORFEIT -> Line("${entry.victimName} forfeited to ${entry.killerName}: ${entry.outcome.label}", entry.outcome.moved)
        Source.ENVIRONMENT -> Line("${entry.victimName} died after a hit from ${entry.killerName}: ${entry.outcome.label}", entry.outcome.moved)
    }

    /**
     * The summary section: a header with the totals, then one line per decision (at most
     * [MAX_LINES], the rest counted). Empty when no heart decision was made in the match.
     */
    fun lines(entries: List<Entry>): List<Line> {
        if (entries.isEmpty()) return emptyList()
        val moved = entries.count { it.outcome.moved }
        val header = Line("Hearts this match: $moved of ${entries.size} moved a heart.", moved > 0)
        val shown = entries.take(MAX_LINES).map { line(it) }
        val rest = entries.size - shown.size
        return listOf(header) + shown + if (rest > 0) listOf(Line("... and $rest more.", false)) else emptyList()
    }
}
