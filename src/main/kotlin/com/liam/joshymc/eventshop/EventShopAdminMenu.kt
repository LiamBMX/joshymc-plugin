package com.liam.joshymc.eventshop

import com.liam.joshymc.Joshymc
import com.liam.joshymc.eventshop.EventShopManager.Companion.EVENT_GOLD
import com.liam.joshymc.eventshop.EventShopStore.ItemKind
import com.liam.joshymc.eventshop.EventShopStore.Listing
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.time.Instant

/**
 * `/eventshop admin` (issue #1299): the Event Shop's admin menu. Lists every listing with its
 * full definition (left-click enables/disables, right-click removes after a confirmation), shows
 * the shop's status, next opening and whether the pool can fill seven slots, and the running
 * rotation with its sales. Every action goes through the same audited store calls as the commands.
 */
class EventShopAdminMenu(private val plugin: Joshymc) {

    companion object {
        private const val SIZE = 54
        private const val PAGE_SIZE = 45
        private const val STATUS_SLOT = 45
        private const val ROTATION_SLOT = 53
        private val TITLE: Component = Component.text("Event Shop Admin", EVENT_GOLD).decoration(TextDecoration.BOLD, true)
    }

    private val shop get() = plugin.eventShopManager

    fun open(player: Player, page: Int = 0) {
        val listings = shop.store.listListings()
        val pages = ((listings.size - 1) / PAGE_SIZE + 1).coerceAtLeast(1)
        val p = page.coerceIn(0, pages - 1)
        val title = if (pages > 1) TITLE.append(Component.text(" ${p + 1}/$pages", NamedTextColor.GRAY)) else TITLE
        val gui = CustomGui(title, SIZE)
        val inRotation = shop.active?.items?.map { it.listingId }?.toSet().orEmpty()

        listings.drop(p * PAGE_SIZE).take(PAGE_SIZE).forEachIndexed { idx, l ->
            gui.setItem(idx, listingIcon(l, l.id in inRotation)) { viewer, event ->
                when (event.click) {
                    ClickType.LEFT, ClickType.SHIFT_LEFT -> {
                        if (shop.store.setEnabled(l.id, !l.enabled, viewer.uniqueId, viewer.name)) {
                            plugin.logger.info("[EventShop] ${viewer.name} ${if (l.enabled) "disabled" else "enabled"} listing #${l.id}.")
                            GuiStyle.clickSound(viewer)
                        } else GuiStyle.errorSound(viewer)
                        open(viewer, p)
                    }
                    ClickType.RIGHT, ClickType.SHIFT_RIGHT -> confirmRemove(viewer, l, p)
                    else -> {}
                }
            }
        }
        for (slot in PAGE_SIZE until SIZE) gui.setItem(slot, GuiStyle.filler())
        gui.setItem(STATUS_SLOT, statusIcon())
        if (p > 0) gui.setItem(GuiStyle.prevSlot(SIZE), GuiStyle.previousPage(p, pages)) { v, _ -> GuiStyle.pageSound(v); open(v, p - 1) }
        if (p < pages - 1) gui.setItem(GuiStyle.nextSlot(SIZE), GuiStyle.nextPage(p, pages)) { v, _ -> GuiStyle.pageSound(v); open(v, p + 1) }
        gui.setItem(GuiStyle.closeSlot(SIZE), GuiStyle.close()) { v, _ -> v.closeInventory() }
        gui.setItem(ROTATION_SLOT, GuiStyle.item(Material.CHEST, Component.text("Current Rotation", EVENT_GOLD),
            listOf(Component.text("View the items on sale right now", NamedTextColor.GRAY), Component.text("and how many have sold.", NamedTextColor.GRAY)))) { v, _ ->
            GuiStyle.clickSound(v)
            openRotation(v, p)
        }
        plugin.guiManager.open(player, gui)
    }

    private fun listingIcon(l: Listing, inRotation: Boolean): ItemStack {
        val problem = shop.problemOf(l)
        val stack = shop.template(l) ?: ItemStack(Material.BARRIER)
        stack.amount = l.quantity.coerceIn(1, stack.maxStackSize.coerceAtLeast(1))
        val lore = mutableListOf<Component>()
        lore += line("Listing: ", "#${l.id}", EVENT_GOLD)
        lore += line("Name: ", l.displayName, NamedTextColor.WHITE)
        lore += line("Type: ", if (l.kind == ItemKind.CUSTOM) "Custom item (${l.customItemId})" else "Saved item (${l.material.lowercase()})", NamedTextColor.WHITE)
        lore += line("Price: ", plugin.economyManager.format(l.price) + " Money", NamedTextColor.GOLD)
        lore += line("Quantity: ", "x${l.quantity}", NamedTextColor.WHITE)
        lore += line("Weight: ", l.weight.toString(), NamedTextColor.WHITE)
        lore += line("Per-player limit: ", if (l.perPlayerLimit > 0) l.perPlayerLimit.toString() else "none", NamedTextColor.WHITE)
        lore += line("Stock per rotation: ", if (l.stockLimit > 0) l.stockLimit.toString() else "unlimited", NamedTextColor.WHITE)
        lore += GuiStyle.stateLine(l.enabled)
        if (inRotation) lore += Component.text("In the current rotation", NamedTextColor.AQUA)
        if (problem != null) lore += GuiStyle.error("⚠ Not eligible: $problem")
        lore += Component.empty()
        lore += Component.text("Left-click to ${if (l.enabled) "disable" else "enable"}", NamedTextColor.YELLOW)
        lore += Component.text("Right-click to remove", NamedTextColor.YELLOW)
        stack.editMeta { meta ->
            val original = meta.lore().orEmpty()
            meta.lore((original + (if (original.isEmpty()) emptyList() else listOf(Component.empty())) + lore)
                .map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return stack
    }

    private fun statusIcon(): ItemStack {
        val now = Instant.now()
        val a = shop.active?.takeIf { it.rotation.isActiveAt(now) }
        val eligible = shop.eligiblePool().listings.size
        val lore = mutableListOf<Component>()
        if (a != null) {
            lore += Component.text("OPEN", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true)
            lore += line("Rotation: ", "#${a.rotation.id} (${a.rotation.kind.name.lowercase()})", NamedTextColor.WHITE)
            lore += line("Time Remaining: ", shop.formatDuration(Duration.between(now, a.rotation.end)), NamedTextColor.YELLOW)
        } else {
            lore += Component.text("CLOSED", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
        }
        val next = shop.nextOpening(now)
        lore += line("Next Opening: ", next?.let { shop.formatClock(it.start) } ?: "none scheduled", NamedTextColor.YELLOW)
        lore += line("Eligible Listings: ", "$eligible", if (eligible >= EventShopSelection.SLOTS) NamedTextColor.GREEN else NamedTextColor.RED)
        if (eligible < EventShopSelection.SLOTS) {
            lore += GuiStyle.error("⚠ Underfilled: fewer than ${EventShopSelection.SLOTS} eligible listings.")
            lore += Component.text("Rotations show only what's available.", NamedTextColor.GRAY)
        }
        lore += Component.empty()
        lore += Component.text("Schedule:", NamedTextColor.GRAY)
        if (shop.schedule.valid) {
            lore += Component.text(shop.describeSchedule(), NamedTextColor.WHITE)
        } else {
            shop.schedule.problems.forEach { lore += GuiStyle.error("⚠ $it") }
        }
        return GuiStyle.item(Material.CLOCK, Component.text("Shop Status", EVENT_GOLD), lore)
    }

    private fun openRotation(player: Player, returnPage: Int) {
        val gui = CustomGui(Component.text("Current Rotation", EVENT_GOLD).decoration(TextDecoration.BOLD, true), 27)
        gui.fill(GuiStyle.filler())
        val a = shop.active?.takeIf { it.rotation.isActiveAt(Instant.now()) }
        if (a == null) {
            gui.setItem(13, GuiStyle.item(Material.BARRIER, Component.text("The Event Shop is closed", NamedTextColor.RED)))
        } else {
            for (item in a.items) {
                val slot = EventShopManager.ITEM_SLOTS.getOrNull(item.position) ?: continue
                val stack = shop.template(item) ?: ItemStack(Material.BARRIER)
                stack.amount = item.quantity.coerceIn(1, stack.maxStackSize.coerceAtLeast(1))
                val sold = shop.store.soldCount(a.rotation.windowId, item.listingId)
                stack.editMeta { meta ->
                    meta.lore(listOf(
                        line("Listing: ", "#${item.listingId}", EVENT_GOLD),
                        line("Price: ", plugin.economyManager.format(item.price), NamedTextColor.GOLD),
                        line("Quantity: ", "x${item.quantity}", NamedTextColor.WHITE),
                        line("Sold this window: ", if (item.stockLimit > 0) "$sold/${item.stockLimit}" else "$sold", NamedTextColor.WHITE),
                    ))
                }
                gui.setItem(slot, stack)
            }
        }
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { v, _ -> GuiStyle.clickSound(v); open(v, returnPage) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { v, _ -> v.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun confirmRemove(player: Player, l: Listing, returnPage: Int) {
        val gui = CustomGui(Component.text("Remove Listing #${l.id}?", NamedTextColor.RED).decoration(TextDecoration.BOLD, true), 27)
        gui.fill(GuiStyle.filler())
        gui.setItem(13, listingIcon(l, false).also { s -> s.editMeta { m -> m.lore(m.lore()?.dropLast(3)) } })
        gui.setItem(11, GuiStyle.item(Material.RED_STAINED_GLASS_PANE, Component.text("Cancel", NamedTextColor.RED))) { v, _ ->
            GuiStyle.clickSound(v)
            open(v, returnPage)
        }
        gui.setItem(15, GuiStyle.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Remove from the pool", NamedTextColor.GREEN),
            listOf(Component.text("Future rotations won't offer it.", NamedTextColor.GRAY), Component.text("A running rotation keeps it.", NamedTextColor.GRAY)))) { v, _ ->
            if (shop.store.removeListing(l.id, v.uniqueId, v.name)) {
                plugin.logger.info("[EventShop] ${v.name} removed listing #${l.id} (${l.displayName}).")
                plugin.commsManager.send(v, Component.text("Removed listing #${l.id} from future Event Shop rotations.", NamedTextColor.GREEN))
                GuiStyle.successSound(v)
            } else GuiStyle.errorSound(v)
            open(v, returnPage)
        }
        plugin.guiManager.open(player, gui)
    }

    private fun line(label: String, value: String, color: TextColor): Component =
        Component.text(label, NamedTextColor.GRAY).append(Component.text(value, color)).decoration(TextDecoration.ITALIC, false)
}
