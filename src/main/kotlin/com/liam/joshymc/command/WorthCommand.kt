package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * No args: opens the read-only Worth GUI — a browsable, sortable price guide for the entire
 * /sell catalog. No categories: every item configured in sell-prices.yml's central `prices:`
 * list shows up, sorted by price or alphabetically. Players no longer need to hold an
 * item; ServerShopManager reads straight from SellPriceManager, so /worth always matches
 * /sell exactly.
 *
 * With an item argument (`/worth <item>`): prints that item's price in chat. Sell price comes
 * from SellPriceManager — the same authoritative source /sell reads — so any item sellable
 * through /sell shows up here, not just items also listed in /shop. Buy price (if any) still
 * comes from ServerShopManager, since /shop is the only purchase system.
 */
class WorthCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.serverShopManager.openWorthMenu(sender)
            return true
        }

        val input = args.joinToString(" ")
        val materialName = args.joinToString("_").uppercase()
        val material = Material.matchMaterial(materialName)

        if (material == null) {
            plugin.commsManager.send(
                sender,
                Component.text("Unknown item: $input", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        val sellPrice = plugin.sellPriceManager.getPrice(material)
        val buyPrice = plugin.serverShopManager.getBuyPrice(material)

        if (sellPrice == null && buyPrice == null) {
            plugin.commsManager.send(
                sender,
                Component.text("This item cannot be bought or sold.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        plugin.commsManager.send(
            sender,
            Component.text(plugin.serverShopManager.formatMaterialName(material), NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.ECONOMY
        )

        val buyLine = if (buyPrice != null) "&7Buy Price: &a${plugin.economyManager.format(buyPrice)}" else "&7Buy Price: &cNot Purchasable"
        val sellLine = if (sellPrice != null) "&7Sell Price: &a${plugin.economyManager.format(sellPrice)}" else "&7Sell Price: &cNot Sellable"

        sender.sendMessage(plugin.commsManager.parseLegacy(buyLine))
        sender.sendMessage(plugin.commsManager.parseLegacy(sellLine))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size != 1) return emptyList()

        val prefix = args[0].lowercase()

        val sellableNames = plugin.sellPriceManager.getAllPrices().keys
            .map { it.name.lowercase() }
            .filter { it.startsWith(prefix) }
        if (sellableNames.size >= 30) return sellableNames.take(30)

        val shopNames = plugin.serverShopManager.getAllShopMaterials()
            .map { it.name.lowercase() }
            .filter { it.startsWith(prefix) && it !in sellableNames }
        val combined = sellableNames + shopNames
        if (combined.size >= 30) return combined.take(30)

        val extra = Material.entries.asSequence()
            .filter { it.isItem && !it.isAir }
            .map { it.name.lowercase() }
            .filter { it.startsWith(prefix) && it !in combined }
            .take(30 - combined.size)
            .toList()

        return combined + extra
    }
}
