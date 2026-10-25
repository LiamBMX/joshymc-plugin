package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.flight.FlightTime
import com.liam.joshymc.flight.LuckPermsFlight
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.Instant

/**
 * `/flytime` (issue #1303, Survival only): how much `/fly` access the player has left.
 * Read-only: it never touches flight, permissions or ranks. See [FlightTime].
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

        val grants = try {
            LuckPermsFlight.current()?.flyGrants(sender.uniqueId)
        } catch (e: Exception) {
            plugin.logger.warning("[FlyTime] Couldn't read LuckPerms flight nodes: ${e.message}")
            null
        }
        val value = when (val access = FlightTime.resolve(sender.hasPermission("joshymc.fly"), grants, Instant.now())) {
            FlightTime.Access.None -> {
                Msg.error(sender, "You do not have any flight time available.")
                return true
            }
            FlightTime.Access.Permanent -> Component.text("Permanent", NamedTextColor.GREEN)
            is FlightTime.Access.Temporary -> Component.text(FlightTime.format(access.remaining), NamedTextColor.YELLOW)
        }
        plugin.commsManager.send(sender, Component.text("Flight Time Remaining: ", NamedTextColor.GRAY).append(value))
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, label: String, args: Array<out String>): List<String> = emptyList()
}
