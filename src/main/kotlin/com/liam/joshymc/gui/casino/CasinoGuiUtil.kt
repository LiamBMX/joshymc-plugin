package com.liam.joshymc.gui.casino

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/** Shared helpers for the `/casino` GUIs (issue #727). */
object CasinoGuiUtil {

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

    fun filler(material: Material = Material.BLACK_STAINED_GLASS_PANE): ItemStack =
        item(material, Component.text(" "))

    fun closeButton(): ItemStack =
        item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))

    fun backButton(): ItemStack =
        item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))
}
