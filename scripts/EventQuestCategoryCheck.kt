// Checks the Event Quest category rules (issue #1335). The Gradle build has no test source set,
// so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/event-quest-category-check.init.gradle.kts eventQuestCategoryCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.eventquest.EventQuestAccess
import com.liam.joshymc.eventquest.EventQuestCategory
import com.liam.joshymc.eventquest.EventQuestCategorySettings
import com.liam.joshymc.eventquest.EventQuestCategorySummary
import com.liam.joshymc.eventquest.EventQuestLayout
import com.liam.joshymc.eventquest.EventQuestSetStatus
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

fun main() {
    // ── Category of a set ──
    check("missing category = Free (legacy sets stay Free)", EventQuestCategory.parse(null) == EventQuestCategory.FREE)
    check("blank category = Free", EventQuestCategory.parse("  ") == EventQuestCategory.FREE)
    check("free parses", EventQuestCategory.parse("free") == EventQuestCategory.FREE)
    check("elite parses case-insensitively", EventQuestCategory.parse(" ELITE ") == EventQuestCategory.ELITE)
    check("typo is rejected, never Free", EventQuestCategory.parse("elitee") == null)
    check("fromId(null) is null", EventQuestCategory.fromId(null) == null)

    // ── Category settings ──
    val defFree = EventQuestCategorySettings.parse(EventQuestCategory.FREE, null, null, null, null)
    check("missing section: enabled", defFree.enabled)
    check("missing section: default name", defFree.displayName == "Free Event Quests")
    check("missing section: no permission", defFree.permission == null)
    val defElite = EventQuestCategorySettings.parse(EventQuestCategory.ELITE, null, null, "", null)
    check("elite default name", defElite.displayName == "Elite Event Quests")
    check("elite: blank permission = everyone (no paid-rank assumption)", defElite.permission == null)
    check("elite default icon", defElite.icon == "ENCHANTED_GOLDEN_APPLE")
    val custom = EventQuestCategorySettings.parse(EventQuestCategory.ELITE, false, " Elite ", " joshymc.eq.elite ", "NETHER_STAR")
    check("custom settings parse", !custom.enabled && custom.displayName == "Elite" &&
        custom.permission == "joshymc.eq.elite" && custom.icon == "NETHER_STAR")
    check("wrong-typed enabled falls back to true", EventQuestCategorySettings.parse(EventQuestCategory.FREE, "no", 5, 7, 1).let {
        it.enabled && it.displayName == "Free Event Quests" && it.permission == null && it.icon == "EMERALD"
    })

    // ── Access ──
    val gated = EventQuestCategorySettings(EventQuestCategory.ELITE, permission = "elite.perm")
    val none: (String) -> Boolean = { false }
    val all: (String) -> Boolean = { true }
    check("open free set allowed", EventQuestAccess.check(true, defFree, null, none) == null)
    check("system disabled wins", EventQuestAccess.check(false, defFree, null, all) == EventQuestAccess.Denial.SYSTEM_DISABLED)
    check("disabled category refused even with perms",
        EventQuestAccess.check(true, defFree.copy(enabled = false), null, all) == EventQuestAccess.Denial.CATEGORY_DISABLED)
    check("category permission enforced", EventQuestAccess.check(true, gated, null, none) == EventQuestAccess.Denial.CATEGORY_PERMISSION)
    check("category permission granted", EventQuestAccess.check(true, gated, null) { it == "elite.perm" } == null)
    check("set permission enforced on top", EventQuestAccess.check(true, gated, "set.perm") { it == "elite.perm" } ==
        EventQuestAccess.Denial.SET_PERMISSION)
    check("both permissions granted", EventQuestAccess.check(true, gated, "set.perm", all) == null)
    check("category-only check ignores set perms", EventQuestAccess.checkCategory(true, gated) { it == "elite.perm" } == null)

    // ── Summary ──
    val summary = EventQuestCategorySummary.of(listOf(
        EventQuestSetStatus(open = true, completed = false, accessible = true),
        EventQuestSetStatus(open = true, completed = true, accessible = true),
        EventQuestSetStatus(open = false, completed = false, accessible = true),
        EventQuestSetStatus(open = true, completed = false, accessible = false)
    ))
    check("summary counts only accessible sets", summary.total == 3)
    check("available = open and unfinished", summary.available == 1)
    check("completed counted", summary.completed == 1)
    check("empty summary", EventQuestCategorySummary.of(emptyList()) == EventQuestCategorySummary(0, 0, 0))

    // ── Layout ──
    check("menu is 27 slots", EventQuestLayout.MENU_SIZE == 27)
    check("free in slot 11", EventQuestLayout.categorySlot(EventQuestCategory.FREE) == 11)
    check("elite in slot 15", EventQuestLayout.categorySlot(EventQuestCategory.ELITE) == 15)
    check("menu close in bottom center", EventQuestLayout.MENU_CLOSE_SLOT == 22)
    val nav = setOf(EventQuestLayout.PAGE_BACK_SLOT, EventQuestLayout.PAGE_CLOSE_SLOT)
    check("page back/close are GuiStyle slots", EventQuestLayout.PAGE_BACK_SLOT == 45 && EventQuestLayout.PAGE_CLOSE_SLOT == 49)
    check("set slots in range", EventQuestLayout.SET_SLOTS.all { it in 0 until EventQuestLayout.PAGE_SIZE })
    check("set slots distinct", EventQuestLayout.SET_SLOTS.toSet().size == EventQuestLayout.SET_SLOTS.size)
    check("set slots never overlap navigation", EventQuestLayout.SET_SLOTS.none { it in nav })
    check("room for every existing set (6+)", EventQuestLayout.SET_SLOTS.size >= 6)

    // ── Issue #1438: set page layout ──
    check("reward in the central area (row 1 center)", EventQuestLayout.SET_REWARD_SLOT == 13)
    check("info slot kept", EventQuestLayout.SET_INFO_SLOT == 40)
    val reserved = nav + setOf(EventQuestLayout.SET_REWARD_SLOT, EventQuestLayout.SET_INFO_SLOT)
    for (n in 1..EventQuestLayout.SET_MAX_QUESTS) {
        val slots = EventQuestLayout.questSlots(n)
        check("$n quests: one slot each", slots.size == n && slots.toSet().size == n)
        check("$n quests: rows 2-3 only", slots.all { it / 9 in 2..3 })
        check("$n quests: never on reward/info/navigation", slots.none { it in reserved })
        check("$n quests: in reading order", slots == slots.sorted())
        for (row in 2..3) {
            val cols = slots.filter { it / 9 == row }.map { it % 9 }
            check("$n quests: row $row symmetric around center", cols.map { 8 - it }.sorted() == cols.sorted())
        }
    }
    check("1 quest under the reward", EventQuestLayout.questSlots(1) == listOf(22))
    check("5 quests fill 20-24", EventQuestLayout.questSlots(5) == (20..24).toList())
    check("10 quests: 20-24 + 29-33", EventQuestLayout.questSlots(10) == (20..24).toList() + (29..33).toList())
    check("even row leaves center empty", EventQuestLayout.questSlots(4) == listOf(20, 21, 23, 24))
    check("manager uses the layout", run {
        val src = File("src/main/kotlin/com/liam/joshymc/manager/EventQuestManager.kt").readText()
        "EventQuestLayout.SET_REWARD_SLOT" in src && "EventQuestLayout.questSlots(" in src && "gui.setItem(4, rewardIcon)" !in src
    })

    // ── Wiring ──
    val yml = File("src/main/resources/event-quests.yml").readText()
    check("bundled config documents categories", yml.contains("categories:") && yml.contains("elite:"))
    val plugin = File("src/main/resources/plugin.yml").readText()
    check("/eventquest alias registered", Regex("""eventquests:[\s\S]*?aliases: \[eq, eventquest]""").containsMatchIn(plugin))

    println("Event Quest category checks passed ($checks checks).")
}
