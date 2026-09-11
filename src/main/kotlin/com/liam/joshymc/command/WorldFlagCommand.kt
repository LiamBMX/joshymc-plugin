package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.WorldFlagManager
import com.liam.joshymc.manager.WorldFlagManager.RegionOpResult
import com.liam.joshymc.manager.WorldFlagManager.WorldFlag
import com.liam.joshymc.manager.WorldFlagManager.WorldFlagRegion
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/worldflag` — single canonical world-protection command. Covers:
 *  - world-wide boolean flags (`/worldflag <flag> <true|false> [world]`)
 *  - cuboid WorldFlag regions (`/worldflag region ...`)
 *  - nested subflags inside a region (`/worldflag subflag ...`)
 *
 * All three layers resolve through [WorldFlagManager.resolveFlag] /
 * [WorldFlagManager.isAllowedAt] — there is exactly one resolver.
 */
class WorldFlagCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.worldFlagManager

    // player UUID -> (region name, requested-at ms), for /worldflag region clearplaced confirmation
    private val pendingClear = ConcurrentHashMap<UUID, Pair<String, Long>>()
    private val confirmWindowMs = 30_000L

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (!sender.hasPermission("joshymc.worldflag")) {
                sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                return true
            }
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "region" -> {
                if (!sender.hasPermission(WorldFlagManager.ADMIN_PERMISSION)) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                handleRegion(sender, args)
            }
            "subflag" -> {
                if (!sender.hasPermission(WorldFlagManager.ADMIN_PERMISSION)) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                handleSubflag(sender, args)
            }
            "list" -> {
                if (!sender.hasPermission("joshymc.worldflag")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                handleList(sender, args)
            }
            "reset" -> {
                if (!sender.hasPermission("joshymc.worldflag")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                handleReset(sender, args)
            }
            else -> {
                if (!sender.hasPermission("joshymc.worldflag")) {
                    sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                handleSet(sender, args)
            }
        }

        return true
    }

    // ══════════════════════════════════════════════
    //  World-wide flags (unchanged behavior)
    // ══════════════════════════════════════════════

    private fun handleList(sender: CommandSender, args: Array<out String>) {
        val worldName = resolveWorld(sender, args.getOrNull(1)) ?: return

        val flags = manager.getFlags(worldName)

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

    private fun handleReset(sender: CommandSender, args: Array<out String>) {
        val worldName = resolveWorld(sender, args.getOrNull(1)) ?: return

        manager.resetFlags(worldName)

        sender.sendMessage(
            Component.text("Reset all flags for ", NamedTextColor.GREEN)
                .append(Component.text(worldName, NamedTextColor.WHITE, TextDecoration.BOLD))
                .append(Component.text(". All flags default to ", NamedTextColor.GREEN))
                .append(Component.text("ALLOWED", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
    }

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

        manager.setFlag(worldName, flag, value)

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

    private fun resolveWorld(sender: CommandSender, explicit: String?): String? {
        if (explicit != null) return explicit
        if (sender is Player) return sender.world.name
        sender.sendMessage(Component.text("Specify a world name (console must provide one).", NamedTextColor.RED))
        return null
    }

    // ══════════════════════════════════════════════
    //  Regions — /worldflag region ...
    // ══════════════════════════════════════════════

    private fun handleRegion(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sendRegionUsage(sender)
            return
        }
        when (args[1].lowercase()) {
            "wand" -> handleRegionWand(sender)
            "create" -> handleRegionCreate(sender, args)
            "edit" -> handleRegionEdit(sender, args)
            "delete" -> handleRegionDelete(sender, args)
            "redefine" -> handleRegionRedefine(sender, args)
            "priority" -> handleRegionPriority(sender, args)
            "info" -> handleRegionInfo(sender, args)
            "list" -> handleRegionList(sender, args)
            "reload" -> handleRegionReload(sender)
            "clearplaced" -> handleRegionClearPlaced(sender, args)
            else -> sendRegionUsage(sender)
        }
    }

    private fun handleRegionWand(sender: CommandSender) {
        val player = playerOnly(sender) ?: return
        player.inventory.addItem(manager.createWand())
        player.sendMessage(
            Component.text("WorldFlag wand added. Left-click sets Position 1, right-click sets Position 2.", NamedTextColor.GREEN)
        )
    }

    private fun handleRegionCreate(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val name = args.getOrNull(2)
        if (name == null) {
            player.sendMessage(Component.text("Usage: /worldflag region create <name>", NamedTextColor.RED))
            return
        }
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the WorldFlag wand first (/worldflag region wand).", NamedTextColor.RED)
            )
            return
        }
        val (min, max) = boundsOf(selection)
        val world = selection.first.world!!.name
        when (val result = manager.createRegion(name, world, min, max)) {
            RegionOpResult.SUCCESS -> {
                manager.clearSelection(player)
                player.sendMessage(Component.text("Region '$name' created in $world.", NamedTextColor.GREEN))
            }
            RegionOpResult.DUPLICATE_NAME -> player.sendMessage(Component.text("A region named '$name' already exists.", NamedTextColor.RED))
            else -> player.sendMessage(Component.text("Could not create region ($result).", NamedTextColor.RED))
        }
    }

    private fun handleRegionRedefine(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val name = args.getOrNull(2)
        if (name == null) {
            player.sendMessage(Component.text("Usage: /worldflag region redefine <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null) {
            player.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the WorldFlag wand first (/worldflag region wand).", NamedTextColor.RED)
            )
            return
        }
        if (selection.first.world!!.name != region.world) {
            player.sendMessage(Component.text("Your selection must be in the same world as '${region.name}' (${region.world}).", NamedTextColor.RED))
            return
        }
        val (min, max) = boundsOf(selection)
        when (manager.redefineRegion(region.name, min, max)) {
            RegionOpResult.SUCCESS -> {
                manager.clearSelection(player)
                player.sendMessage(Component.text("Region '${region.name}' redefined.", NamedTextColor.GREEN))
            }
            else -> player.sendMessage(Component.text("Could not redefine region '${region.name}'.", NamedTextColor.RED))
        }
    }

    private fun handleRegionDelete(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(2)
        if (name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag region delete <name>", NamedTextColor.RED))
            return
        }
        when (manager.deleteRegion(name)) {
            RegionOpResult.SUCCESS -> sender.sendMessage(Component.text("Region '$name' deleted.", NamedTextColor.GREEN))
            RegionOpResult.HAS_SUBFLAGS -> sender.sendMessage(
                Component.text("'$name' still has subflags — delete them first with /worldflag subflag delete.", NamedTextColor.RED)
            )
            else -> sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
        }
    }

    private fun handleRegionEdit(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val name = args.getOrNull(2)
        if (name == null) {
            player.sendMessage(Component.text("Usage: /worldflag region edit <name>", NamedTextColor.RED))
            return
        }
        if (manager.getRegion(name) == null) {
            player.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        openEditorGui(player, name)
    }

    private fun handleRegionPriority(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(2)
        val priority = args.getOrNull(3)?.toIntOrNull()
        if (name == null || priority == null) {
            sender.sendMessage(Component.text("Usage: /worldflag region priority <name> <number>", NamedTextColor.RED))
            return
        }
        if (!manager.setPriority(name, priority)) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("Priority of '$name' set to $priority.", NamedTextColor.GREEN))
    }

    private fun handleRegionInfo(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(2)
        if (name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag region info <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        sendRegionInfo(sender, region)
    }

    private fun handleRegionList(sender: CommandSender, args: Array<out String>) {
        val worldFilter = args.getOrNull(2)
        val list = if (worldFilter != null) manager.regionsInWorld(worldFilter) else manager.allRegions()
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("No regions defined${if (worldFilter != null) " in $worldFilter" else ""}.", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(Component.text("WorldFlag regions:", NamedTextColor.GOLD))
        list.forEach { region ->
            val subCount = manager.subflagsOf(region.name).size
            sender.sendMessage(
                Component.text(" ${region.name} ", NamedTextColor.WHITE)
                    .append(Component.text("(${region.world}, priority ${region.priority}${if (subCount > 0) ", $subCount subflag(s)" else ""})", NamedTextColor.GRAY))
            )
        }
    }

    private fun handleRegionReload(sender: CommandSender) {
        manager.reloadRegions()
        sender.sendMessage(Component.text("WorldFlag regions reloaded.", NamedTextColor.GREEN))
    }

    private fun handleRegionClearPlaced(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(2)
        if (name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag region clearplaced <region>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        val isConfirm = args.getOrNull(3)?.equals("confirm", ignoreCase = true) == true

        if (sender !is Player) {
            if (!isConfirm) {
                sender.sendMessage(
                    Component.text("This resets player-placed tracking for '${region.name}'. Re-run with 'confirm' to proceed.", NamedTextColor.YELLOW)
                )
                return
            }
            val count = manager.clearPlacedBlocks(region.name)
            sender.sendMessage(Component.text("Cleared $count tracked block(s) for '${region.name}'.", NamedTextColor.GREEN))
            return
        }

        val uuid = sender.uniqueId
        if (isConfirm) {
            val pending = pendingClear[uuid]
            if (pending == null || !pending.first.equals(region.name, ignoreCase = true) ||
                System.currentTimeMillis() - pending.second > confirmWindowMs
            ) {
                sender.sendMessage(
                    Component.text("Run /worldflag region clearplaced ${region.name} first, then confirm within 30s.", NamedTextColor.RED)
                )
                return
            }
            pendingClear.remove(uuid)
            val count = manager.clearPlacedBlocks(region.name)
            sender.sendMessage(
                Component.text("Cleared $count tracked block(s) for '${region.name}'. Currently placed blocks are now protected baseline.", NamedTextColor.GREEN)
            )
            return
        }

        pendingClear[uuid] = region.name to System.currentTimeMillis()
        sender.sendMessage(
            Component.text("This resets player-placed tracking for '${region.name}' — blocks placed there so far become part of the protected baseline.", NamedTextColor.YELLOW)
        )
        sender.sendMessage(
            Component.text("Run /worldflag region clearplaced ${region.name} confirm within 30s to proceed.", NamedTextColor.YELLOW)
        )
    }

    // ══════════════════════════════════════════════
    //  Subflags — /worldflag subflag ...
    // ══════════════════════════════════════════════

    private fun handleSubflag(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sendSubflagUsage(sender)
            return
        }
        when (args[1].lowercase()) {
            "create" -> handleSubflagCreate(sender, args)
            "delete" -> handleSubflagDelete(sender, args)
            "edit" -> handleSubflagEdit(sender, args)
            "list" -> handleSubflagList(sender, args)
            "info" -> handleSubflagInfo(sender, args)
            "redefine" -> handleSubflagRedefine(sender, args)
            "priority" -> handleSubflagPriority(sender, args)
            else -> sendSubflagUsage(sender)
        }
    }

    private fun handleSubflagCreate(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            player.sendMessage(Component.text("Usage: /worldflag subflag create <parent> <name>", NamedTextColor.RED))
            return
        }
        val parent = manager.getRegion(parentName)
        if (parent == null) {
            player.sendMessage(Component.text("No WorldFlag region named '$parentName'.", NamedTextColor.RED))
            return
        }
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the WorldFlag wand first (/worldflag region wand).", NamedTextColor.RED)
            )
            return
        }
        val (min, max) = boundsOf(selection)
        val world = selection.first.world!!.name
        when (manager.createRegion(name, world, min, max, parent = parent.name)) {
            RegionOpResult.SUCCESS -> {
                manager.clearSelection(player)
                player.sendMessage(Component.text("Subflag '$name' created inside '${parent.name}'.", NamedTextColor.GREEN))
            }
            RegionOpResult.DUPLICATE_NAME -> player.sendMessage(Component.text("A region/subflag named '$name' already exists.", NamedTextColor.RED))
            RegionOpResult.PARENT_NOT_FOUND -> player.sendMessage(Component.text("No WorldFlag region named '$parentName'.", NamedTextColor.RED))
            RegionOpResult.PARENT_IS_SUBFLAG -> player.sendMessage(
                Component.text("'${parent.name}' is itself a subflag — subflags cannot be nested further.", NamedTextColor.RED)
            )
            RegionOpResult.WORLD_MISMATCH -> player.sendMessage(
                Component.text("Your selection must be in the same world as '${parent.name}' (${parent.world}).", NamedTextColor.RED)
            )
            RegionOpResult.OUTSIDE_PARENT -> player.sendMessage(
                Component.text("Your selection must be fully inside '${parent.name}'.", NamedTextColor.RED)
            )
            else -> player.sendMessage(Component.text("Could not create subflag.", NamedTextColor.RED))
        }
    }

    private fun handleSubflagDelete(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag delete <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null || region.parent?.equals(parentName, ignoreCase = true) != true) {
            sender.sendMessage(Component.text("No subflag named '$name' under '$parentName'.", NamedTextColor.RED))
            return
        }
        when (manager.deleteRegion(name)) {
            RegionOpResult.SUCCESS -> sender.sendMessage(Component.text("Subflag '$name' deleted.", NamedTextColor.GREEN))
            else -> sender.sendMessage(Component.text("Could not delete subflag '$name'.", NamedTextColor.RED))
        }
    }

    private fun handleSubflagEdit(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            player.sendMessage(Component.text("Usage: /worldflag subflag edit <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null || region.parent?.equals(parentName, ignoreCase = true) != true) {
            player.sendMessage(Component.text("No subflag named '$name' under '$parentName'.", NamedTextColor.RED))
            return
        }
        openEditorGui(player, name)
    }

    private fun handleSubflagList(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        if (parentName == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag list <parent>", NamedTextColor.RED))
            return
        }
        val parent = manager.getRegion(parentName)
        if (parent == null) {
            sender.sendMessage(Component.text("No WorldFlag region named '$parentName'.", NamedTextColor.RED))
            return
        }
        val subflags = manager.subflagsOf(parent.name)
        if (subflags.isEmpty()) {
            sender.sendMessage(Component.text("No subflags under '${parent.name}'.", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(Component.text("Subflags under '${parent.name}':", NamedTextColor.GOLD))
        subflags.forEach { sub ->
            sender.sendMessage(
                Component.text(" ${sub.name} ", NamedTextColor.WHITE)
                    .append(Component.text("(priority ${sub.priority})", NamedTextColor.GRAY))
            )
        }
    }

    private fun handleSubflagInfo(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag info <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null || region.parent?.equals(parentName, ignoreCase = true) != true) {
            sender.sendMessage(Component.text("No subflag named '$name' under '$parentName'.", NamedTextColor.RED))
            return
        }
        sendRegionInfo(sender, region)
    }

    private fun handleSubflagRedefine(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        if (parentName == null || name == null) {
            player.sendMessage(Component.text("Usage: /worldflag subflag redefine <parent> <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null || region.parent?.equals(parentName, ignoreCase = true) != true) {
            player.sendMessage(Component.text("No subflag named '$name' under '$parentName'.", NamedTextColor.RED))
            return
        }
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the WorldFlag wand first (/worldflag region wand).", NamedTextColor.RED)
            )
            return
        }
        if (selection.first.world!!.name != region.world) {
            player.sendMessage(Component.text("Your selection must be in the same world as '${region.name}' (${region.world}).", NamedTextColor.RED))
            return
        }
        val (min, max) = boundsOf(selection)
        when (manager.redefineRegion(region.name, min, max)) {
            RegionOpResult.SUCCESS -> {
                manager.clearSelection(player)
                player.sendMessage(Component.text("Subflag '${region.name}' redefined.", NamedTextColor.GREEN))
            }
            RegionOpResult.OUTSIDE_PARENT -> player.sendMessage(
                Component.text("Your selection must stay fully inside '$parentName'.", NamedTextColor.RED)
            )
            else -> player.sendMessage(Component.text("Could not redefine subflag '${region.name}'.", NamedTextColor.RED))
        }
    }

    private fun handleSubflagPriority(sender: CommandSender, args: Array<out String>) {
        val parentName = args.getOrNull(2)
        val name = args.getOrNull(3)
        val priority = args.getOrNull(4)?.toIntOrNull()
        if (parentName == null || name == null || priority == null) {
            sender.sendMessage(Component.text("Usage: /worldflag subflag priority <parent> <name> <number>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null || region.parent?.equals(parentName, ignoreCase = true) != true) {
            sender.sendMessage(Component.text("No subflag named '$name' under '$parentName'.", NamedTextColor.RED))
            return
        }
        manager.setPriority(name, priority)
        sender.sendMessage(Component.text("Priority of '$name' set to $priority.", NamedTextColor.GREEN))
    }

    // ══════════════════════════════════════════════
    //  Shared info renderer
    // ══════════════════════════════════════════════

    private fun sendRegionInfo(sender: CommandSender, region: WorldFlagRegion) {
        sender.sendMessage(Component.text(region.name, NamedTextColor.GOLD, TextDecoration.BOLD))
        sender.sendMessage(Component.text(" World: ${region.world}", NamedTextColor.GRAY))
        if (region.parent != null) {
            sender.sendMessage(Component.text(" Parent: ${region.parent}", NamedTextColor.GRAY))
        }
        sender.sendMessage(
            Component.text(
                " Bounds: (${region.minX}, ${region.minY}, ${region.minZ}) -> (${region.maxX}, ${region.maxY}, ${region.maxZ})",
                NamedTextColor.GRAY
            )
        )
        sender.sendMessage(Component.text(" Priority: ${region.priority}", NamedTextColor.GRAY))
        if (region.flags.isEmpty()) {
            sender.sendMessage(Component.text(" Flags: none set (inherits everything)", NamedTextColor.DARK_GRAY))
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

    // ══════════════════════════════════════════════
    //  Region/subflag editor GUI
    // ══════════════════════════════════════════════

    private val stateSlots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)

    private fun openEditorGui(player: Player, regionName: String) {
        val region = manager.getRegion(regionName)
        if (region == null) {
            player.sendMessage(Component.text("Region '$regionName' no longer exists.", NamedTextColor.RED))
            return
        }

        val title = "Region: ${region.name}".let { if (it.length > 28) it.take(28) else it }
        val gui = CustomGui(Component.text(title, NamedTextColor.GOLD), 54)

        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { displayName(Component.text(" ")) }
        gui.fill(filler)

        gui.setItem(4, infoItem(region))

        manager.editorFlags.forEachIndexed { i, flag ->
            val slot = stateSlots.getOrNull(i) ?: return@forEachIndexed
            gui.setItem(slot, flagItem(region, flag)) { p, _ ->
                val next = when (region.flags[flag]) {
                    null -> true
                    true -> false
                    false -> null
                }
                manager.setRegionFlag(region.name, flag, next)
                openEditorGui(p, region.name)
            }
        }

        gui.setItem(48, priorityItem(region, -1)) { p, e ->
            val delta = if (e.isShiftClick) -10 else -1
            manager.setPriority(region.name, region.priority + delta)
            openEditorGui(p, region.name)
        }
        gui.setItem(50, priorityItem(region, 1)) { p, e ->
            val delta = if (e.isShiftClick) 10 else 1
            manager.setPriority(region.name, region.priority + delta)
            openEditorGui(p, region.name)
        }
        gui.setItem(49, closeItem()) { p, _ -> p.closeInventory() }

        if (region.parent != null) {
            gui.setItem(40, backToParentItem(region.parent)) { p, _ -> openEditorGui(p, region.parent) }
        } else {
            val subCount = manager.subflagsOf(region.name).size
            gui.setItem(40, subflagsButtonItem(subCount)) { p, _ -> openSubflagListGui(p, region.name) }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun openSubflagListGui(player: Player, parentName: String) {
        val parent = manager.getRegion(parentName)
        if (parent == null) {
            player.sendMessage(Component.text("Region '$parentName' no longer exists.", NamedTextColor.RED))
            return
        }

        val title = "Subflags: ${parent.name}".let { if (it.length > 28) it.take(28) else it }
        val gui = CustomGui(Component.text(title, NamedTextColor.AQUA), 54)

        val filler = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        filler.itemMeta = filler.itemMeta?.apply { displayName(Component.text(" ")) }
        gui.fill(filler)

        manager.subflagsOf(parent.name).forEachIndexed { i, sub ->
            if (i >= 45) return@forEachIndexed
            gui.setItem(i, subflagListItem(sub)) { p, _ -> openEditorGui(p, sub.name) }
        }

        gui.setItem(49, backToParentItem(parent.name)) { p, _ -> openEditorGui(p, parent.name) }

        plugin.guiManager.open(player, gui)
    }

    private fun infoItem(region: WorldFlagRegion): ItemStack {
        val item = ItemStack(Material.BOOK)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text(region.name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
        )
        val lore = mutableListOf(
            Component.text("World: ${region.world}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
        )
        if (region.parent != null) {
            lore.add(Component.text("Parent: ${region.parent}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        }
        lore.add(Component.text("Priority: ${region.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        lore.add(
            Component.text(
                "Bounds: (${region.minX},${region.minY},${region.minZ}) to (${region.maxX},${region.maxY},${region.maxZ})",
                NamedTextColor.GRAY
            ).decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    private fun flagItem(region: WorldFlagRegion, flag: WorldFlag): ItemStack {
        val value = region.flags[flag]
        val material = when (value) {
            true -> Material.LIME_WOOL
            false -> Material.RED_WOOL
            null -> Material.LIGHT_GRAY_WOOL
        }
        val color = when (value) {
            true -> NamedTextColor.GREEN
            false -> NamedTextColor.RED
            null -> NamedTextColor.GRAY
        }
        val stateText = when (value) {
            true -> "ALLOW"
            false -> "DENY"
            null -> "UNSET (inherit)"
        }
        val item = ItemStack(material)
        val meta = item.itemMeta!!
        meta.displayName(Component.text(flag.displayName, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text(flag.description, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("State: $stateText", color).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to cycle.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun priorityItem(region: WorldFlagRegion, direction: Int): ItemStack {
        val item = ItemStack(if (direction > 0) Material.LIME_DYE else Material.RED_DYE)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text(if (direction > 0) "Increase Priority" else "Decrease Priority", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(listOf(
            Component.text("Current: ${region.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(
                "Click: ${if (direction > 0) "+1" else "-1"}, Shift-click: ${if (direction > 0) "+10" else "-10"}",
                NamedTextColor.DARK_GRAY
            ).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun subflagsButtonItem(count: Int): ItemStack {
        val item = ItemStack(Material.ITEM_FRAME)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Subflags ($count)", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text("View/manage nested subflags.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to open.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun backToParentItem(parentName: String): ItemStack {
        val item = ItemStack(Material.ARROW)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Back to $parentName", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
        item.itemMeta = meta
        return item
    }

    private fun subflagListItem(region: WorldFlagRegion): ItemStack {
        val item = ItemStack(Material.PAPER)
        val meta = item.itemMeta!!
        meta.displayName(Component.text(region.name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
        meta.lore(listOf(
            Component.text("Priority: ${region.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Flags set: ${region.flags.size}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Click to edit.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun closeItem(): ItemStack {
        val item = ItemStack(Material.BARRIER)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
        item.itemMeta = meta
        return item
    }

    // ══════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════

    private fun playerOnly(sender: CommandSender): Player? {
        if (sender is Player) return sender
        sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
        return null
    }

    private fun boundsOf(selection: Pair<org.bukkit.Location, org.bukkit.Location>): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>> {
        val (a, b) = selection
        val min = Triple(minOf(a.blockX, b.blockX), minOf(a.blockY, b.blockY), minOf(a.blockZ, b.blockZ))
        val max = Triple(maxOf(a.blockX, b.blockX), maxOf(a.blockY, b.blockY), maxOf(a.blockZ, b.blockZ))
        return min to max
    }

    private fun worldNames(prefix: String): List<String> =
        Bukkit.getWorlds().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }

    private fun regionNames(prefix: String): List<String> =
        manager.allRegions().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }

    // ══════════════════════════════════════════════
    //  Usage
    // ══════════════════════════════════════════════

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Usage:", NamedTextColor.GOLD))
        sender.sendMessage(Component.text(" /worldflag <flag> <true|false> [world]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" /worldflag list [world]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" /worldflag reset [world]", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" /worldflag region ... — cuboid regions (see /worldflag region)", NamedTextColor.GRAY))
        sender.sendMessage(Component.text(" /worldflag subflag ... — nested subflags (see /worldflag subflag)", NamedTextColor.GRAY))
    }

    private fun sendRegionUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("WorldFlag regions — cuboid protection:", NamedTextColor.GOLD))
        listOf(
            "/worldflag region wand" to "Get the region selection wand",
            "/worldflag region create <name>" to "Create a region from your wand selection",
            "/worldflag region edit <name>" to "Open the region flag editor",
            "/worldflag region redefine <name>" to "Redefine a region's bounds from your selection",
            "/worldflag region delete <name>" to "Delete a region (must have no subflags)",
            "/worldflag region priority <name> <n>" to "Set a region's priority (higher wins)",
            "/worldflag region info <name>" to "Show a region's details",
            "/worldflag region list [world]" to "List regions",
            "/worldflag region reload" to "Reload regions from storage",
            "/worldflag region clearplaced <name>" to "Reset player-placed block tracking for a region",
        ).forEach { (usage, desc) ->
            sender.sendMessage(Component.text(" $usage ", NamedTextColor.YELLOW).append(Component.text("- $desc", NamedTextColor.GRAY)))
        }
    }

    private fun sendSubflagUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("Subflags — nested regions inside a WorldFlag region:", NamedTextColor.GOLD))
        listOf(
            "/worldflag subflag create <parent> <name>" to "Create a subflag from your wand selection (must be inside <parent>)",
            "/worldflag subflag edit <parent> <name>" to "Open the subflag flag editor",
            "/worldflag subflag redefine <parent> <name>" to "Redefine a subflag's bounds from your selection",
            "/worldflag subflag delete <parent> <name>" to "Delete a subflag",
            "/worldflag subflag priority <parent> <name> <n>" to "Set a subflag's priority (higher wins on overlap)",
            "/worldflag subflag info <parent> <name>" to "Show a subflag's details",
            "/worldflag subflag list <parent>" to "List subflags under a region",
        ).forEach { (usage, desc) ->
            sender.sendMessage(Component.text(" $usage ", NamedTextColor.YELLOW).append(Component.text("- $desc", NamedTextColor.GRAY)))
        }
        sender.sendMessage(
            Component.text(" Unset flags on a subflag inherit from its parent, then the world. Use /worldflag region wand to select.", NamedTextColor.DARK_GRAY)
        )
    }

    // ══════════════════════════════════════════════
    //  Tab completion
    // ══════════════════════════════════════════════

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            if (!sender.hasPermission("joshymc.worldflag")) return emptyList()
            val options = WorldFlag.entries.map { it.name.lowercase() } + listOf("list", "reset", "region", "subflag")
            return options.filter { it.startsWith(args[0].lowercase()) }
        }

        return when (args[0].lowercase()) {
            "region" -> {
                if (!sender.hasPermission(WorldFlagManager.ADMIN_PERMISSION)) emptyList() else regionTabComplete(args)
            }
            "subflag" -> {
                if (!sender.hasPermission(WorldFlagManager.ADMIN_PERMISSION)) emptyList() else subflagTabComplete(args)
            }
            "list", "reset" -> {
                if (!sender.hasPermission("joshymc.worldflag")) return emptyList()
                if (args.size == 2) worldNames(args[1]) else emptyList()
            }
            else -> {
                if (!sender.hasPermission("joshymc.worldflag")) return emptyList()
                when (args.size) {
                    2 -> listOf("true", "false").filter { it.startsWith(args[1].lowercase()) }
                    3 -> worldNames(args[2])
                    else -> emptyList()
                }
            }
        }
    }

    private fun regionTabComplete(args: Array<out String>): List<String> = when (args.size) {
        2 -> listOf("wand", "create", "edit", "delete", "redefine", "priority", "info", "list", "reload", "clearplaced")
            .filter { it.startsWith(args[1].lowercase()) }
        3 -> when (args[1].lowercase()) {
            "edit", "delete", "redefine", "priority", "info", "clearplaced" -> regionNames(args[2])
            "list" -> Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
            else -> emptyList()
        }
        4 -> when (args[1].lowercase()) {
            "clearplaced" -> listOf("confirm").filter { it.startsWith(args[3].lowercase()) }
            else -> emptyList()
        }
        else -> emptyList()
    }

    private fun subflagTabComplete(args: Array<out String>): List<String> = when (args.size) {
        2 -> listOf("create", "delete", "edit", "list", "info", "redefine", "priority")
            .filter { it.startsWith(args[1].lowercase()) }
        3 -> regionNames(args[2])
        4 -> {
            val parent = args.getOrNull(2)
            if (parent != null) manager.subflagsOf(parent).map { it.name }.filter { it.startsWith(args[3], ignoreCase = true) } else emptyList()
        }
        else -> emptyList()
    }
}
