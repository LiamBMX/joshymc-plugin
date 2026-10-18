package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.recovery.StuckRecoveryManager
import com.liam.joshymc.util.Msg
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/unstuck` (issue #1241): self-service recovery for players frozen midair or in chunks that
 * didn't load. Staff with `joshymc.unstuck.others` can run `/unstuck <player>`: online players
 * are recovered right away, offline ones start at spawn on their next login.
 */
class UnstuckCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val recovery = plugin.stuckRecoveryManager
        if (args.isEmpty()) {
            if (sender !is Player) { Msg.usage(sender, "/unstuck <player>"); return true }
            if (!sender.hasPermission(StuckRecoveryManager.PERMISSION)) { Msg.noPermission(sender); return true }
            recovery.requestUnstuck(sender)
            return true
        }

        if (!sender.hasPermission(StuckRecoveryManager.OTHERS_PERMISSION)) { Msg.noPermission(sender); return true }
        if (args.size != 1) { Msg.usage(sender, "/unstuck [player]"); return true }

        val name = args[0]
        val online = Bukkit.getPlayerExact(name)
        if (online != null) {
            recovery.adminUnstuck(sender, online)
            return true
        }
        val offline = plugin.playerResolver.resolve(name)
        if (offline == null) { Msg.playerNotFound(sender, name); return true }
        val displayName = offline.name ?: name
        if (recovery.queueOfflineRecovery(sender, offline.uniqueId, displayName)) {
            Msg.success(
                sender,
                "$displayName is offline: their next login (within 24h) will start at spawn, unless they log into the event world.",
                CommunicationsManager.Category.TELEPORT,
            )
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (args.size != 1 || !sender.hasPermission(StuckRecoveryManager.OTHERS_PERMISSION)) return emptyList()
        return Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) }
    }
}
