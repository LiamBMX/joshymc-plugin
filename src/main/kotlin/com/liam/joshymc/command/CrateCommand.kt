package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.crate.CrateDisplayTuning
import com.liam.joshymc.crate.CrateFacing
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class CrateCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        /** Most keys one /crate givekey hands each player: a full 36-slot inventory of 64-stacks. */
        private const val MAX_GIVEKEY_AMOUNT = 36 * 64

        /** How far away the crate `/crate rotate` turns may be (same reach as `/crate setlocation`). */
        private const val ROTATE_RANGE = 5

        /** `/crate display` subcommands (issue #1517). */
        private val DISPLAY_SUBCOMMANDS = listOf("info", "facing", "rotate", "offset", "scale", "reset", "refresh")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            // Issue #1514: players get the crate browser; console (and players without
            // joshymc.crate.use) the usage. Admins see the usage with /crate help.
            if (sender is Player && sender.hasPermission("joshymc.crate.use")) plugin.crateManager.browser.open(sender)
            else sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "menu", "browse" -> {
                val player = sender as? Player ?: run { Msg.playersOnly(sender); return true }
                plugin.crateManager.browser.open(player)
            }
            "help" -> sendUsage(sender)
            "setlocation" -> handleSetLocation(sender, args)
            "removelocation" -> handleRemoveLocation(sender)
            "givekey" -> handleGiveKey(sender, args)
            "list" -> handleList(sender)
            "preview" -> handlePreview(sender, args)
            "diagnose", "inspect" -> handleDiagnose(sender)
            "locations" -> handleLocations(sender)
            "force" -> handleForceOpen(sender, args)
            "models" -> handleModels(sender, args)
            "rotate" -> handleRotate(sender, args)
            "display" -> handleDisplay(sender, args)
            "monthly" -> handleMonthly(sender, args)
            else -> sendUsage(sender)
        }
        return true
    }

    /**
     * Open a crate animation for a player without needing them to right-click
     * a registered crate block. Useful for testing whether the issue is the
     * click handler, the registration, or the crate itself.
     */
    private fun handleForceOpen(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            Msg.noPermission(sender)
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

        // Exact name only (issue #1170), and never the sender when a named target isn't online.
        val target = if (args.size >= 3) plugin.playerResolver.online(args[2]) else (sender as? Player)
        if (target == null) {
            val msg = if (args.size >= 3) Component.text("No online player is named exactly ${args[2]}.", NamedTextColor.RED)
            else Component.text("Specify a target player.", NamedTextColor.RED)
            if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
            return
        }

        // Use the target's standing block as the visual anchor for particles.
        com.liam.joshymc.staff.AdminAudit.log(plugin, sender, "CRATE_FORCE_OPEN", target.uniqueId, target.name, "crate=$crateType")
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
            Msg.noPermission(sender)
            return
        }
        val player = sender as? Player ?: run {
            Msg.playersOnly(sender)
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
            plugin.crateManager.getLocationAt(block)?.let { loc ->
                val shown = plugin.crateManager.currentFacing(loc, block).id
                plugin.commsManager.send(
                    player,
                    Component.text("Facing: ${loc.facing?.id ?: "auto"} (front shows $shown) — change with /crate rotate", NamedTextColor.GRAY)
                )
            }
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
            Msg.noPermission(sender)
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
            val line = Component.text("  • ${loc.crateType} @ ${loc.world} ${loc.x}, ${loc.y}, ${loc.z} (facing ${loc.facing?.id ?: "auto"})", NamedTextColor.GRAY)
            if (sender is Player) plugin.commsManager.send(sender, line) else sender.sendMessage(line)
        }
    }

    private fun handleSetLocation(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            Msg.noPermission(sender)
            return
        }

        val player = sender as? Player ?: run {
            sender.sendMessage(Component.text("Only players can use this command.", NamedTextColor.RED))
            return
        }

        if (args.size < 2) {
            Msg.usage(player, "/crate setlocation <type>")
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
            Msg.noPermission(sender)
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
            Msg.noPermission(sender)
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

        val amount = if (args.size >= 4) {
            val parsed = args[3].toIntOrNull()
            if (parsed == null || parsed < 1 || parsed > MAX_GIVEKEY_AMOUNT) {
                val msg = Component.text("Invalid amount '${args[3]}'. Use a whole number from 1 to $MAX_GIVEKEY_AMOUNT.", NamedTextColor.RED)
                if (sender is Player) plugin.commsManager.send(sender, msg) else sender.sendMessage(msg)
                return
            }
            parsed
        } else 1

        // Selector support: @a (all), @r (random)
        val targets: List<Player> = when (targetArg.lowercase()) {
            "@a", "*" -> plugin.server.onlinePlayers.toList()
            "@r" -> plugin.server.onlinePlayers.toList().shuffled().take(1)
            else -> {
                // Exact name only (issue #1170): a prefix match could hand the keys to someone else.
                val p = plugin.playerResolver.online(targetArg)
                if (p == null) {
                    val msg = Component.text("No online player is named exactly $targetArg. No keys were given.", NamedTextColor.RED)
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
            if (plugin.crateManager.giveKey(target, crateType, amount)) {
                given++
                com.liam.joshymc.staff.AdminAudit.log(plugin, sender, "CRATE_KEY_GIVE", target.uniqueId, target.name, "crate=$crateType amount=$amount")
            }
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
            Msg.noPermission(player)
            return
        }

        if (args.size < 2) {
            Msg.usage(player, "/crate preview <type>")
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

    /**
     * `/crate models [refresh]` (issue #1389): shows each crate's custom model status, or
     * removes every crate model display and redraws them from the current crate locations.
     */
    private fun handleModels(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            Msg.noPermission(sender)
            return
        }
        val models = plugin.crateManager.models
        when (args.getOrNull(1)?.lowercase()) {
            "refresh" -> {
                models.refreshAll()
                Msg.success(sender, "Crate models redrawn.")
            }
            // Issue #1460: monthly rotation status (current month, timezone, every month's model, fallbacks).
            "monthly" -> {
                for (line in models.monthlyLines()) sender.sendMessage(Component.text(line.trimStart(), NamedTextColor.GRAY))
                return
            }
            "set" -> {
                handleModelSet(sender, args)
                return
            }
        }
        for (line in models.describe()) {
            sender.sendMessage(Component.text(line, NamedTextColor.GRAY))
        }
    }

    /**
     * `/crate models set <crate> <model>` (issue #1460): points one existing crate's static model
     * (`crates.custom-models.models.<crate>.model`) at an item model that is in the bundled pack,
     * saves config.yml and redraws the models. Only that entry's `model` changes: the crate id,
     * keys, rewards, locations and every other entry are untouched. For a rotating crate this is
     * the model used in months whose monthly model is missing.
     */
    private fun handleModelSet(sender: CommandSender, args: Array<out String>) {
        if (args.size < 4) {
            Msg.usage(sender, "/crate models set <crate> <model>")
            return
        }
        val crateManager = plugin.crateManager
        val input = args[2].lowercase()
        val crateId = crateManager.resolveCrateId(input)
        if (crateManager.getCrate(crateId) == null) {
            Msg.error(sender, "Unknown crate: $input. Crates: ${crateManager.getCrateTypes().sorted().joinToString()}")
            return
        }
        val raw = args[3].trim().lowercase()
        val model = if (':' in raw) raw else "joshymc:$raw"
        plugin.crateManager.models.modelProblem(model)?.let {
            Msg.error(sender, "Model not changed: $it.")
            return
        }
        val entryId = crateId.lowercase()
        val path = "crates.custom-models.models.$entryId.model"
        val old = plugin.config.getString(path)
        if (old?.trim()?.lowercase() == model) {
            Msg.reply(sender, Component.text("Crate $entryId already uses $model.", NamedTextColor.GRAY))
            return
        }
        val configFile = java.io.File(plugin.dataFolder, "config.yml")
        try {
            com.liam.joshymc.util.ConfigUtil.backup(configFile, plugin.logger, "Crates")
            plugin.config.set(path, model)
            plugin.saveConfig()
        } catch (e: Exception) {
            plugin.config.set(path, old)
            Msg.error(sender, "Could not save config.yml (${e.message}); nothing changed.")
            return
        }
        plugin.logger.info("[Crates] ${sender.name} set the custom model of crate '$entryId' to $model (was ${old ?: "the default"}).")
        crateManager.restartModels()
        Msg.success(sender, "Crate $entryId now uses $model (was ${old ?: "the default"}). Models redrawn.")
        val resolved = crateManager.models.resolve(entryId)
        if (resolved.source == com.liam.joshymc.crate.CrateModels.ModelSource.MONTHLY) {
            Msg.reply(sender, Component.text("This crate rotates monthly: $model is shown only in months whose monthly model is missing.", NamedTextColor.GRAY))
        } else if (resolved.model == null) {
            Msg.reply(sender, Component.text("Not drawn yet: ${resolved.note}.", NamedTextColor.YELLOW))
        }
    }

    /**
     * `/crate monthly [status]`, `/crate monthly set <month> [7d|12h|month-end|never]` and
     * `/crate monthly reset` (issue #1520): the monthly rotation's state, and the admin override
     * that makes rotating crates use one month until it is reset or expires. Only
     * `joshymc.crate.admin`; set and reset are written to AdminAudit. The override only picks a
     * month: keys, rewards and every open check stay the same.
     */
    private fun handleMonthly(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            Msg.noPermission(sender)
            return
        }
        val models = plugin.crateManager.models
        val zone = models.settings.monthly.zone
        when (args.getOrNull(1)?.lowercase() ?: "status") {
            "status" -> for (line in models.statusLines()) sender.sendMessage(Component.text(line, NamedTextColor.GRAY))
            "set" -> {
                val monthArg = args.getOrNull(2)
                if (monthArg == null) {
                    Msg.usage(sender, "/crate monthly set <january..december> [7d|12h|month-end|never]")
                    return
                }
                val month = com.liam.joshymc.crate.CrateModels.monthOf(monthArg)
                if (month == null) {
                    Msg.error(sender, "'$monthArg' is not a month. Use january .. december.")
                    return
                }
                val expiry = when (val parsed = com.liam.joshymc.crate.CrateMonthlyRotation.parseExpiry(args.getOrNull(3), java.time.Instant.now(models.clock), zone)) {
                    is com.liam.joshymc.crate.CrateMonthlyRotation.ExpiryParse.Invalid -> { Msg.error(sender, parsed.message); return }
                    is com.liam.joshymc.crate.CrateMonthlyRotation.ExpiryParse.Ok -> parsed.expiresAt
                }
                val previous = models.override
                models.setOverride(month, expiry, sender.name)?.let {
                    Msg.error(sender, "Override not set: $it.")
                    return
                }
                val until = expiry?.let { com.liam.joshymc.crate.CrateMonthlyRotation.formatInstant(java.time.Instant.ofEpochMilli(it), zone) }
                com.liam.joshymc.staff.AdminAudit.log(plugin, sender, "CRATE_MONTHLY_OVERRIDE_SET",
                    details = "month=${com.liam.joshymc.crate.CrateModels.monthId(month)} expires_at=${expiry ?: "never"}" +
                        (previous?.let { " previous=${com.liam.joshymc.crate.CrateModels.monthId(it.month)}" } ?: ""))
                Msg.success(sender, "Monthly crates now use ${com.liam.joshymc.crate.CrateMonthlyRotation.monthName(month)}" +
                    (until?.let { " until $it" } ?: " until /crate monthly reset") + ".")
                val opens = models.settings.monthly.crates.mapNotNull { plugin.crateManager.findCrateId(it) }.distinct()
                    .joinToString { id -> "$id -> ${models.crateFor(id).crateId}" }
                if (opens.isNotEmpty()) Msg.reply(sender, Component.text("Rotating crates: $opens", NamedTextColor.GRAY))
            }
            "reset", "clear" -> {
                val previous = models.override
                models.resetOverride()?.let {
                    Msg.error(sender, "Override not reset: $it.")
                    return
                }
                com.liam.joshymc.staff.AdminAudit.log(plugin, sender, "CRATE_MONTHLY_OVERRIDE_RESET",
                    details = "previous=${previous?.let { com.liam.joshymc.crate.CrateModels.monthId(it.month) } ?: "none"}")
                Msg.success(sender, (if (previous == null) "No override was set. " else "Override removed. ") +
                    "Monthly crates use ${com.liam.joshymc.crate.CrateMonthlyRotation.monthName(models.activeMonth)} (the calendar month).")
            }
            else -> Msg.usage(sender, "/crate monthly [status|set <month> [7d|12h|month-end|never]|reset]")
        }
    }

    /**
     * `/crate rotate <north|south|east|west|auto|next>` and `/crate rotate mode [on|off]`
     * (issue #1472): sets the facing of the crate the admin is looking at (only that
     * location), or toggles quick rotation (sneak + right-click with an empty hand).
     */
    private fun handleRotate(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            Msg.noPermission(sender)
            return
        }
        val player = sender as? Player ?: run {
            Msg.playersOnly(sender)
            return
        }
        val crateManager = plugin.crateManager
        val arg = args.getOrNull(1)?.lowercase()
        if (arg == null) {
            Msg.usage(player, "/crate rotate <north|south|east|west|auto|next>  or  /crate rotate mode [on|off]")
            return
        }
        if (arg == "mode") {
            val on = when (args.getOrNull(2)?.lowercase()) {
                null -> !crateManager.isRotateMode(player)
                "on" -> true
                "off" -> false
                else -> {
                    Msg.usage(player, "/crate rotate mode [on|off]")
                    return
                }
            }
            if (on && !crateManager.quickRotateEnabled()) {
                Msg.error(player, "Quick rotation is turned off (config.yml crates.rotation.quick-rotate). Use /crate rotate <direction>.")
                crateManager.setRotateMode(player, false)
                return
            }
            crateManager.setRotateMode(player, on)
            if (on) {
                Msg.success(player, "Rotate mode on: sneak + right-click a crate with an empty hand to turn it (north → east → south → west).")
                Msg.reply(player, Component.text("Holding a key still opens crates as usual. Turn it off with /crate rotate mode off.", NamedTextColor.GRAY))
            } else {
                Msg.success(player, "Rotate mode off.")
            }
            return
        }
        val choice = CrateFacing.parseChoice(arg) ?: run {
            Msg.error(player, "Unknown direction '$arg'. Use north, south, east, west, auto or next.")
            return
        }
        val block = player.getTargetBlockExact(ROTATE_RANGE)
        if (block == null || block.type.isAir) {
            Msg.error(player, "Look at a crate block (within $ROTATE_RANGE blocks) to rotate it.")
            return
        }
        val loc = crateManager.getLocationAt(block)
        if (loc == null) {
            if (crateManager.getCrateTypeAt(block) != null) {
                Msg.error(player, "That block is next to a crate but isn't a registered crate location. Look at the registered block (see /crate diagnose).")
            } else {
                Msg.error(player, "That block isn't a registered crate (${block.type.name} at ${block.x}, ${block.y}, ${block.z}).")
            }
            return
        }
        val target = CrateFacing.target(choice, crateManager.currentFacing(loc, block))
        if (target == loc.facing && choice != CrateFacing.Choice.Next) {
            Msg.reply(player, Component.text("This crate already ${if (target == null) "uses automatic facing" else "faces ${target.id}"}.", NamedTextColor.GRAY))
            return
        }
        val change = crateManager.setCrateFacing(block, target) ?: run {
            Msg.error(player, "That block isn't a registered crate.")
            return
        }
        plugin.logger.info("[Crates] ${player.name} set the facing of the ${loc.crateType} crate at ${loc.world} ${loc.x}, ${loc.y}, ${loc.z} to ${target?.name ?: "auto"} (was ${change.previous?.name ?: "auto"}; block ${change.block.name.lowercase()}).")
        if (target == null) {
            Msg.success(player, "The ${loc.crateType} crate at ${loc.x}, ${loc.y}, ${loc.z} uses automatic facing again (it follows the crate's rotation setting or the block's facing).")
        } else {
            Msg.success(player, "The ${loc.crateType} crate at ${loc.x}, ${loc.y}, ${loc.z} now faces ${target.id}.")
        }
        val note = when (change.block) {
            CrateFacing.BlockAlign.ROTATED -> "The crate block was turned to match."
            CrateFacing.BlockAlign.DOUBLE_CHEST -> "This is half of a double chest, so only the model turns; the chest block keeps its facing."
            CrateFacing.BlockAlign.NO_FACING -> "This block has no matching facing, so only the model turns."
            CrateFacing.BlockAlign.DISABLED -> "Only the model turns (crates.rotation.rotate-block is off)."
            CrateFacing.BlockAlign.ALREADY, CrateFacing.BlockAlign.NOT_REQUESTED -> null
        }
        if (note != null) Msg.reply(player, Component.text(note, NamedTextColor.GRAY))
    }

    /**
     * `/crate display [info|facing|rotate|offset|scale|reset|refresh]` (issue #1517): views and
     * changes the display placement of the one registered crate the admin is looking at. Only
     * the drawn model moves: the crate block (except a facing change, same as /crate rotate),
     * keys, rewards and every other location are untouched.
     */
    private fun handleDisplay(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.crate.admin")) {
            Msg.noPermission(sender)
            return
        }
        val player = sender as? Player ?: run {
            Msg.playersOnly(sender)
            return
        }
        val crateManager = plugin.crateManager
        val sub = args.getOrNull(1)?.lowercase() ?: "info"
        if (sub !in DISPLAY_SUBCOMMANDS) {
            Msg.usage(player, "/crate display [info|facing|rotate|offset|scale|reset|refresh]")
            return
        }
        // Facing is the issue #1472 path: same command, same block handling, same log.
        if (sub == "facing") {
            val dir = args.getOrNull(2) ?: run {
                Msg.usage(player, "/crate display facing <north|south|east|west|auto|next>")
                return
            }
            handleRotate(sender, arrayOf("rotate", dir))
            return
        }
        val block = player.getTargetBlockExact(ROTATE_RANGE)
        if (block == null || block.type.isAir) {
            Msg.error(player, "Look at a crate block (within $ROTATE_RANGE blocks).")
            return
        }
        val loc = crateManager.getLocationAt(block)
        if (loc == null) {
            if (crateManager.getCrateTypeAt(block) != null) {
                Msg.error(player, "That block is next to a crate but isn't a registered crate location. Look at the registered block (see /crate diagnose).")
            } else {
                Msg.error(player, "That block isn't a registered crate (${block.type.name} at ${block.x}, ${block.y}, ${block.z}).")
            }
            return
        }
        val where = "${loc.crateType} crate at ${loc.x}, ${loc.y}, ${loc.z}"
        val next: CrateDisplayTuning.Tuning = when (sub) {
            "info" -> {
                Msg.reply(player, Component.text("─── Crate Display ───", NamedTextColor.GOLD))
                for (line in crateManager.models.describeLocation(loc)) {
                    Msg.reply(player, Component.text(line, NamedTextColor.GRAY))
                }
                return
            }
            "refresh" -> {
                crateManager.refreshCrateModel(block)
                Msg.success(player, "Redrew the model of the $where.")
                return
            }
            "reset" -> CrateDisplayTuning.NONE
            "rotate" -> {
                val text = args.getOrNull(2) ?: run {
                    Msg.usage(player, "/crate display rotate <degrees -180..180|default>")
                    return
                }
                when (val p = CrateDisplayTuning.parseYawAdjust(text)) {
                    is CrateDisplayTuning.Parsed.Bad -> { Msg.error(player, p.reason.replaceFirstChar { it.uppercase() } + "."); return }
                    CrateDisplayTuning.Parsed.Clear -> loc.tuning.copy(yawAdjust = 0f)
                    is CrateDisplayTuning.Parsed.Ok -> loc.tuning.copy(yawAdjust = p.value)
                }
            }
            "offset" -> {
                if (args.size == 3 && args[2].lowercase() in setOf("default", "reset")) {
                    loc.tuning.copy(offsetX = null, offsetY = null, offsetZ = null)
                } else {
                    if (args.size < 5) {
                        Msg.usage(player, "/crate display offset <x> <y> <z>  (blocks, -1..1; 'default' per axis, or /crate display offset default)")
                        return
                    }
                    when (val p = CrateDisplayTuning.withOffsets(loc.tuning, args[2], args[3], args[4])) {
                        is CrateDisplayTuning.Parsed.Bad -> { Msg.error(player, p.reason.replaceFirstChar { it.uppercase() } + "."); return }
                        CrateDisplayTuning.Parsed.Clear -> loc.tuning
                        is CrateDisplayTuning.Parsed.Ok -> p.value
                    }
                }
            }
            "scale" -> {
                val text = args.getOrNull(2) ?: run {
                    Msg.usage(player, "/crate display scale <${CrateDisplayTuning.fmt(com.liam.joshymc.crate.CrateModels.MIN_SCALE)}..${CrateDisplayTuning.fmt(com.liam.joshymc.crate.CrateModels.MAX_SCALE)}|default>")
                    return
                }
                when (val p = CrateDisplayTuning.parseScale(text)) {
                    is CrateDisplayTuning.Parsed.Bad -> { Msg.error(player, p.reason.replaceFirstChar { it.uppercase() } + "."); return }
                    CrateDisplayTuning.Parsed.Clear -> loc.tuning.copy(scale = null)
                    is CrateDisplayTuning.Parsed.Ok -> loc.tuning.copy(scale = p.value)
                }
            }
            else -> return
        }
        if (next == loc.tuning) {
            Msg.reply(player, Component.text("Nothing changed for the $where.", NamedTextColor.GRAY))
            return
        }
        // Refuse a placement that would draw the model beside its block instead of saving it.
        crateManager.models.tuningProblem(loc, next)?.let { problem ->
            Msg.error(player, "Not saved: $problem.")
            return
        }
        val updated = crateManager.setCrateTuning(block, next) ?: run {
            Msg.error(player, "That block isn't a registered crate.")
            return
        }
        plugin.logger.info("[Crates] ${player.name} set the display of the $where (${loc.world}) to $next (was ${loc.tuning}).")
        Msg.success(player, if (sub == "reset") "The $where uses its crate type's rotation, offset and scale again." else "Updated the display of the $where.")
        for (line in CrateDisplayTuning.describe(updated.tuning, crateManager.models.resolve(updated.crateType).model)) {
            Msg.reply(player, Component.text(line, NamedTextColor.GRAY))
        }
    }

    private fun sendUsage(sender: CommandSender) {
        if (sender is Player) {
            plugin.commsManager.send(sender, Component.text("Crate Commands:", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            sender.sendMessage(Component.text("Crate Commands:", NamedTextColor.GREEN))
        }
        val usages = listOf(
            "/crate  (browse crates and preview their rewards)",
            "/crate setlocation <type>",
            "/crate removelocation",
            "/crate givekey <type> [player] [amount]",
            "/crate list",
            "/crate preview <type>",
            "/crate diagnose  (look at a block to inspect)",
            "/crate locations  (list all registered crates)",
            "/crate force <type> [player]  (open a crate without clicking — testing only)",
            "/crate models [refresh]  (custom crate model status / redraw)",
            "/crate models monthly  (monthly model rotation status)",
            "/crate monthly [status]  (active monthly crate, override, next rotation)",
            "/crate monthly set <month> [7d|12h|month-end|never]  (use one month's crate for now)",
            "/crate monthly reset  (back to the calendar month)",
            "/crate models set <crate> <model>  (change a crate's custom model)",
            "/crate rotate <north|south|east|west|auto|next>  (turn the crate you look at)",
            "/crate rotate mode [on|off]  (sneak + right-click with an empty hand to turn crates)",
            "/crate display [info]  (the crate you look at: model, facing, rotation, offset, scale)",
            "/crate display facing <north|south|east|west|auto|next>",
            "/crate display rotate <degrees|default>  (fine rotation, -180..180)",
            "/crate display offset <x> <y> <z>  (blocks, -1..1, 'default' per axis)",
            "/crate display scale <0.5..2|default>",
            "/crate display reset  (back to the crate type's rotation, offset and scale)",
            "/crate display refresh  (redraw this crate's model)"
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
                val subs = mutableListOf("menu", "list", "preview", "help")
                if (sender.hasPermission("joshymc.crate.admin")) {
                    subs.addAll(listOf("setlocation", "removelocation", "givekey", "diagnose", "locations", "force", "models", "rotate", "display", "monthly"))
                }
                subs.filter { it.startsWith(args[0], ignoreCase = true) }
            }
            2 -> when (args[0].lowercase()) {
                "setlocation", "givekey", "preview", "force" ->
                    plugin.crateManager.getCrateTypes().filter { it.startsWith(args[1], ignoreCase = true) }
                "models" ->
                    if (sender.hasPermission("joshymc.crate.admin")) {
                        listOf("refresh", "monthly", "set").filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                "rotate" ->
                    if (sender.hasPermission("joshymc.crate.admin")) {
                        (CrateFacing.CHOICES + "mode").filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                "display" ->
                    if (sender.hasPermission("joshymc.crate.admin")) {
                        DISPLAY_SUBCOMMANDS.filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                "monthly" ->
                    if (sender.hasPermission("joshymc.crate.admin")) {
                        listOf("status", "set", "reset").filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "rotate" ->
                    if (sender.hasPermission("joshymc.crate.admin") && args[1].equals("mode", ignoreCase = true)) {
                        listOf("on", "off").filter { it.startsWith(args[2], ignoreCase = true) }
                    } else emptyList()
                "models" ->
                    if (sender.hasPermission("joshymc.crate.admin") && args[1].equals("set", ignoreCase = true)) {
                        plugin.crateManager.getCrateTypes().filter { it.startsWith(args[2], ignoreCase = true) }
                    } else emptyList()
                "display" ->
                    if (sender.hasPermission("joshymc.crate.admin")) {
                        when (args[1].lowercase()) {
                            "facing" -> CrateFacing.CHOICES
                            "rotate" -> listOf("-45", "-15", "15", "45", "default")
                            "offset" -> listOf("0", "0.5", "-0.5", "default")
                            "scale" -> listOf("0.75", "1", "1.25", "1.5", "default")
                            else -> emptyList()
                        }.filter { it.startsWith(args[2], ignoreCase = true) }
                    } else emptyList()
                "monthly" ->
                    if (args[1].equals("set", ignoreCase = true) && sender.hasPermission("joshymc.crate.admin")) {
                        com.liam.joshymc.crate.CrateModels.MONTH_IDS.filter { it.startsWith(args[2], ignoreCase = true) }
                    } else emptyList()
                "givekey" -> {
                    val names = plugin.server.onlinePlayers.map { it.name } + listOf("@a", "@r")
                    names.filter { it.startsWith(args[2], ignoreCase = true) }
                }
                else -> emptyList()
            }
            4 -> when (args[0].lowercase()) {
                "givekey" -> listOf("1", "5", "10", "32", "64").filter { it.startsWith(args[3], ignoreCase = true) }
                "monthly" ->
                    if (args[1].equals("set", ignoreCase = true) && sender.hasPermission("joshymc.crate.admin")) {
                        listOf("1d", "7d", "12h", "month-end", "never").filter { it.startsWith(args[3], ignoreCase = true) }
                    } else emptyList()
                "models" ->
                    if (sender.hasPermission("joshymc.crate.admin") && args[1].equals("set", ignoreCase = true)) {
                        plugin.crateManager.models.chestModelKeys().filter { it.startsWith(args[3], ignoreCase = true) }
                    } else emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
