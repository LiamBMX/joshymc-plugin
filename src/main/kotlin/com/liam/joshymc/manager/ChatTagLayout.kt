package com.liam.joshymc.manager

/**
 * Slot layout for the Chat Tags menus (issue #1273), kept Bukkit-free.
 *
 * Icons fill a grid of up to [COLUMNS] per row in columns 1..7 (one empty
 * column each side), starting on the top row. A partial last row is centered
 * the same way as [SettingsLayout.rowSlots] (an even count leaves the middle
 * slot empty), so the grid stays mirror-symmetric. One empty row separates the
 * grid from the bottom navigation row. No filler items: unused slots stay empty.
 */
object ChatTagLayout {

    const val ROW = 9
    const val COLUMNS = 7

    /** Most rows a tag page uses: 4 grid rows + spacer + nav row = a 54-slot chest. */
    const val MAX_GRID_ROWS = 4
    const val PAGE_SIZE = COLUMNS * MAX_GRID_ROWS

    fun gridRows(count: Int): Int = ((count + COLUMNS - 1) / COLUMNS).coerceIn(1, MAX_GRID_ROWS)

    /** Grid rows, one spacer row, then the navigation row. */
    fun size(count: Int): Int = (gridRows(count) + 2) * ROW

    /** Slots for [count] icons (capped at [PAGE_SIZE]), in reading order. */
    fun slots(count: Int): List<Int> {
        val n = count.coerceIn(0, PAGE_SIZE)
        val result = mutableListOf<Int>()
        var row = 0
        var remaining = n
        while (remaining > 0) {
            val inRow = minOf(COLUMNS, remaining)
            result += SettingsLayout.rowSlots(inRow).map { row * ROW + it }
            remaining -= inRow
            row++
        }
        return result
    }

    fun totalPages(count: Int): Int = maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE)

    /**
     * Size of every page of a [total]-entry list. Taken from the fullest page so
     * paging never changes the chest size or the grid pattern.
     */
    fun pagedSize(total: Int): Int = size(minOf(total, PAGE_SIZE))
}
