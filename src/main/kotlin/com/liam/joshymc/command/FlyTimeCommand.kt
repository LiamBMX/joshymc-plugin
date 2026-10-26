package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.flight.FlightTime
import com.liam.joshymc.flight.SurvivalFlight
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Duration

/**
 * `/flytime` (issue #1303, Survival only): how much `/fly` access the player has left.
 * Read-only: it never touches flight, permissions or ranks. See [FlightTime], and
 * [SurvivalFlight] for Fly Voucher time (issue #1304).
 */
class FlyTimeCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission("joshymc.flytime.use")) {
            Msg.noPermission(sender)
            return true
        }
        if (plugin.gamemodeLockManager.gamemodeOf(sender) != ServerGamemode.SURVIVAL) {
            Msg.error(sender, "/flytime is only available in Survival.")
            return true
        }

        val now = System.currentTimeMillis()
        val access = plugin.flightTimeManager.luckPermsAccess(sender)
        val survival = plugin.flightTimeManager.phase(sender.uniqueId, now)
        // Issue #1304: Survival flight time (Fly Vouchers) counts too. Permanent always wins;
        // otherwise the longer of it and a LuckPerms timed node. The landing grace is shown
        // apart from the balance, which stays 0s.
        val value = when {
            access == FlightTime.Access.Permanent -> Component.text("Permanent", NamedTextColor.GREEN)
            survival is SurvivalFlight.Phase.Active || access is FlightTime.Access.Temporary -> {
                val voucherLeft = (survival as? SurvivalFlight.Phase.Active)?.remainingMillis ?: 0L
                val luckPermsLeft = (access as? FlightTime.Access.Temporary)?.remaining ?: Duration.ZERO
                Component.text(FlightTime.format(maxOf(Duration.ofMillis(voucherLeft), luckPermsLeft)), NamedTextColor.YELLOW)
            }
            survival is SurvivalFlight.Phase.Grace -> {
                plugin.commsManager.send(
                    sender,
                    Component.text("Flight Time Remaining: ", NamedTextColor.GRAY).append(Component.text("0s", NamedTextColor.RED))
                        .append(Component.newline())
                        .append(Component.text("Flight will be disabled in ", NamedTextColor.YELLOW))
                        .append(Component.text("${SurvivalFlight.ceilSeconds(survival.graceLeftMillis)}s", NamedTextColor.WHITE))
                        .append(Component.text(".", NamedTextColor.YELLOW))
                )
                return true
            }
            else -> {
                Msg.error(sender, "You do not have any flight time available.")
                return true
            }
        }
        plugin.commsManager.send(sender, Component.text("Flight Time Remaining: ", NamedTextColor.GRAY).append(value))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()
}
