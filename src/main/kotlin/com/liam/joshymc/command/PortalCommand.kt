package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class PortalCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val portalManager get() = plugin.portalManager
    private val validActions = listOf("command", "tp", "rtp", "world", "warp")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.portal")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "wand" -> handleWand(sender)
            "create" -> handleCreate(sender, args)
            "delete" -> handleDelete(sender, args)
            "action" -> handleAction(sender, args)
            "cooldown" -> handleCooldown(sender, args)
            "message" -> handleMessage(sender, args)
            "list" -> handleList(sender)
            "info" -> handleInfo(sender, args)
            "tp" -> handleTp(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    private fun handleWand(sender: Player) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }
        portalManager.giveWand(sender)
        plugin.commsManager.send(sender, Component.text("Portal wand given.", NamedTextColor.GREEN))
    }

    private fun handleCreate(sender: Player, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /portal create <name>", NamedTextColor.RED))
            return
        }
        val name = args[1]

        if (portalManager.getPortal(name) != null) {
            plugin.commsManager.send(sender, Component.text("A portal named '$name' already exists.", NamedTextColor.RED))
            return
        }

        val selection = portalManager.getSelection(sender)
        if (selection.first == null || selection.second == null) {
            plugin.commsManager.send(sender, Component.text("Select two positions with the portal wand first.", NamedTextColor.RED))
            return
        }

        if (selection.first!!.world != selection.second!!.world) {
            plugin.commsManager.send(sender, Component.text("Both positions must be in the same world.", NamedTextColor.RED))
            return
        }

        if (portalManager.createPortal(name, sender)) {
            plugin.commsManager.send(sender,
                Component.text("Portal ", NamedTextColor.GREEN)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" created. Use ", NamedTextColor.GREEN))
                    .append(Component.text("/portal action $name <type> <data>", NamedTextColor.YELLOW))
                    .append(Component.text(" to set its action.", NamedTextColor.GREEN))
            )
        } else {
            plugin.commsManager.send(sender, Component.text("Failed to create portal.", NamedTextColor.RED))
        }
    }

    private fun handleDelete(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /portal delete <name>", NamedTextColor.RED))
            return
        }
        val name = args[1]
        if (portalManager.deletePortal(name)) {
            plugin.commsManager.send(sender, Component.text("Portal '$name' deleted.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(sender, Component.text("Portal '$name' not found.", NamedTextColor.RED))
        }
    }

    private fun handleAction(sender: Player, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /portal action <name> <command|tp|rtp|world|warp> [data]", NamedTextColor.RED))
            return
        }
        val name = args[1]
        val action = args[2].lowercase()

        if (action !in validActions) {
            sender.sendMessage(Component.text("Invalid action. Use: ${validActions.joinToString(", ")}", NamedTextColor.RED))
            return
        }

        if (portalManager.getPortal(name) == null) {
            plugin.commsManager.send(sender, Component.text("Portal '$name' not found.", NamedTextColor.RED))
            return
        }

        val data = if (args.size > 3) args.drop(3).joinToString(" ") else ""

        portalManager.setAction(name, action, data)
        plugin.commsManager.send(sender,
            Component.text("Portal '$name' action set to ", NamedTextColor.GREEN)
                .append(Component.text(action, NamedTextColor.YELLOW))
                .append(if (data.isNotBlank()) Component.text(" ($data)", NamedTextColor.GRAY) else Component.empty())
        )
    }

    private fun handleCooldown(sender: Player, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /portal cooldown <name> <seconds>", NamedTextColor.RED))
            return
        }
        val name = args[1]
        val seconds = args[2].toIntOrNull()
        if (seconds == null || seconds < 0) {
            sender.sendMessage(Component.text("Cooldown must be a non-negative number.", NamedTextColor.RED))
            return
        }

        if (portalManager.getPortal(name) == null) {
            plugin.commsManager.send(sender, Component.text("Portal '$name' not found.", NamedTextColor.RED))
            return
        }

        portalManager.setCooldown(name, seconds)
        plugin.commsManager.send(sender,
            Component.text("Portal '$name' cooldown set to ", NamedTextColor.GREEN)
                .append(Component.text("${seconds}s", NamedTextColor.YELLOW))
        )
    }

    private fun handleMessage(sender: Player, args: Array<out String>) {
        if (args.size < 3) {
            sender.sendMessage(Component.text("Usage: /portal message <name> <text>", NamedTextColor.RED))
            return
        }
        val name = args[1]

        if (portalManager.getPortal(name) == null) {
            plugin.commsManager.send(sender, Component.text("Portal '$name' not found.", NamedTextColor.RED))
            return
        }

        val message = args.drop(2).joinToString(" ")
        portalManager.setMessage(name, message)
        plugin.commsManager.send(sender,
            Component.text("Portal '$name' message set to: ", NamedTextColor.GREEN)
                .append(plugin.commsManager.parseLegacy(message))
        )
    }

    private fun handleList(sender: Player) {
        val portals = portalManager.getAllPortals()
        if (portals.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("No portals defined.", NamedTextColor.GRAY))
            return
        }

        sender.sendMessage(Component.text("Portals (${portals.size}):", NamedTextColor.GOLD)
            .decoration(TextDecoration.BOLD, true))

        for (portal in portals) {
            sender.sendMessage(
                Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(portal.name, NamedTextColor.YELLOW))
                    .append(Component.text(" [${portal.action}]", NamedTextColor.GRAY))
                    .append(Component.text(" @ ${portal.world} (${portal.x1},${portal.y1},${portal.z1}) -> (${portal.x2},${portal.y2},${portal.z2})", NamedTextColor.DARK_GRAY))
            )
        }
    }

    private fun handleInfo(sender: Player, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /portal info <name>", NamedTextColor.RED))
            return
        }
        val portal = portalManager.getPortal(args[1])
        if (portal == null) {
            plugin.commsManager.send(sender, Component.text("Portal '${args[1]}' not found.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("Portal: ", NamedTextColor.GOLD)
            .append(Component.text(portal.name, NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true)))
        sender.sendMessage(Component.text("  World: ", NamedTextColor.GRAY).append(Component.text(portal.world, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Region: ", NamedTextColor.GRAY)
            .append(Component.text("(${portal.x1}, ${portal.y1}, ${portal.z1})", NamedTextColor.WHITE))
            .append(Component.text(" to ", NamedTextColor.GRAY))
            .append(Component.text("(${portal.x2}, ${portal.y2}, ${portal.z2})", NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Action: ", NamedTextColor.GRAY).append(Component.text(portal.action, NamedTextColor.YELLOW)))
        sender.sendMessage(Component.text("  Data: ", NamedTextColor.GRAY)
            .append(Component.text(portal.actionData.ifBlank { "(none)" }, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Cooldown: ", NamedTextColor.GRAY)
            .append(Component.text("${portal.cooldownSeconds}s", NamedTextColor.WHITE)))
        sender.sendMessage(Component.text("  Message: ", NamedTextColor.GRAY)
            .append(if (portal.message.isNullOrBlank()) Component.text("(none)", NamedTextColor.DARK_GRAY)
            else plugin.commsManager.parseLegacy(portal.message)))
    }

    private fun handleTp(sender: Player, args: Array<out String>) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /portal tp <name>", NamedTextColor.RED))
            return
        }
        val portal = portalManager.getPortal(args[1])
        if (portal == null) {
            plugin.commsManager.send(sender, Component.text("Portal '${args[1]}' not found.", NamedTextColor.RED))
            return
        }

        val world = plugin.server.getWorld(portal.world)
        if (world == null) {
            plugin.commsManager.send(sender, Component.text("World '${portal.world}' not loaded.", NamedTextColor.RED))
            return
        }

        val x = (portal.x1 + portal.x2) / 2.0 + 0.5
        val y = portal.y1.toDouble()
        val z = (portal.z1 + portal.z2) / 2.0 + 0.5
        sender.teleport(org.bukkit.Location(world, x, y, z, sender.location.yaw, sender.location.pitch))
        plugin.commsManager.send(sender,
            Component.text("Teleported to portal ", NamedTextColor.GREEN)
                .append(Component.text(portal.name, NamedTextColor.YELLOW))
        )
    }

    private fun sendUsage(sender: Player) {
        sender.sendMessage(Component.text("Portal Commands:", NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true))
        sender.sendMessage(Component.text("  /portal wand", NamedTextColor.GRAY).append(Component.text(" - Get selection wand", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal create <name>", NamedTextColor.GRAY).append(Component.text(" - Create from selection", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal delete <name>", NamedTextColor.GRAY).append(Component.text(" - Delete a portal", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal action <name> <type> [data]", NamedTextColor.GRAY).append(Component.text(" - Set action", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal cooldown <name> <seconds>", NamedTextColor.GRAY).append(Component.text(" - Set cooldown", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal message <name> <text>", NamedTextColor.GRAY).append(Component.text(" - Set entry message", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal list", NamedTextColor.GRAY).append(Component.text(" - List all portals", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal info <name>", NamedTextColor.GRAY).append(Component.text(" - Portal details", NamedTextColor.DARK_GRAY)))
        sender.sendMessage(Component.text("  /portal tp <name>", NamedTextColor.GRAY).append(Component.text(" - Teleport to portal", NamedTextColor.DARK_GRAY)))
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (!sender.hasPermission("joshymc.portal")) return emptyList()

        val subcommands = listOf("wand", "create", "delete", "action", "cooldown", "message", "list", "info", "tp")
        val portalNames by lazy { portalManager.getAllPortals().map { it.name } }

        return when (args.size) {
            1 -> subcommands.filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> when (args[0].lowercase()) {
                "delete", "action", "cooldown", "message", "info", "tp" ->
                    portalNames.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "action" -> validActions.filter { it.startsWith(args[2], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
