package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.milestone.CombatRules
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * Quest cycles (issue #1425), Bukkit-free. A cycle is identified by the local date its reset fell
 * on in the configured zone (default America/New_York): `daily:2026-11-08`, `weekly:2026-11-09`
 * (the Monday). Start and end are real instants from [ZonedDateTime], so DST changes give a 23h or
 * 25h day instead of a shifted reset. A reset time inside a DST gap happens right after the gap.
 */
object QuestCycles {

    data class Cycle(val period: QuestPeriod, val id: String, val date: LocalDate, val startMillis: Long, val endMillis: Long) {
        fun remainingMillis(now: Long): Long = (endMillis - now).coerceAtLeast(0)
        fun contains(now: Long): Boolean = now in startMillis until endMillis
    }

    private fun at(date: LocalDate, time: LocalTime, zone: ZoneId): Long = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()

    fun daily(now: Long, zone: ZoneId, reset: LocalTime): Cycle {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date = if (now >= at(today, reset, zone)) today else today.minusDays(1)
        return Cycle(QuestPeriod.DAILY, "daily:$date", date, at(date, reset, zone), at(date.plusDays(1), reset, zone))
    }

    fun weekly(now: Long, zone: ZoneId, day: DayOfWeek, reset: LocalTime): Cycle {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        var date = today.with(TemporalAdjusters.previousOrSame(day))
        if (now < at(date, reset, zone)) date = date.minusWeeks(1)
        return Cycle(QuestPeriod.WEEKLY, "weekly:$date", date, at(date, reset, zone), at(date.plusWeeks(1), reset, zone))
    }

    fun current(period: QuestPeriod, now: Long, s: QuestSettings): Cycle = when (period) {
        QuestPeriod.DAILY -> daily(now, s.zone, s.dailyResetTime)
        QuestPeriod.WEEKLY -> weekly(now, s.zone, s.weeklyResetDay, s.weeklyResetTime)
    }

    /** "5h 12m", "2d 3h", "45s". */
    fun formatRemaining(millis: Long): String {
        val total = (millis.coerceAtLeast(0) + 999) / 1000
        val d = total / 86_400
        val h = (total % 86_400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}

/**
 * Picks quests (issue #1425), Bukkit-free. Weighted sampling without replacement: every pick is a
 * distinct template, never one whose objective or `group` is already on the board this cycle, and
 * only templates the player is eligible for (enabled, team requirement). Never repeats or bends a
 * rule to fill a slot: too few candidates = fewer quests.
 */
object QuestSelection {

    fun eligible(t: QuestTemplate, hasTeam: Boolean): Boolean = t.enabled && t.team.allows(hasTeam)

    /** What is already on a player's board this cycle (plus ids to avoid, e.g. quests rerolled away). */
    data class Taken(val ids: Set<String> = emptySet(), val objectives: Set<QuestObjective> = emptySet(), val groups: Set<String> = emptySet()) {
        companion object {
            fun of(templates: List<QuestTemplate>, excludedIds: Set<String> = emptySet()) = Taken(
                templates.mapTo(HashSet()) { it.id } + excludedIds,
                templates.mapTo(HashSet()) { it.objective },
                templates.mapNotNullTo(HashSet()) { it.group },
            )
        }
    }

    fun pick(
        pool: List<QuestTemplate>,
        count: Int,
        hasTeam: Boolean,
        taken: Taken = Taken(),
        random: Random = Random.Default,
    ): List<QuestTemplate> {
        val usedObjectives = HashSet(taken.objectives)
        val usedGroups = HashSet(taken.groups)
        val candidates = pool.filter { eligible(it, hasTeam) && it.id !in taken.ids }.toMutableList()
        val out = ArrayList<QuestTemplate>(count)
        while (out.size < count) {
            candidates.removeAll { it.objective in usedObjectives || (it.group != null && it.group in usedGroups) }
            if (candidates.isEmpty()) break
            val total = candidates.sumOf { it.weight.toLong() }
            var roll = random.nextLong(total)
            var chosen = candidates.last()
            for (c in candidates) {
                if (roll < c.weight) { chosen = c; break }
                roll -= c.weight
            }
            out += chosen
            candidates.remove(chosen)
            usedObjectives += chosen.objective
            chosen.group?.let { usedGroups += it }
        }
        return out
    }
}

/** Reroll pricing and checks (issue #1425), Bukkit-free. The store re-runs [price] inside its transaction. */
object RerollRules {

    sealed interface Price {
        data object Free : Price
        data class Credits(val amount: Double) : Price
        /** Out of free and paid rerolls this cycle. */
        data object LimitReached : Price
    }

    /**
     * [freeUsed]/[paidUsed] = rerolls already made this cycle. [ineligible] = a team-only quest held
     * without a team, which is free (and doesn't use up anything) when the setting allows it.
     */
    fun price(s: RerollSettings, freeUsed: Int, paidUsed: Int, ineligible: Boolean, freeWhenIneligible: Boolean): Price = when {
        ineligible && freeWhenIneligible -> Price.Free
        freeUsed < s.freePerCycle -> Price.Free
        paidUsed < s.maxPaidPerCycle -> if (s.creditCost <= 0.0) Price.Free else Price.Credits(s.creditCost)
        else -> Price.LimitReached
    }

    enum class Refusal(val message: String) {
        NOT_FOUND("That quest isn't on your board anymore."),
        STALE("That quest changed while the menu was open. The menu has been refreshed."),
        EXPIRED("That quest's cycle has ended."),
        CLAIMED("Claimed quests can't be rerolled."),
        COMPLETED("That quest is complete: claim it instead of rerolling it."),
        LIMIT("You have no rerolls left for this cycle."),
        PRICE_CHANGED("The reroll price changed. The menu has been refreshed."),
        NO_REPLACEMENT("There is no other quest you can get right now."),
        INSUFFICIENT_CREDITS("You don't have enough Credits for that reroll."),
    }
}

/**
 * PvP farming rules for quest progress (issue #1425), Bukkit-free. The environment checks (self,
 * NPCs, events, arenas, Ranked matches and arenas, teammates, allies, unknown teams) are the bounty
 * system's [com.liam.joshymc.lifesteal.bounty.BountyRules.eligibility]; on top of it the
 * Milestones' [CombatRules.pvp] (same-victim cooldown, hourly cap, idle victim) and
 * [CombatRules.connection] (shared connection = possible alt) decide.
 */
object QuestPvpRules {

    fun kill(
        teammates: Boolean,
        victimIdle: Boolean,
        lastKillOfVictimAt: Long?,
        now: Long,
        a: QuestAntiAbuse,
        killsInLastHour: Int,
    ): CombatRules.PvpDecision = CombatRules.pvp(
        sameAccount = false,
        teammates = teammates,
        victimIdle = victimIdle,
        lastKillOfVictimAt = lastKillOfVictimAt,
        now = now,
        cooldownMillis = a.pvpSameVictimCooldownMillis,
        killsInLastHour = killsInLastHour,
        maxPerHour = a.pvpMaxKillsPerHour,
    )

    /** Final step once the shared-connection lookup answered (null = it failed: never counts). */
    fun connection(shared: Boolean?, blockShared: Boolean): CombatRules.PvpDecision =
        if (!blockShared && shared != null) CombatRules.PvpDecision.COUNT else CombatRules.connection(shared)

    /** A kill is a won fight when the victim had hit the killer within the window. */
    fun victory(victimHitKillerAt: Long?, killAt: Long, windowMillis: Long): Boolean =
        victimHitKillerAt != null && killAt - victimHitKillerAt in 0..windowMillis

    /** Teammates of the killer who hit the victim within the window get an assist. */
    fun assist(hitAt: Long?, killAt: Long, windowMillis: Long): Boolean = hitAt != null && killAt - hitAt in 0..windowMillis

    /** Damage (stored tenths) still countable for one attacker against one victim this hour. */
    fun damageAllowance(countedThisHour: Long, capHearts: Int): Long =
        if (capHearts <= 0) Long.MAX_VALUE else (capHearts * QuestObjective.PVP_DAMAGE.scale - countedThisHour).coerceAtLeast(0)
}

/** Progress text (issue #1425), Bukkit-free: never shows "full" before a quest really completes. */
object QuestProgress {

    fun displayed(stored: Long, scale: Long): Long = stored / scale

    fun percent(stored: Long, target: Long): Int = when {
        target <= 0 -> 100
        stored >= target -> 100
        else -> ((stored * 100) / target).toInt().coerceIn(0, 99)
    }

    fun bar(stored: Long, target: Long, width: Int = 20): String {
        val filled = when {
            target <= 0 || stored >= target -> width
            else -> ((stored * width) / target).toInt().coerceIn(0, width - 1)
        }
        return "&a" + "|".repeat(filled) + "&8" + "|".repeat(width - filled)
    }
}

/** The quest menu (issue #1425), Bukkit-free slot layout: 54 slots, daily rows on top, weekly below. */
object LifestealQuestLayout {
    const val SIZE = 54
    const val HEADER = 4
    const val DAILY_CLOCK = 10
    val DAILY_QUESTS = listOf(12, 13, 14)
    val DAILY_REROLLS = listOf(21, 22, 23)
    const val WEEKLY_CLOCK = 28
    val WEEKLY_QUESTS = listOf(30, 31, 32)
    val WEEKLY_REROLLS = listOf(39, 40, 41)
    const val CLOSE = 49
    const val CONFIRM_SIZE = 27
    const val CONFIRM_PREVIEW = 13
    const val CONFIRM_YES = 11
    const val CONFIRM_NO = 15

    fun quests(period: QuestPeriod): List<Int> = if (period == QuestPeriod.DAILY) DAILY_QUESTS else WEEKLY_QUESTS
    fun rerolls(period: QuestPeriod): List<Int> = if (period == QuestPeriod.DAILY) DAILY_REROLLS else WEEKLY_REROLLS
    fun clock(period: QuestPeriod): Int = if (period == QuestPeriod.DAILY) DAILY_CLOCK else WEEKLY_CLOCK

    val ALL: List<Int> get() = listOf(HEADER, DAILY_CLOCK, WEEKLY_CLOCK, CLOSE) + DAILY_QUESTS + DAILY_REROLLS + WEEKLY_QUESTS + WEEKLY_REROLLS
}
