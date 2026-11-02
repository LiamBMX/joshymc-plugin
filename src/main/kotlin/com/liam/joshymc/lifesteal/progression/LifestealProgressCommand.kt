package com.liam.joshymc.lifesteal.progression

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.progression.LifestealProgressionManager.AdminResult
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Milestone
import com.liam.joshymc.lifesteal.progression.ProgressionRules.Phase
import com.liam.joshymc.lifesteal.progression.ProgressionRules.RankedStatus
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `/lifesteal progress` (players) and `/lifesteal admin launch status|schedule|activate|audit`
 * (staff, [LifestealProgressionManager.ADMIN_PERMISSION]) (issue #1352), subcommands of the
 * existing `/lifesteal` executor like `/lifesteal hearts`. Both only read or go through
 * [LifestealProgressionManager]; nothing here decides progression itself, and there is no bypass
 * or reset command. Changes need a literal `confirm` and are audited.
 */
class LifestealProgressCommand(private val plugin: Joshymc) {

    private val progression get() = plugin.lifestealProgressionManager

    fun progressVisibleTo(sender: CommandSender): Boolean = progression.hosted && sender.hasPermission(LifestealProgressionManager.PROGRESS_PERMISSION)
    fun adminVisibleTo(sender: CommandSender): Boolean = progression.hosted && sender.hasPermission(LifestealProgressionManager.ADMIN_PERMISSION)

    // ── /lifesteal progress ─────────────────────────────────

    fun progress(sender: CommandSender) {
        if (!sender.hasPermission(LifestealProgressionManager.PROGRESS_PERMISSION)) return Msg.noPermission(sender)
        val p = progression.progress()
        val zone = zoneOf(sender)
        sender.sendMessage(Component.text("Lifesteal Progress", NamedTextColor.GOLD, TextDecoration.BOLD))
        line(sender, "Phase", p.phase.label, phaseColor(p.phase))
        when (p.phase) {
            Phase.DISABLED -> {
                line(sender, "Status", "No launch schedule on this server: PvP, the End and Ranked Team Wars aren't held back.", NamedTextColor.GRAY)
                return
            }
            Phase.UNKNOWN -> {
                line(sender, "Status", "The launch schedule can't be read right now. PvP stays off and the End stays closed.", NamedTextColor.RED)
                return
            }
            Phase.NOT_SCHEDULED -> line(sender, "Launch", "Not scheduled yet", NamedTextColor.YELLOW)
            else -> line(sender, "Launch", milestone(p, Milestone.LAUNCH, zone, done = "Launched"), stateColor(p, Milestone.LAUNCH))
        }
        line(sender, "Grace period", when {
            p.isReached(Milestone.GRACE_ENDED) -> "Over, PvP is on"
            p.launched -> "Active, ends ${upcoming(p, Milestone.GRACE_ENDED, zone)}"
            else -> "Starts at launch (${p.schedule?.graceHours ?: 0}h)"
        }, if (p.isReached(Milestone.GRACE_ENDED)) NamedTextColor.GREEN else NamedTextColor.YELLOW)
        val ranked = progression.rankedStatus()
        line(sender, "Ranked Team Wars", when (ranked) {
            RankedStatus.AVAILABLE -> "Unlocked"
            RankedStatus.UNLOCKED_UNAVAILABLE -> "Unlocked by schedule, not available yet"
            RankedStatus.LOCKED -> "Locked, ${upcoming(p, Milestone.RANKED_UNLOCKED, zone)}"
        }, if (ranked == RankedStatus.LOCKED) NamedTextColor.RED else NamedTextColor.GREEN)
        line(sender, "The End", if (p.isReached(Milestone.END_UNLOCKED)) "Unlocked" else "Locked, ${upcoming(p, Milestone.END_UNLOCKED, zone)}",
            if (p.isReached(Milestone.END_UNLOCKED)) NamedTextColor.GREEN else NamedTextColor.RED)
    }

    // ── /lifesteal admin launch ... ─────────────────────────

    /** [args] starts after `admin`. */
    fun admin(sender: CommandSender, args: List<String>) {
        if (!sender.hasPermission(LifestealProgressionManager.ADMIN_PERMISSION)) return Msg.noPermission(sender)
        if (!progression.hosted) return Msg.error(sender, "Launch progression only exists on the Lifesteal server.")
        if (args.firstOrNull()?.lowercase() != "launch") return Msg.usage(sender, ADMIN_USAGE)
        when (args.getOrNull(1)?.lowercase()) {
            "status" -> status(sender)
            "schedule" -> {
                val raw = args.getOrNull(2) ?: return Msg.usage(sender, "/lifesteal admin launch schedule <timestamp, e.g. 2026-12-01T18:00:00Z> [confirm]")
                val at = ProgressionRules.parseTimestamp(raw)
                    ?: return Msg.error(sender, "'$raw' isn't a valid launch time. Use ISO-8601 with an offset, e.g. 2026-12-01T18:00:00Z or 2026-12-01T13:00:00-05:00.")
                report(sender, progression.scheduleLaunch(sender, at, confirmed(args, 3)), "/lifesteal admin launch schedule $raw confirm")
            }
            "activate" -> report(sender, progression.activateLaunch(sender, confirmed(args, 2)), "/lifesteal admin launch activate confirm")
            "audit" -> audit(sender)
            else -> Msg.usage(sender, ADMIN_USAGE)
        }
    }

    private fun confirmed(args: List<String>, index: Int): Boolean =
        args.size == index + 1 && args[index].equals("confirm", ignoreCase = true)

    private fun report(sender: CommandSender, result: AdminResult, confirmCommand: String) {
        when (result) {
            is AdminResult.Done -> {
                Msg.success(sender, result.message)
                status(sender)
            }
            is AdminResult.Refused -> Msg.error(sender, result.reason)
            is AdminResult.NeedsConfirmation -> {
                sender.sendMessage(Component.text(result.preview, NamedTextColor.YELLOW))
                sender.sendMessage(Component.text("Run $confirmCommand to do it.", NamedTextColor.GRAY))
            }
        }
    }

    private fun status(sender: CommandSender) {
        val p = progression.progress()
        sender.sendMessage(Component.text("Lifesteal launch progression", NamedTextColor.GOLD, TextDecoration.BOLD))
        line(sender, "Service", progression.status.label + (progression.problem?.let { " ($it)" } ?: ""),
            if (progression.status == LifestealProgressionManager.Status.READY) NamedTextColor.GREEN else NamedTextColor.RED)
        line(sender, "Phase", p.phase.label, phaseColor(p.phase))
        val season = p.season
        line(sender, "Season", season?.let { "${it.id} (launch ${ProgressionRules.formatUtc(it.launchAt)}, version ${it.version})" } ?: "none yet", NamedTextColor.WHITE)
        p.schedule?.let { line(sender, "Schedule", "grace ${it.graceHours}h, ranked +${it.rankedHours}h, End +${it.endHours}h (hours after launch)", NamedTextColor.WHITE) }
        for (m in Milestone.entries) {
            val at = p.at(m)?.let { ProgressionRules.formatUtc(it) } ?: "-"
            line(sender, "  " + m.label, if (p.isReached(m)) "reached ($at)" else "pending ($at, in ${p.remaining(m)?.let(ProgressionRules::formatRemaining) ?: "?"})",
                if (p.isReached(m)) NamedTextColor.GREEN else NamedTextColor.YELLOW)
        }
        line(sender, "PvP protected", p.pvpProtected.toString(), NamedTextColor.WHITE)
        line(sender, "End locked", p.endLocked.toString() + if (plugin.server.worlds.none { it.environment == org.bukkit.World.Environment.THE_END && progression.isLifesteal(it) }) " (no End world is loaded)" else "", NamedTextColor.WHITE)
        line(sender, "Ranked Team Wars", progression.rankedStatus().label, NamedTextColor.WHITE)
        line(sender, "Heart transfers", p.heartWindow.name, NamedTextColor.WHITE)
    }

    private fun audit(sender: CommandSender) {
        val rows = try {
            progression.recentAudit(10)
        } catch (e: Exception) {
            return Msg.error(sender, "Couldn't read the progression audit: ${e.message}")
        }
        if (rows.isEmpty()) return Msg.reply(sender, Component.text("No progression changes recorded yet.", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Recent progression changes", NamedTextColor.GOLD, TextDecoration.BOLD))
        for (r in rows) {
            val change = listOfNotNull(r.before, r.after).joinToString(" -> ")
            sender.sendMessage(Component.text("${ProgressionRules.formatUtc(r.at)} ${r.seasonId} ${r.action} by ${r.actor} $change ${r.detail ?: ""}".trim(), NamedTextColor.GRAY))
        }
    }

    fun complete(sender: CommandSender, args: List<String>): List<String> {
        if (!adminVisibleTo(sender)) return emptyList()
        return when (args.size) {
            1 -> listOf("launch").filter { it.startsWith(args[0].lowercase()) }
            2 -> if (args[0].equals("launch", true)) LAUNCH_SUBCOMMANDS.filter { it.startsWith(args[1].lowercase()) } else emptyList()
            3 -> when (args[1].lowercase()) {
                "activate" -> listOf("confirm").filter { it.startsWith(args[2].lowercase()) }
                "schedule" -> listOf(DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(Instant.now().epochSecond / 3600 * 3600 + 86_400)))
                else -> emptyList()
            }
            4 -> if (args[1].equals("schedule", true)) listOf("confirm").filter { it.startsWith(args[3].lowercase()) } else emptyList()
            else -> emptyList()
        }
    }

    // ── Formatting ──────────────────────────────────────────

    private fun line(sender: CommandSender, label: String, value: String, color: NamedTextColor) {
        sender.sendMessage(Component.text("$label: ", NamedTextColor.GRAY).append(Component.text(value, color)))
    }

    private fun zoneOf(sender: CommandSender): ZoneId = if (sender is Player) plugin.timezoneManager.zoneFor(sender) else ZoneId.of("UTC")

    private fun local(millis: Long, zone: ZoneId): String = LOCAL_TIME.withZone(zone).format(Instant.ofEpochMilli(millis))

    private fun milestone(p: ProgressionRules.Progress, m: Milestone, zone: ZoneId, done: String): String {
        val at = p.at(m) ?: return "Unknown"
        return if (p.isReached(m)) "$done (${local(at, zone)})" else "in ${ProgressionRules.formatRemaining(p.remaining(m) ?: 0)} (${local(at, zone)})"
    }

    private fun upcoming(p: ProgressionRules.Progress, m: Milestone, zone: ZoneId): String {
        val at = p.at(m) ?: return "unlocks after launch (+${p.schedule?.offsetMillis(m)?.div(ProgressionRules.HOUR_MILLIS) ?: "?"}h)"
        return "in ${ProgressionRules.formatRemaining(p.remaining(m) ?: 0)} (${local(at, zone)})"
    }

    private fun stateColor(p: ProgressionRules.Progress, m: Milestone): NamedTextColor = if (p.isReached(m)) NamedTextColor.GREEN else NamedTextColor.YELLOW

    private fun phaseColor(phase: Phase): NamedTextColor = when (phase) {
        Phase.UNKNOWN -> NamedTextColor.RED
        Phase.NOT_SCHEDULED, Phase.PRE_LAUNCH, Phase.GRACE_PERIOD -> NamedTextColor.YELLOW
        else -> NamedTextColor.GREEN
    }

    companion object {
        const val ADMIN_USAGE = "/lifesteal admin launch <status|schedule <timestamp> [confirm]|activate [confirm]|audit>"
        private val LAUNCH_SUBCOMMANDS = listOf("status", "schedule", "activate", "audit")
        private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm z")
    }
}
