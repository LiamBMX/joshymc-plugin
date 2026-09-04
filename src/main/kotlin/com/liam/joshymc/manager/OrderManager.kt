package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player-driven Buy Orders marketplace ("/orders"). A buyer posts what they want and pays the
 * full price up front into escrow; any other player can sell matching items straight into the
 * order, partially or fully. The server never generates items or sets prices — everything here
 * only moves money/items that already belong to real players. Complements (does not replace)
 * /ah (players sell what they HAVE) and /shop (server-controlled convenience items).
 */
class OrderManager(private val plugin: Joshymc) : Listener {

    companion object {
        private val MAIN_TITLE: Component = Component.text("         ")
            .append(Component.text("Buy Orders", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val MY_ORDERS_TITLE: Component = Component.text("         ")
            .append(Component.text("My Buy Orders", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val SELL_TITLE: Component = Component.text("         ")
            .append(Component.text("Sell Into Order", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val CONFIRM_CREATE_TITLE: Component = Component.text("         ")
            .append(Component.text("Confirm Order", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val CONFIRM_SELL_TITLE: Component = Component.text("         ")
            .append(Component.text("Confirm Sale", NamedTextColor.GOLD))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val CONFIRM_CANCEL_TITLE: Component = Component.text("         ")
            .append(Component.text("Cancel Order?", NamedTextColor.RED))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.YELLOW_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }

        private val QUICK_AMOUNTS = intArrayOf(1, 16, 32, 64)
        private const val MAX_ESCROW = 1.0e15
    }

    data class BuyOrder(
        val id: Int,
        val buyerUuid: UUID,
        val buyerName: String,
        val item: ItemStack,
        val originalQty: Int,
        val remainingQty: Int,
        val pricePerItem: Double,
        val originalEscrow: Double,
        val remainingEscrow: Double,
        val createdAt: Long,
        val expiresAt: Long
    )

    data class PendingDelivery(
        val id: Int,
        val ownerUuid: UUID,
        val item: ItemStack,
        val createdAt: Long
    )

    enum class SortMode(val label: String, val orderBy: String) {
        NEWEST("Newest", "created_at DESC"),
        HIGHEST_PRICE("Highest Price", "price_per_item DESC"),
        LOWEST_PRICE("Lowest Price", "price_per_item ASC"),
        EXPIRING_SOON("Expiring Soon", "expires_at ASC");

        fun next(): SortMode = entries[(ordinal + 1) % entries.size]
    }

    enum class CreateStage { QUANTITY, PRICE }
    data class PendingCreation(val item: ItemStack, var stage: CreateStage, var quantity: Int = 0)

    // ---- Config ----
    private var expirationDays: Int = 7
    private var defaultLimit: Int = 10
    private var minPrice: Double = 1.0
    private var maxPrice: Double = 1_000_000_000.0
    private var maxQuantity: Int = 100_000
    private var customItemsEnabled: Boolean = false
    private var notificationsEnabled: Boolean = true
    private var limitPermissions: Map<String, Int> = emptyMap()

    // ---- State ----
    private val playerPages = ConcurrentHashMap<UUID, Int>()
    private val playerMyOrdersPages = ConcurrentHashMap<UUID, Int>()
    private val playerSort = ConcurrentHashMap<UUID, SortMode>()

    /** Multi-step chat input for order creation (quantity, then price). Public for the chat listener. */
    val pendingCreations = ConcurrentHashMap<UUID, PendingCreation>()

    /** Player UUID -> order ID they're typing a custom sell amount for. Public for the chat listener. */
    val pendingCustomSell = ConcurrentHashMap<UUID, Int>()

    private var expiryTask: BukkitTask? = null

    // ---- Lifecycle ----

    fun start() {
        loadConfig()

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS buy_orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                buyer_uuid TEXT NOT NULL,
                buyer_name TEXT NOT NULL,
                item TEXT NOT NULL,
                original_qty INTEGER NOT NULL,
                remaining_qty INTEGER NOT NULL,
                price_per_item REAL NOT NULL,
                original_escrow REAL NOT NULL,
                remaining_escrow REAL NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS order_pending_deliveries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid TEXT NOT NULL,
                item TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS order_notify_pending (
                uuid TEXT PRIMARY KEY
            )
        """.trimIndent())

        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            checkExpired()
        }, 1200L, 1200L)

        plugin.logger.info("[Orders] OrderManager started (expiration: ${expirationDays}d, default limit: $defaultLimit).")
    }

    fun stop() {
        expiryTask?.cancel()
        expiryTask = null
        playerPages.clear()
        playerMyOrdersPages.clear()
        playerSort.clear()
        pendingCreations.clear()
        pendingCustomSell.clear()
    }

    fun reloadValues() {
        loadConfig()
    }

    private fun loadConfig() {
        val cfg = plugin.config
        expirationDays = cfg.getInt("orders.expiration-days", 7)
        defaultLimit = cfg.getInt("orders.default-active-order-limit", 10)
        minPrice = cfg.getDouble("orders.minimum-price-per-item", 1.0)
        maxPrice = cfg.getDouble("orders.maximum-price-per-item", 1_000_000_000.0)
        maxQuantity = cfg.getInt("orders.maximum-quantity-per-order", 100_000)
        customItemsEnabled = cfg.getBoolean("orders.custom-items.enabled", false)
        notificationsEnabled = cfg.getBoolean("orders.notifications.enabled", true)

        val limitsSection = cfg.getConfigurationSection("orders.limits")
        limitPermissions = limitsSection?.getKeys(false)?.associateWith { limitsSection.getInt(it) } ?: emptyMap()
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))

    // ---- Row mappers ----

    private fun mapOrder(rs: java.sql.ResultSet): BuyOrder {
        return BuyOrder(
            id = rs.getInt("id"),
            buyerUuid = UUID.fromString(rs.getString("buyer_uuid")),
            buyerName = rs.getString("buyer_name"),
            item = deserializeItem(rs.getString("item")),
            originalQty = rs.getInt("original_qty"),
            remainingQty = rs.getInt("remaining_qty"),
            pricePerItem = rs.getDouble("price_per_item"),
            originalEscrow = rs.getDouble("original_escrow"),
            remainingEscrow = rs.getDouble("remaining_escrow"),
            createdAt = rs.getLong("created_at"),
            expiresAt = rs.getLong("expires_at")
        )
    }

    private fun mapDelivery(rs: java.sql.ResultSet): PendingDelivery {
        return PendingDelivery(
            id = rs.getInt("id"),
            ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
            item = deserializeItem(rs.getString("item")),
            createdAt = rs.getLong("created_at")
        )
    }

    // ---- Limits / matching ----

    fun getOrderLimit(player: Player): Int {
        var limit = defaultLimit
        for ((key, value) in limitPermissions) {
            if (value > limit && player.hasPermission("joshymc.orders.limit.$key")) limit = value
        }
        return limit
    }

    fun getPlayerActiveOrderCount(uuid: UUID): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM buy_orders WHERE buyer_uuid = ? AND remaining_qty > 0 AND expires_at > ?",
            uuid.toString(), System.currentTimeMillis()
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    /** Same-material + same-metadata match (enchants, custom item PDC tag, model data, etc.), ignoring stack size. */
    private fun itemsMatch(requested: ItemStack, candidate: ItemStack?): Boolean {
        if (candidate == null || candidate.type == Material.AIR) return false
        return candidate.isSimilar(requested)
    }

    private fun countMatching(inventory: Inventory, template: ItemStack): Int {
        return inventory.contents.filterNotNull().filter { itemsMatch(template, it) }.sumOf { it.amount }
    }

    /** Removes up to [amount] matching items, returning the actual removed stacks (metadata intact). */
    private fun removeMatching(inventory: Inventory, template: ItemStack, amount: Int): List<ItemStack> {
        val removed = mutableListOf<ItemStack>()
        var remaining = amount
        for (i in 0 until inventory.size) {
            if (remaining <= 0) break
            val item = inventory.getItem(i) ?: continue
            if (!itemsMatch(template, item)) continue
            if (item.amount <= remaining) {
                removed.add(item.clone())
                remaining -= item.amount
                inventory.setItem(i, null)
            } else {
                removed.add(item.clone().also { it.amount = remaining })
                item.amount -= remaining
                remaining = 0
            }
        }
        return removed
    }

    private fun formatTimeLeft(expiresAt: Long): String {
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0) return "Expired"
        val days = remaining / 86_400_000
        val hours = (remaining % 86_400_000) / 3_600_000
        val minutes = (remaining % 3_600_000) / 60_000
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    // ---- Delivery / notifications ----

    private fun deliverItems(buyerUuid: UUID, items: List<ItemStack>) {
        if (items.isEmpty()) return
        val buyer = Bukkit.getPlayer(buyerUuid)
        if (buyer == null) {
            storePendingDeliveries(buyerUuid, items)
            return
        }
        val leftover = buyer.inventory.addItem(*items.toTypedArray())
        if (leftover.isNotEmpty()) {
            storePendingDeliveries(buyerUuid, leftover.values.toList())
            plugin.commsManager.send(
                buyer,
                Component.text("Your inventory was full — some purchased items were held safely. Use ", NamedTextColor.YELLOW)
                    .append(Component.text("/orders", NamedTextColor.GOLD))
                    .append(Component.text(" to claim them.", NamedTextColor.YELLOW))
            )
        }
    }

    private fun storePendingDeliveries(uuid: UUID, items: List<ItemStack>) {
        val now = System.currentTimeMillis()
        for (item in items) {
            if (item.amount <= 0) continue
            plugin.databaseManager.execute(
                "INSERT INTO order_pending_deliveries (owner_uuid, item, created_at) VALUES (?, ?, ?)",
                uuid.toString(), serializeItem(item), now
            )
        }
        markNotifyPending(uuid)
    }

    private fun markNotifyPending(uuid: UUID) {
        if (!notificationsEnabled) return
        plugin.databaseManager.execute("INSERT OR IGNORE INTO order_notify_pending (uuid) VALUES (?)", uuid.toString())
    }

    fun getPendingDeliveries(uuid: UUID): List<PendingDelivery> {
        return plugin.databaseManager.query(
            "SELECT * FROM order_pending_deliveries WHERE owner_uuid = ? ORDER BY created_at ASC",
            uuid.toString()
        ) { rs -> mapDelivery(rs) }
    }

    fun getPendingDeliveryCount(uuid: UUID): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM order_pending_deliveries WHERE owner_uuid = ?",
            uuid.toString()
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun claimPendingDeliveries(player: Player) {
        val deliveries = getPendingDeliveries(player.uniqueId)
        if (deliveries.isEmpty()) {
            plugin.commsManager.send(player, Component.text("You have no pending items to claim.", NamedTextColor.YELLOW))
            return
        }

        var claimed = 0
        for (delivery in deliveries) {
            val rowsDeleted = plugin.databaseManager.executeUpdate(
                "DELETE FROM order_pending_deliveries WHERE id = ?", delivery.id
            )
            if (rowsDeleted == 0) continue

            val leftover = player.inventory.addItem(delivery.item)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }
            claimed++
        }

        if (claimed > 0) {
            player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f)
            plugin.commsManager.send(player, Component.text("Claimed $claimed pending item stack(s).", NamedTextColor.GREEN))
        } else {
            plugin.commsManager.send(player, Component.text("You have no pending items to claim.", NamedTextColor.YELLOW))
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        val rows = plugin.databaseManager.executeUpdate("DELETE FROM order_notify_pending WHERE uuid = ?", uuid.toString())
        if (rows > 0) {
            plugin.commsManager.send(
                event.player,
                Component.text("Your buy orders received items while you were offline. Use ", NamedTextColor.YELLOW)
                    .append(Component.text("/orders", NamedTextColor.GOLD))
                    .append(Component.text(" to view them.", NamedTextColor.YELLOW))
            )
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        playerPages.remove(uuid)
        playerMyOrdersPages.remove(uuid)
        playerSort.remove(uuid)
        pendingCreations.remove(uuid)
        pendingCustomSell.remove(uuid)
    }

    // ---- Order creation ----

    fun beginCreateOrder(player: Player) {
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to buy in your main hand first.", NamedTextColor.RED))
            return
        }

        val customId = plugin.itemManager.getCustomItemId(held)
        if (customId != null && !customItemsEnabled) {
            plugin.commsManager.send(player, Component.text("Custom items cannot be requested through Buy Orders yet.", NamedTextColor.RED))
            return
        }

        if (getPlayerActiveOrderCount(player.uniqueId) >= getOrderLimit(player)) {
            plugin.commsManager.send(player, Component.text("You have reached your active Buy Order limit (${getOrderLimit(player)}).", NamedTextColor.RED))
            return
        }

        val template = held.clone().also { it.amount = 1 }
        pendingCreations[player.uniqueId] = PendingCreation(template, CreateStage.QUANTITY)
        player.closeInventory()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.3f)
        plugin.commsManager.send(
            player,
            Component.text("Type the quantity you want to buy in chat (max ${maxQuantity}). Type 'cancel' to abort.", NamedTextColor.YELLOW)
        )
    }

    fun handleCreateChatInput(player: Player, raw: String) {
        val pending = pendingCreations[player.uniqueId] ?: return

        if (raw.equals("cancel", ignoreCase = true)) {
            pendingCreations.remove(player.uniqueId)
            plugin.commsManager.send(player, Component.text("Order creation cancelled.", NamedTextColor.GRAY))
            return
        }

        when (pending.stage) {
            CreateStage.QUANTITY -> {
                val qty = raw.replace(",", "").trim().toIntOrNull()
                if (qty == null || qty <= 0) {
                    plugin.commsManager.send(player, Component.text("Invalid quantity. Type a whole number, or 'cancel'.", NamedTextColor.RED))
                    return
                }
                if (qty > maxQuantity) {
                    plugin.commsManager.send(player, Component.text("Maximum quantity per order is $maxQuantity.", NamedTextColor.RED))
                    return
                }
                pending.quantity = qty
                pending.stage = CreateStage.PRICE
                plugin.commsManager.send(
                    player,
                    Component.text("Now type the price PER ITEM you'll pay (e.g. 100, 10k, 1.5m). Minimum ${plugin.economyManager.format(minPrice)}.", NamedTextColor.YELLOW)
                )
            }
            CreateStage.PRICE -> {
                val price = plugin.economyManager.parseAmount(raw)
                if (price == null || !price.isFinite() || price < minPrice) {
                    plugin.commsManager.send(player, Component.text("Invalid price. Minimum is ${plugin.economyManager.format(minPrice)}.", NamedTextColor.RED))
                    return
                }
                if (price > maxPrice) {
                    plugin.commsManager.send(player, Component.text("Maximum price per item is ${plugin.economyManager.format(maxPrice)}.", NamedTextColor.RED))
                    return
                }
                val escrow = pending.quantity.toDouble() * price
                if (!escrow.isFinite() || escrow > MAX_ESCROW) {
                    plugin.commsManager.send(player, Component.text("That order's total cost is too large.", NamedTextColor.RED))
                    return
                }

                pendingCreations.remove(player.uniqueId)
                openCreateConfirmGui(player, pending.item, pending.quantity, price, escrow)
            }
        }
    }

    private fun openCreateConfirmGui(player: Player, item: ItemStack, quantity: Int, pricePerItem: Double, escrow: Double) {
        val display = item.clone().also { it.amount = quantity.coerceIn(1, it.maxStackSize) }
        display.editMeta { meta ->
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Component.empty())
            lore.add(loreLine("Quantity: ").append(Component.text(quantity, NamedTextColor.WHITE)))
            lore.add(loreLine("Price Each: ").append(Component.text(plugin.economyManager.format(pricePerItem), NamedTextColor.GOLD)))
            lore.add(loreLine("Total Escrow: ").append(Component.text(plugin.economyManager.format(escrow), NamedTextColor.GOLD)))
            lore.add(Component.empty())
            lore.add(Component.text("  The full escrow is withdrawn immediately.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }

        val gui = buildConfirmGui(
            CONFIRM_CREATE_TITLE,
            display,
            onConfirm = { p -> p.closeInventory(); executeCreateOrder(p, item, quantity, pricePerItem, escrow) },
            onCancel = { p -> p.closeInventory(); plugin.commsManager.send(p, Component.text("Order creation cancelled.", NamedTextColor.GRAY)); openMainGui(p) }
        )
        plugin.guiManager.open(player, gui)
    }

    private fun executeCreateOrder(player: Player, item: ItemStack, quantity: Int, pricePerItem: Double, escrow: Double) {
        if (getPlayerActiveOrderCount(player.uniqueId) >= getOrderLimit(player)) {
            plugin.commsManager.send(player, Component.text("You have reached your active Buy Order limit.", NamedTextColor.RED))
            return
        }

        if (!plugin.economyManager.has(player.uniqueId, escrow)) {
            plugin.commsManager.send(player, Component.text("You can no longer afford this order.", NamedTextColor.RED))
            return
        }

        if (!plugin.economyManager.withdraw(player.uniqueId, escrow)) {
            plugin.commsManager.send(player, Component.text("Transaction failed.", NamedTextColor.RED))
            return
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + expirationDays * 86_400_000L

        try {
            plugin.databaseManager.execute(
                "INSERT INTO buy_orders (buyer_uuid, buyer_name, item, original_qty, remaining_qty, price_per_item, original_escrow, remaining_escrow, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                player.uniqueId.toString(), player.name, serializeItem(item), quantity, quantity, pricePerItem, escrow, escrow, now, expiresAt
            )
        } catch (e: Exception) {
            plugin.economyManager.deposit(player.uniqueId, escrow)
            plugin.logger.severe("[Orders] Failed to create order for ${player.name}: ${e.message}")
            plugin.commsManager.send(player, Component.text("Something went wrong creating your order. You have been refunded.", NamedTextColor.RED))
            return
        }

        plugin.logger.info("[Orders] ${player.name} created an order for ${quantity}x ${item.type} at ${plugin.economyManager.format(pricePerItem)} each (escrow ${plugin.economyManager.format(escrow)}).")

        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
        plugin.commsManager.send(
            player,
            Component.text("Your order for ", NamedTextColor.GREEN)
                .append(Component.text("${quantity}x ", NamedTextColor.WHITE))
                .append(item.displayName())
                .append(Component.text(" has been created for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(escrow), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
        openMainGui(player)
    }

    // ---- Queries ----

    fun getActiveOrders(page: Int, sort: SortMode, pageSize: Int = 28): List<BuyOrder> {
        val offset = page * pageSize
        return plugin.databaseManager.query(
            "SELECT * FROM buy_orders WHERE remaining_qty > 0 AND expires_at > ? ORDER BY ${sort.orderBy} LIMIT ? OFFSET ?",
            System.currentTimeMillis(), pageSize, offset
        ) { rs -> mapOrder(rs) }
    }

    fun getTotalActiveOrders(): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM buy_orders WHERE remaining_qty > 0 AND expires_at > ?",
            System.currentTimeMillis()
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun getPlayerOrders(uuid: UUID): List<BuyOrder> {
        return plugin.databaseManager.query(
            "SELECT * FROM buy_orders WHERE buyer_uuid = ? AND remaining_qty > 0 AND expires_at > ? ORDER BY created_at DESC",
            uuid.toString(), System.currentTimeMillis()
        ) { rs -> mapOrder(rs) }
    }

    fun getOrderById(id: Int): BuyOrder? {
        return plugin.databaseManager.queryFirst("SELECT * FROM buy_orders WHERE id = ?", id) { rs -> mapOrder(rs) }
    }

    // ---- Fulfillment ----

    fun handleCustomSellInput(player: Player, raw: String) {
        val orderId = pendingCustomSell[player.uniqueId] ?: return

        if (raw.equals("cancel", ignoreCase = true)) {
            pendingCustomSell.remove(player.uniqueId)
            plugin.commsManager.send(player, Component.text("Sale cancelled.", NamedTextColor.GRAY))
            return
        }

        val qty = raw.replace(",", "").trim().toIntOrNull()
        if (qty == null || qty <= 0) {
            plugin.commsManager.send(player, Component.text("Invalid quantity. Type a whole number, or 'cancel'.", NamedTextColor.RED))
            return
        }

        pendingCustomSell.remove(player.uniqueId)
        openFulfillConfirmGui(player, orderId, qty)
    }

    private fun openFulfillConfirmGui(player: Player, orderId: Int, requestedQty: Int) {
        val order = getOrderById(orderId)
        if (order == null || order.remainingQty <= 0 || order.expiresAt <= System.currentTimeMillis()) {
            plugin.commsManager.send(player, Component.text("That order is no longer active.", NamedTextColor.RED))
            openMainGui(player)
            return
        }
        if (order.buyerUuid == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You cannot sell into your own order.", NamedTextColor.RED))
            openMainGui(player)
            return
        }

        val owned = countMatching(player.inventory, order.item)
        val sellQty = minOf(requestedQty, owned, order.remainingQty)
        if (sellQty <= 0) {
            plugin.commsManager.send(
                player,
                if (owned <= 0) Component.text("You don't have any matching items.", NamedTextColor.RED)
                else Component.text("That order has no remaining quantity.", NamedTextColor.RED)
            )
            openFulfillGui(player, orderId)
            return
        }

        val payout = (sellQty.toDouble() * order.pricePerItem).coerceAtMost(order.remainingEscrow)

        val display = order.item.clone().also { it.amount = sellQty.coerceIn(1, it.maxStackSize) }
        display.editMeta { meta ->
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Component.empty())
            lore.add(loreLine("Sell: ").append(Component.text("${sellQty}x", NamedTextColor.WHITE)))
            lore.add(loreLine("Buyer: ").append(Component.text(order.buyerName, NamedTextColor.WHITE)))
            lore.add(loreLine("Receive: ").append(Component.text(plugin.economyManager.format(payout), NamedTextColor.GOLD)))
            meta.lore(lore)
        }

        val gui = buildConfirmGui(
            CONFIRM_SELL_TITLE,
            display,
            onConfirm = { p -> p.closeInventory(); executeFulfill(p, orderId, sellQty) },
            onCancel = { p -> p.closeInventory(); openFulfillGui(p, orderId) }
        )
        plugin.guiManager.open(player, gui)
    }

    fun executeFulfill(seller: Player, orderId: Int, requestedQty: Int) {
        val now = System.currentTimeMillis()
        val order = plugin.databaseManager.queryFirst(
            "SELECT * FROM buy_orders WHERE id = ? AND remaining_qty > 0 AND expires_at > ?",
            orderId, now
        ) { rs -> mapOrder(rs) }

        if (order == null) {
            plugin.commsManager.send(seller, Component.text("That order is no longer active.", NamedTextColor.RED))
            return
        }
        if (order.buyerUuid == seller.uniqueId) {
            plugin.commsManager.send(seller, Component.text("You cannot sell into your own order.", NamedTextColor.RED))
            return
        }
        if (requestedQty <= 0) {
            plugin.commsManager.send(seller, Component.text("Invalid amount.", NamedTextColor.RED))
            return
        }

        val owned = countMatching(seller.inventory, order.item)
        val sellQty = minOf(requestedQty, owned, order.remainingQty)
        if (sellQty <= 0) {
            plugin.commsManager.send(
                seller,
                if (owned <= 0) Component.text("You don't have any matching items.", NamedTextColor.RED)
                else Component.text("That order has no remaining quantity.", NamedTextColor.RED)
            )
            return
        }

        val payout = (sellQty.toDouble() * order.pricePerItem).coerceAtMost(order.remainingEscrow)

        // Anti-dupe: atomically claim the fulfillment slice first. If someone else beat us to it
        // (or the order changed), rowsUpdated will be 0 and nothing has moved yet.
        val rowsUpdated = plugin.databaseManager.executeUpdate(
            "UPDATE buy_orders SET remaining_qty = remaining_qty - ?, remaining_escrow = remaining_escrow - ? WHERE id = ? AND remaining_qty >= ? AND expires_at > ?",
            sellQty, payout, orderId, sellQty, now
        )
        if (rowsUpdated == 0) {
            plugin.commsManager.send(seller, Component.text("Someone beat you to it — try again.", NamedTextColor.RED))
            return
        }

        val removedItems = removeMatching(seller.inventory, order.item, sellQty)
        plugin.economyManager.deposit(seller.uniqueId, payout)
        deliverItems(order.buyerUuid, removedItems)

        val remainingAfter = order.remainingQty - sellQty
        if (remainingAfter <= 0) {
            plugin.databaseManager.execute("DELETE FROM buy_orders WHERE id = ? AND remaining_qty <= 0", orderId)
        }

        plugin.logger.info("[Orders] ${seller.name} sold ${sellQty}x ${order.item.type} into order #$orderId (buyer ${order.buyerName}) for ${plugin.economyManager.format(payout)}.")

        seller.playSound(seller.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)
        val soldMsg = Component.text("Sold ", NamedTextColor.GREEN)
            .append(Component.text("${sellQty}x ", NamedTextColor.WHITE))
            .append(order.item.displayName())
            .append(Component.text(" to ${order.buyerName} for ", NamedTextColor.GREEN))
            .append(Component.text(plugin.economyManager.format(payout), NamedTextColor.GOLD))
            .append(Component.text(".", NamedTextColor.GREEN))
        plugin.commsManager.send(seller, soldMsg)
        if (sellQty < requestedQty) {
            plugin.commsManager.send(seller, Component.text("(Only $sellQty were available to sell.)", NamedTextColor.GRAY))
        }

        val buyer = Bukkit.getPlayer(order.buyerUuid)
        if (buyer != null) {
            val buyerMsg = Component.text("${sellQty}x ", NamedTextColor.WHITE)
                .append(order.item.displayName())
                .append(Component.text(" were delivered to your order for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(payout), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
            plugin.commsManager.send(buyer, buyerMsg)
            if (remainingAfter <= 0) {
                plugin.commsManager.send(buyer, Component.text("Your order has been completed!", NamedTextColor.GOLD))
            }
        }
    }

    // ---- Cancellation ----

    private fun openCancelConfirmGui(player: Player, orderId: Int) {
        val order = getOrderById(orderId)
        if (order == null || order.buyerUuid != player.uniqueId) {
            plugin.commsManager.send(player, Component.text("Order not found.", NamedTextColor.RED))
            return
        }

        val display = order.item.clone().also { it.amount = order.remainingQty.coerceIn(1, it.maxStackSize) }
        display.editMeta { meta ->
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Component.empty())
            lore.add(loreLine("Remaining: ").append(Component.text("${order.remainingQty} / ${order.originalQty}", NamedTextColor.WHITE)))
            lore.add(loreLine("Refund: ").append(Component.text(plugin.economyManager.format(order.remainingEscrow), NamedTextColor.GOLD)))
            meta.lore(lore)
        }

        val gui = buildConfirmGui(
            CONFIRM_CANCEL_TITLE,
            display,
            onConfirm = { p -> p.closeInventory(); executeCancelOrder(p, orderId) },
            onCancel = { p -> p.closeInventory(); openMyOrdersGui(p) }
        )
        plugin.guiManager.open(player, gui)
    }

    fun executeCancelOrder(player: Player, orderId: Int) {
        val order = plugin.databaseManager.queryFirst(
            "SELECT * FROM buy_orders WHERE id = ? AND buyer_uuid = ?",
            orderId, player.uniqueId.toString()
        ) { rs -> mapOrder(rs) }

        if (order == null) {
            plugin.commsManager.send(player, Component.text("Order not found.", NamedTextColor.RED))
            return
        }

        val rowsDeleted = plugin.databaseManager.executeUpdate(
            "DELETE FROM buy_orders WHERE id = ? AND buyer_uuid = ?",
            orderId, player.uniqueId.toString()
        )
        if (rowsDeleted == 0) {
            plugin.commsManager.send(player, Component.text("Order not found.", NamedTextColor.RED))
            return
        }

        if (order.remainingEscrow > 0) {
            plugin.economyManager.deposit(player.uniqueId, order.remainingEscrow)
        }

        plugin.logger.info("[Orders] ${player.name} cancelled order #$orderId, refunded ${plugin.economyManager.format(order.remainingEscrow)}.")

        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f)
        plugin.commsManager.send(
            player,
            Component.text("Order cancelled. Refunded ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(order.remainingEscrow), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN))
        )
        openMyOrdersGui(player)
    }

    // ---- Admin ----

    /** Force-cancels any player's order and refunds their remaining escrow. Returns the cancelled order, or null if not found. */
    fun adminForceCancel(adminName: String, orderId: Int): BuyOrder? {
        val order = getOrderById(orderId) ?: return null

        val rowsDeleted = plugin.databaseManager.executeUpdate("DELETE FROM buy_orders WHERE id = ?", orderId)
        if (rowsDeleted == 0) return null

        if (order.remainingEscrow > 0) {
            plugin.economyManager.deposit(order.buyerUuid, order.remainingEscrow)
        }

        plugin.logger.info("[Orders] Admin $adminName removed order #$orderId (buyer ${order.buyerName}), refunded ${plugin.economyManager.format(order.remainingEscrow)}.")

        val buyer = Bukkit.getPlayer(order.buyerUuid)
        if (buyer != null) {
            plugin.commsManager.send(
                buyer,
                Component.text("Your Buy Order for ${order.originalQty}x ${order.item.type} was removed by an admin. Refunded ${plugin.economyManager.format(order.remainingEscrow)}.", NamedTextColor.YELLOW)
            )
        } else {
            markNotifyPending(order.buyerUuid)
        }

        return order
    }

    // ---- Expiration ----

    private fun checkExpired() {
        val now = System.currentTimeMillis()
        val expired = plugin.databaseManager.query(
            "SELECT * FROM buy_orders WHERE expires_at <= ?", now
        ) { rs -> mapOrder(rs) }

        if (expired.isEmpty()) return

        plugin.databaseManager.transaction {
            for (order in expired) {
                plugin.databaseManager.execute("DELETE FROM buy_orders WHERE id = ?", order.id)

                if (order.remainingEscrow > 0) {
                    plugin.economyManager.deposit(order.buyerUuid, order.remainingEscrow)
                }

                plugin.logger.info("[Orders] Order #${order.id} (${order.buyerName}) expired, refunded ${plugin.economyManager.format(order.remainingEscrow)}.")

                val buyer = Bukkit.getPlayer(order.buyerUuid)
                if (buyer != null) {
                    plugin.commsManager.send(
                        buyer,
                        Component.text("Your order expired. ", NamedTextColor.YELLOW)
                            .append(Component.text(plugin.economyManager.format(order.remainingEscrow), NamedTextColor.GOLD))
                            .append(Component.text(" was refunded.", NamedTextColor.YELLOW))
                    )
                } else {
                    markNotifyPending(order.buyerUuid)
                }
            }
        }
    }

    // ---- GUI helpers ----

    private fun loreLine(label: String): Component =
        Component.text("  $label", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)

    private fun simpleIcon(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val icon = ItemStack(material)
        icon.editMeta { meta ->
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            if (lore.isNotEmpty()) meta.lore(lore)
        }
        return icon
    }

    private fun borderedGui(title: Component, size: Int): CustomGui {
        val gui = CustomGui(title, size)
        val rows = size / 9
        for (i in 0 until 9) gui.inventory.setItem(i, BORDER.clone())
        for (i in size - 9 until size) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1 until rows - 1) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
            for (col in 1..7) {
                gui.inventory.setItem(row * 9 + col, FILLER.clone())
            }
        }
        return gui
    }

    private fun buildConfirmGui(
        title: Component,
        centerItem: ItemStack,
        onConfirm: (Player) -> Unit,
        onCancel: (Player) -> Unit
    ): CustomGui {
        val gui = CustomGui(title, 27)

        val redGlass = ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text("Cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)) }
        }
        val greenGlass = ItemStack(Material.LIME_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text("Confirm", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)) }
        }

        for (i in 0 until 27) {
            val col = i % 9
            if (col < 4) {
                gui.setItem(i, redGlass.clone()) { p, _ -> onCancel(p) }
            } else if (col > 4) {
                gui.setItem(i, greenGlass.clone()) { p, _ -> onConfirm(p) }
            }
        }

        gui.inventory.setItem(13, centerItem)
        return gui
    }

    private fun contentSlots(size: Int): List<Int> {
        val rows = size / 9
        val slots = mutableListOf<Int>()
        for (row in 1 until rows - 1) for (col in 1..7) slots.add(row * 9 + col)
        return slots
    }

    private fun createOrderIcon(order: BuyOrder, viewerUuid: UUID): ItemStack {
        val icon = order.item.clone().also { it.amount = order.remainingQty.coerceIn(1, it.maxStackSize) }
        icon.editMeta { meta ->
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Component.empty())
            lore.add(loreLine("Buyer: ").append(Component.text(order.buyerName, NamedTextColor.WHITE)))
            lore.add(loreLine("Price Each: ").append(Component.text(plugin.economyManager.format(order.pricePerItem), NamedTextColor.GOLD)))
            lore.add(loreLine("Remaining: ").append(Component.text("${order.remainingQty} / ${order.originalQty}", NamedTextColor.WHITE)))
            lore.add(loreLine("Total Remaining: ").append(Component.text("$${plugin.economyManager.formatShort(order.remainingEscrow)}", NamedTextColor.GOLD)))
            lore.add(loreLine("Expires: ").append(Component.text(formatTimeLeft(order.expiresAt), NamedTextColor.YELLOW)))
            lore.add(Component.empty())
            if (order.buyerUuid == viewerUuid) {
                lore.add(Component.text("  This is your order.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            } else {
                lore.add(Component.text("  LEFT CLICK to sell items", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
        }
        return icon
    }

    private fun createMyOrderIcon(order: BuyOrder): ItemStack {
        val filled = order.originalQty - order.remainingQty
        val icon = order.item.clone().also { it.amount = order.remainingQty.coerceIn(1, it.maxStackSize) }
        icon.editMeta { meta ->
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Component.empty())
            lore.add(loreLine("Price Each: ").append(Component.text(plugin.economyManager.format(order.pricePerItem), NamedTextColor.GOLD)))
            lore.add(loreLine("Filled: ").append(Component.text("$filled / ${order.originalQty}", NamedTextColor.WHITE)))
            lore.add(loreLine("Remaining: ").append(Component.text(order.remainingQty, NamedTextColor.WHITE)))
            lore.add(loreLine("Escrow Remaining: ").append(Component.text("$${plugin.economyManager.formatShort(order.remainingEscrow)}", NamedTextColor.GOLD)))
            lore.add(loreLine("Expires: ").append(Component.text(formatTimeLeft(order.expiresAt), NamedTextColor.YELLOW)))
            lore.add(Component.empty())
            lore.add(Component.text("  CLICK to cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return icon
    }

    // ---- Main marketplace GUI ----

    fun openMainGui(player: Player, page: Int = 0) {
        val sort = playerSort.getOrDefault(player.uniqueId, SortMode.NEWEST)
        val gui = borderedGui(MAIN_TITLE, 54)

        val orders = getActiveOrders(page, sort)
        val slots = contentSlots(54)
        for ((index, order) in orders.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]
            gui.setItem(slot, createOrderIcon(order, player.uniqueId)) { p, _ ->
                if (order.buyerUuid == p.uniqueId) {
                    plugin.commsManager.send(p, Component.text("This is your own order — manage it from My Orders.", NamedTextColor.YELLOW))
                } else {
                    openFulfillGui(p, order.id)
                }
            }
        }

        val total = getTotalActiveOrders()
        val totalPages = maxOf(1, (total + 27) / 28)

        if (page > 0) {
            gui.setItem(46, simpleIcon(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ -> openMainGui(p, page - 1) }
        }

        gui.setItem(47, simpleIcon(
            Material.BOOK,
            Component.text("My Orders", NamedTextColor.AQUA).decoration(TextDecoration.BOLD, true),
            listOf(Component.empty(), Component.text("  View and manage your orders", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        )) { p, _ -> openMyOrdersGui(p) }

        gui.setItem(49, simpleIcon(
            Material.EMERALD,
            Component.text("Create Order", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
            listOf(
                Component.empty(),
                Component.text("  Hold the item you want to buy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  and click to start", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            )
        )) { p, _ -> beginCreateOrder(p) }

        gui.setItem(51, simpleIcon(
            Material.HOPPER,
            Component.text("Sort: ${sort.label}", TextColor.color(0xFFAA00)).decoration(TextDecoration.BOLD, true),
            listOf(Component.empty(), Component.text("  Click to change", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        )) { p, _ ->
            playerSort[p.uniqueId] = sort.next()
            openMainGui(p, 0)
        }

        if (page < totalPages - 1) {
            gui.setItem(52, simpleIcon(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ -> openMainGui(p, page + 1) }
        }

        gui.inventory.setItem(4, simpleIcon(
            Material.PAPER,
            Component.text("Page ${page + 1}/$totalPages", NamedTextColor.WHITE),
            listOf(Component.text("  $total active order(s)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        ))

        playerPages[player.uniqueId] = page
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ---- My Orders GUI ----

    private fun openMyOrdersGui(player: Player, page: Int = 0) {
        val gui = borderedGui(MY_ORDERS_TITLE, 54)

        val orders = getPlayerOrders(player.uniqueId)
        val pageSize = 28
        val pageOrders = orders.drop(page * pageSize).take(pageSize)
        val slots = contentSlots(54)

        for ((index, order) in pageOrders.withIndex()) {
            if (index >= slots.size) break
            val slot = slots[index]
            gui.setItem(slot, createMyOrderIcon(order)) { p, _ -> openCancelConfirmGui(p, order.id) }
        }

        val totalPages = maxOf(1, (orders.size + pageSize - 1) / pageSize)
        if (page > 0) {
            gui.setItem(46, simpleIcon(Material.ARROW, Component.text("Previous Page", NamedTextColor.YELLOW))) { p, _ -> openMyOrdersGui(p, page - 1) }
        }
        if (page < totalPages - 1) {
            gui.setItem(52, simpleIcon(Material.ARROW, Component.text("Next Page", NamedTextColor.YELLOW))) { p, _ -> openMyOrdersGui(p, page + 1) }
        }

        val pendingCount = getPendingDeliveryCount(player.uniqueId)
        gui.setItem(47, simpleIcon(
            if (pendingCount > 0) Material.CHEST else Material.BARREL,
            Component.text("Pending Items ($pendingCount)", if (pendingCount > 0) NamedTextColor.GOLD else NamedTextColor.DARK_GRAY).decoration(TextDecoration.BOLD, true),
            listOf(
                Component.empty(),
                if (pendingCount > 0) Component.text("  Click to claim", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                else Component.text("  Nothing to claim", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            )
        )) { p, _ -> claimPendingDeliveries(p); openMyOrdersGui(p, page) }

        gui.setItem(49, simpleIcon(Material.BARRIER, Component.text("Back", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))) { p, _ -> openMainGui(p) }

        playerMyOrdersPages[player.uniqueId] = page
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ---- Sell into order (fulfillment picker) GUI ----

    private fun openFulfillGui(player: Player, orderId: Int) {
        val order = getOrderById(orderId)
        if (order == null || order.remainingQty <= 0 || order.expiresAt <= System.currentTimeMillis()) {
            plugin.commsManager.send(player, Component.text("That order is no longer active.", NamedTextColor.RED))
            openMainGui(player)
            return
        }
        if (order.buyerUuid == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You cannot sell into your own order.", NamedTextColor.RED))
            openMainGui(player)
            return
        }

        val gui = borderedGui(SELL_TITLE, 27)

        val owned = countMatching(player.inventory, order.item)
        val sellAll = minOf(owned, order.remainingQty)

        val infoIcon = order.item.clone().also { it.amount = order.remainingQty.coerceIn(1, it.maxStackSize) }
        infoIcon.editMeta { meta ->
            val lore = (meta.lore() ?: mutableListOf()).toMutableList()
            lore.add(Component.empty())
            lore.add(loreLine("Buyer: ").append(Component.text(order.buyerName, NamedTextColor.WHITE)))
            lore.add(loreLine("Price Each: ").append(Component.text(plugin.economyManager.format(order.pricePerItem), NamedTextColor.GOLD)))
            lore.add(loreLine("Remaining: ").append(Component.text(order.remainingQty, NamedTextColor.WHITE)))
            lore.add(loreLine("You Have: ").append(Component.text(owned, NamedTextColor.WHITE)))
            meta.lore(lore)
        }
        gui.inventory.setItem(13, infoIcon)

        if (sellAll <= 0) {
            gui.inventory.setItem(11, simpleIcon(Material.BARRIER, Component.text("You don't have any matching items", NamedTextColor.RED)))
        } else {
            val fixedSlots = mapOf(1 to 10, 16 to 11, 32 to 12, 64 to 14)
            for (amount in QUICK_AMOUNTS) {
                if (amount >= sellAll) continue
                val slot = fixedSlots[amount] ?: continue
                gui.setItem(slot, simpleIcon(
                    Material.LIME_DYE,
                    Component.text("Sell $amount", NamedTextColor.GREEN),
                    listOf(Component.empty(), Component.text("  Receive: ${plugin.economyManager.format(amount * order.pricePerItem)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                )) { p, _ -> openFulfillConfirmGui(p, orderId, amount) }
            }

            gui.setItem(15, simpleIcon(
                Material.EMERALD,
                Component.text("Sell All Possible ($sellAll)", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
                listOf(Component.empty(), Component.text("  Receive: ${plugin.economyManager.format(sellAll * order.pricePerItem)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )) { p, _ -> openFulfillConfirmGui(p, orderId, sellAll) }

            gui.setItem(16, simpleIcon(
                Material.PAPER,
                Component.text("Custom Amount", NamedTextColor.YELLOW),
                listOf(Component.empty(), Component.text("  Click to type an amount", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )) { p, _ ->
                pendingCustomSell[p.uniqueId] = orderId
                p.closeInventory()
                plugin.commsManager.send(p, Component.text("Type the quantity to sell in chat (max $sellAll). Type 'cancel' to abort.", NamedTextColor.YELLOW))
            }
        }

        gui.setItem(22, simpleIcon(Material.BARRIER, Component.text("Back", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))) { p, _ -> openMainGui(p) }

        plugin.guiManager.open(player, gui)
    }
}
