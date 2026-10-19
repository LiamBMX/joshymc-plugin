package com.liam.joshymc.manager

/**
 * Slot layout for the /settings GUI (issue #1263), kept Bukkit-free.
 *
 * Settings sit in one horizontal row, centered and mirror-symmetric: an odd
 * count is centered directly (7 → slots 1..7), an even count leaves the middle
 * slot empty (8 → 0..3 and 5..8). Past nine settings more rows are added, each
 * laid out the same way, so nothing is ever dropped to fit.
 */
object SettingsLayout {

    const val ROW = 9

    /**
     * Leading spaces that center the bold "Settings" title in a chest GUI.
     * The title area is 176px wide and starts 8px in; bold "Settings" advances
     * 48px (S6 e6 t4 t4 i2 n6 g6 s6, +1 each for bold), so it starts at
     * (176 - 48) / 2 = 64px, i.e. 56px of 4px plain spaces.
     */
    const val TITLE_PADDING_SPACES = 14

    fun rows(count: Int): Int = maxOf(1, (count + ROW - 1) / ROW)

    fun size(count: Int): Int = rows(count) * ROW

    fun slots(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val result = mutableListOf<Int>()
        var remaining = count
        var row = 0
        val rows = rows(count)
        while (remaining > 0) {
            // Spread evenly across rows so the last row isn't a lonely straggler.
            val inRow = (remaining + (rows - row) - 1) / (rows - row)
            result += rowSlots(inRow).map { row * ROW + it }
            remaining -= inRow
            row++
        }
        return result
    }

    /** Columns 0..8 for [count] items (1..9) in one row, centered and symmetric. */
    fun rowSlots(count: Int): List<Int> {
        require(count in 1..ROW) { "row holds 1..$ROW items, got $count" }
        val center = ROW / 2
        return if (count % 2 == 1) {
            val half = count / 2
            (center - half..center + half).toList()
        } else {
            val half = count / 2
            (center - half until center) + (center + 1..center + half)
        }
    }
}
