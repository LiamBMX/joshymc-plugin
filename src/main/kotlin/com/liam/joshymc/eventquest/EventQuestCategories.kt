package com.liam.joshymc.eventquest

/**
 * Event Quest categories (issue #1335). Every quest set belongs to exactly one category; sets
 * without a `category` key are FREE, so every quest set written before categories existed stays
 * where players already had it, with its progress (rows are keyed by set id, never by category).
 *
 * Bukkit-free so the rules can be checked by `scripts/EventQuestCategoryCheck.kt`.
 */
enum class EventQuestCategory(
    val id: String,
    val defaultName: String,
    val defaultIcon: String,
    /** Player-facing name typed in commands (issue #1443: Elite is shown as Monthly; [id] stays `elite`). */
    val commandName: String = id,
    /** Earlier default display names, read as "not customized" so old config files show [defaultName]. */
    val legacyDefaultNames: Set<String> = emptySet()
) {
    FREE("free", "Free Event Quests", "EMERALD"),
    // Issue #1443: renamed to Monthly Event Quests for players. The id, config key (`categories.elite`),
    // `category: elite` values and progress rows are unchanged; `monthly` is accepted as an alias.
    ELITE("elite", "Monthly Event Quests", "ENCHANTED_GOLDEN_APPLE",
        commandName = "monthly", legacyDefaultNames = setOf("Elite Event Quests"));

    companion object {
        fun fromId(raw: String?): EventQuestCategory? =
            raw?.trim()?.let { r ->
                entries.firstOrNull { it.id.equals(r, ignoreCase = true) || it.commandName.equals(r, ignoreCase = true) }
            }

        /**
         * A set's `category` value: missing or blank = [FREE] (every legacy set), a known id = that
         * category, anything else = null so the set is skipped with a warning instead of a typo
         * like `monthlyy` quietly landing in Free.
         */
        fun parse(raw: String?): EventQuestCategory? = if (raw.isNullOrBlank()) FREE else fromId(raw)
    }
}

/** `event-quests.categories.<id>` settings. A missing section gives the defaults below. */
data class EventQuestCategorySettings(
    val category: EventQuestCategory,
    val enabled: Boolean = true,
    val displayName: String = category.defaultName,
    /** Permission needed to view, progress and claim this category; null = everyone. */
    val permission: String? = null,
    /** Material name for the category icon in the Event Quests menu. */
    val icon: String = category.defaultIcon
) {
    companion object {
        /** Builds settings from raw config values; wrong types and blanks fall back to the defaults. */
        fun parse(category: EventQuestCategory, enabled: Any?, displayName: Any?, permission: Any?, icon: Any?) =
            EventQuestCategorySettings(
                category = category,
                enabled = enabled as? Boolean ?: true,
                displayName = (displayName as? String)?.trim()
                    ?.takeIf { it.isNotEmpty() && it !in category.legacyDefaultNames } ?: category.defaultName,
                permission = (permission as? String)?.trim()?.takeIf { it.isNotEmpty() },
                icon = (icon as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: category.defaultIcon
            )
    }
}

/**
 * Who may see, progress and claim a quest set. The same rule gates the category menu, the
 * category page, the set page, progress tracking and claiming, so a player who can't open a
 * set can't earn or claim its reward either. Rewards that were already claimed but not yet
 * delivered are still handed over (they were earned).
 */
object EventQuestAccess {
    enum class Denial(val message: String) {
        SYSTEM_DISABLED("Event Quests are disabled right now."),
        CATEGORY_DISABLED("This category isn't available right now."),
        CATEGORY_PERMISSION("You don't have access to this category."),
        SET_PERMISSION("You don't have access to this quest set.")
    }

    fun check(
        systemEnabled: Boolean,
        settings: EventQuestCategorySettings,
        setPermission: String?,
        hasPermission: (String) -> Boolean
    ): Denial? = when {
        !systemEnabled -> Denial.SYSTEM_DISABLED
        !settings.enabled -> Denial.CATEGORY_DISABLED
        settings.permission != null && !hasPermission(settings.permission) -> Denial.CATEGORY_PERMISSION
        setPermission != null && !hasPermission(setPermission) -> Denial.SET_PERMISSION
        else -> null
    }

    /** Category-level check only (the menu icons and the category page itself). */
    fun checkCategory(systemEnabled: Boolean, settings: EventQuestCategorySettings, hasPermission: (String) -> Boolean): Denial? =
        check(systemEnabled, settings, null, hasPermission)
}

/** One quest set as the category summary sees it. */
data class EventQuestSetStatus(val open: Boolean, val completed: Boolean, val accessible: Boolean)

/** Counts on a category icon: sets the player can work on right now and sets they've finished. */
data class EventQuestCategorySummary(val total: Int, val available: Int, val completed: Int) {
    companion object {
        fun of(sets: List<EventQuestSetStatus>): EventQuestCategorySummary {
            val visible = sets.filter { it.accessible }
            return EventQuestCategorySummary(
                total = visible.size,
                available = visible.count { it.open && !it.completed },
                completed = visible.count { it.completed }
            )
        }
    }
}

/** Slots of the Event Quests menus. Bottom-row navigation follows GuiStyle (Back = size-9, Close = size-5). */
object EventQuestLayout {
    const val MENU_SIZE = 27
    const val FREE_SLOT = 11
    const val ELITE_SLOT = 15
    /** Issue #1385: Back to the `/quests` main menu. */
    const val MENU_BACK_SLOT = MENU_SIZE - 9
    const val MENU_CLOSE_SLOT = MENU_SIZE - 5

    const val PAGE_SIZE = 54
    const val PAGE_BACK_SLOT = PAGE_SIZE - 9
    const val PAGE_CLOSE_SLOT = PAGE_SIZE - 5
    /** "No quests available" / "Coming soon" item on an empty category page. */
    const val EMPTY_SLOT = 22
    val SET_SLOTS: List<Int> = (10..16) + (19..25) + (28..34) + (37..43)

    /**
     * Issue #1439: on the Free page the black filler blends into the dark slot background, so the
     * unused slots of rows 2-5 (18..53) get lime panes instead. Rows 0-1 keep the black filler.
     */
    val FREE_ACCENT_SLOTS: IntRange = 18 until PAGE_SIZE

    /** Whether an unused slot of [category]'s page gets the accent pane instead of the standard filler. */
    fun usesAccentFiller(category: EventQuestCategory, slot: Int): Boolean =
        category == EventQuestCategory.FREE && slot in FREE_ACCENT_SLOTS

    /**
     * Issue #1440: the Elite category page has a purple pane border across the top row (0..8)
     * and the row right above the navigation (36..44). Sets sit in the three rows between them
     * (rows 1..3, columns 1..7), centered both ways, so one set lands in the middle at slot 22.
     */
    val ELITE_BORDER_SLOTS: List<Int> = (0..8) + (36..44)
    const val ELITE_FIRST_ROW = 1
    const val ELITE_ROWS = 3
    const val ELITE_COLUMNS = 7
    const val ELITE_CAPACITY = ELITE_ROWS * ELITE_COLUMNS

    /** Slots for [count] Elite sets (capped at [ELITE_CAPACITY]) in reading order, centered in rows 1..3. */
    fun eliteSetSlots(count: Int): List<Int> {
        val n = count.coerceIn(0, ELITE_CAPACITY)
        if (n == 0) return emptyList()
        val rows = (n + ELITE_COLUMNS - 1) / ELITE_COLUMNS
        val firstRow = ELITE_FIRST_ROW + (ELITE_ROWS - rows) / 2
        val result = mutableListOf<Int>()
        var remaining = n
        for (r in 0 until rows) {
            // Spread evenly across rows; each row is centered and mirror-symmetric (SettingsLayout rules).
            val inRow = (remaining + (rows - r) - 1) / (rows - r)
            result += com.liam.joshymc.manager.SettingsLayout.rowSlots(inRow).map { (firstRow + r) * 9 + it }
            remaining -= inRow
        }
        return result
    }

    fun categorySlot(category: EventQuestCategory): Int = when (category) {
        EventQuestCategory.FREE -> FREE_SLOT
        EventQuestCategory.ELITE -> ELITE_SLOT
    }

    // ── Issue #1438: one set's page (54 slots) ──
    /** The set's reward (claim button), centered above the quests instead of the top row. */
    const val SET_REWARD_SLOT = 13
    /** "N% complete" info, unchanged. */
    const val SET_INFO_SLOT = 40
    /** Most quests a set can have (EventQuestManager.MAX_QUESTS). */
    const val SET_MAX_QUESTS = 10
    private const val QUEST_ROW_A = 2
    private const val QUEST_ROW_B = 3
    private const val ROW_WIDTH = 5

    /**
     * Slots for a set's [count] quests, in quest order, centered under the reward: up to 5 in one
     * row (slot 22 is the center column), more split over two rows (top row gets the extra one).
     * Even counts in a row leave the center column empty so the row stays symmetric.
     */
    fun questSlots(count: Int): List<Int> {
        require(count in 0..SET_MAX_QUESTS) { "count $count outside 0..$SET_MAX_QUESTS" }
        if (count <= ROW_WIDTH) return centeredRow(QUEST_ROW_A, count)
        val top = (count + 1) / 2
        return centeredRow(QUEST_ROW_A, top) + centeredRow(QUEST_ROW_B, count - top)
    }

    private fun centeredRow(row: Int, n: Int): List<Int> {
        val center = row * 9 + 4
        val half = n / 2
        val offsets = if (n % 2 == 1) (-half..half).toList()
            else (-half..-1).toList() + (1..half).toList()
        return offsets.map { center + it }
    }
}
