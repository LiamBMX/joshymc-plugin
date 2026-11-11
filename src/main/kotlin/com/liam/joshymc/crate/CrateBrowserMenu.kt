package com.liam.joshymc.crate

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.manager.CrateManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The `/crate` browser (issue #1514): every listed crate (crates.yml `listed: false` hides one),
 * monthly crates (the ones `crates.custom-models.monthly` rotates) first with this month shown,
 * then permanent ones. Each icon is the crate key's look and shows how it opens, its reward
 * count, the player's keys and whether it can be opened right now; clicking previews it with a
 * Back button. The browser never opens a crate or touches keys (crates still open only at a
 * crate block with a key in hand), and every click re-checks the permission and the crate.
 */
class CrateBrowserMenu(private val plugin: Joshymc, private val crates: CrateManager) {

    companion object {
        const val PERMISSION = "joshymc.crate.use"
        private val CRATE_COLOR = TextColor.color(0x55FFFF)
    }

    private fun plain(text: String, color: TextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    fun open(player: Player, page: Int = 0) {
        if (!player.hasPermission(PERMISSION)) {
            Msg.noPermission(player)
            return
        }
        val listed = crates.menuCrates()
        val monthly = listed.filter { crates.models.isMonthlyCrate(it.id) }
        val permanent = listed.filterNot { crates.models.isMonthlyCrate(it.id) }
        val layout = CrateMenuLayout.page(monthly.size, permanent.size, page)
        val month = crates.models.currentMonth()
        val monthName = month.name.lowercase().replaceFirstChar { it.uppercase() }

        val title = Component.text("Crates").decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, layout.size)

        gui.setItem(CrateMenuLayout.INFO_SLOT, infoItem(listed.isEmpty(), monthly.isNotEmpty(), monthName))

        for ((slot, section) in layout.labels) {
            val label = when (section) {
                CrateMenuLayout.Section.MONTHLY -> GuiStyle.item(
                    Material.CLOCK,
                    Component.text("Monthly Crates", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true),
                    listOf(Component.empty(), plain("  This month: $monthName", NamedTextColor.GRAY),
                        plain("  Their look changes every month.", NamedTextColor.DARK_GRAY), Component.empty())
                )
                CrateMenuLayout.Section.PERMANENT -> GuiStyle.item(
                    Material.CHEST,
                    Component.text("Permanent Crates", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                    listOf(Component.empty(), plain("  Available all year.", NamedTextColor.GRAY), Component.empty())
                )
            }
            gui.setItem(slot, label)
        }

        for ((slot, cell) in layout.cells) {
            val crate = (if (cell.section == CrateMenuLayout.Section.MONTHLY) monthly else permanent).getOrNull(cell.index) ?: continue
            val crateId = crate.id
            gui.setItem(slot, crateItem(player, crate, cell.section == CrateMenuLayout.Section.MONTHLY, monthName)) { p, _ ->
                // Never trust the open GUI: permission and crate are checked again on the click.
                if (!p.hasPermission(PERMISSION)) {
                    p.closeInventory()
                    Msg.noPermission(p)
                    return@setItem
                }
                val current = crates.getCrate(crateId)
                if (current == null || !current.listed) {
                    GuiStyle.errorSound(p)
                    open(p, layout.page)
                    return@setItem
                }
                GuiStyle.clickSound(p)
                val backPage = layout.page
                crates.openPreview(p, crateId, 0) { viewer -> open(viewer, backPage) }
            }
        }

        layout.previous?.let { gui.setItem(it, GuiStyle.previousPage(layout.page - 1, layout.pageCount)) { p, _ -> GuiStyle.pageSound(p); open(p, layout.page - 1) } }
        layout.next?.let { gui.setItem(it, GuiStyle.nextPage(layout.page + 1, layout.pageCount)) { p, _ -> GuiStyle.pageSound(p); open(p, layout.page + 1) } }
        gui.setItem(layout.close, GuiStyle.close()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    private fun infoItem(empty: Boolean, hasMonthly: Boolean, monthName: String): ItemStack {
        val lore = mutableListOf<Component>(Component.empty())
        if (empty) {
            lore.add(plain("  No crates are available right now.", NamedTextColor.GRAY))
        } else {
            lore.add(plain("  Click a crate to see its rewards.", NamedTextColor.GRAY))
            lore.add(plain("  Open one by right-clicking its crate", NamedTextColor.GRAY))
            lore.add(plain("  block with the key in your hand.", NamedTextColor.GRAY))
            lore.add(plain("  Sneak + right-click opens all your keys.", NamedTextColor.DARK_GRAY))
            if (hasMonthly) lore.add(plain("  Monthly crates: $monthName", NamedTextColor.LIGHT_PURPLE))
        }
        lore.add(Component.empty())
        return GuiStyle.item(Material.NETHER_STAR, Component.text("Crates", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true), lore)
    }

    private fun crateItem(player: Player, crate: CrateManager.CrateDef, monthly: Boolean, monthName: String): ItemStack {
        val keys = crates.keyCount(player, crate.id)
        val problem = crates.availabilityProblem(player, crate)
        val lore = mutableListOf<Component>(Component.empty())
        if (monthly) lore.add(plain("  Monthly crate · $monthName", NamedTextColor.LIGHT_PURPLE))
        lore.add(plain("  ${crates.modeDescription(crate)}", NamedTextColor.GRAY))
        lore.add(plain("  Rewards: ", NamedTextColor.GRAY).append(Component.text("${crate.rewards.size}", NamedTextColor.WHITE)))
        lore.add(plain("  Key: ", NamedTextColor.GRAY).append(Component.text(crate.keyName, TextColor.color(0xFFAA00))))
        lore.add(plain("  Your keys: ", NamedTextColor.GRAY)
            .append(Component.text("$keys", if (keys > 0) NamedTextColor.GREEN else NamedTextColor.RED)))
        lore.add(Component.empty())
        if (problem != null) {
            lore.add(plain("  ✘ $problem", NamedTextColor.RED))
        } else if (keys > 0) {
            lore.add(plain("  ✔ Ready to open at a crate", NamedTextColor.GREEN))
        } else {
            lore.add(plain("  You need a key to open this crate.", NamedTextColor.YELLOW))
        }
        lore.add(plain("  Click to preview rewards", NamedTextColor.AQUA))
        lore.add(Component.empty())
        val icon = crates.crateIcon(crate)
        icon.editMeta { meta ->
            meta.displayName(Component.text(crate.displayName, CRATE_COLOR).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
            meta.lore(lore)
            if (keys > 0) meta.setEnchantmentGlintOverride(true)
        }
        if (keys > 0) icon.amount = keys.coerceIn(1, icon.maxStackSize.coerceAtLeast(1))
        return icon
    }
}
