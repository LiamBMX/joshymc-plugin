package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.WorldFlagManager
import com.liam.joshymc.manager.WorldFlagManager.WorldFlag
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
 * `/worldflags` — region-based WorldFlags management (cuboid regions with
 * priority + flag inheritance, layered on top of the world-wide `/worldflag`
 * flags). See [WorldFlagManager] for resolution rules.
 */
class WorldFlagsCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val manager get() = plugin.worldFlagManager

    // player UUID -> (region name, requested-at ms), for /worldflags clearplaced confirmation
    private val pendingClear = ConcurrentHashMap<UUID, Pair<String, Long>>()
    private val confirmWindowMs = 30_000L

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission(WorldFlagManager.ADMIN_PERMISSION)) {
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
            "edit" -> handleEdit(sender, args)
            "delete" -> handleDelete(sender, args)
            "redefine" -> handleRedefine(sender, args)
            "priority" -> handlePriority(sender, args)
            "info" -> handleInfo(sender, args)
            "list" -> handleList(sender, args)
            "reload" -> handleReload(sender)
            "clearplaced" -> handleClearPlaced(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    // ──────────────────────────────────────────────
    //  Handlers
    // ──────────────────────────────────────────────

    private fun handleWand(sender: CommandSender) {
        val player = playerOnly(sender) ?: return
        player.inventory.addItem(manager.createWand())
        player.sendMessage(
            Component.text("WorldFlags wand added. Left-click sets Position 1, right-click sets Position 2.", NamedTextColor.GREEN)
        )
    }

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val name = args.getOrNull(1)
        if (name == null) {
            player.sendMessage(Component.text("Usage: /worldflags create <name>", NamedTextColor.RED))
            return
        }
        if (manager.getRegion(name) != null) {
            player.sendMessage(Component.text("A region named '$name' already exists.", NamedTextColor.RED))
            return
        }
        val selection = manager.getSelection(player)
        if (selection == null) {
            player.sendMessage(
                Component.text("Select both corners with the WorldFlags wand first (/worldflags wand).", NamedTextColor.RED)
            )
            return
        }
        val (min, max) = boundsOf(selection)
        val world = selection.first.world!!.name
        if (!manager.createRegion(name, world, min, max)) {
            player.sendMessage(Component.text("A region named '$name' already exists.", NamedTextColor.RED))
            return
        }
        manager.clearSelection(player)
        player.sendMessage(Component.text("Region '$name' created in $world.", NamedTextColor.GREEN))
    }

    private fun handleRedefine(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val name = args.getOrNull(1)
        if (name == null) {
            player.sendMessage(Component.text("Usage: /worldflags redefine <name>", NamedTextColor.RED))
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
                Component.text("Select both corners with the WorldFlags wand first (/worldflags wand).", NamedTextColor.RED)
            )
            return
        }
        if (selection.first.world!!.name != region.world) {
            player.sendMessage(Component.text("Your selection must be in the same world as '${region.name}' (${region.world}).", NamedTextColor.RED))
            return
        }
        val (min, max) = boundsOf(selection)
        manager.redefineRegion(region.name, min, max)
        manager.clearSelection(player)
        player.sendMessage(Component.text("Region '${region.name}' redefined.", NamedTextColor.GREEN))
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
        if (name == null) {
            sender.sendMessage(Component.text("Usage: /worldflags delete <name>", NamedTextColor.RED))
            return
        }
        if (!manager.deleteRegion(name)) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("Region '$name' deleted.", NamedTextColor.GREEN))
    }

    private fun handleEdit(sender: CommandSender, args: Array<out String>) {
        val player = playerOnly(sender) ?: return
        val name = args.getOrNull(1)
        if (name == null) {
            player.sendMessage(Component.text("Usage: /worldflags edit <name>", NamedTextColor.RED))
            return
        }
        if (manager.getRegion(name) == null) {
            player.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        openEditorGui(player, name)
    }

    private fun handlePriority(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
        val priority = args.getOrNull(2)?.toIntOrNull()
        if (name == null || priority == null) {
            sender.sendMessage(Component.text("Usage: /worldflags priority <name> <number>", NamedTextColor.RED))
            return
        }
        if (!manager.setPriority(name, priority)) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text("Priority of '$name' set to $priority.", NamedTextColor.GREEN))
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
        if (name == null) {
            sender.sendMessage(Component.text("Usage: /worldflags info <name>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        sender.sendMessage(Component.text(region.name, NamedTextColor.GOLD, TextDecoration.BOLD))
        sender.sendMessage(Component.text(" World: ${region.world}", NamedTextColor.GRAY))
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

    private fun handleList(sender: CommandSender, args: Array<out String>) {
        val worldFilter = args.getOrNull(1)
        val list = if (worldFilter != null) manager.regionsInWorld(worldFilter) else manager.allRegions()
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("No regions defined${if (worldFilter != null) " in $worldFilter" else ""}.", NamedTextColor.GRAY))
            return
        }
        sender.sendMessage(Component.text("WorldFlags regions:", NamedTextColor.GOLD))
        list.forEach { region ->
            sender.sendMessage(
                Component.text(" ${region.name} ", NamedTextColor.WHITE)
                    .append(Component.text("(${region.world}, priority ${region.priority})", NamedTextColor.GRAY))
            )
        }
    }

    private fun handleReload(sender: CommandSender) {
        manager.reloadRegions()
        sender.sendMessage(Component.text("WorldFlags regions reloaded.", NamedTextColor.GREEN))
    }

    private fun handleClearPlaced(sender: CommandSender, args: Array<out String>) {
        val name = args.getOrNull(1)
        if (name == null) {
            sender.sendMessage(Component.text("Usage: /worldflags clearplaced <region>", NamedTextColor.RED))
            return
        }
        val region = manager.getRegion(name)
        if (region == null) {
            sender.sendMessage(Component.text("No region named '$name'.", NamedTextColor.RED))
            return
        }
        val isConfirm = args.getOrNull(2)?.equals("confirm", ignoreCase = true) == true

        if (sender !is Player) {
            // Console: require the literal "confirm" argument up front, no time window needed.
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
                    Component.text("Run /worldflags clearplaced ${region.name} first, then confirm within 30s.", NamedTextColor.RED)
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
            Component.text("Run /worldflags clearplaced ${region.name} confirm within 30s to proceed.", NamedTextColor.YELLOW)
        )
    }

    // ──────────────────────────────────────────────
    //  Region editor GUI
    // ──────────────────────────────────────────────

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

        plugin.guiManager.open(player, gui)
    }

    private fun infoItem(region: WorldFlagManager.WorldFlagRegion): ItemStack {
        val item = ItemStack(Material.BOOK)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text(region.name, NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
        )
        meta.lore(listOf(
            Component.text("World: ${region.world}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Priority: ${region.priority}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text(
                "Bounds: (${region.minX},${region.minY},${region.minZ}) to (${region.maxX},${region.maxY},${region.maxZ})",
                NamedTextColor.GRAY
            ).decoration(TextDecoration.ITALIC, false),
        ))
        item.itemMeta = meta
        return item
    }

    private fun flagItem(region: WorldFlagManager.WorldFlagRegion, flag: WorldFlag): ItemStack {
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

    private fun priorityItem(region: WorldFlagManager.WorldFlagRegion, direction: Int): ItemStack {
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

    private fun closeItem(): ItemStack {
        val item = ItemStack(Material.BARRIER)
        val meta = item.itemMeta!!
        meta.displayName(Component.text("Close", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
        item.itemMeta = meta
        return item
    }

    // ──────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────

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

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("WorldFlags — region-based protection:", NamedTextColor.GOLD))
        listOf(
            "/worldflags wand" to "Get the region selection wand",
            "/worldflags create <name>" to "Create a region from your wand selection",
            "/worldflags edit <name>" to "Open the region flag editor",
            "/worldflags redefine <name>" to "Redefine a region's bounds from your selection",
            "/worldflags delete <name>" to "Delete a region",
            "/worldflags priority <name> <n>" to "Set a region's priority (higher wins)",
            "/worldflags info <name>" to "Show a region's details",
            "/worldflags list [world]" to "List regions",
            "/worldflags reload" to "Reload regions from storage",
            "/worldflags clearplaced <name>" to "Reset player-placed block tracking for a region",
        ).forEach { (usage, desc) ->
            sender.sendMessage(Component.text(" $usage ", NamedTextColor.YELLOW).append(Component.text("- $desc", NamedTextColor.GRAY)))
        }
    }

    // ──────────────────────────────────────────────
    //  Tab completion
    // ──────────────────────────────────────────────

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission(WorldFlagManager.ADMIN_PERMISSION)) return emptyList()

        return when (args.size) {
            1 -> listOf("wand", "create", "edit", "delete", "redefine", "priority", "info", "list", "reload", "clearplaced")
                .filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "edit", "delete", "redefine", "priority", "info", "clearplaced" -> regionNames(args[1])
                "list" -> Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "clearplaced" -> listOf("confirm").filter { it.startsWith(args[2].lowercase()) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    private fun regionNames(prefix: String): List<String> =
        manager.allRegions().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
}
