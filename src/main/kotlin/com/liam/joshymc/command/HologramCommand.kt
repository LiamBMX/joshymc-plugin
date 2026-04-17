package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class HologramCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val subcommands = listOf("create", "delete", "addline", "removeline", "setline", "move", "list", "cleanup", "nuke", "scale", "rotation")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.holo")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> handleCreate(sender, args)
            "delete" -> handleDelete(sender, args)
            "addline" -> handleAddLine(sender, args)
            "removeline" -> handleRemoveLine(sender, args)
            "setline" -> handleSetLine(sender, args)
            "move" -> handleMove(sender, args)
            "list" -> handleList(sender)
            "cleanup" -> handleCleanup(sender)
            "nuke" -> handleNuke(sender)
            "scale" -> handleScale(sender, args)
            "rotation" -> handleRotation(sender, args)
            else -> sendUsage(sender)
        }

        return true
    }

    private fun handleCreate(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /holo create <id> <line...>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val text = args.drop(2).joinToString(" ")

        if (!plugin.hologramManager.createHologram(id, player.location, listOf(text))) {
            plugin.commsManager.send(player, Component.text("Hologram '$id' already exists.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Created hologram '$id'.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleDelete(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /holo delete <id>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()

        if (!plugin.hologramManager.deleteHologram(id)) {
            plugin.commsManager.send(player, Component.text("Hologram '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Deleted hologram '$id'.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleAddLine(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /holo addline <id> <text...>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val text = args.drop(2).joinToString(" ")

        if (!plugin.hologramManager.addLine(id, text)) {
            plugin.commsManager.send(player, Component.text("Hologram '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Added line to hologram '$id'.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleRemoveLine(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /holo removeline <id> <lineNumber>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val lineNumber = args[2].toIntOrNull()

        if (lineNumber == null) {
            plugin.commsManager.send(player, Component.text("Line number must be an integer.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        if (!plugin.hologramManager.removeLine(id, lineNumber)) {
            plugin.commsManager.send(player, Component.text("Invalid hologram or line number.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Removed line $lineNumber from hologram '$id'.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleSetLine(player: Player, args: Array<out String>) {
        if (args.size < 4) {
            plugin.commsManager.send(player, Component.text("Usage: /holo setline <id> <lineNumber> <text...>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()
        val lineNumber = args[2].toIntOrNull()

        if (lineNumber == null) {
            plugin.commsManager.send(player, Component.text("Line number must be an integer.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val text = args.drop(3).joinToString(" ")

        if (!plugin.hologramManager.setLine(id, lineNumber, text)) {
            plugin.commsManager.send(player, Component.text("Invalid hologram or line number.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Updated line $lineNumber of hologram '$id'.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleMove(player: Player, args: Array<out String>) {
        if (args.size < 2) {
            plugin.commsManager.send(player, Component.text("Usage: /holo move <id>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].lowercase()

        if (!plugin.hologramManager.moveHologram(id, player.location)) {
            plugin.commsManager.send(player, Component.text("Hologram '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Moved hologram '$id' to your location.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
    }

    private fun handleList(player: Player) {
        val ids = plugin.hologramManager.getHologramIds()

        if (ids.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No holograms.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(player, Component.text("Holograms (${ids.size}):", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        for (id in ids) {
            player.sendMessage(
                Component.text("  - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(id, NamedTextColor.WHITE))
            )
        }
    }

    private fun handleCleanup(player: Player) {
        val removed = plugin.hologramManager.cleanupOrphans(player.world)
        if (removed == 0) {
            plugin.commsManager.send(player, Component.text("No orphaned holograms found in this world.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(
                player,
                Component.text("Removed $removed orphaned hologram${if (removed != 1) "s" else ""} from ${player.world.name}.", NamedTextColor.GREEN),
                CommunicationsManager.Category.ADMIN
            )
        }
    }

    private fun handleNuke(player: Player) {
        val removed = plugin.hologramManager.cleanupAll(player.world)
        plugin.commsManager.send(
            player,
            Component.text("Despawned $removed hologram entit${if (removed != 1) "ies" else "y"} in ${player.world.name} and re-spawned tracked ones.", NamedTextColor.GREEN),
            CommunicationsManager.Category.ADMIN
        )
    }

    private fun handleScale(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /holo scale <id> <size>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(player, Component.text("  e.g. /holo scale logo 2.5  (1.0 = normal, 0.5 = half, 3.0 = triple)", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
            return
        }
        val id = args[1].lowercase()
        val scale = args[2].toFloatOrNull()
        if (scale == null || scale <= 0f) {
            plugin.commsManager.send(player, Component.text("Scale must be a positive number.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }
        if (plugin.hologramManager.setScale(id, scale)) {
            plugin.commsManager.send(player, Component.text("Hologram '$id' scale set to $scale.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("Hologram '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleRotation(player: Player, args: Array<out String>) {
        if (args.size < 3) {
            plugin.commsManager.send(player, Component.text("Usage: /holo rotation <id> <degrees|unlock>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(player, Component.text("  Yaw degrees: 0=south, 90=west, 180=north, 270=east", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
            plugin.commsManager.send(player, Component.text("  Use 'unlock' to make it face the player again.", NamedTextColor.GRAY), CommunicationsManager.Category.ADMIN)
            return
        }
        val id = args[1].lowercase()
        val arg = args[2].lowercase()

        if (arg == "unlock" || arg == "off" || arg == "none") {
            if (plugin.hologramManager.setRotation(id, null)) {
                plugin.commsManager.send(player, Component.text("Hologram '$id' is now facing the player (unlocked).", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
            } else {
                plugin.commsManager.send(player, Component.text("Hologram '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            }
            return
        }

        val degrees = arg.toFloatOrNull()
        if (degrees == null) {
            plugin.commsManager.send(player, Component.text("Rotation must be a number (or 'unlock').", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }
        if (plugin.hologramManager.setRotation(id, degrees)) {
            plugin.commsManager.send(player, Component.text("Hologram '$id' locked to $degrees°.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(player, Component.text("Hologram '$id' not found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun sendUsage(player: Player) {
        plugin.commsManager.send(player, Component.text("Hologram Commands:", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        val usages = listOf(
            "/holo create <id> <line...>",
            "/holo delete <id>",
            "/holo addline <id> <text...>",
            "/holo removeline <id> <lineNumber>",
            "/holo setline <id> <lineNumber> <text...>",
            "/holo move <id>",
            "/holo list",
            "/holo scale <id> <size>",
            "/holo rotation <id> <degrees|unlock>",
            "/holo cleanup  - remove orphaned holograms (safe)",
            "/holo nuke     - despawn all + respawn from db"
        )
        for (usage in usages) {
            player.sendMessage(
                Component.text("  ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(usage, NamedTextColor.GRAY))
            )
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return subcommands.filter { it.startsWith(args[0].lowercase()) }
        }

        if (args.size == 2) {
            val sub = args[0].lowercase()
            if (sub in listOf("delete", "addline", "removeline", "setline", "move", "scale", "rotation")) {
                val prefix = args[1].lowercase()
                return plugin.hologramManager.getHologramIds().filter { it.startsWith(prefix) }
            }
        }

        if (args.size == 3) {
            when (args[0].lowercase()) {
                "scale" -> return listOf("0.5", "1.0", "1.5", "2.0", "3.0").filter { it.startsWith(args[2]) }
                "rotation" -> return listOf("0", "90", "180", "270", "unlock").filter { it.startsWith(args[2].lowercase()) }
            }
        }

        return emptyList()
    }
}
