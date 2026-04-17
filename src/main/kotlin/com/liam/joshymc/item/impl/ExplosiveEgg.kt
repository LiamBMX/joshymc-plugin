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

class ExplosiveEgg : CustomItem() {

    override val id = "explosive_egg"
    override val material = Material.EGG
    override val hasGlint = true

    override val displayName: Component = Component.text("Explosive Egg", TextColor.color(0xFF5555))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore = LoreBuilder.build(
        type = "Throwable",
        description = listOf(
            "Throw it at a player to deal damage.",
            "Creates an explosion on impact.",
            "Does not destroy terrain.",
        ),
        usage = "Throw to use.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "explosive_egg"))
    }
}
