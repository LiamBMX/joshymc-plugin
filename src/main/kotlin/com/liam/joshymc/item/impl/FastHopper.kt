package com.liam.joshymc.item.impl

import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

class FastHopper : CustomItem() {

    override val id = "fast_hopper"
    override val material = Material.HOPPER
    override val hasGlint = true

    override val displayName: Component = Component.text("Fast Hopper", TextColor.color(0xFFAA00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Utility",
        description = listOf("Transfers 5 items per second."),
        usage = "Place like a normal hopper.",
    )
}
