package com.liam.joshymc.lifesteal.profile

import java.util.UUID

/**
 * Builds [StatValues] from [ProfileStore] reads. Bukkit-free (runs on a database thread).
 *
 * Everything is re-derived from history on every call: there is no stored "season total" that a
 * season change would have to reset, and nothing can drift from the source systems.
 *
 * [divisionIndex] maps (team?, rating) to the 1-based division, or null while divisions are off.
 * [playtimeAllowed] = this server's playtime is Lifesteal's (a Lifesteal-only server).
 * [pvpRules] = the PvP counting limits (same-victim cooldown, no-loss limits, issue #1538).
 * [teamRule] = the Team Climber participation rule (issue #1538).
 *
 * One instance is made per profile load / evaluation / leaderboard rebuild, so the per-season
 * ranked totals it memoizes ([rankedTotalsFor]) never outlive one read.
 */
class ProfileCalculator(
    private val store: ProfileStore,
    private val pvpRules: PvpCounting.Rules,
    private val divisionIndex: (team: Boolean, rating: Int) -> Int?,
    private val playtimeAllowed: Boolean,
    private val teamRule: TeamDivisionRule = TeamDivisionRule.DEFAULT,
) {
    /** Only the plain same-victim cooldown (no extra no-loss limits). */
    constructor(store: ProfileStore, pairCooldownMillis: Long, divisionIndex: (team: Boolean, rating: Int) -> Int?, playtimeAllowed: Boolean, teamRule: TeamDivisionRule = TeamDivisionRule.DEFAULT) :
        this(store, PvpCounting.Rules.plain(pairCooldownMillis), divisionIndex, playtimeAllowed, teamRule)

    data class Periods(
        val windows: List<SeasonWindow>,
        val current: SeasonWindow?,
        val season: StatValues?,
        val lifetime: StatValues,
        /** Finished seasons, newest first. */
        val previous: List<Pair<SeasonWindow, StatValues>>,
        val currentStreak: Long,
        /** Staff evidence: lifetime no-loss kills the farming limits didn't count. */
        val suppressedNoLossKills: Long = 0,
    )

    private val rankedMemo = HashMap<String, Map<UUID, ProfileStore.RankedTotals>>()
    private val teamMemo = HashMap<String, Map<UUID, Int>>()

    /** Every player's ranked totals for [seasonId] (null = all seasons), read once per instance. */
    private fun rankedTotalsFor(seasonId: String?): Map<UUID, ProfileStore.RankedTotals> =
        rankedMemo.getOrPut(seasonId ?: ALL) { store.rankedTotals(null, seasonId) }

    private fun teamPeaksFor(seasonId: String?): Map<UUID, Int> =
        teamMemo.getOrPut(seasonId ?: ALL) { store.peakTeamRatings(null, seasonId, teamRule) }

    /** One player's numbers: the current season (null when no season exists), all time, and every previous season. */
    fun player(uuid: UUID, includePrevious: Boolean, now: Long = System.currentTimeMillis()): Periods {
        val windows = Seasons.windows(store.seasons())
        val current = Seasons.current(windows)
        val rows = store.pvpRows(uuid)
        val linked = store.connectionHashes(uuid)
        val lifetimePvp = PvpCounting.derive(rows, pvpRules, linked::linked)[uuid] ?: PvpTotals()
        val lifetime = StatValues(lifetimePvp.asStats() + others(uuid, null, null, lifetime = true))
        fun forWindow(w: SeasonWindow): StatValues {
            val pvp = PvpCounting.derive(PvpCounting.inWindow(rows, w), pvpRules, linked::linked)[uuid] ?: PvpTotals()
            return StatValues(pvp.asStats() + others(uuid, w, w.id, lifetime = false))
        }
        val season = current?.let { forWindow(it) }
        val previous = if (includePrevious) Seasons.previous(windows).map { it to forWindow(it) } else emptyList()
        return Periods(windows, current, season, lifetime, previous, lifetimePvp.currentStreak, lifetimePvp.suppressedNoLossKills)
    }

    private fun others(uuid: UUID, window: SeasonWindow?, seasonId: String?, lifetime: Boolean): Map<ProfileStat, Long> {
        val out = HashMap<ProfileStat, Long>()
        out[ProfileStat.BOUNTY_CLAIMS] = store.bountyClaims(uuid, window)[uuid] ?: 0
        val ranked = store.rankedTotals(uuid, seasonId)[uuid]
        out[ProfileStat.RANKED_MATCHES] = ranked?.matches ?: 0
        out[ProfileStat.RANKED_WINS] = ranked?.wins ?: 0
        out[ProfileStat.PLAYER_DIVISION] = ranked?.peakRating?.let { divisionIndex(false, it)?.toLong() } ?: 0
        out[ProfileStat.TEAM_DIVISION] = store.peakTeamRatings(uuid, seasonId, teamRule)[uuid]?.let { divisionIndex(true, it)?.toLong() } ?: 0
        val counters = store.counters(uuid, seasonId)[uuid].orEmpty()
        for (stat in ProfileStat.COUNTERS) out[stat] = counters[stat.counter] ?: 0
        out[ProfileStat.DIMENSIONS_EXPLORED] = store.dimensions(uuid, seasonId)[uuid] ?: 0
        if (lifetime) {
            out[ProfileStat.TEAM_JOINED] = if (uuid in store.teamMembers(uuid)) 1 else 0
            if (playtimeAllowed) out[ProfileStat.PLAYTIME_HOURS] = (store.activePlaytimeMillis(uuid)[uuid] ?: 0) / HOUR_MILLIS
        }
        return out
    }

    /** Every player's value of [stat] for one period ([window] null = all time). For leaderboards. */
    fun all(stat: ProfileStat, window: SeasonWindow?, pvp: Map<UUID, PvpTotals>?): Map<UUID, Long> = when (stat) {
        ProfileStat.PVP_KILLS -> pvp.orEmpty().mapValues { it.value.kills }
        ProfileStat.PVP_DEATHS -> pvp.orEmpty().mapValues { it.value.deaths }
        ProfileStat.UNIQUE_VICTIMS -> pvp.orEmpty().mapValues { it.value.uniqueVictims }
        ProfileStat.BEST_KILL_STREAK -> pvp.orEmpty().mapValues { it.value.bestStreak }
        ProfileStat.BOUNTY_CLAIMS -> store.bountyClaims(null, window)
        ProfileStat.RANKED_MATCHES -> rankedTotalsFor(window?.id).mapValues { it.value.matches }
        ProfileStat.RANKED_WINS -> rankedTotalsFor(window?.id).mapValues { it.value.wins }
        ProfileStat.PLAYER_DIVISION -> rankedTotalsFor(window?.id).mapNotNull { (k, v) -> v.peakRating?.let { r -> divisionIndex(false, r)?.let { k to it.toLong() } } }.toMap()
        ProfileStat.TEAM_DIVISION -> teamPeaksFor(window?.id).mapNotNull { (k, v) -> divisionIndex(true, v)?.let { k to it.toLong() } }.toMap()
        ProfileStat.TEAM_JOINED -> store.teamMembers(null).associateWith { 1L }
        ProfileStat.DIMENSIONS_EXPLORED -> store.dimensions(null, window?.id)
        ProfileStat.PLAYTIME_HOURS -> if (playtimeAllowed && window == null) store.activePlaytimeMillis(null).mapValues { it.value / HOUR_MILLIS } else emptyMap()
        ProfileStat.ORES_MINED, ProfileStat.CROPS_HARVESTED, ProfileStat.FISH_CAUGHT, ProfileStat.MOBS_KILLED ->
            store.counters(null, window?.id).mapNotNull { (k, v) -> v[stat.counter]?.let { k to it } }.toMap()
    }

    /** PvP totals of every player in one period, from one read of the history. */
    fun allPvp(window: SeasonWindow?, rows: List<PvpRow>, linked: Set<Pair<UUID, UUID>>): Map<UUID, PvpTotals> =
        PvpCounting.derive(PvpCounting.inWindow(rows, window), pvpRules, linked)

    fun allPvp(window: SeasonWindow?, rows: List<PvpRow>, linked: LinkedAccounts): Map<UUID, PvpTotals> =
        PvpCounting.derive(PvpCounting.inWindow(rows, window), pvpRules, linked::linked)

    private companion object {
        const val ALL = "\u0000all"
        const val HOUR_MILLIS = 3_600_000L
    }
}
