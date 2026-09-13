package com.liam.joshymc.gui.casino.mines

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.CasinoGuiUtil
import com.liam.joshymc.gui.casino.CasinoMainGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `/casino` -> Mines setup — pick bet + mine count before the board opens (issue #727). */
object MinesSetupGui {

    private data class Setup(var bet: Double, var mines: Int)

    private val pending = ConcurrentHashMap<UUID, Setup>()

    fun open(plugin: Joshymc, player: Player) {
        val setup = pending.getOrPut(player.uniqueId) {
            Setup(plugin.casinoManager.minBet, plugin.casinoMinesManager.minMines)
        }
        setup.mines = setup.mines.coerceIn(plugin.casinoMinesManager.minMines, plugin.casinoMinesManager.maxMines)

        val gui = CustomGui(Component.text("Mines Setup", NamedTextColor.RED), 27)
        gui.border(CasinoGuiUtil.filler())
        for (slot in 10..16) gui.setItem(slot, CasinoGuiUtil.filler())

        gui.setItem(
            10,
            CasinoGuiUtil.item(
                Material.GOLD_INGOT,
                Component.text("Bet: ${plugin.economyManager.format(setup.bet)}", NamedTextColor.GOLD),
                listOf(Component.text("Click to change your bet.", NamedTextColor.GRAY))
            )
        ) { p, _ ->
            plugin.casinoManager.promptAmount(p, onCancel = { p2 -> open(plugin, p2) }) { player2, amount ->
                pending.getOrPut(player2.uniqueId) { Setup(plugin.casinoManager.minBet, plugin.casinoMinesManager.minMines) }.bet = amount
                open(plugin, player2)
            }
        }

        gui.setItem(
            12,
            CasinoGuiUtil.item(
                Material.TNT,
                Component.text("Mines: ${setup.mines}", NamedTextColor.RED),
                listOf(
                    Component.text("Left-click: +1", NamedTextColor.GRAY),
                    Component.text("Right-click: -1", NamedTextColor.GRAY),
                    Component.text("Range: ${plugin.casinoMinesManager.minMines}-${plugin.casinoMinesManager.maxMines}", NamedTextColor.DARK_GRAY)
                )
            )
        ) { p, event ->
            val delta = if (event.isRightClick) -1 else 1
            setup.mines = (setup.mines + delta).coerceIn(plugin.casinoMinesManager.minMines, plugin.casinoMinesManager.maxMines)
            open(plugin, p)
        }

        val safeMultiplier = plugin.casinoMinesManager.multiplierFor(setup.mines, 1)
        gui.setItem(
            13,
            CasinoGuiUtil.item(
                Material.PAPER,
                Component.text("Risk", NamedTextColor.YELLOW),
                listOf(
                    Component.text("25 tiles, ${setup.mines} mines.", NamedTextColor.GRAY),
                    Component.text("First safe reveal: ~${"%.2f".format(safeMultiplier)}x", NamedTextColor.GRAY)
                )
            )
        )

        gui.setItem(
            14,
            CasinoGuiUtil.item(
                Material.EMERALD,
                Component.text("Confirm", NamedTextColor.GREEN),
                listOf(Component.text("Click to start Mines.", NamedTextColor.GRAY))
            )
        ) { p, _ ->
            val session = plugin.casinoMinesManager.startGame(p, setup.bet, setup.mines)
            if (session != null) {
                pending.remove(p.uniqueId)
                MinesBoardGui.open(plugin, p)
            }
        }

        gui.setItem(18, CasinoGuiUtil.backButton()) { p, _ -> CasinoMainGui.open(plugin, p) }
        gui.setItem(22, CasinoGuiUtil.closeButton()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    }
}
