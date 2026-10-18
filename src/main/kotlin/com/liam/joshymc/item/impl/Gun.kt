package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta

/**
 * Gun (issue #1229): a retextured Paper showing a compact futuristic blaster (3D model in
 * resourcepack/art/items/gun.py). Looks only: it has no listener and does nothing when used.
 */
class Gun : CustomItem() {

    companion object {
        const val ID = "gun"
    }

    override val id = ID
    override val material = Material.PAPER

    override val displayName: Component = Component.text("Gun", TextColor.color(0x8CC7EF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Collectible",
        description = listOf("A compact experimental blaster."),
        usage = "A collectible for display.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, ID))
        meta.setMaxStackSize(1)
    }
}
