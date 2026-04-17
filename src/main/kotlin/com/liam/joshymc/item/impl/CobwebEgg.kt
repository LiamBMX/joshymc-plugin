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

class CobwebEgg : CustomItem() {
    override val id = "cobweb_egg"
    override val material = Material.EGG
    override val hasGlint = true
    override val displayName: Component = Component.text("Cobweb Egg", TextColor.color(0xDDDDDD))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = LoreBuilder.build(
        type = "Throwable",
        description = listOf("Spawns cobwebs on impact.", "Cobwebs despawn after a few seconds."),
        usage = "Throw to use.",
    )
    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "cobweb_egg"))
    }
}
