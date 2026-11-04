package com.liam.joshymc.lifesteal.ranked.rating

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.ranked.RankedMatchRules
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `/lifesteal admin ranked rating ...` (issue #1365, `joshymc.lifesteal.ranked.admin`): read-only
 * Elo diagnostics plus `retry`, which only re-runs the normal idempotent rating of a completed
 * match that has no final result. There is deliberately no command that sets or edits a rating.
 * Every read runs off the main thread.
 */
class RatingAdminCommand(private val plugin: Joshymc) {

    private val ratings get() = plugin.lifestealRatingManager

    fun execute(sender: CommandSender, a: List<String>) {
        when (a.firstOrNull()?.lowercase()) {
            "status", null -> status(sender)
            "player" -> {
                val name = a.getOrNull(1) ?: return Msg.usage(sender, "/lifesteal admin ranked rating player <player>")
                val found = plugin.playerResolver.resolve(name) ?: return Msg.playerNotFound(sender, name)
                subject(sender, RatingType.PLAYER_ELO, found.uniqueId.toString(), found.name ?: name)
            }
            "team" -> {
                val raw = a.getOrNull(1) ?: return Msg.usage(sender, "/lifesteal admin ranked rating team <team name|team id>")
                // Active teams by name; a disbanded team (its name may be reused) only by its id.
                val team = plugin.lifestealTeamManager.teamByName(raw)
                subject(sender, RatingType.TEAM_ELO, team?.id ?: raw, team?.name ?: raw)
            }
            "match" -> {
                val id = a.getOrNull(1) ?: return Msg.usage(sender, "/lifesteal admin ranked rating match <match>")
                match(sender, id)
            }
            "failed" -> failed(sender)
            "retry" -> {
                val id = a.getOrNull(1) ?: return Msg.usage(sender, "/lifesteal admin ranked rating retry <match> [confirm]")
                if (!RankedMatchRules.validMatchId(id)) return Msg.error(sender, "'$id' isn't a match id (rtw-...).")
                if (!(a.size == 3 && a[2].equals("confirm", true))) {
                    sender.sendMessage(Component.text("This runs the normal rating of $id again. It changes nothing if the match is already rated, unrated or invalid.", NamedTextColor.YELLOW))
                    sender.sendMessage(Component.text("Run /lifesteal admin ranked rating retry $id confirm to do it.", NamedTextColor.GRAY))
                    return
                }
                when (val r = ratings.retry(id)) {
                    LifestealRatingManager.QueueResult.Queued -> {
                        plugin.logger.warning("[RankedElo] ${sender.name} retried the rating of $id.")
                        Msg.success(sender, "Rating of $id queued. Check it with /lifesteal admin ranked rating match $id.")
                    }
                    is LifestealRatingManager.QueueResult.Refused -> Msg.error(sender, r.reason)
                }
            }
            else -> Msg.usage(sender, USAGE)
        }
    }

    private fun status(sender: CommandSender) {
        val policy = ratings.policy()
        ratings.read({ s -> s.countByStatus() to s.unratedCompleted(10) }) { value, problem ->
            if (value == null) return@read Msg.error(sender, problem ?: "Unavailable.")
            val (counts, unrated) = value
            header(sender, "Ranked Elo")
            line(sender, "State", ratings.unavailableReason() ?: "running", if (ratings.unavailableReason() == null) NamedTextColor.GREEN else NamedTextColor.RED)
            line(sender, "Season", ratings.currentSeason() ?: "none")
            if (policy != null) line(sender, "Settings", "initial ${policy.params.initialPlayer}/${policy.params.initialTeam}, K ${policy.params.kFactor}, bounds ${policy.params.minimum}-${policy.params.maximum}, challenges ${if (policy.rateChallenges) "rated" else "unrated"}, same-opponent ${if (policy.sameOpponentLimit == 0) "off" else "${policy.sameOpponentLimit}/${policy.sameOpponentWindowMillis / 3_600_000L}h"}")
            line(sender, "Results", RatingStore.ResultStatus.entries.joinToString { "${it.name} ${counts[it] ?: 0}" })
            line(sender, "Being rated now", ratings.inFlightCount.toString())
            line(sender, "Completed, no final result", if (unrated.isEmpty()) "none" else unrated.joinToString(), if (unrated.isEmpty()) NamedTextColor.GREEN else NamedTextColor.YELLOW)
            sender.sendMessage(Component.text("They are rated by the 5-minute sweep (FAILED up to ${LifestealRatingManager.MAX_ATTEMPTS} attempts), or: rating retry <match>.", NamedTextColor.DARK_GRAY))
        }
    }

    private fun subject(sender: CommandSender, type: RatingType, subject: String, shown: String) {
        ratings.read({ s -> s.ratingsOf(type, subject) to s.history(type, subject, null, 8) }) { value, problem ->
            if (value == null) return@read Msg.error(sender, problem ?: "Unavailable.")
            val (rows, history) = value
            header(sender, "${type.label} of $shown")
            line(sender, "Id", subject)
            if (type == RatingType.TEAM_ELO) {
                val active = plugin.lifestealTeamManager.team(subject)
                line(sender, "Team", active?.let { "${it.name} (active)" } ?: "not an active team (disbanded or unknown); its history stays")
            }
            if (rows.isEmpty()) line(sender, "Ratings", "no rated match in any season (initial ${ratings.policy()?.params?.initial(type) ?: "?"})")
            for (r in rows) line(sender, "Season ${r.seasonId}", "${r.rating} after ${r.matches} match(es): W ${r.wins} / L ${r.losses}${if (r.draws > 0) " / D ${r.draws}" else ""}, last ${r.lastMatchId ?: "-"} at ${time(r.updatedAt)}")
            for (h in history) sender.sendMessage(historyLine(h))
        }
    }

    private fun match(sender: CommandSender, id: String) {
        ratings.read({ s -> Triple(s.result(id), s.historyOfMatch(id), s.matchRecord(id)) }) { value, problem ->
            if (value == null) return@read Msg.error(sender, problem ?: "Unavailable.")
            val (result, history, record) = value
            header(sender, "Elo of match $id")
            if (record == null) line(sender, "Match", "no such match record", NamedTextColor.RED)
            else line(sender, "Match", "${record.teamAName} vs ${record.teamBName}, ${record.size}v${record.size}, ${record.kind.name} (${RatingRules.Classification.of(record.kind, record.test).label}), ${record.state?.name ?: "?"}, season ${record.seasonId}")
            if (result == null) line(sender, "Rating", "not processed yet", NamedTextColor.YELLOW)
            else {
                line(sender, "Rating", "${result.status?.name ?: "?"}${result.reason?.let { ": $it" } ?: ""}", when (result.status) {
                    RatingStore.ResultStatus.RATED -> NamedTextColor.GREEN
                    RatingStore.ResultStatus.UNRATED -> NamedTextColor.GRAY
                    else -> NamedTextColor.RED
                })
                line(sender, "Transaction", "${result.transactionId}, ${result.attempts} attempt(s), ${time(result.processedAt)}${result.lastError?.let { ", last error: $it" } ?: ""}")
            }
            for (h in history) {
                val who = if (h.type == RatingType.TEAM_ELO) (if (record?.teamAId == h.subjectId) record.teamAName else record?.teamBName ?: h.subjectId)
                else plugin.lifestealTeamManager.nameOf(java.util.UUID.fromString(h.subjectId))
                sender.sendMessage(Component.text("  ${h.type.label} ${h.side.name} $who: ${h.previous} -> ${h.new} (${LifestealRatingManager.signed(h.delta)}), ${h.outcome?.label}, expected ${fmt(h.expected)} (${fmt(h.ownStrength)} vs ${fmt(h.opponentStrength)}, K ${h.kFactor})", NamedTextColor.GRAY))
            }
        }
    }

    private fun failed(sender: CommandSender) {
        ratings.read({ s -> s.results(RatingStore.ResultStatus.FAILED, 10) + s.results(RatingStore.ResultStatus.INVALID, 10) }) { rows, problem ->
            if (rows == null) return@read Msg.error(sender, problem ?: "Unavailable.")
            header(sender, "Failed and invalid ratings")
            if (rows.isEmpty()) sender.sendMessage(Component.text("None.", NamedTextColor.GREEN))
            for (r in rows) sender.sendMessage(Component.text("${r.matchId} ${r.status?.name} ${r.attempts} attempt(s) ${time(r.processedAt)}: ${r.reason ?: r.lastError ?: "?"}", NamedTextColor.RED))
        }
    }

    private fun historyLine(h: RatingStore.HistoryRow) = Component.text(
        "  ${time(h.at)} ${h.seasonId} ${h.matchId} ${h.outcome?.label ?: "?"}: ${h.previous} -> ${h.new} (${LifestealRatingManager.signed(h.delta)}), expected ${fmt(h.expected)}, K ${h.kFactor}",
        NamedTextColor.GRAY,
    )

    private fun header(sender: CommandSender, text: String) = sender.sendMessage(Component.text(text, NamedTextColor.GOLD, TextDecoration.BOLD))

    private fun line(sender: CommandSender, label: String, value: String, color: NamedTextColor = NamedTextColor.WHITE) {
        sender.sendMessage(Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color)))
    }

    private fun fmt(d: Double): String = String.format(Locale.ROOT, "%.3f", d)
    private fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        fun filter(options: List<String>, typed: String) = options.filter { it.lowercase().startsWith(typed.lowercase()) }
        return when (args.size) {
            1 -> filter(SUBS, args[0])
            2 -> when (args[0].lowercase()) {
                "player" -> filter(org.bukkit.Bukkit.getOnlinePlayers().map { it.name }, args[1])
                "team" -> filter(plugin.lifestealTeamManager.allTeams().map { it.name }, args[1])
                else -> emptyList()
            }
            3 -> if (args[0].equals("retry", true)) filter(listOf("confirm"), args[2]) else emptyList()
            else -> emptyList()
        }
    }

    companion object {
        const val USAGE = "/lifesteal admin ranked rating <status|player <player>|team <team|id>|match <match>|failed|retry <match> [confirm]>"
        private val SUBS = listOf("status", "player", "team", "match", "failed", "retry")
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm'Z'").withZone(ZoneOffset.UTC)
    }
}
