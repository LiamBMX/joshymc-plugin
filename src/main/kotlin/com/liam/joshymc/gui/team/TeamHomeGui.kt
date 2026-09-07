package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/** Team Home sub-GUI for the /team GUI (issue #523) — routes into /team home and /team sethome. */
object TeamHomeGui {

    fun open(plugin: Joshymc, player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            TeamMainGui.open(plugin, player)
            return
        }

        val isOwner = plugin.teamManager.getPlayerRole(player.uniqueId) == "owner"
        val hasHome = plugin.teamManager.getTeamHome(teamName) != null

        val gui = CustomGui(Component.text("Team Home", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, TeamGuiUtil.filler())

        if (hasHome) {
            gui.setItem(11, TeamGuiUtil.item(
                Material.ENDER_PEARL,
                Component.text("Teleport Home", NamedTextColor.GREEN),
                listOf(Component.empty(), Component.text("Click to teleport to your team home.", NamedTextColor.GRAY))
            )) { p, _ -> Bukkit.dispatchCommand(p, "team home"); p.closeInventory() }
        } else {
            gui.setItem(11, TeamGuiUtil.item(
                Material.BARRIER,
                Component.text("No Home Set", NamedTextColor.RED),
                listOf(Component.empty(), Component.text("The owner has not set a team home yet.", NamedTextColor.GRAY))
            ))
        }

        if (isOwner) {
            gui.setItem(15, TeamGuiUtil.item(
                Material.RED_BED,
                Component.text("Set Team Home", NamedTextColor.YELLOW),
                listOf(Component.empty(), Component.text("Click to set the team home to your current location.", NamedTextColor.GRAY))
            )) { p, _ -> Bukkit.dispatchCommand(p, "team sethome"); open(plugin, p) }
        } else {
            gui.setItem(15, TeamGuiUtil.item(
                Material.GRAY_DYE,
                Component.text("Set Team Home", NamedTextColor.DARK_GRAY),
                listOf(Component.empty(), Component.text("You do not have permission to use this.", NamedTextColor.RED))
            ))
        }

        gui.setItem(22, TeamGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> TeamMainGui.open(plugin, p) }

        plugin.guiManager.open(player, gui)
    }
}
