package com.liam.joshymc.quest

/**
 * Bukkit-free rules for which broken blocks count as a HARVEST_CROP harvest, shared by the
 * Event Quests ([com.liam.joshymc.manager.EventQuestManager]) and the Daily/Weekly quests
 * ([com.liam.joshymc.manager.QuestCycleManager]).
 *
 * Age-based crops (wheat, carrots, potatoes, beetroots, nether wart, ...) count once fully grown.
 * Blocks with no growth stage (melons, sugar cane, ...) count whenever they're broken. Brown and
 * Red Mushrooms have no age either and used to be in neither group, so a HARVEST_CROP quest naming
 * them never moved (issue #1374). Mushrooms drop themselves, so like the Event Quests'
 * always-harvestable blocks a mushroom the player placed themselves never counts (place/break
 * loops); naturally generated and spread mushrooms do.
 */
object HarvestRules {

    val MUSHROOMS: Set<String> = setOf("BROWN_MUSHROOM", "RED_MUSHROOM")

    /** Event Quests: blocks with no growth stage, counted unless the player placed them. */
    val EVENT_ALWAYS_HARVESTABLE: Set<String> = setOf(
        "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO",
        "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES", "WEEPING_VINES"
    ) + MUSHROOMS

    /** Daily/Weekly quests: blocks counted at any growth stage. */
    val CYCLE_ALWAYS_HARVESTABLE: Set<String> = setOf(
        "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO", "NETHER_WART",
        "COCOA", "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES", "WEEPING_VINES"
    ) + MUSHROOMS

    /** Folds the stem/plant halves of multi-block plants onto the name quests are configured with. */
    fun normalize(name: String): String = when (name) {
        "KELP_PLANT" -> "KELP"
        "TWISTING_VINES_PLANT" -> "TWISTING_VINES"
        "WEEPING_VINES_PLANT" -> "WEEPING_VINES"
        else -> name
    }

    private fun fullyGrown(age: Int?, maxAge: Int?): Boolean = age != null && maxAge != null && age == maxAge

    /**
     * Whether breaking [blockName] counts toward an Event Quest HARVEST_CROP objective.
     * [age]/[maxAge] are null for blocks without an age property.
     */
    fun countsForEventQuest(blockName: String, age: Int?, maxAge: Int?, playerPlaced: Boolean): Boolean {
        val normalized = normalize(blockName)
        // Planting seeds is a BlockPlaceEvent, so every farmed crop is "player placed". A crop only
        // reaches its maximum age by growing, so the place-then-break guard applies to the
        // always-harvestable blocks but not to grown age-based crops.
        return if (normalized in EVENT_ALWAYS_HARVESTABLE) !playerPlaced else fullyGrown(age, maxAge)
    }

    /**
     * Whether breaking [blockName] counts toward a Daily/Weekly HARVEST_CROP objective. Unchanged for
     * every block it already counted; mushrooms are new and skip player-placed ones.
     */
    fun countsForCycleQuest(blockName: String, age: Int?, maxAge: Int?, playerPlaced: Boolean): Boolean {
        val normalized = normalize(blockName)
        if (normalized in MUSHROOMS) return !playerPlaced
        return fullyGrown(age, maxAge) || normalized in CYCLE_ALWAYS_HARVESTABLE
    }

    /** Event Quest filter match: null = any crop, else the exact (normalized) block name. */
    fun matchesEventFilter(filter: String?, blockName: String): Boolean =
        filter == null || filter == normalize(blockName)

    /** Daily/Weekly target match: `ANY` or the block name, case-insensitive. */
    fun matchesCycleTarget(target: String, blockName: String): Boolean =
        target == "ANY" || target.equals(normalize(blockName), ignoreCase = true)
}
