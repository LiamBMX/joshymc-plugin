package com.liam.joshymc.gui.casino.mines

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.CasinoGuiUtil
import com.liam.joshymc.manager.CasinoMinesManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * `/casino` Mines board — 5x5 grid (issue #727). The grid lives in the middle five
 * columns of each row; the right-most column is a live info/actions panel. Closing
 * this GUI does NOT cancel the bet — [CasinoMinesManager] stays authoritative and the
 * player can reopen from `/casino` to resume.
 */
object MinesBoardGui {

    private val GRID_SLOTS = (0..4).map { row -> (0..4).map { col -> (row + 1) * 9 + (col + 1) } }

    fun open(plugin: Joshymc, player: Player) {
        val session = plugin.casinoMinesManager.getSession(player.uniqueId)
        if (session == null) {
            plugin.commsManager.send(player, Component.text("You don't have an active Mines game.", NamedTextColor.RED), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
            return
        }

        val gui = CustomGui(Component.text("Mines", NamedTextColor.RED), 54)
        val filler = CasinoGuiUtil.filler()
        for (slot in 0..8) gui.setItem(slot, filler)
        for (row in GRID_SLOTS.indices) {
            gui.setItem((row + 1) * 9, filler)
            gui.setItem((row + 1) * 9 + 6, filler)
            gui.setItem((row + 1) * 9 + 8, filler)
        }

        val multiplier = plugin.casinoMinesManager.currentMultiplier(session)
        val potentialPayout = session.bet * multiplier

        gui.setItem(
            4,
            CasinoGuiUtil.item(
                Material.TNT,
                Component.text("Mines", NamedTextColor.RED),
                listOf(
                    Component.text("Bet: ${plugin.economyManager.format(session.bet)}", NamedTextColor.GRAY),
                    Component.text("Mines: ${session.mines}", NamedTextColor.GRAY)
                )
            )
        )
        gui.setItem(8, CasinoGuiUtil.closeButton())

        for (row in GRID_SLOTS.indices) {
            for (col in GRID_SLOTS[row].indices) {
                val tile = row * 5 + col
                val slot = GRID_SLOTS[row][col]
                gui.setItem(slot, tileItem(session, tile)) { p, _ ->
                    when (val result = plugin.casinoMinesManager.reveal(p, tile)) {
                        is CasinoMinesManager.RevealResult.Safe -> {
                            p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
                            if (plugin.casinoMinesManager.getSession(p.uniqueId) != null) {
                                open(plugin, p)
                            } else {
                                plugin.commsManager.send(p, Component.text("Board cleared! Cashed out automatically.", NamedTextColor.GREEN), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
                                p.closeInventory()
                            }
                        }
                        CasinoMinesManager.RevealResult.Mine -> {
                            p.playSound(p.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
                            plugin.commsManager.send(p, Component.text("Boom! You hit a mine and lost ${plugin.economyManager.formatShort(session.bet)}.", NamedTextColor.RED), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
                            p.closeInventory()
                        }
                        CasinoMinesManager.RevealResult.Invalid -> {}
                    }
                }
            }
        }

        gui.setItem(
            16,
            CasinoGuiUtil.item(Material.PAPER, Component.text("Safe Revealed", NamedTextColor.GREEN), listOf(Component.text("${session.revealed.size} / ${25 - session.mines}", NamedTextColor.WHITE)))
        )
        gui.setItem(
            25,
            CasinoGuiUtil.item(Material.CLOCK, Component.text("Multiplier", NamedTextColor.YELLOW), listOf(Component.text("%.2fx".format(multiplier), NamedTextColor.WHITE)))
        )
        gui.setItem(
            34,
            CasinoGuiUtil.item(Material.GOLD_INGOT, Component.text("Potential Payout", NamedTextColor.GOLD), listOf(Component.text(plugin.economyManager.format(potentialPayout), NamedTextColor.WHITE)))
        )

        if (session.revealed.isNotEmpty()) {
            gui.setItem(
                52,
                CasinoGuiUtil.item(Material.EMERALD_BLOCK, Component.text("Cash Out", NamedTextColor.GREEN), listOf(Component.text("Click to secure ${plugin.economyManager.format(potentialPayout)}.", NamedTextColor.GRAY)))
            ) { p, _ ->
                val payout = plugin.casinoMinesManager.cashOut(p)
                if (payout != null) {
                    plugin.commsManager.send(p, Component.text("Cashed out for ${plugin.economyManager.formatShort(payout)}!", NamedTextColor.GREEN), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
                    p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
                }
                p.closeInventory()
            }
        } else {
            gui.setItem(
                52,
                CasinoGuiUtil.item(Material.GRAY_DYE, Component.text("Cash Out", NamedTextColor.GRAY), listOf(Component.text("Reveal a safe tile first.", NamedTextColor.DARK_GRAY)))
            )
        }

        plugin.guiManager.open(player, gui)
    }

    private fun tileItem(session: CasinoMinesManager.Session, tile: Int): org.bukkit.inventory.ItemStack {
        return when {
            tile in session.revealed -> CasinoGuiUtil.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Safe", NamedTextColor.GREEN))
            else -> CasinoGuiUtil.item(Material.GRAY_STAINED_GLASS_PANE, Component.text("?", NamedTextColor.WHITE))
        }
    }
}
