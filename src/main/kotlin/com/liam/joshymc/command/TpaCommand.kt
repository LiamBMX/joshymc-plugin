package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
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

/**
 * TPA request types:
 * - TPA: sender wants to teleport TO target
 * - TPAHERE: sender wants target to teleport TO them
 */
enum class TpaType { TPA, TPAHERE }

data class TpaRequest(
    val sender: UUID,
    val target: UUID,
    val type: TpaType,
    val expiresAt: Long
)

// Shared request storage — all TPA commands share this
object TpaRequests {
    val pending = ConcurrentHashMap<UUID, MutableList<TpaRequest>>() // target UUID -> list of incoming requests

    fun addRequest(request: TpaRequest) {
        pending.getOrPut(request.target) { mutableListOf() }.add(request)
    }

    fun getRequests(targetUuid: UUID): List<TpaRequest> {
        val now = System.currentTimeMillis()
        val list = pending[targetUuid] ?: return emptyList()
        list.removeAll { now > it.expiresAt }
        return list.toList()
    }

    fun getRequestFrom(targetUuid: UUID, senderUuid: UUID): TpaRequest? {
        return getRequests(targetUuid).lastOrNull { it.sender == senderUuid }
    }

    fun getLatestRequest(targetUuid: UUID): TpaRequest? {
        return getRequests(targetUuid).lastOrNull()
    }

    fun removeRequest(targetUuid: UUID, senderUuid: UUID) {
        pending[targetUuid]?.removeAll { it.sender == senderUuid }
    }

    fun removeAllRequests(targetUuid: UUID) {
        pending.remove(targetUuid)
    }
}

// ══════════════════════════════════════════════════════════
//  /tpa <player> — request to teleport TO them
// ══════════════════════════════════════════════════════════

class TpaCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        const val EXPIRY_MS = 60_000L // 60 seconds
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.tpa")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /tpa <player>", NamedTextColor.RED))
            return true
        }

        val target = Bukkit.getPlayer(args[0])
        if (target == null || target == sender) {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }

        // Check for existing pending request
        val existing = TpaRequests.getRequestFrom(target.uniqueId, sender.uniqueId)
        if (existing != null) {
            plugin.commsManager.send(sender, Component.text("You already have a pending request to ${target.name}.", NamedTextColor.RED))
            return true
        }

        val request = TpaRequest(sender.uniqueId, target.uniqueId, TpaType.TPA, System.currentTimeMillis() + EXPIRY_MS)
        TpaRequests.addRequest(request)

        plugin.commsManager.send(sender, Component.text("TPA request sent to ${target.name}. Expires in 60 seconds.", NamedTextColor.GREEN))

        target.sendMessage(
            Component.text("${sender.name} wants to teleport to you. ", NamedTextColor.YELLOW)
                .append(
                    Component.text("[Accept]", NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                        .clickEvent(ClickEvent.runCommand("/tpaccept ${sender.name}"))
                )
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(
                    Component.text("[Deny]", NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true)
                        .clickEvent(ClickEvent.runCommand("/tpdeny ${sender.name}"))
                )
        )

        // Schedule expiry cleanup
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            TpaRequests.removeRequest(target.uniqueId, sender.uniqueId)
        }, (EXPIRY_MS / 50)) // Convert ms to ticks

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /tpahere <player> — request them to teleport TO you
// ══════════════════════════════════════════════════════════

class TpaHereCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.tpa")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("Usage: /tpahere <player>", NamedTextColor.RED))
            return true
        }

        val target = Bukkit.getPlayer(args[0])
        if (target == null || target == sender) {
            plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
            return true
        }

        val existing = TpaRequests.getRequestFrom(target.uniqueId, sender.uniqueId)
        if (existing != null) {
            plugin.commsManager.send(sender, Component.text("You already have a pending request to ${target.name}.", NamedTextColor.RED))
            return true
        }

        val request = TpaRequest(sender.uniqueId, target.uniqueId, TpaType.TPAHERE, System.currentTimeMillis() + TpaCommand.EXPIRY_MS)
        TpaRequests.addRequest(request)

        plugin.commsManager.send(sender, Component.text("TPA request sent to ${target.name}. Expires in 60 seconds.", NamedTextColor.GREEN))

        target.sendMessage(
            Component.text("${sender.name} wants you to teleport to them. ", NamedTextColor.YELLOW)
                .append(
                    Component.text("[Accept]", NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                        .clickEvent(ClickEvent.runCommand("/tpaccept ${sender.name}"))
                )
                .append(Component.text(" ", NamedTextColor.GRAY))
                .append(
                    Component.text("[Deny]", NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true)
                        .clickEvent(ClickEvent.runCommand("/tpdeny ${sender.name}"))
                )
        )

        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            TpaRequests.removeRequest(target.uniqueId, sender.uniqueId)
        }, (TpaCommand.EXPIRY_MS / 50))

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /tpaccept [player] — accept a TPA request
// ══════════════════════════════════════════════════════════

class TpAcceptCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }

        // Find the request to accept
        val request: TpaRequest? = if (args.isNotEmpty()) {
            val requester = Bukkit.getPlayer(args[0])
            if (requester == null) {
                plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
            TpaRequests.getRequestFrom(sender.uniqueId, requester.uniqueId)
        } else {
            // Accept most recent request
            TpaRequests.getLatestRequest(sender.uniqueId)
        }

        if (request == null) {
            plugin.commsManager.send(sender, Component.text("No pending TPA requests.", NamedTextColor.RED))
            return true
        }

        val requester = Bukkit.getPlayer(request.sender)
        if (requester == null) {
            plugin.commsManager.send(sender, Component.text("That player is no longer online.", NamedTextColor.RED))
            TpaRequests.removeRequest(sender.uniqueId, request.sender)
            return true
        }

        // Remove the request
        TpaRequests.removeRequest(sender.uniqueId, request.sender)

        // Perform the teleport based on type
        when (request.type) {
            TpaType.TPA -> {
                // Requester teleports to target (sender)
                if (TeleportChecks.checkAndApply(requester, plugin)) {
                    plugin.commsManager.send(sender, Component.text("${requester.name} could not teleport (combat/cooldown/frozen).", NamedTextColor.RED))
                    return true
                }
                plugin.commsManager.send(sender, Component.text("${requester.name} is teleporting to you...", NamedTextColor.GREEN))
                TeleportChecks.teleportWithWarmup(requester, sender.location, plugin)
            }
            TpaType.TPAHERE -> {
                // Target (sender) teleports to requester
                if (TeleportChecks.checkAndApply(sender, plugin)) return true
                plugin.commsManager.send(requester, Component.text("${sender.name} is teleporting to you...", NamedTextColor.GREEN))
                TeleportChecks.teleportWithWarmup(sender, requester.location, plugin)
            }
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        if (args.size == 1) {
            return TpaRequests.getRequests(sender.uniqueId)
                .mapNotNull { Bukkit.getPlayer(it.sender)?.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /tpdeny [player] — deny a TPA request
// ══════════════════════════════════════════════════════════

class TpDenyCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }

        val request: TpaRequest? = if (args.isNotEmpty()) {
            val requester = Bukkit.getPlayer(args[0])
            if (requester == null) {
                plugin.commsManager.send(sender, Component.text("Player not found.", NamedTextColor.RED))
                return true
            }
            TpaRequests.getRequestFrom(sender.uniqueId, requester.uniqueId)
        } else {
            TpaRequests.getLatestRequest(sender.uniqueId)
        }

        if (request == null) {
            plugin.commsManager.send(sender, Component.text("No pending TPA requests.", NamedTextColor.RED))
            return true
        }

        val requester = Bukkit.getPlayer(request.sender)
        TpaRequests.removeRequest(sender.uniqueId, request.sender)

        plugin.commsManager.send(sender, Component.text("TPA request denied.", NamedTextColor.RED))
        requester?.let {
            plugin.commsManager.send(it, Component.text("${sender.name} denied your TPA request.", NamedTextColor.RED))
        }

        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        if (args.size == 1) {
            return TpaRequests.getRequests(sender.uniqueId)
                .mapNotNull { Bukkit.getPlayer(it.sender)?.name }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        return emptyList()
    }
}

// ══════════════════════════════════════════════════════════
//  /tpacancel — cancel your outgoing request
// ══════════════════════════════════════════════════════════

class TpaCancelCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }

        // Find and remove any outgoing request from this player
        var found = false
        for ((targetUuid, requests) in TpaRequests.pending) {
            val removed = requests.removeAll { it.sender == sender.uniqueId }
            if (removed) {
                found = true
                Bukkit.getPlayer(targetUuid)?.let {
                    plugin.commsManager.send(it, Component.text("${sender.name} cancelled their TPA request.", NamedTextColor.GRAY))
                }
            }
        }

        if (found) {
            plugin.commsManager.send(sender, Component.text("TPA request cancelled.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(sender, Component.text("You have no outgoing TPA requests.", NamedTextColor.RED))
        }

        return true
    }
}
