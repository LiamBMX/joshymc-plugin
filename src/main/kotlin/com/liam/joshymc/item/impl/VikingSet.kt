package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.item.CustomItem
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

// ── Viking set ──────────────────────────────────────────────────────────────
// Survival-only armor (issue #1294). Plain Netherite underneath: vanilla stats, no
// attributes, enchants or abilities, unbreakable like the other armor sets. Only the
// look is custom (resourcepack/art/items/viking_*.py).

private val VIKING_STEEL = TextColor.color(0xB4BBC2)

abstract class VikingPiece(
    override val id: String,
    override val material: Material,
    name: String,
    private val slot: EquipmentSlot,
) : CustomItem() {

    override val displayName: Component = Component.text(name, VIKING_STEEL)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = emptyList()

    override val gamemodeScope = GamemodeScope.SURVIVAL

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

class VikingHelmet : VikingPiece("viking_helmet", Material.NETHERITE_HELMET, "Viking Helmet", EquipmentSlot.HEAD)

class VikingChestplate :
    VikingPiece("viking_chestplate", Material.NETHERITE_CHESTPLATE, "Viking Chestplate", EquipmentSlot.CHEST)

class VikingLeggings :
    VikingPiece("viking_leggings", Material.NETHERITE_LEGGINGS, "Viking Leggings", EquipmentSlot.LEGS)

class VikingBoots : VikingPiece("viking_boots", Material.NETHERITE_BOOTS, "Viking Boots", EquipmentSlot.FEET)
