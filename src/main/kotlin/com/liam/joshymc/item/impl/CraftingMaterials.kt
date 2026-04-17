package com.liam.joshymc.item.impl

import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

class VoidShard : CustomItem() {

    override val id = "void_shard"
    override val material = Material.PRISMARINE_SHARD
    override val hasGlint = true

    override val displayName: Component = Component.text("Void Shard", NamedTextColor.DARK_PURPLE)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Crafting Material",
        description = listOf(
            "A shard infused with void energy.",
            "Drops from Endermen, Shulkers, and the Ender Dragon.",
        ),
        usage = "Used in crafting recipes.",
    )
}

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

class InfernoCore : CustomItem() {

    override val id = "inferno_core"
    override val material = Material.MAGMA_CREAM
    override val hasGlint = true

    override val displayName: Component = Component.text("Inferno Core", NamedTextColor.GOLD)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Crafting Material",
        description = listOf(
            "The burning heart of a nether creature.",
            "Drops from Blazes and Magma Cubes.",
        ),
        usage = "Used in crafting recipes.",
    )
}

class CrystalEssence : CustomItem() {

    override val id = "crystal_essence"
    override val material = Material.AMETHYST_SHARD
    override val hasGlint = true

    override val displayName: Component = Component.text("Crystal Essence", NamedTextColor.AQUA)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Crafting Material",
        description = listOf(
            "Pure crystallized essence.",
            "Drops from mining Diamond and Emerald ore.",
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
