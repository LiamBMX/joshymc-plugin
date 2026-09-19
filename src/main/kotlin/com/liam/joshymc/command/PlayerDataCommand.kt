package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/playerdata reset <player> <reason>` — staff-only V1 data wipe for a banned/tempbanned
 * player. Scope is deliberately narrow: inventory/armor/offhand, Ender Chest, Player Vaults,
 * claims (owned + trust/membership), and homes. Does NOT touch money, punishment history, or
 * ban state, and never unbans the target. See issue #885 for the full spec.
 */
class PlayerDataCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty() || !args[0].equals("reset", ignoreCase = true)) {
            plugin.commsManager.send(
                sender,
                Component.text("Usage: /playerdata reset <player> <reason>", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
            return true
        }

        if (!sender.hasPermission("joshymc.playerdata.reset")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }

        if (args.size < 3) {
            plugin.commsManager.send(
                sender,
                Component.text("Usage: /playerdata reset <player> <reason>", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
            return true
        }

        val target = resolveOfflinePlayer(args[1])
        if (target == null) {
            plugin.commsManager.send(sender, Component.text("Player not found: ${args[1]}", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return true
        }
        val (targetUuid, targetName) = target
        val reason = args.drop(2).joinToString(" ")

        // Hard safety check — target must currently be banned or tempbanned. This command
        // never unbans anyone; it only refuses to run against a player who isn't banned.
        if (plugin.punishmentManager.isBanned(targetUuid) == null) {
            plugin.commsManager.send(
                sender,
                Component.text("$targetName is not currently banned or tempbanned. Playerdata reset only applies to banned players.", NamedTextColor.RED),
                CommunicationsManager.Category.ADMIN
            )
            return true
        }

        val cleared = mutableListOf<String>()
        val failed = mutableListOf<String>()

        // 1-4: Inventory, hotbar, armor, offhand — only reachable while the target is online,
        // since Bukkit has no API to edit an offline player's inventory NBT. Most of the time
        // the target will be offline (they're banned), so this is a known V1 gap.
        val online = Bukkit.getPlayer(targetUuid)
        if (online != null) {
            online.closeInventory()
            online.inventory.clear()
            online.inventory.setArmorContents(arrayOfNulls(4))
            online.inventory.setItemInOffHand(null)
            online.updateInventory()
            cleared.add("Inventory")
        } else {
            failed.add("Inventory")
        }

        // 5. Ender Chest — JoshyMC's extra 27 slots always clear (DB-backed). Vanilla slots
        // 0-26 only clear if the target is online, same constraint as inventory above.
        try {
            plugin.enderChestManager.clear(targetUuid)
            plugin.adminManager.clearCachedEnderchest(targetUuid)
            if (online != null) cleared.add("Ender Chest") else failed.add("Ender Chest")
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerData] Failed to clear Ender Chest for $targetName: ${e.message}")
            failed.add("Ender Chest")
        }
        plugin.adminManager.clearCachedInventory(targetUuid)

        // 6. Player Vaults
        try {
            plugin.storageManager.clearAllVaults(targetUuid)
            cleared.add("Vaults")
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerData] Failed to clear Vaults for $targetName: ${e.message}")
            failed.add("Vaults")
        }

        // 7. Claims owned by the target
        try {
            val owned = plugin.claimManager.getClaimsByPlayer(targetUuid)
            val allDeleted = owned.fold(true) { ok, claim -> plugin.claimManager.deleteClaim(sender, claim) && ok }
            if (allDeleted) cleared.add("Claims") else failed.add("Claims")
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerData] Failed to clear Claims for $targetName: ${e.message}")
            failed.add("Claims")
        }

        // 8. Claim trust/membership entries where the target is only a member
        try {
            val trustedIn = plugin.claimManager.getClaimsTrustedBy(targetUuid)
            val allUntrusted = trustedIn.fold(true) { ok, claim -> plugin.claimManager.untrustPlayer(claim, targetUuid) && ok }
            if (allUntrusted) cleared.add("Claim Trust") else failed.add("Claim Trust")
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerData] Failed to clear Claim Trust for $targetName: ${e.message}")
            failed.add("Claim Trust")
        }

        // 9. Homes
        try {
            plugin.warpManager.deleteAllHomes(targetUuid.toString())
            cleared.add("Homes")
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerData] Failed to clear Homes for $targetName: ${e.message}")
            failed.add("Homes")
        }

        plugin.adminManager.logAction(
            sender,
            "PLAYER DATA RESET V1",
            Bukkit.getOfflinePlayer(targetUuid),
            "reason=$reason; cleared=${cleared.joinToString(",")}; failed=${failed.joinToString(",")}"
        )

        if (failed.isEmpty()) {
            plugin.commsManager.send(
                sender,
                Component.text("Reset player data for $targetName. Cleared: ${cleared.joinToString(", ")}. Reason: $reason", NamedTextColor.GREEN),
                CommunicationsManager.Category.ADMIN
            )
        } else {
            plugin.commsManager.send(
                sender,
                Component.text("Partial reset for $targetName. Failed: ${failed.joinToString(", ")}. Check console/audit log.", NamedTextColor.YELLOW),
                CommunicationsManager.Category.ADMIN
            )
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        return when (args.size) {
            1 -> listOf("reset").filter { it.startsWith(args[0], ignoreCase = true) }
            2 -> onlinePlayerNames(args[1])
            3 -> listOf("Ban evasion cleanup")
            else -> emptyList()
        }
    }
}
