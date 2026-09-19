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
 * With an item argument (`/worth <item>`): prints that item's /shop buy/sell price directly
 * in chat, read from the same ServerShopManager `shop.yml` categories `/shop` itself
 * browses — there is no separate price list to fall out of sync.
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

        val pricing = plugin.serverShopManager.getShopPricing(material)
        if (pricing == null) {
            plugin.commsManager.send(
                sender,
                Component.text("This item does not currently have a shop value.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return true
        }

        val (buyPrice, sellPrice) = pricing

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
        val shopNames = plugin.serverShopManager.getAllShopMaterials()
            .map { it.name.lowercase() }
            .filter { it.startsWith(prefix) }
        if (shopNames.size >= 30) return shopNames.take(30)

        val extra = Material.entries.asSequence()
            .filter { it.isItem && !it.isAir }
            .map { it.name.lowercase() }
            .filter { it.startsWith(prefix) && it !in shopNames }
            .take(30 - shopNames.size)
            .toList()

        return shopNames + extra
    }
}
