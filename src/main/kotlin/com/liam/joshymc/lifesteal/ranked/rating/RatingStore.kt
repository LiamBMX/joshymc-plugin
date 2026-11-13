package com.liam.joshymc.lifesteal.ranked.rating

import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.manager.SqliteDatabase
import java.sql.ResultSet
import java.util.UUID

/**
 * Persistent Player Elo and Team Elo (issue #1365), Bukkit-free, in the Lifesteal server's own
 * `data.db` (tables created only where Lifesteal is hosted).
 *
 * - `lifesteal_ranked_ratings`: the current rating per (season, type, subject). Subject = player
 *   UUID for PLAYER_ELO, Lifesteal team id for TEAM_ELO. A row only exists once that player/team
 *   finished a rated match in the season; until then they have the configured initial rating
 *   (nothing is written on join, reconnect, restart or team changes). A trigger refuses any change
 *   of `rating` that isn't the `new` value of a history row for the same match and subject, so no
 *   other system can overwrite a rating.
 * - `lifesteal_ranked_rating_history`: one append-only row per rating change (UNIQUE per match,
 *   type and subject; triggers refuse deletes and edits except the player's `notified` flag).
 * - `lifesteal_ranked_rating_results`: one row per processed match (PK match id, UNIQUE
 *   transaction id `elo:<match>`): RATED, UNRATED (policy), INVALID (inconsistent record, reported)
 *   or FAILED (database error, retried). RATED/UNRATED/INVALID are final.
 *
 * [finalize] is the only writer of ratings: it re-reads the match record, decides with
 * [RatingRules], calculates with [EloRating] and writes every rating, every history row and the
 * RATED result in **one** transaction, after checking inside it that the match wasn't processed
 * already. A crash or error rolls all of it back, so nobody is ever rated twice or alone.
 */
class RatingStore(private val db: SqliteDatabase) {

    private val matches = RankedStore(db)

    enum class ResultStatus { RATED, UNRATED, INVALID, FAILED;
        val final: Boolean get() = this != FAILED
        companion object { fun parse(raw: String?) = entries.firstOrNull { it.name == raw } }
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_ratings (
                season_id TEXT NOT NULL,
                rating_type TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                rating INTEGER NOT NULL,
                wins INTEGER NOT NULL DEFAULT 0,
                losses INTEGER NOT NULL DEFAULT 0,
                draws INTEGER NOT NULL DEFAULT 0,
                matches INTEGER NOT NULL DEFAULT 0,
                display_name TEXT,
                last_match_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                version INTEGER NOT NULL DEFAULT 1,
                PRIMARY KEY (season_id, rating_type, subject_id)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_ratings_board ON lifesteal_ranked_ratings(season_id, rating_type, rating DESC)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_rating_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id TEXT NOT NULL,
                match_id TEXT NOT NULL,
                season_id TEXT NOT NULL,
                rating_type TEXT NOT NULL,
                subject_id TEXT NOT NULL,
                team_id TEXT NOT NULL,
                side TEXT NOT NULL,
                previous INTEGER NOT NULL,
                delta INTEGER NOT NULL,
                new INTEGER NOT NULL,
                outcome TEXT NOT NULL,
                own_strength REAL NOT NULL,
                opponent_strength REAL NOT NULL,
                expected REAL NOT NULL,
                k_factor INTEGER NOT NULL,
                at INTEGER NOT NULL,
                notified INTEGER NOT NULL DEFAULT 0,
                UNIQUE (match_id, rating_type, subject_id)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_rating_history_subject ON lifesteal_ranked_rating_history(rating_type, subject_id, season_id, id)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_rating_history_unnotified ON lifesteal_ranked_rating_history(subject_id) WHERE notified = 0 AND rating_type = 'PLAYER_ELO'")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_ranked_rating_results (
                match_id TEXT PRIMARY KEY,
                transaction_id TEXT NOT NULL UNIQUE,
                season_id TEXT,
                kind TEXT,
                status TEXT NOT NULL,
                reason TEXT,
                team_a_id TEXT,
                team_b_id TEXT,
                winner_side TEXT,
                size INTEGER,
                match_ended_at INTEGER,
                attempts INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                created_at INTEGER NOT NULL,
                processed_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_rating_results_status ON lifesteal_ranked_rating_results(status, processed_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_rating_results_pair ON lifesteal_ranked_rating_results(season_id, team_a_id, team_b_id, match_ended_at)")
        // Issue #1499: the repeated-roster limit looks up earlier rated matches by player and window
        // (indexes only; the participant history itself already exists and is never deleted).
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_rating_results_window ON lifesteal_ranked_rating_results(status, season_id, match_ended_at)")
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_ranked_participants_uuid ON lifesteal_ranked_participants (uuid, match_id)")
        // Append-only history (only the player's "result shown" flag may change).
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_ranked_rating_history_no_delete
            BEFORE DELETE ON lifesteal_ranked_rating_history
            BEGIN SELECT RAISE(ABORT, 'rating history is append-only'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_ranked_rating_history_no_edit
            BEFORE UPDATE OF transaction_id, match_id, season_id, rating_type, subject_id, team_id, side, previous, delta, new, outcome, own_strength, opponent_strength, expected, k_factor, at
            ON lifesteal_ranked_rating_history
            BEGIN SELECT RAISE(ABORT, 'rating history is append-only'); END
            """.trimIndent()
        )
        // A rating only ever changes to a value the rating history recorded for that match.
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_ranked_ratings_from_history
            BEFORE UPDATE OF rating ON lifesteal_ranked_ratings
            WHEN NEW.last_match_id IS NULL OR NOT EXISTS (
                SELECT 1 FROM lifesteal_ranked_rating_history h
                WHERE h.match_id = NEW.last_match_id AND h.rating_type = NEW.rating_type AND h.subject_id = NEW.subject_id
                  AND h.season_id = NEW.season_id AND h.previous = OLD.rating AND h.new = NEW.rating
            )
            BEGIN SELECT RAISE(ABORT, 'ratings only change through a recorded rating transaction'); END
            """.trimIndent()
        )
        db.createTable(
            """
            CREATE TRIGGER IF NOT EXISTS trg_lifesteal_ranked_ratings_no_delete
            BEFORE DELETE ON lifesteal_ranked_ratings
            BEGIN SELECT RAISE(ABORT, 'ratings are never deleted'); END
            """.trimIndent()
        )
    }

    // ── Finalization ────────────────────────────────────────

    data class Policy(val params: EloRating.Params, val rateChallenges: Boolean, val sameOpponentLimit: Int, val sameOpponentWindowMillis: Long)

    sealed interface Finalized {
        val matchId: String
        data class Rated(
            override val matchId: String,
            val seasonId: String,
            val winner: Side,
            val teamAId: String,
            val teamAName: String,
            val teamBId: String,
            val teamBName: String,
            val size: Int,
            val sides: Map<UUID, Side>,
            val players: Map<UUID, EloRating.Change>,
            val teams: Map<String, EloRating.Change>,
        ) : Finalized
        data class Unrated(
            override val matchId: String,
            val reason: String,
            val participants: List<UUID>,
            val test: Boolean,
            /** Issue #1499: the same-opponent limit that made it unrated (null = another reason). */
            val limit: RatingRules.Limit? = null,
            val sides: Map<UUID, Side> = emptyMap(),
            val teamIds: List<String> = emptyList(),
            /** Earlier rated matches that counted toward [limit]. */
            val earlierCount: Int = 0,
            /** Team ids those earlier matches were played under, other than this match's (a recreated team shows up here). */
            val earlierOtherTeams: Set<String> = emptySet(),
        ) : Finalized
        data class Invalid(override val matchId: String, val reason: String) : Finalized
        data class AlreadyProcessed(override val matchId: String, val status: ResultStatus) : Finalized
        data class NotReady(override val matchId: String, val reason: String) : Finalized
        data class Missing(override val matchId: String) : Finalized
    }

    fun transactionId(matchId: String): String = "elo:$matchId"

    /**
     * Rates [matchId] once (idempotent: a second call returns [Finalized.AlreadyProcessed]).
     * Throws on a database error, with nothing written; the caller then records [recordFailure].
     */
    fun finalize(matchId: String, policy: Policy, now: Long): Finalized {
        var result: Finalized = Finalized.Missing(matchId)
        db.transaction {
            val existing = db.queryFirst("SELECT status FROM lifesteal_ranked_rating_results WHERE match_id = ?", matchId) { ResultStatus.parse(it.getString("status")) }
            if (existing != null && existing.final) {
                result = Finalized.AlreadyProcessed(matchId, existing)
                return@transaction
            }
            val match = matches.match(matchId) ?: return@transaction
            val participants = matches.participants(matchId)
            val since = (match.endedAt ?: now) - policy.sameOpponentWindowMillis
            val pairCount = if (policy.sameOpponentLimit > 0) ratedBetween(match.seasonId, match.teamAId, match.teamBId, since, matchId) else 0
            // Issue #1499: the same limit by player UUIDs, so recreated/renamed teams don't reset it.
            // Read inside this transaction, like the team count, so matches finishing together are
            // counted one after the other.
            val rosterRepeats = if (policy.sameOpponentLimit > 0) repeatedRosterMatches(match.seasonId, participants, since, matchId) else emptyList()
            // Issue #1527: the same limit per opposing player pair, so rotating teammates doesn't reset it either.
            val pairRepeat = if (policy.sameOpponentLimit > 0) mostRepeatedOpponentPair(match.seasonId, participants, since, matchId) else null
            when (val verdict = RatingRules.decide(match, participants, policy.rateChallenges, policy.sameOpponentLimit, pairCount, rosterRepeats.size, pairRepeat?.matches?.size ?: 0)) {
                is RatingRules.Verdict.NotReady -> result = Finalized.NotReady(matchId, verdict.reason)
                is RatingRules.Verdict.Unrated -> {
                    val repeats = if (verdict.limit == RatingRules.Limit.PLAYER_PAIR) pairRepeat?.matches.orEmpty() else rosterRepeats
                    writeResult(match, ResultStatus.UNRATED, verdict.reason, now)
                    result = Finalized.Unrated(
                        matchId, verdict.reason, participants.map { it.uuid }, match.test || match.kind == com.liam.joshymc.lifesteal.ranked.MatchKind.TEST,
                        limit = verdict.limit,
                        sides = participants.associate { it.uuid to it.side },
                        teamIds = listOf(match.teamAId, match.teamBId),
                        earlierCount = if (verdict.limit == RatingRules.Limit.TEAM_PAIR) pairCount else repeats.size,
                        earlierOtherTeams = if (verdict.limit == null) emptySet() else repeats.flatMap { listOf(it.teamAId, it.teamBId) }.toSet() - setOf(match.teamAId, match.teamBId),
                    )
                }
                is RatingRules.Verdict.Invalid -> {
                    writeResult(match, ResultStatus.INVALID, verdict.reason, now)
                    result = Finalized.Invalid(matchId, verdict.reason)
                }
                is RatingRules.Verdict.Rated -> result = rate(match, verdict, policy.params, now)
            }
        }
        return result
    }

    private fun rate(match: RankedStore.MatchRecord, v: RatingRules.Verdict.Rated, params: EloRating.Params, now: Long): Finalized.Rated {
        val season = match.seasonId
        val outcomeA = if (v.winner == Side.A) RatingOutcome.WIN else RatingOutcome.LOSS
        fun current(type: RatingType, subject: String): Int =
            db.queryFirst("SELECT rating FROM lifesteal_ranked_ratings WHERE season_id = ? AND rating_type = ? AND subject_id = ?", season, type.name, subject) { it.getInt("rating") }
                ?: params.initial(type)
        val sideA = v.sideA.associateWith { current(RatingType.PLAYER_ELO, it.toString()) }
        val sideB = v.sideB.associateWith { current(RatingType.PLAYER_ELO, it.toString()) }
        val players = EloRating.players(sideA, sideB, outcomeA, params)
        val (teamA, teamB) = EloRating.teams(current(RatingType.TEAM_ELO, match.teamAId), current(RatingType.TEAM_ELO, match.teamBId), outcomeA, params)
        val tx = transactionId(match.id)
        val sides = v.sideA.associateWith { Side.A } + v.sideB.associateWith { Side.B }

        fun write(type: RatingType, subject: String, teamId: String, side: Side, c: EloRating.Change, displayName: String?) {
            db.execute(
                """INSERT INTO lifesteal_ranked_rating_history (transaction_id, match_id, season_id, rating_type, subject_id, team_id, side, previous, delta, new, outcome, own_strength, opponent_strength, expected, k_factor, at, notified)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                tx, match.id, season, type.name, subject, teamId, side.name, c.before, c.delta, c.after, c.outcome.name,
                c.ownStrength, c.opponentStrength, c.expected, c.kFactor, now,
            )
            db.execute(
                """INSERT OR IGNORE INTO lifesteal_ranked_ratings (season_id, rating_type, subject_id, rating, display_name, created_at, updated_at, version)
                   VALUES (?, ?, ?, ?, ?, ?, ?, 1)""",
                season, type.name, subject, c.before, displayName, now, now,
            )
            val win = if (c.outcome == RatingOutcome.WIN) 1 else 0
            val loss = if (c.outcome == RatingOutcome.LOSS) 1 else 0
            val draw = if (c.outcome == RatingOutcome.DRAW) 1 else 0
            val changed = db.executeUpdate(
                """UPDATE lifesteal_ranked_ratings SET rating = ?, wins = wins + ?, losses = losses + ?, draws = draws + ?, matches = matches + 1,
                   display_name = COALESCE(?, display_name), last_match_id = ?, updated_at = ?, version = version + 1
                   WHERE season_id = ? AND rating_type = ? AND subject_id = ? AND rating = ?""",
                c.after, win, loss, draw, displayName, match.id, now, season, type.name, subject, c.before,
            )
            if (changed != 1) throw IllegalStateException("the $type of $subject changed during the rating of ${match.id}")
        }

        for ((uuid, c) in players) {
            val side = sides.getValue(uuid)
            write(RatingType.PLAYER_ELO, uuid.toString(), if (side == Side.A) match.teamAId else match.teamBId, side, c, null)
        }
        write(RatingType.TEAM_ELO, match.teamAId, match.teamAId, Side.A, teamA, match.teamAName)
        write(RatingType.TEAM_ELO, match.teamBId, match.teamBId, Side.B, teamB, match.teamBName)
        writeResult(match, ResultStatus.RATED, null, now)
        return Finalized.Rated(
            match.id, season, v.winner, match.teamAId, match.teamAName, match.teamBId, match.teamBName, match.size,
            sides, players, mapOf(match.teamAId to teamA, match.teamBId to teamB),
        )
    }

    private fun writeResult(match: RankedStore.MatchRecord, status: ResultStatus, reason: String?, now: Long) {
        // Replaces only a FAILED row (final rows were checked above, inside this transaction).
        db.execute(
            """INSERT INTO lifesteal_ranked_rating_results (match_id, transaction_id, season_id, kind, status, reason, team_a_id, team_b_id, winner_side, size, match_ended_at, attempts, created_at, processed_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
               ON CONFLICT(match_id) DO UPDATE SET status = excluded.status, reason = excluded.reason, season_id = excluded.season_id, kind = excluded.kind,
                 team_a_id = excluded.team_a_id, team_b_id = excluded.team_b_id, winner_side = excluded.winner_side, size = excluded.size,
                 match_ended_at = excluded.match_ended_at, attempts = lifesteal_ranked_rating_results.attempts + 1, processed_at = excluded.processed_at
               WHERE lifesteal_ranked_rating_results.status = 'FAILED'""",
            match.id, transactionId(match.id), match.seasonId, match.kind.name, status.name, reason?.take(300),
            pairKey(match.teamAId, match.teamBId).first, pairKey(match.teamAId, match.teamBId).second,
            match.winner?.name, match.size, match.endedAt, now, now,
        )
    }

    /** Team ids in a fixed order, so A-vs-B and B-vs-A count as the same pair. */
    private fun pairKey(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a

    private fun ratedBetween(season: String, a: String, b: String, since: Long, except: String): Int {
        val (x, y) = pairKey(a, b)
        return db.queryFirst(
            "SELECT COUNT(*) AS n FROM lifesteal_ranked_rating_results WHERE season_id = ? AND team_a_id = ? AND team_b_id = ? AND status = 'RATED' AND match_ended_at >= ? AND match_id <> ?",
            season, x, y, since, except,
        ) { it.getInt("n") } ?: 0
    }

    data class EarlierMatch(val matchId: String, val teamAId: String, val teamBId: String)

    /**
     * Issue #1499: RATED matches of [season] that ended at or after [since] (not [except]) whose
     * rosters are the same opponents as [participants] by [RatingRules.sameOpponents], whatever team
     * ids they were played under. Read from the stored participant history
     * (`lifesteal_ranked_participants`, never deleted with a team), so it survives disbanding,
     * recreating and renaming teams, and restarts. Only matches sharing a player are loaded.
     */
    fun repeatedRosterMatches(season: String, participants: List<RankedStore.ParticipantRecord>, since: Long, except: String): List<EarlierMatch> {
        val curA = participants.filter { it.side == Side.A }.map { it.uuid }
        val curB = participants.filter { it.side == Side.B }.map { it.uuid }
        if (curA.isEmpty() || curB.isEmpty()) return emptyList()
        val uuids = (curA + curB).distinct()
        val marks = uuids.joinToString(",") { "?" }
        val candidates = db.query(
            "SELECT DISTINCT r.match_id AS id FROM lifesteal_ranked_rating_results r JOIN lifesteal_ranked_participants p ON p.match_id = r.match_id " +
                "WHERE r.status = 'RATED' AND r.season_id = ? AND r.match_ended_at >= ? AND r.match_id <> ? AND p.uuid IN ($marks) ORDER BY r.match_id",
            season, since, except, *uuids.map { it.toString() }.toTypedArray(),
        ) { it.getString("id") }
        return candidates.mapNotNull { id ->
            val earlier = matches.participants(id)
            val prevA = earlier.filter { it.side == Side.A }.map { it.uuid }
            val prevB = earlier.filter { it.side == Side.B }.map { it.uuid }
            if (!RatingRules.sameOpponents(curA, curB, prevA, prevB)) return@mapNotNull null
            val rec = matches.match(id)
            EarlierMatch(id, rec?.teamAId ?: earlier.firstOrNull { it.side == Side.A }?.teamId ?: "", rec?.teamBId ?: earlier.firstOrNull { it.side == Side.B }?.teamId ?: "")
        }
    }

    /** Issue #1527: one opposing player pair of a match and the earlier rated matches they already met in. */
    data class OpponentPairRepeat(val a: UUID, val b: UUID, val matches: List<EarlierMatch>)

    /**
     * Issue #1527: the opposing player pair of [participants] (one from each side) that stood on
     * opposite sides in the most RATED matches of [season] that ended at or after [since] (not
     * [except]), whatever team ids or teammates those matches had; null when no pair met before.
     * Read from the stored participant history like [repeatedRosterMatches], so it survives team
     * changes, disbanding, recreating, renaming and restarts. Ties are broken by UUID (deterministic).
     */
    fun mostRepeatedOpponentPair(season: String, participants: List<RankedStore.ParticipantRecord>, since: Long, except: String): OpponentPairRepeat? {
        val curA = participants.filter { it.side == Side.A }.map { it.uuid.toString() }.distinct()
        val curB = participants.filter { it.side == Side.B }.map { it.uuid.toString() }.distinct()
        if (curA.isEmpty() || curB.isEmpty()) return null
        val marksA = curA.joinToString(",") { "?" }
        val marksB = curB.joinToString(",") { "?" }
        val rows = db.query(
            "SELECT pa.uuid AS a, pb.uuid AS b, r.match_id AS id FROM lifesteal_ranked_rating_results r " +
                "JOIN lifesteal_ranked_participants pa ON pa.match_id = r.match_id " +
                "JOIN lifesteal_ranked_participants pb ON pb.match_id = r.match_id AND pb.side <> pa.side " +
                "WHERE r.status = 'RATED' AND r.season_id = ? AND r.match_ended_at >= ? AND r.match_id <> ? " +
                "AND pa.uuid IN ($marksA) AND pb.uuid IN ($marksB) ORDER BY pa.uuid, pb.uuid, r.match_id",
            season, since, except, *curA.toTypedArray(), *curB.toTypedArray(),
        ) { Triple(it.getString("a"), it.getString("b"), it.getString("id")) }
        val best = rows.groupBy { it.first to it.second }
            .map { (pair, hits) -> pair to hits.map { it.third }.distinct() }
            .sortedWith(compareByDescending<Pair<Pair<String, String>, List<String>>> { it.second.size }.thenBy { it.first.first }.thenBy { it.first.second })
            .firstOrNull() ?: return null
        val earlier = best.second.map { id ->
            val rec = matches.match(id)
            EarlierMatch(id, rec?.teamAId ?: "", rec?.teamBId ?: "")
        }
        return OpponentPairRepeat(UUID.fromString(best.first.first), UUID.fromString(best.first.second), earlier)
    }

    /** A failed attempt (nothing of it was written): FAILED with the error, retried later. Never touches a final row. */
    fun recordFailure(matchId: String, error: String, now: Long) {
        db.execute(
            """INSERT INTO lifesteal_ranked_rating_results (match_id, transaction_id, status, last_error, attempts, created_at, processed_at)
               VALUES (?, ?, 'FAILED', ?, 1, ?, ?)
               ON CONFLICT(match_id) DO UPDATE SET attempts = lifesteal_ranked_rating_results.attempts + 1, last_error = excluded.last_error, processed_at = excluded.processed_at
               WHERE lifesteal_ranked_rating_results.status = 'FAILED'""",
            matchId, transactionId(matchId), error.take(300), now, now,
        )
    }

    /** Completed matches with no final rating result yet (never processed, or FAILED with fewer than [maxAttempts]). */
    fun unprocessedCompleted(maxAttempts: Int, limit: Int): List<String> = db.query(
        """SELECT m.match_id FROM lifesteal_ranked_matches m LEFT JOIN lifesteal_ranked_rating_results r ON r.match_id = m.match_id
           WHERE m.state = 'COMPLETED' AND (r.match_id IS NULL OR (r.status = 'FAILED' AND r.attempts < ?))
           ORDER BY m.ended_at, m.match_id LIMIT ?""",
        maxAttempts, limit,
    ) { it.getString("match_id") }

    // ── Reads ───────────────────────────────────────────────

    data class RatingRow(
        val seasonId: String,
        val type: RatingType,
        val subjectId: String,
        val rating: Int,
        val wins: Int,
        val losses: Int,
        val draws: Int,
        val matches: Int,
        val displayName: String?,
        val lastMatchId: String?,
        val updatedAt: Long,
    )

    data class HistoryRow(
        val id: Long,
        val transactionId: String,
        val matchId: String,
        val seasonId: String,
        val type: RatingType,
        val subjectId: String,
        val teamId: String,
        val side: Side,
        val previous: Int,
        val delta: Int,
        val new: Int,
        val outcome: RatingOutcome?,
        val ownStrength: Double,
        val opponentStrength: Double,
        val expected: Double,
        val kFactor: Int,
        val at: Long,
        val notified: Boolean,
    )

    data class ResultRow(
        val matchId: String,
        val transactionId: String,
        val seasonId: String?,
        val kind: String?,
        val status: ResultStatus?,
        val reason: String?,
        val attempts: Int,
        val lastError: String?,
        val processedAt: Long,
    )

    fun rating(season: String, type: RatingType, subject: String): RatingRow? = db.queryFirst(
        "SELECT * FROM lifesteal_ranked_ratings WHERE season_id = ? AND rating_type = ? AND subject_id = ?", season, type.name, subject,
    ) { readRating(it) }

    /** Every season's row of one subject (oldest season first), for audits. */
    fun ratingsOf(type: RatingType, subject: String): List<RatingRow> = db.query(
        "SELECT * FROM lifesteal_ranked_ratings WHERE rating_type = ? AND subject_id = ? ORDER BY created_at", type.name, subject,
    ) { readRating(it) }

    /** Newest first. [season] null = every season. */
    fun history(type: RatingType, subject: String, season: String?, limit: Int): List<HistoryRow> =
        if (season == null) db.query("SELECT * FROM lifesteal_ranked_rating_history WHERE rating_type = ? AND subject_id = ? ORDER BY id DESC LIMIT ?", type.name, subject, limit) { readHistory(it) }
        else db.query("SELECT * FROM lifesteal_ranked_rating_history WHERE rating_type = ? AND subject_id = ? AND season_id = ? ORDER BY id DESC LIMIT ?", type.name, subject, season, limit) { readHistory(it) }

    fun historyOfMatch(matchId: String): List<HistoryRow> =
        db.query("SELECT * FROM lifesteal_ranked_rating_history WHERE match_id = ? ORDER BY rating_type, side, subject_id", matchId) { readHistory(it) }

    fun result(matchId: String): ResultRow? = db.queryFirst("SELECT * FROM lifesteal_ranked_rating_results WHERE match_id = ?", matchId) { readResult(it) }

    fun results(status: ResultStatus, limit: Int): List<ResultRow> =
        db.query("SELECT * FROM lifesteal_ranked_rating_results WHERE status = ? ORDER BY processed_at DESC LIMIT ?", status.name, limit) { readResult(it) }

    fun countByStatus(): Map<ResultStatus, Int> =
        db.query("SELECT status, COUNT(*) AS n FROM lifesteal_ranked_rating_results GROUP BY status") { (ResultStatus.parse(it.getString("status")) to it.getInt("n")) }
            .mapNotNull { (s, n) -> s?.let { it to n } }.toMap()

    /** Completed matches that have no final result at all (any attempts). */
    fun unratedCompleted(limit: Int): List<String> = db.query(
        """SELECT m.match_id FROM lifesteal_ranked_matches m LEFT JOIN lifesteal_ranked_rating_results r ON r.match_id = m.match_id
           WHERE m.state = 'COMPLETED' AND (r.match_id IS NULL OR r.status = 'FAILED') ORDER BY m.ended_at DESC LIMIT ?""",
        limit,
    ) { it.getString("match_id") }

    /** Player rating changes whose result message hasn't been shown yet (oldest first). */
    fun unnotified(uuid: UUID, limit: Int): List<HistoryRow> = db.query(
        "SELECT * FROM lifesteal_ranked_rating_history WHERE subject_id = ? AND rating_type = 'PLAYER_ELO' AND notified = 0 ORDER BY id LIMIT ?", uuid.toString(), limit,
    ) { readHistory(it) }

    /** Claims the result message of one change, once (false = already shown). */
    fun claimNotice(historyId: Long): Boolean =
        db.executeUpdate("UPDATE lifesteal_ranked_rating_history SET notified = 1 WHERE id = ? AND notified = 0", historyId) == 1

    /** Gives a claimed message back (the player left before it could be shown). */
    fun unclaimNotice(historyId: Long) {
        db.executeUpdate("UPDATE lifesteal_ranked_rating_history SET notified = 0 WHERE id = ? AND notified = 1", historyId)
    }

    fun teamChange(matchId: String, teamId: String): HistoryRow? = db.queryFirst(
        "SELECT * FROM lifesteal_ranked_rating_history WHERE match_id = ? AND rating_type = 'TEAM_ELO' AND subject_id = ?", matchId, teamId,
    ) { readHistory(it) }

    fun matchRecord(matchId: String): RankedStore.MatchRecord? = matches.match(matchId)

    private fun readRating(rs: ResultSet) = RatingRow(
        seasonId = rs.getString("season_id"),
        type = RatingType.parse(rs.getString("rating_type")) ?: RatingType.PLAYER_ELO,
        subjectId = rs.getString("subject_id"),
        rating = rs.getInt("rating"),
        wins = rs.getInt("wins"),
        losses = rs.getInt("losses"),
        draws = rs.getInt("draws"),
        matches = rs.getInt("matches"),
        displayName = rs.getString("display_name"),
        lastMatchId = rs.getString("last_match_id"),
        updatedAt = rs.getLong("updated_at"),
    )

    private fun readHistory(rs: ResultSet) = HistoryRow(
        id = rs.getLong("id"),
        transactionId = rs.getString("transaction_id"),
        matchId = rs.getString("match_id"),
        seasonId = rs.getString("season_id"),
        type = RatingType.parse(rs.getString("rating_type")) ?: RatingType.PLAYER_ELO,
        subjectId = rs.getString("subject_id"),
        teamId = rs.getString("team_id"),
        side = Side.valueOf(rs.getString("side")),
        previous = rs.getInt("previous"),
        delta = rs.getInt("delta"),
        new = rs.getInt("new"),
        outcome = RatingOutcome.parse(rs.getString("outcome")),
        ownStrength = rs.getDouble("own_strength"),
        opponentStrength = rs.getDouble("opponent_strength"),
        expected = rs.getDouble("expected"),
        kFactor = rs.getInt("k_factor"),
        at = rs.getLong("at"),
        notified = rs.getInt("notified") == 1,
    )

    private fun readResult(rs: ResultSet) = ResultRow(
        matchId = rs.getString("match_id"),
        transactionId = rs.getString("transaction_id"),
        seasonId = rs.getString("season_id"),
        kind = rs.getString("kind"),
        status = ResultStatus.parse(rs.getString("status")),
        reason = rs.getString("reason"),
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
        processedAt = rs.getLong("processed_at"),
    )
}
