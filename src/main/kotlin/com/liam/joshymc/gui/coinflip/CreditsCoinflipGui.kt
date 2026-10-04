package com.liam.joshymc.gui.coinflip

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CoinflipManager
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.CreditsCoinflipManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import kotlin.math.ceil

/**
 * Browse / detail / animation GUIs for Credits Coinflip (issue #1031). Mirrors the
 * Money Coinflip screens (reusing [CoinflipGuiUtil]) but every amount is labelled
 * "Credits" and read from [CreditsCoinflipManager] — never the Money economy.
 */
object CreditsCoinflipGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9
    private val STEP_WEIGHTS = listOf(1, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5, 6, 8, 10, 13)

    private fun mgr(plugin: Joshymc) = plugin.creditsCoinflipManager

    // ---- Main browse GUI ----

    fun openMain(plugin: Joshymc, player: Player, page: Int = 0) {
        val gui = CustomGui(Component.text("Credits Coinflip", NamedTextColor.AQUA), 54)
        for (slot in 0..8) gui.setItem(slot, CoinflipGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, CoinflipGuiUtil.filler())

        val total = mgr(plugin).getTotalWaiting()
        val totalPages = maxOf(1, ceil(total / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val flips = mgr(plugin).getWaiting(clampedPage)

        gui.setItem(
            4,
            CoinflipGuiUtil.item(
                Material.AMETHYST_SHARD,
                Component.text("Active Credits Coinflips", NamedTextColor.YELLOW),
                listOf(
                    Component.text("  $total waiting for an opponent", NamedTextColor.GRAY),
                    Component.text("  Wagers: 1 - 10,000 Credits", NamedTextColor.GRAY)
                )
            )
        )

        if (flips.isEmpty()) {
            gui.setItem(
                22,
                CoinflipGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Active Coinflips", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("There are no active Credits Coinflips.", NamedTextColor.GRAY))
                )
            )
        } else {
            for ((index, cf) in flips.withIndex()) {
                gui.setItem(FIRST_CONTENT_SLOT + index, entryIcon(plugin, player.uniqueId, cf)) { p, _ ->
                    openDetail(plugin, p, cf.id, clampedPage)
                }
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, CoinflipGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                openMain(plugin, p, clampedPage - 1)
            }
        }

        val myWaiting = mgr(plugin).getWaitingCountForPlayer(player.uniqueId)
        val myLore = when {
            mgr(plugin).isPlayerBusy(player.uniqueId) -> listOf(Component.text("  You are in an active flip.", NamedTextColor.GRAY))
            myWaiting > 0 -> listOf(
                Component.text("  You have $myWaiting Coinflip(s) waiting.", NamedTextColor.GRAY),
                Component.text("  Click your listing above to cancel.", NamedTextColor.DARK_GRAY)
            )
            else -> listOf(Component.text("  You have no active Coinflip.", NamedTextColor.GRAY))
        }
        gui.setItem(47, CoinflipGuiUtil.item(Material.PLAYER_HEAD, Component.text("My Coinflip", NamedTextColor.AQUA), myLore))

        gui.setItem(
            49,
            CoinflipGuiUtil.item(
                Material.EMERALD,
                Component.text("Create Credits Coinflip", NamedTextColor.GREEN),
                listOf(
                    Component.empty(),
                    Component.text("  Click to wager Credits (max 10,000).", NamedTextColor.GRAY),
                    Component.text("  Your balance: ${plugin.creditsManager.format(plugin.creditsManager.getBalance(player))} Credits", NamedTextColor.GRAY)
                )
            )
        ) { p, _ -> promptCreate(plugin, p) }

        gui.setItem(51, CoinflipGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, CoinflipGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                openMain(plugin, p, clampedPage + 1)
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun promptCreate(plugin: Joshymc, player: Player) {
        if (!player.hasPermission(CreditsCoinflipManager.PERMISSION)) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }
        mgr(plugin).pendingCreateInputs[player.uniqueId] = System.currentTimeMillis() + 60_000L
        player.closeInventory()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
        plugin.commsManager.send(
            player,
            Component.text("Enter your Credits wager (1 - 10,000) in chat. Type ", NamedTextColor.YELLOW)
                .append(Component.text("cancel", NamedTextColor.GOLD))
                .append(Component.text(" to cancel.", NamedTextColor.YELLOW)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun entryIcon(plugin: Joshymc, viewer: UUID, cf: CreditsCoinflipManager.Flip): ItemStack {
        val isOwn = cf.creatorUuid == viewer
        val lore = mutableListOf(
            Component.empty(),
            Component.text("Creator: ", NamedTextColor.GRAY).append(Component.text(cf.creatorName, NamedTextColor.WHITE)),
            Component.text("Wager: ", NamedTextColor.GRAY).append(Component.text(mgr(plugin).fmt(cf.wager), NamedTextColor.AQUA)),
            Component.text("Winnings: ", NamedTextColor.GRAY).append(Component.text(mgr(plugin).fmt(cf.wager * 2), NamedTextColor.GOLD)),
            Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("Waiting for opponent", NamedTextColor.YELLOW)),
            Component.empty(),
            if (isOwn) Component.text("Click to view/cancel.", NamedTextColor.YELLOW) else Component.text("Click to join.", NamedTextColor.GREEN)
        )
        return CoinflipGuiUtil.skull(
            Bukkit.getOfflinePlayer(cf.creatorUuid),
            Component.text(cf.creatorName, if (isOwn) NamedTextColor.AQUA else NamedTextColor.GOLD),
            lore
        )
    }

    // ---- Detail GUI ----

    fun openDetail(plugin: Joshymc, player: Player, id: Int, returnPage: Int) {
        val cf = mgr(plugin).getFlip(id)
        if (cf == null || cf.status != CoinflipManager.Status.WAITING) {
            plugin.commsManager.send(player, Component.text("That Credits Coinflip is no longer available.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            openMain(plugin, player, returnPage)
            return
        }

        val isOwn = cf.creatorUuid == player.uniqueId
        val gui = CustomGui(Component.text(if (isOwn) "Your Credits Flip" else "Join Credits Flip", NamedTextColor.AQUA), 27)
        val red = CoinflipGuiUtil.item(Material.RED_STAINED_GLASS_PANE, Component.text(if (isOwn) "Cancel Coinflip" else "Back", NamedTextColor.RED))
        val green = CoinflipGuiUtil.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Confirm Join", NamedTextColor.GREEN))

        for (i in 0 until 27) {
            val col = i % 9
            if (col < 4) {
                gui.setItem(i, red) { p, _ ->
                    if (isOwn) {
                        p.closeInventory()
                        mgr(plugin).cancelFlip(p, id)
                    } else {
                        openMain(plugin, p, returnPage)
                    }
                }
            } else if (col > 4 && !isOwn) {
                gui.setItem(i, green) { p, _ ->
                    p.closeInventory()
                    mgr(plugin).joinFlip(p, id)
                }
            } else if (col > 4) {
                gui.setItem(i, CoinflipGuiUtil.filler())
            }
        }

        val lore = mutableListOf(
            Component.empty(),
            Component.text("Creator: ", NamedTextColor.GRAY).append(Component.text(cf.creatorName, NamedTextColor.WHITE)),
            Component.text("Wager: ", NamedTextColor.GRAY).append(Component.text(mgr(plugin).fmt(cf.wager), NamedTextColor.AQUA)),
            Component.text("Winnings: ", NamedTextColor.GRAY).append(Component.text(mgr(plugin).fmt(cf.wager * 2), NamedTextColor.GOLD)),
            Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("Waiting for opponent", NamedTextColor.YELLOW))
        )
        if (!isOwn) {
            lore.add(Component.empty())
            lore.add(Component.text("Winner takes the full ${mgr(plugin).fmt(cf.wager * 2)} pot.", NamedTextColor.GRAY))
        }
        gui.setItem(13, CoinflipGuiUtil.skull(Bukkit.getOfflinePlayer(cf.creatorUuid), Component.text(cf.creatorName, NamedTextColor.GOLD), lore))

        plugin.guiManager.open(player, gui)
    }

    // ---- Animation ----

    fun startAnimation(plugin: Joshymc, cf: CreditsCoinflipManager.Flip) {
        val challengerUuid = cf.challengerUuid ?: return
        val winnerUuid = cf.winnerUuid ?: return
        val creator = Bukkit.getOfflinePlayer(cf.creatorUuid)
        val challenger = Bukkit.getOfflinePlayer(challengerUuid)

        val gui = CustomGui(Component.text("Credits Coinflip!", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true), 27)
        for (i in 0 until 27) gui.inventory.setItem(i, CoinflipGuiUtil.filler(Material.CYAN_STAINED_GLASS_PANE))
        gui.inventory.setItem(10, sideSkull(plugin, creator, cf.creatorName, cf.wager))
        gui.inventory.setItem(16, sideSkull(plugin, challenger, cf.challengerName ?: "Unknown", cf.wager))

        Bukkit.getPlayer(cf.creatorUuid)?.let { plugin.guiManager.open(it, gui) }
        Bukkit.getPlayer(challengerUuid)?.let { plugin.guiManager.open(it, gui) }

        val steps = buildSteps(plugin.coinflipManager.animationDurationTicks)
        animate(plugin, gui, cf, creator, challenger, winnerUuid, steps.iterator(), true)
    }

    private fun buildSteps(totalTicks: Long): List<Long> {
        val weightSum = STEP_WEIGHTS.sum()
        var used = 0L
        val steps = mutableListOf<Long>()
        for ((index, weight) in STEP_WEIGHTS.withIndex()) {
            val delay = if (index == STEP_WEIGHTS.lastIndex) (totalTicks - used).coerceAtLeast(1L)
            else ((totalTicks * weight) / weightSum).coerceAtLeast(1L)
            steps.add(delay)
            used += delay
        }
        return steps
    }

    private fun animate(
        plugin: Joshymc, gui: CustomGui, cf: CreditsCoinflipManager.Flip,
        creator: OfflinePlayer, challenger: OfflinePlayer, winnerUuid: UUID,
        remaining: Iterator<Long>, showingCreator: Boolean
    ) {
        val (shown, shownName) = if (showingCreator) creator to cf.creatorName else challenger to (cf.challengerName ?: "Unknown")
        gui.inventory.setItem(13, CoinflipGuiUtil.skull(shown, Component.text(shownName, NamedTextColor.YELLOW)))
        for (viewer in gui.inventory.viewers.filterIsInstance<Player>()) {
            viewer.playSound(viewer.location, Sound.UI_BUTTON_CLICK, 0.6f, if (showingCreator) 1.4f else 1.0f)
        }

        if (!remaining.hasNext()) {
            land(plugin, gui, cf, creator, challenger, winnerUuid)
            return
        }
        val delay = remaining.next()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            animate(plugin, gui, cf, creator, challenger, winnerUuid, remaining, !showingCreator)
        }, delay)
    }

    private fun land(
        plugin: Joshymc, gui: CustomGui, cf: CreditsCoinflipManager.Flip,
        creator: OfflinePlayer, challenger: OfflinePlayer, winnerUuid: UUID
    ) {
        val creatorWon = winnerUuid == cf.creatorUuid
        val winnerName = if (creatorWon) cf.creatorName else cf.challengerName ?: "Unknown"
        gui.inventory.setItem(
            13,
            CoinflipGuiUtil.skull(
                if (creatorWon) creator else challenger,
                Component.text("WINNER!", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
                listOf(
                    Component.empty(),
                    Component.text(winnerName, NamedTextColor.GOLD),
                    Component.text("Won ${mgr(plugin).fmt(cf.wager * 2)}", NamedTextColor.GREEN)
                )
            )
        )
        for (viewer in gui.inventory.viewers.filterIsInstance<Player>()) {
            viewer.playSound(viewer.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        }

        // Payout never waits on the cosmetic result display.
        mgr(plugin).completeFlip(cf)

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            for (viewer in gui.inventory.viewers.filterIsInstance<Player>().toList()) {
                if (plugin.guiManager.getOpenGui(viewer) === gui) viewer.closeInventory()
            }
        }, plugin.coinflipManager.resultDisplayTicks)
    }

    private fun sideSkull(plugin: Joshymc, owner: OfflinePlayer, name: String, wager: Long) = CoinflipGuiUtil.skull(
        owner,
        Component.text(name, NamedTextColor.AQUA),
        listOf(Component.text("Wager: ", NamedTextColor.GRAY).append(Component.text(mgr(plugin).fmt(wager), NamedTextColor.AQUA)))
    )
}
