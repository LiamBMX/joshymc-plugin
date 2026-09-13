package com.liam.joshymc.gui.claim

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Per-claim settings sub-GUI (issue #612) — PvP, TNT, and team/personal
 * sharing toggles. Only exposes settings ClaimManager already supports.
 */
object ClaimSettingsGui {

    fun open(plugin: Joshymc, player: Player, claimId: Int) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null || !plugin.claimManager.canManageClaim(player, claim)) {
            ClaimMainGui.open(plugin, player)
            return
        }

        val gui = CustomGui(Component.text("Claim Settings", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, ClaimGuiUtil.filler())

        gui.setItem(10, ClaimGuiUtil.item(
            if (claim.pvpEnabled) Material.LIME_DYE else Material.GRAY_DYE,
            Component.text("PvP", NamedTextColor.YELLOW),
            listOf(
                Component.empty(),
                Component.text("Status: ", NamedTextColor.GRAY).append(
                    if (claim.pvpEnabled) Component.text("Enabled", NamedTextColor.RED) else Component.text("Disabled", NamedTextColor.GREEN)
                ),
                Component.text("Click to toggle.", NamedTextColor.GRAY)
            )
        )) { p, _ ->
            plugin.claimManager.setClaimPvp(claim.id, !claim.pvpEnabled)
            open(plugin, p, claimId)
        }

        gui.setItem(12, ClaimGuiUtil.item(
            if (claim.tntEnabled) Material.LIME_DYE else Material.GRAY_DYE,
            Component.text("TNT", NamedTextColor.YELLOW),
            listOf(
                Component.empty(),
                Component.text("Status: ", NamedTextColor.GRAY).append(
                    if (claim.tntEnabled) Component.text("Enabled", NamedTextColor.RED) else Component.text("Disabled", NamedTextColor.GREEN)
                ),
                Component.text("Click to toggle.", NamedTextColor.GRAY)
            )
        )) { p, _ ->
            plugin.claimManager.setClaimTnt(claim.id, !claim.tntEnabled)
            open(plugin, p, claimId)
        }

        val isOwner = claim.ownerUuid == player.uniqueId
        val onTeam = plugin.teamManager.getPlayerTeam(player.uniqueId) != null
        if (isOwner && onTeam) {
            gui.setItem(14, ClaimGuiUtil.item(
                if (claim.teamName != null) Material.LIME_DYE else Material.GRAY_DYE,
                Component.text("Team Sharing", NamedTextColor.YELLOW),
                listOf(
                    Component.empty(),
                    Component.text("Status: ", NamedTextColor.GRAY).append(
                        if (claim.teamName != null) Component.text("Shared with ${claim.teamName}", NamedTextColor.AQUA) else Component.text("Personal", NamedTextColor.GRAY)
                    ),
                    Component.text("Click to toggle.", NamedTextColor.GRAY)
                )
            )) { p, _ ->
                if (claim.teamName != null) plugin.claimManager.assignToPersonal(p, claim) else plugin.claimManager.assignToTeam(p, claim)
                open(plugin, p, claimId)
            }
        } else {
            gui.setItem(14, ClaimGuiUtil.item(
                Material.GRAY_DYE, Component.text("Team Sharing", NamedTextColor.DARK_GRAY),
                listOf(Component.empty(), Component.text("Only the owner (while on a team) can toggle this.", NamedTextColor.RED))
            ))
        }

        gui.setItem(22, ClaimGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> ClaimManagementGui.open(plugin, p, claimId) }

        plugin.guiManager.open(player, gui)
    }
}
