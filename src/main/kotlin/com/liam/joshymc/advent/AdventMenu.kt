package com.liam.joshymc.advent

import com.liam.joshymc.Joshymc
import com.liam.joshymc.advent.AdventRules.DayState
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The Advent Calendar menus (issue #1424): a 54-slot calendar with the 25 presents in a 5x5 grid
 * ([AdventLayout]) and a 27-slot confirmation for recovering a missed day with Credits. Every
 * click goes back through [AdventCalendarManager.claim], which re-checks everything; the menu only
 * shows what the manager reported. GuiManager cancels every click on these inventories (and
 * shift/double/number-key/drag moves into them), so no icon can be taken.
 */
class AdventMenu(private val plugin: Joshymc) {

    private val manager get() = plugin.adventCalendarManager

    fun open(player: Player) {
        manager.unavailableMessage(player)?.let {
            plugin.commsManager.send(player, it, CommunicationsManager.Category.EVENT)
            return
        }
        val view = manager.view(player) ?: return
        val s = manager.settings ?: return
        val gui = CustomGui(manager.legacy(s.texts.title).decoration(TextDecoration.ITALIC, false), AdventLayout.SIZE)

        for (slot in AdventLayout.SAPLING_SLOTS) {
            gui.setItem(slot, GuiStyle.item(Material.SPRUCE_SAPLING, Component.text("Merry Christmas!", GREEN)))
        }
        AdventLayout.BORDER_SLOTS.forEachIndexed { i, slot ->
            gui.setItem(slot, GuiStyle.filler(if (i % 2 == 0) Material.RED_STAINED_GLASS_PANE else Material.LIME_STAINED_GLASS_PANE))
        }
        gui.setItem(AdventLayout.INFO_SLOT, infoItem(player, view))
        gui.setItem(AdventLayout.CLOSE_SLOT, GuiStyle.close()) { p, _ -> p.closeInventory() }

        for (day in view.days) {
            gui.setItem(AdventLayout.slotOf(day.day), present(view, day, s)) { p, _ -> onPresentClick(p, view, day) }
        }
        plugin.guiManager.open(player, gui)
    }

    private fun onPresentClick(player: Player, view: AdventCalendarManager.CalendarView, day: AdventCalendarManager.DayView) {
        when (day.state) {
            DayState.AVAILABLE, DayState.MISSED_FREE -> claim(player, view.year, day.day, 0.0)
            DayState.MISSED -> openConfirm(player, view, day)
            DayState.CLAIMED -> refuse(player, "You already opened this present.")
            DayState.MISSED_CLOSED -> refuse(player, "Missed presents can't be recovered right now.")
            DayState.LOCKED -> {
                val at = manager.settings?.schedule?.unlockAt(view.year, day.day)
                refuse(player, "This present opens on ${manager.dateText(at)}.")
            }
        }
    }

    private fun claim(player: Player, year: Int, day: Int, price: Double) {
        when (val outcome = manager.claim(player, year, day, price)) {
            is AdventCalendarManager.ClaimOutcome.Done -> GuiStyle.successSound(player)
            is AdventCalendarManager.ClaimOutcome.Refused -> refuse(player, outcome.message)
        }
        if (player.isOnline) open(player)
    }

    private fun refuse(player: Player, message: String) {
        GuiStyle.errorSound(player)
        plugin.commsManager.send(player, Component.text(message, NamedTextColor.RED), CommunicationsManager.Category.EVENT)
    }

    // ── Missed-day confirmation ──────────────────────────────

    private fun openConfirm(player: Player, view: AdventCalendarManager.CalendarView, day: AdventCalendarManager.DayView) {
        val price = day.price ?: return
        val s = manager.settings ?: return
        val date = s.schedule.dateOf(view.year, day.day)
        val gui = CustomGui(Component.text("Unlock ${monthName(date)} ${date.dayOfMonth}?", NamedTextColor.DARK_RED), 27)
        val balance = plugin.creditsManager.getBalance(player.uniqueId)
        val canPay = balance >= price
        gui.setItem(11, GuiStyle.item(
            Material.LIME_CONCRETE,
            Component.text("Confirm Purchase", NamedTextColor.GREEN, TextDecoration.BOLD),
            listOf(
                line("Cost: ", manager.credits(price), NamedTextColor.AQUA),
                line("Your Credits: ", manager.credits(balance), if (canPay) NamedTextColor.AQUA else NamedTextColor.RED),
                Component.empty(),
                Component.text(if (canPay) "Click to unlock this present." else "You don't have enough Credits.", if (canPay) NamedTextColor.YELLOW else NamedTextColor.RED),
            ),
        )) { p, _ -> claim(p, view.year, day.day, price) }
        gui.setItem(13, present(view, day, s, preview = true))
        gui.setItem(15, GuiStyle.item(Material.RED_CONCRETE, Component.text("Cancel", NamedTextColor.RED, TextDecoration.BOLD),
            listOf(Component.text("Back to the calendar.", NamedTextColor.GRAY)))) { p, _ ->
            GuiStyle.clickSound(p)
            open(p)
        }
        plugin.guiManager.open(player, gui)
    }

    // ── Icons ────────────────────────────────────────────────

    private fun present(view: AdventCalendarManager.CalendarView, day: AdventCalendarManager.DayView, s: AdventSettings, preview: Boolean = false): ItemStack {
        val christmas = day.day == AdventRules.DAYS
        val date = s.schedule.dateOf(view.year, day.day)
        val material = when {
            day.state == DayState.CLAIMED -> Material.LIME_STAINED_GLASS
            christmas -> Material.NETHER_STAR
            day.state == DayState.LOCKED -> Material.GRAY_SHULKER_BOX
            day.state == DayState.MISSED -> Material.YELLOW_SHULKER_BOX
            day.state == DayState.MISSED_CLOSED -> Material.BLACK_SHULKER_BOX
            day.day % 2 == 1 -> Material.RED_SHULKER_BOX
            else -> Material.GREEN_SHULKER_BOX
        }
        val dateName = "${monthName(date)} ${date.dayOfMonth}"
        val name = if (christmas) manager.legacy(s.texts.christmasDayName)
        else Component.text(dateName, if (day.day % 2 == 1) RED else GREEN, TextDecoration.BOLD)

        val lore = mutableListOf<Component>()
        if (christmas) lore += Component.text(dateName, GOLD)
        lore += statusLine(day.state)
        lore += Component.empty()
        lore += Component.text(if (christmas) "Bundle contents:" else "Reward:", NamedTextColor.GRAY)
        day.rewards.forEach { lore += Component.text("  • ", GOLD).append(Component.text(it, NamedTextColor.WHITE)) }
        if (day.waiting && day.state != DayState.CLAIMED) {
            lore += Component.text("  Part of this present isn't ready on the server yet;", NamedTextColor.DARK_GRAY)
            lore += Component.text("  it's saved and arrives automatically once it is.", NamedTextColor.DARK_GRAY)
        }
        if (!preview) {
            lore += Component.empty()
            lore += when (day.state) {
                DayState.AVAILABLE -> Component.text("Click to open your present!", NamedTextColor.YELLOW)
                DayState.MISSED_FREE -> Component.text("Click to recover it for free.", NamedTextColor.YELLOW)
                DayState.MISSED -> Component.text("Click to recover it for ", NamedTextColor.YELLOW)
                    .append(Component.text(manager.credits(day.price ?: view.catchUpCost), NamedTextColor.AQUA)).append(Component.text(".", NamedTextColor.YELLOW))
                DayState.MISSED_CLOSED -> Component.text("Missed presents can't be recovered right now.", NamedTextColor.GRAY)
                DayState.LOCKED -> Component.text("Opens ${manager.dateText(s.schedule.unlockAt(view.year, day.day))}", NamedTextColor.GRAY)
                DayState.CLAIMED -> Component.text("Already opened.", NamedTextColor.GRAY)
            }
        }
        val stack = GuiStyle.item(material, name, lore)
        stack.editMeta { meta ->
            meta.setMaxStackSize(64)
            if (day.state == DayState.AVAILABLE || day.state == DayState.MISSED_FREE || (christmas && day.state != DayState.CLAIMED)) {
                meta.setEnchantmentGlintOverride(true)
            }
        }
        stack.amount = day.day
        return stack
    }

    private fun statusLine(state: DayState): Component = when (state) {
        DayState.AVAILABLE -> Component.text("✔ Available today", NamedTextColor.GREEN)
        DayState.CLAIMED -> Component.text("✔ Claimed", NamedTextColor.DARK_GREEN)
        DayState.MISSED -> Component.text("✖ Missed (can be recovered)", GOLD)
        DayState.MISSED_FREE -> Component.text("✖ Missed (free recovery)", GOLD)
        DayState.MISSED_CLOSED -> Component.text("✖ Missed", NamedTextColor.DARK_GRAY)
        DayState.LOCKED -> Component.text("⌚ Locked", NamedTextColor.GRAY)
    }

    private fun infoItem(player: Player, view: AdventCalendarManager.CalendarView): ItemStack {
        val lore = mutableListOf<Component>(
            Component.text("${view.gamemode.displayName} • ${view.year}", GOLD),
            Component.empty(),
            Component.text("A new present unlocks every day at", NamedTextColor.GRAY),
            Component.text("midnight (${manager.settings?.schedule?.zone?.id ?: "server time"}).", NamedTextColor.GRAY),
            Component.text("Today's present is free.", NamedTextColor.GRAY),
        )
        if (view.catchUpEnabled) {
            lore += Component.text("Missed presents cost ", NamedTextColor.GRAY).append(Component.text(manager.credits(view.catchUpCost), NamedTextColor.AQUA))
                .append(Component.text(" each.", NamedTextColor.GRAY))
        }
        lore += Component.text("December 25 is the Christmas Grand Bundle!", GOLD)
        lore += Component.empty()
        val claimed = view.days.count { it.state == DayState.CLAIMED }
        lore += line("Opened: ", "$claimed/${AdventRules.DAYS}", NamedTextColor.WHITE)
        lore += line("Your Credits: ", manager.credits(plugin.creditsManager.getBalance(player.uniqueId)), NamedTextColor.AQUA)
        view.nextUnlockAt?.let { lore += line("Next present: ", manager.dateText(it), NamedTextColor.WHITE) }
        if (view.manual) lore += Component.text("Opened manually by staff.", NamedTextColor.DARK_GRAY)
        lore += Component.empty()
        lore += Component.text("Item rewards wait for free inventory space", NamedTextColor.DARK_GRAY)
        lore += Component.text("and are never dropped on the ground.", NamedTextColor.DARK_GRAY)
        return GuiStyle.item(Material.KNOWLEDGE_BOOK, Component.text("Advent Calendar", GOLD, TextDecoration.BOLD), lore)
    }

    private fun line(label: String, value: String, color: TextColor): Component =
        Component.text(label, NamedTextColor.GRAY).append(Component.text(value, color))

    private fun monthName(date: java.time.LocalDate) =
        date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)

    companion object {
        private val RED = TextColor.color(0xE23B3B)
        private val GREEN = TextColor.color(0x3FBF5A)
        private val GOLD = TextColor.color(0xFFC94A)
    }
}

/** Slots of the calendar menu (Bukkit-free, checked by scripts/AdventCalendarCheck.kt). */
object AdventLayout {
    const val SIZE = 54
    const val INFO_SLOT = 4
    const val CLOSE_SLOT = 53
    val SAPLING_SLOTS = listOf(0, 8)
    /** Red/green panes down both sides of the grid. */
    val BORDER_SLOTS = listOf(9, 17, 18, 26, 27, 35, 36, 44, 45)

    /** Days 1..25 fill a 5x5 grid in rows 1-5, columns 2-6 (Christmas Day bottom right, slot 51). */
    fun slotOf(day: Int): Int {
        require(day in 1..AdventRules.DAYS) { "day $day" }
        val row = (day - 1) / 5 + 1
        val col = (day - 1) % 5 + 2
        return row * 9 + col
    }
}
