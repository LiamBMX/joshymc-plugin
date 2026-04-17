package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CrateCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "setlocation" -> handleSetLocation(sender, args)
            "removelocation" -> handleRemoveLocation(sender)
            "givekey" -> handleGiveKey(sender, args)
            "list" -> handleList(sender)
            "preview" -> handlePreview(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handleSetLocation(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            plugin.commsManager.send(sender as? Player ?: return, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
            return
        }

        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /crate setlocation <type>", NamedTextColor.RED))
            return
        }

        val crateType = args[1].lowercase()
        if (plugin.crateManager.getCrate(crateType) == null) {
            plugin.commsManager.send(player, Component.text("Unknown crate type: $crateType", NamedTextColor.RED))
            val available = plugin.crateManager.getCrateTypes().joinToString(", ")
            plugin.commsManager.send(player, Component.text("Available: $available", NamedTextColor.GRAY))
            return
        }

        val block = player.getTargetBlockExact(5)
        if (block == null || block.type.isAir) {
            plugin.commsManager.send(player, Component.text("Look at a block to set as a crate location.", NamedTextColor.RED))
            return
        }

        if (plugin.crateManager.setCrateLocation(block, crateType)) {
            plugin.commsManager.send(
                player,
                Component.text("Crate location set for ", NamedTextColor.GREEN)
                    .append(Component.text(crateType, TextColor.color(0x55FFFF)))
                    .append(Component.text(" at ${block.x}, ${block.y}, ${block.z}.", NamedTextColor.GREEN))
            )
        } else {
            plugin.commsManager.send(player, Component.text("Failed to set crate location.", NamedTextColor.RED))
        }
    }

    private fun handleRemoveLocation(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            plugin.commsManager.send(sender as? Player ?: return, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
            return
        }

        val block = player.getTargetBlockExact(5)
        if (block == null || block.type.isAir) {
            plugin.commsManager.send(player, Component.text("Look at a crate block to remove it.", NamedTextColor.RED))
            return
        }

        if (plugin.crateManager.removeCrateLocation(block)) {
            plugin.commsManager.send(
                player,
                Component.text("Crate location removed at ${block.x}, ${block.y}, ${block.z}.", NamedTextColor.GREEN)
            )
        } else {
            plugin.commsManager.send(player, Component.text("No crate at that location.", NamedTextColor.RED))
        }
    }

    private fun handleGiveKey(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            if (sender is Player) {
                plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            }
            return
        }

        if (args.size < 2) {
            val msg = Component.text("Usage: /crate givekey <type> [player] [amount]", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        val crateType = args[1].lowercase()
        if (plugin.crateManager.getCrate(crateType) == null) {
            val msg = Component.text("Unknown crate type: $crateType", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            val available = plugin.crateManager.getCrateTypes().joinToString(", ")
            val availMsg = Component.text("Available: $available", NamedTextColor.GRAY)
            if (sender is Player) plugin.commsManager.send(sender, availMsg) else sender.sendMessage(availMsg)
            return
        }

        val targetArg = if (args.size >= 3) args[2] else (sender as? Player)?.name ?: run {
            sender.sendMessage(Component.text("Specify a player when running from console.", NamedTextColor.RED))
            return
        }

        val amount = if (args.size >= 4) args[3].toIntOrNull() ?: 1 else 1

        // Selector support: @a (all), @r (random)
        val targets: List<Player> = when (targetArg.lowercase()) {
            "@a", "*" -> plugin.server.onlinePlayers.toList()
            "@r" -> plugin.server.onlinePlayers.toList().shuffled().take(1)
            else -> {
                val p = plugin.server.getPlayer(targetArg)
                if (p == null) {
                    val msg = Component.text("Player not found: $targetArg", NamedTextColor.RED)
                    if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
                    return
                }
                listOf(p)
            }
        }

        if (targets.isEmpty()) {
            val msg = Component.text("No matching players online.", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        var given = 0
        for (target in targets) {
            if (plugin.crateManager.giveKey(target, crateType, amount)) given++
        }

        val crate = plugin.crateManager.getCrate(crateType)!!
        val summaryMsg = Component.text("Gave $amount ", NamedTextColor.GREEN)
            .append(Component.text(crate.keyName, TextColor.color(0xFFAA00)))
            .append(Component.text(" to $given player${if (given != 1) "s" else ""}.", NamedTextColor.GREEN))
        if (sender is Player) plugin.commsManager.send(sender, summaryMsg) else sender.sendMessage(summaryMsg)
    }

    private fun handleList(sender: CommandSender) {
        val types = plugin.crateManager.getCrateTypes()
        if (types.isEmpty()) {
            val msg = Component.text("No crate types configured.", NamedTextColor.GRAY)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        val msg = Component.text("Crate types: ", NamedTextColor.GRAY)
            .append(Component.text(types.joinToString(", "), TextColor.color(0x55FFFF)))
        if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
    }

    private fun handlePreview(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Only players can preview crates.", NamedTextColor.RED))
            return
        }

        if (!player.hasPermission("joshymc.crate.use")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /crate preview <type>", NamedTextColor.RED))
            return
        }

        val crateType = args[1].lowercase()
        if (plugin.crateManager.getCrate(crateType) == null) {
            plugin.commsManager.send(player, Component.text("Unknown crate type: $crateType", NamedTextColor.RED))
            val available = plugin.crateManager.getCrateTypes().joinToString(", ")
            plugin.commsManager.send(player, Component.text("Available: $available", NamedTextColor.GRAY))
            return
        }

        plugin.crateManager.openPreview(player, crateType)
    }

    private fun sendUsage(sender: CommandSender) {
        if (sender is Player) {
            plugin.commsManager.send(sender, Component.text("Crate Commands:", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            sender.sendMessage(Component.text("Crate Commands:", NamedTextColor.GREEN))
        }
        val usages = listOf(
            "/crate setlocation <type>",
            "/crate removelocation",
            "/crate givekey <type> [player] [amount]",
            "/crate list",
            "/crate preview <type>"
        )
        for (usage in usages) {
            sender.sendMessage(
                Component.text("  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(usage, NamedTextColor.GRAY))
            )
        }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        return when (args.size) {
            1 -> {
                val subs = mutableListOf("list", "preview")
                if (sender.hasPermission("joshymc.crate.admin")) {
                    subs.addAll(listOf("setlocation", "removelocation", "givekey"))
                }
                subs.filter { it.startsWith(args[0], ignoreCase = true) }
            }
            2 -> when (args[0].lowercase()) {
                "setlocation", "givekey", "preview" ->
                    plugin.crateManager.getCrateTypes().filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "givekey" -> {
                    val names = plugin.server.onlinePlayers.map { it.name } + listOf("@a", "@r")
                    names.filter { it.startsWith(args[2], ignoreCase = true) }
                }
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "givekey" -> listOf("1", "5", "10", "32", "64").filter { it.startsWith(args[3], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
