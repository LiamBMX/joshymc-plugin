package com.liam.joshymc.milestone

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.text.DecimalFormat
import java.util.UUID

/**
 * The `/milestones` menus (issue #1420): main menu (five categories, Pending Rewards, info),
 * category pages (Level + objectives, Claim All), milestone details and Pending Rewards. Built on
 * the shared CustomGui/GuiStyle; no glass filler. Every click re-checks on the server through the
 * manager (claims re-validate the book and the database), so a stale screen can't pay twice.
 */
class MilestoneMenus(private val plugin: Joshymc, private val manager: MilestoneManager) {

    private val number = DecimalFormat("#,##0")
    private val open = HashMap<UUID, CustomGui>()

    /** Closes every open Milestones menu (plugin stop/reload). */
    fun closeAll() {
        for ((uuid, gui) in open.toList()) {
            val player = plugin.server.getPlayer(uuid) ?: continue
            if (plugin.guiManager.getOpenGui(player) === gui) player.closeInventory()
        }
        open.clear()
    }

    private fun show(player: Player, gui: CustomGui) {
        gui.onClose = { p -> if (open[p.uniqueId] === gui) open.remove(p.uniqueId) }
        open[player.uniqueId] = gui
        plugin.guiManager.open(player, gui)
    }

    private fun legacy(text: String): Component = plugin.commsManager.parseLegacy(text).decoration(TextDecoration.ITALIC, false)

    private fun material(name: String, fallback: Material = Material.PAPER): Material =
        Material.matchMaterial(name)?.takeIf { it.isItem && !it.isAir } ?: fallback

    private fun gray(text: String) = Component.text(text, NamedTextColor.GRAY)

    private fun item(material: Material, name: Component, lore: List<Component>, glow: Boolean = false): ItemStack =
        GuiStyle.item(material, name, lore).also { stack ->
            stack.editMeta { meta ->
                meta.addItemFlags(*ItemFlag.entries.toTypedArray())
                if (glow) meta.setEnchantmentGlintOverride(true)
            }
        }

    private fun bar(progress: Long, required: Long): Component {
        val width = 20
        val filled = MilestoneMath.barCells(progress, required, width)
        val color = if (progress >= required) NamedTextColor.GREEN else NamedTextColor.YELLOW
        return Component.text("|".repeat(filled), color)
            .append(Component.text("|".repeat(width - filled), NamedTextColor.DARK_GRAY))
            .append(Component.text(" ${MilestoneMath.percent(progress, required)}%", NamedTextColor.WHITE))
    }

    private fun progressLine(progress: Long, required: Long, unit: String): Component =
        Component.text("${number.format(MilestoneMath.units(progress))} / ${number.format(MilestoneMath.units(required))}", NamedTextColor.WHITE)
            .append(gray(if (unit.isBlank()) "" else " $unit"))

    private fun refused(player: Player, reason: String) {
        GuiStyle.errorSound(player)
        plugin.commsManager.send(player, Component.text(reason, NamedTextColor.RED))
    }

    private fun settingsOrRefuse(player: Player): MilestoneSettings? {
        val s = manager.settings
        if (s == null || !manager.running) {
            refused(player, "Milestones aren't available right now.")
            return null
        }
        return s
    }

    // ── Main ────────────────────────────────────────────────

    fun openMain(player: Player) {
        val s = settingsOrRefuse(player) ?: return
        val book = manager.book(player) ?: return refused(player, "Your milestones are still loading. Try again in a moment.")
        val gui = CustomGui(legacy(s.mainTitle), MilestoneLayout.MAIN_SIZE)

        val summary = ArrayList<Component>()
        for (def in s.catalog.categories) {
            summary += gray("${def.name}: ").append(Component.text("Level ${book.tier(def.category.levelKey)}", NamedTextColor.GOLD))
        }
        gui.setItem(MilestoneLayout.MAIN_SUMMARY, GuiStyle.skull(player, Component.text("Your Milestones", NamedTextColor.GOLD), summary))

        for ((i, category) in MilestoneCategory.entries.withIndex()) {
            val def = s.catalog.category(category) ?: continue
            val key = category.levelKey
            val ready = book.claimable(category).size
            val lore = arrayListOf(
                gray("Level ").append(Component.text(book.tier(key), NamedTextColor.GOLD)),
                bar(book.progress(key), book.required(key)),
                Component.empty(),
                gray("${def.objectives.size} milestones"),
            )
            if (ready > 0) lore += Component.text("$ready ready to claim!", NamedTextColor.GREEN)
            lore += Component.empty()
            lore += Component.text("Click to open", NamedTextColor.YELLOW)
            gui.setItem(
                MilestoneLayout.MAIN_CATEGORIES[i],
                item(material(def.icon), Component.text(def.name, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), lore, glow = ready > 0)
            ) { p, _ -> GuiStyle.clickSound(p); openCategory(p, category, 0) }
        }

        val pending = runCatching { manager.store.pendingCount(player.uniqueId) }.getOrDefault(0)
        gui.setItem(
            MilestoneLayout.MAIN_PENDING,
            item(
                if (pending > 0) Material.CHEST_MINECART else Material.MINECART,
                Component.text("Pending Rewards", NamedTextColor.YELLOW),
                listOf(
                    gray(if (pending > 0) "$pending stored reward${if (pending == 1) "" else "s"}" else "Nothing waiting"),
                    gray("Items that didn't fit your inventory."),
                    Component.empty(),
                    Component.text("Click to open", NamedTextColor.YELLOW),
                ),
                glow = pending > 0
            )
        ) { p, _ -> GuiStyle.clickSound(p); openPending(p, 0) }
        gui.setItem(MilestoneLayout.MAIN_CLOSE, GuiStyle.close()) { p, _ -> p.closeInventory() }
        gui.setItem(
            MilestoneLayout.MAIN_INFO,
            item(Material.BOOK, Component.text("How Milestones Work", NamedTextColor.AQUA), s.infoLines.map { legacy(it) })
        )
        show(player, gui)
    }

    // ── Category ────────────────────────────────────────────

    fun openCategory(player: Player, category: MilestoneCategory, page: Int) {
        val s = settingsOrRefuse(player) ?: return
        val book = manager.book(player) ?: return refused(player, "Your milestones are still loading. Try again in a moment.")
        val def = s.catalog.category(category) ?: return refused(player, "That category is disabled.")
        val objectives = book.sortedObjectives(category)
        val layout = MilestoneLayout.categoryPage(objectives.size, page)
        val title = s.categoryTitle.replace("{category}", def.name)
        val gui = CustomGui(legacy(title), layout.size)

        // Overall level
        val levelKey = category.levelKey
        val levelTier = book.tier(levelKey)
        val levelReady = book.isClaimable(levelKey)
        val levelLore = arrayListOf(
            gray("Every ${def.name.lowercase()} milestone activity counts here."),
            Component.empty(),
            progressLine(book.progress(levelKey), book.required(levelKey), unitOf(def)),
            bar(book.progress(levelKey), book.required(levelKey)),
            Component.empty(),
        )
        levelLore += rewardLines(levelKey, levelTier)
        levelLore += Component.empty()
        levelLore += if (levelReady) Component.text("Click to claim Level $levelTier!", NamedTextColor.GREEN)
        else gray("Paused when complete until claimed.")
        gui.setItem(
            MilestoneLayout.CATEGORY_LEVEL,
            item(
                if (levelReady) Material.EXPERIENCE_BOTTLE else material(def.icon),
                Component.text("${def.name} Level $levelTier", if (levelReady) NamedTextColor.GREEN else NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                levelLore,
                glow = levelReady
            )
        ) { p, _ ->
            if (!levelReady) return@setItem GuiStyle.clickSound(p)
            claimAndRefresh(p, levelKey) { openCategory(p, category, layout.page) }
        }

        for ((i, slot) in layout.slots.withIndex()) {
            val objective = objectives[layout.from + i]
            gui.setItem(slot, objectiveIcon(book, objective)) { p, _ ->
                GuiStyle.clickSound(p)
                openDetails(p, objective.key, layout.page)
            }
        }

        val size = layout.size
        gui.setItem(MilestoneLayout.backSlot(size), GuiStyle.back("Back to Milestones")) { p, _ -> GuiStyle.clickSound(p); openMain(p) }
        if (layout.page > 0) {
            gui.setItem(MilestoneLayout.prevSlot(size), GuiStyle.previousPage(layout.page, layout.pages)) { p, _ ->
                GuiStyle.pageSound(p); openCategory(p, category, layout.page - 1)
            }
        }
        gui.setItem(MilestoneLayout.closeSlot(size), GuiStyle.close()) { p, _ -> p.closeInventory() }
        if (layout.page < layout.pages - 1) {
            gui.setItem(MilestoneLayout.nextSlot(size), GuiStyle.nextPage(layout.page, layout.pages)) { p, _ ->
                GuiStyle.pageSound(p); openCategory(p, category, layout.page + 1)
            }
        }
        val ready = book.claimable(category).size
        gui.setItem(
            MilestoneLayout.actionSlot(size),
            item(
                if (ready > 0) Material.LIME_DYE else Material.GRAY_DYE,
                Component.text("Claim All", if (ready > 0) NamedTextColor.GREEN else NamedTextColor.GRAY),
                listOf(gray(if (ready > 0) "$ready completed tier${if (ready == 1) "" else "s"} ready" else "Nothing to claim yet"))
            )
        ) { p, _ ->
            val results = manager.claimAll(p, category)
            if (results.isEmpty()) GuiStyle.errorSound(p)
            else results.filterIsInstance<MilestoneManager.ClaimResult.Refused>().firstOrNull()?.let { refused(p, it.reason) }
                ?: GuiStyle.successSound(p)
            openCategory(p, category, layout.page)
        }
        show(player, gui)
    }

    private fun unitOf(def: CategoryDef): String = def.objectives.firstOrNull()?.unit ?: ""

    private fun objectiveIcon(book: MilestoneBook, objective: ObjectiveDef): ItemStack {
        val key = objective.key
        val tier = book.tier(key)
        val ready = book.isClaimable(key)
        val lore = arrayListOf(
            gray("Tier $tier"),
            progressLine(book.progress(key), book.required(key), objective.unit),
            bar(book.progress(key), book.required(key)),
            Component.empty(),
            if (ready) Component.text("Complete! Click to claim.", NamedTextColor.GREEN)
            else Component.text("Click for details", NamedTextColor.YELLOW),
        )
        return item(
            material(objective.icon),
            Component.text(objective.name, if (ready) NamedTextColor.GREEN else NamedTextColor.WHITE),
            lore,
            glow = ready
        )
    }

    private fun rewardLines(key: String, tier: Int): List<Component> {
        val rewards = manager.settings?.catalog?.rewardsFor(key, tier) ?: return emptyList()
        val out = arrayListOf<Component>(Component.text("Tier $tier rewards:", NamedTextColor.GOLD))
        if (rewards.totalCoins > 0) out += Component.text(" • ${plugin.economyManager.format(rewards.totalCoins)}", NamedTextColor.YELLOW)
        if (rewards.totalCredits > 0) out += Component.text(" • ${plugin.creditsManager.format(rewards.totalCredits)} Credits", NamedTextColor.AQUA)
        for (spec in rewards.physical) out += Component.text(" • ${manager.rewardLabel(spec)}", NamedTextColor.LIGHT_PURPLE)
        return out
    }

    private fun claimAndRefresh(player: Player, key: String, reopen: () -> Unit) {
        when (val result = manager.claim(player, key)) {
            is MilestoneManager.ClaimResult.Claimed -> GuiStyle.successSound(player)
            is MilestoneManager.ClaimResult.Refused -> refused(player, result.reason)
        }
        reopen()
    }

    // ── Details ─────────────────────────────────────────────

    fun openDetails(player: Player, key: String, categoryPage: Int) {
        val s = settingsOrRefuse(player) ?: return
        val book = manager.book(player) ?: return refused(player, "Your milestones are still loading. Try again in a moment.")
        val objective = s.catalog.objective(key) ?: return refused(player, "That milestone doesn't exist.")
        val tier = book.tier(key)
        val progress = book.progress(key)
        val required = book.required(key)
        val ready = book.isClaimable(key)
        val gui = CustomGui(legacy("&8${objective.name}"), MilestoneLayout.DETAIL_SIZE)

        gui.setItem(
            MilestoneLayout.DETAIL_HEADER,
            item(
                material(objective.icon),
                Component.text(objective.name, NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true),
                listOf(
                    gray("${s.catalog.category(objective.category)?.name ?: ""} milestone"),
                    gray("Current tier: ").append(Component.text(tier, NamedTextColor.WHITE)),
                )
            )
        )
        gui.setItem(
            MilestoneLayout.DETAIL_PROGRESS,
            item(
                if (ready) Material.LIME_STAINED_GLASS else Material.YELLOW_STAINED_GLASS,
                Component.text("Progress", if (ready) NamedTextColor.GREEN else NamedTextColor.YELLOW),
                listOf(
                    progressLine(progress, required, objective.unit),
                    bar(progress, required),
                    Component.empty(),
                    if (ready) Component.text("Tier $tier complete!", NamedTextColor.GREEN)
                    else gray("${number.format(MilestoneMath.units(required - progress).coerceAtLeast(1))} to go"),
                )
            )
        )
        gui.setItem(MilestoneLayout.DETAIL_REWARDS, item(Material.GOLD_INGOT, Component.text("Current Tier Rewards", NamedTextColor.GOLD), rewardLines(key, tier).drop(1)))

        val upcoming = ArrayList<Component>()
        for (t in MilestoneMath.nextSpecialTiers(tier - 1, MilestoneLayout.UPCOMING_PREVIEW)) {
            val rewards = s.catalog.rewardsFor(key, t) ?: continue
            val label = if (MilestoneMath.isMajor(t)) "Tier $t (major)" else "Tier $t"
            upcoming += Component.text(label, if (MilestoneMath.isMajor(t)) NamedTextColor.LIGHT_PURPLE else NamedTextColor.GOLD)
            upcoming += gray(" ${manager.describe(rewards)}")
        }
        gui.setItem(MilestoneLayout.DETAIL_UPCOMING, item(Material.NETHER_STAR, Component.text("Upcoming Special Tiers", NamedTextColor.LIGHT_PURPLE), upcoming))

        gui.setItem(MilestoneLayout.DETAIL_BACK, GuiStyle.back("Back")) { p, _ ->
            GuiStyle.clickSound(p); openCategory(p, objective.category, categoryPage)
        }
        gui.setItem(
            MilestoneLayout.DETAIL_CLAIM,
            if (ready) item(Material.LIME_DYE, Component.text("Claim Tier $tier", NamedTextColor.GREEN), listOf(gray("Click to claim your rewards.")), glow = true)
            else item(Material.GRAY_DYE, Component.text("Not complete yet", NamedTextColor.RED), listOf(gray("Reach ${number.format(MilestoneMath.units(required))} to claim.")))
        ) { p, _ ->
            if (!ready) return@setItem GuiStyle.errorSound(p)
            claimAndRefresh(p, key) { openDetails(p, key, categoryPage) }
        }
        gui.setItem(MilestoneLayout.DETAIL_CLOSE, GuiStyle.close()) { p, _ -> p.closeInventory() }
        show(player, gui)
    }

    // ── Pending rewards ─────────────────────────────────────

    fun openPending(player: Player, page: Int) {
        val s = settingsOrRefuse(player) ?: return
        val total = try { manager.store.pendingCount(player.uniqueId) } catch (e: Exception) {
            return refused(player, "Couldn't read your pending rewards. Try again.")
        }
        val pages = MilestoneLayout.pages(total, MilestoneLayout.PENDING_PAGE_SIZE)
        val current = page.coerceIn(0, pages - 1)
        val rows = try { manager.store.listPending(player.uniqueId, current * MilestoneLayout.PENDING_PAGE_SIZE, MilestoneLayout.PENDING_PAGE_SIZE) } catch (e: Exception) {
            return refused(player, "Couldn't read your pending rewards. Try again.")
        }
        val size = MilestoneLayout.pendingSize(rows.size)
        val gui = CustomGui(legacy(s.pendingTitle), size)
        if (rows.isEmpty()) {
            gui.setItem(4, item(Material.MINECART, Component.text("Nothing waiting", NamedTextColor.GRAY), listOf(gray("Rewards that don't fit your inventory land here."))))
        }
        for ((i, row) in rows.withIndex()) {
            val display = manager.decodeForDisplay(row)?.clone() ?: ItemStack(Material.BARRIER)
            display.editMeta { meta ->
                val lore = (meta.lore() ?: emptyList()).toMutableList()
                lore += Component.empty()
                lore += gray("From ${s.catalog.displayName(row.source)} Tier ${row.tier}").decoration(TextDecoration.ITALIC, false)
                lore += Component.text("Click to withdraw", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                meta.lore(lore)
            }
            gui.setItem(i, display) { p, _ ->
                // Re-read the row: a stale screen can't hand out a row that was already withdrawn.
                val fresh = runCatching { manager.store.pending(p.uniqueId, row.id) }.getOrNull()
                when {
                    fresh == null -> refused(p, "That reward was already withdrawn.")
                    plugin.eventManager.isInEventWorld(p) -> refused(p, "Leave the event world to withdraw rewards.")
                    manager.deliver(p, fresh) -> GuiStyle.successSound(p)
                    else -> refused(p, "Not enough inventory space.")
                }
                openPending(p, current)
            }
        }
        gui.setItem(MilestoneLayout.backSlot(size), GuiStyle.back("Back to Milestones")) { p, _ -> GuiStyle.clickSound(p); openMain(p) }
        if (current > 0) gui.setItem(MilestoneLayout.prevSlot(size), GuiStyle.previousPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openPending(p, current - 1) }
        gui.setItem(MilestoneLayout.closeSlot(size), GuiStyle.close()) { p, _ -> p.closeInventory() }
        if (current < pages - 1) gui.setItem(MilestoneLayout.nextSlot(size), GuiStyle.nextPage(current, pages)) { p, _ -> GuiStyle.pageSound(p); openPending(p, current + 1) }
        gui.setItem(
            MilestoneLayout.actionSlot(size),
            item(
                if (total > 0) Material.HOPPER else Material.GRAY_DYE,
                Component.text("Withdraw All", if (total > 0) NamedTextColor.GREEN else NamedTextColor.GRAY),
                listOf(gray("Takes as much as fits."))
            )
        ) { p, _ ->
            if (plugin.eventManager.isInEventWorld(p)) {
                refused(p, "Leave the event world to withdraw rewards.")
                return@setItem
            }
            val (delivered, left) = manager.withdrawAll(p)
            if (delivered > 0) GuiStyle.successSound(p) else GuiStyle.errorSound(p)
            if (left > 0) plugin.commsManager.send(p, Component.text("$left reward${if (left == 1) "" else "s"} still waiting (inventory full).", NamedTextColor.YELLOW))
            openPending(p, 0)
        }
        show(player, gui)
    }
}
