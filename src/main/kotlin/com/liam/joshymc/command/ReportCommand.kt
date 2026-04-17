package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ReportCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val cooldownMillis = 60_000L

    fun createTable() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                reporter_uuid TEXT NOT NULL,
                reporter_name TEXT NOT NULL,
                target_uuid TEXT NOT NULL,
                target_name TEXT NOT NULL,
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                resolved INTEGER DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        when (command.name.lowercase()) {
            "report" -> return handleReport(sender, args)
            "reports" -> return handleReports(sender, args)
        }

        return true
    }

    private fun handleReport(player: Player, args: Array<out String>): Boolean {
        if (!player.hasPermission("joshymc.report")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /report <player> <reason>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        // Cooldown check
        val now = System.currentTimeMillis()
        val lastReport = cooldowns[player.uniqueId]
        if (lastReport != null && (now - lastReport) < cooldownMillis) {
            val remaining = ((cooldownMillis - (now - lastReport)) / 1000).toInt()
            plugin.commsManager.send(player, Component.text("Please wait ${remaining}s before reporting again.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        val target = Bukkit.getPlayer(args[0])
        if (target == null) {
            plugin.commsManager.send(player, Component.text("Player not found or not online.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (target.uniqueId == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You cannot report yourself.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        val reason = args.drop(1).joinToString(" ")

        plugin.databaseManager.execute(
            "INSERT INTO reports (reporter_uuid, reporter_name, target_uuid, target_name, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            player.uniqueId.toString(),
            player.name,
            target.uniqueId.toString(),
            target.name,
            reason,
            now
        )

        cooldowns[player.uniqueId] = now

        plugin.commsManager.send(player, Component.text("Report submitted for ", NamedTextColor.GRAY)
            .append(Component.text(target.name, NamedTextColor.WHITE))
            .append(Component.text(".", NamedTextColor.GRAY)), CommunicationsManager.Category.ADMIN)

        // Notify online staff
        val staffMessage = Component.text("[Report] ", NamedTextColor.RED).decoration(TextDecoration.BOLD, true)
            .append(Component.text(player.name, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
            .append(Component.text(" reported ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
            .append(Component.text(target.name, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, false))
            .append(Component.text(": ", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
            .append(Component.text(reason, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))

        for (online in Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("joshymc.reports.notify")) {
                online.sendMessage(staffMessage)
            }
        }

        return true
    }

    private fun handleReports(player: Player, args: Array<out String>): Boolean {
        if (!player.hasPermission("joshymc.reports.view")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.isNotEmpty()) {
            // Reports for a specific player
            val targetName = args[0]
            val reports = plugin.databaseManager.query(
                "SELECT id, reporter_name, target_name, reason, created_at, resolved FROM reports WHERE target_name = ? ORDER BY created_at DESC LIMIT 10",
                targetName
            ) { rs ->
                ReportEntry(
                    rs.getInt("id"),
                    rs.getString("reporter_name"),
                    rs.getString("target_name"),
                    rs.getString("reason"),
                    rs.getLong("created_at"),
                    rs.getInt("resolved") == 1
                )
            }

            if (reports.isEmpty()) {
                plugin.commsManager.send(player, Component.text("No reports found for $targetName.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
                return true
            }

            player.sendMessage(Component.text("--- Reports for $targetName ---", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
            for (report in reports) {
                sendReportLine(player, report)
            }
        } else {
            // Recent reports
            val reports = plugin.databaseManager.query(
                "SELECT id, reporter_name, target_name, reason, created_at, resolved FROM reports ORDER BY created_at DESC LIMIT 10"
            ) { rs ->
                ReportEntry(
                    rs.getInt("id"),
                    rs.getString("reporter_name"),
                    rs.getString("target_name"),
                    rs.getString("reason"),
                    rs.getLong("created_at"),
                    rs.getInt("resolved") == 1
                )
            }

            if (reports.isEmpty()) {
                plugin.commsManager.send(player, Component.text("No reports found.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
                return true
            }

            player.sendMessage(Component.text("--- Recent Reports ---", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
            for (report in reports) {
                sendReportLine(player, report)
            }
        }

        return true
    }

    private fun sendReportLine(player: Player, report: ReportEntry) {
        val status = if (report.resolved) {
            Component.text("[Resolved] ", NamedTextColor.GREEN)
        } else {
            Component.text("[Open] ", NamedTextColor.RED)
        }

        val ago = formatTimeAgo(System.currentTimeMillis() - report.createdAt)

        val line = Component.text("#${report.id} ", NamedTextColor.GRAY)
            .append(status)
            .append(Component.text(report.reporterName, NamedTextColor.WHITE))
            .append(Component.text(" \u2192 ", NamedTextColor.DARK_GRAY))
            .append(Component.text(report.targetName, NamedTextColor.WHITE))
            .append(Component.text(": ", NamedTextColor.GRAY))
            .append(Component.text(report.reason, NamedTextColor.YELLOW))
            .append(Component.text(" ($ago)", NamedTextColor.DARK_GRAY))

        player.sendMessage(line)
    }

    private fun formatTimeAgo(millis: Long): String {
        val seconds = millis / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return Bukkit.getOnlinePlayers()
                .map { it.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }

    private data class ReportEntry(
        val id: Int,
        val reporterName: String,
        val targetName: String,
        val reason: String,
        val createdAt: Long,
        val resolved: Boolean
    )
}
