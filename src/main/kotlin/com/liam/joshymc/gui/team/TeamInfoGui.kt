package com.liam.joshymc.gui.team

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
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
 * Opened by clicking an entry in [TeamTopGui] or [TeamListGui] (per issue
 * #477: reuse one team-details implementation instead of building a second
 * one). [backLabel]/[onBack] let each caller send the player back to wherever
 * they came from.
 */
object TeamInfoGui {

    private val dateFormat = SimpleDateFormat("MM/dd/yyyy")

    fun open(
        plugin: Joshymc,
        player: Player,
        teamName: String,
        backLabel: Component = Component.text("Back to Leaderboard", NamedTextColor.YELLOW),
        onBack: (Joshymc, Player) -> Unit = { p, viewer -> TeamTopGui.open(p, viewer) }
    ) {
        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            plugin.commsManager.send(player, Component.text("That team no longer exists.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
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

        gui.setItem(40, TeamGuiUtil.item(Material.ARROW, backLabel)) { p, _ ->
            onBack(plugin, p)
        }

        gui.setItem(42, buildJoinItem(plugin, player, teamName, isOpen)) { p, _ ->
            handleJoinClick(plugin, p, teamName, backLabel, onBack)
        }

        plugin.guiManager.open(player, gui)
    }

    private fun buildJoinItem(plugin: Joshymc, player: Player, teamName: String, isOpen: Boolean) =
        when {
            plugin.teamManager.getPlayerTeam(player.uniqueId) != null ->
                TeamGuiUtil.item(
                    Material.BARRIER,
                    Component.text("You Are Already On A Team", NamedTextColor.RED),
                    listOf(Component.empty(), Component.text("Leave your current team to join another.", NamedTextColor.GRAY))
                )
            isOpen ->
                TeamGuiUtil.item(
                    Material.LIME_DYE,
                    Component.text("Join Team", NamedTextColor.GREEN),
                    listOf(Component.empty(), Component.text("Click to join this team.", NamedTextColor.GRAY))
                )
            plugin.teamManager.getPendingInvites(player.uniqueId).contains(teamName) ->
                TeamGuiUtil.item(
                    Material.LIME_DYE,
                    Component.text("Accept Invite", NamedTextColor.GREEN),
                    listOf(Component.empty(), Component.text("Click to accept your invite to this team.", NamedTextColor.GRAY))
                )
            else ->
                TeamGuiUtil.item(
                    Material.BARRIER,
                    Component.text("Invite Only", NamedTextColor.YELLOW),
                    listOf(Component.empty(), Component.text("You need an invitation from this team to join.", NamedTextColor.GRAY))
                )
        }

    /** Reuses the exact join/accept logic + restrictions from `/team join` and `/team accept`. */
    private fun handleJoinClick(
        plugin: Joshymc,
        player: Player,
        teamName: String,
        backLabel: Component,
        onBack: (Joshymc, Player) -> Unit
    ) {
        if (plugin.teamManager.getPlayerTeam(player.uniqueId) != null) return

        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            plugin.commsManager.send(player, Component.text("That team no longer exists.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            player.closeInventory()
            return
        }

        val joined = if (plugin.teamManager.isTeamOpen(teamName)) {
            plugin.teamManager.joinOpenTeam(player.uniqueId, teamName)
        } else if (plugin.teamManager.getPendingInvites(player.uniqueId).contains(teamName)) {
            plugin.teamManager.acceptInvite(player.uniqueId, teamName)
        } else {
            false
        }

        if (!joined) {
            plugin.commsManager.send(player, Component.text("Could not join that team. It may be full or no longer available.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            // Refresh so the player sees the up-to-date state (e.g. now full).
            open(plugin, player, teamName, backLabel, onBack)
            return
        }

        plugin.commsManager.send(
            player,
            Component.text("You joined team ", NamedTextColor.GRAY)
                .append(Component.text(team.displayName, NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
        player.closeInventory()

        plugin.teamManager.getTeamMembers(teamName).forEach { member ->
            val online = Bukkit.getPlayer(UUID.fromString(member.uuid))
            if (online != null && online != player) {
                plugin.commsManager.send(
                    online,
                    Component.text(player.name, NamedTextColor.GREEN)
                        .append(Component.text(" joined the team.", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        }
    }
}
