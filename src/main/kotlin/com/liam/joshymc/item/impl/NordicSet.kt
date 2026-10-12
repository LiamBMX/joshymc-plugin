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

// ── Nordic set ──────────────────────────────────────────────────────────────
// The November crate's armor set (issue #1097). Plain Netherite underneath: vanilla
// stats, no attributes or abilities, unbreakable like the seasonal collections. The
// boots are the old Autumn Wanderer, renamed: their id stays "autumn_wanderer" so
// existing copies keep working (UnbreakableArmorUpgradeListener renames copies still
// called Autumn Wanderer).

private val NORDIC_BRONZE = TextColor.color(0xC9A46E)

abstract class NordicPiece(
    override val id: String,
    override val material: Material,
    name: String,
    private val slot: EquipmentSlot,
) : CustomItem() {

    override val hasGlint = true

    override val displayName: Component = Component.text(name, NORDIC_BRONZE)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override fun applyMeta(meta: ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
        val equippable = meta.equippable
        equippable.slot = slot
        // Helmet: no equipment asset, so the client draws the 3D item model on the head.
        // Other pieces: worn layer equipment/<id>.json (humanoid or humanoid_leggings).
        equippable.model = if (slot == EquipmentSlot.HEAD) null else NamespacedKey(Joshymc.instance, id)
        meta.setEquippable(equippable)
    }
}

class NordicHelm : NordicPiece("nordic_helm", Material.NETHERITE_HELMET, "Nordic Helm", EquipmentSlot.HEAD)

class NordicPlate : NordicPiece("nordic_plate", Material.NETHERITE_CHESTPLATE, "Nordic Plate", EquipmentSlot.CHEST)

class NordicGreaves : NordicPiece("nordic_greaves", Material.NETHERITE_LEGGINGS, "Nordic Greaves", EquipmentSlot.LEGS)

class NordicTreads : NordicPiece("autumn_wanderer", Material.NETHERITE_BOOTS, "Nordic Treads", EquipmentSlot.FEET)
