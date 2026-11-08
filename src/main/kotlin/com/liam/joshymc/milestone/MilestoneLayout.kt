package com.liam.joshymc.milestone

/**
 * Slot layouts of the `/milestones` menus (issue #1420), Bukkit-free. No glass filler: every menu
 * is only as tall as its content needs, and the bottom row uses the shared GuiStyle positions
 * (Back = size-9, Previous = size-7, Close = size-5, Next = size-3) plus one action at size-1.
 */
object MilestoneLayout {

    // ── Main menu (27) ──────────────────────────────────────
    const val MAIN_SIZE = 27
    const val MAIN_SUMMARY = 4
    val MAIN_CATEGORIES: List<Int> = listOf(11, 12, 13, 14, 15)
    const val MAIN_PENDING = 21
    const val MAIN_CLOSE = 22
    const val MAIN_INFO = 23

    // ── Category menu ───────────────────────────────────────
    const val CATEGORY_COLUMNS = 7
    const val CATEGORY_MAX_ROWS = 4
    const val CATEGORY_PAGE_SIZE = CATEGORY_COLUMNS * CATEGORY_MAX_ROWS
    const val CATEGORY_LEVEL = 4

    data class Page(val size: Int, val slots: List<Int>, val page: Int, val pages: Int, val from: Int, val to: Int)

    fun pages(count: Int, pageSize: Int): Int = if (count <= 0) 1 else (count + pageSize - 1) / pageSize

    /** One page of [count] objectives: rows of up to 7 in columns 1-7, the last row centered. */
    fun categoryPage(count: Int, requestedPage: Int): Page {
        val pages = pages(count, CATEGORY_PAGE_SIZE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val from = page * CATEGORY_PAGE_SIZE
        val to = minOf(count, from + CATEGORY_PAGE_SIZE)
        val onPage = to - from
        val rows = ((onPage + CATEGORY_COLUMNS - 1) / CATEGORY_COLUMNS).coerceIn(1, CATEGORY_MAX_ROWS)
        val size = (rows + 2) * 9
        val slots = ArrayList<Int>(onPage)
        for (row in 0 until rows) {
            val inRow = minOf(CATEGORY_COLUMNS, onPage - row * CATEGORY_COLUMNS)
            if (inRow <= 0) break
            val start = 1 + (CATEGORY_COLUMNS - inRow) / 2
            for (col in 0 until inRow) slots += (row + 1) * 9 + start + col
        }
        return Page(size, slots, page, pages, from, to)
    }

    fun backSlot(size: Int) = size - 9
    fun prevSlot(size: Int) = size - 7
    fun closeSlot(size: Int) = size - 5
    fun nextSlot(size: Int) = size - 3
    fun actionSlot(size: Int) = size - 1

    // ── Milestone details (27) ──────────────────────────────
    const val DETAIL_SIZE = 27
    const val DETAIL_HEADER = 4
    const val DETAIL_PROGRESS = 11
    const val DETAIL_REWARDS = 13
    const val DETAIL_UPCOMING = 15
    const val DETAIL_BACK = 18
    const val DETAIL_CLAIM = 22
    const val DETAIL_CLOSE = 26

    /** How many upcoming special tiers the details screen previews. */
    const val UPCOMING_PREVIEW = 4

    // ── Pending rewards ─────────────────────────────────────
    const val PENDING_PAGE_SIZE = 45

    /** Size for [onPage] pending rewards: their rows plus the bottom row (at least 18). */
    fun pendingSize(onPage: Int): Int = (((onPage + 8) / 9).coerceIn(1, 5) + 1) * 9
}
