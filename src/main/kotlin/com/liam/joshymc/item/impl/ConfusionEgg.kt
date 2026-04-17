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

class ConfusionEgg : CustomItem() {
    override val id = "confusion_egg"
    override val material = Material.EGG
    override val hasGlint = true
    override val displayName: Component = Component.text("Confusion Egg", TextColor.color(0x88FF00))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = LoreBuilder.build(
        type = "Throwable",
        description = listOf("Inverts the target's controls.", "Applies Nausea effect."),
        usage = "Throw to use.",
    )
    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "confusion_egg"))
    }
}
