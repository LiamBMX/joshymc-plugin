package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.ClaimManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.command.Command
import org.bukkit.inventory.ItemStack
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ClaimCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase()
        if (sub == "addblocks" || sub == "setblocks") {
            if (sender is Player) {
                if (!sender.hasPermission("joshymc.claim.admin")) {
                    plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
            }
            if (sub == "addblocks") handleAddBlocks(sender, args) else handleSetBlocks(sender, args)
            return true
        }

        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.claim")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (label.equals("unclaim", ignoreCase = true)) {
            handleUnclaim(sender); return true
        }

        when (sub) {
            "wand" -> handleWand(sender)
            "trust" -> handleTrust(sender, args)
            "untrust" -> handleUntrust(sender, args)
            "trusted" -> handleTrustedList(sender)
            "deny" -> handleDeny(sender, args)
            "undeny" -> handleUndeny(sender, args)
            "denied" -> handleDeniedList(sender)
            "show" -> handleShow(sender)
            "team" -> handleTeam(sender)
            "personal" -> handlePersonal(sender)
            "map" -> handleMap(sender)
            "info" -> handleInfo(sender)
            "list" -> handleList(sender)
            "blocks" -> handleBlocks(sender, args)
            "expand" -> handleExpand(sender, args)
            "shrink" -> handleShrink(sender, args)
            else -> showHelp(sender)
        }
        return true
    }

    private fun reply(sender: CommandSender, message: Component) {
        if (sender is Player) plugin.commsManager.send(sender, message) else sender.sendMessage(message)
    }

    /**
     * `/claim addblocks <player> <amount>` — admin command to grant claim
     * blocks. Negative amounts work (subtract). Used by ops to reward
     * players or refund mistakenly-spent blocks. Console-runnable so vote
     * listeners can hand out claim blocks.
     */
    private fun handleAddBlocks(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val amount = args.getOrNull(2)?.toIntOrNull()
        if (targetName == null || amount == null) {
            reply(sender, Component.text("Usage: /claim addblocks <player> <amount>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && target.player == null) {
            reply(sender, Component.text("Unknown player: $targetName", NamedTextColor.RED))
            return
        }
        plugin.claimManager.addBlocks(target.uniqueId, amount)
        val newTotal = plugin.claimManager.getTotalBlocks(target.uniqueId)
        reply(
            sender,
            Component.text("Gave ", NamedTextColor.GREEN)
                .append(Component.text("$amount", NamedTextColor.GOLD))
                .append(Component.text(" claim block${if (amount == 1) "" else "s"} to ${target.name ?: targetName}. ", NamedTextColor.GREEN))
                .append(Component.text("(new total: $newTotal)", NamedTextColor.GRAY))
        )
        target.player?.let { onlineTarget ->
            plugin.commsManager.send(
                onlineTarget,
                Component.text("You received ", NamedTextColor.GREEN)
                    .append(Component.text("$amount", NamedTextColor.GOLD))
                    .append(Component.text(" claim block${if (amount == 1) "" else "s"}. ", NamedTextColor.GREEN))
                    .append(Component.text("(total: $newTotal)", NamedTextColor.GRAY))
            )
        }
    }

    /** `/claim setblocks <player> <amount>` — admin command to overwrite. */
    private fun handleSetBlocks(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val amount = args.getOrNull(2)?.toIntOrNull()
        if (targetName == null || amount == null || amount < 0) {
            reply(sender, Component.text("Usage: /claim setblocks <player> <amount>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && target.player == null) {
            reply(sender, Component.text("Unknown player: $targetName", NamedTextColor.RED))
            return
        }
        plugin.claimManager.setBlocks(target.uniqueId, amount)
        reply(
            sender,
            Component.text("Set ${target.name ?: targetName}'s claim block total to ", NamedTextColor.GREEN)
                .append(Component.text("$amount", NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
    }

    private fun handleTrust(player: Player, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            plugin.commsManager.send(player, Component.text("Usage: /claim trust <player>", NamedTextColor.RED))
            return
        }
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(player, Component.text("You must own this claim.", NamedTextColor.RED)); return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (plugin.claimManager.trustPlayer(claim, target.uniqueId)) {
            plugin.commsManager.send(player, Component.text("Trusted $targetName on this claim.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("$targetName is already trusted.", NamedTextColor.RED))
        }
    }

    private fun handleUntrust(player: Player, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            plugin.commsManager.send(player, Component.text("Usage: /claim untrust <player>", NamedTextColor.RED))
            return
        }
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(player, Component.text("You must own this claim.", NamedTextColor.RED)); return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (plugin.claimManager.untrustPlayer(claim, target.uniqueId)) {
            plugin.commsManager.send(player, Component.text("Untrusted $targetName from this claim.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("$targetName is not trusted.", NamedTextColor.RED))
        }
    }

    private fun handleTrustedList(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        val trusted = plugin.claimManager.getTrustedPlayers(claim)
        if (trusted.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No trusted players on this claim.", NamedTextColor.GRAY))
            return
        }
        val names = trusted.mapNotNull { Bukkit.getOfflinePlayer(it).name }.joinToString(", ")
        plugin.commsManager.send(player, Component.text("Trusted: ", NamedTextColor.GREEN).append(Component.text(names, NamedTextColor.WHITE)))
    }

    private fun handleDeny(player: Player, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            plugin.commsManager.send(player, Component.text("Usage: /claim deny <player>", NamedTextColor.RED))
            return
        }
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(player, Component.text("You must own this claim.", NamedTextColor.RED)); return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (target.uniqueId == claim.ownerUuid) {
            plugin.commsManager.send(player, Component.text("You cannot deny the claim owner.", NamedTextColor.RED)); return
        }
        if (plugin.claimManager.denyPlayer(claim, target.uniqueId)) {
            plugin.claimManager.untrustPlayer(claim, target.uniqueId)
            plugin.commsManager.send(player, Component.text("Denied $targetName from entering this claim.", NamedTextColor.GREEN))
            Bukkit.getPlayer(target.uniqueId)?.let { online ->
                if (plugin.claimManager.isDenied(online, online.location)) {
                    online.teleport(online.location)
                }
            }
        } else {
            plugin.commsManager.send(player, Component.text("$targetName is already denied.", NamedTextColor.RED))
        }
    }

    private fun handleUndeny(player: Player, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            plugin.commsManager.send(player, Component.text("Usage: /claim undeny <player>", NamedTextColor.RED))
            return
        }
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(player, Component.text("You must own this claim.", NamedTextColor.RED)); return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (plugin.claimManager.undenyPlayer(claim, target.uniqueId)) {
            plugin.commsManager.send(player, Component.text("$targetName can now enter this claim.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("$targetName is not denied.", NamedTextColor.RED))
        }
    }

    private fun handleDeniedList(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        val denied = plugin.claimManager.getDeniedPlayers(claim)
        if (denied.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No denied players on this claim.", NamedTextColor.GRAY))
            return
        }
        val names = denied.mapNotNull { Bukkit.getOfflinePlayer(it).name }.joinToString(", ")
        plugin.commsManager.send(player, Component.text("Denied: ", NamedTextColor.RED).append(Component.text(names, NamedTextColor.WHITE)))
    }

    private fun handleWand(player: Player) {
        val wand = ItemStack(Material.GOLDEN_SHOVEL)
        wand.editMeta { meta ->
            meta.displayName(
                Component.text("Claim Wand", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Right-click a block to set corner 1", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  Right-click again to set corner 2", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  Claim is created automatically!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
            meta.setEnchantmentGlintOverride(true)
        }
        player.inventory.addItem(wand)
        plugin.commsManager.send(player, Component.text("Claim wand added to your inventory! Right-click two corners to claim.", NamedTextColor.GREEN))
    }

    private fun handleShow(player: Player) {
        val on = plugin.claimManager.toggleParticles(player)
        val msg = if (on) "Claim borders enabled. Look for particles!" else "Claim borders disabled."
        plugin.commsManager.send(player, Component.text(msg, if (on) NamedTextColor.GREEN else NamedTextColor.GRAY))
    }

    private fun handleUnclaim(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (plugin.claimManager.deleteClaim(player, claim)) {
            plugin.commsManager.send(player, Component.text("Claim removed. ${claim.area} blocks freed.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("You don't own this claim.", NamedTextColor.RED))
        }
    }

    private fun handleTeam(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (plugin.claimManager.assignToTeam(player, claim)) {
            plugin.commsManager.send(player, Component.text("Claim assigned to your team.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("You must own this claim and be in a team.", NamedTextColor.RED))
        }
    }

    private fun handlePersonal(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (plugin.claimManager.assignToPersonal(player, claim)) {
            plugin.commsManager.send(player, Component.text("Claim set to personal.", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("You must own this claim.", NamedTextColor.RED))
        }
    }

    private fun handleMap(player: Player) {
        for (line in plugin.claimManager.buildClaimMap(player)) {
            player.sendMessage(plugin.commsManager.parseLegacy(line))
        }
    }

    private fun handleInfo(player: Player) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("This area is not claimed.", NamedTextColor.GRAY)); return }

        val ownerName = Bukkit.getOfflinePlayer(claim.ownerUuid).name ?: "Unknown"
        val subs = plugin.claimManager.getSubclaimsInClaim(claim)
        val msg = Component.text()
            .append(Component.text("--- Claim Info ---\n", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
            .append(Component.text("  Owner: ", NamedTextColor.GRAY)).append(Component.text("$ownerName\n", NamedTextColor.WHITE))
            .append(Component.text("  Size: ", NamedTextColor.GRAY)).append(Component.text("${claim.maxX - claim.minX + 1}x${claim.maxZ - claim.minZ + 1} (${claim.area} blocks)\n", NamedTextColor.WHITE))
            .append(Component.text("  Corners: ", NamedTextColor.GRAY)).append(Component.text("(${claim.minX}, ${claim.minZ}) to (${claim.maxX}, ${claim.maxZ})\n", NamedTextColor.DARK_GRAY))
        if (claim.teamName != null) msg.append(Component.text("  Team: ", NamedTextColor.GRAY)).append(Component.text("${claim.teamName}\n", NamedTextColor.AQUA))
        msg.append(Component.text("  Subclaims: ", NamedTextColor.GRAY)).append(Component.text("${subs.size}", NamedTextColor.WHITE))
        plugin.commsManager.send(player, msg.build())
    }

    private fun handleList(player: Player) {
        val claims = plugin.claimManager.getClaimsByPlayer(player.uniqueId)
        if (claims.isEmpty()) { plugin.commsManager.send(player, Component.text("You have no claims. Use a golden shovel to create one!", NamedTextColor.GRAY)); return }
        val msg = Component.text()
            .append(Component.text("--- Your Claims (${claims.size}) ---\n", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
        for (claim in claims) {
            msg.append(Component.text("  #${claim.id} ", NamedTextColor.GOLD))
                .append(Component.text("${claim.world} (${claim.minX},${claim.minZ})→(${claim.maxX},${claim.maxZ})", NamedTextColor.GRAY))
                .append(Component.text(" ${claim.area} blocks", NamedTextColor.WHITE))
            if (claim.teamName != null) msg.append(Component.text(" [${claim.teamName}]", NamedTextColor.AQUA))
            msg.append(Component.newline())
        }
        plugin.commsManager.send(player, msg.build())
    }

    private fun handleBlocks(player: Player, args: Array<out String>) {
        if (args.size <= 1 || args.getOrNull(1)?.lowercase() != "give") {
            val total = plugin.claimManager.getTotalBlocks(player.uniqueId)
            val used = plugin.claimManager.getUsedBlocks(player.uniqueId)
            plugin.commsManager.send(player,
                Component.text("Claim Blocks: ", NamedTextColor.GRAY)
                    .append(Component.text("${total - used}", NamedTextColor.GREEN))
                    .append(Component.text(" available / ", NamedTextColor.GRAY))
                    .append(Component.text("$used", NamedTextColor.YELLOW))
                    .append(Component.text(" used / ", NamedTextColor.GRAY))
                    .append(Component.text("$total", NamedTextColor.WHITE))
                    .append(Component.text(" total", NamedTextColor.GRAY))
            ); return
        }
        if (!player.hasPermission("joshymc.claim.admin")) { plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED)); return }
        val targetName = args.getOrNull(2); val amount = args.getOrNull(3)?.toIntOrNull()
        if (targetName == null || amount == null || amount <= 0) { plugin.commsManager.send(player, Component.text("Usage: /claim blocks give <player> <amount>", NamedTextColor.RED)); return }
        plugin.claimManager.addBlocks(Bukkit.getOfflinePlayer(targetName).uniqueId, amount)
        plugin.commsManager.send(player, Component.text("Gave $amount claim blocks to $targetName.", NamedTextColor.GREEN))
    }

    /** Parses `[direction] <amount>` from args starting at startIdx.
     *  Direction defaults to player's facing if omitted. */
    private fun parseDirectionAmount(player: Player, args: Array<out String>, startIdx: Int): Pair<ClaimManager.ClaimDirection?, Int?> {
        val a = args.drop(startIdx)
        return when {
            a.isEmpty() -> null to null
            a.size == 1 -> {
                val amount = a[0].toIntOrNull() ?: return null to null
                val face = player.facing
                val dir = when (face) {
                    BlockFace.NORTH -> ClaimManager.ClaimDirection.NORTH
                    BlockFace.SOUTH -> ClaimManager.ClaimDirection.SOUTH
                    BlockFace.EAST  -> ClaimManager.ClaimDirection.EAST
                    BlockFace.WEST  -> ClaimManager.ClaimDirection.WEST
                    else -> null
                }
                dir to amount
            }
            else -> {
                val dir = ClaimManager.ClaimDirection.from(a[0]) ?: return null to null
                val amount = a[1].toIntOrNull() ?: return null to null
                dir to amount
            }
        }
    }

    private fun handleExpand(player: Player, args: Array<out String>) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(player, Component.text("You don't own this claim.", NamedTextColor.RED)); return
        }
        val (direction, amount) = parseDirectionAmount(player, args, 1)
        if (direction == null || amount == null) {
            plugin.commsManager.send(player, Component.text("Usage: /claim expand [north|south|east|west] <amount>", NamedTextColor.RED)); return
        }
        when (val result = plugin.claimManager.expandClaim(player, claim, direction, amount)) {
            is ClaimManager.ClaimResizeResult.Success -> {
                player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f)
                plugin.commsManager.send(player,
                    Component.text("Claim expanded ${direction.name.lowercase()} by $amount block${if (amount == 1) "" else "s"}. ", NamedTextColor.GREEN)
                        .append(Component.text("(${result.blocksDelta} used, ${plugin.claimManager.getAvailableBlocks(player.uniqueId)} remaining)", NamedTextColor.GRAY))
                )
            }
            is ClaimManager.ClaimResizeResult.Failure -> {
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                plugin.commsManager.send(player, Component.text(result.reason, NamedTextColor.RED))
            }
        }
    }

    private fun handleShrink(player: Player, args: Array<out String>) {
        val claim = plugin.claimManager.getClaimAt(player.location)
        if (claim == null) { plugin.commsManager.send(player, Component.text("You're not standing in a claim.", NamedTextColor.RED)); return }
        if (claim.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(player, Component.text("You don't own this claim.", NamedTextColor.RED)); return
        }
        val (direction, amount) = parseDirectionAmount(player, args, 1)
        if (direction == null || amount == null) {
            plugin.commsManager.send(player, Component.text("Usage: /claim shrink [north|south|east|west] <amount>", NamedTextColor.RED)); return
        }
        when (val result = plugin.claimManager.shrinkClaim(player, claim, direction, amount)) {
            is ClaimManager.ClaimResizeResult.Success -> {
                val refund = -result.blocksDelta
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f)
                plugin.commsManager.send(player,
                    Component.text("Claim shrunk from the ${direction.name.lowercase()} by $amount block${if (amount == 1) "" else "s"}. ", NamedTextColor.GREEN)
                        .append(Component.text("($refund block${if (refund == 1) "" else "s"} freed, ${plugin.claimManager.getAvailableBlocks(player.uniqueId)} available)", NamedTextColor.GRAY))
                )
            }
            is ClaimManager.ClaimResizeResult.Failure -> {
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                plugin.commsManager.send(player, Component.text(result.reason, NamedTextColor.RED))
            }
        }
    }

    private fun showHelp(player: Player) {
        val gold = TextColor.color(0xFFD700)
        val msg = Component.text()
            .append(Component.text("--- Claims Help ---\n", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("How to claim:\n", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.text("  1. Get a wand: ", NamedTextColor.GRAY)).append(Component.text("/claim wand\n", NamedTextColor.GOLD))
            .append(Component.text("  2. Right-click one corner\n", NamedTextColor.GRAY))
            .append(Component.text("  3. Right-click the opposite corner\n", NamedTextColor.GRAY))
            .append(Component.text("  4. Claim created!\n\n", NamedTextColor.GREEN))
            .append(Component.text("Commands:\n", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.text("  /claim wand", NamedTextColor.YELLOW)).append(Component.text(" — get a claim wand\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim show", NamedTextColor.YELLOW)).append(Component.text(" — toggle border particles\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim info", NamedTextColor.YELLOW)).append(Component.text(" — claim info\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim list", NamedTextColor.YELLOW)).append(Component.text(" — your claims\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim blocks", NamedTextColor.YELLOW)).append(Component.text(" — claim block balance\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim expand [dir] <amount>", NamedTextColor.YELLOW)).append(Component.text(" — expand claim\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim shrink [dir] <amount>", NamedTextColor.YELLOW)).append(Component.text(" — shrink claim edge\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim deny <player>", NamedTextColor.YELLOW)).append(Component.text(" — ban player from entering\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim undeny <player>", NamedTextColor.YELLOW)).append(Component.text(" — remove entry ban\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim team", NamedTextColor.YELLOW)).append(Component.text(" — share with team\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim personal", NamedTextColor.YELLOW)).append(Component.text(" — un-share\n", NamedTextColor.GRAY))
            .append(Component.text("  /claim map", NamedTextColor.YELLOW)).append(Component.text(" — nearby claims\n", NamedTextColor.GRAY))
            .append(Component.text("  /unclaim", NamedTextColor.YELLOW)).append(Component.text(" — remove claim\n\n", NamedTextColor.GRAY))
            .append(Component.text("You earn blocks by playing! ", NamedTextColor.DARK_GRAY))
            .append(Component.text("(${plugin.claimManager.getAvailableBlocks(player.uniqueId)} available)", NamedTextColor.GREEN))
        plugin.commsManager.send(player, msg.build())
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (alias.equals("unclaim", true)) return emptyList()
        val directions = listOf("north", "south", "east", "west")
        return when (args.size) {
            1 -> listOf("wand", "trust", "untrust", "trusted", "deny", "undeny", "denied", "show", "team", "personal", "map", "info", "list", "blocks", "expand", "shrink", "help").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "blocks" -> listOf("give").filter { it.startsWith(args[1].lowercase()) }
                "trust", "untrust", "deny", "undeny" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                "expand", "shrink" -> directions.filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            3 -> when {
                args[0].equals("blocks", true) && args[1].equals("give", true) ->
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) }
                args[0].lowercase() in listOf("expand", "shrink") && args[1].lowercase() in directions -> emptyList()
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
