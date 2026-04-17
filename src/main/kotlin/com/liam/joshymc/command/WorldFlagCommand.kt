package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.WorldFlagManager.WorldFlag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class WorldFlagCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.worldflag")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        val sub = args[0].lowercase()

        when (sub) {
            "list" -> handleList(sender, args)
            "reset" -> handleReset(sender, args)
            else -> handleSet(sender, args)
        }

        return true
    }

    // ──────────────────────────────────────────────
    //  /worldflag list [world]
    // ──────────────────────────────────────────────

    private fun handleList(sender: CommandSender, args: Array<out String>) {
        val worldName = resolveWorld(sender, args.getOrNull(1)) ?: return

        val flags = plugin.worldFlagManager.getFlags(worldName)

        sender.sendMessage(
            Component.text("World flags for ", NamedTextColor.GRAY)
                .append(Component.text(worldName, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(":", NamedTextColor.GRAY))
        )

        flags.forEach { (flag, value) ->
            val color = if (value) NamedTextColor.GREEN else NamedTextColor.RED
            val status = if (value) "ALLOWED" else "BLOCKED"

            sender.sendMessage(
                Component.text(" ${flag.displayName} ", NamedTextColor.GRAY)
                    .append(Component.text("[$status]", color))
                    .append(Component.text(" - ${flag.description}", NamedTextColor.DARK_GRAY))
            )
        }
    }

    // ──────────────────────────────────────────────
    //  /worldflag reset [world]
    // ──────────────────────────────────────────────

    private fun handleReset(sender: CommandSender, args: Array<out String>) {
        val worldName = resolveWorld(sender, args.getOrNull(1)) ?: return

        plugin.worldFlagManager.resetFlags(worldName)

        sender.sendMessage(
            Component.text("Reset all flags for ", NamedTextColor.GREEN)
                .append(Component.text(worldName, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(". All flags default to ", NamedTextColor.GREEN))
                .append(Component.text("ALLOWED", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
    }

    // ──────────────────────────────────────────────
    //  /worldflag <flag> <true|false> [world]
    // ──────────────────────────────────────────────

    private fun handleSet(sender: CommandSender, args: Array<out String>) {
        val flagName = args[0].uppercase()
        val flag = WorldFlag.entries.find { it.name == flagName }

        if (flag == null) {
            sender.sendMessage(
                Component.text("Unknown flag '${args[0]}'. Use tab-complete to see available flags.", NamedTextColor.RED)
            )
            return
        }

        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /worldflag ${flag.name.lowercase()} <true|false> [world]", NamedTextColor.RED))
            return
        }

        val value = when (args[1].lowercase()) {
            "true", "allow", "on" -> true
            "false", "deny", "off" -> false
            else -> {
                sender.sendMessage(Component.text("Value must be true or false.", NamedTextColor.RED))
                return
            }
        }

        val worldName = resolveWorld(sender, args.getOrNull(2)) ?: return

        plugin.worldFlagManager.setFlag(worldName, flag, value)

        val color = if (value) NamedTextColor.GREEN else NamedTextColor.RED
        val status = if (value) "ALLOWED" else "BLOCKED"

        sender.sendMessage(
            Component.text("Set ", NamedTextColor.GRAY)
                .append(Component.text(flag.displayName, NamedTextColor.WHITE))
                .append(Component.text(" to ", NamedTextColor.GRAY))
                .append(Component.text(status, color, TextDecoration.BOLD))
                .append(Component.text(" in ", NamedTextColor.GRAY))
                .append(Component.text(worldName, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.GRAY))
        )
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

    private fun resolveWorld(sender: CommandSender, explicit: String?): String? {
        if (explicit != null) return explicit

        if (sender is Player) return sender.world.name

        sender.sendMessage(Component.text("Specify a world name (console must provide one).", NamedTextColor.RED))
        return null
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.GOLD))
        sender.sendMessage(Component.text(" /worldflag <flag> <true|false> [world]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" /worldflag list [world]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" /worldflag reset [world]", NamedTextColor.GRAY))
    }

    // ──────────────────────────────────────────────
    //  Tab Completion
    // ──────────────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.worldflag")) return emptyList()

        return when (args.size) {
            1 -> {
                val options = WorldFlag.entries.map { it.name.lowercase() } + listOf("list", "reset")
                options.filter { it.startsWith(args[0].lowercase()) }
            }
            2 -> {
                val sub = args[0].lowercase()
                if (sub == "list" || sub == "reset") {
                    worldNames(args[1])
                } else {
                    listOf("true", "false").filter { it.startsWith(args[1].lowercase()) }
                }
            }
            3 -> {
                val sub = args[0].lowercase()
                if (sub != "list" && sub != "reset") worldNames(args[2]) else emptyList()
            }
            else -> emptyList()
        }
    }

    private fun worldNames(prefix: String): List<String> {
        return Bukkit.getWorlds().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
    }
}
