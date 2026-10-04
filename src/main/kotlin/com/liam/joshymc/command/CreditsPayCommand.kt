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
import java.math.BigDecimal
import java.util.UUID

/**
 * /creditspay <amount> <player> — standalone Credits transfer. Independent of
 * /credits pay; uses CreditsManager.transfer for an atomic debit + credit.
 */
class CreditsPayCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private fun err(player: Player, text: String) =
        plugin.commsManager.send(player, Component.text(text, NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }
        if (!sender.hasPermission("joshymc.credits.pay")) {
            err(sender, "You don't have permission to pay Credits.")
            return true
        }
        if (args.size != 2) {
            err(sender, "Usage: /creditspay <amount> <player>")
            return true
        }

        val amount = parseAmount(args[0])
        if (amount == null) {
            err(sender, "Please enter a valid Credit amount!")
            return true
        }

        val online = Bukkit.getPlayerExact(args[1])
        val target: Pair<String, UUID>? = if (online != null) {
            online.name to online.uniqueId
        } else {
            Bukkit.getOfflinePlayerIfCached(args[1])?.let { (it.name ?: args[1]) to it.uniqueId }
        }
        if (target == null) {
            err(sender, "That player could not be found!")
            return true
        }
        val (targetName, targetUuid) = target

        if (targetUuid == sender.uniqueId) {
            err(sender, "You cannot pay yourself.")
            return true
        }

        if (online != null && !plugin.settingsManager.getSetting(online, "credit_payments")) {
            err(sender, "$targetName has Credit payments disabled.")
            return true
        }

        if (!plugin.creditsManager.transfer(sender.uniqueId, targetUuid, amount)) {
            err(sender, "You do not have enough Credits!")
            return true
        }

        val formatted = plugin.creditsManager.format(amount)
        plugin.commsManager.send(
            sender,
            Component.text("Successfully sent $formatted Credits to $targetName!", NamedTextColor.GREEN),
            CommunicationsManager.Category.ECONOMY
        )
        if (online != null) {
            plugin.commsManager.send(
                online,
                Component.text("You received $formatted Credits from ${sender.name}!", NamedTextColor.GREEN),
                CommunicationsManager.Category.ECONOMY
            )
        }
        return true
    }

    /** Positive, at most 2 decimals, bounded; rejects NaN/Infinity/negatives. */
    private fun parseAmount(raw: String): Double? {
        val bd = try { BigDecimal(raw) } catch (e: NumberFormatException) { return null }
        if (bd.signum() <= 0 || bd.scale() > 2) return null
        val d = bd.toDouble()
        return if (d.isFinite() && d <= 1_000_000_000_000.0) d else null
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("joshymc.credits.pay")) return emptyList()
        return when (args.size) {
            1 -> listOf("1", "5", "10", "100").filter { it.startsWith(args[0]) }
            2 -> Bukkit.getOnlinePlayers().map { it.name }
                .filter { it != sender.name && it.lowercase().startsWith(args[1].lowercase()) }
            else -> emptyList()
        }
    }
}
