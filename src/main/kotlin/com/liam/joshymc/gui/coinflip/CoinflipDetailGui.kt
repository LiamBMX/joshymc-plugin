package com.liam.joshymc.gui.coinflip

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Detail screen for a single WAITING Coinflip listing (issue #642). If the
 * viewer is the creator, this shows info + a cancel button instead of a join
 * prompt — clicking your own listing must never let you join it. Otherwise
 * it's a confirm-join screen mirroring AuctionManager's buy confirmation.
 */
object CoinflipDetailGui {

    fun open(plugin: Joshymc, player: Player, id: Int, returnPage: Int) {
        val cf = plugin.coinflipManager.getCoinflip(id)
        if (cf == null || cf.status != com.liam.joshymc.manager.CoinflipManager.Status.WAITING) {
            plugin.commsManager.send(player, Component.text("That Coinflip is no longer available.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            CoinflipMainGui.open(plugin, player, returnPage)
            return
        }

        val pot = cf.betAmount * 2.0
        val creator = Bukkit.getOfflinePlayer(cf.creatorUuid)
        val isOwn = cf.creatorUuid == player.uniqueId

        val gui = CustomGui(
            Component.text(if (isOwn) "Your Coinflip" else "Join Coinflip", NamedTextColor.GOLD),
            27
        )

        val redGlass = CoinflipGuiUtil.item(Material.RED_STAINED_GLASS_PANE, Component.text(if (isOwn) "Cancel Coinflip" else "Back", NamedTextColor.RED))
        val greenGlass = CoinflipGuiUtil.item(Material.LIME_STAINED_GLASS_PANE, Component.text("Confirm Join", NamedTextColor.GREEN))

        for (i in 0 until 27) {
            val col = i % 9
            if (col < 4) {
                gui.setItem(i, redGlass) { p, _ ->
                    if (isOwn) {
                        p.closeInventory()
                        plugin.coinflipManager.cancelCoinflip(p, id)
                    } else {
                        CoinflipMainGui.open(plugin, p, returnPage)
                    }
                }
            } else if (col > 4 && !isOwn) {
                gui.setItem(i, greenGlass) { p, _ ->
                    p.closeInventory()
                    plugin.coinflipManager.joinCoinflip(p, id)
                }
            } else if (col > 4) {
                gui.setItem(i, CoinflipGuiUtil.filler())
            }
        }

        val lore = mutableListOf(
            Component.empty(),
            Component.text("Creator: ", NamedTextColor.GRAY).append(Component.text(cf.creatorName, NamedTextColor.WHITE)),
            Component.text("Bet: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(cf.betAmount)}", NamedTextColor.GREEN)),
            Component.text("Pot: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(pot)}", NamedTextColor.GOLD)),
            Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("Waiting for opponent", NamedTextColor.YELLOW))
        )
        if (!isOwn) {
            lore.add(Component.empty())
            lore.add(Component.text("Winner takes the full ${plugin.economyManager.formatShort(pot)} pot.", NamedTextColor.GRAY))
        }

        gui.setItem(13, CoinflipGuiUtil.skull(creator, Component.text(cf.creatorName, NamedTextColor.GOLD), lore))

        plugin.guiManager.open(player, gui)
    }
}
