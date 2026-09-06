package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.TeamManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.UUID
import kotlin.math.ceil

/**
 * "/team top" competitive leaderboard GUI (issue #477). Separate from the
 * plain-text "/team list" browser — this ranks teams by kills or bank
 * balance and lets a player click through to [TeamInfoGui] for details.
 */
object TeamTopGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, sort: TeamManager.TeamSort = TeamManager.TeamSort.KILLS, page: Int = 0) {
        plugin.guiManager.open(player, build(plugin, sort, page))
    }

    private fun build(plugin: Joshymc, sort: TeamManager.TeamSort, page: Int): CustomGui {
        val categoryName = if (sort == TeamManager.TeamSort.KILLS) "Top Kills" else "Top Balance"
        val gui = CustomGui(Component.text("Team Leaderboard", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, TeamGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, TeamGuiUtil.filler())
        gui.setItem(4, TeamGuiUtil.item(Material.PAPER, Component.text("Ranking by: $categoryName", NamedTextColor.YELLOW)))

        val rankings = plugin.teamManager.getTeamRankings(sort)

        if (rankings.isEmpty()) {
            gui.setItem(
                22,
                TeamGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Teams Available", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("There are currently no teams to rank.", NamedTextColor.GRAY))
                )
            )
            return gui
        }

        val totalPages = maxOf(1, ceil(rankings.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageRankings = rankings.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, ranking) in pageRankings.withIndex()) {
            val rank = clampedPage * PAGE_SIZE + index + 1
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(plugin, ranking, rank)) { p, _ ->
                TeamInfoGui.open(plugin, p, ranking.name)
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, TeamGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, sort, clampedPage - 1)
            }
        }

        gui.setItem(47, TeamGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))

        val otherSort = if (sort == TeamManager.TeamSort.KILLS) TeamManager.TeamSort.BALANCE else TeamManager.TeamSort.KILLS
        val otherName = if (otherSort == TeamManager.TeamSort.KILLS) "Top Kills" else "Top Balance"
        gui.setItem(
            49,
            TeamGuiUtil.item(
                Material.NETHER_STAR,
                Component.text("Category: $categoryName", NamedTextColor.AQUA),
                listOf(Component.empty(), Component.text("Click to switch to $otherName", NamedTextColor.GRAY))
            )
        ) { p, _ -> open(plugin, p, otherSort, 0) }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, TeamGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, sort, clampedPage + 1)
            }
        }

        return gui
    }

    private fun buildEntryIcon(plugin: Joshymc, ranking: TeamManager.TeamRanking, rank: Int): ItemStack {
        val owner = Bukkit.getOfflinePlayer(UUID.fromString(ranking.ownerUuid))
        // Top 3 get a visually distinct block so they're recognizable at a glance;
        // everyone else gets the owner's head so entries stay identifiable.
        val material = when (rank) {
            1 -> Material.GOLD_BLOCK
            2 -> Material.IRON_BLOCK
            3 -> Material.COPPER_BLOCK
            else -> Material.PLAYER_HEAD
        }
        val nameColor = when (rank) {
            1 -> NamedTextColor.GOLD
            2 -> NamedTextColor.GRAY
            3 -> TextColor.color(0xB87333)
            else -> NamedTextColor.GREEN
        }

        val item = ItemStack(material)
        item.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = owner
            meta.displayName(
                Component.text("#$rank ", NamedTextColor.YELLOW)
                    .append(Component.text(ranking.displayName, nameColor))
                    .decoration(TextDecoration.ITALIC, false)
            )
            val lore = listOf(
                Component.empty(),
                Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(owner.name ?: "Unknown", NamedTextColor.WHITE)),
                Component.text("Members: ", NamedTextColor.GRAY).append(Component.text("${ranking.memberCount}/${TeamManager.MAX_TEAM_SIZE}", NamedTextColor.WHITE)),
                Component.text("Team Balance: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(ranking.balance), NamedTextColor.GREEN)),
                Component.text("Team Kills: ", NamedTextColor.GRAY).append(Component.text("%,d".format(ranking.kills), NamedTextColor.RED)),
                Component.text("Status: ", NamedTextColor.GRAY).append(
                    if (ranking.isOpen) Component.text("Open", NamedTextColor.GREEN) else Component.text("Invite Only", NamedTextColor.YELLOW)
                ),
                Component.empty(),
                Component.text("Click to view team.", NamedTextColor.YELLOW)
            )
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return item
    }
}
