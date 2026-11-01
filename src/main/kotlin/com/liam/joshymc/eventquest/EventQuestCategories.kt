package com.liam.joshymc.eventquest

/**
 * Event Quest categories (issue #1335). Every quest set belongs to exactly one category; sets
 * without a `category` key are FREE, so every quest set written before categories existed stays
 * where players already had it, with its progress (rows are keyed by set id, never by category).
 *
 * Bukkit-free so the rules can be checked by `scripts/EventQuestCategoryCheck.kt`.
 */
enum class EventQuestCategory(val id: String, val defaultName: String, val defaultIcon: String) {
    FREE("free", "Free Event Quests", "EMERALD"),
    ELITE("elite", "Elite Event Quests", "ENCHANTED_GOLDEN_APPLE");

    companion object {
        fun fromId(raw: String?): EventQuestCategory? =
            raw?.trim()?.let { r -> entries.firstOrNull { it.id.equals(r, ignoreCase = true) } }

        /**
         * A set's `category` value: missing or blank = [FREE] (every legacy set), a known id = that
         * category, anything else = null so the set is skipped with a warning instead of a typo
         * like `elitee` quietly landing in Free.
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
                displayName = (displayName as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: category.defaultName,
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
    const val MENU_CLOSE_SLOT = MENU_SIZE - 5

    const val PAGE_SIZE = 54
    const val PAGE_BACK_SLOT = PAGE_SIZE - 9
    const val PAGE_CLOSE_SLOT = PAGE_SIZE - 5
    /** "No quests available" / "Coming soon" item on an empty category page. */
    const val EMPTY_SLOT = 22
    val SET_SLOTS: List<Int> = (10..16) + (19..25) + (28..34) + (37..43)

    fun categorySlot(category: EventQuestCategory): Int = when (category) {
        EventQuestCategory.FREE -> FREE_SLOT
        EventQuestCategory.ELITE -> ELITE_SLOT
    }
}
