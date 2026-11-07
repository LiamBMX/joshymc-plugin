package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.text
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * Alliance and rivalry screens of the Lifesteal team menu (issue #1405), opened from
 * [LifestealTeamMenu] (main menu slot 15, "Available Teams" profiles) and `/team ally`. Same
 * conventions as the rest of that menu: plain chests through the shared GuiManager (clicks never
 * move items), [GuiStyle] items and bottom-row Back/Close, one centered row of entries, the shared
 * confirm screen for destructive actions.
 *
 * **No button is trusted.** Leader-only buttons are only drawn for the current leader, but every
 * click calls the same [LifestealTeamRelations] action as the commands, which checks the current
 * leadership and state again, and [TeamRelationStore] checks once more in its transaction. Teams
 * are carried by id and re-read when clicked, so a menu opened before a leadership change, an
 * expiry or a disband can't act on outdated data.
 */
class TeamRelationsMenu(private val plugin: Joshymc) {

    private val teams get() = plugin.lifestealTeamManager
    private val relations get() = teams.relations
    private val base get() = LifestealTeamMenu(plugin)

    // ── Alliances & Rivalries ───────────────────────────────

    fun openRelations(player: Player) {
        val team = teams.teamOf(player.uniqueId)
        if (team == null) {
            teams.send(player, text("Join or create a team to have alliances and rivalries.", NamedTextColor.GRAY))
            return base.open(player)
        }
        val leads = team.leader == player.uniqueId
        val now = System.currentTimeMillis()
        val gui = CustomGui(base.title("Alliances & Rivalries"), 27)
        val ally = relations.allyOf(team.id)
        val cooldown = relations.cooldownRemaining(team.id, now)
        val incoming = relations.incoming(team.id, now)
        val outgoing = relations.outgoing(team.id, now)
        val rival = relations.declaredRival(team.id)
        val against = relations.rivalsAgainst(team.id)
        val readOnly = text("Only your team leader can change this.", NamedTextColor.DARK_GRAY)

        // Six entries centered in the middle row, the middle slot left empty (SettingsLayout's rule).
        gui.setItem(10, GuiStyle.item(if (ally != null) Material.LIME_BANNER else Material.GRAY_BANNER, text("Alliance", NamedTextColor.GREEN).decorate(TextDecoration.BOLD), buildList {
            if (ally != null) {
                add(text("Allied with: ", NamedTextColor.GRAY).append(text(ally.name, NamedTextColor.GREEN)))
                relations.allianceSince(team.id)?.let { add(text("For ${relations.formatRemaining(now - it)}", NamedTextColor.GRAY)) }
                add(text("Friendly-fire protection and /ac chat.", NamedTextColor.GRAY))
                add(text("No shared claims, chests or management.", NamedTextColor.GRAY))
                add(Component.empty())
                add(if (leads) text("Click to end the alliance", NamedTextColor.RED) else readOnly)
            } else {
                add(text("No alliance.", NamedTextColor.WHITE))
                cooldown?.let { add(text("Cooldown: ${relations.formatRemaining(it)}", NamedTextColor.RED)) }
                add(Component.empty())
                add(if (leads) text("Click to pick a team to ask", NamedTextColor.YELLOW) else readOnly)
            }
        })) { p, _ ->
            GuiStyle.clickSound(p)
            val current = teams.teamOf(p.uniqueId) ?: return@setItem base.open(p)
            if (current.leader != p.uniqueId) return@setItem GuiStyle.errorSound(p)
            val currentAlly = relations.allyOf(current.id)
            if (currentAlly != null) {
                base.confirm(p, "End alliance?", text("Both teams wait before a new alliance.", NamedTextColor.RED)) {
                    relations.dissolveAlliance(it, confirmed = true)
                }
            } else {
                base.openTeams(p, 0)
            }
        }

        gui.setItem(11, GuiStyle.item(if (incoming.isEmpty()) Material.PAPER else Material.WRITABLE_BOOK, text("Incoming Requests", NamedTextColor.AQUA), listOf(
            text(if (incoming.isEmpty()) "No pending requests." else "${incoming.size} pending request(s).", NamedTextColor.GRAY),
            Component.empty(),
            text("Click to view", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openRequests(p) }

        gui.setItem(12, GuiStyle.item(Material.MAP, text("Outgoing Requests", NamedTextColor.AQUA), listOf(
            text(if (outgoing.isEmpty()) "No pending requests." else "${outgoing.size} pending request(s).", NamedTextColor.GRAY),
            Component.empty(),
            text("Click to view", NamedTextColor.YELLOW),
        ))) { p, _ -> GuiStyle.clickSound(p); openRequests(p) }

        gui.setItem(14, GuiStyle.item(if (rival != null) Material.RED_BANNER else Material.GRAY_BANNER, text("Declared Rival", NamedTextColor.RED).decorate(TextDecoration.BOLD), buildList {
            if (rival != null) {
                add(text("Your team's rival: ", NamedTextColor.GRAY).append(text(rival.name, NamedTextColor.RED)))
                add(text("A label only: no rewards or PvP changes.", NamedTextColor.GRAY))
                add(Component.empty())
                add(if (leads) text("Click to withdraw the rivalry", NamedTextColor.YELLOW) else readOnly)
            } else {
                add(text("No rival declared.", NamedTextColor.WHITE))
                add(Component.empty())
                add(if (leads) text("Click to pick a team", NamedTextColor.YELLOW) else readOnly)
            }
        })) { p, _ ->
            GuiStyle.clickSound(p)
            val current = teams.teamOf(p.uniqueId) ?: return@setItem base.open(p)
            if (current.leader != p.uniqueId) return@setItem GuiStyle.errorSound(p)
            val currentRival = relations.declaredRival(current.id)
            if (currentRival != null) {
                base.confirm(p, "Withdraw rivalry?", text("${currentRival.name} is told.", NamedTextColor.GRAY)) { relations.withdrawRival(it) }
            } else {
                base.openTeams(p, 0)
            }
        }

        gui.setItem(15, GuiStyle.item(Material.IRON_SWORD, text("Rivals Against You", NamedTextColor.RED), buildList {
            if (against.isEmpty()) add(text("No team declared you a rival.", NamedTextColor.GRAY))
            else against.take(8).forEach { add(text(it.name, NamedTextColor.RED)) }
            if (against.size > 8) add(text("and ${against.size - 8} more", NamedTextColor.GRAY))
            add(text("Declared by them, not by your team.", NamedTextColor.DARK_GRAY))
        }))

        gui.setItem(16, GuiStyle.item(Material.CLOCK, text("Alliance Cooldown", NamedTextColor.YELLOW), listOf(
            if (cooldown != null) text("Your team can ally again in ${relations.formatRemaining(cooldown)}.", NamedTextColor.RED)
            else text("No cooldown: your team can form an alliance.", NamedTextColor.GREEN),
            text("Ending an alliance starts it for both teams.", NamedTextColor.GRAY),
        )))

        gui.setItem(GuiStyle.backSlot(27), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); base.open(p) }
        gui.setItem(GuiStyle.closeSlot(27), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Requests ────────────────────────────────────────────

    fun openRequests(player: Player) {
        val team = teams.teamOf(player.uniqueId) ?: return base.open(player)
        val leads = team.leader == player.uniqueId
        val now = System.currentTimeMillis()
        val gui = CustomGui(base.title("Alliance Requests"), 36)
        val incoming = relations.incoming(team.id, now).take(7)
        val outgoing = relations.outgoing(team.id, now).take(7)
        if (incoming.isEmpty() && outgoing.isEmpty()) {
            gui.setItem(13, GuiStyle.item(Material.PAPER, text("No Requests", NamedTextColor.GRAY), listOf(
                text("Alliance requests to and from", NamedTextColor.GRAY),
                text("your team show up here.", NamedTextColor.GRAY),
            )))
        }
        val inSlots = LifestealTeamMenu.centered(incoming.size, 7)
        incoming.forEachIndexed { index, request ->
            val source = teams.team(request.source) ?: return@forEachIndexed
            val sourceId = source.id
            gui.setItem(10 + inSlots[index], GuiStyle.item(Material.WRITABLE_BOOK, text(source.name, NamedTextColor.GOLD), buildList {
                add(text("Wants to ally with your team", NamedTextColor.GRAY))
                add(text("Leader: ", NamedTextColor.GRAY).append(text(teams.nameOf(source.leader), NamedTextColor.WHITE)))
                add(text("Expires in ${relations.formatRemaining(request.expiresAt - now)}", NamedTextColor.GRAY))
                add(Component.empty())
                if (leads) {
                    add(text("Left-click to accept", NamedTextColor.GREEN))
                    add(text("Right-click to decline", NamedTextColor.RED))
                } else add(text("Only your team leader can answer.", NamedTextColor.DARK_GRAY))
            })) { p, event ->
                // Re-read by id: a team that was disbanded meanwhile is refused, never guessed.
                val name = teams.team(sourceId)?.name ?: return@setItem run { GuiStyle.errorSound(p); openRequests(p) }
                p.closeInventory()
                if (event.isRightClick) relations.declineAlliance(p, name) else relations.acceptAlliance(p, name)
            }
        }
        val outSlots = LifestealTeamMenu.centered(outgoing.size, 7)
        outgoing.forEachIndexed { index, request ->
            val target = teams.team(request.target) ?: return@forEachIndexed
            val targetId = target.id
            gui.setItem(19 + outSlots[index], GuiStyle.item(Material.MAP, text(target.name, NamedTextColor.WHITE), buildList {
                add(text("Your team asked them to ally", NamedTextColor.GRAY))
                add(text("Expires in ${relations.formatRemaining(request.expiresAt - now)}", NamedTextColor.GRAY))
                add(Component.empty())
                add(if (leads) text("Click to cancel the request", NamedTextColor.RED) else text("Only your team leader can cancel it.", NamedTextColor.DARK_GRAY))
            })) { p, _ ->
                val name = teams.team(targetId)?.name ?: return@setItem run { GuiStyle.errorSound(p); openRequests(p) }
                p.closeInventory()
                relations.cancelAlliance(p, name)
            }
        }
        gui.setItem(GuiStyle.backSlot(36), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); openRelations(p) }
        gui.setItem(GuiStyle.closeSlot(36), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    // ── Team profile (public) ───────────────────────────────

    /**
     * Any team's public profile: leader, members (heads, leader first and in gold), alliance in green,
     * declared rival and rivals-against in red. Nothing private (no requests, cooldowns, audit).
     * A leader of another team also gets the alliance and rivalry buttons for this team.
     */
    fun openProfile(player: Player, teamId: String) {
        val team = teams.team(teamId) ?: run {
            teams.send(player, text("That team doesn't exist any more.", NamedTextColor.GRAY))
            return base.openTeams(player, 0)
        }
        val gui = CustomGui(base.title(team.name), 36)
        val profile = TeamRelationRules.profile(relations.snapshot, team.id)
        val ally = profile.allyId?.let { teams.team(it) }
        val declared = profile.declaredRivalId?.let { teams.team(it) }
        val against = profile.rivalOfIds.mapNotNull { teams.team(it) }

        gui.setItem(4, GuiStyle.item(Material.WHITE_BANNER, text(team.name, NamedTextColor.GOLD).decorate(TextDecoration.BOLD), buildList {
            add(text("Leader: ", NamedTextColor.GRAY).append(text(teams.nameOf(team.leader), NamedTextColor.GOLD)))
            add(text("Members: ", NamedTextColor.GRAY).append(text("${team.size}/${teams.maxSize}", NamedTextColor.WHITE)))
            add(text("Alliance: ", NamedTextColor.GRAY).append(if (ally != null) text(ally.name, NamedTextColor.GREEN) else text("None", NamedTextColor.WHITE)))
            add(text("Declared Rival: ", NamedTextColor.GRAY).append(
                if (declared != null) text(declared.name + if (profile.mutual()) " (mutual)" else "", NamedTextColor.RED) else text("None", NamedTextColor.WHITE)
            ))
            add(text("Rival of: ", NamedTextColor.GRAY).append(
                if (against.isEmpty()) text("None", NamedTextColor.WHITE) else text(against.take(4).joinToString { it.name } + if (against.size > 4) " +${against.size - 4}" else "", NamedTextColor.RED)
            ))
        }))

        val slots = LifestealTeamMenu.centered(team.size, 9)
        team.members.take(9).forEachIndexed { index, member ->
            val isLeader = member.uuid == team.leader
            val online = teams.isOnline(member.uuid)
            val name = text(teams.nameOf(member.uuid), if (isLeader) NamedTextColor.GOLD else NamedTextColor.WHITE)
            gui.setItem(9 + slots[index], GuiStyle.skull(Bukkit.getOfflinePlayer(member.uuid), name, listOf(
                text(if (isLeader) "Leader" else "Member", if (isLeader) NamedTextColor.GOLD else NamedTextColor.GRAY),
                text(if (online) "Online" else "Offline", if (online) NamedTextColor.GREEN else NamedTextColor.RED),
            )))
        }

        val own = teams.teamOf(player.uniqueId)
        if (own != null && own.id != team.id && own.leader == player.uniqueId) {
            val snapshot = relations.snapshot
            val now = System.currentTimeMillis()
            val allied = snapshot.allied(own.id, team.id)
            val incomingFromThem = snapshot.pending(team.id, own.id, now) != null
            val sentToThem = snapshot.pending(own.id, team.id, now) != null
            val targetId = team.id
            if (relations.alliancesEnabled()) {
                gui.setItem(20, GuiStyle.item(Material.LIME_DYE, text(when {
                    allied -> "End Alliance"
                    incomingFromThem -> "Accept Their Request"
                    sentToThem -> "Cancel Your Request"
                    else -> "Request Alliance"
                }, NamedTextColor.GREEN), listOf(
                    text(when {
                        allied -> "Both teams wait before a new alliance."
                        incomingFromThem -> "Form an alliance with ${team.name}."
                        sentToThem -> "Your request is still pending."
                        else -> "Their leader must accept."
                    }, NamedTextColor.GRAY),
                ))) { p, _ ->
                    val name = teams.team(targetId)?.name ?: return@setItem run { GuiStyle.errorSound(p); base.openTeams(p, 0) }
                    when {
                        allied -> base.confirm(p, "End alliance?", text("Both teams wait before a new alliance.", NamedTextColor.RED)) { relations.dissolveAlliance(it, confirmed = true) }
                        incomingFromThem -> { p.closeInventory(); relations.acceptAlliance(p, name) }
                        sentToThem -> { p.closeInventory(); relations.cancelAlliance(p, name) }
                        else -> base.confirm(p, "Ask $name to ally?", text("Their leader must accept.", NamedTextColor.GRAY)) { relations.requestAlliance(it, name) }
                    }
                }
            }
            if (relations.rivalriesEnabled()) {
                val isOurRival = snapshot.rivalOf(own.id)?.target == team.id
                gui.setItem(24, GuiStyle.item(Material.RED_DYE, text(if (isOurRival) "Withdraw Rivalry" else "Declare Rival", NamedTextColor.RED), listOf(
                    text(if (isOurRival) "Remove your team's declaration." else "A label only: no rewards or PvP changes.", NamedTextColor.GRAY),
                ))) { p, _ ->
                    val name = teams.team(targetId)?.name ?: return@setItem run { GuiStyle.errorSound(p); base.openTeams(p, 0) }
                    if (isOurRival) base.confirm(p, "Withdraw rivalry?", text("$name is told.", NamedTextColor.GRAY)) { relations.withdrawRival(it) }
                    else base.confirm(p, "Declare $name rival?", text("$name is told. No approval needed.", NamedTextColor.GRAY)) { relations.declareRival(it, name) }
                }
            }
        }

        gui.setItem(GuiStyle.backSlot(36), GuiStyle.back()) { p, _ -> GuiStyle.clickSound(p); base.openTeams(p, 0) }
        gui.setItem(GuiStyle.closeSlot(36), GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }
}
