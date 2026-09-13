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

    /**
     * The multiplier CLOCK (slot 13) doubles as the Cash Out button (issue #734) so
     * the player can watch and click without moving their mouse. The click handler
     * always just calls [CasinoCrashManager.cashOut] and lets it be the sole source
     * of truth — it already re-validates round/bet state atomically, so a click that
     * lands the instant the round crashes can't race into a payout.
     */
    private fun refreshInto(plugin: Joshymc, player: Player, gui: CustomGui) {
        val manager = plugin.casinoCrashManager
        val status = manager.getRoundStatus()
        val multiplier = manager.currentMultiplier()
        val liveBet = manager.getLiveBet(player.uniqueId)

        val clockName: Component
        val lore = mutableListOf<Component>()

        when (status) {
            CasinoCrashManager.RoundStatus.BETTING -> {
                clockName = Component.text("Crash — Betting", NamedTextColor.YELLOW)
                lore.add(Component.text("Round starts in ${manager.bettingSecondsRemaining()}s", NamedTextColor.YELLOW))
                lore.add(
                    if (liveBet != null) Component.text("Bet: ${plugin.economyManager.format(liveBet.bet)}", NamedTextColor.GRAY)
                    else Component.text("No active bet", NamedTextColor.DARK_GRAY)
                )
            }
            CasinoCrashManager.RoundStatus.RUNNING -> {
                clockName = Component.text("Crash — %.2fx".format(multiplier), NamedTextColor.WHITE)
                when {
                    liveBet != null && liveBet.status == CasinoCrashManager.BetStatus.PENDING -> {
                        val potential = liveBet.bet * multiplier
                        lore.add(Component.text("Bet: ${plugin.economyManager.format(liveBet.bet)}", NamedTextColor.GRAY))
                        lore.add(Component.text("Potential Payout: ${plugin.economyManager.format(potential)}", NamedTextColor.GREEN))
                        lore.add(Component.empty())
                        lore.add(Component.text("Click to Cash Out", NamedTextColor.YELLOW))
                    }
                    liveBet != null && liveBet.status == CasinoCrashManager.BetStatus.CASHED_OUT -> {
                        lore.add(Component.text("Payout: ${plugin.economyManager.format(liveBet.bet * (liveBet.cashoutMultiplier ?: multiplier))}", NamedTextColor.GREEN))
                        lore.add(Component.text("Waiting for next round...", NamedTextColor.DARK_GRAY))
                    }
                    else -> lore.add(Component.text("No active bet", NamedTextColor.DARK_GRAY))
                }
            }
            CasinoCrashManager.RoundStatus.CRASHED -> {
                clockName = Component.text("Crash — Crashed", NamedTextColor.RED)
                lore.add(Component.text("Crashed at %.2fx".format(multiplier), NamedTextColor.RED))
                when (liveBet?.status) {
                    CasinoCrashManager.BetStatus.CASHED_OUT -> lore.add(Component.text("Payout: ${plugin.economyManager.format(liveBet.bet * (liveBet.cashoutMultiplier ?: 0.0))}", NamedTextColor.GREEN))
                    CasinoCrashManager.BetStatus.LOST -> lore.add(Component.text("You lost ${plugin.economyManager.format(liveBet.bet)}", NamedTextColor.RED))
                    else -> {}
                }
            }
            CasinoCrashManager.RoundStatus.RESETTING -> {
                clockName = Component.text("Crash — Resetting", NamedTextColor.GRAY)
                lore.add(Component.text("Starting next round...", NamedTextColor.GRAY))
            }
        }

        gui.setItem(13, CasinoGuiUtil.item(Material.CLOCK, clockName, lore)) { p, _ ->
            val payout = manager.cashOut(p)
            if (payout != null) {
                plugin.commsManager.send(p, Component.text("Cashed out for ${plugin.economyManager.formatShort(payout)}!", NamedTextColor.GREEN), CommunicationsManager.Category.CASINO)
                p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
            }
        }

        if (liveBet != null) {
            gui.setItem(
                11,
                CasinoGuiUtil.item(Material.GOLD_INGOT, Component.text("Your Bet", NamedTextColor.GOLD), listOf(Component.text(plugin.economyManager.format(liveBet.bet), NamedTextColor.WHITE)))
            )
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
        }
    }
}
