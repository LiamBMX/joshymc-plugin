package com.liam.joshymc.quest

import com.liam.joshymc.manager.SettingsLayout

/**
 * Slots of the `/quests` menus (issue #1385). Bukkit-free so `scripts/QuestMenuCheck.kt` can check it.
 *
 * Main menu: a 27-slot chest laid out like the `/kit` menu, only the middle row used: Daily (11),
 * Weekly (13) and Event Quests (15). No filler, no Back or Close.
 *
 * Daily page (issue #1417, was 36 slots): a 27-slot chest. Reset timer at the top center with the
 * completion bonus (2) and Quest Master (6) beside it, the cycle's quests centered in row 1, and the
 * GuiStyle bottom row in the third row (Back = size-9, Close = size-5) with the "How This Works"
 * book in the last slot.
 *
 * Weekly page (issue #1409): the same 3-row layout, with its own `WEEKLY_*` constants.
 */
object QuestMenuLayout {
    const val ROW = 9

    const val MAIN_SIZE = 27
    const val DAILY_SLOT = 11
    const val WEEKLY_SLOT = 13
    const val EVENT_SLOT = 15
    val MAIN_SLOTS: List<Int> = listOf(DAILY_SLOT, WEEKLY_SLOT, EVENT_SLOT)

    /** Daily Quests page (issue #1417): 3 rows, the same layout as the Weekly page. */
    const val PAGE_SIZE = 27
    const val HEADER_SLOT = 4
    /** Quests fit in one row, columns 1..7. */
    const val MAX_QUESTS = 7
    const val BONUS_SLOT = 2
    const val QUEST_MASTER_SLOT = 6
    const val BACK_SLOT = PAGE_SIZE - 9
    const val CLOSE_SLOT = PAGE_SIZE - 5
    const val INFO_SLOT = PAGE_SIZE - 1

    /** Weekly Quests page (issue #1409): 3 rows. Header and quest row are the same as the Daily page. */
    const val WEEKLY_PAGE_SIZE = 27
    const val WEEKLY_BONUS_SLOT = 2
    const val WEEKLY_QUEST_MASTER_SLOT = 6
    const val WEEKLY_BACK_SLOT = WEEKLY_PAGE_SIZE - 9
    const val WEEKLY_CLOSE_SLOT = WEEKLY_PAGE_SIZE - 5
    const val WEEKLY_INFO_SLOT = WEEKLY_PAGE_SIZE - 1

    /** Slots for [count] quests (capped at [MAX_QUESTS]) in row 1, centered like the other menus. */
    fun questSlots(count: Int): List<Int> {
        val n = count.coerceIn(0, MAX_QUESTS)
        if (n == 0) return emptyList()
        return SettingsLayout.rowSlots(n).map { ROW + it }
    }
}
