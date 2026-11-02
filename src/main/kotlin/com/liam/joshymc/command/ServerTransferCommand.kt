package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.lifesteal.combat.LifestealCombatCommand
import com.liam.joshymc.lifesteal.hearts.LifestealHeartsCommand
import com.liam.joshymc.lifesteal.progression.LifestealProgressCommand
import com.liam.joshymc.network.transfer.TransferDestination
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

/**
 * `/lobby` (`/hub`), `/survival`, `/lifesteal` (issue #1331): sends the player to that network
 * server through the proxy. One instance per command; every check lives in the TransferManager.
 *
 * `/lifesteal hearts ...` (issue #1341) is the admin heart command, handled by
 * [LifestealHeartsCommand], and `/lifesteal combat ...` (issue #1350) the read-only combat-log
 * diagnostics, [LifestealCombatCommand]. `/lifesteal progress` and `/lifesteal admin launch ...` (issue
 * #1352) are the launch progression, [LifestealProgressCommand]; `progress` is only taken on a server
 * that hosts Lifesteal. Plain `/lifesteal` (and any other arguments) still transfers.
 */
class ServerTransferCommand(
    private val plugin: Joshymc,
    private val destination: TransferDestination,
) : CommandExecutor, TabCompleter {

    private val hearts = if (destination == TransferDestination.LIFESTEAL) LifestealHeartsCommand(plugin) else null
    private val combat = if (destination == TransferDestination.LIFESTEAL) LifestealCombatCommand(plugin) else null
    private val progression = if (destination == TransferDestination.LIFESTEAL) LifestealProgressCommand(plugin) else null

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (hearts != null && args.firstOrNull()?.equals("hearts", ignoreCase = true) == true) {
            hearts.execute(sender, args.drop(1))
            return true
        }
        if (combat != null && args.firstOrNull()?.equals("combat", ignoreCase = true) == true) {
            combat.execute(sender, args.drop(1))
            return true
        }
        if (progression != null) {
            val first = args.firstOrNull()?.lowercase()
            if (first == "admin") {
                progression.admin(sender, args.drop(1))
                return true
            }
            // Only where Lifesteal runs: elsewhere `/lifesteal progress` keeps transferring.
            if (first == "progress" && args.size == 1 && plugin.lifestealProgressionManager.hosted) {
                progression.progress(sender)
                return true
            }
        }
        plugin.transferManager.request(sender, destination)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> {
        if (hearts == null || combat == null || progression == null || args.isEmpty()) return emptyList()
        val visible = buildList {
            if (hearts.visibleTo(sender)) add("hearts")
            if (combat.visibleTo(sender)) add("combat")
            if (progression.progressVisibleTo(sender)) add("progress")
            if (progression.adminVisibleTo(sender)) add("admin")
        }
        if (args.size == 1) return visible.filter { it.startsWith(args[0].lowercase()) }
        return when (args[0].lowercase()) {
            "hearts" -> if ("hearts" in visible) hearts.complete(sender, args.drop(1)) else emptyList()
            "combat" -> if ("combat" in visible) combat.complete(sender, args.drop(1)) else emptyList()
            "admin" -> if ("admin" in visible) progression.complete(sender, args.drop(1)) else emptyList()
            else -> emptyList()
        }
    }
}
