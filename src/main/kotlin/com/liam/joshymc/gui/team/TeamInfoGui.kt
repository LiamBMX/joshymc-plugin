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
import org.bukkit.inventory.meta.SkullMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

/**
 * Single "Team Information" GUI — the one place team details are rendered.
 * Opened by clicking an entry in [TeamTopGui] (per issue #477: reuse one
 * team-details implementation instead of building a second one).
 */
object TeamInfoGui {

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy")

    fun open(plugin: Joshymc, player: Player, teamName: String) {
        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            plugin.commsManager.send(player, Component.text("That team no longer exists.", NamedTextColor.RED), com.liam.joshymc.manager.CommunicationsManager.Category.DEFAULT)
            return
        }

        val gui = CustomGui(Component.text(team.displayName, NamedTextColor.GOLD), 45)
        for (slot in 0..44) gui.setItem(slot, TeamGuiUtil.filler())

        val members = plugin.teamManager.getTeamMembers(teamName)
        val owner = Bukkit.getOfflinePlayer(UUID.fromString(team.ownerUuid))
        val balance = plugin.teamManager.getTeamBalance(teamName)
        val kills = plugin.teamManager.getTeamKills(teamName)
        val isOpen = plugin.teamManager.isTeamOpen(teamName)

        val infoLore = mutableListOf<Component>(
            Component.empty(),
            Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(owner.name ?: "Unknown", NamedTextColor.WHITE)),
            Component.text("Members: ", NamedTextColor.GRAY).append(Component.text("${members.size}/${TeamManager.MAX_TEAM_SIZE}", NamedTextColor.WHITE)),
            Component.text("Team Balance: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GREEN)),
            Component.text("Team Kills: ", NamedTextColor.GRAY).append(Component.text("%,d".format(kills), NamedTextColor.RED)),
            Component.text("Status: ", NamedTextColor.GRAY).append(
                if (isOpen) Component.text("Open", NamedTextColor.GREEN) else Component.text("Invite Only", NamedTextColor.YELLOW)
            ),
            Component.text("Created: ", NamedTextColor.GRAY).append(Component.text(dateFormat.format(Date(team.createdAt)), NamedTextColor.WHITE))
        )
        gui.setItem(4, TeamGuiUtil.item(Material.WHITE_BANNER, Component.text(team.displayName, NamedTextColor.GOLD), infoLore))

        val memberSlots = 9..18
        for ((slot, member) in memberSlots.zip(members)) {
            val memberPlayer = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
            val roleColor = when (member.role) {
                "owner" -> NamedTextColor.GOLD
                "admin" -> NamedTextColor.YELLOW
                else -> NamedTextColor.GRAY
            }
            val head = org.bukkit.inventory.ItemStack(Material.PLAYER_HEAD)
            head.editMeta { meta ->
                (meta as? SkullMeta)?.owningPlayer = memberPlayer
                meta.displayName(Component.text(memberPlayer.name ?: "Unknown", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
                meta.lore(listOf(
                    Component.text("Role: ", NamedTextColor.GRAY)
                        .append(Component.text(member.role.replaceFirstChar { it.uppercase() }, roleColor))
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }
            gui.setItem(slot, head)
        }

        gui.setItem(40, TeamGuiUtil.item(Material.ARROW, Component.text("Back to Leaderboard", NamedTextColor.YELLOW))) { p, _ ->
            TeamTopGui.open(plugin, p)
        }

        plugin.guiManager.open(player, gui)
    }
}
