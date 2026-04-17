package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SellCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter, Listener {

    /** Tracks players with the sell GUI open — UUID to inventory */
    private val openSellGuis = ConcurrentHashMap<UUID, Inventory>()

    // Cache of all sellable material names for tab completion
    private var sellableMaterials: List<String> = emptyList()

    fun refreshSellableCache() {
        sellableMaterials = Material.entries
            .filter { (plugin.serverShopManager.getSellPrice(it) ?: 0.0) > 0 }
            .map { it.name.lowercase() }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (!sender.hasPermission("joshymc.sell")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        return when (args.getOrNull(0)?.lowercase()) {
            "hand" -> {
                val sellAllOfType = args.getOrNull(1)?.lowercase() == "all"
                sellHand(sender, sellAllOfType)
                true
            }
            "all" -> {
                if (args.size >= 2) {
                    // /sell all <material>
                    sellSpecific(sender, args.drop(1).joinToString("_").uppercase())
                } else {
                    // /sell all — no item specified, show usage
                    plugin.commsManager.send(sender,
                        Component.text("Usage: /sell all <item> — sell all of an item from your inventory.", NamedTextColor.YELLOW),
                        CommunicationsManager.Category.ECONOMY
                    )
                }
                true
            }
            null -> {
                // /sell with no args — open sell GUI
                openSellGui(sender)
                true
            }
            else -> {
                plugin.commsManager.send(sender,
                    Component.text("Usage:\n", NamedTextColor.YELLOW)
                        .append(Component.text("  /sell", NamedTextColor.GOLD)).append(Component.text(" — open sell GUI\n", NamedTextColor.GRAY))
                        .append(Component.text("  /sell all <item>", NamedTextColor.GOLD)).append(Component.text(" — sell all of an item\n", NamedTextColor.GRAY))
                        .append(Component.text("  /sell hand", NamedTextColor.GOLD)).append(Component.text(" — sell held item\n", NamedTextColor.GRAY))
                        .append(Component.text("  /sell hand all", NamedTextColor.GOLD)).append(Component.text(" — sell all of held item type", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.ECONOMY
                )
                true
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  SELL GUI — place items in to sell them
    // ══════════════════════════════════════════════════════════

    private fun openSellGui(player: Player) {
        val title = Component.text("Sell Items", NamedTextColor.GOLD)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val inv = Bukkit.createInventory(null, 54, title)
        openSellGuis[player.uniqueId] = inv
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSellGuiClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val sellInv = openSellGuis.remove(player.uniqueId) ?: return

        // Process the inventory: sell what's sellable, return what isn't
        processSellGui(player, sellInv)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val sellInv = openSellGuis.remove(event.player.uniqueId) ?: return
        // Return all items on disconnect (anti-dupe: don't sell, just give back)
        returnAllItems(event.player, sellInv)
    }

    private fun processSellGui(player: Player, inv: Inventory) {
        var totalEarned = 0.0
        val breakdown = mutableMapOf<Material, Int>()
        val unsellable = mutableListOf<ItemStack>()

        for (i in 0 until inv.size) {
            val item = inv.getItem(i) ?: continue
            if (item.type == Material.AIR) continue

            val price = plugin.serverShopManager.getSellPrice(item.type) ?: 0.0
            if (price > 0) {
                totalEarned += price * item.amount
                breakdown[item.type] = (breakdown[item.type] ?: 0) + item.amount
            } else {
                unsellable.add(item.clone())
            }
            inv.setItem(i, null) // Clear the sell GUI slot
        }

        // Return unsellable items to player
        for (item in unsellable) {
            val leftover = player.inventory.addItem(item)
            for ((_, drop) in leftover) {
                player.world.dropItemNaturally(player.location, drop)
            }
        }

        if (totalEarned > 0) {
            plugin.economyManager.deposit(player.uniqueId, totalEarned)
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)
            sendSellSummary(player, totalEarned, breakdown)
        } else if (unsellable.isNotEmpty()) {
            plugin.commsManager.send(player,
                Component.text("None of those items can be sold. Items returned.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
        }
    }

    private fun returnAllItems(player: Player, inv: Inventory) {
        for (i in 0 until inv.size) {
            val item = inv.getItem(i) ?: continue
            if (item.type == Material.AIR) continue
            val leftover = player.inventory.addItem(item)
            for ((_, drop) in leftover) {
                player.world.dropItemNaturally(player.location, drop)
            }
        }
    }

    // ══════════════════════════════════════════════════════════
    //  SELL ALL — sell everything sellable in inventory
    // ══════════════════════════════════════════════════════════

    private fun sellAll(player: Player) {
        var totalEarned = 0.0
        val breakdown = mutableMapOf<Material, Int>()

        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            val price = plugin.serverShopManager.getSellPrice(item.type) ?: 0.0
            if (price <= 0) continue

            totalEarned += price * item.amount
            breakdown[item.type] = (breakdown[item.type] ?: 0) + item.amount
            player.inventory.setItem(i, null)
        }

        if (totalEarned <= 0) {
            plugin.commsManager.send(player, Component.text("You have nothing to sell.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        plugin.economyManager.deposit(player.uniqueId, totalEarned)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)
        sendSellSummary(player, totalEarned, breakdown)
    }

    // ══════════════════════════════════════════════════════════
    //  SELL SPECIFIC — /sell all <material>
    // ══════════════════════════════════════════════════════════

    private fun sellSpecific(player: Player, materialName: String) {
        val material = Material.matchMaterial(materialName)
        if (material == null) {
            plugin.commsManager.send(player, Component.text("Unknown item: $materialName", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val price = plugin.serverShopManager.getSellPrice(material) ?: 0.0
        if (price <= 0) {
            plugin.commsManager.send(player, Component.text("That item cannot be sold.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        var count = 0
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (item.type == material) {
                count += item.amount
                player.inventory.setItem(i, null)
            }
        }

        if (count == 0) {
            plugin.commsManager.send(player, Component.text("You don't have any of that item.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val totalEarned = price * count
        plugin.economyManager.deposit(player.uniqueId, totalEarned)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)
        sendSellSummary(player, totalEarned, mapOf(material to count))
    }

    // ══════════════════════════════════════════════════════════
    //  SELL HAND
    // ══════════════════════════════════════════════════════════

    private fun sellHand(player: Player, allOfType: Boolean) {
        val handItem = player.inventory.itemInMainHand
        if (handItem.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("You are not holding anything.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val price = plugin.serverShopManager.getSellPrice(handItem.type) ?: 0.0
        if (price <= 0) {
            plugin.commsManager.send(player, Component.text("This item cannot be sold.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val material = handItem.type
        val totalAmount: Int
        val totalEarned: Double

        if (allOfType) {
            var count = 0
            for (i in 0 until player.inventory.size) {
                val item = player.inventory.getItem(i) ?: continue
                if (item.type == material) {
                    count += item.amount
                    player.inventory.setItem(i, null)
                }
            }
            totalAmount = count
            totalEarned = price * totalAmount
        } else {
            totalAmount = handItem.amount
            totalEarned = price * totalAmount
            player.inventory.setItemInMainHand(null)
        }

        plugin.economyManager.deposit(player.uniqueId, totalEarned)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)
        sendSellSummary(player, totalEarned, mapOf(material to totalAmount))
    }

    // ══════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════

    private fun sendSellSummary(player: Player, total: Double, breakdown: Map<Material, Int>) {
        val formatted = plugin.economyManager.format(total)
        plugin.commsManager.send(player,
            Component.text("Sold items for ", NamedTextColor.GREEN).append(Component.text(formatted, NamedTextColor.GOLD)),
            CommunicationsManager.Category.ECONOMY
        )
        for ((material, amount) in breakdown) {
            val name = material.name.lowercase().replace('_', ' ')
            plugin.commsManager.send(player,
                Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("${amount}x ", NamedTextColor.WHITE))
                    .append(Component.text(name, NamedTextColor.GRAY)),
                CommunicationsManager.Category.ECONOMY
            )
        }
    }

    private fun formatMaterialName(material: Material): String {
        return material.name.lowercase().replace('_', ' ')
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()

        return when (args.size) {
            1 -> listOf("all", "hand").filter { it.startsWith(args[0].lowercase()) }
            2 -> when (args[0].lowercase()) {
                "hand" -> listOf("all").filter { it.startsWith(args[1], ignoreCase = true) }
                "all" -> sellableMaterials.filter { it.startsWith(args[1].lowercase()) }.take(30)
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
