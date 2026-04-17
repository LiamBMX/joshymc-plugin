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

class KnockbackEgg : CustomItem() {
    override val id = "knockback_egg"
    override val material = Material.EGG
    override val hasGlint = true
    override val displayName: Component = Component.text("Knockback Egg", TextColor.color(0xFF8800))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = LoreBuilder.build(
        type = "Throwable",
        description = listOf("Sends targets flying on impact.", "Massive knockback in splash radius."),
        usage = "Throw to use.",
    )
    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "knockback_egg"))
    }
}
