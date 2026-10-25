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

// ── Krampus set ─────────────────────────────────────────────────────────────
// Survival-only December armor (issue #1306). Plain Netherite underneath: vanilla stats,
// no attributes, enchants or abilities, unbreakable like the other armor sets. Only the
// look is custom (resourcepack/art/items/krampus_*.py).

private val KRAMPUS_CRIMSON = TextColor.color(0xC8302C)

abstract class KrampusPiece(
    override val id: String,
    override val material: Material,
    name: String,
    private val slot: EquipmentSlot,
) : CustomItem() {

    override val displayName: Component = Component.text(name, KRAMPUS_CRIMSON)
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Armor",
        description = listOf("Cursed armor of the winter night,", "forged for holiday terror."),
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

class KrampusHelmet :
    KrampusPiece("krampus_helmet", Material.NETHERITE_HELMET, "Krampus Helmet", EquipmentSlot.HEAD)

class KrampusChestplate :
    KrampusPiece("krampus_chestplate", Material.NETHERITE_CHESTPLATE, "Krampus Chestplate", EquipmentSlot.CHEST)

class KrampusLeggings :
    KrampusPiece("krampus_leggings", Material.NETHERITE_LEGGINGS, "Krampus Leggings", EquipmentSlot.LEGS)

class KrampusBoots : KrampusPiece("krampus_boots", Material.NETHERITE_BOOTS, "Krampus Boots", EquipmentSlot.FEET)
