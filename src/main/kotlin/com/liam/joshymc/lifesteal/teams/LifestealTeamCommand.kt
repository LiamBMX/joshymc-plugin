package com.liam.joshymc.lifesteal.teams

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.command
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.teamName
import com.liam.joshymc.lifesteal.teams.LifestealTeamManager.Companion.text
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.lifesteal.teams.progression.TeamLevels
import com.liam.joshymc.lifesteal.teams.progression.TeamPerks
import com.liam.joshymc.lifesteal.teams.progression.TeamUpgradesMenu
import com.liam.joshymc.lifesteal.teams.progression.UpgradeCategory
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
            "ally", "alliance" -> ally(sender, args.drop(1))
            "rival", "rivalry" -> rival(sender, args.drop(1))
            // Issue #1426: Team Upgrades and shared progression.
            "upgrades", "upgrade", "progression", "level" -> TeamUpgradesMenu(plugin).open(sender)
            "objectives" -> TeamUpgradesMenu(plugin).openObjectives(sender)
            "milestones" -> TeamUpgradesMenu(plugin).openMilestones(sender)
            "style" -> TeamUpgradesMenu(plugin).openStyle(sender)
            "bank" -> bank(sender, args.drop(1))
            else -> help(sender)
        }
        return true
    }

    /**
     * Issue #1405: `/team ally ...`. Keywords win over team names, so a team literally called
     * "accept" is reached with `/team ally request accept`.
     */
    private fun ally(player: Player, args: List<String>) {
        val relations = teams.relations
        when (args.firstOrNull()?.lowercase()) {
            null, "list", "requests" -> teams.unavailableReason()?.let { Msg.error(player, it) } ?: TeamRelationsMenu(plugin).openRelations(player)
            "accept" -> relations.acceptAlliance(player, args.getOrNull(1))
            "deny", "decline" -> relations.declineAlliance(player, args.getOrNull(1))
            "cancel" -> relations.cancelAlliance(player, args.getOrNull(1))
            "leave", "end", "dissolve" -> relations.dissolveAlliance(player, args.getOrNull(1).equals("confirm", ignoreCase = true))
            "request", "send" -> relations.requestAlliance(player, args.getOrNull(1))
            else -> relations.requestAlliance(player, args[0])
        }
    }

    /** Issue #1405: `/team rival <team>` / `/team rival remove`. `/team rival declare <team>` for a team named like a keyword. */
    private fun rival(player: Player, args: List<String>) {
        val relations = teams.relations
        when (args.firstOrNull()?.lowercase()) {
            null -> teams.unavailableReason()?.let { Msg.error(player, it) } ?: TeamRelationsMenu(plugin).openRelations(player)
            "remove", "withdraw" -> relations.withdrawRival(player)
            "declare" -> relations.declareRival(player, args.getOrNull(1))
            else -> relations.declareRival(player, args[0])
        }
    }

    /**
     * Issue #1426: `/team bank` (menu) and `/team bank deposit <amount> [confirm]`. Deposits are
     * never refunded (coins only buy upgrades), so the typed form asks for `confirm` first.
     */
    private fun bank(player: Player, args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            null -> TeamUpgradesMenu(plugin).openBank(player)
            "deposit", "add" -> {
                val raw = args.getOrNull(1) ?: return Msg.usage(player, "/team bank deposit <amount>")
                val cents = when (val parsed = BountyMoney.parse(raw)) {
                    is BountyMoney.Parsed.Ok -> parsed.cents
                    else -> return Msg.error(player, "'$raw' isn't an amount of coins.")
                }
                if (!args.getOrNull(2).equals("confirm", ignoreCase = true)) {
                    val amount = BountyMoney.format(cents)
                    val typed = BountyMoney.format(cents).removePrefix("$").replace(",", "")
                    return Msg.reply(player, text("Deposit $amount of your coins into the team bank? It can only be spent on team upgrades and is never refunded. ", NamedTextColor.YELLOW)
                        .append(command("[Confirm]", "/team bank deposit $typed confirm", NamedTextColor.GREEN)))
                }
                plugin.lifestealTeamProgressionManager.deposit(player, cents, "deposit:" + java.util.UUID.randomUUID())
            }
            else -> Msg.usage(player, "/team bank [deposit <amount>]")
        }
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
            "/team ally <team>" to "Ask a team for an alliance (leader)",
            "/team ally accept|deny [team]" to "Answer an alliance request (leader)",
            "/team ally cancel [team]" to "Cancel your alliance request (leader)",
            "/team ally leave" to "End your alliance (leader; both teams wait before a new one)",
            "/team ally" to "Alliances and rivalries menu",
            "/team rival <team>" to "Declare a rival team (leader; a label only)",
            "/team rival remove" to "Withdraw your rivalry (leader)",
            "/ac <message>" to "Chat with your team and its allies",
            "/team upgrades" to "Team Level, upgrades, objectives and milestones",
            "/team bank [deposit <amount>]" to "The team bank (coins only buy upgrades)",
            "/team objectives" to "Shared team objectives",
            "/team milestones" to "Team milestone rewards",
            "/team info [team]" to "Show a team",
            "/team list [page]" to "List all teams",
        )
        if (teams.canAdmin(sender)) {
            lines += "/team admin info <team>" to "Staff: full team details"
            lines += "/team admin disband <team>" to "Staff: disband a team"
            lines += "/team admin transfer <team> <player>" to "Staff: change a team's leader"
            lines += "/team admin pvp <player> <player>" to "Staff: team PvP and heart eligibility of two players"
            lines += "/team admin progression <team>" to "Staff: Team Level, upgrades, bank and recent audit"
        }
        Msg.reply(sender, text("Lifesteal Teams (up to ${teams.maxSize} players)", NamedTextColor.GOLD))
        sender.sendMessage(text(" Friendly fire: ", NamedTextColor.GRAY).append(text(teams.friendlyFireSummary(), NamedTextColor.WHITE)))
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
        // Issue #1426: Team Level and chosen title (cosmetic).
        plugin.lifestealTeamProgressionManager.takeIf { it.active }?.let { progression ->
            line(sender, "Team Level", progression.level(team.id).toString())
            progression.snapshot(team.id)?.title?.let { line(sender, "Title", it) }
        }
        line(sender, "Friendly fire", teams.friendlyFireSummary())
        relationLines(sender, team, admin)
        if (admin) {
            line(sender, "Team id", team.id)
            line(sender, "Version", team.version.toString())
            line(sender, "Created by", teams.nameOf(team.createdBy))
            val pending = teams.invitesFrom(team.id)
            line(sender, "Pending invitations", if (pending.isEmpty()) "none" else pending.joinToString { teams.nameOf(it.target) })
        }
    }

    /**
     * Issue #1405: the team profile's relationships, public: ally in green, rivals in red, with a
     * rivalry declared BY the team kept apart from rivalries declared AGAINST it. The alliance cooldown
     * is only shown to the team's own members (and staff).
     */
    private fun relationLines(sender: CommandSender, team: Team, admin: Boolean) {
        val relations = teams.relations
        val ally = relations.allyOf(team.id)
        sender.sendMessage(text("  Alliance: ", NamedTextColor.GRAY).append(
            if (ally != null) text(ally.name, NamedTextColor.GREEN) else text("None", NamedTextColor.WHITE)
        ))
        val profile = TeamRelationRules.profile(relations.snapshot, team.id)
        val declared = relations.declaredRival(team.id)
        sender.sendMessage(text("  Declared Rival: ", NamedTextColor.GRAY).append(
            if (declared != null) text(declared.name + if (profile.mutual()) " (mutual)" else "", NamedTextColor.RED) else text("None", NamedTextColor.WHITE)
        ))
        val against = relations.rivalsAgainst(team.id)
        sender.sendMessage(text("  Rival of: ", NamedTextColor.GRAY).append(
            if (against.isEmpty()) text("None", NamedTextColor.WHITE) else text(against.joinToString { it.name }, NamedTextColor.RED)
        ))
        val own = (sender as? Player)?.let { teams.teamOf(it.uniqueId)?.id == team.id } == true
        if (own || admin) {
            relations.cooldownRemaining(team.id)?.let { line(sender, "Alliance cooldown", relations.formatRemaining(it)) }
        }
        if (admin) {
            line(sender, "Alliance requests in", relations.incoming(team.id).joinToString { teams.team(it.source)?.name ?: it.source }.ifEmpty { "none" })
            line(sender, "Alliance requests out", relations.outgoing(team.id).joinToString { teams.team(it.target)?.name ?: it.target }.ifEmpty { "none" })
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
            "progression", "upgrades" -> {
                val name = args.getOrNull(1) ?: return Msg.usage(sender, "/team admin progression <team>")
                teams.unavailableReason()?.let { return Msg.error(sender, it) }
                val team = teams.teamByName(name) ?: return Msg.error(sender, "There's no team called $name.")
                progressionInfo(sender, team)
            }
            "pvp" -> {
                val first = args.getOrNull(1)
                val second = args.getOrNull(2)
                if (first == null || second == null) return Msg.usage(sender, "/team admin pvp <player> <player>")
                pvpDiagnostics(sender, first, second)
            }
            else -> Msg.usage(sender, "/team admin <info|disband|transfer|pvp|progression> ...")
        }
    }

    /**
     * `/team admin pvp <a> <b>` (issue #1348): read-only. The policy, how the two relate right now,
     * whether a hit between them is blocked and whether a kill could move a heart (team check plus
     * both same-victim cooldowns). Shows nothing but team relationship and cooldown times.
     */
    private fun pvpDiagnostics(sender: CommandSender, firstName: String, secondName: String) {
        teams.unavailableReason()?.let { Msg.error(sender, it) }
        val first = plugin.playerResolver.resolve(firstName) ?: return Msg.playerNotFound(sender, firstName)
        val second = plugin.playerResolver.resolve(secondName) ?: return Msg.playerNotFound(sender, secondName)
        val a = first.uniqueId
        val b = second.uniqueId
        val nameA = teams.nameOf(a)
        val nameB = teams.nameOf(b)
        val policy = teams.combatPolicy()
        val relation = teams.combatRelation(a, b)
        Msg.reply(sender, text("Team PvP: ", NamedTextColor.GRAY).append(text("$nameA / $nameB", NamedTextColor.WHITE)))
        line(sender, "Friendly fire", teams.friendlyFireSummary())
        line(sender, "Teams", "${teams.teamOf(a)?.name ?: "none"} / ${teams.teamOf(b)?.name ?: "none"}")
        line(sender, "Relation", when (relation) {
            TeamCombatRules.Relation.TEAMMATES -> "teammates"
            TeamCombatRules.Relation.RECENT_TEAMMATES -> "recent teammates (left within the combat-tag time)"
            TeamCombatRules.Relation.NOT_TEAMMATES -> if (a == b) "same player" else "not teammates"
            TeamCombatRules.Relation.UNKNOWN -> "unknown (team data unavailable)"
            TeamCombatRules.Relation.ALLIES -> "allies (their teams are allied)"
        })
        val damage = TeamCombatRules.damage(a, b, relation, policy)
        line(sender, "Damage between them", if (damage == TeamCombatRules.DamageDecision.BLOCK_FRIENDLY_FIRE) {
            if (relation == TeamCombatRules.Relation.ALLIES) "blocked (alliance)" else "blocked (friendly fire)"
        } else "normal PvP rules")
        line(sender, "Heart transfer (team check)", when (TeamCombatRules.heartCheck(relation, policy)) {
            TeamCombatRules.HeartCheck.ELIGIBLE -> "allowed"
            TeamCombatRules.HeartCheck.TEAMMATES -> "refused (teammates)"
            TeamCombatRules.HeartCheck.UNKNOWN -> "refused (team data or settings unavailable)"
            TeamCombatRules.HeartCheck.ALLIES -> "refused (allied teams)"
        })
        if (a == b) return
        val hearts = plugin.lifestealHeartManager
        val cooldownAB = try { hearts.pairCooldownRemaining(a, b) } catch (e: Exception) { null }
        val cooldownBA = try { hearts.pairCooldownRemaining(b, a) } catch (e: Exception) { null }
        fun cooldown(value: Long?): String = when {
            value == null -> "unavailable (hearts paused)"
            value <= 0 -> "none"
            else -> com.liam.joshymc.lifesteal.hearts.HeartRules.formatDuration(value)
        }
        line(sender, "Cooldown $nameA -> $nameB", cooldown(cooldownAB))
        line(sender, "Cooldown $nameB -> $nameA", cooldown(cooldownBA))
    }

    /** `/team admin progression <team>` (issue #1426): read-only, straight from the database. */
    private fun progressionInfo(sender: CommandSender, team: Team) {
        val progression = plugin.lifestealTeamProgressionManager
        val s = progression.settings ?: return Msg.error(sender, progression.unavailableReason() ?: "Team Upgrades are unavailable.")
        val season = progression.seasonId()
        progression.read("admin " + team.id, { store -> store.snapshot(team.id, progression.cycle(s, season), season) to store.audit(team.id, 8) }) { data ->
            val (snap, audit) = data ?: return@read Msg.error(sender, "Couldn't read that team's progression (see the console).")
            val levels = TeamLevels.progress(snap.totalXp, s.levels)
            val perks = TeamPerks.of(snap.tiers, s)
            Msg.reply(sender, text("Team progression: ", NamedTextColor.GRAY).append(teamName(team)))
            line(sender, "Level", "${levels.level} (${levels.xpIntoLevel}/${levels.xpForNext} XP, total ${snap.totalXp}, season $season ${snap.seasonXp})")
            line(sender, "Upgrades", UpgradeCategory.entries.joinToString { it.id + " " + (snap.tiers[it] ?: 0) })
            line(sender, "Bank", BountyMoney.format(snap.bankCents) + " / " + BountyMoney.format(perks.bankCapacityCents))
            line(sender, "XP bonus", "+" + perks.xpBonusPercent + "%")
            line(sender, "Milestones claimed", snap.claimed.sorted().joinToString().ifEmpty { "none" })
            line(sender, "Objectives (" + snap.cycle + ")", snap.objectives.entries.joinToString { it.key + " " + it.value.progress + (if (it.value.completed) " done" else "") }.ifEmpty { "none" })
            for (row in audit) {
                sender.sendMessage(text("  " + formatDate(row.at, sender) + " " + row.action + " " + (row.actorName ?: "") + " " + (row.details ?: ""), NamedTextColor.DARK_GRAY))
            }
        }
    }

    // ── Tab completion ──────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val player = sender as? Player
        val subs = mutableListOf("create", "invite", "accept", "deny", "leave", "kick", "transfer", "promote", "disband", "ally", "rival",
            "upgrades", "bank", "objectives", "milestones", "style", "info", "list", "help")
        if (teams.canAdmin(sender)) subs += "admin"
        val own = player?.let { teams.teamOf(it.uniqueId) }
        val options: List<String> = when (args.size) {
            1 -> subs
            2 -> when (args[0].lowercase()) {
                "invite" -> org.bukkit.Bukkit.getOnlinePlayers().filter { player == null || player.canSee(it) }.map { it.name }
                "kick", "remove", "transfer", "promote" -> own?.members?.filter { it.uuid != player?.uniqueId }?.map { teams.nameOf(it.uuid) }.orEmpty()
                "accept", "join", "deny", "decline" -> player?.let { p -> teams.invitesFor(p.uniqueId).mapNotNull { teams.team(it.teamId)?.name } }.orEmpty()
                "info" -> teams.allTeams().map { it.name }
                "admin" -> if (teams.canAdmin(sender)) listOf("info", "disband", "transfer", "pvp", "progression") else emptyList()
                "bank" -> listOf("deposit")
                "ally", "alliance" -> listOf("accept", "deny", "cancel", "leave") + otherTeams(own)
                "rival", "rivalry" -> listOf("remove") + otherTeams(own)
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("admin", true) && args[1].equals("pvp", true) && teams.canAdmin(sender) ->
                    org.bukkit.Bukkit.getOnlinePlayers().map { it.name }
                args[0].equals("admin", true) && teams.canAdmin(sender) -> teams.allTeams().map { it.name }
                args[0].lowercase() in setOf("transfer", "promote") -> listOf("confirm")
                args[0].lowercase() in setOf("ally", "alliance") -> allySecond(args[1].lowercase(), own)
                args[0].lowercase() in setOf("rival", "rivalry") && args[1].equals("declare", true) -> otherTeams(own)
                else -> emptyList()
            }
            4 -> when {
                !args[0].equals("admin", true) || !teams.canAdmin(sender) -> emptyList()
                args[1].equals("transfer", true) -> teams.teamByName(args[2])?.members?.map { teams.nameOf(it.uuid) }.orEmpty()
                args[1].equals("pvp", true) -> org.bukkit.Bukkit.getOnlinePlayers().map { it.name }
                else -> emptyList()
            }
            else -> emptyList()
        }
        val prefix = args.lastOrNull()?.lowercase().orEmpty()
        return options.filter { it.lowercase().startsWith(prefix) }
    }

    private fun otherTeams(own: Team?): List<String> = teams.allTeams().filter { it.id != own?.id }.map { it.name }

    /** Tab completion after `/team ally <keyword>`: only the teams that keyword can act on. */
    private fun allySecond(keyword: String, own: Team?): List<String> {
        val relations = teams.relations
        return when (keyword) {
            "accept", "deny", "decline" -> own?.let { t -> relations.incoming(t.id).mapNotNull { teams.team(it.source)?.name } }.orEmpty()
            "cancel" -> own?.let { t -> relations.outgoing(t.id).mapNotNull { teams.team(it.target)?.name } }.orEmpty()
            "leave", "end", "dissolve" -> listOf("confirm")
            "request", "send" -> otherTeams(own)
            else -> emptyList()
        }
    }

    companion object {
        const val PER_PAGE = 10
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")
    }
}
