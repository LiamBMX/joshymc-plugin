package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.command
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.teamName
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.text
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `/team` for Lifesteal (issue #1346). Reached only through `TeamCommandRouter` when the sender
 * is in a Lifesteal world (or is the console of a Lifesteal server); the Survival `TeamCommand`
 * handles everyone else. Every check that matters runs again in [LifestealTeamManager] (and the
 * store), so this class only parses arguments and renders the read-only views from the cache.
 */
class LifestealTeamCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val teams get() = plugin.lifestealTeamManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.firstOrNull()?.lowercase()
        if (sub == "admin") {
            admin(sender, args.drop(1))
            return true
        }
        // Read-only views also work from the console.
        when (sub) {
            "info" -> { info(sender, args.getOrNull(1)); return true }
            "list" -> { list(sender, args.getOrNull(1)); return true }
            null, "help" -> if (sender !is Player || sub == "help") { help(sender); return true }
        }
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission(LifestealTeamManager.PERMISSION_USE)) {
            Msg.noPermission(sender)
            return true
        }
        when (sub) {
            null -> teams.unavailableReason()?.let { Msg.error(sender, it) } ?: LifestealTeamMenu(plugin).open(sender)
            "create" -> args.getOrNull(1)?.let { teams.create(sender, it) } ?: Msg.usage(sender, "/team create <name>")
            "invite" -> args.getOrNull(1)?.let { teams.invite(sender, it) } ?: Msg.usage(sender, "/team invite <player>")
            "accept", "join" -> teams.accept(sender, args.getOrNull(1))
            "deny", "decline" -> teams.deny(sender, args.getOrNull(1))
            "leave" -> teams.leave(sender)
            "kick", "remove" -> args.getOrNull(1)?.let { teams.kick(sender, it) } ?: Msg.usage(sender, "/team kick <player>")
            "transfer", "promote" -> args.getOrNull(1)?.let { teams.transfer(sender, it, confirmed(args, 2)) }
                ?: Msg.usage(sender, "/team $sub <player>")
            "disband" -> teams.disband(sender, confirmed(args, 1))
            else -> help(sender)
        }
        return true
    }

    private fun confirmed(args: Array<out String>, index: Int): Boolean = args.getOrNull(index).equals("confirm", ignoreCase = true)

    private fun help(sender: CommandSender) {
        val lines = mutableListOf(
            "/team" to "Open the team menu",
            "/team create <name>" to "Create a team (you become its leader)",
            "/team invite <player>" to "Invite a player (leader)",
            "/team accept [team]" to "Accept an invitation",
            "/team deny [team]" to "Decline an invitation",
            "/team leave" to "Leave your team",
            "/team kick <player>" to "Remove a member (leader)",
            "/team transfer <player>" to "Make a member the leader (leader; /team promote does the same)",
            "/team disband" to "Disband your team (leader)",
            "/team info [team]" to "Show a team",
            "/team list [page]" to "List all teams",
        )
        if (teams.canAdmin(sender)) {
            lines += "/team admin info <team>" to "Staff: full team details"
            lines += "/team admin disband <team>" to "Staff: disband a team"
            lines += "/team admin transfer <team> <player>" to "Staff: change a team's leader"
        }
        Msg.reply(sender, text("Lifesteal Teams (up to ${teams.maxSize} players)", NamedTextColor.GOLD))
        for ((usage, what) in lines) {
            sender.sendMessage(text(" $usage", NamedTextColor.YELLOW).append(text(" - $what", NamedTextColor.GRAY)))
        }
    }

    // ── Read-only views (cache only) ────────────────────────

    private fun available(sender: CommandSender): Boolean {
        if (sender is Player && !sender.hasPermission(LifestealTeamManager.PERMISSION_USE)) {
            Msg.noPermission(sender)
            return false
        }
        val reason = teams.unavailableReason() ?: return true
        Msg.error(sender, reason)
        return false
    }

    private fun info(sender: CommandSender, name: String?) {
        if (!available(sender)) return
        val team = if (name == null) {
            val player = sender as? Player ?: return Msg.usage(sender, "/team info <team>")
            teams.teamOf(player.uniqueId) ?: return Msg.error(sender, "You're not in a team. Use /team info <team> to look at another one.")
        } else {
            teams.teamByName(name) ?: return Msg.error(sender, "There's no team called $name.")
        }
        showInfo(sender, team, admin = false)
    }

    private fun showInfo(sender: CommandSender, team: Team, admin: Boolean) {
        val online = team.members.count { teams.isOnline(it.uuid) }
        Msg.reply(sender, text("Team ", NamedTextColor.GRAY).append(teamName(team)))
        line(sender, "Leader", teams.nameOf(team.leader))
        line(sender, "Members", "${team.size}/${teams.maxSize} (${online} online)")
        val roster = team.members.foldIndexed(Component.empty()) { index, acc, member ->
            val color = if (teams.isOnline(member.uuid)) NamedTextColor.GREEN else NamedTextColor.GRAY
            val tag = if (member.uuid == team.leader) " (Leader)" else ""
            acc.append(if (index > 0) text(", ", NamedTextColor.DARK_GRAY) else Component.empty())
                .append(text(teams.nameOf(member.uuid) + tag, color))
        }
        sender.sendMessage(text("  ", NamedTextColor.GRAY).append(roster))
        line(sender, "Created", formatDate(team.createdAt, sender))
        if (admin) {
            line(sender, "Team id", team.id)
            line(sender, "Version", team.version.toString())
            line(sender, "Created by", teams.nameOf(team.createdBy))
            val pending = teams.invitesFrom(team.id)
            line(sender, "Pending invitations", if (pending.isEmpty()) "none" else pending.joinToString { teams.nameOf(it.target) })
        }
    }

    private fun line(sender: CommandSender, label: String, value: String) {
        sender.sendMessage(text("  $label: ", NamedTextColor.GRAY).append(text(value, NamedTextColor.WHITE)))
    }

    private fun formatDate(millis: Long, sender: CommandSender): String {
        val zone: ZoneId = (sender as? Player)?.let { plugin.timezoneManager.zoneFor(it) } ?: ZoneId.systemDefault()
        return DATE.withZone(zone).format(Instant.ofEpochMilli(millis))
    }

    private fun list(sender: CommandSender, rawPage: String?) {
        if (!available(sender)) return
        val all = teams.allTeams()
        if (all.isEmpty()) return Msg.reply(sender, text("There are no Lifesteal teams yet. Create one with /team create <name>.", NamedTextColor.GRAY))
        val pages = (all.size + PER_PAGE - 1) / PER_PAGE
        val page = (rawPage?.toIntOrNull() ?: 1).coerceIn(1, pages)
        Msg.reply(sender, text("Lifesteal Teams", NamedTextColor.GOLD).append(text(" (${all.size})" + if (pages > 1) " - page $page/$pages" else "", NamedTextColor.GRAY)))
        for (team in all.drop((page - 1) * PER_PAGE).take(PER_PAGE)) {
            sender.sendMessage(
                text(" ", NamedTextColor.GRAY).append(command(team.name, "/team info ${team.name}", NamedTextColor.GOLD))
                    .append(text(" ${team.size}/${teams.maxSize}", NamedTextColor.WHITE))
                    .append(text(" - leader ${teams.nameOf(team.leader)}", NamedTextColor.GRAY))
            )
        }
        if (page < pages) sender.sendMessage(text(" Next page: ", NamedTextColor.GRAY).append(command("/team list ${page + 1}", "/team list ${page + 1}")))
    }

    // ── Admin ───────────────────────────────────────────────

    private fun admin(sender: CommandSender, args: List<String>) {
        if (!teams.canAdmin(sender)) return Msg.noPermission(sender)
        when (args.firstOrNull()?.lowercase()) {
            "info" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/team admin info <team>")
                teams.unavailableReason()?.let { return Msg.error(sender, it) }
                val team = teams.teamByName(name) ?: return Msg.error(sender, "There's no team called $name.")
                showInfo(sender, team, admin = true)
            }
            "disband" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/team admin disband <team> [confirm]")
                teams.adminDisband(sender, name, args.getOrNull(2).equals("confirm", ignoreCase = true))
            }
            "transfer" -> {
                val name = args.getOrNull(1)
                val player = args.getOrNull(2)
                if (name == null || player == null) return Msg.usage(sender, "/team admin transfer <team> <player> [confirm]")
                teams.adminTransfer(sender, name, player, args.getOrNull(3).equals("confirm", ignoreCase = true))
            }
            else -> Msg.usage(sender, "/team admin <info|disband|transfer> <team> ...")
        }
    }

    // ── Tab completion ──────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val player = sender as? Player
        val subs = mutableListOf("create", "invite", "accept", "deny", "leave", "kick", "transfer", "promote", "disband", "info", "list", "help")
        if (teams.canAdmin(sender)) subs += "admin"
        val own = player?.let { teams.teamOf(it.uniqueId) }
        val options: List<String> = when (args.size) {
            1 -> subs
            2 -> when (args[0].lowercase()) {
                "invite" -> org.bukkit.Bukkit.getOnlinePlayers().filter { player == null || player.canSee(it) }.map { it.name }
                "kick", "remove", "transfer", "promote" -> own?.members?.filter { it.uuid != player?.uniqueId }?.map { teams.nameOf(it.uuid) }.orEmpty()
                "accept", "join", "deny", "decline" -> player?.let { p -> teams.invitesFor(p.uniqueId).mapNotNull { teams.team(it.teamId)?.name } }.orEmpty()
                "info" -> teams.allTeams().map { it.name }
                "admin" -> if (teams.canAdmin(sender)) listOf("info", "disband", "transfer") else emptyList()
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("admin", true) && teams.canAdmin(sender) -> teams.allTeams().map { it.name }
                args[0].lowercase() in setOf("transfer", "promote") -> listOf("confirm")
                else -> emptyList()
            }
            4 -> if (args[0].equals("admin", true) && args[1].equals("transfer", true) && teams.canAdmin(sender)) {
                teams.teamByName(args[2])?.members?.map { teams.nameOf(it.uuid) }.orEmpty()
            } else emptyList()
            else -> emptyList()
        }
        val prefix = args.lastOrNull()?.lowercase().orEmpty()
        return options.filter { it.lowercase().startsWith(prefix) }
    }

    companion object {
        const val PER_PAGE = 10
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}
