package com.liam.joshymc.lifesteal.ranked.rating

import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side
import java.util.UUID

/**
 * Whether a finished match may change Elo (issue #1365), Bukkit-free. It only reads the stored
 * match record and participants (never memory or anything a player can send), so the decision is
 * the same after a restart.
 *
 * Classification, from the match record's `kind` (set by the server code path that created the
 * match, never by a player):
 * - `PUBLIC` = RANKED_MATCHMAKING (`/ranked queue`): rated.
 * - `CHALLENGE` = DIRECT_TEAM_CHALLENGE (`/ranked challenge`): unrated unless
 *   `ranked-team-wars.elo.rate-challenges` is true.
 * - `TEST` = ADMIN_TEST_MATCH (`/lifesteal admin ranked testmatch`): never rated.
 *
 * Issue #1499 (anti-farming): `same-opponent-limit` rated matches per `same-opponent-window-hours`
 * apply twice, and exceeding **either** makes the match UNRATED for both sides (Player Elo and Team
 * Elo alike, one verdict):
 * - [Limit.TEAM_PAIR]: rated matches between the same two team ids (the original rule).
 * - [Limit.REPEATED_ROSTER]: rated matches, under any team ids, whose rosters are the same
 *   opponents by [sameOpponents]. Disbanding, recreating or renaming a team changes nothing here,
 *   because it is computed from the participants' UUIDs in the stored match history.
 * - [Limit.PLAYER_PAIR] (issue #1527): rated matches, under any team ids and with any teammates, in
 *   which one player of this match stood against one opposing player of this match. It closes the
 *   gap [sameOpponents] leaves on purpose (it ignores a single incidental opponent pair): two players
 *   who keep meeting by rotating their teammates. Without a team change two players only meet as
 *   their two teams, which [Limit.TEAM_PAIR] already caps, so in practice this only adds a limit
 *   when a player moved teams.
 */
object RatingRules {

    enum class Classification(val label: String) {
        RANKED_MATCHMAKING("ranked matchmaking"),
        DIRECT_TEAM_CHALLENGE("direct team challenge"),
        ADMIN_TEST_MATCH("admin test match");

        companion object {
            fun of(kind: MatchKind, testFlag: Boolean): Classification = when {
                testFlag || kind == MatchKind.TEST -> ADMIN_TEST_MATCH
                kind == MatchKind.CHALLENGE -> DIRECT_TEAM_CHALLENGE
                else -> RANKED_MATCHMAKING
            }
        }
    }

    sealed interface Verdict {
        /** Rate it: [winner] won, [sideA]/[sideB] are the validated participant UUIDs. */
        data class Rated(val winner: Side, val sideA: List<java.util.UUID>, val sideB: List<java.util.UUID>) : Verdict
        /** A valid result that doesn't change Elo by policy (recorded UNRATED, never retried). [limit] = which anti-farming limit, if any. */
        data class Unrated(val reason: String, val limit: Limit? = null) : Verdict
        /** The stored result doesn't add up (recorded INVALID and reported, never rated, never guessed). */
        data class Invalid(val reason: String) : Verdict
        /** The match isn't over yet: nothing is recorded. */
        data class NotReady(val reason: String) : Verdict
    }

    /** Which same-opponent limit made a match unrated (issue #1499). */
    enum class Limit { TEAM_PAIR, REPEATED_ROSTER, PLAYER_PAIR }

    /**
     * Issue #1499: the roster-overlap rule. An earlier match ([prevA] vs [prevB]) counts as **the
     * same opponents** as this one ([curA] vs [curB]) when, with the sides lined up either way
     * (A with A and B with B, or A with B and B with A), both of these hold:
     * 1. each side of this match shares at least half (rounded up) of the smaller of the two lined-up
     *    rosters with its counterpart, and
     * 2. at least two opposing player pairs of this match also stood on opposite sides then
     *    (shared on one side x shared on the other >= 2).
     *
     * So a single incidental opponent (one player of each side met before) never counts, but the same
     * players under a new team id do, and so does swapping a minority of either roster: 2v2 with one
     * player replaced (3 of 4 the same), 3v3/4v4 with two of each side kept, 5v5 with three of each.
     */
    fun sameOpponents(curA: Collection<UUID>, curB: Collection<UUID>, prevA: Collection<UUID>, prevB: Collection<UUID>): Boolean =
        lined(curA, curB, prevA, prevB) || lined(curA, curB, prevB, prevA)

    private fun lined(x: Collection<UUID>, y: Collection<UUID>, px: Collection<UUID>, py: Collection<UUID>): Boolean {
        if (x.isEmpty() || y.isEmpty() || px.isEmpty() || py.isEmpty()) return false
        val sx = x.toSet().intersect(px.toSet()).size
        val sy = y.toSet().intersect(py.toSet()).size
        return sx >= half(x.size, px.size) && sy >= half(y.size, py.size) && sx * sy >= 2
    }

    private fun half(a: Int, b: Int): Int = (minOf(a, b) + 1) / 2

    /** `none` is what the match lifecycle stores when no season exists. */
    fun validSeason(id: String?): Boolean = !id.isNullOrBlank() && id != "none" && id.length <= 64

    /**
     * [ratedBetweenPair] = rated matches between these two teams inside the same-opponent window,
     * not counting this one. [repeatedRosters] = rated matches inside the same window, under any
     * team ids, whose rosters are the same opponents by [sameOpponents] (issue #1499), not counting this one.
     * [repeatedPair] = the most rated matches inside the same window, under any team ids, in which one
     * opposing player pair of this match already stood on opposite sides (issue #1527), not counting this one.
     */
    fun decide(
        match: RankedStore.MatchRecord,
        participants: List<RankedStore.ParticipantRecord>,
        rateChallenges: Boolean,
        sameOpponentLimit: Int,
        ratedBetweenPair: Int,
        repeatedRosters: Int = 0,
        repeatedPair: Int = 0,
    ): Verdict {
        when (match.state) {
            MatchState.COMPLETED -> Unit
            MatchState.CANCELLED -> return Verdict.Unrated("the match was cancelled (${match.cancelReason ?: "no reason"})")
            null -> return Verdict.Invalid("the match has an unknown state")
            else -> return Verdict.NotReady("the match is ${match.state.name.lowercase()}")
        }
        when (Classification.of(match.kind, match.test)) {
            Classification.ADMIN_TEST_MATCH -> return Verdict.Unrated("staff test matches are never rated")
            Classification.DIRECT_TEAM_CHALLENGE -> if (!rateChallenges) return Verdict.Unrated("team challenges are unrated")
            Classification.RANKED_MATCHMAKING -> Unit
        }
        if (match.outcome != "WIN") return Verdict.Invalid("a completed match without a WIN outcome (${match.outcome ?: "none"})")
        val winner = match.winner ?: return Verdict.Invalid("a completed match without a winner")
        // Ratings belong to a season; without one (progression disabled) a match can't be rated.
        if (!validSeason(match.seasonId)) return Verdict.Unrated("no Lifesteal season was active (ratings belong to a season)")
        if (match.teamAId.isBlank() || match.teamBId.isBlank() || match.teamAId == match.teamBId) return Verdict.Invalid("the match doesn't have two different teams")
        val winnerTeam = if (winner == Side.A) match.teamAId else match.teamBId
        if (match.winnerTeamId != winnerTeam) return Verdict.Invalid("the recorded winner team (${match.winnerTeamId}) isn't ${winner.label}'s team ($winnerTeam)")
        if (match.size !in 2..5) return Verdict.Invalid("the match size ${match.size} isn't 2..5")
        val a = participants.filter { it.side == Side.A }
        val b = participants.filter { it.side == Side.B }
        if (a.size != match.size || b.size != match.size) return Verdict.Invalid("expected ${match.size} participants per side, found ${a.size} and ${b.size}")
        if (a.any { it.teamId != match.teamAId } || b.any { it.teamId != match.teamBId }) return Verdict.Invalid("a participant is recorded for the wrong team")
        if ((a + b).map { it.uuid }.toSet().size != a.size + b.size) return Verdict.Invalid("a player is recorded twice")
        // Issue #1498: a win by a side that used a restricted ability is kept as the result, never rated.
        if (com.liam.joshymc.lifesteal.ranked.RankedIntegrity.ratingBlocked(winner, match.ineligibleSides, participants.filter { it.side == winner }.map { it.defeatReason })) {
            return Verdict.Unrated(com.liam.joshymc.lifesteal.ranked.RankedIntegrity.UNRATED_REASON)
        }
        if (sameOpponentLimit > 0 && ratedBetweenPair >= sameOpponentLimit) {
            return Verdict.Unrated("these two teams already played $ratedBetweenPair rated match(es) recently (same-opponent limit $sameOpponentLimit)", Limit.TEAM_PAIR)
        }
        if (sameOpponentLimit > 0 && repeatedRosters >= sameOpponentLimit) {
            return Verdict.Unrated("most of these players already played $repeatedRosters rated match(es) against each other recently, under any team (repeated-opponent limit $sameOpponentLimit)", Limit.REPEATED_ROSTER)
        }
        if (sameOpponentLimit > 0 && repeatedPair >= sameOpponentLimit) {
            return Verdict.Unrated("two of these players already faced each other in $repeatedPair rated match(es) recently, whatever their teams or teammates (repeated-opponent limit $sameOpponentLimit)", Limit.PLAYER_PAIR)
        }
        return Verdict.Rated(winner, a.map { it.uuid }, b.map { it.uuid })
    }
}
