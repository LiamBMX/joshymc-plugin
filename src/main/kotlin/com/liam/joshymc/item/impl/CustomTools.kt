package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.meta.ItemMeta

class AutoMiner : CustomItem() {

    override val id = "auto_miner"
    override val material = Material.DIAMOND_PICKAXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Auto Miner", TextColor.color(0x55FFFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Tool",
        description = listOf(
            "Right-click to mine a 1x1 tunnel forward",
            "30 second cooldown",
        ),
        usage = "Right-click to activate",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "auto_miner"))
    }
}

class FarmersSickle : CustomItem() {

    override val id = "farmers_sickle"
    override val material = Material.DIAMOND_HOE
    override val hasGlint = true

    override val displayName: Component = Component.text("Farmer's Sickle", TextColor.color(0x55FF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Tool",
        description = listOf(
            "Harvests and replants a 5x5 area",
        ),
        usage = "Right-click crops",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "farmers_sickle"))
    }
}

class LumberjacksAxe : CustomItem() {

    override val id = "lumberjacks_axe"
    override val material = Material.DIAMOND_AXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Lumberjack's Axe", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Tool",
        description = listOf(
            "Fells entire trees in one swing",
            "No sneak required",
        ),
        usage = "Break any log",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "lumberjacks_axe"))
    }
}

class Excavator : CustomItem() {

    override val id = "excavator"
    override val material = Material.DIAMOND_SHOVEL
    override val hasGlint = true

    override val displayName: Component = Component.text("Excavator", TextColor.color(0xFFFF55))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Tool",
        description = listOf(
            "Digs a 3x3 area",
        ),
        usage = "Mine dirt, sand, or gravel",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "excavator"))
    }
}

class MagnetWand : CustomItem() {

    override val id = "magnet_wand"
    override val material = Material.BREEZE_ROD
    override val hasGlint = true

    override val displayName: Component = Component.text("Magnet Wand", TextColor.color(0xFF5555))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Utility Tool",
        description = listOf(
            "Pulls all dropped items within 10 blocks to you",
        ),
        usage = "Right-click to activate (5s cooldown)",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "magnet_wand"))
    }
}
