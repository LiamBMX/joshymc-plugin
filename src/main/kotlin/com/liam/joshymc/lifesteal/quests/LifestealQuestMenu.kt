package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.quests.LifestealQuestLayout as L
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * The Lifesteal quest menu (issue #1425) on the shared [CustomGui] / [GuiStyle]: GuiManager cancels
 * every click on menu slots (nothing can be taken, moved or duplicated) and rate-limits clicks.
 *
 * The menu never changes anything itself. A claim click passes only the quest's row id to
 * [LifestealQuestManager.claim]; a reroll confirmation remembers the row id, the quest it showed and
 * the price it showed, and [LifestealQuestManager.reroll] refuses (never charges) when any of them
 * changed. Busy flags in the manager plus the store's conditional updates make double clicks and
 * two open menus harmless.
 */
class LifestealQuestMenu(private val plugin: Joshymc, private val manager: LifestealQuestManager) {

    private val open = HashMap<UUID, CustomGui>()

    private fun text(s: String, color: NamedTextColor = NamedTextColor.GRAY): Component = Component.text(s, color)
    private fun legacy(s: String): Component = plugin.commsManager.parseLegacy(s)

    fun openMain(player: Player) {
        manager.unavailableReason(player)?.let {
            manager.send(player, "&c$it")
            return
        }
        val s = manager.settings ?: return
        if (manager.book(player) == null) {
            manager.load(player)
            manager.send(player, "&7Your quests are loading. Try again in a moment.")
            return
        }
        val gui = CustomGui(legacy(s.gui.title), L.SIZE)
        render(player, gui)
        open[player.uniqueId] = gui
        gui.onClose = { p -> open.remove(p.uniqueId, gui) }
        plugin.guiManager.open(player, gui)
    }

    /** Redraws [player]'s open quest menu, if it is the one showing. */
    fun refresh(player: Player) {
        val gui = open[player.uniqueId] ?: return
        if (plugin.guiManager.getOpenGui(player) !== gui) {
            open.remove(player.uniqueId)
            return
        }
        render(player, gui)
    }

    /** Every 2s: live progress and timers. */
    fun refreshOpen() {
        for (uuid in open.keys.toList()) Bukkit.getPlayer(uuid)?.let { refresh(it) } ?: open.remove(uuid)
    }

    fun forget(uuid: UUID) {
        open.remove(uuid)
    }

    fun closeAll() {
        for (uuid in open.keys.toList()) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (plugin.guiManager.getOpenGui(player) === open[uuid]) player.closeInventory()
        }
        open.clear()
    }

    private fun render(player: Player, gui: CustomGui) {
        val book = manager.book(player) ?: return
        val s = manager.settings ?: return
        gui.inventory.clear()
        gui.clickHandlers.clear()
        val now = System.currentTimeMillis()
        val daily = book.boards[QuestPeriod.DAILY]
        val weekly = book.boards[QuestPeriod.WEEKLY]

        gui.setItem(L.HEADER, GuiStyle.item(Material.WRITABLE_BOOK, text("Lifesteal Quests", NamedTextColor.GOLD), listOf(
            text("Complete quests to earn rewards."),
            text("Claim each reward here once it's done."),
            Component.empty(),
            text("Daily reset in: ", NamedTextColor.GRAY).append(text(daily?.let { QuestCycles.formatRemaining(it.cycle.remainingMillis(now)) } ?: "-", NamedTextColor.WHITE)),
            text("Weekly reset in: ", NamedTextColor.GRAY).append(text(weekly?.let { QuestCycles.formatRemaining(it.cycle.remainingMillis(now)) } ?: "-", NamedTextColor.WHITE)),
        )))

        for (period in QuestPeriod.entries) {
            val board = book.boards[period] ?: continue
            val r = s.rerolls.getValue(period)
            val freeLeft = (r.freePerCycle - board.usage.free).coerceAtLeast(0)
            val paidLeft = (r.maxPaidPerCycle - board.usage.paid).coerceAtLeast(0)
            gui.setItem(L.clock(period), GuiStyle.item(Material.CLOCK, text("${period.label} Quests", NamedTextColor.YELLOW), listOf(
                text("Resets in: ", NamedTextColor.GRAY).append(text(QuestCycles.formatRemaining(board.cycle.remainingMillis(now)), NamedTextColor.WHITE)),
                Component.empty(),
                text("Free rerolls left: ", NamedTextColor.GRAY).append(text("$freeLeft", NamedTextColor.GREEN)),
                text("Paid rerolls left: ", NamedTextColor.GRAY).append(text("$paidLeft", NamedTextColor.AQUA)),
            )))
            val slots = L.quests(period)
            val rerollSlots = L.rerolls(period)
            val quests = board.quests.sortedBy { it.row.slot }
            for (i in slots.indices) {
                val live = quests.getOrNull(i)
                if (live == null) {
                    gui.setItem(slots[i], GuiStyle.item(Material.GRAY_DYE, text("No quest", NamedTextColor.DARK_GRAY), listOf(text("No other quest is available for you right now."))))
                    continue
                }
                gui.setItem(slots[i], questItem(live, board, now)) { p, _ -> onQuestClick(p, live.id) }
                gui.setItem(rerollSlots[i], rerollItem(player, live)) { p, _ -> onRerollClick(p, live.id) }
            }
        }
        gui.setItem(L.CLOSE, GuiStyle.close()) { p, _ -> p.closeInventory() }
    }

    private fun icon(o: QuestObjective): Material = when (o) {
        QuestObjective.PLAYER_KILL, QuestObjective.UNIQUE_PLAYER_KILL -> Material.IRON_SWORD
        QuestObjective.PVP_DAMAGE -> Material.NETHERITE_AXE
        QuestObjective.BOUNTY_CLAIM -> Material.GOLD_INGOT
        QuestObjective.PVP_VICTORY -> Material.TOTEM_OF_UNDYING
        QuestObjective.MINE_ORE, QuestObjective.TEAM_MINE_ORE -> Material.IRON_PICKAXE
        QuestObjective.HARVEST_CROP -> Material.WHEAT
        QuestObjective.CATCH_FISH -> Material.FISHING_ROD
        QuestObjective.KILL_MOB, QuestObjective.TEAM_MOB_KILL -> Material.ZOMBIE_HEAD
        QuestObjective.EXPLORE, QuestObjective.TEAM_EXPLORE -> Material.COMPASS
        QuestObjective.GATHER_BLOCK -> Material.OAK_LOG
        QuestObjective.TEAM_ASSIST, QuestObjective.TEAM_PVP_KILL -> Material.SHIELD
    }

    private fun questItem(live: LifestealQuestManager.Live, board: LifestealQuestManager.Board, now: Long): ItemStack {
        val row = live.row
        val scale = row.objective.scale
        val claimed = live.state == LifestealQuestStore.State.CLAIMED
        val done = !claimed && live.complete
        val expired = now >= row.cycleEnd || row.cycleId != board.cycle.id
        val lore = ArrayList<Component>()
        if (row.description.isNotBlank()) lore += legacy("&7${row.description}")
        lore += text("${row.category.label} · ${row.period.label}", NamedTextColor.DARK_GRAY)
        lore += Component.empty()
        lore += text("Progress: ", NamedTextColor.GRAY)
            .append(text("${QuestProgress.displayed(live.progress, scale)}/${QuestProgress.displayed(row.target, scale)} ${row.objective.unit}", NamedTextColor.WHITE))
            .append(text(" (${QuestProgress.percent(live.progress, row.target)}%)", NamedTextColor.YELLOW))
        lore += legacy(QuestProgress.bar(live.progress, row.target))
        lore += Component.empty()
        lore += text("Rewards:", NamedTextColor.GOLD)
        for (spec in row.rewards) lore += text(" • ${manager.rewardLabel(spec)}", NamedTextColor.YELLOW)
        if (row.team == TeamRequirement.REQUIRED) lore += text("Needs a Lifesteal team", NamedTextColor.AQUA)
        lore += Component.empty()
        lore += when {
            claimed -> text("Claimed", NamedTextColor.GREEN)
            expired -> text("This quest has expired.", NamedTextColor.RED)
            done -> text("Click to claim!", NamedTextColor.GREEN)
            else -> text("In progress", NamedTextColor.YELLOW)
        }
        val material = if (claimed) Material.LIME_STAINED_GLASS_PANE else icon(row.objective)
        val nameColor = when {
            claimed -> NamedTextColor.GREEN
            done -> NamedTextColor.GOLD
            else -> NamedTextColor.WHITE
        }
        val item = GuiStyle.item(material, legacy(row.name).colorIfAbsent(nameColor), lore)
        item.editMeta { meta ->
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES)
            if (done) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    private fun rerollItem(player: Player, live: LifestealQuestManager.Live): ItemStack =
        when (val q = manager.quote(player, live)) {
            is LifestealQuestManager.Quote.No -> GuiStyle.item(Material.GRAY_DYE, text("Reroll", NamedTextColor.DARK_GRAY), listOf(text(q.reason)))
            is LifestealQuestManager.Quote.Ok -> GuiStyle.item(Material.HOPPER, text("Reroll this quest", NamedTextColor.AQUA), listOf(
                text("Replace it with a different quest."),
                text("Cost: ", NamedTextColor.GRAY).append(priceText(q.price)),
                Component.empty(),
                text("Click to reroll", NamedTextColor.YELLOW),
            ))
        }

    private fun priceText(price: RerollRules.Price): Component = when (price) {
        RerollRules.Price.Free -> text("Free", NamedTextColor.GREEN)
        is RerollRules.Price.Credits -> text("${plugin.creditsManager.format(price.amount)} Credits", NamedTextColor.AQUA)
        RerollRules.Price.LimitReached -> text("No rerolls left", NamedTextColor.RED)
    }

    private fun onQuestClick(player: Player, assignmentId: Long) {
        val live = manager.book(player)?.find(assignmentId)
        if (live == null || live.state != LifestealQuestStore.State.COMPLETED && !(live.state == LifestealQuestStore.State.ACTIVE && live.complete)) {
            return
        }
        when (val r = manager.claim(player, assignmentId)) {
            is LifestealQuestManager.ActionResult.Done -> { GuiStyle.successSound(player); manager.send(player, r.message) }
            is LifestealQuestManager.ActionResult.Refused -> { GuiStyle.errorSound(player); manager.send(player, "&c${r.message}") }
        }
        refresh(player)
    }

    private fun onRerollClick(player: Player, assignmentId: Long) {
        val live = manager.book(player)?.find(assignmentId) ?: return refresh(player)
        when (val q = manager.quote(player, live)) {
            is LifestealQuestManager.Quote.No -> { GuiStyle.errorSound(player); manager.send(player, "&c${q.reason}") }
            is LifestealQuestManager.Quote.Ok -> { GuiStyle.clickSound(player); openConfirm(player, live, q.price) }
        }
    }

    /** The confirmation remembers exactly what it showed; the manager refuses if any of it changed. */
    private fun openConfirm(player: Player, live: LifestealQuestManager.Live, price: RerollRules.Price) {
        val s = manager.settings ?: return
        val assignmentId = live.id
        val questId = live.row.questId
        val gui = CustomGui(legacy(s.gui.rerollTitle), L.CONFIRM_SIZE)
        gui.setItem(L.CONFIRM_PREVIEW, GuiStyle.item(icon(live.row.objective), legacy(live.row.name), listOf(
            text("This quest will be replaced by a"),
            text("random different ${live.row.period.label} quest."),
            Component.empty(),
            text("Cost: ", NamedTextColor.GRAY).append(priceText(price)),
        )))
        var used = false
        gui.setItem(L.CONFIRM_YES, GuiStyle.item(Material.LIME_DYE, text("Confirm Reroll", NamedTextColor.GREEN), listOf(text("Cost: ", NamedTextColor.GRAY).append(priceText(price))))) { p, _ ->
            if (used) return@setItem
            used = true
            when (val r = manager.reroll(p, assignmentId, questId, price)) {
                is LifestealQuestManager.ActionResult.Done -> { GuiStyle.successSound(p); manager.send(p, r.message) }
                is LifestealQuestManager.ActionResult.Refused -> { GuiStyle.errorSound(p); manager.send(p, "&c${r.message}") }
            }
            openMain(p)
        }
        gui.setItem(L.CONFIRM_NO, GuiStyle.item(Material.RED_DYE, text("Cancel", NamedTextColor.RED))) { p, _ ->
            GuiStyle.clickSound(p)
            openMain(p)
        }
        open.remove(player.uniqueId)
        plugin.guiManager.open(player, gui)
    }
}
