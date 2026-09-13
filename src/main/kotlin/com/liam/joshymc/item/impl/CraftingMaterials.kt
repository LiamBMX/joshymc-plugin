package com.liam.joshymc.item.impl

import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

// Custom crafting materials are admin-granted only (via /joshymc give) — they must
// never enter the economy through /sell or the Sell Wand just because they share a
// vanilla Material with a sellable item (e.g. Void Shard = PRISMARINE_SHARD).
val CRAFTING_MATERIAL_IDS = setOf(
    "soul_fragment",
    "ancient_rune",
    "enchanted_dust",
)

class SoulFragment : CustomItem() {

    override val id = "soul_fragment"
    override val material = Material.GHAST_TEAR
    override val hasGlint = true

    override val displayName: Component = Component.text("Soul Fragment", NamedTextColor.DARK_AQUA)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Crafting Material",
        description = listOf(
            "A fragment of a trapped soul.",
            "Drops from Wither Skeletons, Ghasts, and the Wither.",
        ),
        usage = "Used in crafting recipes.",
    )
}

class AncientRune : CustomItem() {

    override val id = "ancient_rune"
    override val material = Material.BRICK
    override val hasGlint = true

    override val displayName: Component = Component.text("Ancient Rune", NamedTextColor.GOLD)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Material",
        description = listOf(
            "An ancient rune of immense power.",
            "Ultra rare drop from Elder Guardians, Wardens, and Withers.",
        ),
        usage = "Used in crafting recipes.",
    )
}

class EnchantedDust : CustomItem() {

    override val id = "enchanted_dust"
    override val material = Material.GLOWSTONE_DUST
    override val hasGlint = true

    override val displayName: Component = Component.text("Enchanted Dust", NamedTextColor.LIGHT_PURPLE)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Crafting Material",
        description = listOf(
            "Magical dust from enchanting.",
            "Obtained from enchanting or grindstoning items.",
        ),
        usage = "Used in crafting recipes.",
    )
}
