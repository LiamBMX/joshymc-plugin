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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.UUID

// ── /ban ────────────────────────────────────────────────────

class BanCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.ban")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /ban <player> [reason]", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val reason = if (args.size > 1) args.drop(1).joinToString(" ") else null
        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        plugin.punishmentManager.ban(target.first, target.second, punisherName, punisherUuid, reason)

        // Kick if online
        val online = Bukkit.getPlayer(target.first)
        online?.kick(buildBanKickMessage(reason, null))

        val msg = Component.text("${target.second} has been permanently banned", NamedTextColor.RED)
            .let { if (reason != null) it.append(Component.text(" - $reason", NamedTextColor.GRAY)) else it }
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── /tempban ────────────────────────────────────────────────

class TempbanCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.tempban")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /tempban <player> <duration> [reason]", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val durationMs = PunishmentManager.parseDuration(args[1])
        if (durationMs == null) {
            sender.sendMessage(Component.text("Invalid duration: ${args[1]} (e.g. 1d, 2h30m, 7d)", NamedTextColor.RED))
            return true
        }

        val reason = if (args.size > 2) args.drop(2).joinToString(" ") else null
        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        plugin.punishmentManager.tempban(target.first, target.second, punisherName, punisherUuid, reason, durationMs)

        val online = Bukkit.getPlayer(target.first)
        online?.kick(buildBanKickMessage(reason, durationMs))

        val durationStr = PunishmentManager.formatDuration(durationMs)
        val msg = Component.text("${target.second} has been banned for $durationStr", NamedTextColor.RED)
            .let { if (reason != null) it.append(Component.text(" - $reason", NamedTextColor.GRAY)) else it }
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> onlinePlayerNames(args[0])
            2 -> listOf("1h", "6h", "12h", "1d", "3d", "7d", "30d").filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }
    }
}

// ── /unban ──────────────────────────────────────────────────

class UnbanCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.unban")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /unban <player>", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        plugin.punishmentManager.unban(target.first)
        val msg = Component.text("${target.second} has been unbanned.", NamedTextColor.GREEN)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── /mute ───────────────────────────────────────────────────

class MuteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.mute")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /mute <player> [reason]", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val reason = if (args.size > 1) args.drop(1).joinToString(" ") else null
        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        plugin.punishmentManager.mute(target.first, target.second, punisherName, punisherUuid, reason)

        val msg = Component.text("${target.second} has been permanently muted", NamedTextColor.RED)
            .let { if (reason != null) it.append(Component.text(" - $reason", NamedTextColor.GRAY)) else it }
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        // Notify target if online
        val online = Bukkit.getPlayer(target.first)
        if (online != null) {
            plugin.commsManager.send(online, Component.text("You have been muted!", NamedTextColor.RED)
                .let { if (reason != null) it.append(Component.text(" Reason: $reason", NamedTextColor.GRAY)) else it },
                CommunicationsManager.Category.ADMIN
            )
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── /tempmute ───────────────────────────────────────────────

class TempmuteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.tempmute")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /tempmute <player> <duration> [reason]", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val durationMs = PunishmentManager.parseDuration(args[1])
        if (durationMs == null) {
            sender.sendMessage(Component.text("Invalid duration: ${args[1]} (e.g. 1d, 2h30m, 7d)", NamedTextColor.RED))
            return true
        }

        val reason = if (args.size > 2) args.drop(2).joinToString(" ") else null
        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        plugin.punishmentManager.tempmute(target.first, target.second, punisherName, punisherUuid, reason, durationMs)

        val durationStr = PunishmentManager.formatDuration(durationMs)
        val msg = Component.text("${target.second} has been muted for $durationStr", NamedTextColor.RED)
            .let { if (reason != null) it.append(Component.text(" - $reason", NamedTextColor.GRAY)) else it }
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        // Notify target if online
        val online = Bukkit.getPlayer(target.first)
        if (online != null) {
            plugin.commsManager.send(online, Component.text("You have been muted for $durationStr!", NamedTextColor.RED)
                .let { if (reason != null) it.append(Component.text(" Reason: $reason", NamedTextColor.GRAY)) else it },
                CommunicationsManager.Category.ADMIN
            )
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> onlinePlayerNames(args[0])
            2 -> listOf("5m", "15m", "30m", "1h", "6h", "12h", "1d", "7d").filter { it.startsWith(args[1], ignoreCase = true) }
            else -> emptyList()
        }
    }
}

// ── /unmute ─────────────────────────────────────────────────

class UnmuteCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.unmute")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /unmute <player>", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        plugin.punishmentManager.unmute(target.first)
        val msg = Component.text("${target.second} has been unmuted.", NamedTextColor.GREEN)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        // Notify target if online
        val online = Bukkit.getPlayer(target.first)
        if (online != null) {
            plugin.commsManager.send(online, Component.text("You have been unmuted.", NamedTextColor.GREEN),
                CommunicationsManager.Category.ADMIN
            )
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── /warn ───────────────────────────────────────────────────

class WarnCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.warn")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /warn <player> [reason]", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val reason = if (args.size > 1) args.drop(1).joinToString(" ") else null
        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        plugin.punishmentManager.warn(target.first, target.second, punisherName, punisherUuid, reason)

        val warnings = plugin.punishmentManager.getWarnings(target.first)
        val msg = Component.text("${target.second} has been warned", NamedTextColor.YELLOW)
            .append(Component.text(" (${warnings.size} total)", NamedTextColor.GRAY))
            .let { if (reason != null) it.append(Component.text(" - $reason", NamedTextColor.GRAY)) else it }
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        // Notify target if online
        val online = Bukkit.getPlayer(target.first)
        if (online != null) {
            plugin.commsManager.send(online, Component.text("You have been warned!", NamedTextColor.YELLOW)
                .let { if (reason != null) it.append(Component.text(" Reason: $reason", NamedTextColor.GRAY)) else it },
                CommunicationsManager.Category.ADMIN
            )
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── /kick ───────────────────────────────────────────────────

class KickCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.kick")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /kick <player> [reason]", NamedTextColor.RED))
            return true
        }

        val online = Bukkit.getPlayer(args[0])
        if (online == null) {
            sender.sendMessage(Component.text("Player not online: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val reason = if (args.size > 1) args.drop(1).joinToString(" ") else null
        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        // Log the kick in history
        plugin.punishmentManager.kick(online.uniqueId, online.name, punisherName, punisherUuid, reason)

        // Build kick message
        val kickMsg = Component.text()
            .append(Component.text("Kicked by $punisherName", NamedTextColor.RED))
        if (reason != null) {
            kickMsg.append(Component.newline())
                .append(Component.text(reason, NamedTextColor.GRAY))
        }
        online.kick(kickMsg.build())

        val msg = Component.text("${online.name} has been kicked", NamedTextColor.RED)
            .let { if (reason != null) it.append(Component.text(" - $reason", NamedTextColor.GRAY)) else it }
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── /history ────────────────────────────────────────────────

class HistoryCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.history")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }
        if (args.isEmpty()) {
            sender.sendMessage(Component.text("Usage: /history <player>", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }

        val history = plugin.punishmentManager.getHistory(target.first)
        if (history.isEmpty()) {
            sender.sendMessage(Component.text("${target.second} has no punishment history.", NamedTextColor.GRAY))
            return true
        }

        sender.sendMessage(Component.text("--- Punishment History: ${target.second} (${history.size}) ---", NamedTextColor.GOLD)
            .decoration(TextDecoration.BOLD, true))

        for (record in history) {
            val typeColor = when (record.type) {
                "BAN", "TEMPBAN" -> NamedTextColor.RED
                "MUTE", "TEMPMUTE" -> NamedTextColor.GOLD
                "WARN" -> NamedTextColor.YELLOW
                "KICK" -> NamedTextColor.AQUA
                else -> NamedTextColor.GRAY
            }

            val activeIndicator = if (record.active) {
                Component.text(" [ACTIVE]", NamedTextColor.GREEN)
            } else {
                Component.text(" [EXPIRED]", NamedTextColor.DARK_GRAY)
            }

            val line = Component.text("#${record.id} ", NamedTextColor.GRAY)
                .append(Component.text(record.type, typeColor).decoration(TextDecoration.BOLD, true))
                .append(activeIndicator)
                .append(Component.text(" by ${record.punisherName}", NamedTextColor.GRAY))
                .append(Component.text(" - ${dateFormat.format(Date(record.createdAt))}", NamedTextColor.DARK_GRAY))

            sender.sendMessage(line)

            if (record.reason != null) {
                sender.sendMessage(Component.text("  Reason: ${record.reason}", NamedTextColor.GRAY))
            }
            if (record.expiresAt != null) {
                val remaining = record.expiresAt - System.currentTimeMillis()
                val expiryText = if (remaining > 0 && record.active) {
                    "Expires in ${PunishmentManager.formatDuration(remaining)}"
                } else {
                    "Expired at ${dateFormat.format(Date(record.expiresAt))}"
                }
                sender.sendMessage(Component.text("  $expiryText", NamedTextColor.DARK_GRAY))
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return if (args.size == 1) onlinePlayerNames(args[0]) else emptyList()
    }
}

// ── Shared helpers ──────────────────────────────────────────

/**
 * Resolve a player name to UUID + last known name.
 * Tries online first, then falls back to Bukkit's offline player cache.
 */
private fun resolveOfflinePlayer(name: String): Pair<UUID, String>? {
    // Try online first
    val online = Bukkit.getPlayer(name)
    if (online != null) return online.uniqueId to online.name

    // Fallback to offline player cache (will have UUID if they've joined before)
    @Suppress("DEPRECATION")
    val offline = Bukkit.getOfflinePlayer(name)
    if (offline.hasPlayedBefore() || offline.isOnline) {
        return offline.uniqueId to (offline.name ?: name)
    }

    return null
}

private fun onlinePlayerNames(prefix: String): List<String> {
    return Bukkit.getOnlinePlayers()
        .map { it.name }
        .filter { it.startsWith(prefix, ignoreCase = true) }
}

private fun notifyStaff(sender: CommandSender, message: Component) {
    val prefix = Component.text("[Staff] ", NamedTextColor.DARK_GRAY)
    val full = prefix.append(message)
    for (player in Bukkit.getOnlinePlayers()) {
        if (player.hasPermission("joshymc.punishment.notify") && player != sender) {
            player.sendMessage(full)
        }
    }
}

private fun buildBanKickMessage(reason: String?, durationMs: Long?): Component {
    val msg = Component.text()
        .append(Component.text("You have been banned!", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
        .append(Component.newline())

    if (reason != null) {
        msg.append(Component.newline())
            .append(Component.text("Reason: ", NamedTextColor.GRAY))
            .append(Component.text(reason, NamedTextColor.WHITE))
    }

    msg.append(Component.newline())
    if (durationMs != null) {
        msg.append(Component.text("Duration: ", NamedTextColor.GRAY))
            .append(Component.text(PunishmentManager.formatDuration(durationMs), NamedTextColor.WHITE))
    } else {
        msg.append(Component.text("Duration: ", NamedTextColor.GRAY))
            .append(Component.text("Permanent", NamedTextColor.RED))
    }

    return msg.build()
}
