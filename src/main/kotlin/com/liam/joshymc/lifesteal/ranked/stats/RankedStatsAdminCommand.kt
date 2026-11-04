package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender

/**
 * `/lifesteal admin ranked stats ...` (issue #1367, `joshymc.lifesteal.ranked.admin`): read-only
 * diagnostics of the leaderboards, divisions and statistics.
 *
 * - `player <player>` / `team <team name|team id>`: statistics, division, place and eligibility
 *   (a disbanded team by its id, with its kept records).
 * - `consistency [season]`: compares every rating row with its rating history and lists rating
 *   history without a RATED result (and the reverse). Statistics are derived from that history, so
 *   there is nothing to rebuild; a reported problem is for staff to investigate, and nothing here
 *   writes. Ratings can only change through the rating transaction (database triggers).
 * - `divisions`: the loaded division table and any configuration problem.
 *
 * There is deliberately no command that awards, sets or edits a rating, a division or a statistic.
 */
class RankedStatsAdminCommand(private val plugin: Joshymc) {

    private val stats get() = plugin.lifestealRankedStatsManager

    fun execute(sender: CommandSender, a: List<String>) {
        when (a.firstOrNull()?.lowercase()) {
            "player" -> {
                val name = a.getOrNull(1) ?: return Msg.usage(sender, "/lifesteal admin ranked stats player <player>")
                val found = plugin.playerResolver.resolve(name) ?: return Msg.playerNotFound(sender, name)
                player(sender, found.uniqueId, found.name ?: name)
            }
            "team" -> {
                val raw = a.getOrNull(1) ?: return Msg.usage(sender, "/lifesteal admin ranked stats team <team name|team id>")
                team(sender, plugin.lifestealTeamManager.teamByName(raw)?.id ?: raw)
            }
            "consistency", "check" -> consistency(sender, a.getOrNull(1))
            "divisions" -> divisions(sender)
            else -> Msg.usage(sender, USAGE)
        }
    }

    private fun player(sender: CommandSender, uuid: java.util.UUID, shown: String) {
        stats.loadPlayerStats(uuid, 3) { s, problem ->
            if (s == null) return@loadPlayerStats Msg.error(sender, problem ?: "Unavailable.")
            val sum = s.summary
            header(sender, "Ranked statistics of $shown")
            line(sender, "UUID", "$uuid${if (uuid.version() == 0) " (Floodgate/Bedrock)" else ""}")
            line(sender, "Season", s.seasonId)
            line(sender, "Player Elo", "${sum.current} (peak ${sum.peak}, lowest ${sum.lowest}, +${sum.gained}/-${sum.lost})")
            line(sender, "Division", stats.division(RatingType.PLAYER_ELO, sum.current)?.displayName ?: "unavailable (${stats.divisionStatus.label})")
            line(sender, "Record", "${sum.matches} rated: W ${sum.wins} / L ${sum.losses} / D ${sum.draws}, ${sum.winPercent}, streak ${sum.currentStreak} (best ${sum.bestStreak})")
            line(sender, "Leaderboard", eligibility(s.position, s.minimumMatches, sum.matches))
            line(sender, "Team", s.teamName?.let { "$it (${s.teamId}), Team Elo ${s.teamRating}" } ?: "none")
            for (l in s.recent) line(sender, "  ${l.matchId}", "${l.outcome.label} vs ${l.opponentTeamName}, ${l.kind}, ${l.rated.label}${l.playerDelta?.let { ", Player ${it}" } ?: ""}")
        }
    }

    private fun team(sender: CommandSender, teamId: String) {
        stats.loadTeamStats(teamId, 3) { s, problem ->
            if (s == null) return@loadTeamStats Msg.error(sender, problem ?: "Unavailable.")
            val sum = s.summary
            header(sender, "Ranked statistics of team ${s.name}")
            line(sender, "Id", s.teamId)
            line(sender, "Status", if (s.active) "active" else "disbanded: records kept, never ranked", if (s.active) NamedTextColor.GREEN else NamedTextColor.YELLOW)
            line(sender, "Leader", s.leader?.let { plugin.lifestealTeamManager.nameOf(it) } ?: "-")
            line(sender, "Season", s.seasonId)
            line(sender, "Team Elo", "${sum.current} (peak ${sum.peak}, lowest ${sum.lowest})")
            line(sender, "Division", stats.division(RatingType.TEAM_ELO, sum.current)?.displayName ?: "unavailable (${stats.divisionStatus.label})")
            line(sender, "Record", "${sum.matches} rated: W ${sum.wins} / L ${sum.losses} / D ${sum.draws}, ${sum.winPercent}, streak ${sum.currentStreak} (best ${sum.bestStreak})")
            line(sender, "Leaderboard", if (!s.active) "not listed (disbanded)" else eligibility(s.position, s.minimumMatches, sum.matches))
            for (l in s.recent) line(sender, "  ${l.matchId}", "${l.outcome.label} vs ${l.opponentTeamName}, ${l.kind}, ${l.rated.label}${l.teamDelta?.let { ", Team $it" } ?: ""}")
        }
    }

    private fun eligibility(position: Int?, minimum: Int?, matches: Int): String = when {
        minimum == null -> "leaderboards unavailable (lifesteal.yml)"
        position != null -> "#$position (eligible: $matches/$minimum)"
        else -> "not listed: $matches/$minimum rated matches"
    }

    private fun consistency(sender: CommandSender, seasonArg: String?) {
        val season = seasonArg ?: plugin.lifestealRatingManager.currentSeason() ?: return Msg.error(sender, "No active season; name one: /lifesteal admin ranked stats consistency <season>.")
        plugin.logger.info("[RankedStats] ${sender.name} ran the read-only statistics consistency check for $season.")
        stats.read({ s -> s.consistency(season, 20) }) { problems, error ->
            if (problems == null) return@read Msg.error(sender, error ?: "Unavailable.")
            header(sender, "Ranked statistics consistency ($season)")
            if (problems.isEmpty()) {
                sender.sendMessage(Component.text("Consistent: every rating matches its history and every rated match has history.", NamedTextColor.GREEN))
                return@read
            }
            plugin.logger.warning("[RankedStats] Consistency check of $season found ${problems.size} problem(s): ${problems.joinToString(" | ")}")
            for (p in problems) sender.sendMessage(Component.text("  $p", NamedTextColor.RED))
            sender.sendMessage(Component.text("Nothing was changed. Ratings only change through the rating transaction; see docs/lifesteal-ranked-stats.md.", NamedTextColor.GRAY))
        }
    }

    private fun divisions(sender: CommandSender) {
        val result = plugin.lifestealConfigManager.divisions
        header(sender, "Ranked divisions: ${result.status.label}")
        result.table?.divisions?.forEach { d ->
            sender.sendMessage(LifestealRankedStatsManager.label(d).append(Component.text("  player ${d.playerMinimum}+, team ${d.teamMinimum}+, icon ${d.icon ?: "-"}", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)))
        }
        for (p in result.problems + plugin.lifestealConfigManager.leaderboards.problems) sender.sendMessage(Component.text("  ${p.message}", if (p.severe) NamedTextColor.RED else NamedTextColor.YELLOW))
        val lb = stats.leaderboardSettings
        line(sender, "Leaderboards", lb?.let { "players need ${it.minimumPlayerMatches}, teams ${it.minimumTeamMatches} rated matches; pages cached ${it.cacheSeconds}s" } ?: "unavailable")
    }

    private fun header(sender: CommandSender, text: String) = sender.sendMessage(Component.text(text, NamedTextColor.GOLD, TextDecoration.BOLD))

    private fun line(sender: CommandSender, label: String, value: String, color: NamedTextColor = NamedTextColor.WHITE) {
        sender.sendMessage(Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color)))
    }

    fun complete(args: List<String>): List<String> {
        fun filter(options: List<String>, typed: String) = options.filter { it.lowercase().startsWith(typed.lowercase()) }
        return when (args.size) {
            1 -> filter(SUBS, args[0])
            2 -> when (args[0].lowercase()) {
                "player" -> filter(Bukkit.getOnlinePlayers().map { it.name }, args[1])
                "team" -> filter(plugin.lifestealTeamManager.allTeams().map { it.name }, args[1])
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    companion object {
        const val USAGE = "/lifesteal admin ranked stats <player <player>|team <team|id>|consistency [season]|divisions>"
        private val SUBS = listOf("player", "team", "consistency", "divisions")
    }
}
