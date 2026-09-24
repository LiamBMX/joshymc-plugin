package com.liam.joshymc.gui.bounty

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import kotlin.math.ceil

/**
 * "My Bounties" view opened from [BountyMainGui] (issue #556) — every bounty the
 * viewing player has personally placed, informational-first. Cancellation is still
 * only ever offered via [BountyDetailGui], gated server-side by `joshymc.bounty.cancel`,
 * same as the main Active Bounties list.
 */
object BountyMyGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        plugin.guiManager.open(player, build(plugin, player, page))
    }

    private fun build(plugin: Joshymc, player: Player, page: Int): CustomGui {
        val gui = CustomGui(Component.text("My Bounties", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, BountyGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, BountyGuiUtil.filler())
        gui.setItem(4, BountyGuiUtil.item(Material.GOLD_INGOT, Component.text("My Bounties", NamedTextColor.YELLOW)))

        val bounties = plugin.teamManager.getBountiesPlacedBy(player.uniqueId)

        if (bounties.isEmpty()) {
            gui.setItem(
                22,
                BountyGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Bounties Placed", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("You have not placed any active bounties.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(45, BountyGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> BountyMainGui.open(plugin, p) }
            gui.setItem(49, BountyGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }
            return gui
        }

        val totalPages = maxOf(1, ceil(bounties.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageBounties = bounties.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, bounty) in pageBounties.withIndex()) {
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(plugin, bounty)) { p, _ ->
                BountyDetailGui.open(plugin, p, bounty.id, clampedPage, BountyDetailGui.Origin.MINE)
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, BountyGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage - 1)
            }
        }

        gui.setItem(47, BountyGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))
        gui.setItem(48, BountyGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> BountyMainGui.open(plugin, p) }
        gui.setItem(49, BountyGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, BountyGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage + 1)
            }
        }

        return gui
    }

    private fun buildEntryIcon(plugin: Joshymc, bounty: com.liam.joshymc.manager.TeamManager.BountyInfo): ItemStack {
        val target = Bukkit.getOfflinePlayer(UUID.fromString(bounty.targetUuid))

        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = target
            meta.displayName(Component.text(bounty.targetName, NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            val lore = listOf(
                Component.empty(),
                Component.text("Bounty: ", NamedTextColor.GRAY).append(Component.text("$${plugin.economyManager.formatShort(bounty.amount)}", NamedTextColor.GREEN)),
                Component.text("Bounty ID: ", NamedTextColor.GRAY).append(Component.text("#${bounty.id}", NamedTextColor.WHITE)),
                Component.text("Status: ", NamedTextColor.GRAY).append(Component.text("Active", NamedTextColor.GREEN)),
                Component.empty(),
                Component.text("Click for details.", NamedTextColor.YELLOW)
            )
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return item
    }
}
