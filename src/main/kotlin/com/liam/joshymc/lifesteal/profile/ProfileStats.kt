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
    /** Whole hours played on this server; only where this server is Lifesteal-only. */
    PLAYTIME_HOURS("playtime-hours", "Hours Played", Source.PLAYTIME, false);

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

/** One evaluated PvP death from `lifesteal_heart_transactions` (type PVP), oldest first. */
data class PvpRow(val killer: UUID, val victim: UUID, val outcome: String, val at: Long)

data class PvpTotals(
    val kills: Long = 0,
    val deaths: Long = 0,
    val uniqueVictims: Long = 0,
    val bestStreak: Long = 0,
    val currentStreak: Long = 0,
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
 */
object PvpCounting {

    val LEGITIMATE_OUTCOMES: Set<String> = setOf("TRANSFERRED", "VICTIM_LOST_ONLY", "VICTIM_AT_MINIMUM", "KILLER_AT_MAXIMUM")

    fun legitimate(outcome: String): Boolean = outcome in LEGITIMATE_OUTCOMES

    /** An unordered account pair, for the shared-connection set. */
    fun pair(a: UUID, b: UUID): Pair<UUID, UUID> = if (a.toString() <= b.toString()) a to b else b to a

    /**
     * Totals per player over [rows] (must be oldest first; only rows inside the period). [linked] =
     * account pairs that shared a connection.
     */
    fun derive(rows: List<PvpRow>, pairCooldownMillis: Long, linked: Set<Pair<UUID, UUID>>): Map<UUID, PvpTotals> {
        val kills = HashMap<UUID, Long>()
        val deaths = HashMap<UUID, Long>()
        val victims = HashMap<UUID, MutableSet<UUID>>()
        val streak = HashMap<UUID, Long>()
        val best = HashMap<UUID, Long>()
        val lastCounted = HashMap<Pair<UUID, UUID>, Long>()
        for (row in rows) {
            if (row.killer == row.victim || !legitimate(row.outcome)) continue
            if (pair(row.killer, row.victim) in linked) continue
            // The death always counts for the victim and ends their streak.
            deaths.merge(row.victim, 1L, Long::plus)
            streak[row.victim] = 0L
            val key = row.killer to row.victim
            val last = lastCounted[key]
            if (last != null && row.at - last < pairCooldownMillis) continue
            lastCounted[key] = row.at
            kills.merge(row.killer, 1L, Long::plus)
            victims.getOrPut(row.killer) { HashSet() }.add(row.victim)
            val now = (streak[row.killer] ?: 0L) + 1
            streak[row.killer] = now
            if (now > (best[row.killer] ?: 0L)) best[row.killer] = now
        }
        val players = kills.keys + deaths.keys
        return players.associateWith { uuid ->
            PvpTotals(kills[uuid] ?: 0, deaths[uuid] ?: 0, (victims[uuid]?.size ?: 0).toLong(), best[uuid] ?: 0, streak[uuid] ?: 0)
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
