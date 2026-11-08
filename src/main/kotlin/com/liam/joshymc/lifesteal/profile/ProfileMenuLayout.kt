package com.liam.joshymc.lifesteal.profile

/**
 * Slot layout of the Lifesteal profile menus (issue #1429). Bukkit-free, so the layout checks can
 * prove no two buttons share a slot and every slot fits its chest.
 */
object ProfileMenuLayout {
    // Profile (54)
    const val PROFILE_SIZE = 54
    const val HEAD = 4
    const val HEARTS = 19
    const val KILLS = 20
    const val DEATHS = 21
    const val KD = 22
    const val STREAK = 23
    const val BOUNTIES = 24
    const val PLAYTIME = 25
    const val RANKED = 28
    const val TEAM = 29
    const val ACHIEVEMENTS = 30
    const val ACTIVITY = 32
    const val SEASONS = 33
    const val RANK = 34
    val FEATURED = listOf(38, 40, 42)
    const val OPEN_ACHIEVEMENTS = 47
    const val CLOSE = 49
    const val OPEN_LEADERBOARDS = 51
    const val CUSTOMIZE = 53

    /** Border panes: the outer ring, except the slots buttons use. */
    fun borderSlots(size: Int): List<Int> {
        val rows = size / 9
        val ring = (0 until size).filter { it < 9 || it >= size - 9 || it % 9 == 0 || it % 9 == 8 }
        return ring.filter { rows > 0 && it !in profileButtons() }
    }

    fun profileButtons(): List<Int> =
        listOf(HEAD, HEARTS, KILLS, DEATHS, KD, STREAK, BOUNTIES, PLAYTIME, RANKED, TEAM, ACHIEVEMENTS, ACTIVITY, SEASONS, RANK) +
            FEATURED + listOf(OPEN_ACHIEVEMENTS, CLOSE, OPEN_LEADERBOARDS, CUSTOMIZE)

    // Achievements main menu (27)
    const val MAIN_SIZE = 27
    const val SUMMARY = 4
    val CATEGORY_SLOTS = listOf(11, 12, 13, 14, 15)
    const val MAIN_BACK = 18
    const val MAIN_CLOSE = 22

    // Paged lists (54): entries 0..44, controls in the bottom row (GuiStyle slots).
    const val LIST_SIZE = 54
    const val PAGE_SIZE = 45
    const val LIST_BACK = 45
    const val LIST_PREV = 47
    const val LIST_CLOSE = 49
    const val LIST_NEXT = 51
    const val LIST_SORT = 53

    // Leaderboards (54)
    val BOARD_SLOTS = (1..3).flatMap { row -> (1..7).map { row * 9 + it } }
    val BOARD_CATEGORY_SLOTS = listOf(37, 38, 39, 40, 41, 42, 43, 44)
    const val BOARD_BACK = 45
    const val BOARD_PREV = 47
    const val BOARD_PERIOD = 48
    const val BOARD_CLOSE = 49
    const val BOARD_SELF = 50
    const val BOARD_NEXT = 51
    const val BOARD_HEADER = 4

    // Customize (27)
    const val CUSTOM_SIZE = 27
    const val CUSTOM_TITLE = 10
    const val CUSTOM_BORDER = 12
    const val CUSTOM_FEATURED = 14
    const val CUSTOM_PRIVACY = 16
    const val CUSTOM_BACK = 18
    const val CUSTOM_CLOSE = 22

    fun pages(total: Int, perPage: Int): Int = if (total <= 0) 1 else (total + perPage - 1) / perPage
}
