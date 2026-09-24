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

// ── Woodland Collection ─────────────────────────────────────────────────────
// Retextured vanilla gear with normal vanilla behavior. No custom mechanics.

class LumberjacksLegacy : CustomItem() {

    override val id = "lumberjacks_legacy"
    override val material = Material.NETHERITE_AXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Lumberjack's Legacy", TextColor.color(0xC0392B))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "lumberjacks_legacy"))
    }
}

class WoodlandHunter : CustomItem() {

    override val id = "woodland_hunter"
    override val material = Material.CROSSBOW
    override val hasGlint = true

    override val displayName: Component = Component.text("Woodland Hunter", TextColor.color(0x5E8C3A))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "woodland_hunter"))
    }
}

class Maplefang : CustomItem() {

    override val id = "maplefang"
    override val material = Material.TRIDENT
    override val hasGlint = true

    override val displayName: Component = Component.text("Maplefang", TextColor.color(0xD9502A))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "maplefang"))
    }
}

class AutumnWanderer : CustomItem() {

    override val id = "autumn_wanderer"
    override val material = Material.NETHERITE_BOOTS
    override val hasGlint = true

    override val displayName: Component = Component.text("Autumn Wanderer", TextColor.color(0xB5793C))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "autumn_wanderer"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.FEET
        equippable.model = NamespacedKey(Joshymc.instance, "autumn_wanderer")
        meta.setEquippable(equippable)
    }
}

class Hearthkeeper : CustomItem() {

    override val id = "hearthkeeper"
    override val material = Material.SHIELD
    override val hasGlint = true

    override val displayName: Component = Component.text("Hearthkeeper", TextColor.color(0xE2862F))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "hearthkeeper"))
    }
}
