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

// ── Viking set ──────────────────────────────────────────────────────────────
// Issue #1217. Plain Netherite underneath: vanilla stats, no attributes or abilities,
// unbreakable like the other armor sets. The helmet's 3D model carries the horned
// helmet and the wearer's big orange beard.

private val VIKING_GINGER = TextColor.color(0xE0782A)

/** Every Viking piece id, in the order `/joshymc give viking_set` hands them out. */
val VIKING_SET_IDS = listOf("viking_helmet", "viking_chestplate", "viking_leggings", "viking_boots")

abstract class VikingPiece(
    override val id: String,
    override val material: Material,
    name: String,
    private val slot: EquipmentSlot,
) : CustomItem() {

    override val hasGlint = true

    override val displayName: Component = Component.text(name, VIKING_GINGER)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
        val equippable = meta.equippable
        equippable.slot = slot
        // Helmet: no equipment asset, so the client draws the 3D item model (beard
        // included) on the head. Other pieces: worn layer equipment/<id>.json.
        equippable.model = if (slot == EquipmentSlot.HEAD) null else NamespacedKey(Joshymc.instance, id)
        meta.setEquippable(equippable)
    }
}

class VikingHelmet : VikingPiece("viking_helmet", Material.NETHERITE_HELMET, "Viking Helmet", EquipmentSlot.HEAD)

class VikingChestplate : VikingPiece("viking_chestplate", Material.NETHERITE_CHESTPLATE, "Viking Chestplate", EquipmentSlot.CHEST)

class VikingLeggings : VikingPiece("viking_leggings", Material.NETHERITE_LEGGINGS, "Viking Leggings", EquipmentSlot.LEGS)

class VikingBoots : VikingPiece("viking_boots", Material.NETHERITE_BOOTS, "Viking Boots", EquipmentSlot.FEET)
