package com.liam.joshymc.lifesteal.ranked.rating

import com.liam.joshymc.lifesteal.ranked.MatchKind
import com.liam.joshymc.lifesteal.ranked.MatchState
import com.liam.joshymc.lifesteal.ranked.RankedStore
import com.liam.joshymc.lifesteal.ranked.Side

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
        /** A valid result that doesn't change Elo by policy (recorded UNRATED, never retried). */
        data class Unrated(val reason: String) : Verdict
        /** The stored result doesn't add up (recorded INVALID and reported, never rated, never guessed). */
        data class Invalid(val reason: String) : Verdict
        /** The match isn't over yet: nothing is recorded. */
        data class NotReady(val reason: String) : Verdict
    }

    /** `none` is what the match lifecycle stores when no season exists. */
    fun validSeason(id: String?): Boolean = !id.isNullOrBlank() && id != "none" && id.length <= 64

    /**
     * [ratedBetweenPair] = rated matches between these two teams inside the same-opponent window,
     * not counting this one.
     */
    fun decide(
        match: RankedStore.MatchRecord,
        participants: List<RankedStore.ParticipantRecord>,
        rateChallenges: Boolean,
        sameOpponentLimit: Int,
        ratedBetweenPair: Int,
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
            return Verdict.Unrated("these two teams already played $ratedBetweenPair rated match(es) recently (same-opponent limit $sameOpponentLimit)")
        }
        return Verdict.Rated(winner, a.map { it.uuid }, b.map { it.uuid })
    }
}
