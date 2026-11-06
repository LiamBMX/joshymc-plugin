package com.liam.joshymc.manager

import com.liam.joshymc.gamemode.ServerGamemode

/**
 * Which kits the `/kit` menus show and in which category (issue #1379). Bukkit-free.
 *
 * Every kit in kits.yml belongs to one gamemode (`gamemode: survival|lifesteal`). A kit without
 * the key is a Survival kit, since every kit made before this change was, so nothing that already
 * exists shows up on Lifesteal by accident. A kit with a value that isn't a gamemode is shown
 * nowhere (the manager logs it).
 *
 * The category comes from the kit's `category: starter|rank|special` key when it has one, else
 * from its name: `starter` is the Starter Kit, a name in that gamemode's rank order is a Rank Kit,
 * `media` and `discord` are Special Kits, and every other kit is listed with the Rank Kits after
 * the ordered ranks, so no configured kit becomes unreachable.
 */
object KitCatalog {

    enum class Category(val id: String) {
        STARTER("starter"),
        RANK("rank"),
        SPECIAL("special");

        companion object {
            fun fromId(id: String?): Category? = entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) }
        }
    }

    const val STARTER_KIT = "starter"

    /** The Special Kits, in display order. */
    val SPECIAL_ORDER: List<String> = listOf("media", "discord")

    /** Survival's rank kits are the donor tiers, lowest to highest. */
    val DEFAULT_SURVIVAL_RANK_ORDER: List<String> = DonorTiers.ORDER

    /**
     * What a kit's kits.yml entry says about where it belongs. [gamemode] and [category] are the
     * raw values (null = key missing).
     */
    data class Entry(val name: String, val gamemode: String?, val category: String?)

    /** The kit's gamemode: missing = Survival, anything that isn't a gamemode = null (shown nowhere). */
    fun gamemodeOf(raw: String?): ServerGamemode? =
        if (raw == null || raw.isBlank()) ServerGamemode.SURVIVAL else ServerGamemode.fromId(raw)

    /** True when [raw] is set but isn't a gamemode or a category, i.e. worth a warning. */
    fun invalidGamemode(raw: String?): Boolean = raw != null && raw.isNotBlank() && ServerGamemode.fromId(raw) == null
    fun invalidCategory(raw: String?): Boolean = raw != null && raw.isNotBlank() && Category.fromId(raw) == null

    fun visibleIn(entry: Entry, current: ServerGamemode): Boolean = gamemodeOf(entry.gamemode) == current

    fun normalizeOrder(order: List<String>): List<String> =
        order.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    fun categoryOf(entry: Entry, rankOrder: List<String>): Category {
        Category.fromId(entry.category)?.let { return it }
        val name = entry.name.lowercase()
        return when {
            name == STARTER_KIT -> Category.STARTER
            name in normalizeOrder(rankOrder) -> Category.RANK
            name in SPECIAL_ORDER -> Category.SPECIAL
            else -> Category.RANK
        }
    }

    /**
     * The kits of [category] visible in [current], in display order. [entries] are in kits.yml
     * order. Rank kits follow [rankOrder], then the rest in file order; Special kits follow
     * [SPECIAL_ORDER], then the rest in file order; Starter kits keep file order.
     */
    fun kitsFor(category: Category, entries: List<Entry>, current: ServerGamemode, rankOrder: List<String>): List<String> {
        val matching = entries.filter { visibleIn(it, current) && categoryOf(it, rankOrder) == category }
        val preferred = when (category) {
            Category.RANK -> normalizeOrder(rankOrder)
            Category.SPECIAL -> SPECIAL_ORDER
            Category.STARTER -> emptyList()
        }
        val byName = matching.associateBy { it.name.lowercase() }
        val first = preferred.mapNotNull { byName[it] }
        val rest = matching.filter { it !in first }
        return (first + rest).map { it.name }
    }

    /** The donor/rank name a rank kit stands for, or null when the kit isn't one of the ordered ranks. */
    fun rankFor(kitName: String, rankOrder: List<String>): String? =
        kitName.lowercase().takeIf { it in normalizeOrder(rankOrder) }

    /** Main menu (27 slots, middle row only). */
    const val MAIN_SIZE = 27
    const val STARTER_SLOT = 11
    const val RANK_SLOT = 13
    const val SPECIAL_SLOT = 15
    val MAIN_SLOTS: List<Int> = listOf(STARTER_SLOT, RANK_SLOT, SPECIAL_SLOT)
}
