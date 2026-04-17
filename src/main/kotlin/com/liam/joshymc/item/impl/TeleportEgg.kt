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

class TeleportEgg : CustomItem() {
    override val id = "teleport_egg"
    override val material = Material.EGG
    override val hasGlint = true
    override val displayName: Component = Component.text("Teleport Egg", TextColor.color(0xAA00FF))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)
    override val lore = LoreBuilder.build(
        type = "Throwable",
        description = listOf("Swaps your position with the target.", "Splash radius effect."),
        usage = "Throw to use.",
    )
    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, "teleport_egg"))
    }
}
