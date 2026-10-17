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

/**
 * Tomato (issue #1195): a retextured Snowball that throws exactly like one (vanilla handles the
 * throw, its speed and gravity, and using one up outside Creative). The thrown snowball carries
 * a copy of this stack, so the client draws the tomato model in flight too. TomatoListener adds
 * the splat on impact and keeps hits harmless.
 */
class Tomato : CustomItem() {

    companion object {
        const val ID = "tomato"
    }

    override val id = ID
    override val material = Material.SNOWBALL

    override val displayName: Component = Component.text("Tomato", TextColor.color(0xE5391F))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Throwable",
        description = listOf("A juicy tomato, perfect for throwing!"),
        usage = "Right-click to throw.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, ID))
        meta.setMaxStackSize(16)
    }
}
