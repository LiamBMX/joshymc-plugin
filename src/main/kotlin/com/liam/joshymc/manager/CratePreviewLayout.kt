package com.liam.joshymc.manager

/**
 * Slot layout for the crate Preview GUI (issue #1414), kept Bukkit-free.
 *
 * The preview is sized to its rewards: no glass, no filler rows, no border.
 *
 * - Up to [SINGLE_PAGE_CAPACITY] (54) rewards: one page with the fewest rows that hold them
 *   (`ceil(n / 9)`, at least one), and no navigation row. The rewards are spread over those
 *   rows as evenly as possible (earlier rows take the extra one), so 14 rewards are two rows
 *   of 7, 21 are three rows of 7, 27 are three full rows and 7 are one row.
 * - More than that: pages of [PAGE_CAPACITY] (45) rewards in five rows, plus a bottom
 *   navigation row (Previous / page indicator / Next). Every page is 54 slots so the buttons
 *   never move between pages.
 *
 * Each row is centered with [columns]: an odd count is one contiguous centered run, an even
 * count leaves the middle column empty so the row stays symmetrical. Rewards keep their order
 * (reading order, top row first) and every reward gets exactly one slot.
 */
object CratePreviewLayout {

    const val ROW = 9
    const val MAX_ROWS = 6
    const val SINGLE_PAGE_CAPACITY = ROW * MAX_ROWS // 54
    const val PAGE_ROWS = MAX_ROWS - 1
    const val PAGE_CAPACITY = ROW * PAGE_ROWS // 45

    /** True when [total] rewards need more than one page (and so a navigation row). */
    fun paginated(total: Int): Boolean = total > SINGLE_PAGE_CAPACITY

    fun pageCount(total: Int): Int =
        if (!paginated(total)) 1 else (total - 1) / PAGE_CAPACITY + 1

    /** Rewards on one page. */
    fun pageCapacity(total: Int): Int = if (paginated(total)) PAGE_CAPACITY else SINGLE_PAGE_CAPACITY

    /** Index (into the full reward list) of the first reward on [page]. */
    fun pageStart(total: Int, page: Int): Int = page.coerceIn(0, pageCount(total) - 1) * pageCapacity(total)

    /** How many rewards [page] shows. */
    fun pageSize(total: Int, page: Int): Int {
        val start = pageStart(total, page)
        return (total - start).coerceIn(0, pageCapacity(total))
    }

    /** Inventory size: the reward rows only, or a full 54-slot page when paginated. */
    fun size(total: Int): Int =
        if (paginated(total)) SINGLE_PAGE_CAPACITY else rowCount(total.coerceAtLeast(0)) * ROW

    private fun rowCount(count: Int): Int = ((count + ROW - 1) / ROW).coerceIn(1, MAX_ROWS)

    /** Per-row reward counts (top to bottom) for [count] rewards on one page. */
    fun rowCounts(count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val capped = count.coerceAtMost(SINGLE_PAGE_CAPACITY)
        val rows = rowCount(capped)
        val base = capped / rows
        val extra = capped % rows
        return (0 until rows).map { if (it < extra) base + 1 else base }
    }

    /** Column indices (0..8) that center [length] items in a 9-wide row. */
    fun columns(length: Int): List<Int> {
        val n = length.coerceIn(0, ROW)
        if (n == 0) return emptyList()
        if (n % 2 == 1) {
            val start = (ROW - n) / 2
            return (start until start + n).toList()
        }
        val half = n / 2
        val center = ROW / 2
        return (center - half until center).toList() + (center + 1..center + half).toList()
    }

    /** Slots, in reward order, for the [count] rewards on one page. */
    fun slots(count: Int): List<Int> {
        val result = mutableListOf<Int>()
        for ((row, length) in rowCounts(count).withIndex()) {
            for (col in columns(length)) result.add(row * ROW + col)
        }
        return result
    }

    /** Navigation slots (bottom row) used only when [paginated]. */
    const val PREVIOUS_SLOT = SINGLE_PAGE_CAPACITY - 6 // 48
    const val INDICATOR_SLOT = SINGLE_PAGE_CAPACITY - 5 // 49
    const val NEXT_SLOT = SINGLE_PAGE_CAPACITY - 4 // 50
}
