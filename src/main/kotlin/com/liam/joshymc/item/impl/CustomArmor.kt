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
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

private fun setArmorModel(meta: ItemMeta, setName: String, slot: EquipmentSlot) {
    val equippable = meta.equippable
    equippable.slot = slot
    equippable.model = NamespacedKey(Joshymc.instance, setName)
    meta.setEquippable(equippable)
}

// ── Void Armor Set (Diamond, dark purple) ───────────────────────────────────

private val VOID_COLOR = TextColor.color(0xAA00AA)
private val VOID_LORE = LoreBuilder.build(
    type = "Void Armor",
    description = listOf("Full set bonus: Slow Falling, -50% Ender Pearl cooldown, +10% Speed"),
    usage = "Equip full set for bonus.",
)

private fun applyVoidMeta(meta: ItemMeta) {
    meta.addEnchant(Enchantment.PROTECTION, 4, true)
    meta.addEnchant(Enchantment.UNBREAKING, 3, true)
    meta.isUnbreakable = true
}

class VoidHelmet : CustomItem() {
    override val id = "void_helmet"
    override val material = Material.DIAMOND_HELMET
    override val hasGlint = true
    override val displayName: Component = Component.text("Void Helmet", VOID_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = VOID_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyVoidMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "void_helmet"))
        setArmorModel(meta, "void", EquipmentSlot.HEAD)
    }
}

class VoidChestplate : CustomItem() {
    override val id = "void_chestplate"
    override val material = Material.DIAMOND_CHESTPLATE
    override val hasGlint = true
    override val displayName: Component = Component.text("Void Chestplate", VOID_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = VOID_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyVoidMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "void_chestplate"))
        setArmorModel(meta, "void", EquipmentSlot.CHEST)
    }
}

class VoidLeggings : CustomItem() {
    override val id = "void_leggings"
    override val material = Material.DIAMOND_LEGGINGS
    override val hasGlint = true
    override val displayName: Component = Component.text("Void Leggings", VOID_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = VOID_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyVoidMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "void_leggings"))
        setArmorModel(meta, "void", EquipmentSlot.LEGS)
    }
}

class VoidBoots : CustomItem() {
    override val id = "void_boots"
    override val material = Material.DIAMOND_BOOTS
    override val hasGlint = true
    override val displayName: Component = Component.text("Void Boots", VOID_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = VOID_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyVoidMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "void_boots"))
        setArmorModel(meta, "void", EquipmentSlot.FEET)
    }
}

// ── Inferno Armor Set (Netherite, orange/gold) ──────────────────────────────

private val INFERNO_COLOR = TextColor.color(0xFFAA00)
private val INFERNO_LORE = LoreBuilder.build(
    type = "Inferno Armor",
    description = listOf("Full set bonus: Fire Immunity, Lava Swimming, Blazes passive"),
    usage = "Equip full set for bonus.",
)

private fun applyInfernoMeta(meta: ItemMeta) {
    meta.addEnchant(Enchantment.PROTECTION, 4, true)
    meta.addEnchant(Enchantment.FIRE_PROTECTION, 4, true)
    meta.addEnchant(Enchantment.UNBREAKING, 3, true)
    meta.isUnbreakable = true
}

class InfernoHelmet : CustomItem() {
    override val id = "inferno_helmet"
    override val material = Material.NETHERITE_HELMET
    override val hasGlint = true
    override val displayName: Component = Component.text("Inferno Helmet", INFERNO_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = INFERNO_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyInfernoMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "inferno_helmet"))
        setArmorModel(meta, "inferno", EquipmentSlot.HEAD)
    }
}

class InfernoChestplate : CustomItem() {
    override val id = "inferno_chestplate"
    override val material = Material.NETHERITE_CHESTPLATE
    override val hasGlint = true
    override val displayName: Component = Component.text("Inferno Chestplate", INFERNO_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = INFERNO_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyInfernoMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "inferno_chestplate"))
        setArmorModel(meta, "inferno", EquipmentSlot.CHEST)
    }
}

class InfernoLeggings : CustomItem() {
    override val id = "inferno_leggings"
    override val material = Material.NETHERITE_LEGGINGS
    override val hasGlint = true
    override val displayName: Component = Component.text("Inferno Leggings", INFERNO_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = INFERNO_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyInfernoMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "inferno_leggings"))
        setArmorModel(meta, "inferno", EquipmentSlot.LEGS)
    }
}

class InfernoBoots : CustomItem() {
    override val id = "inferno_boots"
    override val material = Material.NETHERITE_BOOTS
    override val hasGlint = true
    override val displayName: Component = Component.text("Inferno Boots", INFERNO_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = INFERNO_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyInfernoMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "inferno_boots"))
        setArmorModel(meta, "inferno", EquipmentSlot.FEET)
    }
}

// ── Crystal Armor Set (Diamond, aqua) ───────────────────────────────────────

private val CRYSTAL_COLOR = TextColor.color(0x55FFFF)
private val CRYSTAL_LORE = LoreBuilder.build(
    type = "Crystal Armor",
    description = listOf("Full set bonus: +20% XP, Fortune I on all mining"),
    usage = "Equip full set for bonus.",
)

private fun applyCrystalMeta(meta: ItemMeta) {
    meta.addEnchant(Enchantment.PROTECTION, 4, true)
    meta.addEnchant(Enchantment.UNBREAKING, 3, true)
    meta.isUnbreakable = true
}

class CrystalHelmet : CustomItem() {
    override val id = "crystal_helmet"
    override val material = Material.DIAMOND_HELMET
    override val hasGlint = true
    override val displayName: Component = Component.text("Crystal Helmet", CRYSTAL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = CRYSTAL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyCrystalMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "crystal_helmet"))
        setArmorModel(meta, "crystal", EquipmentSlot.HEAD)
    }
}

class CrystalChestplate : CustomItem() {
    override val id = "crystal_chestplate"
    override val material = Material.DIAMOND_CHESTPLATE
    override val hasGlint = true
    override val displayName: Component = Component.text("Crystal Chestplate", CRYSTAL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = CRYSTAL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyCrystalMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "crystal_chestplate"))
        setArmorModel(meta, "crystal", EquipmentSlot.CHEST)
    }
}

class CrystalLeggings : CustomItem() {
    override val id = "crystal_leggings"
    override val material = Material.DIAMOND_LEGGINGS
    override val hasGlint = true
    override val displayName: Component = Component.text("Crystal Leggings", CRYSTAL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = CRYSTAL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyCrystalMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "crystal_leggings"))
        setArmorModel(meta, "crystal", EquipmentSlot.LEGS)
    }
}

class CrystalBoots : CustomItem() {
    override val id = "crystal_boots"
    override val material = Material.DIAMOND_BOOTS
    override val hasGlint = true
    override val displayName: Component = Component.text("Crystal Boots", CRYSTAL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = CRYSTAL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applyCrystalMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "crystal_boots"))
        setArmorModel(meta, "crystal", EquipmentSlot.FEET)
    }
}

// ── Soul Armor Set (Netherite, dark aqua) ───────────────────────────────────

private val SOUL_COLOR = TextColor.color(0x00AAAA)
private val SOUL_LORE = LoreBuilder.build(
    type = "Soul Armor",
    description = listOf("Full set bonus: Wither Immunity, 3% Lifesteal, Night Vision"),
    usage = "Equip full set for bonus.",
)

private fun applySoulMeta(meta: ItemMeta) {
    meta.addEnchant(Enchantment.PROTECTION, 4, true)
    meta.addEnchant(Enchantment.UNBREAKING, 3, true)
    meta.isUnbreakable = true
}

class SoulHelmet : CustomItem() {
    override val id = "soul_helmet"
    override val material = Material.NETHERITE_HELMET
    override val hasGlint = true
    override val displayName: Component = Component.text("Soul Helmet", SOUL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = SOUL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applySoulMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "soul_helmet"))
        setArmorModel(meta, "soul", EquipmentSlot.HEAD)
    }
}

class SoulChestplate : CustomItem() {
    override val id = "soul_chestplate"
    override val material = Material.NETHERITE_CHESTPLATE
    override val hasGlint = true
    override val displayName: Component = Component.text("Soul Chestplate", SOUL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = SOUL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applySoulMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "soul_chestplate"))
        setArmorModel(meta, "soul", EquipmentSlot.CHEST)
    }
}

class SoulLeggings : CustomItem() {
    override val id = "soul_leggings"
    override val material = Material.NETHERITE_LEGGINGS
    override val hasGlint = true
    override val displayName: Component = Component.text("Soul Leggings", SOUL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = SOUL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applySoulMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "soul_leggings"))
        setArmorModel(meta, "soul", EquipmentSlot.LEGS)
    }
}

class SoulBoots : CustomItem() {
    override val id = "soul_boots"
    override val material = Material.NETHERITE_BOOTS
    override val hasGlint = true
    override val displayName: Component = Component.text("Soul Boots", SOUL_COLOR)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = SOUL_LORE
    override fun applyMeta(meta: ItemMeta) {
        applySoulMeta(meta)
        meta.setItemModel(NamespacedKey(Joshymc.instance, "soul_boots"))
        setArmorModel(meta, "soul", EquipmentSlot.FEET)
    }
}
