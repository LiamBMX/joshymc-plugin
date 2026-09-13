package com.liam.joshymc.gui.casino.crash

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.CasinoGuiUtil
import com.liam.joshymc.gui.casino.CasinoMainGui
import com.liam.joshymc.manager.CasinoCrashManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/casino` Crash (issue #727) — server-wide shared round. This GUI is a live view
 * into [CasinoCrashManager]'s round state; it is refreshed in place (not reopened)
 * on every manager tick while the player has it open, so it never drives the
 * outcome itself.
 */
object CrashGui {

    private val openGuis = ConcurrentHashMap<UUID, CustomGui>()

    fun open(plugin: Joshymc, player: Player) {
        val gui = CustomGui(Component.text("Crash", NamedTextColor.GOLD), 36)
        gui.border(CasinoGuiUtil.filler())
        for (slot in 10..16) gui.setItem(slot, CasinoGuiUtil.filler())
        for (slot in 19..25) gui.setItem(slot, CasinoGuiUtil.filler())

        gui.setItem(4, CasinoGuiUtil.item(Material.FIREWORK_ROCKET, Component.text("Crash", NamedTextColor.GOLD), listOf(Component.text("Bet during betting, cash out before it crashes!", NamedTextColor.GRAY))))
        gui.setItem(8, CasinoGuiUtil.closeButton())

        gui.setItem(29, CasinoGuiUtil.backButton()) { p, _ -> CasinoMainGui.open(plugin, p) }
        gui.setItem(33, CasinoGuiUtil.closeButton()) { p, _ -> p.closeInventory() }

        gui.onClose = { p ->
            openGuis.remove(p.uniqueId)
            plugin.casinoCrashManager.viewers.remove(p.uniqueId)
        }

        openGuis[player.uniqueId] = gui
        plugin.casinoCrashManager.viewers.add(player.uniqueId)
        refreshInto(plugin, player, gui)

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    }

    fun refresh(plugin: Joshymc, player: Player) {
        val gui = openGuis[player.uniqueId] ?: return
        refreshInto(plugin, player, gui)
    }

    private fun refreshInto(plugin: Joshymc, player: Player, gui: CustomGui) {
        val manager = plugin.casinoCrashManager
        val status = manager.getRoundStatus()
        val multiplier = manager.currentMultiplier()
        val liveBet = manager.getLiveBet(player.uniqueId)

        val statusLore = when (status) {
            CasinoCrashManager.RoundStatus.BETTING -> listOf(Component.text("Betting closes in ${manager.bettingSecondsRemaining()}s", NamedTextColor.YELLOW))
            CasinoCrashManager.RoundStatus.RUNNING -> listOf(Component.text("Round is running!", NamedTextColor.GREEN))
            CasinoCrashManager.RoundStatus.CRASHED -> listOf(Component.text("Crashed at %.2fx".format(multiplier), NamedTextColor.RED))
            CasinoCrashManager.RoundStatus.RESETTING -> listOf(Component.text("Starting next round...", NamedTextColor.GRAY))
        }
        gui.setItem(13, CasinoGuiUtil.item(Material.CLOCK, Component.text("%.2fx".format(multiplier), NamedTextColor.WHITE), statusLore))

        if (liveBet != null) {
            val potential = liveBet.bet * multiplier
            gui.setItem(
                11,
                CasinoGuiUtil.item(Material.GOLD_INGOT, Component.text("Your Bet", NamedTextColor.GOLD), listOf(Component.text(plugin.economyManager.format(liveBet.bet), NamedTextColor.WHITE)))
            )
            gui.setItem(
                15,
                if (status == CasinoCrashManager.RoundStatus.RUNNING && liveBet.status == CasinoCrashManager.BetStatus.PENDING) {
                    CasinoGuiUtil.item(Material.EMERALD_BLOCK, Component.text("Cash Out", NamedTextColor.GREEN), listOf(Component.text("Secure ${plugin.economyManager.format(potential)} now.", NamedTextColor.GRAY)))
                } else {
                    CasinoGuiUtil.item(Material.GRAY_DYE, Component.text("Cash Out", NamedTextColor.GRAY), listOf(Component.text(liveBet.status.name, NamedTextColor.DARK_GRAY)))
                }
            ) { p, _ ->
                val payout = manager.cashOut(p)
                if (payout != null) {
                    plugin.commsManager.send(p, Component.text("Cashed out for ${plugin.economyManager.formatShort(payout)}!", NamedTextColor.GREEN), CommunicationsManager.Category.CASINO)
                    p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
                }
            }
        } else {
            gui.setItem(
                11,
                if (status == CasinoCrashManager.RoundStatus.BETTING) {
                    CasinoGuiUtil.item(Material.EMERALD, Component.text("Place Bet", NamedTextColor.GREEN), listOf(Component.text("Click to bet this round.", NamedTextColor.GRAY)))
                } else {
                    CasinoGuiUtil.item(Material.GRAY_DYE, Component.text("Place Bet", NamedTextColor.GRAY), listOf(Component.text("Wait for the next round.", NamedTextColor.DARK_GRAY)))
                }
            ) { p, _ ->
                if (manager.getRoundStatus() != CasinoCrashManager.RoundStatus.BETTING) return@setItem
                plugin.casinoManager.promptAmount(p) { player2, amount -> manager.placeBet(player2, amount) }
            }
            gui.setItem(15, CasinoGuiUtil.item(Material.GRAY_DYE, Component.text("Cash Out", NamedTextColor.GRAY), listOf(Component.text("No active bet.", NamedTextColor.DARK_GRAY))))
        }
    }
}
