package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.lifesteal.ranked.rating.RatingOutcome
import java.util.Locale

/**
 * Competitive statistics (issue #1367), Bukkit-free and derived only from the append-only rating
 * history (`lifesteal_ranked_rating_history`, one row per match per subject, UNIQUE, written in the
 * same transaction as the rating). So a statistic only exists for a committed, rated match:
 * cancelled matches, staff test matches, invalid records and unrated challenges never reach the
 * history and are never counted, and a match can't be counted twice (the rows are also de-duplicated
 * by match id here, defensively). Nothing is stored, so there are no counters to drift and nothing
 * to rebuild after a restart.
 */
object RankedStatistics {

    /** One committed rating change of one subject. */
    data class Point(val matchId: String, val previous: Int, val delta: Int, val new: Int, val outcome: RatingOutcome?, val at: Long)

    data class Summary(
        val matches: Int,
        val wins: Int,
        val losses: Int,
        val draws: Int,
        /** The rating now: the last committed value, or the initial rating before the first rated match. */
        val current: Int,
        /** Highest / lowest rating held this season, the starting rating included. */
        val peak: Int,
        val lowest: Int,
        /** Sum of all positive / negative changes (lost is a positive number). */
        val gained: Int,
        val lost: Int,
        /** Wins in a row up to the latest match (0 after a loss or draw). */
        val currentStreak: Int,
        val bestStreak: Int,
        val lastMatchAt: Long?,
    ) {
        val winPercent: String get() = winPercent(wins, matches)
        val rated: Boolean get() = matches > 0
    }

    /** [points] in commit order (oldest first). [initial] = the configured starting rating. */
    fun summarize(points: List<Point>, initial: Int): Summary {
        val unique = points.distinctBy { it.matchId }
        var wins = 0
        var losses = 0
        var draws = 0
        var gained = 0
        var lost = 0
        var streak = 0
        var best = 0
        var peak = unique.firstOrNull()?.previous ?: initial
        var lowest = peak
        for (p in unique) {
            peak = maxOf(peak, p.previous, p.new)
            lowest = minOf(lowest, p.previous, p.new)
            if (p.delta > 0) gained += p.delta else lost -= p.delta
            when (p.outcome) {
                RatingOutcome.WIN -> { wins++; streak++; best = maxOf(best, streak) }
                RatingOutcome.LOSS -> { losses++; streak = 0 }
                RatingOutcome.DRAW -> { draws++; streak = 0 }
                null -> Unit // Unreadable outcome: not counted (never guessed).
            }
        }
        return Summary(
            matches = wins + losses + draws,
            wins = wins, losses = losses, draws = draws,
            current = unique.lastOrNull()?.new ?: initial,
            peak = peak, lowest = lowest, gained = gained, lost = lost,
            currentStreak = streak, bestStreak = best,
            lastMatchAt = unique.lastOrNull()?.at,
        )
    }

    /** "57.1%", or "-" before the first match. */
    fun winPercent(wins: Int, matches: Int): String =
        if (matches <= 0) "-" else String.format(Locale.ROOT, "%.1f%%", wins * 100.0 / matches)

    /** Pages needed for [total] entries ([perPage] each); at least one, so an empty board still has a page. */
    fun pages(total: Int, perPage: Int): Int = if (total <= 0) 1 else (total + perPage - 1) / perPage

    /** [page] (0-based) clamped to the existing pages. */
    fun clampPage(page: Int, total: Int, perPage: Int): Int = page.coerceIn(0, pages(total, perPage) - 1)

    /** "12m 5s" (match durations). */
    fun duration(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0L) + 500) / 1000
        return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    }
}
