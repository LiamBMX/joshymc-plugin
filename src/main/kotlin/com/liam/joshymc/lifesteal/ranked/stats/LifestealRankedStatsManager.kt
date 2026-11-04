package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Ranked leaderboards, divisions and competitive statistics (issue #1367), Lifesteal only.
 *
 * Owns no table and never writes a rating: every number is read from what the ranked match system
 * and the rating service ([com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager]) already
 * committed, through [RankedStatsStore], off the main thread. Divisions are computed from the
 * current rating with the [DivisionTable] from `lifesteal.yml` and are never stored.
 *
 * Leaderboard pages are cached briefly (bounded, `leaderboards.cache-seconds`) and the whole cache is
 * dropped as soon as a rating change commits ([onRatingsCommitted]); a load that started before the
 * drop is never cached (generation counter), so a stale page can't come back.
 *
 * Where Lifesteal isn't hosted nothing runs and every read answers "unavailable".
 */
class LifestealRankedStatsManager(private val plugin: Joshymc) {

    private val store = RankedStatsStore(plugin.databaseManager)
    @Volatile private var indexesReady = false
    private val generation = AtomicLong()

    private data class CacheKey(val season: String, val type: RatingType, val page: Int, val perPage: Int, val minimum: Int)
    private data class Cached(val page: RankedStatsStore.Page, val at: Long)

    /** Main thread only; bounded. */
    private val pages = object : LinkedHashMap<CacheKey, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, Cached>?): Boolean = size > PAGE_CACHE_SIZE
    }

    val hosted: Boolean get() = plugin.lifestealRatingManager.hosted

    /** The division table, or null while divisions are disabled or invalid. */
    val divisions: DivisionTable? get() = if (hosted) plugin.lifestealConfigManager.divisions.table else null
    val divisionStatus: DivisionConfig.Status get() = plugin.lifestealConfigManager.divisions.status
    val leaderboardSettings: DivisionConfig.Leaderboards? get() = if (hosted) plugin.lifestealConfigManager.leaderboards.settings else null

    fun unavailableReason(): String? = plugin.lifestealRatingManager.unavailableReason()
        ?: if (!indexesReady) "Ranked statistics are unavailable (database error); see the console." else null

    fun leaderboardProblem(): String? = unavailableReason()
        ?: if (leaderboardSettings == null) "Ranked leaderboards are unavailable (lifesteal.yml ranked-team-wars.leaderboards is invalid)." else null

    fun initial(type: RatingType): Int? = plugin.lifestealRatingManager.policy()?.params?.initial(type)

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after the rating service (its tables exist by then). */
    fun refresh() {
        pages.clear()
        generation.incrementAndGet()
        if (!hosted || !plugin.lifestealRatingManager.ready) return
        if (!indexesReady) {
            try {
                store.createIndexes()
                indexesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[RankedStats] Could not create the statistics indexes; leaderboards and statistics stay off until this is fixed: ${e.message}")
                return
            }
        }
        val table = divisions
        plugin.logger.info("[RankedStats] Divisions ${if (table == null) divisionStatus.label else table.divisions.joinToString { "${it.displayName} ${it.playerMinimum}/${it.teamMinimum}" }}; " +
            (leaderboardSettings?.let { "leaderboards need ${it.minimumPlayerMatches} (players) / ${it.minimumTeamMatches} (teams) rated matches." } ?: "leaderboards unavailable."))
    }

    fun stop() {
        pages.clear()
        generation.incrementAndGet()
    }

    /** A rating transaction committed (called on the main thread): no cached page may outlive it. */
    fun onRatingsCommitted() {
        generation.incrementAndGet()
        pages.clear()
    }

    // ── Reads (async; [done] runs on the main thread) ───────

    data class Board(
        val page: RankedStatsStore.Page,
        val minimumMatches: Int,
        /** The viewer's (player boards) or the viewer's team's (team boards) place, null when not listed. */
        val viewerPosition: Int?,
        val viewerSubject: String?,
        val viewerMatches: Int,
    )

    fun loadLeaderboard(type: RatingType, page: Int, perPage: Int, viewer: UUID?, done: (Board?, String?) -> Unit) {
        leaderboardProblem()?.let { return done(null, it) }
        val season = plugin.lifestealRatingManager.currentSeason() ?: return done(null, NO_SEASON)
        val settings = leaderboardSettings ?: return done(null, leaderboardProblem())
        val minimum = if (type == RatingType.TEAM_ELO) settings.minimumTeamMatches else settings.minimumPlayerMatches
        val subject = when (type) {
            RatingType.PLAYER_ELO -> viewer?.toString()
            RatingType.TEAM_ELO -> viewer?.let { plugin.lifestealTeamManager.teamOf(it)?.id }
        }
        val key = CacheKey(season, type, page, perPage, minimum)
        val now = System.currentTimeMillis()
        val cached = pages[key]?.takeIf { settings.cacheSeconds > 0 && now - it.at < settings.cacheSeconds * 1000L }
        val gen = generation.get()
        read({ s ->
            val p = cached?.page ?: s.page(season, type, minimum, page, perPage)
            val position = subject?.let { s.position(season, type, it, minimum) }
            val matches = subject?.let { s.entry(season, type, it)?.matches } ?: 0
            Board(p, minimum, position, subject, matches)
        }) { board, problem ->
            if (board != null && cached == null && settings.cacheSeconds > 0 && gen == generation.get()) pages[key] = Cached(board.page, now)
            done(board, problem)
        }
    }

    data class PlayerStats(
        val uuid: UUID,
        val seasonId: String,
        val summary: RankedStatistics.Summary,
        val position: Int?,
        val minimumMatches: Int?,
        val teamId: String?,
        val teamName: String?,
        val teamRating: Int?,
        val recent: List<RankedStatsStore.MatchLine>,
    )

    fun loadPlayerStats(uuid: UUID, recent: Int, done: (PlayerStats?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        val season = plugin.lifestealRatingManager.currentSeason() ?: return done(null, NO_SEASON)
        val initialPlayer = initial(RatingType.PLAYER_ELO) ?: return done(null, unavailableReason() ?: "Ranked ratings are unavailable.")
        val initialTeam = initial(RatingType.TEAM_ELO) ?: initialPlayer
        val minimum = leaderboardSettings?.minimumPlayerMatches
        val team = plugin.lifestealTeamManager.teamOf(uuid)
        val teamId = team?.id
        val teamName = team?.name
        read({ s ->
            PlayerStats(
                uuid, season,
                RankedStatistics.summarize(s.points(RatingType.PLAYER_ELO, uuid.toString(), season), initialPlayer),
                minimum?.let { s.position(season, RatingType.PLAYER_ELO, uuid.toString(), it) }, minimum,
                teamId, teamName, teamId?.let { s.entry(season, RatingType.TEAM_ELO, it)?.rating ?: initialTeam },
                if (recent > 0) s.playerHistory(uuid, 0, recent).lines else emptyList(),
            )
        }, done)
    }

    data class TeamStats(
        val teamId: String,
        val seasonId: String,
        val name: String,
        val leader: UUID?,
        val active: Boolean,
        val summary: RankedStatistics.Summary,
        val position: Int?,
        val minimumMatches: Int?,
        val recent: List<RankedStatsStore.MatchLine>,
    )

    /**
     * Team [teamId]'s statistics. Players reach only active teams (by name); staff diagnostics may
     * pass a disbanded team's id: its records are kept and shown as disbanded, never ranked.
     */
    fun loadTeamStats(teamId: String, recent: Int, done: (TeamStats?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        val season = plugin.lifestealRatingManager.currentSeason() ?: return done(null, NO_SEASON)
        val initialTeam = initial(RatingType.TEAM_ELO) ?: return done(null, unavailableReason() ?: "Ranked ratings are unavailable.")
        val minimum = leaderboardSettings?.minimumTeamMatches
        val active = plugin.lifestealTeamManager.team(teamId)
        read({ s ->
            val status = s.teamStatus(teamId) ?: return@read null
            val stored = s.entry(season, RatingType.TEAM_ELO, teamId)
            val lines = if (recent > 0) s.teamHistory(teamId, 0, recent).lines else emptyList()
            val name = active?.name ?: stored?.name ?: lines.firstOrNull()?.ownTeamName ?: "Team ${teamId.take(8)}"
            TeamStats(
                teamId, season, name, active?.leader, status == "ACTIVE" && active != null,
                RankedStatistics.summarize(s.points(RatingType.TEAM_ELO, teamId, season), initialTeam),
                minimum?.let { s.position(season, RatingType.TEAM_ELO, teamId, it) }, minimum, lines,
            )
        }) { stats, problem -> done(stats, problem ?: if (stats == null) "No Lifesteal team has that id." else null) }
    }

    fun loadPlayerHistory(uuid: UUID, page: Int, perPage: Int, done: (RankedStatsStore.HistoryPage?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        read({ s -> s.playerHistory(uuid, page, perPage) }, done)
    }

    fun loadTeamHistory(teamId: String, page: Int, perPage: Int, done: (RankedStatsStore.HistoryPage?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        read({ s -> s.teamHistory(teamId, page, perPage) }, done)
    }

    /** Staff diagnostics: runs [block] against the store off the main thread. */
    fun <T> read(block: (RankedStatsStore) -> T?, done: (T?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val value = try { block(store) } catch (e: Exception) {
                plugin.logger.warning("[RankedStats] Couldn't read ranked statistics: ${e.message}")
                onMain { done(null, "Couldn't read the ranked statistics right now; please try again.") }
                return@Runnable
            }
            onMain { done(value, null) }
        })
    }

    private fun onMain(block: () -> Unit) {
        try {
            if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable { block() })
        } catch (_: Exception) {
            // Shutting down.
        }
    }

    // ── Divisions (display) ─────────────────────────────────

    fun division(type: RatingType, rating: Int): Division? = divisions?.of(type, rating)

    /** "Gold" in the division's colour, or "-" while divisions are off. */
    fun divisionName(type: RatingType, rating: Int): Component =
        division(type, rating)?.let { label(it) } ?: Component.text("-", NamedTextColor.DARK_GRAY)

    /**
     * The extra notice lines for one committed change (issue #1367): promotion / demotion of the
     * player's own division, and of their team's for a participant. Sent with the match result,
     * which the rating service delivers once (also on the next join when offline).
     */
    fun changeLines(playerBefore: Int, playerAfter: Int, teamName: String?, teamBefore: Int?, teamAfter: Int?): List<Component> {
        val table = divisions ?: return emptyList()
        val lines = mutableListOf<Component>()
        when (val c = table.change(RatingType.PLAYER_ELO, playerBefore, playerAfter)) {
            is DivisionTable.Change.Promotion -> {
                lines += Component.text("RANKED PROMOTION!", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                lines += Component.text("You have reached ", NamedTextColor.GREEN).append(label(c.to)).append(Component.text(" Division!", NamedTextColor.GREEN))
            }
            is DivisionTable.Change.Demotion -> lines += Component.text("Your ranked division has changed to ", NamedTextColor.RED).append(label(c.to)).append(Component.text(".", NamedTextColor.RED))
            null -> Unit
        }
        if (teamBefore != null && teamAfter != null) {
            val team = teamName ?: "Your team"
            when (val c = table.change(RatingType.TEAM_ELO, teamBefore, teamAfter)) {
                is DivisionTable.Change.Promotion -> lines += Component.text("$team reached ", NamedTextColor.GREEN).append(label(c.to)).append(Component.text(" Division!", NamedTextColor.GREEN))
                is DivisionTable.Change.Demotion -> lines += Component.text("$team's division has changed to ", NamedTextColor.RED).append(label(c.to)).append(Component.text(".", NamedTextColor.RED))
                null -> Unit
            }
        }
        return lines
    }

    companion object {
        const val PAGE_CACHE_SIZE = 64
        const val NO_SEASON = "There is no active Lifesteal season, so there are no ranked statistics yet."

        fun color(division: Division): TextColor =
            TextColor.fromHexString(division.color) ?: NamedTextColor.NAMES.value(division.color) ?: NamedTextColor.WHITE

        fun label(division: Division): Component = Component.text(division.displayName, color(division)).decorate(TextDecoration.BOLD)
    }
}
