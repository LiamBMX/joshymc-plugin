package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * /mcr — Ranger/Pioneer rank perk: 500 Credits every 30 days.
 *
 * Access is gated by a single permission node (joshymc.mcr) rather than a
 * hard-coded rank check. On the live server that permission is granted to
 * the Ranger LuckPerms group; Pioneer inherits Ranger, so Pioneer players
 * get it automatically with no duplicate grant or duplicate handling needed.
 *
 * Bukkit dispatches commands synchronously on the main thread, so the
 * cooldown check + credit deposit + cooldown persist below can't interleave
 * with another invocation from the same (or any) player — that's what makes
 * this spam/concurrency-safe without an extra lock.
 */
class McrCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.mcr")) {
            plugin.commsManager.send(
                sender,
                Component.text("You don't have permission to use /mcr.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        val remaining = getCooldownRemaining(sender)
        if (remaining > 0) {
            plugin.commsManager.send(
                sender,
                Component.text("You can use /mcr again in ${formatCooldown(remaining)}.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        try {
            plugin.creditsManager.deposit(sender.uniqueId, REWARD_CREDITS)
        } catch (e: Exception) {
            plugin.logger.warning("[MCR] Failed to grant Credits to ${sender.name}: ${e.message}")
            plugin.commsManager.send(
                sender,
                Component.text("Something went wrong claiming your reward. Please try again.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        plugin.databaseManager.execute(
            "INSERT INTO mcr_claims (uuid, last_claim_at) VALUES (?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET last_claim_at = excluded.last_claim_at",
            sender.uniqueId.toString(), System.currentTimeMillis()
        )

        plugin.commsManager.send(
            sender,
            Component.text("You claimed ", NamedTextColor.GREEN)
                .append(Component.text("${plugin.creditsManager.format(REWARD_CREDITS)} Credits", NamedTextColor.AQUA))
                .append(Component.text("! You can claim again in 30 days.", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
        return true
    }

    private fun getCooldownRemaining(player: Player): Long {
        if (player.hasPermission("joshymc.mcr.bypass")) return 0L

        val lastClaimAt = plugin.databaseManager.queryFirst(
            "SELECT last_claim_at FROM mcr_claims WHERE uuid = ?",
            player.uniqueId.toString()
        ) { rs -> rs.getLong("last_claim_at") } ?: return 0L

        val elapsed = System.currentTimeMillis() - lastClaimAt
        val remaining = COOLDOWN_MS - elapsed
        return if (remaining > 0) remaining else 0L
    }

    private fun formatCooldown(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (60 * 24)
        val hours = (totalMinutes / 60) % 24
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    companion object {
        private const val REWARD_CREDITS = 500.0
        private const val COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000

        fun createTable(plugin: Joshymc) {
            plugin.databaseManager.createTable(
                "CREATE TABLE IF NOT EXISTS mcr_claims (uuid TEXT PRIMARY KEY, last_claim_at INTEGER NOT NULL)"
            )
        }
    }
}
