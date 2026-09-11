package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PunishmentManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * Unified `/punish <player> <type> [duration] <reason>` staff command.
 * Each punishment type has its own `joshymc.punish.<type>` permission, checked independently -
 * holding one type's permission does not grant any other. Wraps the same [PunishmentManager]
 * backend the standalone /ban, /mute, /warn, etc. commands use.
 */
class PunishCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        val TYPES = listOf("ban", "tempban", "tempmute", "unban", "unmute", "warn", "unwarn")
        val TEMP_DURATIONS = listOf("30m", "1h", "6h", "12h", "1d", "3d", "7d", "14d", "30d")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /punish <player> <${TYPES.joinToString("|")}> [duration] <reason>", NamedTextColor.RED))
            return true
        }

        val type = args[1].lowercase()
        if (type !in TYPES) {
            sender.sendMessage(Component.text("Unknown punishment type: ${args[1]}. Valid: ${TYPES.joinToString(", ")}", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.punish.$type")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val target = resolveOfflinePlayer(args[0])
        if (target == null) {
            sender.sendMessage(Component.text("Player not found: ${args[0]}", NamedTextColor.RED))
            return true
        }
        val (targetUuid, targetName) = target

        if (sender is Player && sender.uniqueId == targetUuid) {
            sender.sendMessage(Component.text("You cannot punish yourself.", NamedTextColor.RED))
            return true
        }

        if (sender is Player) {
            val senderWeight = plugin.rankManager.getPlayerRankById(sender.uniqueId)?.weight ?: 0
            val targetWeight = plugin.rankManager.getPlayerRankById(targetUuid)?.weight ?: 0
            if (targetWeight > senderWeight) {
                sender.sendMessage(Component.text("You cannot punish a player with a higher rank than you.", NamedTextColor.RED))
                return true
            }
        }

        val punisherUuid = (sender as? Player)?.uniqueId
        val punisherName = sender.name

        return when (type) {
            "ban" -> handleBan(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "tempban" -> handleTempban(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "tempmute" -> handleTempmute(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "unban" -> handleUnban(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "unmute" -> handleUnmute(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "warn" -> handleWarn(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            "unwarn" -> handleUnwarn(sender, args, targetUuid, targetName, punisherUuid, punisherName)
            else -> true
        }
    }

    // ── Type handlers ───────────────────────────────────────────

    private fun handleBan(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> ban <reason>", NamedTextColor.RED))
            return true
        }

        val inserted = plugin.punishmentManager.ban(targetUuid, targetName, punisherName, punisherUuid, reason)
        Bukkit.getPlayer(targetUuid)?.kick(
            plugin.punishmentManager.buildBanMessage(inserted.id, targetUuid, reason, punisherName, null, null)
        )

        val msg = Component.text("$targetName has been permanently banned - $reason", NamedTextColor.RED)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)
        return true
    }

    private fun handleTempban(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /punish <player> tempban <duration> <reason>", NamedTextColor.RED))
            return true
        }
        val durationMs = PunishmentManager.parseDuration(args[2])
        if (durationMs == null) {
            sender.sendMessage(Component.text("Invalid duration: ${args[2]} (e.g. 1d, 2h30m, 7d, 1w, 1mo)", NamedTextColor.RED))
            return true
        }
        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> tempban <duration> <reason>", NamedTextColor.RED))
            return true
        }

        val inserted = plugin.punishmentManager.tempban(targetUuid, targetName, punisherName, punisherUuid, reason, durationMs)
        Bukkit.getPlayer(targetUuid)?.kick(
            plugin.punishmentManager.buildBanMessage(inserted.id, targetUuid, reason, punisherName, durationMs, inserted.expiresAt)
        )

        val durationStr = PunishmentManager.formatDuration(durationMs)
        val msg = Component.text("$targetName has been banned for $durationStr - $reason", NamedTextColor.RED)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)
        return true
    }

    private fun handleTempmute(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /punish <player> tempmute <duration> <reason>", NamedTextColor.RED))
            return true
        }
        val durationMs = PunishmentManager.parseDuration(args[2])
        if (durationMs == null) {
            sender.sendMessage(Component.text("Invalid duration: ${args[2]} (e.g. 1d, 2h30m, 7d, 1w, 1mo)", NamedTextColor.RED))
            return true
        }
        val reason = args.drop(3).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> tempmute <duration> <reason>", NamedTextColor.RED))
            return true
        }

        plugin.punishmentManager.tempmute(targetUuid, targetName, punisherName, punisherUuid, reason, durationMs)

        val durationStr = PunishmentManager.formatDuration(durationMs)
        val msg = Component.text("$targetName has been muted for $durationStr - $reason", NamedTextColor.RED)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(
                online,
                Component.text("You have been muted for $durationStr! Reason: $reason", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
        }
        return true
    }

    private fun handleUnban(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> unban <reason>", NamedTextColor.RED))
            return true
        }
        if (plugin.punishmentManager.isBanned(targetUuid) == null) {
            sender.sendMessage(Component.text("$targetName is not currently banned.", NamedTextColor.RED))
            return true
        }

        plugin.punishmentManager.unban(targetUuid, punisherName, punisherUuid, reason)
        val msg = Component.text("$targetName has been unbanned.", NamedTextColor.GREEN)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)
        return true
    }

    private fun handleUnmute(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> unmute <reason>", NamedTextColor.RED))
            return true
        }
        if (plugin.punishmentManager.isMuted(targetUuid) == null) {
            sender.sendMessage(Component.text("$targetName is not currently muted.", NamedTextColor.RED))
            return true
        }

        plugin.punishmentManager.unmute(targetUuid, punisherName, punisherUuid, reason)
        val msg = Component.text("$targetName has been unmuted.", NamedTextColor.GREEN)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(online, Component.text("You have been unmuted.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    private fun handleWarn(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val reason = args.drop(2).joinToString(" ").trim()
        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> warn <reason>", NamedTextColor.RED))
            return true
        }

        plugin.punishmentManager.warn(targetUuid, targetName, punisherName, punisherUuid, reason)
        val activeWarns = plugin.punishmentManager.getActiveWarnings(targetUuid).size

        val msg = Component.text("$targetName has been warned ($activeWarns active) - $reason", NamedTextColor.YELLOW)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(online, Component.text("You have been warned! Reason: $reason", NamedTextColor.YELLOW), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    private fun handleUnwarn(sender: CommandSender, args: Array<out String>, targetUuid: java.util.UUID, targetName: String, punisherUuid: java.util.UUID?, punisherName: String): Boolean {
        val activeWarnings = plugin.punishmentManager.getActiveWarnings(targetUuid)
        val explicitId = args.getOrNull(2)?.toIntOrNull()?.takeIf { id -> activeWarnings.any { it.id == id } }

        val reasonArgs = if (explicitId != null) args.drop(3) else args.drop(2)
        val reason = reasonArgs.joinToString(" ").trim()

        if (explicitId == null && activeWarnings.size > 1) {
            val ids = activeWarnings.joinToString(", ") { "#${it.id}" }
            sender.sendMessage(Component.text("$targetName has multiple active warnings: $ids", NamedTextColor.YELLOW))
            sender.sendMessage(Component.text("Specify which one: /punish $targetName unwarn <id> <reason>", NamedTextColor.YELLOW))
            return true
        }

        if (reason.isBlank()) {
            sender.sendMessage(Component.text("Usage: /punish <player> unwarn [id] <reason>", NamedTextColor.RED))
            return true
        }

        if (activeWarnings.isEmpty()) {
            sender.sendMessage(Component.text("$targetName has no active warnings.", NamedTextColor.RED))
            return true
        }

        val warnId = explicitId ?: activeWarnings.first().id
        val removed = plugin.punishmentManager.unwarn(targetUuid, warnId, punisherName, punisherUuid, reason)
        if (!removed) {
            sender.sendMessage(Component.text("Warning #$warnId not found for $targetName.", NamedTextColor.RED))
            return true
        }

        val remaining = plugin.punishmentManager.getActiveWarnings(targetUuid).size
        val msg = Component.text("Warning removed from $targetName ($remaining remaining)", NamedTextColor.GREEN)
        sender.sendMessage(msg)
        notifyStaff(sender, msg)

        Bukkit.getPlayer(targetUuid)?.let { online ->
            plugin.commsManager.send(online, Component.text("A warning has been removed from your record.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        }
        return true
    }

    // ── Tab completion ──────────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> onlinePlayerNames(args[0])
            2 -> TYPES.filter { sender.hasPermission("joshymc.punish.$it") && it.startsWith(args[1], ignoreCase = true) }
            3 -> {
                val type = args[1].lowercase()
                when (type) {
                    "tempban", "tempmute" -> TEMP_DURATIONS.filter { it.startsWith(args[2], ignoreCase = true) }
                    "unwarn" -> {
                        val target = resolveOfflinePlayer(args[0]) ?: return emptyList()
                        plugin.punishmentManager.getActiveWarnings(target.first)
                            .map { it.id.toString() }
                            .filter { it.startsWith(args[2]) }
                    }
                    else -> emptyList()
                }
            }
            else -> emptyList()
        }
    }
}
