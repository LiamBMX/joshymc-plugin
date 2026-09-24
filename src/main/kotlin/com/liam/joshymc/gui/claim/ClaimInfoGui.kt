package com.liam.joshymc.gui.claim

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Read-only claim info screen (issue #612), reachable both for a claim the
 * viewer manages and for someone else's claim — owner-only controls are only
 * exposed when [com.liam.joshymc.manager.ClaimManager.canManageClaim] passes.
 */
object ClaimInfoGui {

    fun open(plugin: Joshymc, player: Player, claimId: Int, onBack: (Joshymc, Player) -> Unit) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null) {
            onBack(plugin, player)
            return
        }

        val gui = CustomGui(Component.text("Claim Info", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, ClaimGuiUtil.filler())

        val ownerName = Bukkit.getOfflinePlayer(claim.ownerUuid).name ?: "Unknown"
        val subs = plugin.claimManager.getSubclaimsInClaim(claim)
        val created = SimpleDateFormat("MMM d, yyyy").format(Date(claim.createdAt))

        val lore = buildList {
            add(Component.empty())
            add(Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(ownerName, NamedTextColor.WHITE)))
            add(Component.text("World: ", NamedTextColor.GRAY).append(Component.text(claim.world, NamedTextColor.WHITE)))
            add(Component.text("Size: ", NamedTextColor.GRAY).append(Component.text("${claim.maxX - claim.minX + 1}x${claim.maxZ - claim.minZ + 1} (${claim.area} blocks)", NamedTextColor.WHITE)))
            add(Component.text("Corners: ", NamedTextColor.GRAY).append(Component.text("(${claim.minX}, ${claim.minZ}) to (${claim.maxX}, ${claim.maxZ})", NamedTextColor.DARK_GRAY)))
            if (claim.teamName != null) add(Component.text("Team: ", NamedTextColor.GRAY).append(Component.text(claim.teamName, NamedTextColor.AQUA)))
            add(Component.text("Trusted Players: ", NamedTextColor.GRAY).append(Component.text("${claim.trusted.size}", NamedTextColor.WHITE)))
            add(Component.text("Subclaims: ", NamedTextColor.GRAY).append(Component.text("${subs.size}", NamedTextColor.WHITE)))
            add(Component.text("PvP: ", NamedTextColor.GRAY).append(
                if (claim.pvpEnabled) Component.text("Enabled", NamedTextColor.RED) else Component.text("Disabled", NamedTextColor.GREEN)
            ))
            add(Component.text("TNT: ", NamedTextColor.GRAY).append(
                if (claim.tntEnabled) Component.text("Enabled", NamedTextColor.RED) else Component.text("Disabled", NamedTextColor.GREEN)
            ))
            add(Component.text("Created: ", NamedTextColor.GRAY).append(Component.text(created, NamedTextColor.WHITE)))
        }

        gui.setItem(13, ClaimGuiUtil.item(Material.MAP, Component.text("Claim #${claim.id}", NamedTextColor.GOLD), lore))

        if (plugin.claimManager.canManageClaim(player, claim)) {
            gui.setItem(15, ClaimGuiUtil.item(
                Material.GOLDEN_SHOVEL, Component.text("Manage This Claim", NamedTextColor.GREEN),
                listOf(Component.empty(), Component.text("Click to open management.", NamedTextColor.GRAY))
            )) { p, _ -> ClaimManagementGui.open(plugin, p, claim.id) }
        }

        gui.setItem(22, ClaimGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> onBack(plugin, p) }

        plugin.guiManager.open(player, gui)
    }
}
