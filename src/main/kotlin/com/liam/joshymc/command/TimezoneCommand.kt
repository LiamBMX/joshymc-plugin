package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.TimezoneManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * `/timezone` shows the current timezone + a sample of the local time.
 * `/timezone <zone>` sets it (accepts IANA names like `America/Los_Angeles`
 *   or shorthand like `PST` / `EST` / `UTC`).
 * `/timezone reset` clears the override and falls back to server time.
 */
class TimezoneCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val sampleFmt = DateTimeFormatter.ofPattern("MM/dd/yyyy hh:mm a")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            showCurrent(sender)
            return true
        }

        when (args[0].lowercase()) {
            "reset", "clear", "default" -> {
                plugin.timezoneManager.clearZone(sender.uniqueId)
                plugin.commsManager.send(
                    sender,
                    Component.text("Timezone reset to server default.", NamedTextColor.GREEN)
                )
            }
            "list" -> showSuggestions(sender)
            else -> {
                val zone = plugin.timezoneManager.parseZone(args.joinToString(" "))
                if (zone == null) {
                    plugin.commsManager.send(
                        sender,
                        Component.text("Unknown timezone: '${args.joinToString(" ")}'", NamedTextColor.RED)
                    )
                    plugin.commsManager.send(
                        sender,
                        Component.text("Try /timezone list — or use IANA names like America/Los_Angeles.", NamedTextColor.GRAY)
                    )
                    return true
                }
                plugin.timezoneManager.setZone(sender.uniqueId, zone)
                val sample = ZonedDateTime.now(zone).format(sampleFmt)
                plugin.commsManager.send(
                    sender,
                    Component.text("Timezone set to ", NamedTextColor.GREEN)
                        .append(Component.text(zone.id, NamedTextColor.GOLD))
                        .append(Component.text("  (it's now ", NamedTextColor.GRAY))
                        .append(Component.text(sample, NamedTextColor.WHITE))
                        .append(Component.text(" there)", NamedTextColor.GRAY))
                )
            }
        }
        return true
    }

    private fun showCurrent(player: Player) {
        val zone = plugin.timezoneManager.zoneFor(player)
        val explicit = plugin.timezoneManager.explicitZoneFor(player) != null
        val sample = ZonedDateTime.now(zone).format(sampleFmt)

        plugin.commsManager.send(
            player,
            Component.text("Your timezone: ", NamedTextColor.GRAY)
                .append(Component.text(zone.id, NamedTextColor.GOLD))
                .append(Component.text(if (explicit) "" else "  (default — EST)", NamedTextColor.DARK_GRAY))
        )
        plugin.commsManager.send(
            player,
            Component.text("Local time: ", NamedTextColor.GRAY)
                .append(Component.text(sample, NamedTextColor.WHITE))
        )
        plugin.commsManager.send(
            player,
            Component.text("/timezone <zone>", NamedTextColor.YELLOW)
                .append(Component.text(" to change, ", NamedTextColor.GRAY))
                .append(Component.text("/timezone reset", NamedTextColor.YELLOW))
                .append(Component.text(" to clear.", NamedTextColor.GRAY))
        )
    }

    private fun showSuggestions(player: Player) {
        plugin.commsManager.send(player, Component.text("Common timezones:", NamedTextColor.GOLD))
        for (z in TimezoneManager.SUGGESTED_ZONES) {
            player.sendMessage(
                Component.text("  ", NamedTextColor.GRAY)
                    .append(Component.text(z, NamedTextColor.YELLOW))
            )
        }
        plugin.commsManager.send(
            player,
            Component.text("Full list: any IANA zone (e.g. ", NamedTextColor.GRAY)
                .append(Component.text("America/Los_Angeles", NamedTextColor.WHITE))
                .append(Component.text(") or shorthand (PST, EST, UTC, …).", NamedTextColor.GRAY))
        )
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        if (args.size != 1) return emptyList()
        val prefix = args[0]
        val pool = (TimezoneManager.SUGGESTED_ZONES + listOf("reset", "list")).toMutableList()
        return pool
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .take(20)
    }
}
