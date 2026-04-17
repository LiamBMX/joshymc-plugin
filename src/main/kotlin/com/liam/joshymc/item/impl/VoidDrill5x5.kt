package com.liam.joshymc.item.impl

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.CustomItem
import com.liam.joshymc.util.LoreBuilder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material

class VoidDrill5x5 : CustomItem() {

    override val id = "void_drill_5x5"
    override val material = Material.NETHERITE_PICKAXE
    override val hasGlint = true

    override val displayName: Component = Component.text("Void Drill [5x5]", TextColor.color(0xFF55FF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Pickaxe",
        description = listOf(
            "Mines a 5x5 area when you break a block.",
            "Works in the direction you're facing.",
        ),
        usage = "Mine any block to activate.",
    )

    override fun applyMeta(meta: org.bukkit.inventory.meta.ItemMeta) {
        meta.isUnbreakable = true
        meta.setItemModel(org.bukkit.NamespacedKey(Joshymc.instance, "void_drill_5x5"))
    }
}
