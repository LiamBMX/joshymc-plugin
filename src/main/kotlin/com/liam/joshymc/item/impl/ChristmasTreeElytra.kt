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
import org.bukkit.Sound
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.meta.ItemMeta

// ── Christmas Tree Elytra ───────────────────────────────────────────────────
// Survival-only elytra (issue #1397). A plain vanilla elytra underneath: it glides,
// takes durability, repairs with phantom membrane and takes elytra enchants as usual,
// and has no abilities. Only the look is custom
// (resourcepack/art/items/christmas_tree_elytra.py): the item model, and
// equipment/christmas_tree_elytra.json for the worn wings and the garland harness.
// Given out only by admins with /joshymc give (joshymc.give, op).

class ChristmasTreeElytra : CustomItem() {

    override val id = "christmas_tree_elytra"
    override val material = Material.ELYTRA

    override val displayName: Component = Component.text("Christmas Tree Elytra", TextColor.color(0x3FAE4A))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Elytra",
        description = listOf("A festive evergreen, trimmed for the holidays."),
        usage = "Wear in the chest slot to glide.",
    )

    override val gamemodeScope = GamemodeScope.SURVIVAL

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, id))
        // Setting the equippable replaces the elytra's vanilla one, so restore what the
        // vanilla elytra has (chest slot, elytra equip sound, no damage when the wearer
        // is hurt) and only swap the worn look.
        val equippable = meta.equippable
        equippable.slot = EquipmentSlot.CHEST
        equippable.setEquipSound(Sound.ITEM_ARMOR_EQUIP_ELYTRA)
        equippable.isDamageOnHurt = false
        equippable.model = NamespacedKey(Joshymc.instance, id)
        meta.setEquippable(equippable)
    }
}
