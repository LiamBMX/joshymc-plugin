package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

// ── September Autumn Collection ──────────────────────────────────────────────
// Seasonal reward items: retextured vanilla gear with normal vanilla behavior.
// No custom combat/mining mechanics, no crafting recipes, no natural acquisition.

class AutumnsEdge : CustomItem() {

    override val id = "autumns_edge"
    override val material = Material.NETHERITE_SWORD
    override val hasGlint = true

    override val displayName: Component = Component.text("Autumn's Edge", TextColor.color(0xB33A1E))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "autumns_edge"))
    }
}

class HarvestScythe : CustomItem() {

    override val id = "harvest_scythe"
    override val material = Material.NETHERITE_HOE
    override val hasGlint = true

    override val displayName: Component = Component.text("Harvest Scythe", TextColor.color(0xE8971E))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "harvest_scythe"))
    }
}

class OrchardPickaxe : CustomItem() {

    override val id = "orchard_pickaxe"
    override val material = Material.NETHERITE_PICKAXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Orchard Pickaxe", TextColor.color(0xFFA23D))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "orchard_pickaxe"))
    }
}

class GoldenCrest : CustomItem() {

    override val id = "golden_crest"
    override val material = Material.NETHERITE_HELMET
    override val hasGlint = true

    override val displayName: Component = Component.text("Golden Crest", TextColor.color(0xFFD700))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "golden_crest"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.HEAD
        equippable.model = NamespacedKey(Joshymc.instance, "golden_crest")
        meta.setEquippable(equippable)
    }
}

class FallingLeaf : CustomItem() {

    override val id = "falling_leaf"
    override val material = Material.ELYTRA
    override val hasGlint = true

    override val displayName: Component = Component.text("Falling Leaf", TextColor.color(0xD2601A))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "falling_leaf"))
    }
}
