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

// ── Shark Set ───────────────────────────────────────────────────────────────
// Retextured Netherite armor: vanilla stats and enchanting, no attribute modifiers,
// no glint. Unbreakable (issue #1070); otherwise only the look is custom.

private val SHARK_BLUE = TextColor.color(0x3E8FB0)

private fun sharkName(name: String): Component =
    Component.text(name, SHARK_BLUE).decoration(TextDecoration.ITALIC, false)

class SharkHelmet : CustomItem() {

    override val id = "shark_helmet"
    override val material = Material.NETHERITE_HELMET
    override val displayName: Component = sharkName("Shark Helmet")
    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "shark_helmet"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.HEAD
        // No equipment asset: the client draws the 3D item model on the head instead.
        equippable.model = null
        meta.setEquippable(equippable)
    }
}

class SharkChestplate : CustomItem() {

    override val id = "shark_chestplate"
    override val material = Material.NETHERITE_CHESTPLATE
    override val displayName: Component = sharkName("Shark Chestplate")
    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "shark_chestplate"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.CHEST
        equippable.model = NamespacedKey(Joshymc.instance, "shark_chestplate")
        meta.setEquippable(equippable)
    }
}

class SharkLeggings : CustomItem() {

    override val id = "shark_leggings"
    override val material = Material.NETHERITE_LEGGINGS
    override val displayName: Component = sharkName("Shark Leggings")
    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "shark_leggings"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.LEGS
        equippable.model = NamespacedKey(Joshymc.instance, "shark_leggings")
        meta.setEquippable(equippable)
    }
}

class SharkBoots : CustomItem() {

    override val id = "shark_boots"
    override val material = Material.NETHERITE_BOOTS
    override val displayName: Component = sharkName("Shark Boots")
    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, "shark_boots"))
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.FEET
        equippable.model = NamespacedKey(Joshymc.instance, "shark_boots")
        meta.setEquippable(equippable)
    }
}
