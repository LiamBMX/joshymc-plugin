package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

// ── Snowman set ─────────────────────────────────────────────────────────────
// Survival-only winter holiday armor (issue #1316). Plain Netherite underneath: vanilla
// stats, no attributes, enchants or abilities, unbreakable like the other armor sets.
// Only the look is custom (resourcepack/art/items/snowman_*.py).

private val SNOWMAN_FROST = TextColor.color(0xDCEBFF)

abstract class SnowmanPiece(
    override val id: String,
    override val material: Material,
    name: String,
    private val slot: EquipmentSlot,
) : CustomItem() {

    override val displayName: Component = Component.text(name, SNOWMAN_FROST)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Armor",
        description = listOf("Winter holiday armor,", "packed from fresh snow."),
        usage = "Equip to wear.",
    )

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

class SnowmanHelmet :
    SnowmanPiece("snowman_helmet", Material.NETHERITE_HELMET, "Snowman Helmet", EquipmentSlot.HEAD)

class SnowmanChestplate :
    SnowmanPiece("snowman_chestplate", Material.NETHERITE_CHESTPLATE, "Snowman Chestplate", EquipmentSlot.CHEST)

class SnowmanLeggings :
    SnowmanPiece("snowman_leggings", Material.NETHERITE_LEGGINGS, "Snowman Leggings", EquipmentSlot.LEGS)

class SnowmanBoots : SnowmanPiece("snowman_boots", Material.NETHERITE_BOOTS, "Snowman Boots", EquipmentSlot.FEET)
