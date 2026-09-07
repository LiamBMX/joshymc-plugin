package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/** Team Settings sub-GUI for the /team GUI (issue #523) — friendly fire, open/close, rename, team chat. */
object TeamSettingsGui {

    fun open(plugin: Joshymc, player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            TeamMainGui.open(plugin, player)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        val isOwner = role == "owner"
        val isAdmin = role == "admin"

        val friendlyFire = plugin.teamManager.isTeamPvpEnabled(teamName)
        val teamOpen = plugin.teamManager.isTeamOpen(teamName)
        val teamChat = plugin.teamManager.isTeamChatEnabled(player.uniqueId)

        val gui = CustomGui(Component.text("Team Settings", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, TeamGuiUtil.filler())

        if (isOwner || isAdmin) {
            gui.setItem(10, TeamGuiUtil.item(
                if (friendlyFire) Material.LIME_DYE else Material.GRAY_DYE,
                Component.text("Friendly Fire", NamedTextColor.YELLOW),
                listOf(
                    Component.empty(),
                    Component.text("Status: ", NamedTextColor.GRAY).append(
                        if (friendlyFire) Component.text("Enabled", NamedTextColor.GREEN) else Component.text("Disabled", NamedTextColor.RED)
                    ),
                    Component.text("Click to toggle.", NamedTextColor.GRAY)
                )
            )) { p, _ -> Bukkit.dispatchCommand(p, "team pvp ${if (friendlyFire) "off" else "on"}"); open(plugin, p) }
        } else {
            gui.setItem(10, disabledItem("Friendly Fire"))
        }

        if (isOwner) {
            gui.setItem(12, TeamGuiUtil.item(
                if (teamOpen) Material.LIME_DYE else Material.GRAY_DYE,
                Component.text("Team Access", NamedTextColor.YELLOW),
                listOf(
                    Component.empty(),
                    Component.text("Status: ", NamedTextColor.GRAY).append(
                        if (teamOpen) Component.text("Open", NamedTextColor.GREEN) else Component.text("Invite Only", NamedTextColor.YELLOW)
                    ),
                    Component.text("Click to toggle.", NamedTextColor.GRAY)
                )
            )) { p, _ -> Bukkit.dispatchCommand(p, if (teamOpen) "team close" else "team open"); open(plugin, p) }

            gui.setItem(14, TeamGuiUtil.item(
                Material.NAME_TAG,
                Component.text("Rename Team", NamedTextColor.YELLOW),
                listOf(Component.empty(), Component.text("Click to rename your team.", NamedTextColor.GRAY))
            )) { p, _ ->
                TeamGuiInput.prompt(
                    plugin, p,
                    Component.text("Type the new team name in chat.", NamedTextColor.YELLOW),
                    command = { input -> "team rename $input" },
                    andThen = { pl, viewer -> open(pl, viewer) }
                )
            }
        } else {
            gui.setItem(12, disabledItem("Team Access"))
            gui.setItem(14, disabledItem("Rename Team"))
        }

        gui.setItem(16, TeamGuiUtil.item(
            if (teamChat) Material.LIME_DYE else Material.GRAY_DYE,
            Component.text("Team Chat", NamedTextColor.YELLOW),
            listOf(
                Component.empty(),
                Component.text("Status: ", NamedTextColor.GRAY).append(
                    if (teamChat) Component.text("Enabled", NamedTextColor.GREEN) else Component.text("Disabled", NamedTextColor.RED)
                ),
                Component.text("Click to toggle.", NamedTextColor.GRAY)
            )
        )) { p, _ -> Bukkit.dispatchCommand(p, "team chat ${if (teamChat) "off" else "on"}"); open(plugin, p) }

        gui.setItem(22, TeamGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> TeamMainGui.open(plugin, p) }

        plugin.guiManager.open(player, gui)
    }

    private fun disabledItem(label: String) = TeamGuiUtil.item(
        Material.GRAY_DYE,
        Component.text(label, NamedTextColor.DARK_GRAY),
        listOf(Component.empty(), Component.text("You do not have permission to use this.", NamedTextColor.RED))
    )
}
