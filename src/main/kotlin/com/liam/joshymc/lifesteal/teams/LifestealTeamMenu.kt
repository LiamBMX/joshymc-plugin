package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.suggest
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.text
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import java.util.UUID

/**
 * The Lifesteal team menu (issue #1346), `/team` with no arguments in Lifesteal. Plain chest GUIs
 * through the shared [com.liam.joshymc.gui.GuiManager] (which cancels every click on the menu and
 * blocks shift/number-key moves into it, so nothing can be taken or put in), styled with [GuiStyle].
 * Bedrock players get the same chests through Geyser.
 *
 * Controls are only shown when they apply (leader vs member), but **no button is trusted**: every
 * click calls the same [LifestealTeamManager] action as the command, which checks permission,
 * leadership and the current team state again, and the store checks once more in its
 * transaction. Changing actions close the menu; the result arrives as a chat message.
 */
class LifestealTeamMenu(private val plugin: Joshymc) {

    private val teams get() = plugin.lifestealTeamManager

    fun open(player: Player) {
        val team = teams.teamOf(player.uniqueId)
        val gui = CustomGui(title("Lifesteal Team"), 27)
        val leader = team?.leader == player.uniqueId
        val invites = teams.invitesFor(player.uniqueId)

        gui.setItem(11, if (team == null) {
            GuiStyle.item(Material.GRAY_BANNER, text("No Team", NamedTextColor.GRAY), listOf(
                text("You're not in a Lifesteal team.", NamedTextColor.GRAY),
                text("Create one or accept an invitation.", NamedTextColor.GRAY),
                Component.empty(),
                text("Click to create a team", NamedTextColor.YELLOW),
            ))
        } else {
            GuiStyle.item(Material.WHITE_BANNER, text(team.name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD), teamLore(team, player.uniqueId))
        }) { p, _ ->
            GuiStyle.clickSound(p)
            p.closeInventory()
            if (team == null) promptCreate(p) else p.performCommand("team info")
        }

        gui.setItem(12, GuiStyle.item(Material.PLAYER_HEAD, text("Team Members", NamedTextColor.AQUA), listOf(
            if (team == null) text("Join a team to see its members.", NamedTextColor.GRAY)
            else text("${team.size}/${teams.maxSize} members", NamedTextColor.GRAY),
        ))) { p, _ ->
            val current = teams.teamOf(p.uniqueId) ?: return@setItem GuiStyle.errorSound(p)
            GuiStyle.clickSound(p)
            openMembers(p, current)
        }

        gui.setItem(13, GuiStyle.item(if (invites.isEmpty()) Material.PAPER else Material.WRITABLE_BOOK, text("Team Invitations", NamedTextColor.GREEN), listOf(
            text(if (invites.isEmpty()) "No pending invitations." else "${invites.size} pending invitation(s).", NamedTextColor.GRAY),
        ))) { p, _ ->
            GuiStyle.clickSound(p)
            openInvites(p)
        }

        gui.setItem(14, GuiStyle.item(Material.COMPARATOR, text("Team Management", NamedTextColor.YELLOW), listOf(
            text(when {
                team == null -> "Create a team."
                leader -> "Invite, transfer leadership or disband."
                else -> "Leave the team."
            }, NamedTextColor.GRAY),
        ))) { p, _ ->
            GuiStyle.clickSound(p)
            openManage(p)
        }

        gui.setItem(15, GuiStyle.item(Material.BOOKSHELF, text("Available Teams", NamedTextColor.LIGHT_PURPLE), listOf(
            text("${teams.allTeams().size} team(s) on this server.", NamedTextColor.GRAY),
        ))) { p, _ ->
            GuiStyle.pageSound(p)
            openTeams(p, 0)
        }

        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun teamLore(team: Team, viewer: UUID): List<Component> {
        val online = team.members.count { teams.isOnline(it.uuid) }
        return listOfNotNull(
            text("Leader: ", NamedTextColor.GRAY).append(text(teams.nameOf(team.leader), NamedTextColor.WHITE)),
            text("Members: ", NamedTextColor.GRAY).append(text("${team.size}/${teams.maxSize}", NamedTextColor.WHITE)),
            text("Online: ", NamedTextColor.GRAY).append(text("$online", NamedTextColor.GREEN)),
            text("Friendly fire: ", NamedTextColor.GRAY).append(text(teams.friendlyFireSummary(), NamedTextColor.WHITE)),
            if (team.leader == viewer) text("You lead this team.", NamedTextColor.GOLD) else null,
        )
    }

    // ── Members ─────────────────────────────────────────────

    private fun openMembers(player: Player, team: Team) {
        val gui = CustomGui(title(team.name), 27)
        val viewerLeads = team.leader == player.uniqueId
        val slots = centered(team.size, 9)
        team.members.forEachIndexed { index, member ->
            val isLeader = member.uuid == team.leader
            val online = teams.isOnline(member.uuid)
            val lore = mutableListOf(
                text(if (isLeader) "Leader" else "Member", if (isLeader) NamedTextColor.GOLD else NamedTextColor.GRAY),
                text(if (online) "Online" else "Offline", if (online) NamedTextColor.GREEN else NamedTextColor.RED),
            )
            if (viewerLeads && !isLeader) {
                lore += Component.empty()
                lore += text("Click to manage", NamedTextColor.YELLOW)
            }
            val name = text(teams.nameOf(member.uuid), if (isLeader) NamedTextColor.GOLD else NamedTextColor.WHITE)
            gui.setItem(9 + slots[index], GuiStyle.skull(Bukkit.getOfflinePlayer(member.uuid), name, lore)) { p, _ ->
                if (!viewerLeads || isLeader) return@setItem
                GuiStyle.clickSound(p)
                openMemberActions(p, member.uuid)
            }
        }
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    private fun openMemberActions(player: Player, member: UUID) {
        val name = teams.nameOf(member)
        val gui = CustomGui(title(name), 27)
        gui.setItem(11, GuiStyle.item(Material.GOLDEN_HELMET, text("Make Leader", NamedTextColor.GOLD), listOf(
            text("Hand leadership to $name.", NamedTextColor.GRAY),
            text("You become a regular member.", NamedTextColor.GRAY),
        ))) { p, _ ->
            confirm(p, "Make $name leader?", text("$name becomes the team leader.", NamedTextColor.GRAY)) { teams.transfer(it, name, confirmed = true) }
        }
        gui.setItem(15, GuiStyle.item(Material.RED_DYE, text("Remove from Team", NamedTextColor.RED), listOf(
            text("$name loses their membership.", NamedTextColor.GRAY),
        ))) { p, _ ->
            confirm(p, "Remove $name?", text("$name is removed from the team.", NamedTextColor.GRAY)) { teams.kick(it, name) }
        }
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ ->
            GuiStyle.clickSound(p)
            teams.teamOf(p.uniqueId)?.let { openMembers(p, it) } ?: open(p)
        }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Invitations ─────────────────────────────────────────

    private fun openInvites(player: Player) {
        val gui = CustomGui(title("Team Invitations"), 36)
        val now = System.currentTimeMillis()
        val incoming = teams.invitesFor(player.uniqueId, now).take(7)
        if (incoming.isEmpty()) {
            gui.setItem(13, GuiStyle.item(Material.PAPER, text("No Invitations", NamedTextColor.GRAY), listOf(
                text("When a team leader invites you,", NamedTextColor.GRAY),
                text("it shows up here.", NamedTextColor.GRAY),
            )))
        }
        val inSlots = centered(incoming.size, 7)
        incoming.forEachIndexed { index, invite ->
            val team = teams.team(invite.teamId) ?: return@forEachIndexed
            val seconds = ((invite.expiresAt - now) / 1000).coerceAtLeast(1)
            gui.setItem(10 + inSlots[index], GuiStyle.item(Material.WRITABLE_BOOK, text(team.name, NamedTextColor.GOLD), listOf(
                text("From: ", NamedTextColor.GRAY).append(text(teams.nameOf(invite.inviter), NamedTextColor.WHITE)),
                text("Members: ", NamedTextColor.GRAY).append(text("${team.size}/${teams.maxSize}", NamedTextColor.WHITE)),
                text("Expires in ${seconds}s", NamedTextColor.GRAY),
                Component.empty(),
                text("Left-click to accept", NamedTextColor.GREEN),
                text("Right-click to decline", NamedTextColor.RED),
            ))) { p, event ->
                p.closeInventory()
                if (event.isRightClick) teams.deny(p, team.name) else teams.accept(p, team.name)
            }
        }
        // A leader also sees who they invited (display only).
        val own = teams.teamOf(player.uniqueId)
        if (own != null && own.leader == player.uniqueId) {
            val outgoing = teams.invitesFrom(own.id, now).take(7)
            val outSlots = centered(outgoing.size, 7)
            outgoing.forEachIndexed { index, invite ->
                val seconds = ((invite.expiresAt - now) / 1000).coerceAtLeast(1)
                gui.setItem(19 + outSlots[index], GuiStyle.item(Material.PAPER, text(teams.nameOf(invite.target), NamedTextColor.WHITE), listOf(
                    text("Invited by you", NamedTextColor.GRAY),
                    text("Expires in ${seconds}s", NamedTextColor.GRAY),
                )))
            }
        }
        gui.setItem(GuiStyle.backSlot(36), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(36), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Management ──────────────────────────────────────────

    private fun openManage(player: Player) {
        val team = teams.teamOf(player.uniqueId)
        val gui = CustomGui(title("Team Management"), 27)
        when {
            team == null -> gui.setItem(13, GuiStyle.item(Material.WHITE_BANNER, text("Create a Team", NamedTextColor.GREEN), listOf(
                text("You become its leader.", NamedTextColor.GRAY),
                text("Click, then type the name.", NamedTextColor.YELLOW),
            ))) { p, _ -> p.closeInventory(); promptCreate(p) }

            team.leader == player.uniqueId -> {
                gui.setItem(11, GuiStyle.item(Material.WRITABLE_BOOK, text("Invite a Player", NamedTextColor.GREEN), listOf(
                    text("${team.size}/${teams.maxSize} members", NamedTextColor.GRAY),
                    text("Click, then type their name.", NamedTextColor.YELLOW),
                ))) { p, _ ->
                    p.closeInventory()
                    teams.send(p, text("Invite a player: ", NamedTextColor.GRAY).append(suggest("[/team invite <player>]", "/team invite ")))
                }
                gui.setItem(13, GuiStyle.item(Material.GOLDEN_HELMET, text("Transfer Leadership", NamedTextColor.GOLD), listOf(
                    text("Pick a member to lead the team.", NamedTextColor.GRAY),
                ))) { p, _ ->
                    val current = teams.teamOf(p.uniqueId) ?: return@setItem open(p)
                    GuiStyle.clickSound(p)
                    openMembers(p, current)
                }
                gui.setItem(15, GuiStyle.item(Material.TNT, text("Disband Team", NamedTextColor.RED), listOf(
                    text("Every member loses their membership.", NamedTextColor.GRAY),
                    text("Hearts and items are not affected.", NamedTextColor.GRAY),
                ))) { p, _ ->
                    confirm(p, "Disband ${team.name}?", text("This can't be undone.", NamedTextColor.RED)) { teams.disband(it, confirmed = true) }
                }
            }

            else -> gui.setItem(13, GuiStyle.item(Material.OAK_DOOR, text("Leave Team", NamedTextColor.RED), listOf(
                text("Leave ${team.name}.", NamedTextColor.GRAY),
            ))) { p, _ ->
                confirm(p, "Leave ${team.name}?", text("You'll need a new invitation to rejoin.", NamedTextColor.GRAY)) { teams.leave(it) }
            }
        }
        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Available teams ─────────────────────────────────────

    private fun openTeams(player: Player, requestedPage: Int) {
        val all = teams.allTeams()
        val pages = maxOf(1, (all.size + PER_PAGE - 1) / PER_PAGE)
        val page = requestedPage.coerceIn(0, pages - 1)
        val gui = CustomGui(title(if (pages > 1) "Teams ${page + 1}/$pages" else "Teams"), 54)
        if (all.isEmpty()) {
            gui.setItem(22, GuiStyle.item(Material.GRAY_BANNER, text("No Teams Yet", NamedTextColor.GRAY), listOf(
                text("Be the first: /team create <name>", NamedTextColor.GRAY),
            )))
        }
        all.drop(page * PER_PAGE).take(PER_PAGE).forEachIndexed { index, team ->
            val slot = 10 + (index / 7) * 9 + index % 7
            gui.setItem(slot, GuiStyle.item(Material.WHITE_BANNER, text(team.name, NamedTextColor.GOLD), teamLore(team, player.uniqueId) +
                listOf(Component.empty(), text("Click for details", NamedTextColor.YELLOW)))) { p, _ ->
                p.closeInventory()
                p.performCommand("team info ${team.name}")
            }
        }
        if (page > 0) gui.setItem(GuiStyle.prevSlot(54), GuiStyle.previousPage(page + 1, pages)) { p, _ -> GuiStyle.pageSound(p); openTeams(p, page - 1) }
        if (page < pages - 1) gui.setItem(GuiStyle.nextSlot(54), GuiStyle.nextPage(page + 1, pages)) { p, _ -> GuiStyle.pageSound(p); openTeams(p, page + 1) }
        gui.setItem(GuiStyle.backSlot(54), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); open(p) }
        gui.setItem(GuiStyle.closeSlot(54), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Helpers ─────────────────────────────────────────────

    /** A yes/no screen; [action] runs the real (re-checked) manager action. */
    private fun confirm(player: Player, question: String, detail: Component, action: (Player) -> Unit) {
        GuiStyle.clickSound(player)
        val gui = CustomGui(title(question), 27)
        gui.setItem(11, GuiStyle.item(Material.LIME_STAINED_GLASS_PANE, text("Confirm", NamedTextColor.GREEN), listOf(detail))) { p, _ ->
            p.closeInventory()
            action(p)
        }
        gui.setItem(13, GuiStyle.item(Material.PAPER, text(question, NamedTextColor.YELLOW), listOf(detail)))
        gui.setItem(15, GuiStyle.item(Material.RED_STAINED_GLASS_PANE, text("Cancel", NamedTextColor.RED))) { p, _ ->
            GuiStyle.clickSound(p)
            open(p)
        }
        plugin.guiManager.open(player, gui)
    }

    private fun promptCreate(player: Player) {
        teams.send(player, text("Create a team: ", NamedTextColor.GRAY).append(suggest("[/team create <name>]", "/team create ")))
    }

    /** Chest titles stay short (CLAUDE.md: titles over ~28 characters overflow). */
    private fun title(raw: String): Component =
        text(if (raw.length > 28) raw.take(27) + "…" else raw, NamedTextColor.DARK_GRAY)

    companion object {
        private const val PER_PAGE = 28

        /** Columns (0-based within [width]) for [count] items centered in one row. */
        fun centered(count: Int, width: Int): List<Int> {
            if (count <= 0) return emptyList()
            val n = minOf(count, width)
            val start = (width - n) / 2
            return List(n) { start + it }
        }
    }
}
