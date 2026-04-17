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

class VoidBlade : CustomItem() {

    override val id = "void_blade"
    override val material = Material.DIAMOND_SWORD
    override val hasGlint = true

    override val displayName: Component = Component.text("Void Blade", TextColor.color(0xAA00AA))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Weapon",
        description = listOf(
            "+15% damage to Ender mobs",
            "Teleport strike on hit",
        ),
        usage = "Melee",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.SHARPNESS, 5, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "void_blade"))
    }
}

class SoulScythe : CustomItem() {

    override val id = "soul_scythe"
    override val material = Material.NETHERITE_HOE
    override val hasGlint = true

    override val displayName: Component = Component.text("Soul Scythe", TextColor.color(0x00AAAA))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Weapon",
        description = listOf(
            "Lifesteal on hit",
            "Applies Wither on hit",
        ),
        usage = "Melee",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.SHARPNESS, 4, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "soul_scythe"))
    }
}

class InfernoAxe : CustomItem() {

    override val id = "inferno_axe"
    override val material = Material.NETHERITE_AXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Inferno Axe", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Weapon",
        description = listOf(
            "Sets targets ablaze",
            "Fire Aspect III equivalent",
        ),
        usage = "Melee",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.SHARPNESS, 5, true)
        meta.addEnchant(Enchantment.FIRE_ASPECT, 3, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "inferno_axe"))
    }
}

class CrystalMace : CustomItem() {

    override val id = "crystal_mace"
    override val material = Material.MACE
    override val hasGlint = true

    override val displayName: Component = Component.text("Crystal Mace", TextColor.color(0x55FFFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Legendary Weapon",
        description = listOf(
            "AoE ground slam with particles",
            "Deals damage to all nearby on hit",
        ),
        usage = "Melee",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.addEnchant(Enchantment.DENSITY, 3, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "crystal_mace"))
    }
}

class CarrotLauncher : CustomItem() {

    override val id = "carrot_launcher"
    override val material = Material.CARROT_ON_A_STICK
    override val hasGlint = true

    override val displayName: Component = Component.text("Carrot Launcher", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Fun Weapon",
        description = listOf(
            "Shoots explosive carrots",
        ),
        usage = "Right-click to fire",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "carrot_launcher"))
    }
}
