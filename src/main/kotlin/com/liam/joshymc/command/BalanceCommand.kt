package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import java.util.UUID

class BalanceCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val adminBalance = AdminBalanceCommand(plugin)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // /money remove|set <player> <amount>: operator-only, works offline (issue #1162).
        // A lone "/bal set" still looks up a player named "set".
        val sub = args.getOrNull(0)?.lowercase()
        if (args.size >= 2 && (sub == "remove" || sub == "set")) {
            val operation = if (sub == "remove") AdminBalanceCommand.Operation.REMOVE else AdminBalanceCommand.Operation.SET
            adminBalance.handle(sender, AdminBalanceCommand.Currency.COINS, operation, "/$label $sub <player> <amount>", args.drop(1))
            return true
        }

        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }

        if (!sender.hasPermission("joshymc.balance")) {
            Msg.noPermission(sender, CommunicationsManager.Category.ECONOMY)
            return true
        }

        if (args.isNotEmpty()) {
            val target = resolveTarget(args[0])
            if (target == null) {
                Msg.playerNotFound(sender, category = CommunicationsManager.Category.ECONOMY)
                return true
            }
            val (targetName, targetUuid) = target
            val balance = plugin.economyManager.getBalance(targetUuid)
            plugin.commsManager.send(
                sender,
                Component.text(targetName, NamedTextColor.WHITE)
                    .append(Component.text("'s balance: ", NamedTextColor.GRAY))
                    .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD)),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        val balance = plugin.economyManager.getBalance(sender)
        plugin.commsManager.send(
            sender,
            Component.text("Your balance: ", NamedTextColor.GRAY)
                .append(Component.text(plugin.economyManager.format(balance), NamedTextColor.GOLD)),
            CommunicationsManager.Category.ECONOMY
        )
        return true
    }

    private fun resolveTarget(name: String): Pair<String, UUID>? {
        val online = Bukkit.getPlayerExact(name)
        if (online != null) return online.name to online.uniqueId
        val cached = Bukkit.getOfflinePlayerIfCached(name) ?: return null
        return (cached.name ?: name) to cached.uniqueId
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val canAdmin = adminBalance.canUse(sender)
        if (args.size == 1) {
            val subs = if (canAdmin) listOf("remove", "set") else emptyList()
            return (subs + Bukkit.getOnlinePlayers().map { it.name }).filter { it.lowercase().startsWith(args[0].lowercase()) }
        }
        val sub = args[0].lowercase()
        if (!canAdmin || (sub != "remove" && sub != "set")) return emptyList()
        return when (args.size) {
            2 -> Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
            3 -> listOf("100", "1000", "10000", "100000").filter { it.startsWith(args[2]) }
            else -> emptyList()
        }
    }
}
