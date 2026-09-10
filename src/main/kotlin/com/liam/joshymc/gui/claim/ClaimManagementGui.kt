package com.liam.joshymc.gui.claim

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.ClaimManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Per-claim management panel opened from the /claim GUI (issue #612) — either
 * from "My Claims" or the current-claim context button. Every mutation reuses
 * the same ClaimManager methods the /claim chat subcommands call; this GUI
 * just adds a permission-gated, location-independent frontend so an owner can
 * manage a claim without needing to stand inside it first.
 */
object ClaimManagementGui {

    fun open(plugin: Joshymc, player: Player, claimId: Int) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null) {
            ClaimMainGui.open(plugin, player)
            return
        }
        if (!plugin.claimManager.canManageClaim(player, claim)) {
            ClaimInfoGui.open(plugin, player, claimId) { p, viewer -> ClaimMainGui.open(p, viewer) }
            return
        }

        val gui = CustomGui(Component.text("Manage Claim #${claim.id}", NamedTextColor.GOLD), 45)
        for (slot in 0..44) gui.setItem(slot, ClaimGuiUtil.filler())

        val ownerName = Bukkit.getOfflinePlayer(claim.ownerUuid).name ?: "Unknown"
        gui.setItem(4, ClaimGuiUtil.item(
            Material.GRASS_BLOCK,
            Component.text("Claim #${claim.id}", NamedTextColor.GOLD),
            listOf(
                Component.empty(),
                Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(ownerName, NamedTextColor.WHITE)),
                Component.text("World: ", NamedTextColor.GRAY).append(Component.text(claim.world, NamedTextColor.WHITE)),
                Component.text("Size: ", NamedTextColor.GRAY).append(Component.text("${claim.maxX - claim.minX + 1}x${claim.maxZ - claim.minZ + 1} (${claim.area} blocks)", NamedTextColor.WHITE)),
                Component.text("Trusted: ", NamedTextColor.GRAY).append(Component.text("${claim.trusted.size}", NamedTextColor.WHITE))
            )
        ))

        gui.setItem(10, ClaimGuiUtil.item(
            Material.MAP, Component.text("Claim Info", NamedTextColor.YELLOW),
            listOf(Component.empty(), Component.text("View full details.", NamedTextColor.GRAY))
        )) { p, _ -> ClaimInfoGui.open(plugin, p, claim.id) { pl, viewer -> open(pl, viewer, claim.id) } }

        gui.setItem(11, ClaimGuiUtil.item(
            Material.LIME_DYE, Component.text("Trust Player", NamedTextColor.GREEN),
            listOf(Component.empty(), Component.text("Give another player full access.", NamedTextColor.GRAY))
        )) { p, _ -> openTrustPicker(plugin, p, claim.id) }

        gui.setItem(12, ClaimGuiUtil.item(
            Material.RED_DYE, Component.text("Untrust Player", NamedTextColor.RED),
            listOf(Component.empty(), Component.text("Remove a trusted player's access.", NamedTextColor.GRAY))
        )) { p, _ -> openUntrustPicker(plugin, p, claim.id) }

        gui.setItem(13, ClaimGuiUtil.item(
            Material.COMPARATOR, Component.text("Claim Settings", NamedTextColor.YELLOW),
            listOf(Component.empty(), Component.text("PvP, TNT, team sharing.", NamedTextColor.GRAY))
        )) { p, _ -> ClaimSettingsGui.open(plugin, p, claim.id) }

        gui.setItem(14, ClaimGuiUtil.item(
            Material.ENDER_PEARL, Component.text("Teleport to Claim", NamedTextColor.LIGHT_PURPLE),
            listOf(Component.empty(), Component.text("Teleport to this claim's center.", NamedTextColor.GRAY))
        )) { p, _ -> teleportToClaim(p, claim) }

        gui.setItem(40, ClaimGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> ClaimMainGui.open(plugin, p) }

        gui.setItem(44, ClaimGuiUtil.item(
            Material.BARRIER, Component.text("Delete Claim", NamedTextColor.DARK_RED),
            listOf(Component.empty(), Component.text("Permanently delete this claim.", NamedTextColor.GRAY))
        )) { p, _ ->
            ClaimConfirmGui.open(
                plugin, p,
                Component.text("Delete claim #${claim.id}?", NamedTextColor.RED),
                listOf(Component.text("This cannot be undone.", NamedTextColor.GRAY)),
                onConfirm = { pl, viewer ->
                    if (pl.claimManager.deleteClaim(viewer, claim)) {
                        pl.commsManager.send(viewer, Component.text("Claim removed. ${claim.area} blocks freed.", NamedTextColor.GREEN))
                    } else {
                        pl.commsManager.send(viewer, Component.text("You don't own this claim.", NamedTextColor.RED))
                    }
                    ClaimMainGui.open(pl, viewer)
                },
                onCancel = { pl, viewer -> open(pl, viewer, claim.id) }
            )
        }

        plugin.guiManager.open(player, gui)
    }

    private fun teleportToClaim(player: Player, claim: ClaimManager.Claim) {
        val world = Bukkit.getWorld(claim.world) ?: run { player.closeInventory(); return }
        val cx = (claim.minX + claim.maxX) / 2
        val cz = (claim.minZ + claim.maxZ) / 2
        val y = world.getHighestBlockYAt(cx, cz) + 1
        player.closeInventory()
        player.teleport(Location(world, cx + 0.5, y.toDouble(), cz + 0.5))
    }

    private fun openTrustPicker(plugin: Joshymc, player: Player, claimId: Int) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null) { ClaimMainGui.open(plugin, player); return }

        val entries = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != claim.ownerUuid && !claim.trusted.contains(it.uniqueId) }
            .map { ClaimPlayerPickerGui.Entry(it, listOf(Component.text("Click to trust.", NamedTextColor.GRAY))) }

        ClaimPlayerPickerGui.open(
            plugin, player,
            Component.text("Trust Player", NamedTextColor.GREEN),
            entries,
            Component.text("No Eligible Players Online", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer, claimId) },
            onPick = { p, viewer, target ->
                val current = p.claimManager.getClaimById(claimId)
                if (current != null && p.claimManager.canManageClaim(viewer, current)) {
                    if (p.claimManager.trustPlayer(current, target.uniqueId)) {
                        p.commsManager.send(viewer, Component.text("Trusted ${target.name} on this claim.", NamedTextColor.GREEN))
                    }
                }
                open(p, viewer, claimId)
            }
        )
    }

    private fun openUntrustPicker(plugin: Joshymc, player: Player, claimId: Int) {
        val claim = plugin.claimManager.getClaimById(claimId)
        if (claim == null) { ClaimMainGui.open(plugin, player); return }

        val entries = claim.trusted
            .map { Bukkit.getOfflinePlayer(it) }
            .sortedBy { it.name ?: "" }
            .map { ClaimPlayerPickerGui.Entry(it, listOf(Component.text("Click to untrust.", NamedTextColor.RED))) }

        ClaimPlayerPickerGui.open(
            plugin, player,
            Component.text("Untrust Player", NamedTextColor.RED),
            entries,
            Component.text("No Trusted Players", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer, claimId) },
            onPick = { p, viewer, target ->
                val current = p.claimManager.getClaimById(claimId)
                if (current != null && p.claimManager.canManageClaim(viewer, current)) {
                    if (p.claimManager.untrustPlayer(current, target.uniqueId)) {
                        p.commsManager.send(viewer, Component.text("Untrusted ${target.name ?: "player"} from this claim.", NamedTextColor.GREEN))
                    }
                }
                open(p, viewer, claimId)
            }
        )
    }
}
