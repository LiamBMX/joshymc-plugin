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
            "diagnose", "inspect" -> handleDiagnose(sender)
            "locations" -> handleLocations(sender)
            "force" -> handleForceOpen(sender, args)
            "use", "open" -> handleUseKey(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    /**
     * Player-facing fallback for when right-clicking the crate block doesn't
     * work (some other plugin intercepts it, etc.). The player must hold the
     * matching key in their main hand — we consume it and open the crate.
     */
    private fun handleUseKey(sender: CommandSender, args: Array<out String>) {
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }
        if (!player.hasPermission("joshymc.crate.use")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val held = player.inventory.itemInMainHand
        val heldType = plugin.crateManager.getKeyType(held)

        // If the player typed a crate type, validate the key matches.
        val crateType = if (args.size >= 2) args[1].lowercase() else heldType
        if (crateType == null) {
            plugin.commsManager.send(player, Component.text("Hold a crate key, or specify a type: /crate use <type>", NamedTextColor.RED))
            return
        }
        if (plugin.crateManager.getCrate(crateType) == null) {
            plugin.commsManager.send(player, Component.text("Unknown crate type: $crateType", NamedTextColor.RED))
            return
        }
        if (heldType != crateType) {
            plugin.commsManager.send(
                player,
                Component.text("You need to hold a ", NamedTextColor.RED)
                    .append(Component.text("$crateType key", TextColor.color(0xFFAA00)))
                    .append(Component.text(" to use it.", NamedTextColor.RED))
            )
            return
        }

        // Sanity checks before consuming the key
        if (player.inventory.firstEmpty() == -1 && held.amount > 1) {
            plugin.commsManager.send(player, Component.text("Your inventory is full — clear some space first.", NamedTextColor.RED))
            return
        }

        // Consume the key (unless SELECT-mode — animation only consumes on pick)
        plugin.crateManager.consumeOneKeyIfAuto(player, crateType)
        plugin.crateManager.openCrate(player, crateType, player.location.block)
    }

    /**
     * Open a crate animation for a player without needing them to right-click
     * a registered crate block. Useful for testing whether the issue is the
     * click handler, the registration, or the crate itself.
     */
    private fun handleForceOpen(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            if (sender is Player) plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        if (args.size < 2) {
            val msg = Component.text("Usage: /crate force <type> [player]", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        val crateType = args[1].lowercase()
        if (plugin.crateManager.getCrate(crateType) == null) {
            val msg = Component.text("Unknown crate type: $crateType", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        val target = if (args.size >= 3) plugin.server.getPlayer(args[2]) else (sender as? Player)
        if (target == null) {
            val msg = Component.text("Specify a target player.", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        // Use the target's standing block as the visual anchor for particles.
        plugin.crateManager.openCrate(target, crateType, target.location.block)
        val msg = Component.text("Opening $crateType crate for ${target.name}.", NamedTextColor.GREEN)
        if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
    }

    /**
     * Inspect the block the player is looking at — shows whether it's a
     * registered crate, what type, and any nearby (within 1 block) registered
     * crates so admins can spot off-by-one registrations.
     */
    private fun handleDiagnose(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            if (sender is Player) plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }

        val block = player.getTargetBlockExact(8)
        if (block == null || block.type.isAir) {
            plugin.commsManager.send(player, Component.text("Look at a block first (within 8 blocks).", NamedTextColor.RED))
            return
        }

        plugin.commsManager.send(player, Component.text("─── Crate Diagnose ───", NamedTextColor.GOLD))
        plugin.commsManager.send(player, Component.text("World: ${block.world.name}", NamedTextColor.GRAY))
        plugin.commsManager.send(player, Component.text("Block: ${block.type.name} @ ${block.x}, ${block.y}, ${block.z}", NamedTextColor.GRAY))

        val type = plugin.crateManager.getCrateTypeAt(block)
        if (type != null) {
            plugin.commsManager.send(
                player,
                Component.text("✔ Registered as crate: ", NamedTextColor.GREEN)
                    .append(Component.text(type, TextColor.color(0x55FFFF)))
            )
        } else {
            plugin.commsManager.send(
                player,
                Component.text("✘ Not registered. Use ", NamedTextColor.RED)
                    .append(Component.text("/crate setlocation <type>", NamedTextColor.YELLOW))
                    .append(Component.text(" while looking at this block.", NamedTextColor.RED))
            )

            // Look for nearby registrations the user may have meant
            val nearby = (-1..1).flatMap { dy ->
                listOf(
                    block.world.getBlockAt(block.x, block.y + dy, block.z)
                ).mapNotNull { b ->
                    if (b == block) null else plugin.crateManager.getCrateTypeAt(b)?.let { t -> b to t }
                }
            }
            if (nearby.isNotEmpty()) {
                plugin.commsManager.send(player, Component.text("Nearby registered crates:", NamedTextColor.YELLOW))
                for ((b, t) in nearby) {
                    plugin.commsManager.send(player, Component.text("  • ${b.x}, ${b.y}, ${b.z}: $t", NamedTextColor.GRAY))
                }
            }
        }

        val held = player.inventory.itemInMainHand
        if (plugin.crateManager.isKey(held)) {
            val keyType = plugin.crateManager.getKeyType(held)
            plugin.commsManager.send(
                player,
                Component.text("Key in hand: ", NamedTextColor.GRAY)
                    .append(Component.text(keyType ?: "unknown", TextColor.color(0xFFAA00)))
            )
        } else {
            plugin.commsManager.send(player, Component.text("Hold a crate key to verify it's recognized.", NamedTextColor.DARK_GRAY))
        }
    }

    /**
     * List every registered crate location so the user can verify their /crate
     * setlocation commands actually persisted.
     */
    private fun handleLocations(sender: CommandSender) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            if (sender is Player) plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        val locations = plugin.crateManager.getAllLocations()
        if (locations.isEmpty()) {
            val msg = Component.text("No crate locations registered.", NamedTextColor.GRAY)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }
        val header = Component.text("Registered crate locations (${locations.size}):", NamedTextColor.GOLD)
        if (sender is Player) plugin.commsManager.send(sender, header) else sender.sendMessage(header)
        for (loc in locations) {
            val line = Component.text("  • ${loc.crateType} @ ${loc.world} ${loc.x}, ${loc.y}, ${loc.z}", NamedTextColor.GRAY)
            if (sender is Player) plugin.commsManager.send(sender, line) else sender.sendMessage(line)
        }
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

            // If this is half of a double chest, register the other half too so
            // right-clicking either side opens the crate.
            val pairedHalf = findDoubleChestPartner(block)
            if (pairedHalf != null && plugin.crateManager.getCrateTypeAt(pairedHalf) == null) {
                if (plugin.crateManager.setCrateLocation(pairedHalf, crateType)) {
                    plugin.commsManager.send(
                        player,
                        Component.text(
                            "  + paired half registered at ${pairedHalf.x}, ${pairedHalf.y}, ${pairedHalf.z}.",
                            NamedTextColor.GREEN
                        )
                    )
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("Failed to set crate location.", NamedTextColor.RED))
        }
    }

    /**
     * If [block] is half of a double chest, return its partner block; otherwise null.
     * Works on both regular and trapped chests.
     */
    private fun findDoubleChestPartner(block: org.bukkit.block.Block): org.bukkit.block.Block? {
        val state = block.state as? org.bukkit.block.Chest ?: return null
        val inv = state.inventory as? org.bukkit.inventory.DoubleChestInventory ?: return null
        val left = inv.leftSide.location
        val right = inv.rightSide.location
        val target = block.location
        return when {
            left != null && (left.blockX != target.blockX || left.blockY != target.blockY || left.blockZ != target.blockZ)
                -> left.block
            right != null && (right.blockX != target.blockX || right.blockY != target.blockY || right.blockZ != target.blockZ)
                -> right.block
            else -> null
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
            "/crate use [type]  (open the crate matching the key in your hand)",
            "/crate preview <type>",
            "/crate setlocation <type>",
            "/crate removelocation",
            "/crate givekey <type> [player] [amount]",
            "/crate list",
            "/crate diagnose  (look at a block to inspect)",
            "/crate locations  (list all registered crates)",
            "/crate force <type> [player]  (open a crate without clicking)"
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
                val subs = mutableListOf("list", "preview", "use", "open")
                if (sender.hasPermission("joshymc.crate.admin")) {
                    subs.addAll(listOf("setlocation", "removelocation", "givekey", "diagnose", "locations", "force"))
                }
                subs.filter { it.startsWith(args[0], ignoreCase = true) }
            }
            2 -> when (args[0].lowercase()) {
                "setlocation", "givekey", "preview", "force", "use", "open" ->
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
