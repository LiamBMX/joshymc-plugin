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
// Back cosmetic (issue #1516), shared by Survival and Lifesteal since issue #1591: the
// item is stat-for-stat a vanilla Netherite Chestplate and the wings are a display entity
// with no hitbox, hidden in combat and in Ranked matches. A plain vanilla Netherite Chestplate
// underneath: vanilla armor, toughness, knockback resistance and durability, normal
// enchants and repair, no gliding and no abilities. While it is worn, BackCosmeticManager
// draws the big 3D wings (resourcepack/art/items/eagle_wings.py, about 2 blocks across)
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

    // Issue #1591: no gamemode scope. Nothing about the item differs from a vanilla Netherite
    // Chestplate (no extra stats, no gliding, not unbreakable), so it is safe in Lifesteal;
    // BackCosmeticRules keeps the wings hidden while combat-tagged or in a Ranked match.
    override val gamemodeScope: GamemodeScope? = null

    override val backModel: NamespacedKey get() = NamespacedKey(Joshymc.instance, id)

    /**
     * Must match BACK_SCALE in the art module. Issue #1571: 1.25 made the wings 3.8 blocks
     * across with the tips 0.8 blocks over the head, which looked stretched and detached;
     * 0.65 is about 2 blocks tip to tip with the tips just over the head.
     */
    override val backScale = 0.65

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
