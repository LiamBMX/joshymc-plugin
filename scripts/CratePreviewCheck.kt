// Checks the crate Preview GUI layout (issue #1414): sized to the rewards, no glass rows,
// centered rows, every reward exactly once and in order, pagination with a navigation row.
// The Gradle build has no test source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/crate-preview-check.init.gradle.kts cratePreviewCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.manager.CratePreviewLayout as L
import java.io.File
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun rowsOf(slots: List<Int>): Map<Int, List<Int>> = slots.groupBy { it / L.ROW }.mapValues { (_, s) -> s.map { it % L.ROW } }

fun main() {
    // ── Examples from the issue ─────────────────────────
    check("7 rewards = one row", L.size(7) == 9)
    check("7 rewards centered in columns 1..7", L.slots(7) == (1..7).toList())
    check("14 rewards = 18 slots (November)", L.size(14) == 18)
    check("14 rewards = two centered rows of 7",
        L.slots(14) == (1..7).toList() + (10..16).toList())
    check("21 rewards = 27 slots", L.size(21) == 27)
    check("21 rewards = three centered rows of 7", L.rowCounts(21) == listOf(7, 7, 7) &&
        rowsOf(L.slots(21)).values.all { it == (1..7).toList() })
    check("27 rewards = 27 slots", L.size(27) == 27)
    check("27 rewards = three full rows", L.slots(27) == (0 until 27).toList())
    check("54 rewards = one full 54-slot page", L.size(54) == 54 && L.pageCount(54) == 1 && L.slots(54) == (0 until 54).toList())

    // ── Small / empty lists ─────────────────────────────
    check("0 rewards = 9 slots, no slots, one page", L.size(0) == 9 && L.slots(0).isEmpty() && L.pageCount(0) == 1)
    check("negative count doesn't crash", L.size(-3) == 9 && L.slots(-3).isEmpty() && L.pageSize(-3, 0) == 0)
    check("1 reward centered", L.slots(1) == listOf(4))
    check("2 rewards symmetric", L.slots(2) == listOf(3, 5))
    check("8 rewards one symmetric row", L.slots(8) == listOf(0, 1, 2, 3, 5, 6, 7, 8))

    // ── Every single-page count ─────────────────────────
    for (n in 1..L.SINGLE_PAGE_CAPACITY) {
        val slots = L.slots(n)
        val size = L.size(n)
        check("$n: one slot per reward", slots.size == n)
        check("$n: no duplicate slots", slots.toSet().size == n)
        check("$n: slots in bounds", slots.all { it in 0 until size })
        check("$n: reading order kept", slots == slots.sorted())
        check("$n: smallest size", size == ((n + 8) / 9) * 9)
        check("$n: no empty row", (0 until size / L.ROW).all { row -> slots.any { it / L.ROW == row } })
        check("$n: rows balanced", L.rowCounts(n).let { it.max() - it.min() <= 1 })
        for ((_, cols) in rowsOf(slots)) {
            check("$n: row symmetrical", cols.map { 8 - it }.sorted() == cols)
        }
        check("$n: not paginated", !L.paginated(n) && L.pageCount(n) == 1)
    }

    // ── Pagination ──────────────────────────────────────
    for (total in listOf(55, 90, 91, 100, 135, 200)) {
        val pages = L.pageCount(total)
        check("$total: paginated", L.paginated(total))
        check("$total: page count", pages == (total + L.PAGE_CAPACITY - 1) / L.PAGE_CAPACITY)
        check("$total: 54-slot pages", L.size(total) == 54)
        val shown = mutableListOf<Int>()
        for (page in 0 until pages) {
            val start = L.pageStart(total, page)
            val count = L.pageSize(total, page)
            check("$total p$page: starts after the previous page", start == shown.size)
            val slots = L.slots(count)
            check("$total p$page: fits above the nav row", slots.all { it < L.PAGE_CAPACITY })
            check("$total p$page: one slot per reward", slots.size == count && slots.toSet().size == count)
            repeat(count) { shown.add(start + it) }
        }
        check("$total: every reward once, in order", shown == (0 until total).toList())
    }
    check("nav row is the bottom row of 54", listOf(L.PREVIOUS_SLOT, L.INDICATOR_SLOT, L.NEXT_SLOT).all { it / 9 == 5 })
    check("nav buttons don't overlap rewards", L.slots(L.PAGE_CAPACITY).none { it >= 45 })
    check("out-of-range page clamps", L.pageStart(100, 99) == L.pageStart(100, 2) && L.pageStart(100, -1) == 0)

    // ── Preview GUI wiring ──────────────────────────────
    val manager = File("src/main/kotlin/com/liam/joshymc/manager/CrateManager.kt").readText()
    val preview = manager.substringAfter("fun openPreview(").substringBefore("// --- Crate browser support")
    check("preview title kept", "PREVIEW_GUI_TITLE_PREFIX = \"Preview: \"" in manager &&
        "Component.text(PREVIEW_GUI_TITLE_PREFIX)" in preview)
    check("preview has no glass filler", "GLASS" !in preview && "filler" !in preview && ".fill(" !in preview)
    check("preview sized by the layout", "CratePreviewLayout.size(" in preview && "val size = 45" !in preview)
    check("preview slots from the layout", "CratePreviewLayout.slots(" in preview)
    check("preview nav uses layout slots", "CratePreviewLayout.PREVIOUS_SLOT" in preview &&
        "CratePreviewLayout.NEXT_SLOT" in preview && "CratePreviewLayout.INDICATOR_SLOT" in preview)
    check("preview opened through GuiManager (click/drag protection)", "plugin.guiManager.open(player, gui)" in preview)

    println("Crate preview checks passed ($checks checks).")
}
