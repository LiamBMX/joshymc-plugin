package com.liam.joshymc.lifesteal.ranked.stats

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager
import com.liam.joshymc.lifesteal.ranked.rating.RatingOutcome
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Chat views of `/ranked` (issue #1367): `stats`, `teamstats`, `top players|teams`, `history`,
 * `divisions`, `season`. [com.liam.joshymc.lifesteal.ranked.queue.RankedCommand] parses the
 * arguments and checks `joshymc.lifesteal.ranked.use`; everything here only reads (async, through
 * [LifestealRankedStatsManager]) and prints. Other players need
 * `joshymc.lifesteal.ranked.stats.others` (checked by the caller); other teams are public.
 */
class RankedStatsCommand(private val plugin: Joshymc) {

    private val stats get() = plugin.lifestealRankedStatsManager
    private val teams get() = plugin.lifestealTeamManager

    private fun send(player: Player, c: Component) = plugin.commsManager.send(player, c)
    private fun header(player: Player, s: String) = send(player, Component.text(s, NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
    private fun stat(player: Player, label: String, value: Component) = send(player, Component.text("$label: ", NamedTextColor.GRAY).append(value))
    private fun text(s: String, c: TextColor = NamedTextColor.WHITE) = Component.text(s, c)

    fun stats(player: Player, uuid: UUID, shown: String, full: Boolean) {
        stats.loadPlayerStats(uuid, if (full) 5 else 0) { s, problem ->
            if (!player.isOnline) return@loadPlayerStats
            if (s == null) return@loadPlayerStats Msg.error(player, problem ?: "No ranked statistics.")
            val sum = s.summary
            header(player, "Ranked Team Wars | ${if (uuid == player.uniqueId) "Your" else "$shown's"} statistics")
            stat(player, "Player Elo", text(sum.current.toString()).append(text(if (!sum.rated) " (no rated matches yet)" else "", NamedTextColor.DARK_GRAY)))
            stat(player, "Division", stats.divisionName(RatingType.PLAYER_ELO, sum.current).append(progressSuffix(RatingType.PLAYER_ELO, sum.current)))
            val teamName = s.teamName
            if (teamName == null || s.teamRating == null) stat(player, "Team Elo", text("not in a team", NamedTextColor.DARK_GRAY))
            else stat(player, "Team Elo", text("${s.teamRating} ").append(text("($teamName, ", NamedTextColor.GRAY)).append(stats.divisionName(RatingType.TEAM_ELO, s.teamRating)).append(text(")", NamedTextColor.GRAY)))
            if (!full) return@loadPlayerStats
            stat(player, "Season", text(s.seasonId))
            stat(player, "Ranking", ranking(s.position, s.minimumMatches, sum.matches))
            record(player, sum)
            stat(player, "Elo", text("peak ${sum.peak}, lowest ${sum.lowest}, gained +${sum.gained}, lost -${sum.lost}"))
            stat(player, "Win streak", text("${sum.currentStreak} (best ${sum.bestStreak})"))
            if (s.recent.isNotEmpty()) {
                send(player, text("Recent matches:", NamedTextColor.GRAY))
                for (l in s.recent) send(player, matchLine(player, l, playerView = true))
            }
        }
    }

    fun teamStats(player: Player, teamId: String) {
        stats.loadTeamStats(teamId, 5) { s, problem ->
            if (!player.isOnline) return@loadTeamStats
            if (s == null) return@loadTeamStats Msg.error(player, problem ?: "No ranked statistics.")
            val sum = s.summary
            header(player, "Ranked Team Wars | ${s.name}")
            stat(player, "Leader", text(s.leader?.let { teams.nameOf(it) } ?: "-"))
            stat(player, "Team Elo", text(sum.current.toString()).append(text(if (!sum.rated) " (no rated matches yet)" else "", NamedTextColor.DARK_GRAY)))
            stat(player, "Division", stats.divisionName(RatingType.TEAM_ELO, sum.current).append(progressSuffix(RatingType.TEAM_ELO, sum.current)))
            stat(player, "Season", text(s.seasonId))
            stat(player, "Ranking", ranking(s.position, s.minimumMatches, sum.matches))
            record(player, sum)
            stat(player, "Team Elo range", text("peak ${sum.peak}, lowest ${sum.lowest}"))
            stat(player, "Win streak", text("${sum.currentStreak} (best ${sum.bestStreak})"))
            if (s.recent.isNotEmpty()) {
                send(player, text("Recent matches:", NamedTextColor.GRAY))
                for (l in s.recent) send(player, matchLine(player, l, playerView = false))
            }
        }
    }

    fun top(player: Player, type: RatingType, page: Int) {
        stats.loadLeaderboard(type, page, CHAT_PAGE, player.uniqueId) { board, problem ->
            if (!player.isOnline) return@loadLeaderboard
            if (board == null) return@loadLeaderboard Msg.error(player, problem ?: "Leaderboards are unavailable.")
            val p = board.page
            val players = type == RatingType.PLAYER_ELO
            header(player, "${if (players) "Player" else "Team"} Elo leaderboard | ${p.seasonId} (page ${p.page + 1}/${p.pages})")
            if (p.entries.isEmpty()) send(player, text("Nobody is ranked yet (listed after ${board.minimumMatches} rated matches).", NamedTextColor.GRAY))
            val ownTeam = teams.teamOf(player.uniqueId)?.id
            for (e in p.entries) {
                val mine = if (players) e.subjectId == player.uniqueId.toString() else e.subjectId == ownTeam
                val name = if (players) runCatching { UUID.fromString(e.subjectId) }.getOrNull()?.let { teams.nameOf(it) } ?: "?" else e.name ?: "?"
                send(player, text("#${e.position} ", RankedStatsMenu.positionColor(e.position)).decorate(TextDecoration.BOLD)
                    .append(text(name, if (mine) NamedTextColor.GREEN else NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                    .append(text("  ${e.rating} ", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                    .append(stats.divisionName(type, e.rating))
                    .append(text("  W ${e.wins} / L ${e.losses}, ${e.matches} played, ${e.winPercent}", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)))
            }
            when {
                board.viewerSubject == null -> Unit
                board.viewerPosition != null -> send(player, text("${if (players) "You are" else "Your team is"} #${board.viewerPosition} of ${p.total}.", NamedTextColor.GREEN))
                else -> send(player, text("${if (players) "You aren't" else "Your team isn't"} ranked yet: ${board.viewerMatches}/${board.minimumMatches} rated matches.", NamedTextColor.GRAY))
            }
            val cmd = "/ranked top ${if (players) "players" else "teams"}"
            var nav: Component = Component.empty()
            if (p.page > 0) nav = nav.append(button("[Previous]", "$cmd ${p.page}")).append(text(" "))
            if (p.page < p.pages - 1) nav = nav.append(button("[Next]", "$cmd ${p.page + 2}"))
            if (p.pages > 1) send(player, nav)
        }
    }

    fun history(player: Player, uuid: UUID, shown: String, page: Int) {
        stats.loadPlayerHistory(uuid, page, CHAT_PAGE) { h, problem ->
            if (!player.isOnline) return@loadPlayerHistory
            if (h == null) return@loadPlayerHistory Msg.error(player, problem ?: "No match history.")
            val of = if (uuid == player.uniqueId) "" else " of $shown"
            header(player, "Ranked match history$of (page ${h.page + 1}/${h.pages}, newest first)")
            if (h.lines.isEmpty()) send(player, text("No ranked matches yet.", NamedTextColor.GRAY))
            for (l in h.lines) send(player, matchLine(player, l, playerView = true))
            val base = if (uuid == player.uniqueId) "/ranked history" else "/ranked history $shown"
            var nav: Component = Component.empty()
            if (h.page > 0) nav = nav.append(button("[Previous]", "$base ${h.page}")).append(text(" "))
            if (h.page < h.pages - 1) nav = nav.append(button("[Next]", "$base ${h.page + 2}"))
            if (h.pages > 1) send(player, nav)
        }
    }

    fun divisions(player: Player) {
        val table = stats.divisions ?: return Msg.error(player, if (stats.divisionStatus == DivisionConfig.Status.DISABLED) "Ranked divisions are turned off on this server." else "Ranked divisions are unavailable (lifesteal.yml); see the console.")
        header(player, "Ranked divisions (Player Elo / Team Elo)")
        for (d in table.divisions) {
            send(player, LifestealRankedStatsManager.label(d)
                .append(text("  ${d.playerMinimum}+ / ${d.teamMinimum}+", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
                .append(text(if (d.description.isEmpty()) "" else "  ${d.description}", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false)))
        }
        send(player, text("Divisions follow the current rating. They are titles only: no powers, no rewards.", NamedTextColor.DARK_GRAY))
        stats.loadPlayerStats(player.uniqueId, 0) { s, _ ->
            if (s == null || !player.isOnline) return@loadPlayerStats
            stat(player, "You", stats.divisionName(RatingType.PLAYER_ELO, s.summary.current).append(text(" (${s.summary.current})", NamedTextColor.GRAY)).append(progressSuffix(RatingType.PLAYER_ELO, s.summary.current)))
        }
    }

    fun season(player: Player) {
        header(player, "Lifesteal season")
        for (line in RankedStatsMenu(plugin).seasonLore(player)) send(player, line)
        stats.loadPlayerStats(player.uniqueId, 0) { s, _ ->
            if (s == null || !player.isOnline) return@loadPlayerStats
            stat(player, "Your Player Elo", text(s.summary.current.toString()).append(text(" ")).append(stats.divisionName(RatingType.PLAYER_ELO, s.summary.current)))
            if (s.teamRating != null) stat(player, "Your Team Elo", text("${s.teamRating} ").append(stats.divisionName(RatingType.TEAM_ELO, s.teamRating)).append(text(" (${s.teamName})", NamedTextColor.GRAY)))
        }
    }

    // ── Pieces ──────────────────────────────────────────────

    private fun record(player: Player, s: RankedStatistics.Summary) = stat(player, "Rated matches", text("${s.matches}  ")
        .append(text("W ${s.wins}", NamedTextColor.GREEN)).append(text(" / ", NamedTextColor.GRAY)).append(text("L ${s.losses}", NamedTextColor.RED))
        .append(text(if (s.draws > 0) " / D ${s.draws}" else "", NamedTextColor.GRAY))
        .append(text("  win rate ${s.winPercent}", NamedTextColor.GRAY)))

    private fun ranking(position: Int?, minimum: Int?, matches: Int): Component = when {
        minimum == null -> text("leaderboards unavailable", NamedTextColor.RED)
        position != null -> text("#$position", NamedTextColor.GREEN)
        else -> text("not ranked yet ($matches/$minimum rated matches)", NamedTextColor.GRAY)
    }

    private fun progressSuffix(type: RatingType, rating: Int): Component {
        val p = stats.divisions?.progress(type, rating) ?: return Component.empty()
        val next = p.next ?: return text("  (highest division)", NamedTextColor.GRAY)
        return text("  next: ", NamedTextColor.GRAY).append(LifestealRankedStatsManager.label(next).decoration(TextDecoration.BOLD, false))
            .append(text(" at ${p.required} (${p.remaining} to go)", NamedTextColor.GRAY))
    }

    private fun matchLine(player: Player, l: RankedStatsStore.MatchLine, playerView: Boolean): Component {
        val won = l.outcome == RatingOutcome.WIN
        val date = DATE.withZone(plugin.timezoneManager.zoneFor(player)).format(Instant.ofEpochMilli(l.endedAt))
        var c = text("  ${if (won) "Win" else "Loss"} ", if (won) NamedTextColor.GREEN else NamedTextColor.RED)
            .append(text("vs ${l.opponentTeamName} ", NamedTextColor.WHITE))
            .append(text("${l.size}v${l.size}, ${l.arena}${l.durationMillis?.let { ", " + RankedStatistics.duration(it) } ?: ""}, $date ", NamedTextColor.GRAY))
        c = c.append(when (l.rated) {
            RankedStatsStore.RatedState.RATED -> {
                var r: Component = Component.empty()
                if (playerView) l.playerDelta?.let { r = r.append(text("Player ${LifestealRatingManager.signed(it)} ", LifestealRatingManager.deltaColor(it))) }
                l.teamDelta?.let { r = r.append(text("Team ${LifestealRatingManager.signed(it)}", LifestealRatingManager.deltaColor(it))) }
                r
            }
            RankedStatsStore.RatedState.UNRATED -> text("Unrated${l.unratedReason?.let { " ($it)" } ?: ""}", NamedTextColor.DARK_GRAY)
            RankedStatsStore.RatedState.PENDING -> text("Rating pending", NamedTextColor.YELLOW)
        })
        return c
    }

    private fun button(label: String, command: String): Component =
        Component.text(label, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD).clickEvent(ClickEvent.runCommand(command))

    companion object {
        const val CHAT_PAGE = 10
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
