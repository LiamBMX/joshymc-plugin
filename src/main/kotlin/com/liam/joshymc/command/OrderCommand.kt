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
 * making the player search through it manually. `/order create <item>` jumps straight into
 * the Buy Order creation flow with that item already selected — a separate shortcut so it
 * doesn't collide with the existing `/order <item>` search filter.
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

        if (args[0].equals("create", ignoreCase = true)) {
            val itemArgs = args.drop(1)
            if (itemArgs.isEmpty()) {
                plugin.commsManager.send(sender, Component.text("Usage: /order create <item>", NamedTextColor.RED))
                return true
            }
            val material = Material.matchMaterial(itemArgs.joinToString("_").uppercase())
            if (material == null || material !in plugin.orderManager.orderableMaterialCatalog()) {
                plugin.commsManager.send(sender, Component.text("Unknown item, or Buy Orders can't be created for it.", NamedTextColor.RED))
                return true
            }
            plugin.orderManager.beginCreateOrderForItem(sender, material)
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
        if (args.isEmpty()) return emptyList()

        if (args.size == 1) {
            val prefix = args[0].lowercase()
            val options = listOf("create") + plugin.orderManager.orderableMaterialCatalog().map { it.name.lowercase() }
            return options.filter { it.startsWith(prefix) }.take(30)
        }

        if (args[0].equals("create", ignoreCase = true) && args.size == 2) {
            val prefix = args[1].lowercase()
            return plugin.orderManager.orderableMaterialCatalog()
                .map { it.name.lowercase() }
                .filter { it.startsWith(prefix) }
                .take(30)
        }

        return emptyList()
    }
}
