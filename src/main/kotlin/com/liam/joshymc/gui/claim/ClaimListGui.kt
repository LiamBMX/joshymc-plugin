package com.liam.joshymc.gui.claim

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.ClaimManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import kotlin.math.ceil

/** Paginated "My Claims" browser opened from the /claim GUI (issue #612). */
object ClaimListGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        val claims = plugin.claimManager.getClaimsByPlayer(player.uniqueId).sortedBy { it.id }

        val gui = CustomGui(Component.text("My Claims", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, ClaimGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, ClaimGuiUtil.filler())

        if (claims.isEmpty()) {
            gui.setItem(22, ClaimGuiUtil.item(
                Material.BARRIER,
                Component.text("You Have No Claims", NamedTextColor.RED),
                listOf(Component.empty(), Component.text("Use a claim wand to create one.", NamedTextColor.GRAY))
            ))
            gui.setItem(49, ClaimGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> ClaimMainGui.open(plugin, p) }
            plugin.guiManager.open(player, gui)
            return
        }

        val totalPages = maxOf(1, ceil(claims.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageClaims = claims.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, claim) in pageClaims.withIndex()) {
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(player, claim)) { p, _ ->
                ClaimManagementGui.open(plugin, p, claim.id)
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, ClaimGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage - 1)
            }
        }

        gui.setItem(47, ClaimGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))
        gui.setItem(49, ClaimGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> ClaimMainGui.open(plugin, p) }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, ClaimGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage + 1)
            }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun buildEntryIcon(player: Player, claim: ClaimManager.Claim): ItemStack {
        val lore = buildList {
            add(Component.empty())
            add(Component.text("World: ", NamedTextColor.GRAY).append(Component.text(claim.world, NamedTextColor.WHITE)))
            add(Component.text("Location: ", NamedTextColor.GRAY).append(Component.text("(${claim.minX}, ${claim.minZ})", NamedTextColor.WHITE)))
            add(Component.text("Size: ", NamedTextColor.GRAY).append(Component.text("${claim.maxX - claim.minX + 1}x${claim.maxZ - claim.minZ + 1} (${claim.area} blocks)", NamedTextColor.WHITE)))
            add(Component.text("Trusted: ", NamedTextColor.GRAY).append(Component.text("${claim.trusted.size}", NamedTextColor.WHITE)))
            if (claim.teamName != null) add(Component.text("Team: ", NamedTextColor.GRAY).append(Component.text(claim.teamName, NamedTextColor.AQUA)))
            if (claim.contains(player.location)) add(Component.text("You are here!", NamedTextColor.YELLOW))
            add(Component.empty())
            add(Component.text("Click to manage.", NamedTextColor.YELLOW))
        }
        return ClaimGuiUtil.item(Material.GRASS_BLOCK, Component.text("Claim #${claim.id}", NamedTextColor.GREEN), lore)
    }
}
