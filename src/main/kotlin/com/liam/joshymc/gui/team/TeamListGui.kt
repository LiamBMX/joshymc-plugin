package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.TeamManager
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
 * "/team list" team browser GUI (issue #476). Lets players page through every
 * existing team and inspect/join one via [TeamInfoGui], rather than dumping a
 * plain-text list into chat.
 */
object TeamListGui {

    private const val PAGE_SIZE = 36
    private const val FIRST_CONTENT_SLOT = 9

    fun open(plugin: Joshymc, player: Player, page: Int = 0) {
        plugin.guiManager.open(player, build(plugin, page))
    }

    private fun build(plugin: Joshymc, page: Int): CustomGui {
        val gui = CustomGui(Component.text("Team Browser", NamedTextColor.GOLD), 54)
        for (slot in 0..8) gui.setItem(slot, TeamGuiUtil.filler())
        for (slot in 45..53) gui.setItem(slot, TeamGuiUtil.filler())
        gui.setItem(4, TeamGuiUtil.item(Material.PAPER, Component.text("Browse Teams", NamedTextColor.YELLOW)))

        // Same ranking query /team top uses (kills desc, then balance desc,
        // then name asc) so ordering stays predictable and reuses one query.
        val teams = plugin.teamManager.getTeamRankings(TeamManager.TeamSort.KILLS)

        if (teams.isEmpty()) {
            gui.setItem(
                22,
                TeamGuiUtil.item(
                    Material.BARRIER,
                    Component.text("No Teams Available", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("There are currently no teams to display.", NamedTextColor.GRAY))
                )
            )
            gui.setItem(49, TeamGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }
            return gui
        }

        val totalPages = maxOf(1, ceil(teams.size / PAGE_SIZE.toDouble()).toInt())
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageTeams = teams.drop(clampedPage * PAGE_SIZE).take(PAGE_SIZE)

        for ((index, team) in pageTeams.withIndex()) {
            gui.setItem(FIRST_CONTENT_SLOT + index, buildEntryIcon(plugin, team)) { p, _ ->
                TeamInfoGui.open(
                    plugin, p, team.name,
                    Component.text("Back to Team Browser", NamedTextColor.YELLOW)
                ) { pl, viewer -> open(pl, viewer, clampedPage) }
            }
        }

        if (clampedPage > 0) {
            gui.setItem(45, TeamGuiUtil.item(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage - 1)
            }
        }

        gui.setItem(47, TeamGuiUtil.item(Material.PAPER, Component.text("Page ${clampedPage + 1}/$totalPages", NamedTextColor.WHITE)))
        gui.setItem(49, TeamGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        if (clampedPage < totalPages - 1) {
            gui.setItem(53, TeamGuiUtil.item(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ ->
                open(plugin, p, clampedPage + 1)
            }
        }

        return gui
    }

    private fun buildEntryIcon(plugin: Joshymc, team: TeamManager.TeamRanking): ItemStack {
        val owner = Bukkit.getOfflinePlayer(UUID.fromString(team.ownerUuid))
        val nameColor = if (team.isOpen) NamedTextColor.GREEN else NamedTextColor.YELLOW

        val item = ItemStack(Material.PLAYER_HEAD)
        item.editMeta { meta ->
            if (meta is SkullMeta) meta.owningPlayer = owner
            meta.displayName(Component.text(team.displayName, nameColor).decoration(TextDecoration.ITALIC, false))
            val lore = listOf(
                Component.empty(),
                Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(owner.name ?: "Unknown", NamedTextColor.WHITE)),
                Component.text("Members: ", NamedTextColor.GRAY).append(Component.text("${team.memberCount}/${TeamManager.MAX_TEAM_SIZE}", NamedTextColor.WHITE)),
                Component.text("Team Balance: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(team.balance), NamedTextColor.GREEN)),
                Component.text("Team Kills: ", NamedTextColor.GRAY).append(Component.text("%,d".format(team.kills), NamedTextColor.RED)),
                Component.text("Status: ", NamedTextColor.GRAY).append(
                    if (team.isOpen) Component.text("Open", NamedTextColor.GREEN) else Component.text("Invite Only", NamedTextColor.YELLOW)
                ),
                Component.empty(),
                Component.text("Click to view this team.", NamedTextColor.YELLOW)
            )
            meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        }
        return item
    }
}
