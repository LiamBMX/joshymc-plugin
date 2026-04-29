package com.liam.joshymc.item.impl

import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

class AfkKey : CustomItem() {

    override val id = "afk_key"
    override val material = Material.TRIPWIRE_HOOK
    override val hasGlint = true

    override val displayName: Component = Component.text("AFK Key", TextColor.color(0x55FFFF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Crate Key",
        description = listOf(
            "Earned by staying AFK in the AFK world.",
            "Use it on an AFK crate to claim a reward.",
        ),
        usage = "Right-click an AFK crate.",
    )
}
