package com.liam.joshymc.gui.coinflip

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CoinflipManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.ceil

/**
 * `/coinflip` (aliases `/cf`) main GUI — browsing IS the base command (issue
 * #642 explicitly asked for no separate "browse" subcommand). Lists every
 * WAITING Coinflip as the creator's skull, newest first, with Create/My
 * Coinflip/Close controls in the bottom row.
 */
object CoinflipMainGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        val gui = CustomGui(Component.text("Coinflip", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, CoinflipGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, CoinflipGuiUtil.filler())

        val coinflips = plugin.coinflipManager.getWaitingCoinflips(page)
        val total = plugin.coinflipManager.getTotalWaiting()
        val totalPages = maxOf(1, ceil(total / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)

        gui.setItem(
            4,
            CoinflipGuiUtil.item(
                Material.GOLD_INGOT,
                Component.text("Active Coinflips", NamedTextColor.YELLOW),
                listOf(Component.text("  $total waiting for an opponent", NamedTextColor.GRAY))
            )
        )

        if (coinflips.isEmpty()) {
            gui.setItem(
                22,
                CoinflipGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Active Coinflips", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("There are currently no active Coinflips.", NamedTextColor.GRAY))
                )
            )
        } else {
            for ((index, cf) in coinflips.withIndex()) {
                gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(plugin, player.uniqueId, cf)) { p, _ ->
                    CoinflipDetailGui.open(plugin, p, cf.id, clampedPage)
                }
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, CoinflipGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage - 1)
            }
        }

        val myWaiting = plugin.coinflipManager.getWaitingCountForPlayer(player.uniqueId)
        val busy = plugin.coinflipManager.isPlayerBusy(player.uniqueId)
        val myStatusLore = when {
            busy -> listOf(Component.text("  You are in an active flip.", NamedTextColor.GRAY))
            myWaiting > 0 -> listOf(Component.text("  You have $myWaiting Coinflip(s) waiting.", NamedTextColor.GRAY), Component.text("  Click your listing above to cancel.", NamedTextColor.DARK_GRAY))
            else -> listOf(Component.text("  You have no active Coinflip.", NamedTextColor.GRAY))
        }
        gui.setItem(
            47,
            CoinflipGuiUtil.item(Material.PLAYER_HEAD, Component.text("My Coinflip", NamedTextColor.AQUA), myStatusLore)
        )

        gui.setItem(
            49,
            CoinflipGuiUtil.item(
                Material.EMERALD,
                Component.text("Create Coinflip", NamedTextColor.GREEN),
                listOf(Component.empty(), Component.text("  Click to create a new Coinflip.", NamedTextColor.GRAY))
            )
        ) { p, _ -> promptCreate(plugin, p) }

        gui.setItem(51, CoinflipGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, CoinflipGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage + 1)
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun promptCreate(plugin: Joshymc, player: Player) {
        if (!player.hasPermission("joshymc.coinflip.create")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }
        plugin.coinflipManager.pendingCreateInputs[player.uniqueId] = System.currentTimeMillis() + 60_000L
        player.closeInventory()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
        plugin.commsManager.send(
            player,
            Component.text("Enter your Coinflip amount in chat. Type ", NamedTextColor.YELLOW)
                .append(Component.text("cancel", NamedTextColor.GOLD))
                .append(Component.text(" to cancel.", NamedTextColor.YELLOW)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun buildEntryIcon(plugin: Joshymc, viewerUuid: UUID, cf: CoinflipManager.CoinflipInfo): org.bukkit.inventory.ItemStack {
        val creator = Bukkit.getOfflinePlayer(cf.creatorUuid)
        val pot = cf.betAmount * 2.0
        val isOwn = cf.creatorUuid == viewerUuid

        val lore = mutableListOf(
            Component.empty(),
            Component.text("Creator: ", NamedTextColor.GRAY).append(Component.text(cf.creatorName, NamedTextColor.WHITE)),
            Component.text("Bet: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(cf.betAmount)}", NamedTextColor.GREEN)),
            Component.text("Pot: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(pot)}", NamedTextColor.GOLD)),
            Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("Waiting for opponent", NamedTextColor.YELLOW)),
            Component.empty()
        )
        lore.add(
            if (isOwn) Component.text("Click to view/cancel.", NamedTextColor.YELLOW)
            else Component.text("Click to join.", NamedTextColor.GREEN)
        )

        return CoinflipGuiUtil.skull(
            creator,
            Component.text(cf.creatorName, if (isOwn) NamedTextColor.AQUA else NamedTextColor.GOLD),
            lore
        )
    }
}
