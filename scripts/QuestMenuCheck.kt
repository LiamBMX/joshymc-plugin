// Checks the /quests menus (issue #1385): the Bukkit-free layout, that the main menu has only the
// three categories and no filler or Back/Close, that every page has Back + Close, that the Event
// Quests menu keeps Free 11 / Elite 15 and gained Back, that opening menus never touches progress,
// and that the quest commands stay Survival-only. The Gradle build has no test source set, so it
// runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/quest-menu-check.init.gradle.kts questMenuCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.eventquest.EventQuestCategory
import com.liam.joshymc.eventquest.EventQuestLayout
import com.liam.joshymc.gamemode.GamemodeAssignments
import com.liam.joshymc.gamemode.GamemodeFeature
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.quest.QuestMenuLayout
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

private fun source(path: String) = File("src/main/kotlin/com/liam/joshymc/$path").readText()

fun main() {
    // ── Main menu layout ────────────────────────────────
    check("main menu is 27 slots", QuestMenuLayout.MAIN_SIZE == 27)
    check("daily 11, weekly 13, event 15",
        QuestMenuLayout.DAILY_SLOT == 11 && QuestMenuLayout.WEEKLY_SLOT == 13 && QuestMenuLayout.EVENT_SLOT == 15)
    check("main menu uses exactly three slots", QuestMenuLayout.MAIN_SLOTS == listOf(11, 13, 15))

    val quests = source("manager/QuestCycleManager.kt")
    val mainMenu = quests.substringAfter("private fun renderMain(").substringBefore("/** Why [player] can't open Event Quests")
    check("main menu has no filler", "fill(" !in mainMenu && "FILLER" !in mainMenu && "GLASS" !in mainMenu && "border(" !in mainMenu)
    check("main menu has no Back/Close", "GuiStyle.back" !in mainMenu && "GuiStyle.close" !in mainMenu)
    val mainSlotsUsed = Regex("""gui\.setItem\(QuestMenuLayout\.(\w+)""").findAll(mainMenu).map { it.groupValues[1] }.toList()
    check("main menu sets only the three category slots", mainSlotsUsed == listOf("DAILY_SLOT", "WEEKLY_SLOT", "EVENT_SLOT"))
    check("daily icon opens daily page", "openDailyGui(p)" in mainMenu)
    check("weekly icon opens weekly page", "openWeeklyGui(p)" in mainMenu)
    check("event icon opens the existing Event Quests menu", "plugin.eventQuestManager.openGui(p)" in mainMenu)
    check("event icon keeps the /eventquests permission", "\"joshymc.eventquests\"" in quests)

    // ── Daily / Weekly pages ────────────────────────────
    check("pages are 36 slots", QuestMenuLayout.PAGE_SIZE == 36)
    check("Back bottom-left, Close bottom-center",
        QuestMenuLayout.BACK_SLOT == 27 && QuestMenuLayout.CLOSE_SLOT == 31)
    check("three quests sit centered in row 1", QuestMenuLayout.questSlots(3) == listOf(12, 13, 14))
    check("seven quests fill row 1", QuestMenuLayout.questSlots(7) == (10..16).toList())
    check("more than seven are capped", QuestMenuLayout.questSlots(12).size == QuestMenuLayout.MAX_QUESTS)
    check("no quests, no slots", QuestMenuLayout.questSlots(0).isEmpty())
    val fixed = listOf(QuestMenuLayout.HEADER_SLOT, QuestMenuLayout.BONUS_SLOT, QuestMenuLayout.QUEST_MASTER_SLOT,
        QuestMenuLayout.BACK_SLOT, QuestMenuLayout.CLOSE_SLOT, QuestMenuLayout.INFO_SLOT)
    check("fixed page slots are distinct and inside the chest", fixed.toSet().size == fixed.size && fixed.all { it in 0 until 36 })
    check("quest row never hits a fixed slot",
        (1..QuestMenuLayout.MAX_QUESTS).all { n -> QuestMenuLayout.questSlots(n).none { it in fixed } })
    val page = quests.substringAfter("private fun renderCycle(").substringBefore("private fun showQuestDetails(")
    check("page Back returns to the main menu", "GuiStyle.back(\"Back to Quests\")) { p, _ -> openGui(p) }" in page)
    check("page Close closes", "GuiStyle.close()) { p, _ -> p.closeInventory() }" in page)
    check("page keeps the completion bonus", "bonusItem(\"Daily Completion\"" in page && "bonusItem(\"Weekly Completion\"" in page)
    check("page keeps Quest Master", "questMasterItem(" in page)
    check("page shows the effective target", "effectiveTarget(uuid, quest)" in page)

    // ── Data safety: rendering only reads ───────────────
    val gui = quests.substringAfter("// ── GUI ──").substringBefore("private fun formatTimeLeft(")
    for (writer in listOf("setProgress(", "incrementProgress(", "completeQuest(", "claimQuestRewardOnce(", "claimDailyBonusOnce(",
            "claimWeeklyBonusOnce(", "flushProgress(", "flushAllProgress(", "databaseManager", "forceDailyReset(", "forceWeeklyReset(")) {
        check("quest menus never call $writer", writer !in gui)
    }
    check("refresh only redraws the exact quest menu", "getOpenGui(player) !== tracked.gui" in quests)

    // ── Event Quests menu ───────────────────────────────
    check("event menu still 27 slots", EventQuestLayout.MENU_SIZE == 27)
    check("free 11, elite 15 kept",
        EventQuestLayout.categorySlot(EventQuestCategory.FREE) == 11 && EventQuestLayout.categorySlot(EventQuestCategory.ELITE) == 15)
    check("event menu Back bottom-left", EventQuestLayout.MENU_BACK_SLOT == 18 && EventQuestLayout.MENU_CLOSE_SLOT == 22)
    val events = source("manager/EventQuestManager.kt")
    val eventMenu = events.substringAfter("fun openGui(player: Player) {").substringBefore("/** The same gates as `/quests`")
    check("event menu Back opens /quests", "plugin.questCycleManager.openGui(p)" in eventMenu)
    check("event menu has no filler", "fill(" !in eventMenu)
    check("event Back needs the /quests permission", "\"joshymc.quests\"" in events && "GamemodeFeature.QUESTS" in events)

    // ── Commands and gamemode scope ─────────────────────
    for (name in listOf("quests", "daily", "eventquests")) {
        check("/$name stays Survival-only", GamemodeAssignments.COMMANDS[name] == GamemodeScope.SURVIVAL)
    }
    check("quest features stay Survival-only",
        GamemodeFeature.QUESTS.scope == GamemodeScope.SURVIVAL && GamemodeFeature.EVENT_QUESTS.scope == GamemodeScope.SURVIVAL)
    check("/daily opens the daily page", "openDailyGui(sender)" in source("command/DailyCommand.kt"))
    check("/quests opens the main menu", "plugin.questCycleManager.openGui(sender)" in source("command/QuestCycleCommand.kt"))
    val pluginYml = File("src/main/resources/plugin.yml").readText()
    check("/quests aliases kept", "aliases: [quest, q, questboard, questbook]" in pluginYml)
    check("/eventquests aliases kept", "aliases: [eq, eventquest]" in pluginYml)

    println("Quest menu checks passed ($checks checks).")
}
