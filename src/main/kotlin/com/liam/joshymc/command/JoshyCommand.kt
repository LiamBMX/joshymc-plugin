package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
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
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /joshymc give <item_id> [player]", NamedTextColor.RED))
            return
        }

        val itemId = args[1]

        // Special "eggs" keyword — give all egg items
        if (itemId.equals("eggs", ignoreCase = true)) {
            val target: Player = if (args.size >= 3) {
                plugin.server.getPlayer(args[2]) ?: run {
                    sender.sendMessage(Component.text("Player not found: ${args[2]}", NamedTextColor.RED))
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
                target.inventory.addItem(egg.createItemStack())
            }
            sender.sendMessage(
                Component.text("Gave ${eggs.size} eggs to ${target.name}", NamedTextColor.GREEN)
            )
            return
        }

        // Armor set keywords — give full set at once
        val setMap = mapOf(
            "void_set" to listOf("void_helmet", "void_chestplate", "void_leggings", "void_boots"),
            "inferno_set" to listOf("inferno_helmet", "inferno_chestplate", "inferno_leggings", "inferno_boots"),
            "crystal_set" to listOf("crystal_helmet", "crystal_chestplate", "crystal_leggings", "crystal_boots"),
            "soul_set" to listOf("soul_helmet", "soul_chestplate", "soul_leggings", "soul_boots"),
            "bunny_set" to listOf("bunny_helmet", "bunny_chestplate", "bunny_leggings", "bunny_boots"),
        )
        if (itemId.lowercase() in setMap) {
            val target: Player = if (args.size >= 3) {
                plugin.server.getPlayer(args[2]) ?: run {
                    sender.sendMessage(Component.text("Player not found: ${args[2]}", NamedTextColor.RED))
                    return
                }
            } else if (sender is Player) sender else {
                sender.sendMessage(Component.text("Specify a player.", NamedTextColor.RED)); return
            }
            val ids = setMap[itemId.lowercase()]!!
            for (id in ids) {
                plugin.itemManager.getItem(id)?.let { target.inventory.addItem(it.createItemStack()) }
            }
            sender.sendMessage(Component.text("Gave ${itemId} to ${target.name}", NamedTextColor.GREEN))
            return
        }

        val customItem = plugin.itemManager.getItem(itemId)
        if (customItem == null) {
            sender.sendMessage(Component.text("Unknown item: $itemId", NamedTextColor.RED))
            val available = plugin.itemManager.getAllItems().joinToString(", ") { it.id }
            sender.sendMessage(Component.text("Available: $available", NamedTextColor.GRAY))
            return
        }

        val target: Player = if (args.size >= 3) {
            plugin.server.getPlayer(args[2]) ?: run {
                sender.sendMessage(Component.text("Player not found: ${args[2]}", NamedTextColor.RED))
                return
            }
        } else if (sender is Player) {
            sender
        } else {
            sender.sendMessage(Component.text("Specify a player when running from console.", NamedTextColor.RED))
            return
        }

        target.inventory.addItem(customItem.createItemStack())
        sender.sendMessage(
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(customItem.displayName)
                .append(Component.text(" to ${target.name}", NamedTextColor.GREEN))
        )
    }

    private fun handleReload(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.reload")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("Reloading JoshyMC...", NamedTextColor.YELLOW))
        plugin.reload()
        sender.sendMessage(Component.text("JoshyMC fully reloaded.", NamedTextColor.GREEN))
    }

    private fun handleHardReload(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.reload")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("Hard reloading — reloading all plugins...", NamedTextColor.YELLOW))
        plugin.server.dispatchCommand(plugin.server.consoleSender, "reload confirm")
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("  /joshymc give <item_id> [player]", NamedTextColor.GRAY))
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
                    (plugin.itemManager.getAllItems().map { it.id } + listOf("eggs", "void_set", "inferno_set", "crystal_set", "soul_set", "bunny_set")).filter { it.startsWith(args[1], ignoreCase = true) }
                args[0].equals("reload", ignoreCase = true) ->
                    listOf("hard").filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
