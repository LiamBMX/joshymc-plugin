package com.liam.joshymc.gui

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

/**
 * The JoshyMC GUI design language (issue #1120): one place for the items,
 * slots, colors and sounds every normal utility menu shares.
 *
 * Bottom-row navigation for a GUI of `size` slots:
 *   Back = [backSlot] (`size - 9`), Previous Page = [prevSlot] (`size - 7`),
 *   Close = [closeSlot] (`size - 5`), Next Page = [nextSlot] (`size - 3`).
 *
 * Colors: GREEN = enabled / success, RED = disabled / error,
 * YELLOW = navigation and warnings.
 */
object GuiStyle {

    /** Standard border/filler pane for normal utility menus. */
    val FILLER_MATERIAL: Material = Material.GRAY_STAINED_GLASS_PANE

    /** Darker pane kept by the casino family, whose look is intentionally themed. */
    val THEMED_FILLER_MATERIAL: Material = Material.BLACK_STAINED_GLASS_PANE

    // ── Slots ───────────────────────────────────────────

    fun backSlot(size: Int): Int = size - 9
    fun prevSlot(size: Int): Int = size - 7
    fun closeSlot(size: Int): Int = size - 5
    fun nextSlot(size: Int): Int = size - 3

    // ── Items ───────────────────────────────────────────

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

    fun filler(material: Material = FILLER_MATERIAL): ItemStack =
        item(material, Component.text(" "))

    /** Player head with the skin applied through PlayerHeadManager (never `owningPlayer`). */
    fun skull(owner: OfflinePlayer, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        stack.editMeta { meta ->
            if (meta is SkullMeta) Joshymc.instance.playerHeadManager.applyHead(meta, owner)
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
            }
        }
        return stack
    }

    /** Returns to the parent menu. Pass a label such as "Back to Home" when the parent isn't obvious. */
    fun back(label: String = "Back"): ItemStack =
        item(Material.ARROW, Component.text(label, NamedTextColor.YELLOW))

    /** Closes the GUI. Never used as a Back button. */
    fun close(): ItemStack =
        item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))

    fun previousPage(page: Int, totalPages: Int): ItemStack =
        item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW), listOf(pageLine(page, totalPages)))

    fun nextPage(page: Int, totalPages: Int): ItemStack =
        item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW), listOf(pageLine(page, totalPages)))

    /** "Page X/Y" in the shared format; [page] is zero-based. */
    fun pageLine(page: Int, totalPages: Int): Component =
        Component.text("Page ${page + 1}/${totalPages.coerceAtLeast(1)}", NamedTextColor.GRAY)

    /** "Enabled"/"Disabled" status line in the shared green/red. */
    fun stateLine(enabled: Boolean): Component =
        if (enabled) Component.text("Enabled", NamedTextColor.GREEN)
        else Component.text("Disabled", NamedTextColor.RED)

    fun stateColor(enabled: Boolean): NamedTextColor =
        if (enabled) NamedTextColor.GREEN else NamedTextColor.RED

    /** Lime/red dye toggle: name colored by state, status line first, then [lore]. */
    fun toggle(name: String, enabled: Boolean, lore: List<Component> = emptyList()): ItemStack =
        item(
            if (enabled) Material.LIME_DYE else Material.GRAY_DYE,
            Component.text(name, stateColor(enabled)),
            listOf(stateLine(enabled)) + lore
        )

    fun warning(text: String): Component = Component.text(text, NamedTextColor.YELLOW)

    fun error(text: String): Component = Component.text(text, NamedTextColor.RED)

    // ── Sounds ──────────────────────────────────────────

    fun clickSound(player: Player) =
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)

    fun pageSound(player: Player) =
        player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 0.6f, 1.0f)

    fun successSound(player: Player) =
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f)

    fun errorSound(player: Player) =
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f)
}
