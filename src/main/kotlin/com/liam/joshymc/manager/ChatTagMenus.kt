package com.liam.joshymc.manager

/**
 * Layout and classification rules for the `/tags` menus (issue #1393), kept Bukkit-free.
 *
 * - The main menu is a 27-slot chest holding only Bundled Chat Tags ([BUNDLED_SLOT]) and
 *   Special Chat Tags ([SPECIAL_SLOT]). Every other slot stays empty.
 * - The bundle browser and every tag list are 54-slot chests: entries in slots 0..44 in reading
 *   order, then Previous ([PREV_SLOT]), Back ([BACK_SLOT]), Remove Current Tag ([REMOVE_SLOT])
 *   and Next ([NEXT_SLOT]) in the bottom row. Nothing else, no filler.
 *
 * Classification follows how a category's tags are actually unlocked, not its name:
 * the voucher category is the only one whose tags each carry their own
 * `joshymc.tag.voucher.<id>` node and can be redeemed from a physical Chat Tag Voucher
 * ([ChatTagManager.isVoucherTag]), so it is [Kind.SPECIAL]. Every other loaded category is a
 * themed collection, [Kind.BUNDLED]. Ownership is never decided here: it is always the per-tag
 * [ChatTagManager.canUse] check (tag permission or voucher unlock), so no bundle grants anything.
 */
object ChatTagMenus {

    enum class Kind { BUNDLED, SPECIAL }

    const val MAIN_SIZE = 27
    const val BUNDLED_SLOT = 12
    const val SPECIAL_SLOT = 14

    const val BROWSE_SIZE = 54
    const val PAGE_SIZE = 45
    const val PREV_SLOT = 45
    const val BACK_SLOT = 49
    const val REMOVE_SLOT = 51
    const val NEXT_SLOT = 53

    fun kindOf(category: String): Kind =
        if (category == ChatTagDefaults.VOUCHER_CATEGORY) Kind.SPECIAL else Kind.BUNDLED

    /** Bundled categories in their tags.yml order. */
    fun bundles(categories: List<String>): List<String> = categories.filter { kindOf(it) == Kind.BUNDLED }

    fun totalPages(count: Int): Int = maxOf(1, (count + PAGE_SIZE - 1) / PAGE_SIZE)

    /** [page] clamped to the pages a [count]-entry list has (zero-based). */
    fun clampPage(page: Int, count: Int): Int = page.coerceIn(0, totalPages(count) - 1)

    /** The entries shown on [page] (clamped), at most [PAGE_SIZE]. */
    fun <T> pageOf(entries: List<T>, page: Int): List<T> =
        entries.drop(clampPage(page, entries.size) * PAGE_SIZE).take(PAGE_SIZE)

    /** Slots for [count] entries on one page: 0, 1, 2, ... capped at [PAGE_SIZE]. */
    fun slots(count: Int): List<Int> = (0 until count.coerceIn(0, PAGE_SIZE)).toList()

    fun hasPrevious(page: Int, count: Int): Boolean = clampPage(page, count) > 0
    fun hasNext(page: Int, count: Int): Boolean = clampPage(page, count) < totalPages(count) - 1

    /** "Vibe" for a bundle, "Special Chat Tags" for the voucher category. */
    fun displayName(category: String): String =
        if (kindOf(category) == Kind.SPECIAL) "Special Chat Tags"
        else category.split('_', '-', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
            .ifEmpty { category }

    /** The sample message used by tag previews: `[Tag] PlayerName: Hello!`. */
    const val PREVIEW_MESSAGE = "Hello!"
}
