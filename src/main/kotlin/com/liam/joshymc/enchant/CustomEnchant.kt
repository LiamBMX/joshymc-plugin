package com.liam.joshymc.enchant

import org.bukkit.inventory.ItemStack

/**
 * Defines a custom enchantment that can be applied to items.
 *
 * Enchant data is stored in the item's PDC as `joshymc:enchant_<id>` with the level as value.
 * Lore is rendered in vanilla-style aqua text with roman numeral levels.
 */
data class CustomEnchant(
    /** Unique ID used in PDC key (e.g., "lifesteal") */
    val id: String,
    /** Display name shown in lore (e.g., "Lifesteal") */
    val displayName: String,
    /** Maximum level this enchant can reach */
    val maxLevel: Int,
    /** Which item types this enchant can be applied to */
    val target: EnchantTarget,
    /** Enchants that conflict with this one (by ID) */
    val conflicts: Set<String> = emptySet(),
    /** Description shown in enchant table / help */
    val description: String = ""
)

enum class EnchantTarget {
    SWORD,
    AXE,
    BOW,
    CROSSBOW,
    TRIDENT,
    HELMET,
    CHESTPLATE,
    LEGGINGS,
    BOOTS,
    ALL_ARMOR,
    PICKAXE,
    SHOVEL,
    HOE,
    ALL_TOOLS,
    MELEE_WEAPON,
    ALL;

    fun canApplyTo(item: ItemStack): Boolean {
        val name = item.type.name
        return when (this) {
            SWORD -> name.endsWith("_SWORD")
            AXE -> name.endsWith("_AXE")
            BOW -> name == "BOW"
            CROSSBOW -> name == "CROSSBOW"
            TRIDENT -> name == "TRIDENT"
            HELMET -> name.endsWith("_HELMET") || name == "TURTLE_HELMET"
            CHESTPLATE -> name.endsWith("_CHESTPLATE") || name == "ELYTRA"
            LEGGINGS -> name.endsWith("_LEGGINGS")
            BOOTS -> name.endsWith("_BOOTS")
            ALL_ARMOR -> HELMET.canApplyTo(item) || CHESTPLATE.canApplyTo(item)
                    || LEGGINGS.canApplyTo(item) || BOOTS.canApplyTo(item)
            PICKAXE -> name.endsWith("_PICKAXE")
            SHOVEL -> name.endsWith("_SHOVEL")
            HOE -> name.endsWith("_HOE")
            ALL_TOOLS -> PICKAXE.canApplyTo(item) || SHOVEL.canApplyTo(item)
                    || HOE.canApplyTo(item) || AXE.canApplyTo(item)
            MELEE_WEAPON -> SWORD.canApplyTo(item) || AXE.canApplyTo(item)
            ALL -> true
        }
    }
}
