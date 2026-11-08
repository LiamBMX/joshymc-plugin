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
 */
class ProfileCalculator(
    private val store: ProfileStore,
    private val pairCooldownMillis: Long,
    private val divisionIndex: (team: Boolean, rating: Int) -> Int?,
    private val playtimeAllowed: Boolean,
) {
    data class Periods(
        val windows: List<SeasonWindow>,
        val current: SeasonWindow?,
        val season: StatValues?,
        val lifetime: StatValues,
        /** Finished seasons, newest first. */
        val previous: List<Pair<SeasonWindow, StatValues>>,
        val currentStreak: Long,
    )

    /** One player's numbers: the current season (null when no season exists), all time, and every previous season. */
    fun player(uuid: UUID, includePrevious: Boolean, now: Long = System.currentTimeMillis()): Periods {
        val windows = Seasons.windows(store.seasons())
        val current = Seasons.current(windows)
        val rows = store.pvpRows(uuid)
        val linked = store.linkedPairs(uuid)
        val lifetimePvp = PvpCounting.derive(rows, pairCooldownMillis, linked)[uuid] ?: PvpTotals()
        val lifetime = StatValues(lifetimePvp.asStats() + others(uuid, null, null, lifetime = true))
        fun forWindow(w: SeasonWindow): StatValues {
            val pvp = PvpCounting.derive(PvpCounting.inWindow(rows, w), pairCooldownMillis, linked)[uuid] ?: PvpTotals()
            return StatValues(pvp.asStats() + others(uuid, w, w.id, lifetime = false))
        }
        val season = current?.let { forWindow(it) }
        val previous = if (includePrevious) Seasons.previous(windows).map { it to forWindow(it) } else emptyList()
        return Periods(windows, current, season, lifetime, previous, lifetimePvp.currentStreak)
    }

    private fun others(uuid: UUID, window: SeasonWindow?, seasonId: String?, lifetime: Boolean): Map<ProfileStat, Long> {
        val out = HashMap<ProfileStat, Long>()
        out[ProfileStat.BOUNTY_CLAIMS] = store.bountyClaims(uuid, window)[uuid] ?: 0
        val ranked = store.rankedTotals(uuid, seasonId)[uuid]
        out[ProfileStat.RANKED_MATCHES] = ranked?.matches ?: 0
        out[ProfileStat.RANKED_WINS] = ranked?.wins ?: 0
        out[ProfileStat.PLAYER_DIVISION] = ranked?.peakRating?.let { divisionIndex(false, it)?.toLong() } ?: 0
        out[ProfileStat.TEAM_DIVISION] = store.peakTeamRatings(uuid, seasonId)[uuid]?.let { divisionIndex(true, it)?.toLong() } ?: 0
        val counters = store.counters(uuid, seasonId)[uuid].orEmpty()
        for (stat in ProfileStat.COUNTERS) out[stat] = counters[stat.counter] ?: 0
        out[ProfileStat.DIMENSIONS_EXPLORED] = store.dimensions(uuid, seasonId)[uuid] ?: 0
        if (lifetime) {
            out[ProfileStat.TEAM_JOINED] = if (uuid in store.teamMembers(uuid)) 1 else 0
            if (playtimeAllowed) out[ProfileStat.PLAYTIME_HOURS] = (store.playtimeSeconds(uuid)[uuid] ?: 0) / 3600
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
        ProfileStat.RANKED_MATCHES -> store.rankedTotals(null, window?.id).mapValues { it.value.matches }
        ProfileStat.RANKED_WINS -> store.rankedTotals(null, window?.id).mapValues { it.value.wins }
        ProfileStat.PLAYER_DIVISION -> store.rankedTotals(null, window?.id).mapNotNull { (k, v) -> v.peakRating?.let { r -> divisionIndex(false, r)?.let { k to it.toLong() } } }.toMap()
        ProfileStat.TEAM_DIVISION -> store.peakTeamRatings(null, window?.id).mapNotNull { (k, v) -> divisionIndex(true, v)?.let { k to it.toLong() } }.toMap()
        ProfileStat.TEAM_JOINED -> store.teamMembers(null).associateWith { 1L }
        ProfileStat.DIMENSIONS_EXPLORED -> store.dimensions(null, window?.id)
        ProfileStat.PLAYTIME_HOURS -> if (playtimeAllowed && window == null) store.playtimeSeconds(null).mapValues { it.value / 3600 } else emptyMap()
        ProfileStat.ORES_MINED, ProfileStat.CROPS_HARVESTED, ProfileStat.FISH_CAUGHT, ProfileStat.MOBS_KILLED ->
            store.counters(null, window?.id).mapNotNull { (k, v) -> v[stat.counter]?.let { k to it } }.toMap()
    }

    /** PvP totals of every player in one period, from one read of the history. */
    fun allPvp(window: SeasonWindow?, rows: List<PvpRow>, linked: Set<Pair<UUID, UUID>>): Map<UUID, PvpTotals> =
        PvpCounting.derive(PvpCounting.inWindow(rows, window), pairCooldownMillis, linked)
}
