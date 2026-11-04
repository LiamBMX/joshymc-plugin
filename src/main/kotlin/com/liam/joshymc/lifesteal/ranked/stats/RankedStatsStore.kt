package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.ranked.rating.RatingOutcome
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Read-only queries for leaderboards, statistics and match history (issue #1367), Bukkit-free.
 * It owns **no table** and writes **no row**: everything is read from the tables the ranked match
 * system (`lifesteal_ranked_matches` / `_participants` / `_arenas`), the rating service
 * (`lifesteal_ranked_ratings` / `_rating_history` / `_rating_results`) and the Lifesteal teams
 * (`lifesteal_teams`) already keep. [createIndexes] only adds indexes so these reads stay indexed.
 *
 * Leaderboard order (deterministic, the same in every query): rating DESC, wins DESC, the earlier
 * last rated match first (`updated_at` ASC: who got there first), then the subject id. Ratings are
 * per season, so seasons never mix. Team boards only list ACTIVE teams (a disbanded team keeps its
 * rows and history but isn't ranked), and a team is keyed by its id, so a new team with an old
 * name starts from nothing.
 */
class RankedStatsStore(private val db: SqliteDatabase) {

    fun createIndexes() {
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_ratings_order ON lifesteal_ranked_ratings(season_id, rating_type, rating DESC, wins DESC, updated_at, subject_id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_participants_uuid ON lifesteal_ranked_participants(uuid, match_id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_matches_team_a ON lifesteal_ranked_matches(team_a_id, ended_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_matches_team_b ON lifesteal_ranked_matches(team_b_id, ended_at)")
    }

    // ── Leaderboards ────────────────────────────────────────

    data class Entry(
        val position: Int,
        val type: RatingType,
        val subjectId: String,
        val rating: Int,
        val wins: Int,
        val losses: Int,
        val draws: Int,
        val matches: Int,
        /** Teams: the team's current name (renames show at once); players: null (resolved by UUID). */
        val name: String?,
        /** Teams: the current leader. */
        val leader: UUID?,
        val updatedAt: Long,
    ) {
        val winPercent: String get() = RankedStatistics.winPercent(wins, matches)
    }

    data class Page(val type: RatingType, val seasonId: String, val page: Int, val perPage: Int, val total: Int, val entries: List<Entry>) {
        val pages: Int get() = RankedStatistics.pages(total, perPage)
    }

    private fun from(type: RatingType): String = if (type == RatingType.TEAM_ELO)
        "lifesteal_ranked_ratings r JOIN lifesteal_teams t ON t.id = r.subject_id AND t.status = 'ACTIVE'"
    else "lifesteal_ranked_ratings r"

    private fun columns(type: RatingType): String = if (type == RatingType.TEAM_ELO) "r.*, t.name AS team_name, t.leader AS team_leader" else "r.*, NULL AS team_name, NULL AS team_leader"

    /** Eligible entries of [season] (at least [minimumMatches] rated matches), counted for paging. */
    fun count(season: String, type: RatingType, minimumMatches: Int): Int = db.queryFirst(
        "SELECT COUNT(*) AS n FROM ${from(type)} WHERE r.season_id = ? AND r.rating_type = ? AND r.matches >= ?",
        season, type.name, minimumMatches,
    ) { it.getInt("n") } ?: 0

    /** One page (0-based [page]) of the board; reads only that page's rows. */
    fun page(season: String, type: RatingType, minimumMatches: Int, page: Int, perPage: Int): Page {
        require(perPage in 1..100) { "page size must be 1..100" }
        val total = count(season, type, minimumMatches)
        val p = RankedStatistics.clampPage(page, total, perPage)
        val offset = p * perPage
        var position = offset
        val entries = db.query(
            """SELECT ${columns(type)} FROM ${from(type)} WHERE r.season_id = ? AND r.rating_type = ? AND r.matches >= ?
               ORDER BY r.rating DESC, r.wins DESC, r.updated_at ASC, r.subject_id ASC LIMIT ? OFFSET ?""",
            season, type.name, minimumMatches, perPage, offset,
        ) { rs -> readEntry(rs, type, ++position) }
        return Page(type, season, p, perPage, total, entries)
    }

    /**
     * [subject]'s place on the board (1 = first), or null when they aren't listed (no rated match
     * yet, fewer than [minimumMatches], or a team that isn't active). Counted with the board's own
     * order, so it always matches the page they appear on.
     */
    fun position(season: String, type: RatingType, subject: String, minimumMatches: Int): Int? {
        val own = db.queryFirst(
            "SELECT ${columns(type)} FROM ${from(type)} WHERE r.season_id = ? AND r.rating_type = ? AND r.subject_id = ? AND r.matches >= ?",
            season, type.name, subject, minimumMatches,
        ) { readEntry(it, type, 0) } ?: return null
        val ahead = db.queryFirst(
            """SELECT COUNT(*) AS n FROM ${from(type)} WHERE r.season_id = ? AND r.rating_type = ? AND r.matches >= ? AND (
                 r.rating > ? OR (r.rating = ? AND (r.wins > ? OR (r.wins = ? AND (r.updated_at < ? OR (r.updated_at = ? AND r.subject_id < ?))))))""",
            season, type.name, minimumMatches, own.rating, own.rating, own.wins, own.wins, own.updatedAt, own.updatedAt, own.subjectId,
        ) { it.getInt("n") } ?: return null
        return ahead + 1
    }

    /** The rating row of [subject] in [season] (any number of matches), with the team's name for teams. */
    fun entry(season: String, type: RatingType, subject: String): Entry? = db.queryFirst(
        "SELECT ${columns(type)} FROM ${from(type)} WHERE r.season_id = ? AND r.rating_type = ? AND r.subject_id = ?",
        season, type.name, subject,
    ) { readEntry(it, type, 0) }

    private fun readEntry(rs: ResultSet, type: RatingType, position: Int) = Entry(
        position = position,
        type = type,
        subjectId = rs.getString("subject_id"),
        rating = rs.getInt("rating"),
        wins = rs.getInt("wins"),
        losses = rs.getInt("losses"),
        draws = rs.getInt("draws"),
        matches = rs.getInt("matches"),
        name = rs.getString("team_name") ?: rs.getString("display_name")?.takeIf { type == RatingType.TEAM_ELO },
        leader = rs.getString("team_leader")?.let { runCatching { UUID.fromString(it) }.getOrNull() },
        updatedAt = rs.getLong("updated_at"),
    )

    // ── Statistics ──────────────────────────────────────────

    /** [subject]'s committed rating changes in [season], oldest first (at most [limit]). */
    fun points(type: RatingType, subject: String, season: String, limit: Int = MAX_POINTS): List<RankedStatistics.Point> = db.query(
        """SELECT match_id, previous, delta, new, outcome, at FROM lifesteal_ranked_rating_history
           WHERE rating_type = ? AND subject_id = ? AND season_id = ? ORDER BY id LIMIT ?""",
        type.name, subject, season, limit,
    ) { rs -> RankedStatistics.Point(rs.getString("match_id"), rs.getInt("previous"), rs.getInt("delta"), rs.getInt("new"), RatingOutcome.parse(rs.getString("outcome")), rs.getLong("at")) }

    /** Seasons [subject] has rated matches in, oldest first (kept forever; for history views). */
    fun seasonsOf(type: RatingType, subject: String): List<String> = db.query(
        "SELECT season_id FROM lifesteal_ranked_ratings WHERE rating_type = ? AND subject_id = ? ORDER BY created_at, season_id", type.name, subject,
    ) { it.getString("season_id") }

    /** ACTIVE / DISBANDED, or null for an id no team ever had. */
    fun teamStatus(teamId: String): String? = db.queryFirst("SELECT status FROM lifesteal_teams WHERE id = ?", teamId) { it.getString("status") }

    // ── Match history ───────────────────────────────────────

    enum class RatedState(val label: String) {
        RATED("Rated"),
        UNRATED("Unrated"),
        /** Completed, but the rating hasn't committed yet (it will be, by the rating sweep). */
        PENDING("Rating pending"),
    }

    data class MatchLine(
        val matchId: String,
        val seasonId: String,
        val size: Int,
        val kind: String,
        val arena: String,
        val ownTeamId: String,
        val ownTeamName: String,
        val opponentTeamId: String,
        val opponentTeamName: String,
        /** WIN / LOSS from the viewer's side (a completed match always has a winner; draws aren't produced). */
        val outcome: RatingOutcome,
        val endedAt: Long,
        val durationMillis: Long?,
        val rated: RatedState,
        val unratedReason: String?,
        /** Null unless [rated] is RATED. */
        val playerDelta: Int?,
        val playerAfter: Int?,
        val teamDelta: Int?,
        val teamAfter: Int?,
    )

    data class HistoryPage(val page: Int, val perPage: Int, val total: Int, val lines: List<MatchLine>) {
        val pages: Int get() = RankedStatistics.pages(total, perPage)
    }

    /**
     * Matches that count as results: COMPLETED, not a staff test match (by `kind` or, for rows
     * from before `kind` existed, the test flag), and not rated INVALID. Unrated matches (e.g.
     * challenges while they aren't rated) are listed and marked; cancelled ones aren't results.
     */
    private val resultFilter = """m.state = 'COMPLETED' AND m.test = 0 AND (m.kind IS NULL OR m.kind <> 'TEST')
        AND m.winner_side IN ('A', 'B') AND (res.status IS NULL OR res.status <> 'INVALID')"""

    /** One page of [uuid]'s match results, newest first, every season. */
    fun playerHistory(uuid: UUID, page: Int, perPage: Int): HistoryPage {
        val subject = uuid.toString()
        val total = db.queryFirst(
            """SELECT COUNT(*) AS n FROM lifesteal_ranked_participants p JOIN lifesteal_ranked_matches m ON m.match_id = p.match_id
               LEFT JOIN lifesteal_ranked_rating_results res ON res.match_id = m.match_id WHERE p.uuid = ? AND $resultFilter""",
            subject,
        ) { it.getInt("n") } ?: 0
        val p = RankedStatistics.clampPage(page, total, perPage)
        val lines = db.query(
            """SELECT m.*, p.side AS own_side, p.team_id AS own_team, res.status AS res_status, res.reason AS res_reason, a.display_name AS arena_name,
                 hp.delta AS p_delta, hp.new AS p_new, ht.delta AS t_delta, ht.new AS t_new
               FROM lifesteal_ranked_participants p
               JOIN lifesteal_ranked_matches m ON m.match_id = p.match_id
               LEFT JOIN lifesteal_ranked_rating_results res ON res.match_id = m.match_id
               LEFT JOIN lifesteal_ranked_arenas a ON a.arena_id = m.arena_id
               LEFT JOIN lifesteal_ranked_rating_history hp ON hp.match_id = m.match_id AND hp.rating_type = 'PLAYER_ELO' AND hp.subject_id = p.uuid
               LEFT JOIN lifesteal_ranked_rating_history ht ON ht.match_id = m.match_id AND ht.rating_type = 'TEAM_ELO' AND ht.subject_id = p.team_id
               WHERE p.uuid = ? AND $resultFilter
               ORDER BY m.ended_at DESC, m.match_id DESC LIMIT ? OFFSET ?""",
            subject, perPage, p * perPage,
        ) { rs -> readLine(rs, side(rs.getString("own_side")), withPlayer = true) }.filterNotNull()
        return HistoryPage(p, perPage, total, lines)
    }

    /** One page of team [teamId]'s match results (by its stable id, so renames and disbanding keep them). */
    fun teamHistory(teamId: String, page: Int, perPage: Int): HistoryPage {
        val total = db.queryFirst(
            """SELECT COUNT(*) AS n FROM lifesteal_ranked_matches m LEFT JOIN lifesteal_ranked_rating_results res ON res.match_id = m.match_id
               WHERE (m.team_a_id = ? OR m.team_b_id = ?) AND $resultFilter""",
            teamId, teamId,
        ) { it.getInt("n") } ?: 0
        val p = RankedStatistics.clampPage(page, total, perPage)
        val lines = db.query(
            """SELECT m.*, CASE WHEN m.team_a_id = ? THEN 'A' ELSE 'B' END AS own_side, res.status AS res_status, res.reason AS res_reason,
                 a.display_name AS arena_name, NULL AS p_delta, NULL AS p_new, ht.delta AS t_delta, ht.new AS t_new
               FROM lifesteal_ranked_matches m
               LEFT JOIN lifesteal_ranked_rating_results res ON res.match_id = m.match_id
               LEFT JOIN lifesteal_ranked_arenas a ON a.arena_id = m.arena_id
               LEFT JOIN lifesteal_ranked_rating_history ht ON ht.match_id = m.match_id AND ht.rating_type = 'TEAM_ELO' AND ht.subject_id = ?
               WHERE (m.team_a_id = ? OR m.team_b_id = ?) AND $resultFilter
               ORDER BY m.ended_at DESC, m.match_id DESC LIMIT ? OFFSET ?""",
            teamId, teamId, teamId, teamId, perPage, p * perPage,
        ) { rs -> readLine(rs, side(rs.getString("own_side")), withPlayer = false) }.filterNotNull()
        return HistoryPage(p, perPage, total, lines)
    }

    private fun side(raw: String?): Side? = raw?.let { Side.parse(it) }

    private fun readLine(rs: ResultSet, own: Side?, withPlayer: Boolean): MatchLine? {
        own ?: return null
        val winner = side(rs.getString("winner_side")) ?: return null
        val aId = rs.getString("team_a_id")
        val aName = rs.getString("team_a_name")
        val bId = rs.getString("team_b_id")
        val bName = rs.getString("team_b_name")
        val status = rs.getString("res_status")
        val rated = when (status) {
            "RATED" -> RatedState.RATED
            "UNRATED" -> RatedState.UNRATED
            else -> RatedState.PENDING // no result yet, or FAILED (retried)
        }
        val started = rs.getLong("started_at").takeIf { !rs.wasNull() }
        val ended = rs.getLong("ended_at").takeIf { !rs.wasNull() } ?: rs.getLong("created_at")
        fun int(col: String): Int? = rs.getInt(col).takeIf { !rs.wasNull() }
        return MatchLine(
            matchId = rs.getString("match_id"),
            seasonId = rs.getString("season_id"),
            size = rs.getInt("size"),
            kind = rs.getString("kind") ?: "TEST",
            arena = rs.getString("arena_name") ?: rs.getString("arena_id"),
            ownTeamId = if (own == Side.A) aId else bId,
            ownTeamName = if (own == Side.A) aName else bName,
            opponentTeamId = if (own == Side.A) bId else aId,
            opponentTeamName = if (own == Side.A) bName else aName,
            outcome = if (winner == own) RatingOutcome.WIN else RatingOutcome.LOSS,
            endedAt = ended,
            durationMillis = started?.let { (ended - it).coerceAtLeast(0L) },
            rated = rated,
            unratedReason = if (rated == RatedState.UNRATED) rs.getString("res_reason") else null,
            playerDelta = if (withPlayer && rated == RatedState.RATED) int("p_delta") else null,
            playerAfter = if (withPlayer && rated == RatedState.RATED) int("p_new") else null,
            teamDelta = if (rated == RatedState.RATED) int("t_delta") else null,
            teamAfter = if (rated == RatedState.RATED) int("t_new") else null,
        )
    }

    // ── Diagnostics ─────────────────────────────────────────

    /**
     * Read-only consistency check of [season]: every rating row must equal what its history says
     * (match/win/loss/draw counts and the latest value), every history row must belong to a RATED
     * result of a COMPLETED, non-test match, and every RATED result must have history. Returns at
     * most [limit] human-readable problems; empty = consistent. Nothing is ever corrected here:
     * ratings only change through the rating transaction (database triggers enforce it).
     */
    fun consistency(season: String, limit: Int = 20): List<String> {
        val problems = mutableListOf<String>()
        problems += db.query(
            """SELECT r.rating_type, r.subject_id, r.rating, r.matches, r.wins, r.losses, r.draws,
                 COUNT(h.id) AS n, COALESCE(SUM(h.outcome = 'WIN'), 0) AS w, COALESCE(SUM(h.outcome = 'LOSS'), 0) AS l, COALESCE(SUM(h.outcome = 'DRAW'), 0) AS d,
                 (SELECT h2.new FROM lifesteal_ranked_rating_history h2 WHERE h2.season_id = r.season_id AND h2.rating_type = r.rating_type AND h2.subject_id = r.subject_id ORDER BY h2.id DESC LIMIT 1) AS last_new
               FROM lifesteal_ranked_ratings r
               LEFT JOIN lifesteal_ranked_rating_history h ON h.season_id = r.season_id AND h.rating_type = r.rating_type AND h.subject_id = r.subject_id
               WHERE r.season_id = ?
               GROUP BY r.rating_type, r.subject_id
               HAVING n <> r.matches OR w <> r.wins OR l <> r.losses OR d <> r.draws OR last_new IS NULL OR last_new <> r.rating
               LIMIT ?""",
            season, limit,
        ) { rs ->
            "${rs.getString("rating_type")} ${rs.getString("subject_id")}: row says ${rs.getInt("rating")} after ${rs.getInt("matches")} (W ${rs.getInt("wins")} L ${rs.getInt("losses")} D ${rs.getInt("draws")}), " +
                "history says ${rs.getObject("last_new") ?: "none"} after ${rs.getInt("n")} (W ${rs.getInt("w")} L ${rs.getInt("l")} D ${rs.getInt("d")})"
        }
        problems += db.query(
            """SELECT DISTINCT h.match_id, res.status AS res_status, m.state, m.kind, m.test FROM lifesteal_ranked_rating_history h
               LEFT JOIN lifesteal_ranked_rating_results res ON res.match_id = h.match_id
               LEFT JOIN lifesteal_ranked_matches m ON m.match_id = h.match_id
               WHERE h.season_id = ? AND (res.status IS NULL OR res.status <> 'RATED' OR m.state IS NULL OR m.state <> 'COMPLETED' OR m.test = 1 OR m.kind = 'TEST')
               LIMIT ?""",
            season, limit,
        ) { rs -> "history of ${rs.getString("match_id")} has result ${rs.getString("res_status") ?: "none"}, match ${rs.getString("state") ?: "missing"} ${rs.getString("kind") ?: ""}${if (rs.getInt("test") == 1) " (test)" else ""}" }
        problems += db.query(
            """SELECT res.match_id FROM lifesteal_ranked_rating_results res
               WHERE res.season_id = ? AND res.status = 'RATED' AND NOT EXISTS (SELECT 1 FROM lifesteal_ranked_rating_history h WHERE h.match_id = res.match_id)
               LIMIT ?""",
            season, limit,
        ) { rs -> "${rs.getString("match_id")} is RATED but has no rating history" }
        return problems.take(limit)
    }

    companion object {
        /** A season's rated matches per subject are far below this; it only bounds a corrupted table. */
        const val MAX_POINTS = 20_000
    }
}
