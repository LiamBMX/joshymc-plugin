package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class WorthCommand(private val plugin: Joshymc) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        val item = sender.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            plugin.commsManager.send(sender,
                Component.text("Hold an item to check its worth.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        val material = item.type
        val name = material.name.lowercase().replace('_', ' ')
        val buyPrice = plugin.marketManager.getCurrentBuyPrice(material)
        val sellPrice = plugin.marketManager.getCurrentSellPrice(material)

        if (buyPrice == null && sellPrice == null) {
            plugin.commsManager.send(sender,
                Component.text("$name has no shop value.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        plugin.commsManager.send(sender,
            Component.text("Worth — ", NamedTextColor.GOLD)
                .append(Component.text(name, NamedTextColor.WHITE)),
            CommunicationsManager.Category.ECONOMY
        )

        if (buyPrice != null) {
            plugin.commsManager.send(sender,
                Component.text(" Buy:  ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.economyManager.format(buyPrice), NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        }

        if (sellPrice != null) {
            plugin.commsManager.send(sender,
                Component.text(" Sell: ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.economyManager.format(sellPrice), NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
        }

        val multiplier = plugin.marketManager.getMultiplier(material)
        if (multiplier != 1.0) {
            val pct = ((multiplier - 1.0) * 100).toInt()
            val sign = if (pct > 0) "+" else ""
            val color = if (pct > 0) NamedTextColor.GREEN else NamedTextColor.RED
            plugin.commsManager.send(sender,
                Component.text(" Market: ", NamedTextColor.GRAY)
                    .append(Component.text("$sign$pct%", color)),
                CommunicationsManager.Category.ECONOMY
            )
        }

        return true
    }
}
