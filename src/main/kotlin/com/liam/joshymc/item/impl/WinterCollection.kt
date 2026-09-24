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

// ── Winter Collection ───────────────────────────────────────────────────────
// Retextured vanilla gear with normal vanilla behavior. No custom mechanics.

class Frostbite : CustomItem() {

    override val id = "frostbite"
    override val material = Material.NETHERITE_SWORD
    override val hasGlint = true

    override val displayName: Component = Component.text("Frostbite", TextColor.color(0x7FDBFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "frostbite"))
    }
}

class GlacierBreaker : CustomItem() {

    override val id = "glacier_breaker"
    override val material = Material.NETHERITE_PICKAXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Glacier Breaker", TextColor.color(0x9FE3F5))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "glacier_breaker"))
    }
}

class IceSkates : CustomItem() {

    override val id = "ice_skates"
    override val material = Material.NETHERITE_BOOTS
    override val hasGlint = true

    override val displayName: Component = Component.text("Ice Skates", TextColor.color(0xE8F6FF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "ice_skates"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.FEET
        equippable.model = NamespacedKey(Joshymc.instance, "ice_skates")
        meta.setEquippable(equippable)
    }
}

class WingsOfTheBlizzard : CustomItem() {

    override val id = "wings_of_the_blizzard"
    override val material = Material.ELYTRA
    override val hasGlint = true

    override val displayName: Component = Component.text("Wings of the Blizzard", TextColor.color(0xB8E8FF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "wings_of_the_blizzard"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.CHEST
        equippable.model = NamespacedKey(Joshymc.instance, "wings_of_the_blizzard")
        meta.setEquippable(equippable)
    }
}

class WintersWrath : CustomItem() {

    override val id = "winters_wrath"
    override val material = Material.BOW
    override val hasGlint = true

    override val displayName: Component = Component.text("Winter's Wrath", TextColor.color(0x5BC0EB))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "winters_wrath"))
    }
}
