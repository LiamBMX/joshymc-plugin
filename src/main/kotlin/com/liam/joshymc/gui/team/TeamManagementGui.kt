package com.liam.joshymc.gui.team

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

/**
 * Members & management panel for the /team GUI (issue #523). Every action
 * dispatches the matching /team subcommand instead of re-implementing role
 * checks or persistence, so behavior always matches the direct command.
 */
object TeamManagementGui {

    fun open(plugin: Joshymc, player: Player) {
        val teamName = plugin.teamManager.getPlayerTeam(player.uniqueId)
        if (teamName == null) {
            TeamMainGui.open(plugin, player)
            return
        }

        val role = plugin.teamManager.getPlayerRole(player.uniqueId)
        val isOwner = role == "owner"
        val canManage = isOwner || role == "admin"

        val gui = CustomGui(Component.text("Team Management", NamedTextColor.GOLD), 45)
        for (slot in 0..44) gui.setItem(slot, TeamGuiUtil.filler())

        val members = plugin.teamManager.getTeamMembers(teamName)
        val memberSlots = 9..18
        for ((slot, member) in memberSlots.zip(members)) {
            val memberPlayer = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
            val roleColor = when (member.role) {
                "owner" -> NamedTextColor.GOLD
                "admin" -> NamedTextColor.YELLOW
                else -> NamedTextColor.GRAY
            }
            val head = ItemStack(Material.PLAYER_HEAD)
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

        if (canManage) {
            gui.setItem(28, TeamGuiUtil.item(
                Material.LIME_DYE, Component.text("Invite Player", NamedTextColor.GREEN),
                listOf(Component.empty(), Component.text("Click to invite an online player.", NamedTextColor.GRAY))
            )) { p, _ -> openInvitePicker(plugin, p) }

            gui.setItem(29, TeamGuiUtil.item(
                Material.BARRIER, Component.text("Kick Member", NamedTextColor.RED),
                listOf(Component.empty(), Component.text("Click to remove a member.", NamedTextColor.GRAY))
            )) { p, _ -> openKickPicker(plugin, p, teamName, role) }
        } else {
            gui.setItem(28, disabledItem("Invite Player"))
            gui.setItem(29, disabledItem("Kick Member"))
        }

        if (isOwner) {
            gui.setItem(30, TeamGuiUtil.item(
                Material.EXPERIENCE_BOTTLE, Component.text("Promote Member", NamedTextColor.YELLOW),
                listOf(Component.empty(), Component.text("Click to promote a member to admin.", NamedTextColor.GRAY))
            )) { p, _ -> openPromotePicker(plugin, p, teamName) }

            gui.setItem(31, TeamGuiUtil.item(
                Material.GLASS_BOTTLE, Component.text("Demote Member", NamedTextColor.GRAY),
                listOf(Component.empty(), Component.text("Click to demote an admin to member.", NamedTextColor.GRAY))
            )) { p, _ -> openDemotePicker(plugin, p, teamName) }

            gui.setItem(32, TeamGuiUtil.item(
                Material.NETHER_STAR, Component.text("Transfer Ownership", NamedTextColor.LIGHT_PURPLE),
                listOf(Component.empty(), Component.text("Click to make another member the owner.", NamedTextColor.GRAY))
            )) { p, _ -> openTransferPicker(plugin, p, teamName) }

            gui.setItem(33, TeamGuiUtil.item(
                Material.TNT, Component.text("Disband Team", NamedTextColor.DARK_RED),
                listOf(Component.empty(), Component.text("Permanently delete this team.", NamedTextColor.GRAY))
            )) { p, _ ->
                TeamConfirmGui.open(
                    plugin, p,
                    Component.text("Disband this team?", NamedTextColor.RED),
                    listOf(Component.text("This cannot be undone.", NamedTextColor.GRAY)),
                    onConfirm = { pl, viewer -> Bukkit.dispatchCommand(viewer, "team disband"); TeamMainGui.open(pl, viewer) },
                    onCancel = { pl, viewer -> open(pl, viewer) }
                )
            }
        } else {
            gui.setItem(30, disabledItem("Promote Member"))
            gui.setItem(31, disabledItem("Demote Member"))
            gui.setItem(32, disabledItem("Transfer Ownership"))
            gui.setItem(33, disabledItem("Disband Team"))
        }

        gui.setItem(40, TeamGuiUtil.item(Material.ARROW, Component.text("Back", NamedTextColor.YELLOW))) { p, _ -> TeamMainGui.open(plugin, p) }

        plugin.guiManager.open(player, gui)
    }

    private fun disabledItem(label: String) = TeamGuiUtil.item(
        Material.GRAY_DYE,
        Component.text(label, NamedTextColor.DARK_GRAY),
        listOf(Component.empty(), Component.text("You do not have permission to use this.", NamedTextColor.RED))
    )

    private fun openInvitePicker(plugin: Joshymc, player: Player) {
        val entries = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId && plugin.teamManager.getPlayerTeam(it.uniqueId) == null }
            .map { TeamPlayerPickerGui.Entry(it, listOf(Component.text("Click to invite.", NamedTextColor.GRAY))) }

        TeamPlayerPickerGui.open(
            plugin, player,
            Component.text("Invite Player", NamedTextColor.GREEN),
            entries,
            Component.text("No Eligible Players Online", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer) },
            onPick = { p, viewer, target ->
                Bukkit.dispatchCommand(viewer, "team invite ${target.name}")
                open(p, viewer)
            }
        )
    }

    private fun openKickPicker(plugin: Joshymc, player: Player, teamName: String, actorRole: String?) {
        val members = plugin.teamManager.getTeamMembers(teamName)
            .filter { UUID.fromString(it.uuid) != player.uniqueId }
            // Admins cannot kick other admins or the owner, mirroring /team kick.
            .filter { actorRole == "owner" || it.role == "member" }
            .mapNotNull { member ->
                val op = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
                if (op.name == null) return@mapNotNull null
                TeamPlayerPickerGui.Entry(op, listOf(
                    Component.text("Role: ${member.role.replaceFirstChar { c -> c.uppercase() }}", NamedTextColor.GRAY),
                    Component.text("Click to kick.", NamedTextColor.RED)
                ))
            }

        TeamPlayerPickerGui.open(
            plugin, player,
            Component.text("Kick Member", NamedTextColor.RED),
            members,
            Component.text("No Kickable Members", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer) },
            onPick = { p, viewer, target ->
                Bukkit.dispatchCommand(viewer, "team kick ${target.name}")
                open(p, viewer)
            }
        )
    }

    private fun openPromotePicker(plugin: Joshymc, player: Player, teamName: String) {
        val members = plugin.teamManager.getTeamMembers(teamName)
            .filter { it.role == "member" }
            .mapNotNull { member ->
                val op = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
                if (op.name == null) return@mapNotNull null
                TeamPlayerPickerGui.Entry(op, listOf(Component.text("Click to promote to admin.", NamedTextColor.GRAY)))
            }

        TeamPlayerPickerGui.open(
            plugin, player,
            Component.text("Promote Member", NamedTextColor.YELLOW),
            members,
            Component.text("No Promotable Members", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer) },
            onPick = { p, viewer, target ->
                Bukkit.dispatchCommand(viewer, "team promote ${target.name}")
                open(p, viewer)
            }
        )
    }

    private fun openDemotePicker(plugin: Joshymc, player: Player, teamName: String) {
        val members = plugin.teamManager.getTeamMembers(teamName)
            .filter { it.role == "admin" }
            .mapNotNull { member ->
                val op = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
                if (op.name == null) return@mapNotNull null
                TeamPlayerPickerGui.Entry(op, listOf(Component.text("Click to demote to member.", NamedTextColor.GRAY)))
            }

        TeamPlayerPickerGui.open(
            plugin, player,
            Component.text("Demote Member", NamedTextColor.GRAY),
            members,
            Component.text("No Demotable Members", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer) },
            onPick = { p, viewer, target ->
                Bukkit.dispatchCommand(viewer, "team demote ${target.name}")
                open(p, viewer)
            }
        )
    }

    private fun openTransferPicker(plugin: Joshymc, player: Player, teamName: String) {
        val members = plugin.teamManager.getTeamMembers(teamName)
            .filter { UUID.fromString(it.uuid) != player.uniqueId }
            .mapNotNull { member ->
                val op = Bukkit.getOfflinePlayer(UUID.fromString(member.uuid))
                if (op.name == null) return@mapNotNull null
                TeamPlayerPickerGui.Entry(op, listOf(Component.text("Click to transfer ownership.", NamedTextColor.GRAY)))
            }

        TeamPlayerPickerGui.open(
            plugin, player,
            Component.text("Transfer Ownership", NamedTextColor.LIGHT_PURPLE),
            members,
            Component.text("No Eligible Members", NamedTextColor.RED),
            onBack = { p, viewer -> open(p, viewer) },
            onPick = { p, viewer, target ->
                TeamConfirmGui.open(
                    p, viewer,
                    Component.text("Transfer ownership to ${target.name}?", NamedTextColor.YELLOW),
                    listOf(Component.text("You will become a member.", NamedTextColor.GRAY)),
                    onConfirm = { pl, v -> Bukkit.dispatchCommand(v, "team transfer ${target.name}"); open(pl, v) },
                    onCancel = { pl, v -> open(pl, v) }
                )
            }
        )
    }
}
