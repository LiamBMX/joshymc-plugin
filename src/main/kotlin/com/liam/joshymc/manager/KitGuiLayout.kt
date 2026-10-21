package com.liam.joshymc.manager

/**
 * Slot layout for the /kit selection GUI (issue #1280), kept Bukkit-free.
 *
 * A plain chest with no filler: one empty top row, the kit icons, then a bottom
 * row holding only the centered Close barrier. Up to [COLUMNS] kits sit in the
 * single middle row (columns 1..7), centered the same way as
 * [SettingsLayout.rowSlots]; only past that are more kit rows added, balanced so
 * the last row isn't a lonely straggler.
 */
object KitGuiLayout {

    const val ROW = 9
    const val COLUMNS = 7

    /** 4 kit rows + top spacer + bottom row = a 54-slot chest. */
    const val MAX_KIT_ROWS = 4
    const val CAPACITY = COLUMNS * MAX_KIT_ROWS

    fun kitRows(count: Int): Int = ((count + COLUMNS - 1) / COLUMNS).coerceIn(1, MAX_KIT_ROWS)

    /** Top spacer row, the kit rows, then the bottom row: 27 slots for up to 7 kits. */
    fun size(count: Int): Int = (kitRows(count) + 2) * ROW

    /** Bottom-row center. */
    fun closeSlot(count: Int): Int = size(count) - 5

    /** Slots for [count] kit icons (capped at [CAPACITY]), in reading order, starting on row 1. */
    fun slots(count: Int): List<Int> {
        val n = count.coerceIn(0, CAPACITY)
        if (n == 0) return emptyList()
        val rows = kitRows(n)
        val result = mutableListOf<Int>()
        var remaining = n
        for (r in 0 until rows) {
            val inRow = (remaining + (rows - r) - 1) / (rows - r)
            result += SettingsLayout.rowSlots(inRow).map { (1 + r) * ROW + it }
            remaining -= inRow
        }
        return result
    }
}
