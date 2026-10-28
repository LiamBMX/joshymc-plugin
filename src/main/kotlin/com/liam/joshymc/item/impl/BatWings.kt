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

// ── Bat Wings ───────────────────────────────────────────────────────────────
// Survival-only elytra (issue #1317). A plain vanilla elytra underneath: it glides,
// takes durability, repairs with phantom membrane and takes elytra enchants as usual,
// and has no abilities. Only the look is custom (resourcepack/art/items/bat_wings.py):
// the item model, and equipment/bat_wings.json for the worn wings and chest harness.

class BatWings : CustomItem() {

    override val id = "bat_wings"
    override val material = Material.ELYTRA

    override val displayName: Component = Component.text("Bat Wings", TextColor.color(0x9AA3B5))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Elytra",
        description = listOf("Become the shadow of the night."),
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
