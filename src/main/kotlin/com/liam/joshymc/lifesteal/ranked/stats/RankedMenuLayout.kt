package com.liam.joshymc.lifesteal.ranked.stats

/**
 * Slot layout of the Ranked Team Wars menus (issue #1367), Bukkit-free so it can be checked
 * without a server. Bottom-row conventions follow `GuiStyle` (Back = size-9, Previous = size-7,
 * Close = size-5, Next = size-3).
 *
 * Main menu (45 slots): the queue row of issue #1363 stays where it was (sizes 10-13, status 15,
 * challenges 16, accept/decline 19/20, leave 24) and slot 4 still shows the rating summary; the new
 * row 3 holds the statistics buttons; Close sits in the bottom centre.
 */
object RankedMenuLayout {
    const val MAIN_SIZE = 45
    const val RATING = 4
    val QUEUE_SIZES = listOf(10, 11, 12, 13)
    const val STATUS = 15
    const val CHALLENGES = 16
    const val ACCEPT = 19
    const val DECLINE = 20
    const val LEAVE = 24

    const val PLAYER_STATS = 28
    const val TEAM_STATS = 29
    const val HISTORY = 30
    const val SEASON = 31
    const val DIVISIONS = 32
    const val TOP_PLAYERS = 33
    const val TOP_TEAMS = 34

    /** Every main-menu slot that holds a button (Close included), for the overlap check. */
    val MAIN_SLOTS: List<Int> = listOf(RATING) + QUEUE_SIZES + listOf(STATUS, CHALLENGES, ACCEPT, DECLINE, LEAVE,
        PLAYER_STATS, TEAM_STATS, HISTORY, SEASON, DIVISIONS, TOP_PLAYERS, TOP_TEAMS, closeSlot(MAIN_SIZE))

    // ── List menus (leaderboards, match history) ────────────

    const val LIST_SIZE = 54
    /** The viewer's own place (leaderboards) / whose history it is. */
    const val LIST_HEADER = 4
    /** Rows 1-4, columns 1-7: 28 entries per page, filled left to right, top to bottom. */
    val LIST_SLOTS: List<Int> = (1..4).flatMap { row -> (1..7).map { row * 9 + it } }
    val PER_PAGE: Int get() = LIST_SLOTS.size

    // ── Detail menus (stats, season, divisions) ─────────────

    const val DETAIL_SIZE = 45

    /** [count] items centred in row [row] of a 9-wide chest (at most 9). */
    fun centeredRow(row: Int, count: Int): List<Int> {
        val n = count.coerceIn(0, 9)
        val start = (9 - n) / 2
        return List(n) { row * 9 + start + it }
    }

    /**
     * Division slots: up to 7 centred in row 2, the rest (up to 7 more) centred in row 3, so the
     * default seven fit in one row and the configured maximum still fits a 45-slot chest.
     */
    fun divisionSlots(count: Int): List<Int> {
        val first = minOf(count, 7)
        val rest = (count - first).coerceIn(0, 7)
        return centeredRow(1, first).map { it + 9 } + centeredRow(3, rest)
    }

    fun backSlot(size: Int) = size - 9
    fun prevSlot(size: Int) = size - 7
    fun closeSlot(size: Int) = size - 5
    fun nextSlot(size: Int) = size - 3
}
