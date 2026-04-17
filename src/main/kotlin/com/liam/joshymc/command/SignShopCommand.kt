package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class SignShopCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private val subcommands = listOf("help", "example", "info", "remove")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.shop")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return true
        }

        val sub = args.getOrNull(0)?.lowercase() ?: "help"

        when (sub) {
            "help" -> showHelp(sender)
            "example" -> showExample(sender)
            "info" -> showInfo(sender)
            "remove" -> removeShop(sender)
            else -> showHelp(sender)
        }

        return true
    }

    private fun showHelp(player: Player) {
        val gold = TextColor.color(0xFFD700)
        val msg = Component.text()
            .append(Component.text("--- Shop Help ---", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("Creating a shop:", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("1. Place a sign on a chest", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("2. Line 1: ", NamedTextColor.GRAY))
            .append(Component.text("[Shop]", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("3. Line 2: ", NamedTextColor.GRAY))
            .append(Component.text("Item name", NamedTextColor.WHITE))
            .append(Component.text(" or ", NamedTextColor.GRAY))
            .append(Component.text("[hand]", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("4. Line 3: ", NamedTextColor.GRAY))
            .append(Component.text("B <price>", NamedTextColor.GREEN))
            .append(Component.text(" / ", NamedTextColor.GRAY))
            .append(Component.text("S <price>", NamedTextColor.YELLOW))
            .append(Component.text(" / ", NamedTextColor.GRAY))
            .append(Component.text("B <price> S <price>", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("5. Line 4 is auto-filled with your name", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("What B and S mean:", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  B", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" = You are ", NamedTextColor.GRAY))
            .append(Component.text("selling", NamedTextColor.GREEN))
            .append(Component.text(" to players (they buy from you)", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("  S", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
            .append(Component.text(" = You are ", NamedTextColor.GRAY))
            .append(Component.text("buying", NamedTextColor.YELLOW))
            .append(Component.text(" from players (they sell to you)", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("Commands:", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("/shop help", NamedTextColor.YELLOW))
            .append(Component.text(" — Show this help", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/shop example", NamedTextColor.YELLOW))
            .append(Component.text(" — Show an example shop sign", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/shop info", NamedTextColor.YELLOW))
            .append(Component.text(" — Look at a shop sign for details", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("/shop remove", NamedTextColor.YELLOW))
            .append(Component.text(" — Look at a shop sign to remove it", NamedTextColor.GRAY))

        plugin.commsManager.send(player, msg.build(), CommunicationsManager.Category.ECONOMY)
    }

    private fun showExample(player: Player) {
        val gold = TextColor.color(0xFFD700)
        val msg = Component.text()
            .append(Component.text("--- Shop Sign Example ---", gold).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("To sell diamonds for $100 each:", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  Line 1: ", NamedTextColor.GRAY))
            .append(Component.text("[Shop]", TextColor.color(0x0000AA)).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  Line 2: ", NamedTextColor.GRAY))
            .append(Component.text("diamond", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  Line 3: ", NamedTextColor.GRAY))
            .append(Component.text("B 100", NamedTextColor.GREEN))
            .append(Component.newline())
            .append(Component.text("  Line 4: ", NamedTextColor.GRAY))
            .append(Component.text("(your name auto-fills)", NamedTextColor.DARK_GRAY))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("To buy diamonds from players for $50:", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  Line 1: ", NamedTextColor.GRAY))
            .append(Component.text("[Shop]", TextColor.color(0x0000AA)).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  Line 2: ", NamedTextColor.GRAY))
            .append(Component.text("diamond", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("  Line 3: ", NamedTextColor.GRAY))
            .append(Component.text("S 50", NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("To do both (sell for $100, buy for $50):", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("  Line 3: ", NamedTextColor.GRAY))
            .append(Component.text("B 100 S 50", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.newline())
            .append(Component.text("Tip: ", NamedTextColor.YELLOW))
            .append(Component.text("Use ", NamedTextColor.GRAY))
            .append(Component.text("[hand]", NamedTextColor.WHITE))
            .append(Component.text(" on line 2 to use the item you're holding.", NamedTextColor.GRAY))

        plugin.commsManager.send(player, msg.build(), CommunicationsManager.Category.ECONOMY)
    }

    private fun showInfo(player: Player) {
        val target = player.getTargetBlockExact(5)
        if (target == null) {
            plugin.commsManager.send(player, Component.text("Look at a shop sign.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val shop = plugin.signShopManager.getShopAtLocation(target.location)
        if (shop == null) {
            plugin.commsManager.send(player, Component.text("That's not a shop sign.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val itemName = formatMaterialName(Material.valueOf(shop.item))
        val stock = plugin.signShopManager.countChestStock(shop)

        val msg = Component.text()
            .append(Component.text("--- Shop Info ---", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("Owner: ", NamedTextColor.GRAY))
            .append(Component.text(shop.ownerName, NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("Item: ", NamedTextColor.GRAY))
            .append(Component.text(itemName, NamedTextColor.WHITE))
            .append(Component.newline())

        if (shop.buyPrice != null) {
            msg.append(Component.text("Buy Price: ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(shop.buyPrice), NamedTextColor.GREEN))
                .append(Component.newline())
        }
        if (shop.sellPrice != null) {
            msg.append(Component.text("Sell Price: ", NamedTextColor.GRAY))
                .append(Component.text(plugin.economyManager.format(shop.sellPrice), NamedTextColor.YELLOW))
                .append(Component.newline())
        }

        msg.append(Component.text("Stock: ", NamedTextColor.GRAY))
            .append(Component.text("$stock", NamedTextColor.WHITE))
        msg.append(Component.newline())
        msg.append(Component.text("Location: ", NamedTextColor.GRAY))
            .append(Component.text("${shop.x}, ${shop.y}, ${shop.z}", NamedTextColor.DARK_GRAY))

        plugin.commsManager.send(player, msg.build(), CommunicationsManager.Category.ECONOMY)
    }

    private fun removeShop(player: Player) {
        val target = player.getTargetBlockExact(5)
        if (target == null) {
            plugin.commsManager.send(player, Component.text("Look at a shop sign.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val shop = plugin.signShopManager.getShopAtLocation(target.location)
        if (shop == null) {
            plugin.commsManager.send(player, Component.text("That's not a shop sign.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        if (player.uniqueId != shop.ownerUuid && !player.hasPermission("joshymc.shop.admin")) {
            plugin.commsManager.send(player, Component.text("You can only remove your own shops.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        plugin.signShopManager.removeShop(shop.world, shop.x, shop.y, shop.z)

        // Clear the sign text
        val block = target
        val state = block.state
        if (state is org.bukkit.block.Sign) {
            val side = state.getSide(org.bukkit.block.sign.Side.FRONT)
            side.line(0, Component.empty())
            side.line(1, Component.empty())
            side.line(2, Component.empty())
            side.line(3, Component.empty())
            state.update()
        }

        plugin.commsManager.send(player, Component.text("Shop removed.", NamedTextColor.GREEN), CommunicationsManager.Category.ECONOMY)
    }

    private fun formatMaterialName(material: Material): String {
        return material.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return subcommands.filter { it.startsWith(args[0].lowercase()) }
        }
        return emptyList()
    }
}
