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

class EasterEgg : CustomItem() {

    override val id = "easter_egg"
    override val material = Material.GOLD_NUGGET
    override val hasGlint = true

    override val displayName: Component = Component.text("Easter Egg", TextColor.color(0xFFD700))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Consumable",
        description = listOf(
            "A mysterious golden egg.",
            "Open it to reveal a surprise inside!",
        ),
        usage = "Right-click to open.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "easter_egg"))
    }
}
