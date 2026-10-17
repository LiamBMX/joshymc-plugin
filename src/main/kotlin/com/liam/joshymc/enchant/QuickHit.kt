package com.liam.joshymc.enchant

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack

/**
 * Quick Hit (issue #1209): a main-hand enchant that shortens the melee attack recharge.
 *
 * Vanilla's recharge time is `20 / attackSpeed` ticks, so cutting it by a fraction `r` means
 * multiplying the player's effective attack speed by `1 / (1 - r)`. That is done with one
 * transient ADD_MULTIPLIED_TOTAL modifier of `1 / (1 - r) - 1` on the player (see
 * QuickHitListener), which scales whatever the held item and every other modifier already
 * produce, so a sword stays faster than an axe and custom items keep their own speeds.
 */
object QuickHit {
    const val ID = "quick_hit"
    const val DISPLAY_NAME = "Quick Hit"
    const val DESCRIPTION = "Reduces melee attack cooldown."

    /** Default cooldown reductions, in percent, for levels I, II and III. */
    val DEFAULT_REDUCTIONS = listOf(10.0, 20.0, 30.0)

    /** Highest level the config may define. */
    const val MAX_CONFIG_LEVEL = 5

    /** A 50% cut is double attack speed; anything past that is no longer a reasonable enchant. */
    const val MAX_REDUCTION_PERCENT = 50.0

    /** Attack-speed multiplier for a cooldown cut of [reductionPercent] percent: 1 / (1 - r). */
    fun speedMultiplier(reductionPercent: Double): Double = 1.0 / (1.0 - reductionPercent / 100.0)

    /** ADD_MULTIPLIED_TOTAL amount that multiplies attack speed by [speedMultiplier]. */
    fun modifierAmount(reductionPercent: Double): Double = speedMultiplier(reductionPercent) - 1.0
}

/** Item groups Quick Hit can go on, chosen in `quick-hit.categories`. */
enum class QuickHitCategory(val key: String) {
    SWORD("sword"),
    AXE("axe"),
    PICKAXE("pickaxe"),
    SHOVEL("shovel"),
    HOE("hoe"),
    MACE("mace"),
    TRIDENT("trident"),
    SPEAR("spear");

    fun matches(item: ItemStack): Boolean {
        val name = item.type.name
        return when (this) {
            SWORD -> name.endsWith("_SWORD")
            AXE -> name.endsWith("_AXE")
            PICKAXE -> name.endsWith("_PICKAXE")
            SHOVEL -> name.endsWith("_SHOVEL")
            HOE -> name.endsWith("_HOE")
            MACE -> name == "MACE"
            TRIDENT -> name == "TRIDENT"
            SPEAR -> name.endsWith("_SPEAR")
        }
    }

    companion object {
        val DEFAULTS: Set<QuickHitCategory> = setOf(SWORD, AXE, PICKAXE, SHOVEL, HOE, MACE, TRIDENT)

        fun fromKey(key: String): QuickHitCategory? = entries.firstOrNull { it.key == key.trim().lowercase() }
    }
}

/**
 * Validated `quick-hit` settings. [reductions] holds one cooldown cut (percent) per level,
 * index 0 = level I, so its size is the max level. [problems] lists every value that was
 * rejected and replaced by its default.
 */
data class QuickHitSettings(
    val enabled: Boolean,
    val reductions: List<Double>,
    val categories: Set<QuickHitCategory>,
    val problems: List<String> = emptyList()
) {
    val maxLevel: Int get() = reductions.size

    fun canApplyTo(item: ItemStack): Boolean = categories.any { it.matches(item) }

    /** Cooldown cut for an item's stored level; levels above the max count as the max, 0 or less as none. */
    fun reductionFor(level: Int): Double {
        if (level <= 0 || reductions.isEmpty()) return 0.0
        return reductions[level.coerceAtMost(maxLevel) - 1]
    }

    companion object {
        val DEFAULT = QuickHitSettings(true, QuickHit.DEFAULT_REDUCTIONS, QuickHitCategory.DEFAULTS)

        fun load(section: ConfigurationSection?): QuickHitSettings {
            if (section == null) return DEFAULT
            val problems = mutableListOf<String>()

            val maxLevel = section.getInt("max-level", QuickHit.DEFAULT_REDUCTIONS.size).let {
                if (it in 1..QuickHit.MAX_CONFIG_LEVEL) it else {
                    problems += "max-level $it is not 1-${QuickHit.MAX_CONFIG_LEVEL}, using ${QuickHit.DEFAULT_REDUCTIONS.size}"
                    QuickHit.DEFAULT_REDUCTIONS.size
                }
            }

            val reductions = mutableListOf<Double>()
            for (level in 1..maxLevel) {
                val path = "cooldown-reduction-percent.$level"
                val default = QuickHit.DEFAULT_REDUCTIONS.getOrNull(level - 1)
                val raw = if (section.isSet(path)) section.getDouble(path, Double.NaN) else null
                val value = when {
                    raw != null && raw > 0.0 && raw <= QuickHit.MAX_REDUCTION_PERCENT -> raw
                    default != null -> {
                        if (raw != null) problems += "$path ($raw) must be above 0 and at most ${QuickHit.MAX_REDUCTION_PERCENT}, using $default"
                        default
                    }
                    else -> {
                        problems += "$path is missing or invalid, so max level is ${level - 1}"
                        break
                    }
                }
                reductions += value
            }

            val categories = if (section.isList("categories")) {
                val parsed = mutableSetOf<QuickHitCategory>()
                for (key in section.getStringList("categories")) {
                    val category = QuickHitCategory.fromKey(key)
                    if (category == null) problems += "unknown category '$key'" else parsed += category
                }
                parsed
            } else {
                QuickHitCategory.DEFAULTS
            }

            return QuickHitSettings(section.getBoolean("enabled", true), reductions, categories, problems)
        }
    }
}
