package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.item.impl.VIKING_SET_IDS
import com.liam.joshymc.util.Msg
import com.liam.joshymc.util.giveItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class JoshyCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "give" -> handleGive(sender, args)
            "reload" -> {
                if (args.size >= 2 && args[1].equals("hard", ignoreCase = true)) {
                    handleHardReload(sender)
                } else {
                    handleReload(sender)
                }
            }
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handleGive(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.give")) {
            Msg.noPermission(sender)
            return
        }

        if (args.size < 2) {
            Msg.usage(sender, "/joshymc give <item_id> <amount> [player]")
            return
        }

        val itemId = args[1]

        val amount = if (args.size >= 3) {
            // Out-of-range amounts are refused rather than quietly clamped (issue #1207).
            args[2].toIntOrNull()?.takeIf { it in 1..64 } ?: run {
                sender.sendMessage(Component.text("Amount must be a number between 1 and 64.", NamedTextColor.RED))
                return
            }
        } else {
            1
        }

        // Special "eggs" keyword — give all egg items
        if (itemId.equals("eggs", ignoreCase = true)) {
            val target: Player = if (args.size >= 4) {
                plugin.playerResolver.online(args[3]) ?: run { // exact name only (issue #1170)
                    Msg.playerNotFound(sender, args[3])
                    return
                }
            } else if (sender is Player) {
                sender
            } else {
                sender.sendMessage(Component.text("Specify a player when running from console.", NamedTextColor.RED))
                return
            }

            val eggs = plugin.itemManager.getAllItems().filter { it.id.endsWith("_egg") }
            for (egg in eggs) {
                plugin.giveItemSafely(target, egg.createItemStack(amount))
            }
            sender.sendMessage(
                Component.text("Gave ${eggs.size} eggs (x$amount) to ${target.name}", NamedTextColor.GREEN)
            )
            return
        }

        // Special "viking_set" keyword: all four Viking pieces (issue #1217)
        if (itemId.equals("viking_set", ignoreCase = true)) {
            val target: Player = if (args.size >= 4) {
                plugin.playerResolver.online(args[3]) ?: run {
                    Msg.playerNotFound(sender, args[3])
                    return
                }
            } else if (sender is Player) {
                sender
            } else {
                sender.sendMessage(Component.text("Specify a player when running from console.", NamedTextColor.RED))
                return
            }

            val pieces = VIKING_SET_IDS.mapNotNull { plugin.itemManager.getItem(it) }
            for (piece in pieces) {
                plugin.giveItemSafely(target, piece.createItemStack(amount))
            }
            sender.sendMessage(
                Component.text("Gave the Viking set (x$amount) to ${target.name}", NamedTextColor.GREEN)
            )
            return
        }

        val customItem = plugin.itemManager.getItem(itemId)
        if (customItem == null) {
            sender.sendMessage(Component.text("Unknown item: $itemId", NamedTextColor.RED))
            val available = plugin.itemManager.getAllItems().joinToString(", ") { it.id }
            sender.sendMessage(Component.text("Available: $available", NamedTextColor.GRAY))
            return
        }

        val target: Player = if (args.size >= 4) {
            plugin.playerResolver.online(args[3]) ?: run { // exact name only (issue #1170)
                Msg.playerNotFound(sender, args[3])
                return
            }
        } else if (sender is Player) {
            sender
        } else {
            sender.sendMessage(Component.text("Specify a player when running from console.", NamedTextColor.RED))
            return
        }

        plugin.giveItemSafely(target, customItem.createItemStack(amount))
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(customItem.displayName)
                .append(Component.text(" x$amount to ${target.name}", NamedTextColor.GREEN))
        )
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.reload")) {
            Msg.noPermission(sender)
            return
        }

        sender.sendMessage(Component.text("Reloading JoshyMC...", NamedTextColor.YELLOW))
        // Wrap the reload so a failure inside any manager's stop()/start()
        // doesn't kill the whole server — log a stack trace, restore listeners,
        // and tell the sender exactly what blew up so it can be reported.
        try {
            plugin.reload()
            sender.sendMessage(Component.text("JoshyMC fully reloaded.", NamedTextColor.GREEN))
        } catch (e: Throwable) {
            plugin.logger.severe("[Reload] Failed: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            sender.sendMessage(Component.text(
                "Reload failed: ${e.javaClass.simpleName} — ${e.message}. " +
                "See server console for the full stack trace. " +
                "If features are broken, run /joshymc reload hard or restart the server.",
                NamedTextColor.RED
            ))
        }
    }

    private fun handleHardReload(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.reload")) {
            Msg.noPermission(sender)
            return
        }

        sender.sendMessage(Component.text("Hard reloading — reloading all plugins...", NamedTextColor.YELLOW))
        plugin.server.dispatchCommand(plugin.server.consoleSender, "reload confirm")
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /joshymc give <item_id> <amount> [player]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /joshymc reload", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /joshymc reload hard", NamedTextColor.GRAY))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        return when (args.size) {
            1 -> listOf("give", "reload").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when {
                args[0].equals("give", ignoreCase = true) ->
                    (plugin.itemManager.getAllItems().map { it.id } + listOf("eggs", "viking_set")).filter { it.startsWith(args[1], ignoreCase = true) }
                args[0].equals("reload", ignoreCase = true) ->
                    listOf("hard").filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("give", ignoreCase = true) ->
                    listOf("1", "16", "32", "64").filter { it.startsWith(args[2]) }
                else -> emptyList()
            }
            4 -> when {
                args[0].equals("give", ignoreCase = true) ->
                    plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(args[3], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
