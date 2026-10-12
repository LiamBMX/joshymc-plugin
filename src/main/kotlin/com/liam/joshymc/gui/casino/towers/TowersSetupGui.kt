package com.liam.joshymc.gui.casino.towers

import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.CasinoMainGui
import com.liam.joshymc.manager.CasinoTowersManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `/casino` -> Towers setup — pick bet + difficulty before the tower opens (issue #727). */
object TowersSetupGui {

    private data class Setup(var bet: Double, var difficulty: CasinoTowersManager.Difficulty)

    private val pending = ConcurrentHashMap<UUID, Setup>()

    /**
     * Entry point for `/towers <bet>` (issue #746) — preselects [bet] in the setup GUI
     * without starting a game. Callers must already have confirmed there's no active
     * session; an existing difficulty selection (if the player was already mid-setup)
     * is preserved, only the bet is overwritten.
     */
    fun openWithBet(plugin: Joshymc, player: Player, bet: Double) {
        pending.getOrPut(player.uniqueId) { Setup(bet, CasinoTowersManager.Difficulty.EASY) }.bet = bet
        open(plugin, player)
    }

    fun open(plugin: Joshymc, player: Player) {
        val setup = pending.getOrPut(player.uniqueId) {
            Setup(plugin.casinoManager.minBet, CasinoTowersManager.Difficulty.EASY)
        }

        val gui = CustomGui(Component.text("Towers Setup", NamedTextColor.GREEN), 27)
        gui.border(GuiStyle.filler(GuiStyle.THEMED_FILLER_MATERIAL))
        for (slot in 10..16) gui.setItem(slot, GuiStyle.filler(GuiStyle.THEMED_FILLER_MATERIAL))

        gui.setItem(
            10,
            GuiStyle.item(
                Material.GOLD_INGOT,
                Component.text("Bet: ${plugin.economyManager.format(setup.bet)}", NamedTextColor.GOLD),
                listOf(Component.text("Click to change your bet.", NamedTextColor.GRAY))
            )
        ) { p, _ ->
            plugin.casinoManager.promptAmount(p, onCancel = { p2 -> open(plugin, p2) }) { player2, amount ->
                pending.getOrPut(player2.uniqueId) { Setup(plugin.casinoManager.minBet, CasinoTowersManager.Difficulty.EASY) }.bet = amount
                open(plugin, player2)
            }
        }

        gui.setItem(
            12,
            GuiStyle.item(
                Material.LADDER,
                Component.text("Difficulty: ${setup.difficulty.name}", NamedTextColor.YELLOW),
                listOf(
                    Component.text("Click to cycle difficulty.", NamedTextColor.GRAY),
                    Component.text("${setup.difficulty.tiles} tiles per level, 1 losing tile.", NamedTextColor.DARK_GRAY)
                )
            )
        ) { p, _ ->
            val values = CasinoTowersManager.Difficulty.entries
            setup.difficulty = values[(setup.difficulty.ordinal + 1) % values.size]
            open(plugin, p)
        }

        val perLevel = plugin.casinoTowersManager.multiplierFor(setup.difficulty, 1)
        gui.setItem(
            13,
            GuiStyle.item(
                Material.PAPER,
                Component.text("Risk", NamedTextColor.YELLOW),
                listOf(
                    Component.text("${plugin.casinoTowersManager.levels} levels to climb.", NamedTextColor.GRAY),
                    Component.text("Per-level multiplier: ~${"%.2f".format(perLevel)}x", NamedTextColor.GRAY)
                )
            )
        )

        gui.setItem(
            14,
            GuiStyle.item(
                Material.EMERALD,
                Component.text("Confirm", NamedTextColor.GREEN),
                listOf(Component.text("Click to start Towers.", NamedTextColor.GRAY))
            )
        ) { p, _ ->
            val session = plugin.casinoTowersManager.startGame(p, setup.bet, setup.difficulty)
            if (session != null) {
                pending.remove(p.uniqueId)
                TowersBoardGui.open(plugin, p)
            }
        }

        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> CasinoMainGui.open(plugin, p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        GuiStyle.clickSound(player)
    }
}
