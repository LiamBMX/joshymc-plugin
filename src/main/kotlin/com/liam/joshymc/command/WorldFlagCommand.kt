package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.WorldFlagManager
import com.liam.joshymc.manager.WorldFlagManager.WorldFlag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
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
            "subflag" -> handleSubflag(sender, args)
            else -> handleSet(sender, args)
        }

        return true
    }

    // ──────────────────────────────────────────────
    //  /worldflag subflag ... — nested regions inside a parent WorldFlag
    // ──────────────────────────────────────────────

    private val manager get() = plugin.worldFlagManager

    /** Reuses the WorldFlags region editor GUI — see [WorldFlagsCommand]. */
    private val guiDelegate by lazy { WorldFlagsCommand(plugin) }

    private fun handleSubflag(sender: CommandSender, args: Array<out String>) {
        val sub = args.getOrNull(1)?.lowercase()
        if (sub == null) {
            sendSubflagUsage(sender)
            return
        }
        when (sub) {
            "wand" -> handleSubflagWand(sender)
            "create" -> handleSubflagCreate(sender, args)
            "delete" -> handleSubflagDelete(sender, args)
            "edit" -> handleSubflagEdit(sender, args)
            "redefine" -> handleSubflagRedefine(sender, args)
            "priority" -> handleSubflagPriority(sender, args)
            "info" -> handleSubflagInfo(sender, args)
            "list" -> handleSubflagList(sender, args)
            else -> sendSubflagUsage(sender)
        }
    }

    private fun handleSubflagWand(sender: CommandSender) {
        val player = playerOnly(sender) ?: return
        player.inventory.addItem(manager.createWand())
        player.sendMessage(
            Component.text("WorldFlags wand added. Left-click sets Position 1, right-click sets Position 2.", NamedTextColor.GREEN)
        )
    }

    private fun handleSubflagCreate(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            player.sendMessage(Component.text("Usage: /worldflag subflag create <parent> <name>", NamedTextColor.RED))
            return
        }
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the wand first (/worldflag subflag wand).", NamedTextColor.RED)
            )
            return
        }
        val (min, max) = boundsOf(selection)
        val world = selection.first.world!!.name
        when (val result = manager.createSubflag(parentName, name, world, min, max)) {
            is WorldFlagManager.SubflagCreateResult.ParentNotFound ->
                player.sendMessage(Component.text("No WorldFlag region named '$parentName'.", NamedTextColor.RED))
            is WorldFlagManager.SubflagCreateResult.ParentIsSubflag ->
                player.sendMessage(Component.text("'$parentName' is itself a Subflag — Subflags can't be nested further.", NamedTextColor.RED))
            is WorldFlagManager.SubflagCreateResult.NameTaken ->
                player.sendMessage(Component.text("Subflag '$name' already exists under '$parentName'.", NamedTextColor.RED))
            is WorldFlagManager.SubflagCreateResult.WrongWorld ->
                player.sendMessage(Component.text("Your selection must be in the same world as '$parentName'.", NamedTextColor.RED))
            is WorldFlagManager.SubflagCreateResult.OutsideParent ->
                player.sendMessage(Component.text("Your selection extends outside '$parentName' on the X/Z boundary.", NamedTextColor.RED))
            is WorldFlagManager.SubflagCreateResult.Created -> {
                manager.clearSelection(player)
                player.sendMessage(Component.text("Subflag '${result.region.name}' created inside '$parentName'.", NamedTextColor.GREEN))
            }
        }
    }

    private fun handleSubflagRedefine(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            player.sendMessage(Component.text("Usage: /worldflag subflag redefine <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = findSubflag(player, parentName, name) ?: return
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the wand first (/worldflag subflag wand).", NamedTextColor.RED)
            )
            return
        }
        val (min, max) = boundsOf(selection)
        val updated = manager.redefineRegion(region.name, min, max)
        if (updated == null) {
            player.sendMessage(Component.text("Selection must stay fully inside '$parentName' and in the same world.", NamedTextColor.RED))
            return
        }
        manager.clearSelection(player)
        player.sendMessage(Component.text("Subflag '${region.name}' redefined.", NamedTextColor.GREEN))
    }

    private fun handleSubflagDelete(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag delete <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = findSubflag(sender, parentName, name) ?: return
        manager.deleteRegion(region.name, cascade = true)
        sender.sendMessage(Component.text("Subflag '${region.name}' deleted.", NamedTextColor.GREEN))
    }

    private fun handleSubflagEdit(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            player.sendMessage(Component.text("Usage: /worldflag subflag edit <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = findSubflag(player, parentName, name) ?: return
        guiDelegate.openEditorGui(player, region.name)
    }

    private fun handleSubflagPriority(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        val priority = args.getOrNull(4)?.toIntOrNull()
        if (parentName == null || name == null || priority == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag priority <parent> <name> <number>", NamedTextColor.RED))
            return
        }
        val region = findSubflag(sender, parentName, name) ?: return
        manager.setPriority(region.name, priority)
        sender.sendMessage(Component.text("Priority of '${region.name}' set to $priority.", NamedTextColor.GREEN))
    }

    private fun handleSubflagInfo(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag info <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = findSubflag(sender, parentName, name) ?: return
        sender.sendMessage(Component.text(region.name, NamedTextColor.GOLD, TextDecoration.BOLD))
        sender.sendMessage(Component.text(" Parent: ${region.parent}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" World: ${region.world}", NamedTextColor.GRAY))
        sender.sendMessage(
            Component.text(
                " Bounds: (${region.minX}, ${region.minY}, ${region.minZ}) -> (${region.maxX}, ${region.maxY}, ${region.maxZ})",
                NamedTextColor.GRAY
            )
        )
        sender.sendMessage(Component.text(" Priority: ${region.priority}", NamedTextColor.GRAY))
        if (region.flags.isEmpty()) {
            sender.sendMessage(Component.text(" Flags: none set (inherits everything from '${region.parent}')", NamedTextColor.DARK_GRAY))
        } else {
            sender.sendMessage(Component.text(" Flags:", NamedTextColor.GRAY))
            region.flags.forEach { (flag, value) ->
                val color = if (value) NamedTextColor.GREEN else NamedTextColor.RED
                sender.sendMessage(
                    Component.text("  ${flag.displayName}: ", NamedTextColor.GRAY)
                        .append(Component.text(if (value) "ALLOW" else "DENY", color))
                )
            }
        }
    }

    private fun handleSubflagList(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        if (parentName == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag list <parent>", NamedTextColor.RED))
            return
        }
        if (manager.getRegion(parentName) == null) {
            sender.sendMessage(Component.text("No WorldFlag region named '$parentName'.", NamedTextColor.RED))
            return
        }
        val subflags = manager.subflagsOf(parentName)
        if (subflags.isEmpty()) {
            sender.sendMessage(Component.text("No Subflags under '$parentName'.", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(Component.text("Subflags under '$parentName':", NamedTextColor.GOLD))
        subflags.forEach { subflag ->
            sender.sendMessage(
                Component.text(" ${subflag.name} ", NamedTextColor.WHITE)
                    .append(Component.text("(priority ${subflag.priority})", NamedTextColor.GRAY))
            )
        }
    }

    private fun findSubflag(sender: CommandSender, parentName: String, name: String): WorldFlagManager.WorldFlagRegion? {
        val parent = manager.getRegion(parentName)
        if (parent == null) {
            sender.sendMessage(Component.text("No WorldFlag region named '$parentName'.", NamedTextColor.RED))
            return null
        }
        val fullName = "${parent.name}.$name"
        val region = manager.getRegion(fullName)
        if (region == null || !region.parent.equals(parent.name, ignoreCase = true)) {
            sender.sendMessage(Component.text("No Subflag named '$name' under '$parentName'.", NamedTextColor.RED))
            return null
        }
        return region
    }

    private fun playerOnly(sender: CommandSender): Player? {
        if (sender is Player) return sender
        sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
        return null
    }

    private fun boundsOf(selection: Pair<Location, Location>): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>> {
        val (a, b) = selection
        val min = Triple(minOf(a.blockX, b.blockX), minOf(a.blockY, b.blockY), minOf(a.blockZ, b.blockZ))
        val max = Triple(maxOf(a.blockX, b.blockX), maxOf(a.blockY, b.blockY), maxOf(a.blockZ, b.blockZ))
        return min to max
    }

    private fun sendSubflagUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Subflags — nested regions inside a WorldFlag:", NamedTextColor.GOLD))
        listOf(
            "/worldflag subflag wand" to "Get the region selection wand",
            "/worldflag subflag create <parent> <name>" to "Create a Subflag from your wand selection",
            "/worldflag subflag edit <parent> <name>" to "Open the Subflag flag editor",
            "/worldflag subflag redefine <parent> <name>" to "Redefine a Subflag's bounds from your selection",
            "/worldflag subflag delete <parent> <name>" to "Delete a Subflag",
            "/worldflag subflag priority <parent> <name> <n>" to "Set a Subflag's priority (higher wins among overlapping Subflags)",
            "/worldflag subflag info <parent> <name>" to "Show a Subflag's details",
            "/worldflag subflag list <parent>" to "List a WorldFlag's Subflags",
        ).forEach { (usage, desc) ->
            sender.sendMessage(Component.text(" $usage ", NamedTextColor.YELLOW).append(Component.text("- $desc", NamedTextColor.GRAY)))
        }
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

        if (args.isNotEmpty() && args[0].equals("subflag", ignoreCase = true)) {
            return subflagTabComplete(args)
        }

        return when (args.size) {
            1 -> {
                val options = WorldFlag.entries.map { it.name.lowercase() } + listOf("list", "reset", "subflag")
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

    private fun subflagTabComplete(args: Array<out String>): List<String> {
        return when (args.size) {
            2 -> listOf("wand", "create", "delete", "edit", "redefine", "priority", "info", "list")
                .filter { it.startsWith(args[1].lowercase()) }
            3 -> if (args[1].equals("wand", ignoreCase = true)) {
                emptyList()
            } else {
                manager.topLevelRegions().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            }
            4 -> when (args[1].lowercase()) {
                "create" -> emptyList()
                "list", "wand" -> emptyList()
                else -> manager.subflagsOf(args[2]).map { it.name.substringAfter('.') }
                    .filter { it.startsWith(args[3], ignoreCase = true) }
            }
            else -> emptyList()
        }
    }

    private fun worldNames(prefix: String): List<String> {
        return Bukkit.getWorlds().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
    }
}
