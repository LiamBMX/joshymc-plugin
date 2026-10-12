package com.liam.joshymc.gui.bounty

import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Entry point for bare `/bounty` (issue #556) — replaces the old chat help dump
 * with a GUI front end for the existing bounty system. Direct subcommands
 * (`/bounty set|list|cancel`) keep working exactly as before.
 */
object BountyMainGui {

    fun open(plugin: Joshymc, player: Player) {
        plugin.teamManager.clearBountySession(player.uniqueId)

        val gui = CustomGui(Component.text("Bounties", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, GuiStyle.filler())

        gui.setItem(
            11,
            GuiStyle.item(
                Material.EMERALD,
                Component.text("Place Bounty", NamedTextColor.GREEN),
                listOf(Component.empty(), Component.text("Place a bounty on another player.", NamedTextColor.GRAY))
            )
        ) { p, _ -> BountyPlaceGui.openPlayerSelect(plugin, p, 0) }

        gui.setItem(
            13,
            GuiStyle.item(
                Material.GOLD_INGOT,
                Component.text("Active Bounties", NamedTextColor.YELLOW),
                listOf(Component.empty(), Component.text("Browse every active bounty.", NamedTextColor.GRAY))
            )
        ) { p, _ -> BountyListGui.open(plugin, p) }

        gui.setItem(
            15,
            GuiStyle.item(
                Material.PLAYER_HEAD,
                Component.text("My Bounties", NamedTextColor.AQUA),
                listOf(Component.empty(), Component.text("View bounties you've placed.", NamedTextColor.GRAY))
            )
        ) { p, _ -> BountyMyGui.open(plugin, p) }

        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }
}
