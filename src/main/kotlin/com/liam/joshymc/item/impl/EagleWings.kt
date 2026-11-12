package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeScope
import com.liam.joshymc.item.BackCosmeticItem
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

// ── Eagle Wings ─────────────────────────────────────────────────────────────
// Survival-only back cosmetic (issue #1516). A plain vanilla Netherite Chestplate
// underneath: vanilla armor, toughness, knockback resistance and durability, normal
// enchants and repair, no gliding and no abilities. While it is worn, BackCosmeticManager
// draws the huge 3D wings (resourcepack/art/items/eagle_wings.py, about 3.7 blocks across)
// on the wearer's back; the worn humanoid layer is the leather harness they hang from.

class EagleWings : CustomItem(), BackCosmeticItem {

    override val id = "eagle_wings"
    override val material = Material.NETHERITE_CHESTPLATE

    override val displayName: Component = Component.text("Eagle Wings", TextColor.color(0xE3B55E))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Chestplate",
        description = listOf("Great feathered wings for your back."),
        usage = "Wear in the chest slot to show the wings.",
    )

    override val gamemodeScope = GamemodeScope.SURVIVAL

    override val backModel: NamespacedKey get() = NamespacedKey(Joshymc.instance, id)

    /** Must match BACK_SCALE in the art module. */
    override val backScale = 1.25

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
        // Same chest slot and equip sound as the vanilla chestplate; only the worn look
        // changes (equipment/eagle_wings.json: the harness).
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.CHEST
        equippable.setEquipSound(Sound.ITEM_ARMOR_EQUIP_NETHERITE)
        equippable.model = NamespacedKey(Joshymc.instance, id)
        meta.setEquippable(equippable)
    }
}
