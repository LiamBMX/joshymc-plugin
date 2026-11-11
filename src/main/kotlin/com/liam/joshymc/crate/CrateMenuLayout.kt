package com.liam.joshymc.crate

import com.liam.joshymc.manager.CratePreviewLayout

/**
 * Slot layouts for the crate browser (`/crate`) and for a Preview opened from it (issue #1514),
 * kept Bukkit-free.
 *
 * Browser: a header row (info item at [INFO_SLOT]), then content rows, then a bottom row with
 * Close (and Previous / Next when there is more than one page). Monthly crates come first, then
 * permanent ones; each section starts a new row whose first column holds the section label and
 * whose crates are centered in columns 1..7 (up to [PER_ROW] per row). At most
 * [MAX_CONTENT_ROWS] content rows per page.
 *
 * Preview from the browser: the reward rows (same centering as [CratePreviewLayout], at most
 * [PREVIEW_CAPACITY] per page) plus a bottom row with Back, Previous, info, Next and Close. The
 * Preview a player gets by clicking a crate block keeps the [CratePreviewLayout] look.
 */
object CrateMenuLayout {

    const val ROW = 9
    const val PER_ROW = 7
    const val MAX_CONTENT_ROWS = 4
    const val INFO_SLOT = 4

    enum class Section { MONTHLY, PERMANENT }

    /** [index] = position in that section's crate list. */
    data class Cell(val section: Section, val index: Int)

    data class Page(
        val size: Int,
        val page: Int,
        val pageCount: Int,
        val labels: Map<Int, Section>,
        val cells: Map<Int, Cell>,
        val previous: Int?,
        val next: Int?,
        val close: Int,
    )

    private data class Row(val section: Section, val start: Int, val count: Int)

    private fun rows(monthly: Int, permanent: Int): List<Row> {
        val out = mutableListOf<Row>()
        for ((section, count) in listOf(Section.MONTHLY to monthly.coerceAtLeast(0), Section.PERMANENT to permanent.coerceAtLeast(0))) {
            var start = 0
            while (start < count) {
                out += Row(section, start, minOf(PER_ROW, count - start))
                start += PER_ROW
            }
        }
        return out
    }

    fun pageCount(monthly: Int, permanent: Int): Int {
        val n = rows(monthly, permanent).size
        return if (n == 0) 1 else (n - 1) / MAX_CONTENT_ROWS + 1
    }

    /** Column indices (0..6 of the 7-wide area) centering [n] items; even counts skip the middle. */
    fun columns(n: Int): List<Int> {
        val count = n.coerceIn(0, PER_ROW)
        if (count == 0) return emptyList()
        if (count % 2 == 1) {
            val start = (PER_ROW - count) / 2
            return (start until start + count).toList()
        }
        val half = count / 2
        val center = PER_ROW / 2
        return (center - half until center).toList() + (center + 1..center + half).toList()
    }

    fun page(monthly: Int, permanent: Int, page: Int): Page {
        val all = rows(monthly, permanent)
        val pages = pageCount(monthly, permanent)
        val p = page.coerceIn(0, pages - 1)
        val onPage = all.drop(p * MAX_CONTENT_ROWS).take(MAX_CONTENT_ROWS)
        val size = (onPage.size.coerceAtLeast(1) + 2) * ROW
        val labels = mutableMapOf<Int, Section>()
        val cells = mutableMapOf<Int, Cell>()
        for ((i, row) in onPage.withIndex()) {
            val rowStart = (i + 1) * ROW
            if (i == 0 || onPage[i - 1].section != row.section) labels[rowStart] = row.section
            for ((k, col) in columns(row.count).withIndex()) {
                cells[rowStart + 1 + col] = Cell(row.section, row.start + k)
            }
        }
        return Page(
            size = size,
            page = p,
            pageCount = pages,
            labels = labels,
            cells = cells,
            previous = if (p > 0) size - 7 else null,
            next = if (p < pages - 1) size - 3 else null,
            close = size - 5,
        )
    }

    // ── Preview opened from the browser ─────────────────

    const val PREVIEW_CAPACITY = 45

    fun previewPageCount(total: Int): Int = if (total <= 0) 1 else (total - 1) / PREVIEW_CAPACITY + 1

    fun previewPageStart(total: Int, page: Int): Int = page.coerceIn(0, previewPageCount(total) - 1) * PREVIEW_CAPACITY

    fun previewPageSize(total: Int, page: Int): Int =
        (total - previewPageStart(total, page)).coerceIn(0, PREVIEW_CAPACITY)

    /** Same on every page: the rows the largest page needs (at least one) plus the button row. */
    fun previewSize(total: Int): Int {
        val largest = minOf(total.coerceAtLeast(0), PREVIEW_CAPACITY)
        val rows = ((largest + ROW - 1) / ROW).coerceAtLeast(1)
        return rows * ROW + ROW
    }

    /** Reward slots for [count] rewards on one page (always above the button row). */
    fun previewSlots(count: Int): List<Int> = CratePreviewLayout.slots(count.coerceIn(0, PREVIEW_CAPACITY))

    fun previewBack(size: Int) = size - 9
    fun previewPrevious(size: Int) = size - 7
    fun previewInfo(size: Int) = size - 5
    fun previewNext(size: Int) = size - 3
    fun previewClose(size: Int) = size - 1
}
