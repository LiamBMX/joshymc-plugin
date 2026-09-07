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

/**
 * Main /team GUI (issue #523). Bare `/team` with no subcommand opens this.
 * Every button routes into the existing /team subcommand logic — either via
 * [Bukkit.dispatchCommand] or the existing [TeamListGui]/[TeamTopGui]/[TeamInfoGui]
 * screens — instead of duplicating any team logic here.
 */
object TeamMainGui {

    fun open(plugin: Joshymc, player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            openNoTeam(plugin, player)
        } else {
            openDashboard(plugin, player, teamName)
        }
    }

    private fun openDashboard(plugin: Joshymc, player: Player, teamName: String) {
        val team = plugin.teamManager.getTeam(teamName)
        if (team == null) {
            openNoTeam(plugin, player)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        val isOwner = role == "owner"
        val canManageBank = isOwner || role == "admin"

        val gui = CustomGui(Component.text(team.displayName, NamedTextColor.GOLD), 45)
        for (slot in 0..44) gui.setItem(slot, TeamGuiUtil.filler())

        val owner = Bukkit.getOfflinePlayer(UUID.fromString(team.ownerUuid))
        val members = plugin.teamManager.getTeamMembers(teamName)
        val balance = plugin.teamManager.getTeamBalance(teamName)
        val isOpen = plugin.teamManager.isTeamOpen(teamName)
        val friendlyFire = plugin.teamManager.isTeamPvpEnabled(teamName)

        gui.setItem(4, TeamGuiUtil.item(
            Material.WHITE_BANNER,
            Component.text(team.displayName, NamedTextColor.GOLD),
            listOf(
                Component.empty(),
                Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(owner.name ?: "Unknown", NamedTextColor.WHITE)),
                Component.text("Members: ", NamedTextColor.GRAY).append(Component.text("${members.size}/${TeamManager.MAX_TEAM_SIZE}", NamedTextColor.WHITE)),
                Component.text("Balance: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GREEN)),
                Component.text("Access: ", NamedTextColor.GRAY).append(
                    if (isOpen) Component.text("Open", NamedTextColor.GREEN) else Component.text("Invite Only", NamedTextColor.YELLOW)
                ),
                Component.text("Friendly Fire: ", NamedTextColor.GRAY).append(
                    if (friendlyFire) Component.text("Enabled", NamedTextColor.GREEN) else Component.text("Disabled", NamedTextColor.RED)
                ),
                Component.text("Your Role: ", NamedTextColor.GRAY).append(Component.text((role ?: "member").replaceFirstChar { it.uppercase() }, NamedTextColor.WHITE))
            )
        ))

        gui.setItem(10, TeamGuiUtil.item(
            Material.PAPER, Component.text("Team Info", NamedTextColor.YELLOW),
            listOf(Component.empty(), Component.text("View full team info & roster.", NamedTextColor.GRAY))
        )) { p, _ ->
            TeamInfoGui.open(plugin, p, teamName, Component.text("Back to Team Panel", NamedTextColor.YELLOW)) { pl, viewer -> open(pl, viewer) }
        }

        gui.setItem(11, TeamGuiUtil.item(
            Material.PLAYER_HEAD, Component.text("Members & Management", NamedTextColor.AQUA),
            listOf(Component.empty(), Component.text("Invite, kick, promote, and more.", NamedTextColor.GRAY))
        )) { p, _ -> TeamManagementGui.open(plugin, p) }

        gui.setItem(12, TeamGuiUtil.item(
            Material.GOLD_INGOT, Component.text("Team Bank", NamedTextColor.GREEN),
            listOf(Component.empty(), Component.text("Deposit or withdraw team funds.", NamedTextColor.GRAY))
        )) { p, _ -> TeamBankGui.open(plugin, p) }

        gui.setItem(13, TeamGuiUtil.item(
            Material.RED_BED, Component.text("Team Home", NamedTextColor.LIGHT_PURPLE),
            listOf(Component.empty(), Component.text("Teleport to or set the team home.", NamedTextColor.GRAY))
        )) { p, _ -> TeamHomeGui.open(plugin, p) }

        gui.setItem(14, TeamGuiUtil.item(
            Material.COMPARATOR, Component.text("Team Settings", NamedTextColor.YELLOW),
            listOf(Component.empty(), Component.text("Friendly fire, access, rename, chat.", NamedTextColor.GRAY))
        )) { p, _ -> TeamSettingsGui.open(plugin, p) }

        if (canManageBank) {
            gui.setItem(15, TeamGuiUtil.item(
                Material.ENDER_CHEST, Component.text("Team Ender Chest", NamedTextColor.DARK_PURPLE),
                listOf(Component.empty(), Component.text("Click to open the shared team chest.", NamedTextColor.GRAY))
            )) { p, _ -> Bukkit.dispatchCommand(p, "team echest") }
        } else {
            gui.setItem(15, TeamGuiUtil.item(
                Material.GRAY_DYE, Component.text("Team Ender Chest", NamedTextColor.DARK_GRAY),
                listOf(Component.empty(), Component.text("You do not have permission to use this.", NamedTextColor.RED))
            ))
        }

        gui.setItem(16, TeamGuiUtil.item(Material.COMPASS, Component.text("Browse Teams", NamedTextColor.WHITE))) { p, _ ->
            TeamListGui.open(plugin, p)
        }

        gui.setItem(20, TeamGuiUtil.item(Material.NETHER_STAR, Component.text("Team Leaderboard", NamedTextColor.GOLD))) { p, _ ->
            TeamTopGui.open(plugin, p)
        }

        if (isOwner) {
            gui.setItem(31, TeamGuiUtil.item(
                Material.GRAY_DYE, Component.text("Leave Team", NamedTextColor.DARK_GRAY),
                listOf(
                    Component.empty(),
                    Component.text("The owner cannot leave.", NamedTextColor.RED),
                    Component.text("Transfer ownership or disband instead.", NamedTextColor.GRAY)
                )
            ))
        } else {
            gui.setItem(31, TeamGuiUtil.item(
                Material.BARRIER, Component.text("Leave Team", NamedTextColor.RED),
                listOf(Component.empty(), Component.text("Click to leave your team.", NamedTextColor.GRAY))
            )) { p, _ ->
                TeamConfirmGui.open(
                    plugin, p,
                    Component.text("Leave this team?", NamedTextColor.RED),
                    onConfirm = { pl, viewer -> Bukkit.dispatchCommand(viewer, "team leave"); open(pl, viewer) },
                    onCancel = { pl, viewer -> open(pl, viewer) }
                )
            }
        }

        gui.setItem(40, TeamGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    private fun openNoTeam(plugin: Joshymc, player: Player) {
        val gui = CustomGui(Component.text("Team Panel", NamedTextColor.GOLD), 27)
        for (slot in 0..26) gui.setItem(slot, TeamGuiUtil.filler())

        gui.setItem(4, TeamGuiUtil.item(
            Material.WHITE_BANNER,
            Component.text("You Are Not On A Team", NamedTextColor.RED),
            listOf(Component.empty(), Component.text("Create or join a team to get started.", NamedTextColor.GRAY))
        ))

        gui.setItem(10, TeamGuiUtil.item(
            Material.LIME_DYE, Component.text("Create Team", NamedTextColor.GREEN),
            listOf(Component.empty(), Component.text("Click to create a new team.", NamedTextColor.GRAY))
        )) { p, _ ->
            TeamGuiInput.prompt(
                plugin, p,
                Component.text("Type your new team's name in chat (2-16 letters/numbers/underscores).", NamedTextColor.YELLOW),
                validate = { input ->
                    if (!input.matches(Regex("^[a-zA-Z0-9_]{2,16}$")))
                        Component.text("Team name must be 2-16 characters (a-z, A-Z, 0-9, _), no spaces.", NamedTextColor.RED)
                    else null
                },
                command = { input -> "team create $input" },
                andThen = { pl, viewer -> open(pl, viewer) }
            )
        }

        val invites = plugin.teamManager.getPendingInvites(player.uniqueId)
        gui.setItem(12, TeamGuiUtil.item(
            Material.WRITABLE_BOOK,
            Component.text("Pending Invites", NamedTextColor.YELLOW),
            if (invites.isEmpty())
                listOf(Component.empty(), Component.text("You have no pending invites.", NamedTextColor.GRAY))
            else
                listOf(
                    Component.empty(),
                    Component.text("You have ${invites.size} pending invite(s).", NamedTextColor.GRAY),
                    Component.text("Click to view.", NamedTextColor.GRAY)
                )
        )) { p, _ -> if (invites.isNotEmpty()) openInvites(plugin, p) }

        gui.setItem(14, TeamGuiUtil.item(
            Material.COMPASS, Component.text("Browse Teams", NamedTextColor.WHITE),
            listOf(Component.empty(), Component.text("Browse & join open teams.", NamedTextColor.GRAY))
        )) { p, _ -> TeamListGui.open(plugin, p) }

        gui.setItem(16, TeamGuiUtil.item(Material.NETHER_STAR, Component.text("Team Leaderboard", NamedTextColor.GOLD))) { p, _ ->
            TeamTopGui.open(plugin, p)
        }

        gui.setItem(22, TeamGuiUtil.item(Material.BARRIER, Component.text("Close", NamedTextColor.RED))) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    private fun openInvites(plugin: Joshymc, player: Player) {
        val invites = plugin.teamManager.getPendingInvites(player.uniqueId)
        val gui = CustomGui(Component.text("Pending Invites", NamedTextColor.YELLOW), 27)
        for (slot in 0..26) gui.setItem(slot, TeamGuiUtil.filler())

        if (invites.isEmpty()) {
            gui.setItem(13, TeamGuiUtil.item(Material.BARRIER, Component.text("No Pending Invites", NamedTextColor.RED)))
        } else {
            for ((index, invitedTeam) in invites.withIndex().take(9)) {
                val team = plugin.teamManager.getTeam(invitedTeam) ?: continue
                val owner = Bukkit.getOfflinePlayer(UUID.fromString(team.ownerUuid))
                val head = ItemStack(Material.PLAYER_HEAD)
                head.editMeta { meta ->
                    (meta as? SkullMeta)?.owningPlayer = owner
                    meta.displayName(Component.text(team.displayName, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                    meta.lore(listOf(
                        Component.text("Owner: ", NamedTextColor.GRAY).append(Component.text(owner.name ?: "Unknown", NamedTextColor.WHITE)),
                        Component.text("Click to view & accept.", NamedTextColor.YELLOW)
                    ).map { it.decoration(TextDecoration.ITALIC, false) })
                }
                gui.setItem(9 + index, head) { p, _ ->
                    TeamInfoGui.open(plugin, p, invitedTeam, Component.text("Back to Invites", NamedTextColor.YELLOW)) { pl, viewer -> openInvites(pl, viewer) }
                }
            }
        }

        gui.setItem(22, TeamGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> open(plugin, p) }

        plugin.guiManager.open(player, gui)
    }
}
