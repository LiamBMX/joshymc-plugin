package com.liam.joshymc.gui.casino.towers

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.CasinoGuiUtil
import com.liam.joshymc.manager.CasinoTowersManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player

/**
 * `/casino` Towers board (issue #727). Each level shows [Session.difficulty]'s tile
 * count, exactly one of which is the losing tile. Closing does NOT cancel the bet —
 * the session stays authoritative server-side.
 */
object TowersBoardGui {

    private fun tileSlots(tiles: Int): List<Int> = when (tiles) {
        2 -> listOf(12, 14)
        3 -> listOf(11, 13, 15)
        else -> listOf(10, 12, 14, 16)
    }

    fun open(plugin: Joshymc, player: Player) {
        val session = plugin.casinoTowersManager.getSession(player.uniqueId)
        if (session == null) {
            plugin.commsManager.send(player, Component.text("You don't have an active Towers game.", NamedTextColor.RED), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
            return
        }

        val gui = CustomGui(Component.text("Towers", NamedTextColor.GREEN), 36)
        gui.border(CasinoGuiUtil.filler())
        for (slot in 10..16) gui.setItem(slot, CasinoGuiUtil.filler())
        for (slot in 19..25) gui.setItem(slot, CasinoGuiUtil.filler())

        gui.setItem(
            4,
            CasinoGuiUtil.item(
                Material.LADDER,
                Component.text("Towers — ${session.difficulty.name}", NamedTextColor.GREEN),
                listOf(Component.text("Bet: ${plugin.economyManager.format(session.bet)}", NamedTextColor.GRAY))
            )
        )
        gui.setItem(8, CasinoGuiUtil.closeButton())

        val slots = tileSlots(session.difficulty.tiles)
        for ((tileIndex, slot) in slots.withIndex()) {
            gui.setItem(
                slot,
                CasinoGuiUtil.item(Material.YELLOW_STAINED_GLASS_PANE, Component.text("Climb", NamedTextColor.YELLOW), listOf(Component.text("Level ${session.level + 1} / ${plugin.casinoTowersManager.levels}", NamedTextColor.GRAY)))
            ) { p, _ ->
                when (val result = plugin.casinoTowersManager.pick(p, tileIndex)) {
                    is CasinoTowersManager.ClimbResult.Safe -> {
                        p.playSound(p.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)
                        if (result.completed) {
                            plugin.commsManager.send(p, Component.text("You climbed the whole tower! Cashed out automatically.", NamedTextColor.GREEN), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
                            p.closeInventory()
                        } else {
                            open(plugin, p)
                        }
                    }
                    CasinoTowersManager.ClimbResult.Lost -> {
                        p.playSound(p.location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
                        plugin.commsManager.send(p, Component.text("Wrong tile! You lost ${plugin.economyManager.formatShort(session.bet)}.", NamedTextColor.RED), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
                        p.closeInventory()
                    }
                    CasinoTowersManager.ClimbResult.Invalid -> {}
                }
            }
        }

        val multiplier = plugin.casinoTowersManager.currentMultiplier(session)
        val potentialPayout = session.bet * multiplier

        gui.setItem(19, CasinoGuiUtil.item(Material.PAPER, Component.text("Level", NamedTextColor.AQUA), listOf(Component.text("${session.level} / ${plugin.casinoTowersManager.levels}", NamedTextColor.WHITE))))
        gui.setItem(22, CasinoGuiUtil.item(Material.CLOCK, Component.text("Multiplier", NamedTextColor.YELLOW), listOf(Component.text("%.2fx".format(multiplier), NamedTextColor.WHITE))))
        gui.setItem(25, CasinoGuiUtil.item(Material.GOLD_INGOT, Component.text("Potential Payout", NamedTextColor.GOLD), listOf(Component.text(plugin.economyManager.format(potentialPayout), NamedTextColor.WHITE))))

        if (session.level > 0) {
            gui.setItem(
                31,
                CasinoGuiUtil.item(Material.EMERALD_BLOCK, Component.text("Cash Out", NamedTextColor.GREEN), listOf(Component.text("Click to secure ${plugin.economyManager.format(potentialPayout)}.", NamedTextColor.GRAY)))
            ) { p, _ ->
                val payout = plugin.casinoTowersManager.cashOut(p)
                if (payout != null) {
                    plugin.commsManager.send(p, Component.text("Cashed out for ${plugin.economyManager.formatShort(payout)}!", NamedTextColor.GREEN), com.liam.joshymc.manager.CommunicationsManager.Category.CASINO)
                    p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
                }
                p.closeInventory()
            }
        } else {
            gui.setItem(
                31,
                CasinoGuiUtil.item(Material.GRAY_DYE, Component.text("Cash Out", NamedTextColor.GRAY), listOf(Component.text("Clear a level first.", NamedTextColor.DARK_GRAY)))
            )
        }

        plugin.guiManager.open(player, gui)
    }
}
