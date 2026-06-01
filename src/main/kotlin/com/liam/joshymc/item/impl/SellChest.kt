package com.liam.joshymc.item.impl

import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

class SellChest : CustomItem() {

    override val id = "sell_chest"
    override val material = Material.CHEST
    override val hasGlint = true

    override val displayName: Component = Component.text("Sell Chest", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Tool",
        description = listOf(
            "Place this chest and add items to it.",
            "Contents auto-sell every 60 seconds.",
            "Earnings go to whoever placed the chest.",
        ),
        usage = "Place to activate.",
    )
}
