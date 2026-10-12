package com.liam.joshymc.gui.claim

import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import kotlin.math.ceil

/**
 * Generic paginated player-skull picker reused by the /claim GUI's trust and
 * untrust flows (issue #612). Picking an entry only invokes [onPick] — callers
 * dispatch the matching claim logic from there, so no claim logic is duplicated here.
 */
object ClaimPlayerPickerGui {

    data class Entry(val player: OfflinePlayer, val lore: List<Component>)

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(
        plugin: Joshymc,
        player: Player,
        title: Component,
        entries: List<Entry>,
        emptyMessage: Component,
        onBack: (Joshymc, Player) -> Unit,
        onPick: (Joshymc, Player, OfflinePlayer) -> Unit,
        page: Int = 0
    ) {
        val gui = CustomGui(title, 54)
        for (slot in 0..8) gui.setItem(slot, GuiStyle.filler())
        for (slot in 45..53) gui.setItem(slot, GuiStyle.filler())

        if (entries.isEmpty()) {
            gui.setItem(22, GuiStyle.item(Material.BARRIER, emptyMessage))
            gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> onBack(plugin, p) }
            gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
            plugin.guiManager.open(player, gui)
            return
        }

        val totalPages = maxOf(1, ceil(entries.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageEntries = entries.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, entry) in pageEntries.withIndex()) {
            gui.setItem(FIRST_CONTENT_SLOT + index, buildHead(entry)) { p, _ -> onPick(plugin, p, entry.player) }
        }

        if (clampedPage > 0) {
            gui.setItem(GuiStyle.prevSlot(54), GuiStyle.previousPage(clampedPage, totalPages)) { p, _ ->
                GuiStyle.pageSound(p)
                open(plugin, p, title, entries, emptyMessage, onBack, onPick, clampedPage - 1)
            }
        }
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> onBack(plugin, p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(GuiStyle.nextSlot(54), GuiStyle.nextPage(clampedPage, totalPages)) { p, _ ->
                GuiStyle.pageSound(p)
                open(plugin, p, title, entries, emptyMessage, onBack, onPick, clampedPage + 1)
            }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun buildHead(entry: Entry): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        stack.editMeta { meta ->
            if (meta is SkullMeta) Joshymc.instance.playerHeadManager.applyHead(meta, entry.player)
            meta.displayName(Component.text(entry.player.name ?: "Unknown", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            meta.lore(entry.lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return stack
    }
}
