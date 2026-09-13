package com.liam.joshymc.gui.casino.roulette

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.casino.CasinoGuiUtil
import com.liam.joshymc.gui.casino.CasinoMainGui
import com.liam.joshymc.manager.CasinoRouletteManager
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/casino` Roulette (issue #727). Every bet type immediately prompts for an amount
 * and places the bet — the result is already rolled and persisted server-side by
 * [CasinoRouletteManager] before this GUI's spin animation even starts, so the
 * animation is purely cosmetic and cannot change the outcome.
 */
object RouletteGui {

    private val selectedNumber = ConcurrentHashMap<UUID, Int>()
    private val animRandom = SecureRandom()

    fun open(plugin: Joshymc, player: Player) {
        if (plugin.casinoRouletteManager.hasPendingBet(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("Your last Roulette bet is still resolving...", NamedTextColor.YELLOW), CommunicationsManager.Category.CASINO)
        }

        val number = selectedNumber.getOrDefault(player.uniqueId, 0)

        val gui = CustomGui(Component.text("Roulette", NamedTextColor.DARK_RED), 54)
        gui.border(CasinoGuiUtil.filler())
        for (slot in 10..16) gui.setItem(slot, CasinoGuiUtil.filler())
        for (slot in 19..25) gui.setItem(slot, CasinoGuiUtil.filler())
        for (slot in 28..34) gui.setItem(slot, CasinoGuiUtil.filler())
        for (slot in 37..43) gui.setItem(slot, CasinoGuiUtil.filler())

        gui.setItem(
            4,
            CasinoGuiUtil.item(
                Material.CLOCK,
                Component.text("Roulette", NamedTextColor.DARK_RED),
                listOf(Component.text("Pick a bet type — you'll be asked for an amount.", NamedTextColor.GRAY))
            )
        )

        // Main betting row — Red / Black / Green, spread evenly across the row.
        betTypeButton(gui, plugin, 10, Material.RED_WOOL, "Red", NamedTextColor.RED, CasinoRouletteManager.BetType.RED)
        betTypeButton(gui, plugin, 13, Material.BLACK_WOOL, "Black", NamedTextColor.DARK_GRAY, CasinoRouletteManager.BetType.BLACK)
        betTypeButton(gui, plugin, 16, Material.LIME_WOOL, "Green (0)", NamedTextColor.GREEN, CasinoRouletteManager.BetType.GREEN)

        // Second betting row — Odd / Even / Low / High, spread evenly across the row.
        betTypeButton(gui, plugin, 19, Material.PAPER, "Odd", NamedTextColor.YELLOW, CasinoRouletteManager.BetType.ODD)
        betTypeButton(gui, plugin, 21, Material.PAPER, "Even", NamedTextColor.YELLOW, CasinoRouletteManager.BetType.EVEN)
        betTypeButton(gui, plugin, 23, Material.IRON_INGOT, "Low (1-18)", NamedTextColor.AQUA, CasinoRouletteManager.BetType.LOW)
        betTypeButton(gui, plugin, 25, Material.GOLD_INGOT, "High (19-36)", NamedTextColor.AQUA, CasinoRouletteManager.BetType.HIGH)

        // Third row — exact-number picker, spread evenly across the row.
        gui.setItem(28, CasinoGuiUtil.item(Material.RED_DYE, Component.text("-1", NamedTextColor.RED))) { p, _ ->
            selectedNumber[p.uniqueId] = ((number - 1) % 37 + 37) % 37
            open(plugin, p)
        }
        gui.setItem(30, CasinoGuiUtil.item(Material.NAME_TAG, Component.text("Number: $number", NamedTextColor.WHITE)))
        gui.setItem(32, CasinoGuiUtil.item(Material.LIME_DYE, Component.text("+1", NamedTextColor.GREEN))) { p, _ ->
            selectedNumber[p.uniqueId] = (number + 1) % 37
            open(plugin, p)
        }
        gui.setItem(
            34,
            CasinoGuiUtil.item(
                Material.EMERALD,
                Component.text("Bet on Exact Number", NamedTextColor.GREEN),
                listOf(Component.text("Pays approximately 36x.", NamedTextColor.GRAY))
            )
        ) { p, _ -> promptAndPlace(plugin, p, CasinoRouletteManager.BetType.NUMBER, number) }

        // Fourth row — payout reference, centered.
        gui.setItem(
            40,
            CasinoGuiUtil.item(
                Material.BOOK,
                Component.text("Payouts", NamedTextColor.AQUA),
                listOf(
                    Component.text("Red/Black/Odd/Even/Low/High: ~${"%.2f".format(plugin.casinoRouletteManager.payoutMultiplier(CasinoRouletteManager.BetType.RED))}x", NamedTextColor.GRAY),
                    Component.text("Exact Number: ~${"%.2f".format(plugin.casinoRouletteManager.payoutMultiplier(CasinoRouletteManager.BetType.NUMBER))}x", NamedTextColor.GRAY)
                )
            )
        )

        gui.setItem(45, CasinoGuiUtil.backButton()) { p, _ -> CasinoMainGui.open(plugin, p) }
        gui.setItem(49, CasinoGuiUtil.closeButton()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    }

    private fun betTypeButton(gui: CustomGui, plugin: Joshymc, slot: Int, material: Material, name: String, color: NamedTextColor, betType: CasinoRouletteManager.BetType) {
        gui.setItem(
            slot,
            CasinoGuiUtil.item(
                material,
                Component.text(name, color),
                listOf(Component.text("Click to bet ${name}.", NamedTextColor.GRAY))
            )
        ) { p, _ -> promptAndPlace(plugin, p, betType, null) }
    }

    private fun promptAndPlace(plugin: Joshymc, player: Player, betType: CasinoRouletteManager.BetType, betValue: Int?) {
        plugin.casinoManager.promptAmount(player, onCancel = { p -> open(plugin, p) }) { p, amount ->
            val bet = plugin.casinoRouletteManager.placeBet(p, betType, betValue, amount)
            if (bet == null) {
                open(plugin, p)
                return@promptAmount
            }
            playSpinAnimation(plugin, p, bet)
        }
    }

    private fun playSpinAnimation(plugin: Joshymc, player: Player, bet: CasinoRouletteManager.Bet) {
        val gui = CustomGui(Component.text("Roulette — Spinning...", NamedTextColor.DARK_RED), 9)
        for (slot in listOf(0, 1, 2, 6, 7, 8)) gui.setItem(slot, CasinoGuiUtil.filler())
        gui.setItem(4, CasinoGuiUtil.item(Material.CLOCK, Component.text("...", NamedTextColor.WHITE)))
        plugin.guiManager.open(player, gui)

        var ticks = 0
        var task: BukkitTask? = null
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            ticks++
            if (ticks >= 30) {
                task?.cancel()
                gui.setItem(
                    4,
                    CasinoGuiUtil.item(
                        if (bet.result == 0) Material.LIME_WOOL else if (bet.result in CasinoRouletteManager.RED_NUMBERS) Material.RED_WOOL else Material.BLACK_WOOL,
                        Component.text("${bet.result}", CasinoRouletteManager.colorOf(bet.result))
                    )
                )
                plugin.casinoRouletteManager.resolve(bet.id)
                Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    if (player.isOnline && plugin.guiManager.getOpenGui(player) === gui) player.closeInventory()
                }, 30L)
                return@Runnable
            }
            val spin = animRandom.nextInt(37)
            gui.setItem(4, CasinoGuiUtil.item(Material.CLOCK, Component.text("$spin", CasinoRouletteManager.colorOf(spin))))
        }, 2L, 2L)
    }
}
