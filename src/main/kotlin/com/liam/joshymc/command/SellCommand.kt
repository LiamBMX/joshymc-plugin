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
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SellCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter, Listener {

    /** One deposit slots + control row, per open GUI. Removed from the map exactly once —
     *  on close or on quit, whichever fires first — so a sale can never be processed twice. */
    private class SellSession(val inventory: Inventory)

    private val openSellSessions = ConcurrentHashMap<UUID, SellSession>()

    /** Deposit area is the top 5 rows (0-44); the bottom row (45-53) is info/control only. */
    private val DEPOSIT_SLOTS = 45
    private val GUI_SIZE = 54

    private val CONTROL_FILLER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // Cache of all sellable material names for tab completion
    private var sellableMaterials: List<String> = emptyList()

    fun refreshSellableCache() {
        sellableMaterials = Material.entries
            .filter { plugin.sellPriceManager.getPrice(it) != null }
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
                // /sell with no args — open the deposit GUI
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
    //  SELL GUI — deposit items to sell them, sale confirms on close
    // ══════════════════════════════════════════════════════════

    private fun openSellGui(player: Player) {
        val title = Component.text("Sell Items", NamedTextColor.GOLD)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val inv = Bukkit.createInventory(null, GUI_SIZE, title)
        buildControlRow(inv, 0.0)

        openSellSessions[player.uniqueId] = SellSession(inv)
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun buildControlRow(inv: Inventory, estimate: Double) {
        for (i in DEPOSIT_SLOTS until GUI_SIZE) inv.setItem(i, CONTROL_FILLER.clone())

        val info = ItemStack(Material.PAPER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("How This Works", NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Place items in the slots above.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("Unsellable items are ignored", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("and returned to you.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }
        }
        inv.setItem(45, info)

        inv.setItem(49, buildValueItem(estimate))

        val closeInfo = ItemStack(Material.HOPPER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Sells On Close", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Closing this menu sells", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("everything sellable above.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("No extra confirmation needed.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }
        }
        inv.setItem(53, closeInfo)
    }

    private fun buildValueItem(estimate: Double): ItemStack {
        return ItemStack(Material.GOLD_INGOT).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Estimated Value", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("$" + plugin.economyManager.formatShort(estimate), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }
        }
    }

    private fun refreshEstimate(session: SellSession) {
        var total = 0.0
        for (i in 0 until DEPOSIT_SLOTS) {
            val item = session.inventory.getItem(i) ?: continue
            total += plugin.sellPriceManager.getStackValue(item)
        }
        total = Math.round(total * 100.0) / 100.0
        session.inventory.setItem(49, buildValueItem(total))
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSellGuiClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = openSellSessions[player.uniqueId] ?: return
        if (event.view.topInventory !== session.inventory) return

        // Block placing/taking/swapping in the control row — it's info-only.
        if (event.clickedInventory === session.inventory && event.slot >= DEPOSIT_SLOTS) {
            event.isCancelled = true
            return
        }

        // Any other click that touches this GUI (deposit, shift-click, swap, etc.) may have
        // changed the deposit contents — recompute the estimate once the click resolves.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (openSellSessions[player.uniqueId] === session) refreshEstimate(session)
        })
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSellGuiDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val session = openSellSessions[player.uniqueId] ?: return
        if (event.view.topInventory !== session.inventory) return

        if (event.rawSlots.any { it in DEPOSIT_SLOTS until GUI_SIZE }) {
            event.isCancelled = true
            return
        }

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (openSellSessions[player.uniqueId] === session) refreshEstimate(session)
        })
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onSellGuiClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val session = openSellSessions.remove(player.uniqueId) ?: return
        processSellSession(player, session)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val session = openSellSessions.remove(event.player.uniqueId) ?: return
        processSellSession(event.player, session)
    }

    /** Called from Joshymc.onDisable()/reload() so no deposited items are ever lost on
     *  shutdown or plugin reload — every open session is settled exactly once. */
    fun resolveAllOpenSessions() {
        for ((uuid, session) in openSellSessions.toMap()) {
            openSellSessions.remove(uuid)
            val player = Bukkit.getPlayer(uuid) ?: continue
            processSellSession(player, session)
            player.closeInventory()
        }
    }

    private fun processSellSession(player: Player, session: SellSession) {
        val inv = session.inventory
        var totalEarned = 0.0
        var soldCount = 0
        var returnedCount = 0
        val breakdown = mutableMapOf<Material, Int>()
        val unsellable = mutableListOf<ItemStack>()

        for (i in 0 until DEPOSIT_SLOTS) {
            val item = inv.getItem(i) ?: continue
            if (item.type == Material.AIR) continue

            if (plugin.sellPriceManager.isSellable(item)) {
                totalEarned += plugin.sellPriceManager.getStackValue(item)
                soldCount += item.amount
                breakdown[item.type] = (breakdown[item.type] ?: 0) + item.amount
            } else {
                unsellable.add(item.clone())
                returnedCount += item.amount
            }
            inv.setItem(i, null)
        }

        totalEarned = Math.round(totalEarned * 100.0) / 100.0

        for (item in unsellable) {
            val leftover = player.inventory.addItem(item)
            for (drop in leftover.values) {
                player.world.dropItemNaturally(player.location, drop)
            }
        }

        if (totalEarned > 0) {
            plugin.economyManager.deposit(player.uniqueId, totalEarned)
            for ((material, amount) in breakdown) {
                plugin.marketManager.recordTransaction(material, "SELL", amount)
            }
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f)
        }

        if (soldCount > 0 || returnedCount > 0) {
            sendSellCloseSummary(player, soldCount, totalEarned, returnedCount)
        }
    }

    private fun sendSellCloseSummary(player: Player, soldCount: Int, total: Double, returnedCount: Int) {
        if (soldCount == 0) {
            plugin.commsManager.send(player,
                Component.text("None of those items could be sold. Items returned.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            return
        }

        var message = Component.text("Sold ", NamedTextColor.GREEN)
            .append(Component.text("${"%,d".format(soldCount)} items", NamedTextColor.WHITE))
            .append(Component.text(" for ", NamedTextColor.GREEN))
            .append(Component.text("$" + plugin.economyManager.formatShort(total), NamedTextColor.GOLD))
            .append(Component.text(".", NamedTextColor.GREEN))

        if (returnedCount > 0) {
            message = message.append(
                Component.text(" ${"%,d".format(returnedCount)} unsellable item${if (returnedCount == 1) "" else "s"} were returned.", NamedTextColor.GRAY)
            )
        }

        plugin.commsManager.send(player, message, CommunicationsManager.Category.ECONOMY)
    }

    // ══════════════════════════════════════════════════════════
    //  SELL ALL — sell everything sellable in inventory
    // ══════════════════════════════════════════════════════════

    private fun sellAll(player: Player) {
        var totalEarned = 0.0
        val breakdown = mutableMapOf<Material, Int>()

        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (!plugin.sellPriceManager.isSellable(item)) continue

            val basePrice = plugin.sellPriceManager.getPrice(item.type) ?: continue
            val price = plugin.serverShopManager.applyCropBonus(basePrice, item.type, player.uniqueId)
            val mutMult = plugin.mutationsManager.getMutationMultiplier(item)
            totalEarned += price * mutMult * item.amount
            breakdown[item.type] = (breakdown[item.type] ?: 0) + item.amount
            player.inventory.setItem(i, null)
        }

        if (totalEarned <= 0) {
            plugin.commsManager.send(player, Component.text("You have nothing to sell.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        plugin.economyManager.deposit(player.uniqueId, totalEarned)
        for ((material, amount) in breakdown) {
            plugin.marketManager.recordTransaction(material, "SELL", amount)
        }
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

        val basePrice = plugin.sellPriceManager.getPrice(material)
        if (basePrice == null) {
            plugin.commsManager.send(player, Component.text("That item cannot be sold.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val price = plugin.serverShopManager.applyCropBonus(basePrice, material, player.uniqueId)

        var count = 0
        var totalEarned = 0.0
        for (i in 0 until player.inventory.size) {
            val item = player.inventory.getItem(i) ?: continue
            if (item.type == material && plugin.sellPriceManager.isSellable(item)) {
                val mutMult = plugin.mutationsManager.getMutationMultiplier(item)
                totalEarned += price * mutMult * item.amount
                count += item.amount
                player.inventory.setItem(i, null)
            }
        }

        if (count == 0) {
            plugin.commsManager.send(player, Component.text("You don't have any of that item.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }
        plugin.economyManager.deposit(player.uniqueId, totalEarned)
        plugin.marketManager.recordTransaction(material, "SELL", count)
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

        if (!plugin.sellPriceManager.isSellable(handItem)) {
            plugin.commsManager.send(player, Component.text("This item cannot be sold.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val material = handItem.type
        val basePrice = plugin.sellPriceManager.getPrice(material) ?: return
        val price = plugin.serverShopManager.applyCropBonus(basePrice, material, player.uniqueId)
        val totalAmount: Int
        val totalEarned: Double

        if (allOfType) {
            var count = 0
            var earned = 0.0
            for (i in 0 until player.inventory.size) {
                val item = player.inventory.getItem(i) ?: continue
                if (item.type == material && plugin.sellPriceManager.isSellable(item)) {
                    val mutMult = plugin.mutationsManager.getMutationMultiplier(item)
                    earned += price * mutMult * item.amount
                    count += item.amount
                    player.inventory.setItem(i, null)
                }
            }
            totalAmount = count
            totalEarned = earned
        } else {
            totalAmount = handItem.amount
            totalEarned = price * plugin.mutationsManager.getMutationMultiplier(handItem) * totalAmount
            player.inventory.setItemInMainHand(null)
        }

        plugin.economyManager.deposit(player.uniqueId, totalEarned)
        plugin.marketManager.recordTransaction(material, "SELL", totalAmount)
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
