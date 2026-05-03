package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.inventory.ItemStack
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class ClaimCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.claim")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (label.equals("unclaim", ignoreCase = true)) {
            handleUnclaim(sender); return true
        }

        when (args.getOrNull(0)?.lowercase()) {
            "wand" -> handleWand(sender)
            "trust" -> handleTrust(sender, args)
            "untrust" -> handleUntrust(sender, args)
            "trusted" -> handleTrustedList(sender)
            "show" -> handleShow(sender)
            "team" -> handleTeam(sender)
            "personal" -> handlePersonal(sender)
            "map" -> handleMap(sender)
            "info" -> handleInfo(sender)
            "list" -> handleList(sender)
            "blocks" -> handleBlocks(sender, args)
            "addblocks" -> handleAddBlocks(sender, args)
            "setblocks" -> handleSetBlocks(sender, args)
            else -> showHelp(sender)
        }
        return true
    }

    /**
     * `/claim addblocks <player> <amount>` — admin command to grant claim
     * blocks. Negative amounts work (subtract). Used by ops to reward
     * players or refund mistakenly-spent blocks.
     */
    private fun handleAddBlocks(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        val targetName = args.getOrNull(1)
        val amount = args.getOrNull(2)?.toIntOrNull()
        if (targetName == null || amount == null) {
            plugin.commsManager.send(sender, Component.text("Usage: /claim addblocks <player> <amount>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && target.player == null) {
            plugin.commsManager.send(sender, Component.text("Unknown player: $targetName", NamedTextColor.RED))
            return
        }
        plugin.claimManager.addBlocks(target.uniqueId, amount)
        val newTotal = plugin.claimManager.getTotalBlocks(target.uniqueId)
        plugin.commsManager.send(
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
    private fun handleSetBlocks(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.claim.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        val targetName = args.getOrNull(1)
        val amount = args.getOrNull(2)?.toIntOrNull()
        if (targetName == null || amount == null || amount < 0) {
            plugin.commsManager.send(sender, Component.text("Usage: /claim setblocks <player> <amount>", NamedTextColor.RED))
            return
        }
        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && target.player == null) {
            plugin.commsManager.send(sender, Component.text("Unknown player: $targetName", NamedTextColor.RED))
            return
        }
        plugin.claimManager.setBlocks(target.uniqueId, amount)
        plugin.commsManager.send(
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
        return when (args.size) {
            1 -> listOf("wand", "trust", "untrust", "trusted", "show", "team", "personal", "map", "info", "list", "blocks", "help").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "blocks" -> listOf("give").filter { it.startsWith(args[1].lowercase()) }
                "trust", "untrust" -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[1], true) }
                else -> emptyList()
            }
            3 -> if (args[0].equals("blocks", true) && args[1].equals("give", true))
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], true) } else emptyList()
            else -> emptyList()
        }
    }
}
