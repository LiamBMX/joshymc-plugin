package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.coinflip.CreditsCoinflipGui
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.CreditsCoinflipManager
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/** `/ccoinflip` (alias `/ccf`) — Credits Coinflip GUI, or `create <amount>` directly (issue #1031). */
class CreditsCoinflipCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private fun reply(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.DEFAULT)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            Msg.playersOnly(sender)
            return true
        }
        if (!sender.hasPermission(CreditsCoinflipManager.PERMISSION)) {
            Msg.noPermission(sender)
            return true
        }

        if (args.isNotEmpty() && args[0].equals("create", ignoreCase = true)) {
            if (args.size < 2) {
                Msg.usage(sender, "/ccoinflip create <amount>")
                return true
            }
            val wager = CreditsCoinflipManager.parseWager(args[1])
            if (wager == null || wager <= 0L) {
                reply(sender, "Invalid amount. Use a whole number of Credits, e.g. 500.", NamedTextColor.RED)
                return true
            }
            plugin.creditsCoinflipManager.createFlip(sender, wager)
            return true
        }

        CreditsCoinflipGui.openMain(plugin, sender)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) return listOf("create").filter { it.startsWith(args[0].lowercase()) }
        return emptyList()
    }
}
