package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.stock.StockHomeGui
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.StockMarketManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/invest` — opens the player-driven stock market home GUI (Trade / Create Your Own /
 * My Investments). All buying/selling/creation happens through the stock market GUIs
 * (package gui.stock) and StockTradeChatListener. The only subcommand is `admin`, for
 * staff-only stock reset/delete with automatic investor refunds (see StockMarketManager).
 */
class InvestCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("admin", ignoreCase = true)) {
            return handleAdmin(sender, args)
        }

        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.invest")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        plugin.guiManager.open(sender, StockHomeGui.build(plugin, sender))
        return true
    }

    // ── Admin: reset/delete a stock with confirmation + automatic investor refunds ──

    private fun adminKey(sender: CommandSender): String = if (sender is Player) sender.uniqueId.toString() else "CONSOLE"

    private fun hasAdminPerm(sender: CommandSender, specific: String): Boolean =
        sender.hasPermission("joshymc.invest.admin.*") || sender.hasPermission(specific)

    private fun hasAnyAdminPerm(sender: CommandSender): Boolean =
        hasAdminPerm(sender, "joshymc.invest.admin.reset") || hasAdminPerm(sender, "joshymc.invest.admin.delete")

    private fun reply(sender: CommandSender, message: Component) {
        if (sender is Player) {
            plugin.commsManager.send(sender, message, CommunicationsManager.Category.ADMIN)
        } else {
            sender.sendMessage(message)
        }
    }

    private fun handleAdmin(sender: CommandSender, args: Array<out String>): Boolean {
        if (args.size < 2) {
            reply(sender, Component.text("Usage: /invest admin <reset|delete|confirm|cancel> ...", NamedTextColor.RED))
            return true
        }

        when (args[1].lowercase()) {
            "reset" -> handleResetOrDelete(sender, args, StockMarketManager.AdminActionType.RESET)
            "delete" -> handleResetOrDelete(sender, args, StockMarketManager.AdminActionType.DELETE)

            "confirm" -> {
                if (!hasAnyAdminPerm(sender)) {
                    reply(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                when (val result = plugin.stockMarketManager.confirmAdminAction(adminKey(sender), sender.name)) {
                    is StockMarketManager.AdminActionResult.Failure ->
                        reply(sender, Component.text(result.message, NamedTextColor.RED))
                    is StockMarketManager.AdminActionResult.Success -> reply(
                        sender,
                        Component.text(
                            "${result.ticker} was ${result.actionType.pastTense}. Refunded ${result.investorsRefunded} investor(s) a total of ${plugin.economyManager.format(result.totalRefunded)}.",
                            NamedTextColor.GREEN
                        )
                    )
                }
            }

            "cancel" -> {
                if (!hasAnyAdminPerm(sender)) {
                    reply(sender, Component.text("No permission.", NamedTextColor.RED))
                    return true
                }
                val pending = plugin.stockMarketManager.cancelAdminAction(adminKey(sender))
                if (pending == null) {
                    reply(sender, Component.text("You have no pending stock action to cancel.", NamedTextColor.RED))
                } else {
                    reply(sender, Component.text("Cancelled pending ${pending.actionType.verb} of ${pending.ticker}.", NamedTextColor.YELLOW))
                }
            }

            else -> reply(sender, Component.text("Usage: /invest admin <reset|delete|confirm|cancel> ...", NamedTextColor.RED))
        }

        return true
    }

    private fun handleResetOrDelete(sender: CommandSender, args: Array<out String>, actionType: StockMarketManager.AdminActionType) {
        val permission = "joshymc.invest.admin.${actionType.verb}"
        if (!hasAdminPerm(sender, permission)) {
            reply(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }

        val stockInput = args.getOrNull(2)
        if (stockInput == null) {
            reply(sender, Component.text("Usage: /invest admin ${actionType.verb} <stock>", NamedTextColor.RED))
            return
        }

        when (val preview = plugin.stockMarketManager.prepareAdminAction(adminKey(sender), actionType, stockInput)) {
            is StockMarketManager.AdminActionPreview.Failure ->
                reply(sender, Component.text(preview.message, NamedTextColor.RED))
            is StockMarketManager.AdminActionPreview.Ready -> {
                reply(
                    sender,
                    Component.text(
                        "WARNING: ${if (actionType == StockMarketManager.AdminActionType.RESET) "Resetting" else "Deleting"} " +
                            "${preview.stock.name} (${preview.stock.ticker}) will affect ${preview.investorCount} investor(s) " +
                            "and refund ${plugin.economyManager.format(preview.refundTotal)} total.",
                        NamedTextColor.RED
                    )
                )
                reply(sender, Component.text("Run /invest admin confirm within 30 seconds to continue.", NamedTextColor.YELLOW))
            }
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("admin").filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("admin", ignoreCase = true)) {
            return listOf("reset", "delete", "confirm", "cancel").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 3 && args[0].equals("admin", ignoreCase = true) &&
            (args[1].equals("reset", ignoreCase = true) || args[1].equals("delete", ignoreCase = true))
        ) {
            return plugin.stockMarketManager.getAllStocks().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
        }
        return emptyList()
    }
}
