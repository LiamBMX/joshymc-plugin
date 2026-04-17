package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

class MoneyPouchSmall : CustomItem() {

    override val id = "money_pouch_small"
    override val material = Material.PAPER

    override val displayName: Component = Component.text("Small Money Pouch", TextColor.color(0x55FF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Contains \$1,000"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "money_pouch_small"))
    }
}

class MoneyPouchMedium : CustomItem() {

    override val id = "money_pouch_medium"
    override val material = Material.PAPER

    override val displayName: Component = Component.text("Medium Money Pouch", TextColor.color(0xFFFF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Contains \$10,000"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "money_pouch_medium"))
    }
}

class MoneyPouchLarge : CustomItem() {

    override val id = "money_pouch_large"
    override val material = Material.PAPER
    override val hasGlint = true

    override val displayName: Component = Component.text("Large Money Pouch", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Contains \$100,000"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "money_pouch_large"))
    }
}

class XpTome : CustomItem() {

    override val id = "xp_tome"
    override val material = Material.BOOK
    override val hasGlint = true

    override val displayName: Component = Component.text("XP Tome", TextColor.color(0x55FF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Grants 30 experience levels"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "xp_tome"))
    }
}

class SpeedApple : CustomItem() {

    override val id = "speed_apple"
    override val material = Material.GOLDEN_APPLE

    override val displayName: Component = Component.text("Speed Apple", TextColor.color(0x55FFFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Speed III for 2 minutes"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "speed_apple"))
    }
}

class StrengthApple : CustomItem() {

    override val id = "strength_apple"
    override val material = Material.GOLDEN_APPLE

    override val displayName: Component = Component.text("Strength Apple", TextColor.color(0xFF5555))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Strength II for 1 minute"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "strength_apple"))
    }
}

class GiantsBrew : CustomItem() {

    override val id = "giants_brew"
    override val material = Material.POTION
    override val hasGlint = true

    override val displayName: Component = Component.text("Giant's Brew", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("+4 hearts for 5 minutes"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "giants_brew"))
    }
}

class MinersBrew : CustomItem() {

    override val id = "miners_brew"
    override val material = Material.POTION

    override val displayName: Component = Component.text("Miner's Brew", TextColor.color(0x55FFFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf("Haste II + Night Vision for 10 minutes"),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "miners_brew"))
    }
}

class WardensHeart : CustomItem() {

    override val id = "wardens_heart"
    override val material = Material.SCULK_CATALYST
    override val hasGlint = true

    override val displayName: Component = Component.text("Warden's Heart", TextColor.color(0xAA0000))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Consumable",
        description = listOf(
            "Resistance II + Strength II for 60 seconds",
            "Dropped by the Warden",
        ),
        usage = "Right-click to open",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "wardens_heart"))
    }
}
