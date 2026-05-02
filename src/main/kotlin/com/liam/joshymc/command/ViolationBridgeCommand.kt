package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

/**
 * `/jmc-violation log <player> <check-name> [vl] [source]`
 *
 * Console-only bridge that lets external anti-cheat plugins (Grim, Vulcan,
 * Matrix, Spartan, anything that supports running console commands on flag)
 * push violations into JoshyMC's admin panel without needing to depend on
 * any of those plugins' internal APIs.
 *
 * Recommended Grim setup — in `plugins/GrimAC/punishments.yml`:
 * ```
 * default:
 *   commands:
 *     1: '[alert]'
 *     5: 'jmc-violation log %player% %check_name% %vl% Grim'
 *     20: 'jmc-violation log %player% %check_name% %vl% Grim kick'
 * ```
 *
 * The same pattern works for Vulcan/Matrix/Spartan — their punishment
 * config sections all support `commands:` on threshold.
 */
class ViolationBridgeCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // Console-only — keeps players from spoofing flags on each other.
        if (sender is org.bukkit.entity.Player && !sender.hasPermission("joshymc.violation.bridge")) {
            sender.sendMessage(Component.text("This command is for console / external AC plugins only.", NamedTextColor.RED))
            return true
        }

        if (args.size < 2 || !args[0].equals("log", ignoreCase = true)) {
            sender.sendMessage(Component.text("Usage: /jmc-violation log <player> <check> [vl] [source]", NamedTextColor.RED))
            return true
        }

        val playerName = args[1]
        val checkName = args.getOrNull(2) ?: "unknown"
        val vl = args.getOrNull(3)?.toDoubleOrNull() ?: 1.0
        val source = args.getOrNull(4) ?: "external"

        val target = plugin.server.getPlayerExact(playerName)
        if (target == null) {
            // Don't error loudly — the AC may have flagged a player who quit
            // mid-flag. Log to console for diagnostics.
            plugin.logger.warning("[ViolationBridge] Skipping flag — player '$playerName' is offline.")
            return true
        }

        plugin.antiCheatManager.recordExternalViolation(target, checkName, vl, source)
        return true
    }
}
