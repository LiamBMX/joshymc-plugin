package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * /cshop — a shop that only accepts Credits.
 *
 * Players: /cshop                                        opens the browse/buy GUI
 * Admins:  /cshop additem hand <price> <category>         list the held item
 *          /cshop removeitem <id>                         delist an item
 *          /cshop category create <name>                  create a new category
 *          /cshop list [category]                         show listing ids
 */
class CreditShopCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            plugin.creditShopManager.openMainMenu(sender)
            return true
        }

        when (args[0].lowercase()) {
            "additem" -> handleAddItem(sender, args)
            "removeitem" -> handleRemoveItem(sender, args)
            "category" -> handleCategory(sender, args)
            "list" -> handleList(sender, args)
            else -> sendUsage(sender)
        }

        return true
    }

    private fun handleAddItem(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.cshop.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }
        if (args.size != 4 || args[1].lowercase() != "hand") {
            plugin.commsManager.send(sender, Component.text("Usage: /cshop additem hand <price> <category>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val held = sender.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            plugin.commsManager.send(sender, Component.text("Hold the item you want to add.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val price = args[2].toDoubleOrNull()
        if (price == null || price <= 0) {
            plugin.commsManager.send(sender, Component.text("Invalid price.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val categoryId = plugin.creditShopManager.slugify(args[3])
        val category = plugin.creditShopManager.getCategory(categoryId)
        if (category == null) {
            plugin.commsManager.send(sender,
                Component.text("Category not found. Create it first with ", NamedTextColor.RED)
                    .append(Component.text("/cshop category create <name>", NamedTextColor.WHITE)),
                CommunicationsManager.Category.ADMIN
            )
            return
        }

        val id = plugin.creditShopManager.addItem(category.id, held.clone(), price)
        plugin.commsManager.send(sender,
            Component.text("Added ", NamedTextColor.GREEN)
                .append(held.displayName())
                .append(Component.text(" to ", NamedTextColor.GREEN))
                .append(Component.text(category.name, NamedTextColor.WHITE))
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text("${plugin.creditsManager.format(price)} credits", NamedTextColor.AQUA))
                .append(Component.text(" (id $id).", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ADMIN
        )
    }

    private fun handleRemoveItem(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.cshop.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }
        if (args.size != 2) {
            plugin.commsManager.send(sender, Component.text("Usage: /cshop removeitem <id>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val id = args[1].toIntOrNull()
        if (id == null) {
            plugin.commsManager.send(sender, Component.text("Invalid id.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        if (plugin.creditShopManager.removeItem(id)) {
            plugin.commsManager.send(sender, Component.text("Removed listing $id.", NamedTextColor.GREEN), CommunicationsManager.Category.ADMIN)
        } else {
            plugin.commsManager.send(sender, Component.text("No listing found with id $id.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
        }
    }

    private fun handleCategory(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.cshop.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }
        if (args.size < 3 || args[1].lowercase() != "create") {
            plugin.commsManager.send(sender, Component.text("Usage: /cshop category create <name>", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val name = args.drop(2).joinToString(" ")
        val icon = if (sender.inventory.itemInMainHand.type != Material.AIR) {
            sender.inventory.itemInMainHand.type
        } else {
            Material.CHEST
        }

        val category = plugin.creditShopManager.createCategory(name, icon)
        if (category == null) {
            plugin.commsManager.send(sender, Component.text("A category with that name already exists.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        plugin.commsManager.send(sender,
            Component.text("Created category ", NamedTextColor.GREEN)
                .append(Component.text(category.name, NamedTextColor.WHITE))
                .append(Component.text(" (id ${category.id}).", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ADMIN
        )
    }

    private fun handleList(sender: Player, args: Array<out String>) {
        if (!sender.hasPermission("joshymc.cshop.admin")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        val categories = if (args.size >= 2) {
            val id = plugin.creditShopManager.slugify(args[1])
            listOfNotNull(plugin.creditShopManager.getCategory(id))
        } else {
            plugin.creditShopManager.getCategories()
        }

        if (categories.isEmpty()) {
            plugin.commsManager.send(sender, Component.text("No categories found.", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
            return
        }

        for (category in categories) {
            val items = plugin.creditShopManager.getItemsForCategory(category.id)
            plugin.commsManager.send(sender,
                Component.text("${category.name} (${category.id}) - ${items.size} items", NamedTextColor.LIGHT_PURPLE),
                CommunicationsManager.Category.ADMIN
            )
            for (item in items) {
                plugin.commsManager.send(sender,
                    Component.text("  #${item.id} ", NamedTextColor.DARK_GRAY)
                        .append(item.item.displayName())
                        .append(Component.text(" - ${plugin.creditsManager.format(item.price)} credits", NamedTextColor.AQUA)),
                    CommunicationsManager.Category.ADMIN
                )
            }
        }
    }

    private fun sendUsage(sender: Player) {
        plugin.commsManager.send(sender, Component.text("Usage: /cshop [additem|removeitem|category|list] [args...]", NamedTextColor.RED), CommunicationsManager.Category.ADMIN)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val isAdmin = sender.hasPermission("joshymc.cshop.admin")

        return when (args.size) {
            1 -> if (isAdmin) {
                listOf("additem", "removeitem", "category", "list").filter { it.startsWith(args[0].lowercase()) }
            } else emptyList()
            2 -> when (args[0].lowercase()) {
                "additem" -> listOf("hand").filter { it.startsWith(args[1].lowercase()) }
                "category" -> listOf("create").filter { it.startsWith(args[1].lowercase()) }
                "list" -> plugin.creditShopManager.getCategories().map { it.id }.filter { it.startsWith(args[1].lowercase()) }
                else -> emptyList()
            }
            4 -> if (args[0].lowercase() == "additem") {
                plugin.creditShopManager.getCategories().map { it.id }.filter { it.startsWith(args[3].lowercase()) }
            } else emptyList()
            else -> emptyList()
        }
    }
}
