package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.WorldFlagManager
import com.liam.joshymc.manager.WorldFlagManager.WorldFlag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Shared region flag editor GUI for `/worldflag subflag edit` — see [WorldFlagCommand].
 */
class WorldFlagsCommand(private val plugin: Joshymc) {

    private val manager get() = plugin.worldFlagManager

    // ──────────────────────────────────────────────
    //  Region editor GUI
    // ──────────────────────────────────────────────

    private val stateSlots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)

    /** Also called from [WorldFlagCommand]'s `subflag edit` handler. */
    internal fun openEditorGui(player: Player, regionName: String) {
        val region = manager.getRegion(regionName)
        if (region == null) {
            player.sendMessage(Component.text("Region '$regionName' no longer exists.", NamedTextColor.RED))
            return
        }

        val title = "Region: ${region.name}".let { if (it.length > 28) it.take(28) else it }
        val gui = CustomGui(Component.text(title, NamedTextColor.GOLD), 54)

        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { displayName(Component.text(" ")) }
        gui.fill(filler)

        gui.setItem(4, infoItem(region))

        manager.editorFlags.forEachIndexed { i, flag ->
            val slot = stateSlots.getOrNull(i) ?: return@forEachIndexed
            gui.setItem(slot, flagItem(region, flag)) { p, _ ->
                val next = when (region.flags[flag]) {
                    null -> true
                    true -> false
                    false -> null
                }
                manager.setRegionFlag(region.name, flag, next)
                openEditorGui(p, region.name)
            }
        }

        gui.setItem(48, priorityItem(region, -1)) { p, e ->
            val delta = if (e.isShiftClick) -10 else -1
            manager.setPriority(region.name, region.priority + delta)
            openEditorGui(p, region.name)
        }
        gui.setItem(50, priorityItem(region, 1)) { p, e ->
            val delta = if (e.isShiftClick) 10 else 1
            manager.setPriority(region.name, region.priority + delta)
            openEditorGui(p, region.name)
        }
        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }

        if (region.parent == null) {
            gui.setItem(40, subflagsButtonItem(region)) { p, _ -> openSubflagListGui(p, region.name) }
        } else {
            gui.setItem(40, backToParentItem(region.parent)) { p, _ ->
                manager.getRegion(region.parent)?.let { openSubflagListGui(p, it.name) }
                    ?: p.closeInventory()
            }
        }

        plugin.guiManager.open(player, gui)
    }

    // ──────────────────────────────────────────────
    //  Subflag list GUI
    // ──────────────────────────────────────────────

    private fun openSubflagListGui(player: Player, parentName: String) {
        val parent = manager.getRegion(parentName)
        if (parent == null) {
            player.sendMessage(Component.text("Region '$parentName' no longer exists.", NamedTextColor.RED))
            return
        }

        val title = "Subflags: ${parent.name}".let { if (it.length > 28) it.take(28) else it }
        val gui = CustomGui(Component.text(title, NamedTextColor.GOLD), 54)

        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { displayName(Component.text(" ")) }
        gui.fill(filler)

        gui.setItem(4, infoItem(parent))

        val subflags = manager.subflagsOf(parent.name)
        val listSlots = (9..44).toList()
        subflags.take(listSlots.size).forEachIndexed { i, subflag ->
            gui.setItem(listSlots[i], subflagListItem(subflag)) { p, e ->
                if (e.isShiftClick) {
                    manager.deleteRegion(subflag.name, cascade = true)
                    p.sendMessage(Component.text("Subflag '${subflag.name}' deleted.", NamedTextColor.GREEN))
                    openSubflagListGui(p, parent.name)
                } else {
                    openEditorGui(p, subflag.name)
                }
            }
        }

        gui.setItem(45, createHintItem(parent.name))
        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }
        gui.setItem(53, backItem()) { p, _ -> openEditorGui(p, parent.name) }

        plugin.guiManager.open(player, gui)
    }

    private fun subflagsButtonItem(region: WorldFlagManager.WorldFlagRegion): ItemStack {
        val subflags = manager.subflagsOf(region.name)
        val item = ItemStack(Material.MAP)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Subflags", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text("${subflags.size} Subflag(s) inside this region.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to view/manage.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun backToParentItem(parentName: String): ItemStack {
        val item = ItemStack(Material.MAP)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Back to $parentName", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text("This region is a Subflag of '$parentName'.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun subflagListItem(subflag: WorldFlagManager.WorldFlagRegion): ItemStack {
        val item = ItemStack(Material.ITEM_FRAME)
        val meta = item.itemMeta!!
        meta.displayName(Component.text(subflag.name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text("Priority: ${subflag.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(
                "Bounds: (${subflag.minX},${subflag.minY},${subflag.minZ}) to (${subflag.maxX},${subflag.maxY},${subflag.maxZ})",
                NamedTextColor.GRAY
            ).decoration(TextDecoration.ITALIC, false),
            Component.text("Click: edit flags.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Shift-click: delete this Subflag.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun createHintItem(parentName: String): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Create a Subflag", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text("Select an area with the wand, then run:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("/worldflag subflag create $parentName <name>", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun backItem(): ItemStack {
        val item = ItemStack(Material.ARROW)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Back", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
        item.itemMeta = meta
        return item
    }

    private fun infoItem(region: WorldFlagManager.WorldFlagRegion): ItemStack {
        val item = ItemStack(Material.BOOK)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text(region.name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(buildList {
            add(Component.text("World: ${region.world}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            if (region.parent != null) {
                add(Component.text("Parent: ${region.parent}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            }
            add(Component.text("Priority: ${region.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            add(Component.text(
                "Bounds: (${region.minX},${region.minY},${region.minZ}) to (${region.maxX},${region.maxY},${region.maxZ})",
                NamedTextColor.GRAY
            ).decoration(TextDecoration.ITALIC, false))
        })
        item.itemMeta = meta
        return item
    }

    private fun flagItem(region: WorldFlagManager.WorldFlagRegion, flag: WorldFlag): ItemStack {
        val value = region.flags[flag]
        val material = when (value) {
            true -> Material.LIME_WOOL
            false -> Material.RED_WOOL
            null -> Material.LIGHT_GRAY_WOOL
        }
        val color = when (value) {
            true -> NamedTextColor.GREEN
            false -> NamedTextColor.RED
            null -> NamedTextColor.GRAY
        }
        val stateText = when (value) {
            true -> "ALLOW"
            false -> "DENY"
            null -> "UNSET (inherit)"
        }
        val item = ItemStack(material)
        val meta = item.itemMeta!!
        meta.displayName(Component.text(flag.displayName, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text(flag.description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("State: $stateText", color).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to cycle.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun priorityItem(region: WorldFlagManager.WorldFlagRegion, direction: Int): ItemStack {
        val item = ItemStack(if (direction > 0) Material.LIME_DYE else Material.RED_DYE)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text(if (direction > 0) "Increase Priority" else "Decrease Priority", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(listOf(
            Component.text("Current: ${region.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(
                "Click: ${if (direction > 0) "+1" else "-1"}, Shift-click: ${if (direction > 0) "+10" else "-10"}",
                NamedTextColor.DARK_GRAY
            ).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun closeItem(): ItemStack {
        val item = ItemStack(Material.BARRIER)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
        item.itemMeta = meta
        return item
    }
}
