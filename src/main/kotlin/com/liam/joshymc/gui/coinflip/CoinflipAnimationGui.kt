package com.liam.joshymc.gui.coinflip

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CoinflipManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.Sound

/**
 * Shared animation GUI for a FLIPPING Coinflip (issue #642). Both players are
 * opened onto the SAME `CustomGui`/Inventory instance, so the flip is a single
 * live view rather than two independently-driven copies. The winner is
 * already decided (see CoinflipManager.startFlip) — this only ever renders
 * that outcome, it never influences it. Driven by a bounded chain of
 * `runTaskLater` calls (not a forever-ticking task): a fixed number of flips
 * that slow down, landing on the winner, then a short result hold before the
 * viewer(s) are closed out. Closing the GUI early does not stop the chain —
 * the payout in `completeCoinflip` fires regardless, so disconnecting or
 * manually closing can never cancel or duplicate the transaction.
 */
object CoinflipAnimationGui {

    private val TITLE: Component = Component.text("Coinflip!", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true)

    // Relative weights for each flip step's delay — increasing weights slow the
    // flip down over a fixed, bounded number of steps ("fast -> slower -> land").
    private val STEP_WEIGHTS = listOf(1, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5, 6, 8, 10, 13)

    private const val LEFT_SLOT = 10
    private const val RIGHT_SLOT = 16
    private const val CENTER_SLOT = 13

    fun start(plugin: Joshymc, cf: CoinflipManager.CoinflipInfo) {
        val challengerUuid = cf.challengerUuid ?: return
        val winnerUuid = cf.winnerUuid ?: return

        val creator = Bukkit.getOfflinePlayer(cf.creatorUuid)
        val challenger = Bukkit.getOfflinePlayer(challengerUuid)

        val gui = CustomGui(TITLE, 27)
        for (i in 0 until 27) gui.inventory.setItem(i, CoinflipGuiUtil.filler(org.bukkit.Material.YELLOW_STAINED_GLASS_PANE))
        gui.inventory.setItem(LEFT_SLOT, sideSkull(creator, cf.creatorName, cf.betAmount, plugin))
        gui.inventory.setItem(RIGHT_SLOT, sideSkull(challenger, cf.challengerName ?: "Unknown", cf.betAmount, plugin))

        Bukkit.getPlayer(cf.creatorUuid)?.let { plugin.guiManager.open(it, gui) }
        Bukkit.getPlayer(challengerUuid)?.let { plugin.guiManager.open(it, gui) }

        val steps = buildSteps(plugin.coinflipManager.animationDurationTicks)
        animate(plugin, gui, cf, creator, challenger, winnerUuid, steps.iterator(), showingCreator = true)
    }

    private fun buildSteps(totalTicks: Long): List<Long> {
        val weightSum = STEP_WEIGHTS.sum()
        var used = 0L
        val steps = mutableListOf<Long>()
        for ((index, weight) in STEP_WEIGHTS.withIndex()) {
            val delay = if (index == STEP_WEIGHTS.lastIndex) {
                (totalTicks - used).coerceAtLeast(1L)
            } else {
                ((totalTicks * weight) / weightSum).coerceAtLeast(1L)
            }
            steps.add(delay)
            used += delay
        }
        return steps
    }

    private fun animate(
        plugin: Joshymc,
        gui: CustomGui,
        cf: CoinflipManager.CoinflipInfo,
        creator: OfflinePlayer,
        challenger: OfflinePlayer,
        winnerUuid: java.util.UUID,
        remaining: Iterator<Long>,
        showingCreator: Boolean
    ) {
        val shown = if (showingCreator) creator to cf.creatorName else challenger to (cf.challengerName ?: "Unknown")
        gui.inventory.setItem(CENTER_SLOT, flippingSkull(shown.first, shown.second))

        for (viewer in gui.inventory.viewers.filterIsInstance<org.bukkit.entity.Player>()) {
            viewer.playSound(viewer.location, Sound.UI_BUTTON_CLICK, 0.6f, if (showingCreator) 1.4f else 1.0f)
        }

        if (!remaining.hasNext()) {
            landOnWinner(plugin, gui, cf, creator, challenger, winnerUuid)
            return
        }

        val delay = remaining.next()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            animate(plugin, gui, cf, creator, challenger, winnerUuid, remaining, !showingCreator)
        }, delay)
    }

    private fun landOnWinner(
        plugin: Joshymc,
        gui: CustomGui,
        cf: CoinflipManager.CoinflipInfo,
        creator: OfflinePlayer,
        challenger: OfflinePlayer,
        winnerUuid: java.util.UUID
    ) {
        val isCreatorWinner = winnerUuid == cf.creatorUuid
        val winnerOffline = if (isCreatorWinner) creator else challenger
        val winnerName = if (isCreatorWinner) cf.creatorName else cf.challengerName ?: "Unknown"
        val pot = cf.betAmount * 2.0

        gui.inventory.setItem(
            CENTER_SLOT,
            CoinflipGuiUtil.skull(
                winnerOffline,
                Component.text("WINNER!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
                listOf(
                    Component.empty(),
                    Component.text(winnerName, NamedTextColor.GOLD),
                    Component.text("Won ${plugin.economyManager.formatShort(pot)}", NamedTextColor.GREEN)
                )
            )
        )

        for (viewer in gui.inventory.viewers.filterIsInstance<org.bukkit.entity.Player>()) {
            viewer.playSound(viewer.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }

        // Pay out immediately — the result display below is purely cosmetic and
        // must never gate the transaction. cf.winnerUuid is already this winnerUuid
        // (set atomically in startFlip before the animation ever began).
        plugin.coinflipManager.completeCoinflip(cf)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (viewer in gui.inventory.viewers.filterIsInstance<org.bukkit.entity.Player>().toList()) {
                if (plugin.guiManager.getOpenGui(viewer) === gui) {
                    viewer.closeInventory()
                }
            }
        }, plugin.coinflipManager.resultDisplayTicks)
    }

    private fun sideSkull(owner: OfflinePlayer, name: String, bet: Double, plugin: Joshymc) = CoinflipGuiUtil.skull(
        owner,
        Component.text(name, NamedTextColor.AQUA),
        listOf(Component.text("Bet: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(bet)}", NamedTextColor.GREEN)))
    )

    private fun flippingSkull(owner: OfflinePlayer, name: String) = CoinflipGuiUtil.skull(
        owner,
        Component.text(name, NamedTextColor.YELLOW)
    )
}
