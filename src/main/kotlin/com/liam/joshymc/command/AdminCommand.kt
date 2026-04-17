package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PunishmentManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class AdminCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("This command can only be used by players.", NamedTextColor.RED))
            return true
        }

        if (!plugin.adminManager.hasAnyAdminPermission(sender)) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.adminManager.openMainPanel(sender)
            return true
        }

        when (args[0].lowercase()) {
            "player" -> handlePlayer(sender, args)
            "logs" -> handleLogs(sender, args)
            "freeze" -> handleFreeze(sender, args)
            "snapshot" -> handleSnapshot(sender, args)
            "rollback" -> handleRollback(sender, args)
            "banlist" -> plugin.adminManager.openBanList(sender, 0)
            "mutelist" -> plugin.adminManager.openMuteList(sender, 0)
            "lagclear" -> handleLagClear(sender)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handlePlayer(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /admin player <name>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val targetName = args[1]
        // Try online first, then offline
        val target = Bukkit.getPlayer(targetName) ?: Bukkit.getOfflinePlayer(targetName)

        if (!target.hasPlayedBefore() && !target.isOnline) {
            plugin.commsManager.send(sender, Component.text("Player not found: $targetName", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.adminManager.openPlayerPanel(sender, target)
    }

    private fun handleLogs(sender: Player, args: Array<out String>) {
        if (args.size >= 2) {
            // Logs for a specific player
            val targetName = args[1]
            val target = Bukkit.getOfflinePlayer(targetName)
            if (!target.hasPlayedBefore() && !target.isOnline) {
                plugin.commsManager.send(sender, Component.text("Player not found: $targetName", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
                return
            }

            val logs = plugin.adminManager.getLogsForPlayer(target.uniqueId)
            if (logs.isEmpty()) {
                plugin.commsManager.send(sender, Component.text("No admin logs for $targetName", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
                return
            }

            sender.sendMessage(Component.text("Admin logs for $targetName:", NamedTextColor.YELLOW)
                .decoration(TextDecoration.BOLD, true))
            for (log in logs) {
                val targetLabel = if (log.targetName != null) " -> ${log.targetName}" else ""
                val detailLabel = if (log.details != null) " (${log.details})" else ""
                sender.sendMessage(
                    Component.text(" ${formatTimeAgo(log.timestamp)} ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(log.adminName, NamedTextColor.AQUA))
                        .append(Component.text(" ${log.action}", NamedTextColor.WHITE))
                        .append(Component.text(targetLabel, NamedTextColor.YELLOW))
                        .append(Component.text(detailLabel, NamedTextColor.GRAY))
                )
            }
        } else {
            // Open log viewer GUI
            plugin.adminManager.openLogViewer(sender, 0)
        }
    }

    private fun handleFreeze(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /admin freeze <player>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            plugin.commsManager.send(sender, Component.text("Player not online: ${args[1]}", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val frozen = plugin.adminManager.toggleFreeze(target)
        plugin.adminManager.logAction(sender, if (frozen) "FREEZE" else "UNFREEZE", target)

        if (frozen) {
            plugin.commsManager.send(sender, Component.text("Froze ${target.name}", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(target, Component.text("You have been frozen by an administrator!", NamedTextColor.AQUA), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(sender, Component.text("Unfroze ${target.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(target, Component.text("You have been unfrozen.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleSnapshot(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /admin snapshot <player>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val target = Bukkit.getPlayer(args[1])
        if (target == null) {
            plugin.commsManager.send(sender, Component.text("Player not online: ${args[1]}", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.adminManager.saveSnapshot(target, "manual")
        plugin.adminManager.logAction(sender, "SNAPSHOT", target, "Manual save")
        plugin.commsManager.send(sender, Component.text("Saved inventory snapshot for ${target.name}", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleRollback(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /admin rollback <player>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val targetName = args[1]
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && !target.isOnline) {
            plugin.commsManager.send(sender, Component.text("Player not found: $targetName", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.adminManager.openSnapshotList(sender, target.uniqueId, target.name ?: targetName, 0)
    }

    private fun handleLagClear(sender: Player) {
        if (!sender.hasPermission("joshymc.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }
        plugin.lagCleanerManager.triggerManualClear()
        plugin.adminManager.logAction(sender, "LAGCLEAR")
    }

    private fun sendUsage(sender: Player) {
        sender.sendMessage(Component.text("Admin Commands:", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
        sender.sendMessage(Component.text("  /admin", NamedTextColor.GRAY).append(Component.text(" - Open admin panel", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin player <name>", NamedTextColor.GRAY).append(Component.text(" - Manage a player", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin logs [player]", NamedTextColor.GRAY).append(Component.text(" - View action logs", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin freeze <player>", NamedTextColor.GRAY).append(Component.text(" - Toggle freeze", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin snapshot <player>", NamedTextColor.GRAY).append(Component.text(" - Save inventory snapshot", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin rollback <player>", NamedTextColor.GRAY).append(Component.text(" - Restore inventory", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin banlist", NamedTextColor.GRAY).append(Component.text(" - View ban list", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin mutelist", NamedTextColor.GRAY).append(Component.text(" - View mute list", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /admin lagclear", NamedTextColor.GRAY).append(Component.text(" - Manual ground item clear", NamedTextColor.DARK_GRAY)))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (sender !is Player || !plugin.adminManager.hasAnyAdminPermission(sender)) return emptyList()

        return when (args.size) {
            1 -> listOf("player", "logs", "freeze", "snapshot", "rollback", "banlist", "mutelist", "lagclear")
                .filter { it.startsWith(args[0], ignoreCase = true) }

            2 -> when (args[0].lowercase()) {
                "player", "logs", "freeze", "snapshot", "rollback" ->
                    Bukkit.getOnlinePlayers().map { it.name }
                        .filter { it.startsWith(args[1], ignoreCase = true) }

                else -> emptyList()
            }

            else -> emptyList()
        }
    }

    private fun formatTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 0) return "just now"
        val seconds = diff / 1000
        return when {
            seconds < 60 -> "${seconds}s ago"
            seconds < 3600 -> "${seconds / 60}m ago"
            seconds < 86400 -> "${seconds / 3600}h ago"
            else -> "${seconds / 86400}d ago"
        }
    }
}
