package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Opens the read-only Worth GUI — a browsable, categorized price guide for the /sell
 * catalog. Players no longer need to hold an item; ServerShopManager owns both the
 * sell-price data and the GUI itself, so /worth always matches /sell exactly.
 */
class WorthCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        plugin.serverShopManager.openWorthMenu(sender)
        return true
    }
}
