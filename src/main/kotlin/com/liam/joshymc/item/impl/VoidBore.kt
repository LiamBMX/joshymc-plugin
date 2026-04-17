package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

class VoidBore : CustomItem() {

    override val id = "void_bore"
    override val material = Material.NETHERITE_INGOT
    override val hasGlint = true

    override val displayName: Component = Component.text("Void Bore [1x1]", TextColor.color(0xFF5555))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Deployable",
        description = listOf(
            "Right-click a block to drill straight down.",
            "Stops when it hits bedrock.",
            "Single use per item.",
        ),
        usage = "Right-click to deploy.",
    )

    override fun applyMeta(meta: org.bukkit.inventory.meta.ItemMeta) {
        meta.setItemModel(org.bukkit.NamespacedKey(Joshymc.instance, "void_bore"))
    }
}
