package com.liam.joshymc.gui.casino

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.mines.MinesSetupGui
import com.liam.joshymc.gui.casino.roulette.RouletteGui
import com.liam.joshymc.gui.casino.towers.TowersSetupGui
import com.liam.joshymc.manager.CasinoManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * `/casino` main menu (issue #727) — the single public entry point into every Casino
 * game. Coins-only; never touches Credits. Games disabled in config still show a
 * button so the layout stays stable, but it's greyed out and does nothing.
 */
object CasinoMainGui {

    fun open(plugin: Joshymc, player: Player) {
        val gui = CustomGui(Component.text("Casino", NamedTextColor.DARK_PURPLE), 54)
        gui.border(CasinoGuiUtil.filler())

        gui.setItem(
            4,
            CasinoGuiUtil.item(
                Material.GOLD_INGOT,
                Component.text("Your Balance", NamedTextColor.GOLD),
                listOf(Component.text(plugin.economyManager.format(plugin.economyManager.getBalance(player)), NamedTextColor.GREEN))
            )
        )

        gameButton(gui, plugin, 19, Material.TNT, "Mines", CasinoManager.Game.MINES) { p, _ ->
            MinesSetupGui.open(plugin, p)
        }
        gameButton(gui, plugin, 21, Material.CLOCK, "Roulette", CasinoManager.Game.ROULETTE) { p, _ ->
            RouletteGui.open(plugin, p)
        }
        gameButton(gui, plugin, 23, Material.FIREWORK_ROCKET, "Crash", CasinoManager.Game.CRASH) { p, _ ->
            com.liam.joshymc.gui.casino.crash.CrashGui.open(plugin, p)
        }
        gameButton(gui, plugin, 25, Material.LADDER, "Towers", CasinoManager.Game.TOWERS) { p, _ ->
            TowersSetupGui.open(plugin, p)
        }

        gui.setItem(
            31,
            CasinoGuiUtil.item(
                Material.BOOK,
                Component.text("Casino Statistics", NamedTextColor.AQUA),
                listOf(Component.text("View your Casino history.", NamedTextColor.GRAY))
            )
        ) { p, _ -> CasinoStatsGui.open(plugin, p) }

        gui.setItem(49, CasinoGuiUtil.closeButton()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun gameButton(
        gui: CustomGui,
        plugin: Joshymc,
        slot: Int,
        material: Material,
        name: String,
        game: CasinoManager.Game,
        onClick: (Player, org.bukkit.event.inventory.InventoryClickEvent) -> Unit
    ) {
        val enabled = plugin.casinoManager.isGameEnabled(game)
        if (enabled) {
            gui.setItem(
                slot,
                CasinoGuiUtil.item(
                    material,
                    Component.text(name, NamedTextColor.GREEN),
                    listOf(Component.empty(), Component.text("Click to play.", NamedTextColor.GRAY))
                ),
                onClick
            )
        } else {
            gui.setItem(
                slot,
                CasinoGuiUtil.item(
                    material,
                    Component.text(name, NamedTextColor.GRAY),
                    listOf(Component.empty(), Component.text("Currently disabled.", NamedTextColor.DARK_GRAY))
                )
            )
        }
    }
}
