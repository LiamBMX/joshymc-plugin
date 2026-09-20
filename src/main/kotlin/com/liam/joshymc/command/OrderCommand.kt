package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/order` — quick shortcut into the Buy Orders marketplace. With no args it's identical to
 * `/orders`; with an item name it opens the same GUI pre-filtered to that item instead of
 * making the player search through it manually.
 */
class OrderCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.orders")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.orderManager.openMainGui(sender)
            return true
        }

        val material = Material.matchMaterial(args.joinToString("_").uppercase())
        if (material == null || plugin.orderManager.getTotalActiveOrders(material) == 0) {
            plugin.commsManager.send(sender, Component.text("No matching buy orders were found for that item.", NamedTextColor.RED))
            return true
        }

        plugin.orderManager.openMainGui(sender, materialFilter = material)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val prefix = args[0].lowercase()
        return plugin.orderManager.orderableMaterialCatalog()
            .map { it.name.lowercase() }
            .filter { it.startsWith(prefix) }
            .take(30)
    }
}
