package com.liam.joshymc.gui.casino

import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/** Read-only Casino Statistics screen (issue #727) — persisted per-player totals. */
object CasinoStatsGui {

    fun open(plugin: Joshymc, player: Player) {
        val gui = CustomGui(Component.text("Casino Statistics", NamedTextColor.AQUA), 27)
        gui.border(GuiStyle.filler(GuiStyle.THEMED_FILLER_MATERIAL))

        val stats = plugin.casinoManager.getStats(player.uniqueId)
        val net = stats.won - stats.lost
        val netColor = if (net >= 0) NamedTextColor.GREEN else NamedTextColor.RED

        gui.setItem(
            11,
            GuiStyle.item(
                Material.GOLD_INGOT,
                Component.text("Overview", NamedTextColor.GOLD),
                listOf(
                    Component.text("Total Wagered: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.formatShort(stats.wagered), NamedTextColor.WHITE)),
                    Component.text("Total Won: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.formatShort(stats.won), NamedTextColor.GREEN)),
                    Component.text("Total Lost: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.formatShort(stats.lost), NamedTextColor.RED)),
                    Component.text("Net Profit/Loss: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.formatShort(net), netColor)),
                    Component.text("Biggest Win: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.formatShort(stats.biggestWin), NamedTextColor.GOLD))
                )
            )
        )

        gui.setItem(
            15,
            GuiStyle.item(
                Material.PAPER,
                Component.text("Games Played", NamedTextColor.AQUA),
                listOf(
                    Component.text("Mines: ", NamedTextColor.GRAY).append(Component.text("${stats.minesPlayed} played, ${stats.minesWins} won", NamedTextColor.WHITE)),
                    Component.text("Roulette: ", NamedTextColor.GRAY).append(Component.text("${stats.roulettePlayed} played, ${stats.rouletteWins} won", NamedTextColor.WHITE)),
                    Component.text("Crash: ", NamedTextColor.GRAY).append(Component.text("${stats.crashPlayed} played, ${stats.crashWins} won", NamedTextColor.WHITE)),
                    Component.text("Towers: ", NamedTextColor.GRAY).append(Component.text("${stats.towersPlayed} played, ${stats.towersWins} won", NamedTextColor.WHITE))
                )
            )
        )

        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> CasinoMainGui.open(plugin, p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        GuiStyle.clickSound(player)
    }
}
