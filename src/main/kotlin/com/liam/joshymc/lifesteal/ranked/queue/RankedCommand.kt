package com.liam.joshymc.lifesteal.ranked.queue

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.button
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.seconds
import com.liam.joshymc.lifesteal.ranked.queue.LifestealRankedQueueManager.Companion.text
import com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.lifesteal.ranked.stats.RankedStatsCommand
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/ranked` (issue #1363, Lifesteal only, scope LIFESTEAL): the player side of Ranked Team Wars
 * matchmaking. CommandManager only registers it on a server that hosts Lifesteal (it is released
 * elsewhere). Every subcommand calls [LifestealRankedQueueManager], which re-checks everything; the
 * command only parses.
 *
 * - `/ranked` opens the menu ([RankedQueueMenu]).
 * - `/ranked queue <2v2|3v3|4v4|5v5> [players...]`: the leader picks who plays (list size-1
 *   teammates to play too); every picked player then has to `/ranked accept`.
 * - `/ranked accept|decline [request]`: agree to play / accept a found match, or refuse.
 * - `/ranked leave`, `/ranked status`.
 * - `/ranked challenge <team> <size> [players...]`, `/ranked challenge accept <team> [players...]`,
 *   `/ranked challenge decline <team>`, `/ranked challenge cancel`, `/ranked challenges`.
 * - `/ranked stats [player]`, `/ranked rating`, `/ranked history [player]` (issue #1365): Player Elo
 *   and Team Elo of the current season, read off the main thread through [LifestealRatingManager].
 *   Other players' stats need `joshymc.lifesteal.ranked.stats.others` (default true).
 */
class RankedCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val queue get() = plugin.lifestealRankedQueueManager
    private val teams get() = plugin.lifestealTeamManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: run {
            Msg.playersOnly(sender)
            return true
        }
        if (!player.hasPermission(LifestealRankedQueueManager.PERMISSION_USE)) {
            Msg.noPermission(player)
            return true
        }
        if (!queue.hosted) {
            Msg.error(player, "Ranked Team Wars only exist on the Lifesteal server.")
            return true
        }
        val a = args.toList()
        when (a.firstOrNull()?.lowercase()) {
            // The rating is loaded first (async), so the menu opens with it filled in.
            null, "menu" -> plugin.lifestealRatingManager.loadProfile(player.uniqueId) { _, _ -> if (player.isOnline) RankedQueueMenu(plugin).open(player) }
            "stats" -> stats(player, a.getOrNull(1), full = true)
            "rating" -> stats(player, null, full = false)
            "history" -> history(player, a.drop(1))
            // Issue #1367: leaderboards, team statistics, divisions and the season (read-only).
            "top", "leaderboard", "lb" -> top(player, a.drop(1))
            "teamstats", "team" -> teamStats(player, a.drop(1).joinToString(" ").ifBlank { null })
            "divisions", "division" -> statsView.divisions(player)
            "season" -> statsView.season(player)
            "queue", "join" -> {
                val size = a.getOrNull(1)?.let { RankedQueueBook.parseSize(it) }
                if (size == null) Msg.usage(player, "/ranked queue <2v2|3v3|4v4|5v5> [teammates...]")
                else queue.queue(player, size, a.drop(2))
            }
            "leave", "cancel" -> queue.leave(player)
            "status" -> status(player)
            "accept" -> queue.accept(player, a.getOrNull(1)?.takeIf { RankedQueueBook.validToken(it) })
            "decline", "deny" -> queue.decline(player, a.getOrNull(1)?.takeIf { RankedQueueBook.validToken(it) })
            "challenges" -> challenges(player)
            "challenge" -> challenge(player, a.drop(1))
            else -> Msg.usage(player, USAGE)
        }
        return true
    }

    // ── Ratings (issue #1365) ───────────────────────────────

    /** The player [name] names (null = [player] themselves), or null after telling them why not. */
    private fun target(player: Player, name: String?): Pair<java.util.UUID, String>? {
        if (name == null || name.equals(player.name, ignoreCase = true)) return player.uniqueId to player.name
        if (!player.hasPermission(LifestealRatingManager.PERMISSION_STATS_OTHERS)) { Msg.noPermission(player); return null }
        // Never Bukkit.getOfflinePlayer(String): names the server has never seen are refused.
        val found = plugin.playerResolver.resolve(name) ?: run { Msg.playerNotFound(player, name); return null }
        return found.uniqueId to (found.name ?: name)
    }

    /** Issue #1367: the statistics views (chat). Ratings are only read, never changed. */
    private val statsView by lazy { RankedStatsCommand(plugin) }

    private fun stats(player: Player, name: String?, full: Boolean) {
        val (uuid, shown) = target(player, name) ?: return
        statsView.stats(player, uuid, shown, full)
    }

    /** `/ranked history [player] [page]`: match results with their Elo changes (issue #1367). */
    private fun history(player: Player, args: List<String>) {
        val page = args.lastOrNull()?.toIntOrNull()
        val name = (if (page != null) args.dropLast(1) else args).firstOrNull()
        val (uuid, shown) = target(player, name) ?: return
        statsView.history(player, uuid, shown, ((page ?: 1) - 1).coerceAtLeast(0))
    }

    /** `/ranked top [players|teams] [page]`. */
    private fun top(player: Player, args: List<String>) {
        val type = when (args.firstOrNull()?.lowercase()) {
            null, "players", "player", "p" -> RatingType.PLAYER_ELO
            "teams", "team", "t" -> RatingType.TEAM_ELO
            else -> if (args.first().toIntOrNull() != null) RatingType.PLAYER_ELO else return Msg.usage(player, "/ranked top [players|teams] [page]")
        }
        val page = args.lastOrNull()?.toIntOrNull() ?: 1
        statsView.top(player, type, (page - 1).coerceAtLeast(0))
    }

    /** `/ranked teamstats [team]`: own team by default; other active teams are public. */
    private fun teamStats(player: Player, name: String?) {
        val team = if (name == null) teams.teamOf(player.uniqueId) ?: return Msg.error(player, "You aren't in a Lifesteal team. Use /ranked teamstats <team>.")
        else teams.teamByName(name) ?: return Msg.error(player, "No active Lifesteal team is called '$name'.")
        statsView.teamStats(player, team.id)
    }

    private fun challenge(player: Player, a: List<String>) {
        when (a.firstOrNull()?.lowercase()) {
            null -> Msg.usage(player, "/ranked challenge <team> <2v2|3v3|4v4|5v5> [teammates...] | accept <team> [teammates...] | decline <team> | cancel")
            "accept" -> {
                val from = a.getOrNull(1) ?: return Msg.usage(player, "/ranked challenge accept <team> [teammates...]")
                queue.acceptChallenge(player, from, a.drop(2))
            }
            "decline", "deny" -> {
                val from = a.getOrNull(1) ?: return Msg.usage(player, "/ranked challenge decline <team>")
                queue.declineChallenge(player, from)
            }
            "cancel", "withdraw" -> queue.cancelChallenge(player)
            else -> {
                val target = a[0]
                val size = a.getOrNull(1)?.let { RankedQueueBook.parseSize(it) }
                    ?: return Msg.usage(player, "/ranked challenge <team> <2v2|3v3|4v4|5v5> [teammates...]")
                queue.challenge(player, target, size, a.drop(2))
            }
        }
    }

    private fun status(player: Player) {
        val now = System.currentTimeMillis()
        queue.unavailableReason(now)?.let { Msg.reply(player, text(it, NamedTextColor.GRAY)) }
        val team = teams.teamOf(player.uniqueId)
        val entry = queue.entryOf(player.uniqueId) ?: team?.let { queue.entryOfTeam(it.id) }
        val send = { c: Component -> plugin.commsManager.send(player, c) }
        send(text("Ranked Team Wars", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        if (entry == null) {
            send(text("You aren't in a ranked queue or challenge.", NamedTextColor.GRAY))
        } else {
            send(text("${team?.name ?: entry.teamName}: ${queue.describe(entry)}, ${entry.state.label}.", NamedTextColor.YELLOW))
            val proposal = entry.proposalId?.let { queue.proposal(it) }
            for (uuid in entry.participants) {
                val mark = when {
                    proposal != null -> if (uuid in proposal.accepted) "accepted" else "hasn't accepted"
                    entry.state == QueueState.FORMING -> if (uuid in entry.consented) "agreed" else "hasn't agreed"
                    else -> "ready"
                }
                send(text("  ${teams.nameOf(uuid)}: $mark", NamedTextColor.GRAY))
            }
            entry.waitingSince?.let { send(text("Waiting for ${seconds(now - it)}s (${queue.waitingCount(entry.size)} team(s) in the ${entry.size}v${entry.size} queue).", NamedTextColor.GRAY)) }
            entry.problem?.let { send(text("Paused: $it.", NamedTextColor.YELLOW)) }
            if (proposal != null) send(text("Accept within ${seconds(proposal.expiresAt - now)}s: ", NamedTextColor.GOLD)
                .append(button("[Accept]", "/ranked accept ${proposal.id}", NamedTextColor.GREEN))
                .append(text(" ", NamedTextColor.GRAY))
                .append(button("[Decline]", "/ranked decline ${proposal.id}", NamedTextColor.RED)))
            else if (entry.state == QueueState.FORMING && player.uniqueId in entry.pendingConsent) send(button("[Agree]", "/ranked accept ${entry.id}", NamedTextColor.GREEN)
                .append(text(" ", NamedTextColor.GRAY))
                .append(button("[Decline]", "/ranked decline ${entry.id}", NamedTextColor.RED)))
            send(button("[Leave]", "/ranked leave", NamedTextColor.RED))
        }
        queue.playerCooldownSeconds(player.uniqueId).takeIf { it > 0 }?.let { send(text("You can queue again in ${it}s.", NamedTextColor.GRAY)) }
        team?.let { t ->
            queue.teamCooldownSeconds(t.id).takeIf { it > 0 && entry == null }?.let { send(text("Your team can queue again in ${it}s.", NamedTextColor.GRAY)) }
            val incoming = queue.incomingChallenges(t.id)
            if (incoming.isNotEmpty()) send(text("${incoming.size} open challenge(s) for ${t.name}: ${incoming.joinToString { "${it.fromTeamName} (${it.size}v${it.size})" }}. /ranked challenges", NamedTextColor.GOLD))
        }
    }

    private fun challenges(player: Player) {
        val team = teams.teamOf(player.uniqueId) ?: return Msg.error(player, "Join a Lifesteal team first.")
        val now = System.currentTimeMillis()
        val incoming = queue.incomingChallenges(team.id)
        val outgoing = queue.outgoingChallenge(team.id)
        plugin.commsManager.send(player, text("Team challenges for ${team.name}", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        if (incoming.isEmpty() && outgoing == null) plugin.commsManager.send(player, text("None. Challenge a team: /ranked challenge <team> <size> [teammates]", NamedTextColor.GRAY))
        for (c in incoming) {
            var line = text("From ${c.fromTeamName}: ${c.size}v${c.size}, ${seconds(c.expiresAt - now)}s left ", NamedTextColor.YELLOW)
            if (team.leader == player.uniqueId) line = line.append(button("[Decline]", "/ranked challenge decline ${c.fromTeamName}", NamedTextColor.RED))
                .append(text("  accept: /ranked challenge accept ${c.fromTeamName} [teammates]", NamedTextColor.GRAY))
            plugin.commsManager.send(player, line)
        }
        outgoing?.let { c ->
            plugin.commsManager.send(player, text("To ${c.toTeamName}: ${c.size}v${c.size}, ${c.state.name.lowercase()} ", NamedTextColor.YELLOW)
                .append(if (team.leader == player.uniqueId) button("[Withdraw]", "/ranked challenge cancel", NamedTextColor.RED) else Component.empty()))
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        val player = sender as? Player ?: return emptyList()
        if (!queue.hosted || !player.hasPermission(LifestealRankedQueueManager.PERMISSION_USE)) return emptyList()
        fun filter(options: Iterable<String>, typed: String) = options.filter { it.lowercase().startsWith(typed.lowercase()) }
        val sizes = queue.allowedSizes.map { "${it}v$it" }
        val team = teams.teamOf(player.uniqueId)
        val mates = team?.members?.map { it.uuid }?.filter { it != player.uniqueId && Bukkit.getPlayer(it)?.isOnline == true }?.map { teams.nameOf(it) }.orEmpty()
        val otherTeams = teams.allTeams().filter { it.id != team?.id }.map { it.name }
        val first = args.getOrNull(0)?.lowercase()
        return when {
            args.size == 1 -> filter(SUBS, args[0])
            (first == "stats" || first == "history") && args.size == 2 && player.hasPermission(LifestealRatingManager.PERMISSION_STATS_OTHERS) ->
                filter(Bukkit.getOnlinePlayers().map { it.name }, args[1])
            first == "top" && args.size == 2 -> filter(listOf("players", "teams"), args[1])
            first == "teamstats" && args.size == 2 -> filter(teams.allTeams().map { it.name }, args[1])
            first == "queue" && args.size == 2 -> filter(sizes, args[1])
            first == "queue" && args.size > 2 -> filter(mates - args.drop(2).toSet(), args.last())
            first == "challenge" && args.size == 2 -> filter(listOf("accept", "decline", "cancel") + otherTeams, args[1])
            first == "challenge" && args[1].lowercase() in setOf("accept", "decline") && args.size == 3 ->
                filter(team?.let { t -> queue.incomingChallenges(t.id).map { it.fromTeamName } }.orEmpty(), args[2])
            first == "challenge" && args[1].lowercase() == "accept" && args.size > 3 -> filter(mates - args.drop(3).toSet(), args.last())
            first == "challenge" && args[1].lowercase() !in setOf("accept", "decline", "cancel") && args.size == 3 -> filter(sizes, args[2])
            first == "challenge" && args[1].lowercase() !in setOf("accept", "decline", "cancel") && args.size > 3 -> filter(mates - args.drop(3).toSet(), args.last())
            else -> emptyList()
        }
    }

    companion object {
        const val USAGE = "/ranked [queue <size> [teammates]|leave|status|accept|decline|challenge <team> <size> [teammates]|challenge accept|decline <team>|challenge cancel|challenges|stats [player]|rating|history [player] [page]|top [players|teams] [page]|teamstats [team]|divisions|season]"
        private val SUBS = listOf("queue", "leave", "status", "accept", "decline", "challenge", "challenges", "stats", "rating", "history", "top", "teamstats", "divisions", "season")
    }
}
