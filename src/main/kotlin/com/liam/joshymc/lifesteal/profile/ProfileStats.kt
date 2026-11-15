package com.liam.joshymc.lifesteal.profile

import java.util.UUID

/**
 * Every statistic a Lifesteal profile, achievement or leaderboard reads (issue #1429). Bukkit-free.
 *
 * [source] says where the number comes from. Nothing here is a free-standing kill counter: PvP
 * numbers are derived from the heart system's own deduplicated history, ranked numbers from the
 * rating history, bounty numbers from the bounty payouts. Only the gathering activities
 * (ores, crops, fish, mobs, dimensions) are counted by the profile system itself, because no
 * Lifesteal system recorded them before.
 *
 * [seasonal] = the statistic can be split per season. Playtime and team membership exist only as
 * all-time values, so a seasonal achievement or board can't use them.
 */
enum class ProfileStat(val id: String, val label: String, val source: Source, val seasonal: Boolean) {
    PVP_KILLS("pvp-kills", "PvP Kills", Source.HEART_HISTORY, true),
    PVP_DEATHS("pvp-deaths", "PvP Deaths", Source.HEART_HISTORY, true),
    UNIQUE_VICTIMS("unique-victims", "Unique Players Eliminated", Source.HEART_HISTORY, true),
    BEST_KILL_STREAK("best-kill-streak", "Highest Kill Streak", Source.HEART_HISTORY, true),
    BOUNTY_CLAIMS("bounty-claims", "Bounties Claimed", Source.BOUNTIES, true),
    RANKED_MATCHES("ranked-matches", "Rated Ranked Matches", Source.RANKED, true),
    RANKED_WINS("ranked-wins", "Ranked Wins", Source.RANKED, true),
    /** Highest player division reached: 1 = the lowest division. */
    PLAYER_DIVISION("player-division", "Best Player Division", Source.RANKED, true),
    /** Highest division a team reached in a match the player played for it. */
    TEAM_DIVISION("team-division", "Best Team Division", Source.RANKED, true),
    /** 1 once the player has been in a Lifesteal team (current or past membership). */
    TEAM_JOINED("team-joined", "Joined a Team", Source.TEAMS, false),
    ORES_MINED("ores-mined", "Natural Ores Mined", Source.ACTIVITY, true),
    CROPS_HARVESTED("crops-harvested", "Crops Harvested", Source.ACTIVITY, true),
    FISH_CAUGHT("fish-caught", "Fish Caught", Source.ACTIVITY, true),
    MOBS_KILLED("mobs-killed", "Hostile Mobs Defeated", Source.ACTIVITY, true),
    DIMENSIONS_EXPLORED("dimensions-explored", "Dimensions Explored", Source.ACTIVITY, true),
    /**
     * Whole hours of **active** Lifesteal playtime (issue #1538): the heart system's
     * `lifesteal_heart_playtime` (#1457), which only counts time loaded in a Lifesteal world, alive
     * and not AFK. Only where this server is Lifesteal-only.
     */
    PLAYTIME_HOURS("playtime-hours", "Active Hours Played", Source.PLAYTIME, false);

    enum class Source { HEART_HISTORY, BOUNTIES, RANKED, TEAMS, ACTIVITY, PLAYTIME }

    /** Counter name in `lifesteal_profile_counters` (activity statistics only, not dimensions). */
    val counter: String? get() = if (source == Source.ACTIVITY && this != DIMENSIONS_EXPLORED) id else null

    companion object {
        fun of(id: String?): ProfileStat? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
        val COUNTERS: List<ProfileStat> = entries.filter { it.counter != null }
    }
}

/** A player's numbers for one period (a season, or all time). Missing = 0. */
data class StatValues(val values: Map<ProfileStat, Long>) {
    operator fun get(stat: ProfileStat): Long = values[stat] ?: 0L

    fun with(more: Map<ProfileStat, Long>): StatValues = StatValues(values + more)

    companion object {
        val EMPTY = StatValues(emptyMap())
    }
}

/**
 * Where a PvP heart transaction came from, read from its recorded `dedupe_key` prefix (issue #1538).
 * The heart system writes exactly three kinds: `pvp:<boot>:<victim>:<tick>` (an ordinary death,
 * which includes ranked match and staff TEST match kills and combat-tagged environmental deaths),
 * `combatnpc:<session>` (a Combat NPC killed by a player) and `rankedforfeit:<match>:<victim>`
 * (a ranked disconnect forfeit: nobody killed anyone).
 */
enum class PvpSource {
    ORDINARY, COMBAT_NPC, RANKED_FORFEIT,
    /** A key with no known prefix: never a kill (a kill is never inferred from a heart moving). */
    UNKNOWN;

    /** Whether the killer actually killed the victim (and so may earn a kill, a streak step and a victim). */
    val isKill: Boolean get() = this == ORDINARY || this == COMBAT_NPC

    companion object {
        fun of(dedupeKey: String?): PvpSource = when {
            dedupeKey == null -> UNKNOWN
            dedupeKey.startsWith("pvp:") -> ORDINARY
            dedupeKey.startsWith("combatnpc:") -> COMBAT_NPC
            dedupeKey.startsWith("rankedforfeit:") -> RANKED_FORFEIT
            else -> UNKNOWN
        }
    }
}

/** One evaluated PvP death from `lifesteal_heart_transactions` (type PVP), oldest first. */
data class PvpRow(val killer: UUID, val victim: UUID, val outcome: String, val at: Long, val source: PvpSource = PvpSource.ORDINARY)

data class PvpTotals(
    val kills: Long = 0,
    val deaths: Long = 0,
    val uniqueVictims: Long = 0,
    val bestStreak: Long = 0,
    val currentStreak: Long = 0,
    /** Staff evidence (issue #1538): kills of victims who lost nothing that the no-loss limits didn't count. */
    val suppressedNoLossKills: Long = 0,
) {
    fun asStats(): Map<ProfileStat, Long> = mapOf(
        ProfileStat.PVP_KILLS to kills,
        ProfileStat.PVP_DEATHS to deaths,
        ProfileStat.UNIQUE_VICTIMS to uniqueVictims,
        ProfileStat.BEST_KILL_STREAK to bestStreak,
    )
}

/**
 * Which PvP deaths count for profiles, achievements and leaderboards. Bukkit-free.
 *
 * The heart system already decided every attributable Lifesteal PvP death once (UNIQUE dedupe key
 * per death, so a repeated event can never appear twice) and recorded why hearts did or didn't
 * move. A death counts here only when that decision was a real fight:
 * - outcome TRANSFERRED / VICTIM_LOST_ONLY / VICTIM_AT_MINIMUM / KILLER_AT_MAXIMUM. Teammates,
 *   allies, unknown teams, the heart cooldown, before launch / grace period / unknown progression
 *   and missing records never count (they are not legitimate eliminations);
 * - the two accounts never shared a connection (AltManager's `player_connections`): alt boosting
 *   counts for nothing, in either direction;
 * - for kills: at most one counted kill of the same victim per [pairCooldownMillis] (the heart
 *   system's own same-victim cooldown only covers deaths that moved a heart; killing a player on
 *   their last heart, or while at the maximum, moves nothing and would otherwise be farmable).
 *
 * Kill streak = counted kills in a row with no counted PvP death in between (only PvP deaths are
 * recorded by the heart system; environmental deaths don't end a streak).
 *
 * Issue #1538:
 * - Only rows whose recorded source is an actual kill ([PvpSource.isKill]) can give the killer a
 *   kill, a streak step or a unique victim. A ranked disconnect forfeit still counts as the
 *   disconnecting player's death (they can't dodge a death by disconnecting), but never as anyone's
 *   kill. Combat NPC kills keep counting (a player really killed the logger's NPC).
 * - Kills of victims who lost nothing ([NO_LOSS_OUTCOMES]: the victim was at the minimum, or the
 *   heart system recorded KILLER_AT_MAXIMUM) are limited further by [Rules]: a longer same-victim
 *   cooldown and a per-killer cap per rolling 24 hours. Outcomes are read as recorded, never guessed
 *   from heart counts, so a VICTIM_LOST_ONLY kill (a heart was lost) is an ordinary kill.
 */
object PvpCounting {

    val LEGITIMATE_OUTCOMES: Set<String> = setOf("TRANSFERRED", "VICTIM_LOST_ONLY", "VICTIM_AT_MINIMUM", "KILLER_AT_MAXIMUM")

    /** Legitimate outcomes in which the victim kept every heart (cooperative farming risk, issue #1538). */
    val NO_LOSS_OUTCOMES: Set<String> = setOf("VICTIM_AT_MINIMUM", "KILLER_AT_MAXIMUM")

    fun legitimate(outcome: String): Boolean = outcome in LEGITIMATE_OUTCOMES

    fun noLoss(outcome: String): Boolean = outcome in NO_LOSS_OUTCOMES

    /**
     * Counting limits. [pairCooldownMillis]: one counted kill of the same victim per this long.
     * [noLossPairCooldownMillis]: one counted *no-loss* kill of the same victim per this long.
     * [noLossPerDay]: at most this many counted no-loss kills per killer in any 24 hours.
     */
    data class Rules(val pairCooldownMillis: Long, val noLossPairCooldownMillis: Long, val noLossPerDay: Int) {
        companion object {
            /** No extra no-loss limits: the pre-#1538 behaviour, for callers that only know the pair cooldown. */
            fun plain(pairCooldownMillis: Long) = Rules(pairCooldownMillis, pairCooldownMillis, Int.MAX_VALUE)
        }
    }

    const val DAY_MILLIS = 24 * 60 * 60_000L

    /** An unordered account pair, for the shared-connection set. */
    fun pair(a: UUID, b: UUID): Pair<UUID, UUID> = if (a.toString() <= b.toString()) a to b else b to a

    /**
     * Totals per player over [rows] (must be oldest first; only rows inside the period). [linked] =
     * account pairs that shared a connection.
     */
    fun derive(rows: List<PvpRow>, pairCooldownMillis: Long, linked: Set<Pair<UUID, UUID>>): Map<UUID, PvpTotals> =
        derive(rows, Rules.plain(pairCooldownMillis)) { a, b -> pair(a, b) in linked }

    fun derive(rows: List<PvpRow>, rules: Rules, linked: Set<Pair<UUID, UUID>>): Map<UUID, PvpTotals> =
        derive(rows, rules) { a, b -> pair(a, b) in linked }

    /** [linked] answers whether two accounts ever shared a connection. */
    fun derive(rows: List<PvpRow>, rules: Rules, linked: (UUID, UUID) -> Boolean): Map<UUID, PvpTotals> {
        val kills = HashMap<UUID, Long>()
        val deaths = HashMap<UUID, Long>()
        val victims = HashMap<UUID, MutableSet<UUID>>()
        val streak = HashMap<UUID, Long>()
        val best = HashMap<UUID, Long>()
        val suppressed = HashMap<UUID, Long>()
        val lastCounted = HashMap<Pair<UUID, UUID>, Long>()
        val lastNoLoss = HashMap<Pair<UUID, UUID>, Long>()
        val noLossTimes = HashMap<UUID, ArrayDeque<Long>>()
        for (row in rows) {
            if (row.killer == row.victim || !legitimate(row.outcome)) continue
            if (linked(row.killer, row.victim)) continue
            // The death always counts for the victim and ends their streak.
            deaths.merge(row.victim, 1L, Long::plus)
            streak[row.victim] = 0L
            if (!row.source.isKill) continue
            val key = row.killer to row.victim
            val last = lastCounted[key]
            if (last != null && row.at - last < rules.pairCooldownMillis) continue
            if (noLoss(row.outcome)) {
                val lastFree = lastNoLoss[key]
                val window = noLossTimes.getOrPut(row.killer) { ArrayDeque() }
                while (window.isNotEmpty() && row.at - window.first() >= DAY_MILLIS) window.removeFirst()
                if ((lastFree != null && row.at - lastFree < rules.noLossPairCooldownMillis) || window.size >= rules.noLossPerDay) {
                    suppressed.merge(row.killer, 1L, Long::plus)
                    continue
                }
                lastNoLoss[key] = row.at
                window.addLast(row.at)
            }
            lastCounted[key] = row.at
            kills.merge(row.killer, 1L, Long::plus)
            victims.getOrPut(row.killer) { HashSet() }.add(row.victim)
            val now = (streak[row.killer] ?: 0L) + 1
            streak[row.killer] = now
            if (now > (best[row.killer] ?: 0L)) best[row.killer] = now
        }
        val players = kills.keys + deaths.keys + suppressed.keys
        return players.associateWith { uuid ->
            PvpTotals(kills[uuid] ?: 0, deaths[uuid] ?: 0, (victims[uuid]?.size ?: 0).toLong(), best[uuid] ?: 0, streak[uuid] ?: 0, suppressed[uuid] ?: 0)
        }
    }

    /** Only the rows inside [window] (start inclusive, end exclusive; null end = still running). */
    fun inWindow(rows: List<PvpRow>, window: SeasonWindow?): List<PvpRow> =
        if (window == null) rows else rows.filter { window.contains(it.at) }

    /** K/D as a display string: deaths 0 = the kills themselves. */
    fun kdText(kills: Long, deaths: Long): String =
        if (deaths == 0L) "%.2f".format(kills.toDouble()) else "%.2f".format(kills.toDouble() / deaths)

    /** K/D scaled by 100 (for sorting), or null below [minimumFights] kills + deaths. */
    fun kdScaled(kills: Long, deaths: Long, minimumFights: Int): Long? {
        if (kills + deaths < minimumFights.coerceAtLeast(1)) return null
        return if (deaths == 0L) kills * 100 else (kills * 100) / deaths
    }
}

/**
 * Accounts that shared a connection (AltManager's `player_connections`), as account -> connection
 * hashes (issue #1538). Two accounts are linked when their hash sets intersect. Reading the table
 * once and checking each fight is linear; the old `a JOIN b ON hash` self-join was quadratic for a
 * hash many accounts share (a school network, a proxy).
 */
class LinkedAccounts(private val hashes: Map<UUID, Set<String>>) {
    fun linked(a: UUID, b: UUID): Boolean {
        val ha = hashes[a] ?: return false
        val hb = hashes[b] ?: return false
        val (small, large) = if (ha.size <= hb.size) ha to hb else hb to ha
        return small.any { it in large }
    }

    val accounts: Int get() = hashes.size

    companion object {
        val NONE = LinkedAccounts(emptyMap())
    }
}

/**
 * Achievement coin claims (issue #1538, Owner decision for audit P2-05). Bukkit-free. Coins may only
 * be claimed once the account has [requirements] (by default 2 hours of active Lifesteal playtime
 * and 24 hours since its first Lifesteal join), the same facts and the same arithmetic as the heart
 * withdrawal eligibility (#1457, [com.liam.joshymc.lifesteal.hearts.HeartRules.withdrawEligibility]):
 * `lifesteal_heart_playtime.active_millis` and `lifesteal_hearts.created_at`. Completion is never
 * blocked; only the claim of a tier that pays coins waits.
 */
object CoinEligibility {
    /** [firstJoinAt] null = no heart record (never joined Lifesteal here): counts as joining right now. */
    fun check(
        activeMillis: Long,
        firstJoinAt: Long?,
        now: Long,
        requirements: com.liam.joshymc.lifesteal.hearts.HeartRules.WithdrawRequirements?,
    ): com.liam.joshymc.lifesteal.hearts.HeartRules.Eligibility =
        com.liam.joshymc.lifesteal.hearts.HeartRules.withdrawEligibility(activeMillis, firstJoinAt ?: now, now, requirements)

    /** Whether claiming [rewards] pays coins on this server (coins are left out where the economy isn't Lifesteal's). */
    fun paysCoins(rewards: List<AchievementReward>, coinsPaid: Boolean): Boolean =
        coinsPaid && rewards.any { it is AchievementReward.Store && it.spec.kind == com.liam.joshymc.manager.PurchaseRewards.Kind.COINS }
}

/**
 * Team Climber participation (issue #1538, Owner decision for audit P2-02). Bukkit-free. A team's
 * division only counts for a player who played at least [minimumMatches] rated matches **for that
 * team** and won at least [minimumWins] of them; the value is then the team's peak Team Elo after
 * one of those matches. Rated matches only exist in the committed rating history, so cancelled,
 * unrated, TEST and never-started matches and spectators never count.
 */
data class TeamDivisionRule(val minimumMatches: Int, val minimumWins: Int) {
    fun qualifies(matches: Long, wins: Long): Boolean = matches >= minimumMatches && wins >= minimumWins

    companion object {
        val DEFAULT = TeamDivisionRule(5, 1)
    }
}

/** A recorded Lifesteal season (`lifesteal_seasons`). */
data class SeasonRow(val id: String, val launchAt: Long, val status: String)

/** A season's time window: [start] inclusive to [end] exclusive; null end = the season still running. */
data class SeasonWindow(val id: String, val start: Long, val end: Long?, val active: Boolean) {
    fun contains(at: Long): Boolean = at >= start && (end == null || at < end)
}

/**
 * Seasons as time windows. Bukkit-free. A season runs from its launch until the next recorded
 * season launches; the ACTIVE one never ends here. JoshyMC has no season rollover yet (one season
 * exists and is never closed automatically): when staff record a new one, the previous season's
 * window closes at the new launch, so its numbers stay exactly as they were. Nothing is reset or
 * rewritten: seasonal numbers are always re-derived from history inside the window.
 */
object Seasons {
    /** Counters recorded while no season exists at all. Counted only in all-time numbers. */
    const val PRESEASON = "preseason"
    /** `lifesteal_achievements.period` of permanent achievements. */
    const val PERMANENT = "permanent"

    fun windows(rows: List<SeasonRow>): List<SeasonWindow> {
        val sorted = rows.sortedWith(compareBy<SeasonRow> { it.launchAt }.thenBy { it.id })
        return sorted.mapIndexed { i, row ->
            val next = sorted.getOrNull(i + 1)
            SeasonWindow(row.id, row.launchAt, if (row.status == "ACTIVE") null else next?.launchAt, row.status == "ACTIVE")
        }
    }

    fun current(windows: List<SeasonWindow>): SeasonWindow? = windows.firstOrNull { it.active }

    /** Finished seasons, newest first. */
    fun previous(windows: List<SeasonWindow>): List<SeasonWindow> = windows.filter { !it.active }.sortedByDescending { it.start }

    /** The season id activity counters recorded now belong to. */
    fun counterSeason(current: SeasonWindow?): String = current?.id ?: PRESEASON
}

/**
 * Leaderboard ranking. Bukkit-free. Higher is better; equal values share a place (1, 2, 2, 4) and
 * are listed by UUID so the order never flickers between refreshes.
 */
object Leaderboards {
    data class Entry(val uuid: UUID, val value: Long)
    data class Ranked(val position: Int, val uuid: UUID, val value: Long)

    fun rank(values: Map<UUID, Long>, minimumValue: Long = 1): List<Ranked> {
        val sorted = values.filter { it.value >= minimumValue }
            .entries.sortedWith(compareByDescending<Map.Entry<UUID, Long>> { it.value }.thenBy { it.key.toString() })
        val out = ArrayList<Ranked>(sorted.size)
        var position = 0
        var previous: Long? = null
        for ((i, e) in sorted.withIndex()) {
            if (previous == null || e.value != previous) position = i + 1
            previous = e.value
            out += Ranked(position, e.key, e.value)
        }
        return out
    }

    fun pages(total: Int, perPage: Int): Int = if (total <= 0) 1 else (total + perPage - 1) / perPage

    fun page(list: List<Ranked>, page: Int, perPage: Int): List<Ranked> {
        val p = page.coerceIn(0, pages(list.size, perPage) - 1)
        return list.drop(p * perPage).take(perPage)
    }

    fun positionOf(list: List<Ranked>, uuid: UUID): Ranked? = list.firstOrNull { it.uuid == uuid }
}
