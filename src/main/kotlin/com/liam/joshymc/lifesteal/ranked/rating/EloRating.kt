package com.liam.joshymc.lifesteal.ranked.rating

import com.liam.joshymc.lifesteal.LifestealConfigParser
import com.liam.joshymc.lifesteal.LifestealSettings
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/** The two independent ratings (issue #1365). They are never derived from each other. */
enum class RatingType(val label: String) {
    /** A player's own rating, keyed by their UUID (Floodgate ids included). */
    PLAYER_ELO("Player Elo"),
    /** A Lifesteal team's rating, keyed by the team's stable id (never its name or members). */
    TEAM_ELO("Team Elo");

    companion object {
        fun parse(raw: String?): RatingType? = entries.firstOrNull { it.name == raw }
    }
}

/** A side's result. [DRAW] exists in the formula only: no ranked rule produces a draw today. */
enum class RatingOutcome(val score: Double, val label: String) {
    WIN(1.0, "win"),
    LOSS(0.0, "loss"),
    DRAW(0.5, "draw");

    fun opposite(): RatingOutcome = when (this) { WIN -> LOSS; LOSS -> WIN; DRAW -> DRAW }

    companion object {
        fun parse(raw: String?): RatingOutcome? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * The one Elo calculation (issue #1365), Bukkit-free and deterministic. Commands, menus and the
 * match code never compute ratings themselves; [RatingStore.finalize] is the only caller that
 * applies the results.
 *
 * - Expected score: `E = 1 / (1 + 10^((opponent - own) / 400))`.
 * - Change: `delta = K x (actual - E)`, rounded half away from zero to a whole number, so a win and
 *   the matching loss are always the same size.
 * - New rating: `own + delta`, kept inside [Params.minimum]..[Params.maximum]; the change that is
 *   stored is the one actually applied after that clamp.
 *
 * Ratings are Ints far below overflow (at most [LifestealConfigParser.MAX_ELO_RATING]); the
 * arithmetic is done in Double and every value is checked finite before it is used.
 */
object EloRating {

    data class Params(
        val initialPlayer: Int,
        val initialTeam: Int,
        val minimum: Int,
        val maximum: Int,
        val kFactor: Int,
    ) {
        init {
            require(minimum in 0 until maximum) { "minimum rating $minimum must be 0 or more and below the maximum $maximum" }
            require(maximum <= LifestealConfigParser.MAX_ELO_RATING) { "maximum rating $maximum is above ${LifestealConfigParser.MAX_ELO_RATING}" }
            require(initialPlayer in minimum..maximum) { "initial player rating $initialPlayer is outside $minimum..$maximum" }
            require(initialTeam in minimum..maximum) { "initial team rating $initialTeam is outside $minimum..$maximum" }
            require(kFactor in 1..200) { "K factor $kFactor is outside 1..200" }
        }

        fun initial(type: RatingType): Int = if (type == RatingType.PLAYER_ELO) initialPlayer else initialTeam

        companion object {
            fun of(s: LifestealSettings.RankedElo) = Params(s.initialPlayerRating, s.initialTeamRating, s.minimumRating, s.maximumRating, s.kFactor)
        }
    }

    /** One rating change and every input that produced it (kept in the rating history). */
    data class Change(
        val before: Int,
        val delta: Int,
        val after: Int,
        val outcome: RatingOutcome,
        /** Strength used for this side (a Team Elo, or the average Player Elo of the side's participants). */
        val ownStrength: Double,
        val opponentStrength: Double,
        val expected: Double,
        val kFactor: Int,
    )

    /**
     * `1 / (1 + 10^((opponent - own) / 400))`, always in 0..1 (a gap of thousands of points rounds
     * to exactly 0 or 1 in double precision, which only means a change of 0 or K).
     */
    fun expected(own: Double, opponent: Double): Double {
        require(own.isFinite() && opponent.isFinite()) { "ratings must be finite (got $own vs $opponent)" }
        val e = 1.0 / (1.0 + 10.0.pow((opponent - own) / 400.0))
        check(e.isFinite() && e >= 0.0 && e <= 1.0) { "expected score $e out of range" }
        return e
    }

    /** Half away from zero: 15.5 → 16, -15.5 → -16 (so both sides of a match move by the same amount). */
    fun roundHalfAway(value: Double): Int {
        require(value.isFinite()) { "can't round $value" }
        val magnitude = floor(abs(value) + 0.5)
        require(magnitude <= Int.MAX_VALUE) { "value $value is out of range" }
        return (if (value < 0) -magnitude else magnitude).toInt()
    }

    /** The raw change for one side, before the bounds clamp. */
    fun rawDelta(own: Double, opponent: Double, outcome: RatingOutcome, k: Int): Int {
        require(k in 1..200) { "K factor $k is outside 1..200" }
        return roundHalfAway(k * (outcome.score - expected(own, opponent)))
    }

    /** Applies [delta] to [before] inside the bounds and reports the change that really happened. */
    fun apply(before: Int, ownStrength: Double, opponentStrength: Double, outcome: RatingOutcome, params: Params): Change {
        require(before in 0..LifestealConfigParser.MAX_ELO_RATING) { "stored rating $before is out of range" }
        val raw = rawDelta(ownStrength, opponentStrength, outcome, params.kFactor)
        val after = (before.toLong() + raw).coerceIn(params.minimum.toLong(), params.maximum.toLong()).toInt()
        return Change(before, after - before, after, outcome, ownStrength, opponentStrength, expected(ownStrength, opponentStrength), params.kFactor)
    }

    /** Mean of a side's participant ratings (the side's strength for Player Elo). */
    fun average(ratings: Collection<Int>): Double {
        require(ratings.isNotEmpty()) { "a side needs at least one rated participant" }
        for (r in ratings) require(r in 0..LifestealConfigParser.MAX_ELO_RATING) { "rating $r is out of range" }
        return ratings.sumOf { it.toLong() }.toDouble() / ratings.size
    }

    /**
     * Player Elo for a team match: each side's strength is the average of its participants'
     * pre-match Player Elo, and every participant of a side gets the change computed from those two
     * averages (the same raw change for everyone on the side; only the bounds clamp can differ).
     * Kills and deaths never enter it.
     */
    fun players(sideA: Map<UUID, Int>, sideB: Map<UUID, Int>, outcomeA: RatingOutcome, params: Params): Map<UUID, Change> {
        require(sideA.isNotEmpty() && sideB.isNotEmpty()) { "both sides need participants" }
        require(sideA.keys.none { it in sideB.keys }) { "a player can't be on both sides" }
        val avgA = average(sideA.values)
        val avgB = average(sideB.values)
        val out = LinkedHashMap<UUID, Change>()
        for ((uuid, rating) in sideA) out[uuid] = apply(rating, avgA, avgB, outcomeA, params)
        for ((uuid, rating) in sideB) out[uuid] = apply(rating, avgB, avgA, outcomeA.opposite(), params)
        return out
    }

    /** Team Elo: the two teams' own pre-match Team Elo against each other. */
    fun teams(teamA: Int, teamB: Int, outcomeA: RatingOutcome, params: Params): Pair<Change, Change> =
        apply(teamA, teamA.toDouble(), teamB.toDouble(), outcomeA, params) to
            apply(teamB, teamB.toDouble(), teamA.toDouble(), outcomeA.opposite(), params)
}
