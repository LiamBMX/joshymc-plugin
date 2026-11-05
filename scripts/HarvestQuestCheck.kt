// Checks the HARVEST_CROP rules shared by Event Quests and Daily/Weekly quests (issue #1374:
// Brown/Red Mushroom harvests never counted). The Gradle build has no test source set, so it
// runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/harvest-quest-check.init.gradle.kts harvestQuestCheck
//
// Exits non-zero on the first failed check.

import com.liam.joshymc.gamemode.GamemodeFeature
import com.liam.joshymc.gamemode.GamemodeIsolation
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.quest.HarvestRules
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

/**
 * Mirrors the Event Quest listener: one block-break event that already passed
 * `ignoreCancelled = true` adds 1 to every matching quest whose filter matches, capped at the amount.
 */
private class EventQuestSim(val filter: String?, val amount: Int, val repeatable: Boolean) {
    var progress = 0
    var completions = 0
    fun breakBlock(name: String, age: Int? = null, maxAge: Int? = null, placed: Boolean = false, cancelled: Boolean = false) {
        if (!repeatable && completions > 0) return // finished, non-repeatable quests no longer track
        if (cancelled) return // MONITOR + ignoreCancelled: cancelled (protected/claimed) breaks never reach the handler
        if (!HarvestRules.countsForEventQuest(name, age, maxAge, placed)) return
        if (!HarvestRules.matchesEventFilter(filter, name)) return
        progress = minOf(amount, progress + 1)
        if (progress == amount) {
            completions++
            if (repeatable) progress = 0
        }
    }
}

fun main() {
    // ── Mushrooms (the bug) ──
    check("event: natural brown mushroom counts", HarvestRules.countsForEventQuest("BROWN_MUSHROOM", null, null, false))
    check("event: natural red mushroom counts", HarvestRules.countsForEventQuest("RED_MUSHROOM", null, null, false))
    check("event: player-placed brown mushroom does not count (place/break loop)", !HarvestRules.countsForEventQuest("BROWN_MUSHROOM", null, null, true))
    check("cycle: natural brown mushroom counts", HarvestRules.countsForCycleQuest("BROWN_MUSHROOM", null, null, false))
    check("cycle: natural red mushroom counts", HarvestRules.countsForCycleQuest("RED_MUSHROOM", null, null, false))
    check("cycle: player-placed mushroom does not count", !HarvestRules.countsForCycleQuest("RED_MUSHROOM", null, null, true))
    check("mushrooms need no age property", HarvestRules.countsForEventQuest("BROWN_MUSHROOM", null, null, false))

    // ── Only the configured mushroom ──
    check("event: brown filter matches brown", HarvestRules.matchesEventFilter("BROWN_MUSHROOM", "BROWN_MUSHROOM"))
    check("event: brown filter rejects red", !HarvestRules.matchesEventFilter("BROWN_MUSHROOM", "RED_MUSHROOM"))
    check("event: red filter rejects brown", !HarvestRules.matchesEventFilter("RED_MUSHROOM", "BROWN_MUSHROOM"))
    check("cycle: brown target rejects red", !HarvestRules.matchesCycleTarget("BROWN_MUSHROOM", "RED_MUSHROOM"))
    check("cycle: target is case-insensitive", HarvestRules.matchesCycleTarget("brown_mushroom", "BROWN_MUSHROOM"))
    check("cycle: ANY matches mushrooms", HarvestRules.matchesCycleTarget("ANY", "RED_MUSHROOM"))

    // ── Unrelated blocks ──
    for (name in listOf("STONE", "DIRT", "MUSHROOM_STEM", "BROWN_MUSHROOM_BLOCK", "RED_MUSHROOM_BLOCK", "POTTED_BROWN_MUSHROOM", "MYCELIUM")) {
        check("event: $name is not a harvest", !HarvestRules.countsForEventQuest(name, null, null, false))
        check("cycle: $name is not a harvest", !HarvestRules.countsForCycleQuest(name, null, null, false))
    }

    // ── Existing crops unchanged ──
    for (crop in listOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART")) {
        val max = if (crop == "BEETROOTS" || crop == "NETHER_WART") 3 else 7
        check("event: grown $crop counts (even though seeds were placed)", HarvestRules.countsForEventQuest(crop, max, max, true))
        check("event: young $crop does not count", !HarvestRules.countsForEventQuest(crop, 1, max, false))
        check("cycle: grown $crop counts", HarvestRules.countsForCycleQuest(crop, max, max, true))
    }
    check("cycle: young wheat does not count", !HarvestRules.countsForCycleQuest("WHEAT", 2, 7, false))
    check("cycle: young nether wart still counts (always harvestable there)", HarvestRules.countsForCycleQuest("NETHER_WART", 0, 3, false))
    for (name in listOf("SUGAR_CANE", "MELON", "PUMPKIN", "CACTUS", "BAMBOO")) {
        check("event: natural $name counts", HarvestRules.countsForEventQuest(name, null, null, false))
        check("event: placed $name does not count", !HarvestRules.countsForEventQuest(name, null, null, true))
        check("cycle: $name counts (placed too, unchanged)", HarvestRules.countsForCycleQuest(name, null, null, true))
    }
    check("kelp plant folds onto kelp", HarvestRules.matchesEventFilter("KELP", "KELP_PLANT"))
    check("sugar cane age property is irrelevant", HarvestRules.countsForEventQuest("SUGAR_CANE", 3, 15, false))

    // ── Progress: one per event, cancelled breaks ignored, completion at 500, repeatable reset ──
    val quest = EventQuestSim("BROWN_MUSHROOM", 500, repeatable = true)
    quest.breakBlock("BROWN_MUSHROOM")
    check("one break = exactly 1 progress", quest.progress == 1)
    quest.breakBlock("BROWN_MUSHROOM", cancelled = true)
    check("cancelled/protected break adds nothing", quest.progress == 1)
    quest.breakBlock("RED_MUSHROOM")
    check("wrong mushroom adds nothing", quest.progress == 1)
    quest.breakBlock("STONE")
    check("unrelated block adds nothing", quest.progress == 1)
    repeat(498) { quest.breakBlock("BROWN_MUSHROOM") }
    check("499 before the last mushroom", quest.progress == 499 && quest.completions == 0)
    quest.breakBlock("BROWN_MUSHROOM")
    check("completes at 500", quest.completions == 1)
    check("repeatable quest resets after completion", quest.progress == 0)
    repeat(500) { quest.breakBlock("BROWN_MUSHROOM") }
    check("repeatable quest completes again", quest.completions == 2)

    val red = EventQuestSim("RED_MUSHROOM", 3, repeatable = false)
    repeat(5) { red.breakBlock("RED_MUSHROOM") }
    check("red mushroom quest completes once and caps", red.completions == 1 && red.progress == 3)

    // ── Survival only ──
    check("event quest progress is a Survival feature", GamemodeFeature.EVENT_QUESTS.scope == GamemodeScope.SURVIVAL)
    check("daily/weekly quest progress is a Survival feature", GamemodeFeature.QUESTS.scope == GamemodeScope.SURVIVAL)
    check("no progress in Lifesteal while Survival is locked",
        !GamemodeIsolation.allowed(GamemodeScope.SURVIVAL, ServerGamemode.LIFESTEAL, setOf(ServerGamemode.SURVIVAL)))
    check("progress in Survival",
        GamemodeIsolation.allowed(GamemodeScope.SURVIVAL, ServerGamemode.SURVIVAL, setOf(ServerGamemode.SURVIVAL)))

    println("HarvestQuestCheck: all $checks checks passed")
}
