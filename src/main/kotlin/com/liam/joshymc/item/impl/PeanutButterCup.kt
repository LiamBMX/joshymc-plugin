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
 * Peanut Butter Cup (issue #1198): a retextured Cookie that eats exactly like one. Eating it
 * also gives a short hidden effect (PeanutButterCupListener). The name and lore must never
 * mention or hint at that effect: it's meant to be a surprise.
 */
class PeanutButterCup : CustomItem() {

    companion object {
        const val ID = "peanut_butter_cup"
    }

    override val id = ID
    override val material = Material.COOKIE
    override val isCandy = true

    override val displayName: Component = Component.text("Peanut Butter Cup", TextColor.color(0xD99234))
        .decoration(TextDecoration.ITALIC, false)
        .decoration(TextDecoration.BOLD, true)

    override val lore: List<Component> = LoreBuilder.build(
        type = "Food",
        description = listOf("A sweet chocolate treat with", "a creamy peanut butter center."),
        usage = "Right-click to eat.",
    )

    override fun applyMeta(meta: ItemMeta) {
        meta.setItemModel(NamespacedKey(Joshymc.instance, ID))
    }
}
