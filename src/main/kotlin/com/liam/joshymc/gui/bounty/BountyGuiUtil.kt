package com.liam.joshymc.gui.bounty

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/** Small shared helpers for building the /bounty list GUI (issue #494). */
object BountyGuiUtil {

    fun item(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
        return stack
    }

    fun filler(material: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack =
        item(material, Component.text(" "))
}
