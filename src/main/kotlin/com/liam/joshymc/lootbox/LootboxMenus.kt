package com.liam.joshymc.lootbox

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.lootbox.LootboxStore.Prize
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * The three Lootbox screens (issue #1602), all 54 slots ([LootboxLayout]):
 *
 * 1. Selection ("Level X Lootbox"): 27 identical mystery slots (0-26), pick exactly 5; info at 40,
 *    Cancel at 45, Confirm at 49. Memory only, per player; closing it uses nothing.
 * 2. Rolling ("Opening Level X Lootbox..."): five reels at 20-24 cycle through previews of the
 *    level's pool and stop one after another on the prizes already committed by
 *    [LootboxManager.confirm]. One repeating task per opening, cancelled as soon as it ends.
 * 3. Summary: the five prizes and Claim at 49.
 *
 * Every GUI slot is cancelled by GuiManager (drags, shift-clicks and number keys into the GUI too),
 * so nothing can be taken out. Closing the rolling or summary screen, the Claim button, a rejoin
 * and the one-minute sweep all deliver the same stored prizes; none of them can change them.
 */
class LootboxMenus(private val plugin: Joshymc, private val lootboxes: LootboxManager) {

    private class Session(val level: Int, val gui: CustomGui) {
        val selection = LootboxSelection()
    }

    private inner class Rolling(
        val uuid: UUID,
        val openingId: Int,
        val level: Int,
        val prizes: List<Prize>,
        val previews: List<ItemStack>,
        val gui: CustomGui,
        val plan: LootboxAnimationPlan.Plan,
    ) {
        var tick = 0
        var task: BukkitTask? = null
        val nextChange = IntArray(plan.reels)
        val stopped = BooleanArray(plan.reels)
    }

    private val sessions = HashMap<UUID, Session>()
    private val rolling = HashMap<UUID, Rolling>()
    private val summaries = HashMap<UUID, Int>()

    /** The opening [uuid] is watching roll right now (its prizes aren't delivered until it ends), or null. */
    fun animatingOpening(uuid: UUID): Int? = rolling[uuid]?.openingId

    fun isBusy(uuid: UUID): Boolean = uuid in sessions || uuid in rolling

    // ── 1. Selection ─────────────────────────────────────────────

    fun openSelection(player: Player, level: Int) {
        if (isBusy(player.uniqueId)) {
            lootboxes.send(player, lootboxes.legacy(lootboxes.message("busy", level)))
            return
        }
        lootboxes.openProblem(player, level)?.let {
            lootboxes.send(player, lootboxes.legacy(it))
            lootboxes.playSound(player, "denied")
            return
        }
        val settings = lootboxes.settings ?: return
        val gui = CustomGui(title(settings.gui.selectionTitle, level), LootboxLayout.SIZE)
        val session = Session(level, gui)
        sessions[player.uniqueId] = session
        drawSelection(session)
        for (slot in LootboxLayout.MYSTERY_SLOTS) {
            gui.clickHandlers[slot] = { p, _ -> onPick(p, session, slot) }
        }
        gui.clickHandlers[LootboxLayout.CANCEL_SLOT] = { p, _ -> p.closeInventory() }
        gui.clickHandlers[LootboxLayout.CONFIRM_SLOT] = { p, _ -> onConfirm(p, session) }
        gui.onClose = { p ->
            if (sessions[p.uniqueId] === session && !session.selection.locked) {
                sessions.remove(p.uniqueId)
                lootboxes.send(p, lootboxes.legacy(lootboxes.message("selection-cancelled", level)))
            }
        }
        plugin.guiManager.open(player, gui)
        lootboxes.playSound(player, "open")
    }

    private fun drawSelection(session: Session) {
        val settings = lootboxes.settings ?: return
        val gui = session.gui
        val selection = session.selection
        val filler = pane(settings.gui.fillerMaterial)
        for (slot in 0 until LootboxLayout.SIZE) gui.inventory.setItem(slot, filler)
        val order = selection.picks
        for (slot in LootboxLayout.MYSTERY_SLOTS) {
            gui.inventory.setItem(slot, if (selection.isPicked(slot)) {
                item(settings.gui.selectedMaterial, "&a&lPicked #${order.indexOf(slot) + 1}", listOf("&7Click to un-pick this slot."), glint = true)
            } else {
                item(settings.gui.mysteryMaterial, "&d&lMystery Slot", listOf("&7Click to pick this slot."))
            })
        }
        val left = selection.required - selection.count
        gui.inventory.setItem(LootboxLayout.INFO_SLOT, item(
            Material.BOOK, "&ePicked ${selection.count} / ${selection.required}",
            listOf(
                "&7Pick ${selection.required} mystery slots, then confirm.",
                "&7Each pick wins one prize, drawn at random",
                "&7from this level's rewards.",
            ),
        ))
        gui.inventory.setItem(LootboxLayout.CANCEL_SLOT, item(Material.BARRIER, "&cCancel", listOf("&7Your Lootbox is not used.")))
        gui.inventory.setItem(LootboxLayout.CONFIRM_SLOT, if (selection.ready) {
            item(Material.LIME_CONCRETE, "&a&lConfirm Selection", listOf("&7Opens your Lootbox (uses 1)."))
        } else {
            item(Material.GRAY_CONCRETE, "&7Confirm Selection", listOf("&cPick $left more slot${if (left == 1) "" else "s"}."))
        })
    }

    private fun onPick(player: Player, session: Session, slot: Int) {
        when (session.selection.toggle(slot)) {
            LootboxSelection.Toggle.SELECTED -> lootboxes.playSound(player, "select")
            LootboxSelection.Toggle.DESELECTED -> lootboxes.playSound(player, "deselect")
            LootboxSelection.Toggle.FULL -> {
                lootboxes.playSound(player, "denied")
                lootboxes.send(player, lootboxes.legacy(lootboxes.message("selection-full", session.level)))
                return
            }
            LootboxSelection.Toggle.INVALID, LootboxSelection.Toggle.LOCKED -> return
        }
        drawSelection(session)
    }

    private fun onConfirm(player: Player, session: Session) {
        if (sessions[player.uniqueId] !== session) return
        if (!session.selection.ready) {
            lootboxes.playSound(player, "denied")
            return
        }
        if (!session.selection.lock()) return
        sessions.remove(player.uniqueId)
        when (val outcome = lootboxes.confirm(player, session.level, session.selection.picks)) {
            is LootboxManager.ConfirmOutcome.Refused -> {
                player.closeInventory()
                lootboxes.send(player, lootboxes.legacy(outcome.message))
                lootboxes.playSound(player, "denied")
            }
            is LootboxManager.ConfirmOutcome.Committed -> {
                lootboxes.playSound(player, "confirm")
                lootboxes.send(player, lootboxes.legacy(lootboxes.message("opened", session.level)))
                startRolling(player, session.level, outcome)
            }
        }
    }

    // ── 2. Rolling ───────────────────────────────────────────────

    private fun startRolling(player: Player, level: Int, committed: LootboxManager.ConfirmOutcome.Committed) {
        val settings = lootboxes.settings ?: return deliverLater(player.uniqueId, committed.openingId)
        val plan = LootboxAnimationPlan.plan(LootboxLayout.REWARD_SLOTS.size, settings.animation)
        val gui = CustomGui(title(settings.gui.openingTitle, level), LootboxLayout.SIZE)
        val filler = pane(settings.gui.rollingFillerMaterial)
        for (slot in 0 until LootboxLayout.SIZE) gui.inventory.setItem(slot, filler)
        val roll = Rolling(player.uniqueId, committed.openingId, level, committed.prizes, committed.previews, gui, plan)
        for (reel in 0 until plan.reels) gui.inventory.setItem(LootboxLayout.REWARD_SLOTS[reel], randomPreview(roll, null))
        rolling[player.uniqueId] = roll
        gui.onClose = { p ->
            // Closing early skips to the end: the prizes are already committed.
            if (rolling[p.uniqueId] === roll) {
                endRolling(roll)
                deliverLater(p.uniqueId, roll.openingId)
            }
        }
        plugin.guiManager.open(player, gui)
        roll.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { step(roll) }, 1L, 1L)
    }

    private fun step(roll: Rolling) {
        val player = Bukkit.getPlayer(roll.uuid)
        if (player == null || rolling[roll.uuid] !== roll) {
            endRolling(roll)
            return
        }
        val settings = lootboxes.settings
        var changed = false
        for (reel in 0 until roll.plan.reels) {
            if (roll.stopped[reel]) continue
            val slot = LootboxLayout.REWARD_SLOTS[reel]
            if (roll.tick >= roll.plan.stopTicks[reel]) {
                roll.stopped[reel] = true
                roll.gui.inventory.setItem(slot, lootboxes.iconOf(roll.prizes[reel]))
                val lit = pane(settings?.gui?.stoppedMaterial ?: "LIME_STAINED_GLASS_PANE")
                LootboxLayout.indicatorSlots(reel).forEach { roll.gui.inventory.setItem(it, lit) }
                lootboxes.playSound(player, "reel-stop")
                continue
            }
            val changes = roll.plan.changeTicks[reel]
            var advanced = false
            while (roll.nextChange[reel] < changes.size && changes[roll.nextChange[reel]] <= roll.tick) {
                roll.nextChange[reel]++
                advanced = true
            }
            if (advanced) {
                roll.gui.inventory.setItem(slot, randomPreview(roll, roll.gui.inventory.getItem(slot)))
                changed = true
            }
        }
        if (changed) lootboxes.playSound(player, "roll")
        if (roll.stopped.all { it } && roll.tick >= roll.plan.endTick) {
            endRolling(roll)
            lootboxes.playSound(player, "finish")
            player.spawnParticle(Particle.TOTEM_OF_UNDYING, player.location.add(0.0, 1.0, 0.0), 30, 0.4, 0.6, 0.4, 0.2)
            openSummary(player, roll)
            return
        }
        roll.tick++
    }

    private fun endRolling(roll: Rolling) {
        roll.task?.cancel()
        roll.task = null
        if (rolling[roll.uuid] === roll) rolling.remove(roll.uuid)
    }

    /** A preview to show next: visual only, never tied to the outcome. Avoids repeating the one already shown. */
    private fun randomPreview(roll: Rolling, current: ItemStack?): ItemStack {
        val pool = roll.previews.ifEmpty { roll.prizes.map { lootboxes.iconOf(it) } }
        val rng = ThreadLocalRandom.current()
        var pick = pool[rng.nextInt(pool.size)]
        if (pool.size > 1 && current != null && pick.isSimilar(current)) pick = pool[rng.nextInt(pool.size)]
        return pick.clone()
    }

    // ── 3. Summary ───────────────────────────────────────────────

    private fun openSummary(player: Player, roll: Rolling) {
        val settings = lootboxes.settings ?: return deliverLater(player.uniqueId, roll.openingId)
        val gui = CustomGui(title(settings.gui.summaryTitle, roll.level), LootboxLayout.SIZE)
        val filler = pane(settings.gui.fillerMaterial)
        for (slot in 0 until LootboxLayout.SIZE) gui.inventory.setItem(slot, filler)
        val def = settings.level(roll.level)
        gui.inventory.setItem(LootboxLayout.TITLE_SLOT, item(
            Material.matchMaterial(def?.material ?: "CHEST") ?: Material.CHEST,
            def?.name ?: "&6Level ${roll.level} Lootbox",
            listOf("&7Level ${roll.level} · opening #${roll.openingId}", "&7Your five rewards are below."),
        ))
        for ((reel, prize) in roll.prizes.withIndex()) gui.inventory.setItem(LootboxLayout.REWARD_SLOTS[reel], lootboxes.iconOf(prize))
        val lit = pane(settings.gui.stoppedMaterial)
        for (reel in roll.prizes.indices) LootboxLayout.indicatorSlots(reel).forEach { gui.inventory.setItem(it, lit) }
        gui.inventory.setItem(LootboxLayout.CLAIM_SLOT, item(
            Material.LIME_CONCRETE, "&a&lClaim Rewards",
            listOf("&7Rewards go to your inventory.", "&7Anything that doesn't fit waits for you", "&7and is never dropped."),
        ))
        summaries[player.uniqueId] = roll.openingId
        gui.clickHandlers[LootboxLayout.CLAIM_SLOT] = { p, _ ->
            lootboxes.playSound(p, "claim")
            p.closeInventory()
        }
        gui.onClose = { p ->
            if (summaries[p.uniqueId] == roll.openingId) {
                summaries.remove(p.uniqueId)
                deliverLater(p.uniqueId, roll.openingId)
            }
        }
        plugin.guiManager.open(player, gui)
    }

    /** Delivers on the next tick (never inside an inventory close), only if the player is still here. */
    private fun deliverLater(uuid: UUID, openingId: Int) {
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val player = Bukkit.getPlayer(uuid) ?: return@Runnable
            if (player.isOnline) lootboxes.deliverOpening(player, openingId, announce = true)
        })
    }

    // ── Lifecycle ────────────────────────────────────────────────

    /** Quit: forget the screens. Committed prizes stay owed and are delivered on the next join. */
    fun forget(uuid: UUID) {
        sessions.remove(uuid)
        rolling[uuid]?.let { endRolling(it) }
        summaries.remove(uuid)
    }

    /** Plugin stop: end every animation and close the screens. Prizes stay owed (delivered after the restart). */
    fun closeAll() {
        val players = (sessions.keys + rolling.keys + summaries.keys).toSet()
        rolling.values.toList().forEach { endRolling(it) }
        sessions.clear()
        summaries.clear()
        for (uuid in players) Bukkit.getPlayer(uuid)?.closeInventory()
    }

    // ── Items ────────────────────────────────────────────────────

    private fun title(template: String, level: Int): Component {
        val name = lootboxes.settings?.level(level)?.name ?: "Level $level Lootbox"
        return lootboxes.legacy(LootboxRules.fill(template, mapOf("level" to level.toString(), "name" to name)))
    }

    private fun pane(material: String): ItemStack =
        ItemStack(Material.matchMaterial(material) ?: Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text(" ")) ; it.isHideTooltip = true }
        }

    private fun item(material: String, name: String, lore: List<String>, glint: Boolean = false): ItemStack =
        item(Material.matchMaterial(material) ?: Material.CHEST, name, lore, glint)

    private fun item(material: Material, name: String, lore: List<String>, glint: Boolean = false): ItemStack =
        ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(lootboxes.legacy(name))
                meta.lore(lore.map { lootboxes.legacy(it).decoration(TextDecoration.ITALIC, false) })
                if (glint) meta.setEnchantmentGlintOverride(true)
            }
        }
}
