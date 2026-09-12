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

// ── October Halloween Collection ────────────────────────────────────────────
// Retextured vanilla gear with normal vanilla behavior. No custom mechanics.

class PhantomsGrasp : CustomItem() {

    override val id = "phantoms_grasp"
    override val material = Material.BOW
    override val hasGlint = true

    override val displayName: Component = Component.text("Phantom's Grasp", TextColor.color(0xB19CD9))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "phantoms_grasp"))
    }
}

class Gravedigger : CustomItem() {

    override val id = "gravedigger"
    override val material = Material.NETHERITE_SHOVEL
    override val hasGlint = true

    override val displayName: Component = Component.text("Gravedigger", TextColor.color(0x5C5248))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "gravedigger"))
    }
}

class JackOLanternMask : CustomItem() {

    override val id = "jack_o_lantern_mask"
    override val material = Material.NETHERITE_HELMET
    override val hasGlint = true

    override val displayName: Component = Component.text("Jack-o'-Lantern Mask", TextColor.color(0xFF7518))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "jack_o_lantern_mask"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.HEAD
        equippable.model = NamespacedKey(Joshymc.instance, "jack_o_lantern_mask")
        meta.setEquippable(equippable)
    }
}

class BoneRattler : CustomItem() {

    override val id = "bone_rattler"
    override val material = Material.MACE
    override val hasGlint = true

    override val displayName: Component = Component.text("Bone Rattler", TextColor.color(0xDCD3B4))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "bone_rattler"))
    }
}
