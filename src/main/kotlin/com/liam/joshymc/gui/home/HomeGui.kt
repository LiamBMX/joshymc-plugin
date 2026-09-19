package com.liam.joshymc.gui.home

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.math.ceil

/** "/home" with no args (issue #821) — a selector GUI over the player's existing saved homes. */
object HomeGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        val uuid = player.uniqueId.toString()
        val homeNames = plugin.warpManager.getHomes(uuid)

        val gui = CustomGui(Component.text("Your Homes", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, filler())
        for (slot in 45..53) gui.setItem(slot, filler())

        if (homeNames.isEmpty()) {
            gui.setItem(
                22,
                item(
                    Material.BARRIER,
                    Component.text("You do not have any homes set.", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("Use /sethome <name> to create one.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(49, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }
            plugin.guiManager.open(player, gui)
            return
        }

        val totalPages = maxOf(1, ceil(homeNames.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageHomes = homeNames.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, name) in pageHomes.withIndex()) {
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(plugin, uuid, name)) { p, _ ->
                p.closeInventory()
                Bukkit.dispatchCommand(p, "home $name")
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage - 1)
            }
        }

        gui.setItem(47, item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))
        gui.setItem(49, item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage + 1)
            }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun buildEntryIcon(plugin: Joshymc, uuid: String, name: String): ItemStack {
        val location = plugin.warpManager.getHome(uuid, name)
        val lore = buildList {
            add(Component.empty())
            if (location != null) {
                add(Component.text("World: ", NamedTextColor.GRAY).append(Component.text(location.world.name, NamedTextColor.WHITE)))
                add(
                    Component.text("Location: ", NamedTextColor.GRAY).append(
                        Component.text(
                            "${location.blockX}, ${location.blockY}, ${location.blockZ}",
                            NamedTextColor.WHITE
                        )
                    )
                )
            }
            add(Component.empty())
            add(Component.text("Click to teleport.", NamedTextColor.YELLOW))
        }
        return item(Material.RED_BED, Component.text(name, NamedTextColor.GREEN), lore)
    }

    private fun item(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
        return stack
    }

    private fun filler(material: Material = Material.GRAY_STAINED_GLASS_PANE): ItemStack =
        item(material, Component.text(" "))
}
