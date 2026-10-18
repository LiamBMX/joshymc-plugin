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
 * Rurus (issue #1205): a little caramel-filled chocolate cup, a retextured Cookie that eats
 * exactly like one. Eating it also gives a short hidden effect (RurusListener). The name and
 * lore must never mention or hint at that effect: it's meant to be a surprise.
 */
class Rurus : CustomItem() {

    companion object {
        const val ID = "rurus"
    }

    override val id = ID
    override val material = Material.COOKIE
    override val isCandy = true

    override val displayName: Component = Component.text("Rurus", TextColor.color(0xF1B649))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Food",
        description = listOf("Little chocolate cups filled", "with smooth, golden caramel."),
        usage = "Right-click to eat.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, ID))
    }
}
