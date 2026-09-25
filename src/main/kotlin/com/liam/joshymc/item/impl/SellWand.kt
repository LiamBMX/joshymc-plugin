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

class SellWand : CustomItem() {

    override val id = "sell_wand"
    override val material = Material.BLAZE_ROD
    override val hasGlint = true

    override val displayName: Component = Component.text("Sell Wand", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Tool",
        description = listOf(
            "Right-click a chest to sell its contents.",
            "Single use.",
        ),
        usage = "Right-click a chest to activate.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "sell_wand"))
    }
}
