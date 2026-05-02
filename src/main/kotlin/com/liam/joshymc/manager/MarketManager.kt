package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

class MarketManager(private val plugin: Joshymc) {

    enum class Trend(val symbol: String, val color: NamedTextColor, val legacyCode: String) {
        UP("▲", NamedTextColor.GREEN, "&a"),
        DOWN("▼", NamedTextColor.RED, "&c"),
        STABLE("■", NamedTextColor.GRAY, "&7")
    }

    private val multiplierCache = ConcurrentHashMap<Material, Double>()

    private var recalculateTask: BukkitTask? = null
    private var cleanupTask: BukkitTask? = null

    private val WINDOW_MS = 24 * 3600 * 1000L
    private val SENSITIVITY = 0.001
    private val MIN_MULTIPLIER = 0.3
    private val MAX_MULTIPLIER = 3.0
    /** Need at least this much net volume in the window before the multiplier
     *  moves at all. Stops a small server from showing "+200%" off two
     *  transactions. */
    private val MIN_VOLUME_FOR_MOVE = 50L
    /** Per-recalc fraction of the gap to 1.0 that decays away when an item
     *  has no recent activity. 0.1 = 10% reversion every 5 minutes. */
    private val IDLE_DECAY_RATE = 0.10

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val BORDER = ItemStack(Material.GREEN_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS market_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                material TEXT NOT NULL,
                type TEXT NOT NULL,
                amount INTEGER NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        recalculateAll()

        // Recalculate every 5 minutes (6000 ticks)
        recalculateTask = plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable {
            recalculateAll()
        }, 6000L, 6000L)

        // Cleanup old transactions every hour (72000 ticks) — delete older than 48h
        cleanupTask = plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable {
            val cutoff = System.currentTimeMillis() - (48 * 3600 * 1000L)
            plugin.databaseManager.execute(
                "DELETE FROM market_transactions WHERE timestamp < ?",
                cutoff
            )
        }, 72000L, 72000L)

        plugin.logger.info("[Market] MarketManager started.")
    }

    fun stop() {
        recalculateTask?.cancel()
        cleanupTask?.cancel()
        recalculateTask = null
        cleanupTask = null
    }

    // ── Transaction Recording ───────────────────────────────────────────

    fun recordTransaction(material: Material, type: String, amount: Int) {
        plugin.databaseManager.execute(
            "INSERT INTO market_transactions (material, type, amount, timestamp) VALUES (?, ?, ?, ?)",
            material.name, type, amount, System.currentTimeMillis()
        )
    }

    // ── Price Calculation ───────────────────────────────────────────────

    fun recalculateAll() {
        val now = System.currentTimeMillis()
        val windowStart = now - WINDOW_MS

        // Get all materials with transactions in the window
        val activeNames = plugin.databaseManager.query(
            "SELECT DISTINCT material FROM market_transactions WHERE timestamp > ?",
            windowStart
        ) { rs -> rs.getString("material") }.toSet()

        for (materialName in activeNames) {
            val material = Material.matchMaterial(materialName) ?: continue

            val totalBought = plugin.databaseManager.queryFirst(
                "SELECT COALESCE(SUM(amount), 0) AS total FROM market_transactions WHERE material = ? AND type = 'BUY' AND timestamp > ?",
                materialName, windowStart
            ) { rs -> rs.getLong("total") } ?: 0L

            val totalSold = plugin.databaseManager.queryFirst(
                "SELECT COALESCE(SUM(amount), 0) AS total FROM market_transactions WHERE material = ? AND type = 'SELL' AND timestamp > ?",
                materialName, windowStart
            ) { rs -> rs.getLong("total") } ?: 0L

            val totalVolume = totalBought + totalSold
            // Below the volume threshold, hold the multiplier at 1.0 — stops
            // single-digit transactions from yanking the price 200%.
            if (totalVolume < MIN_VOLUME_FOR_MOVE) {
                multiplierCache[material] = 1.0
                continue
            }

            val netDemand = totalBought - totalSold
            val rawMultiplier = 1.0 + (netDemand * SENSITIVITY)
            multiplierCache[material] = rawMultiplier.coerceIn(MIN_MULTIPLIER, MAX_MULTIPLIER)
        }

        // Decay multipliers for items that no longer have any activity in
        // the window — without this a one-off pump leaves the multiplier
        // stuck at 3.00x forever. Pull each idle multiplier 10% closer to
        // 1.0 every recalc tick; once it's effectively neutral, drop it.
        val activeKeys = activeNames.mapNotNull { Material.matchMaterial(it) }.toSet()
        val idle = multiplierCache.keys - activeKeys
        for (mat in idle) {
            val current = multiplierCache[mat] ?: continue
            val decayed = current + (1.0 - current) * IDLE_DECAY_RATE
            if (kotlin.math.abs(decayed - 1.0) < 0.01) {
                multiplierCache.remove(mat)
            } else {
                multiplierCache[mat] = decayed
            }
        }
    }

    fun getMultiplier(material: Material): Double {
        return multiplierCache.getOrDefault(material, 1.0)
    }

    fun getCurrentBuyPrice(material: Material): Double? {
        val base = getBaseBuyPrice(material) ?: return null
        return base * getMultiplier(material)
    }

    fun getCurrentSellPrice(material: Material): Double? {
        val base = getBaseSellPrice(material) ?: return null
        return base * getMultiplier(material)
    }

    private fun getBaseBuyPrice(material: Material): Double? {
        for (category in plugin.serverShopManager.getCategories()) {
            val item = category.items.find { it.material == material }
            if (item != null && item.buyPrice > 0) return item.buyPrice
        }
        return null
    }

    private fun getBaseSellPrice(material: Material): Double? {
        return plugin.serverShopManager.getSellPrice(material)
    }

    // ── Trend Calculation ───────────────────────────────────────────────

    /**
     * Trend arrow now mirrors the multiplier directly so the arrow and the
     * percentage in the same line always agree. Previously the arrow was a
     * 6h-vs-prev-6h rate-of-change indicator while the percent was the 24h
     * cumulative; the two metrics disagreed often (e.g. an item pumped to
     * 3.00x earlier and now sitting still showed "+200% ▼"), which is what
     * users were complaining about.
     */
    fun getTrend(material: Material): Trend {
        val mult = getMultiplier(material)
        return when {
            mult > 1.05 -> Trend.UP
            mult < 0.95 -> Trend.DOWN
            else -> Trend.STABLE
        }
    }

    // ── Top Movers ──────────────────────────────────────────────────────

    fun getTopMovers(): List<Pair<Material, Double>> {
        return multiplierCache.entries
            .sortedByDescending { kotlin.math.abs(it.value - 1.0) }
            .take(10)
            .map { it.key to it.value }
    }

    // ── Volume ──────────────────────────────────────────────────────────

    private fun get24hVolume(material: Material): Long {
        val cutoff = System.currentTimeMillis() - WINDOW_MS
        return plugin.databaseManager.queryFirst(
            "SELECT COALESCE(SUM(amount), 0) AS total FROM market_transactions WHERE material = ? AND timestamp > ?",
            material.name, cutoff
        ) { rs -> rs.getLong("total") } ?: 0L
    }

    // ── GUI: Market Overview ────────────────────────────────────────────

    fun openMarketOverview(player: Player, sortMode: SortMode = SortMode.VOLUME) {
        val title = Component.text("Stock Market", NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 54)
        gui.fill(FILLER.clone())
        gui.border(BORDER.clone())

        // Gather all shop items
        val allItems = plugin.serverShopManager.getCategories()
            .flatMap { it.items }
            .distinctBy { it.material }

        // Sort based on mode
        val sorted = when (sortMode) {
            SortMode.VOLUME -> allItems.sortedByDescending { get24hVolume(it.material) }
            SortMode.PRICE -> allItems.sortedByDescending { getCurrentBuyPrice(it.material) ?: 0.0 }
            SortMode.TREND -> allItems.sortedByDescending { kotlin.math.abs(getMultiplier(it.material) - 1.0) }
        }

        // Item slots: rows 1-4, columns 1-7
        val itemSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                itemSlots.add(row * 9 + col)
            }
        }

        val displayItems = sorted.take(itemSlots.size)
        for ((index, shopItem) in displayItems.withIndex()) {
            val slot = itemSlots[index]
            val icon = buildOverviewIcon(shopItem.material, shopItem)

            gui.setItem(slot, icon) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openItemDetail(p, shopItem.material)
            }
        }

        // Bottom row: sort buttons
        val volumeSortItem = buildSortButton("Sort by Volume", Material.HOPPER, sortMode == SortMode.VOLUME)
        gui.setItem(48, volumeSortItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMarketOverview(p, SortMode.VOLUME)
        }

        val priceSortItem = buildSortButton("Sort by Price", Material.GOLD_INGOT, sortMode == SortMode.PRICE)
        gui.setItem(49, priceSortItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMarketOverview(p, SortMode.PRICE)
        }

        val trendSortItem = buildSortButton("Sort by Trend", Material.COMPASS, sortMode == SortMode.TREND)
        gui.setItem(50, trendSortItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMarketOverview(p, SortMode.TREND)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun buildOverviewIcon(material: Material, shopItem: ServerShopManager.ShopItem): ItemStack {
        val multiplier = getMultiplier(material)
        val trend = getTrend(material)
        val buyPrice = getCurrentBuyPrice(material)
        val sellPrice = getCurrentSellPrice(material)
        val volume = get24hVolume(material)
        val percentChange = ((multiplier - 1.0) * 100)
        val sign = if (percentChange >= 0) "+" else ""

        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(formatMaterialName(material), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )

                val lore = mutableListOf<Component>()

                // Buy price line
                if (buyPrice != null) {
                    lore.add(
                        Component.text("Buy: ", NamedTextColor.GRAY)
                            .append(Component.text(plugin.economyManager.format(buyPrice), NamedTextColor.GREEN))
                            .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                            .append(Component.text(trend.symbol, trend.color))
                            .append(Component.text(" ${sign}${String.format("%.1f", percentChange)}%", trend.color))
                            .append(Component.text(")", NamedTextColor.DARK_GRAY))
                            .decoration(TextDecoration.ITALIC, false)
                    )
                } else {
                    lore.add(
                        Component.text("Buy: ", NamedTextColor.GRAY)
                            .append(Component.text("N/A", NamedTextColor.RED))
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }

                // Sell price line
                if (sellPrice != null) {
                    lore.add(
                        Component.text("Sell: ", NamedTextColor.GRAY)
                            .append(Component.text(plugin.economyManager.format(sellPrice), NamedTextColor.YELLOW))
                            .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                            .append(Component.text(trend.symbol, trend.color))
                            .append(Component.text(" ${sign}${String.format("%.1f", percentChange)}%", trend.color))
                            .append(Component.text(")", NamedTextColor.DARK_GRAY))
                            .decoration(TextDecoration.ITALIC, false)
                    )
                } else {
                    lore.add(
                        Component.text("Sell: ", NamedTextColor.GRAY)
                            .append(Component.text("N/A", NamedTextColor.RED))
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }

                lore.add(Component.empty())
                lore.add(
                    Component.text("24h Volume: ", NamedTextColor.GRAY)
                        .append(Component.text("$volume traded", NamedTextColor.AQUA))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("Trend: ", NamedTextColor.GRAY)
                        .append(Component.text(trendLabel(trend), trend.color))
                        .decoration(TextDecoration.ITALIC, false)
                )

                lore.add(Component.empty())
                lore.add(
                    Component.text("Click for details", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )

                meta.lore(lore)
            }
        }
    }

    private fun buildSortButton(label: String, material: Material, active: Boolean): ItemStack {
        val color = if (active) NamedTextColor.GREEN else NamedTextColor.GRAY
        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(label, color)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                if (active) {
                    meta.lore(listOf(
                        Component.text("Currently selected", NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false)
                    ))
                }
            }
        }
    }

    // ── GUI: Item Detail ────────────────────────────────────────────────

    fun openItemDetail(player: Player, material: Material) {
        val title = Component.text(formatMaterialName(material), NamedTextColor.GREEN)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)
        gui.fill(FILLER.clone())
        gui.border(BORDER.clone())

        val multiplier = getMultiplier(material)
        val trend = getTrend(material)
        val baseBuy = getBaseBuyPrice(material)
        val baseSell = getBaseSellPrice(material)
        val currentBuy = getCurrentBuyPrice(material)
        val currentSell = getCurrentSellPrice(material)
        val volume = get24hVolume(material)
        val percentChange = ((multiplier - 1.0) * 100)
        val sign = if (percentChange >= 0) "+" else ""

        // Center item with full info — slot 13
        val infoItem = ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(formatMaterialName(material), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())

                // Base prices
                lore.add(
                    Component.text("Base Buy: ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(if (baseBuy != null) plugin.economyManager.format(baseBuy) else "N/A", NamedTextColor.GRAY))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("Base Sell: ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(if (baseSell != null) plugin.economyManager.format(baseSell) else "N/A", NamedTextColor.GRAY))
                        .decoration(TextDecoration.ITALIC, false)
                )

                lore.add(Component.empty())

                // Current prices
                if (currentBuy != null) {
                    lore.add(
                        Component.text("Buy: ", NamedTextColor.GRAY)
                            .append(Component.text(plugin.economyManager.format(currentBuy), NamedTextColor.GREEN))
                            .append(Component.text(" (${sign}${String.format("%.1f", percentChange)}%)", trend.color))
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
                if (currentSell != null) {
                    lore.add(
                        Component.text("Sell: ", NamedTextColor.GRAY)
                            .append(Component.text(plugin.economyManager.format(currentSell), NamedTextColor.YELLOW))
                            .append(Component.text(" (${sign}${String.format("%.1f", percentChange)}%)", trend.color))
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }

                lore.add(Component.empty())
                lore.add(
                    Component.text("Multiplier: ", NamedTextColor.GRAY)
                        .append(Component.text("${String.format("%.2f", multiplier)}x", NamedTextColor.AQUA))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("24h Volume: ", NamedTextColor.GRAY)
                        .append(Component.text("$volume traded", NamedTextColor.AQUA))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("Trend: ", NamedTextColor.GRAY)
                        .append(Component.text("${trend.symbol} ${trendLabel(trend)}", trend.color))
                        .decoration(TextDecoration.ITALIC, false)
                )

                meta.lore(lore)
            }
        }
        gui.setItem(13, infoItem)

        // Buy buttons — row 1 left side
        if (currentBuy != null) {
            gui.setItem(10, buildActionButton("Buy 1", Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN, currentBuy)) { p, _ ->
                executeBuy(p, material, 1)
            }
            gui.setItem(11, buildActionButton("Buy 16", Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN, currentBuy * 16)) { p, _ ->
                executeBuy(p, material, 16)
            }
            gui.setItem(12, buildActionButton("Buy 64", Material.LIME_STAINED_GLASS_PANE, NamedTextColor.GREEN, currentBuy * 64)) { p, _ ->
                executeBuy(p, material, 64)
            }
        }

        // Sell buttons — row 1 right side
        if (currentSell != null) {
            gui.setItem(14, buildActionButton("Sell 1", Material.ORANGE_STAINED_GLASS_PANE, NamedTextColor.YELLOW, currentSell)) { p, _ ->
                executeSell(p, material, 1)
            }
            gui.setItem(15, buildActionButton("Sell 16", Material.ORANGE_STAINED_GLASS_PANE, NamedTextColor.YELLOW, currentSell * 16)) { p, _ ->
                executeSell(p, material, 16)
            }
            gui.setItem(16, buildActionButton("Sell All", Material.RED_STAINED_GLASS_PANE, NamedTextColor.GOLD, null)) { p, _ ->
                executeSell(p, material, -1)
            }
        }

        // Back button — bottom center
        val backItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Back to Market", NamedTextColor.RED)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
        }
        gui.setItem(22, backItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMarketOverview(p)
        }

        plugin.guiManager.open(player, gui)
    }

    private fun buildActionButton(
        label: String,
        material: Material,
        color: NamedTextColor,
        cost: Double?
    ): ItemStack {
        return ItemStack(material).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(label, color)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                if (cost != null) {
                    meta.lore(listOf(
                        Component.text(plugin.economyManager.format(cost), NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                    ))
                }
            }
        }
    }

    // ── Buy/Sell Execution ──────────────────────────────────────────────

    private fun executeBuy(player: Player, material: Material, amount: Int) {
        val price = getCurrentBuyPrice(material)
        if (price == null) {
            plugin.commsManager.send(player,
                Component.text("This item is not for sale.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        val totalCost = price * amount

        if (!plugin.economyManager.has(player.uniqueId, totalCost)) {
            plugin.commsManager.send(player,
                Component.text("You need ", NamedTextColor.RED)
                    .append(Component.text(plugin.economyManager.format(totalCost), NamedTextColor.GOLD))
                    .append(Component.text(" but only have ", NamedTextColor.RED))
                    .append(Component.text(plugin.economyManager.format(plugin.economyManager.getBalance(player)), NamedTextColor.GOLD))
                    .append(Component.text(".", NamedTextColor.RED)),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        plugin.economyManager.withdraw(player.uniqueId, totalCost)

        val items = ItemStack(material, amount)
        val overflow = player.inventory.addItem(items)
        for (remaining in overflow.values) {
            player.world.dropItemNaturally(player.location, remaining)
        }

        recordTransaction(material, "BUY", amount)

        val name = formatMaterialName(material)
        plugin.commsManager.send(player,
            Component.text("Bought ", NamedTextColor.GREEN)
                .append(Component.text("${amount}x $name", NamedTextColor.WHITE))
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(totalCost), NamedTextColor.GOLD))
                .append(Component.text(".", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)

        // Refresh the detail view
        openItemDetail(player, material)
    }

    private fun executeSell(player: Player, material: Material, amount: Int) {
        val price = getCurrentSellPrice(material)
        if (price == null) {
            plugin.commsManager.send(player,
                Component.text("You cannot sell this item.", NamedTextColor.RED),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        val inventory = player.inventory

        if (amount == -1) {
            // Sell all
            var totalCount = 0
            for (slot in 0 until inventory.size) {
                val stack = inventory.getItem(slot) ?: continue
                if (stack.type == material) {
                    totalCount += stack.amount
                }
            }

            if (totalCount == 0) {
                plugin.commsManager.send(player,
                    Component.text("You don't have any ", NamedTextColor.RED)
                        .append(Component.text(formatMaterialName(material), NamedTextColor.WHITE))
                        .append(Component.text(" to sell.", NamedTextColor.RED)),
                    CommunicationsManager.Category.ECONOMY
                )
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return
            }

            var remaining = totalCount
            for (slot in 0 until inventory.size) {
                if (remaining <= 0) break
                val stack = inventory.getItem(slot) ?: continue
                if (stack.type == material) {
                    val take = remaining.coerceAtMost(stack.amount)
                    stack.amount -= take
                    remaining -= take
                    if (stack.amount <= 0) {
                        inventory.setItem(slot, null)
                    }
                }
            }

            val totalEarned = price * totalCount
            plugin.economyManager.deposit(player.uniqueId, totalEarned)
            recordTransaction(material, "SELL", totalCount)

            plugin.commsManager.send(player,
                Component.text("Sold ", NamedTextColor.YELLOW)
                    .append(Component.text("${totalCount}x ${formatMaterialName(material)}", NamedTextColor.WHITE))
                    .append(Component.text(" for ", NamedTextColor.YELLOW))
                    .append(Component.text(plugin.economyManager.format(totalEarned), NamedTextColor.GOLD))
                    .append(Component.text(".", NamedTextColor.YELLOW)),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.0f)
        } else {
            if (!inventory.contains(material, amount)) {
                plugin.commsManager.send(player,
                    Component.text("You don't have enough ", NamedTextColor.RED)
                        .append(Component.text(formatMaterialName(material), NamedTextColor.WHITE))
                        .append(Component.text(" to sell.", NamedTextColor.RED)),
                    CommunicationsManager.Category.ECONOMY
                )
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return
            }

            var remaining = amount
            for (slot in 0 until inventory.size) {
                if (remaining <= 0) break
                val stack = inventory.getItem(slot) ?: continue
                if (stack.type == material) {
                    val take = remaining.coerceAtMost(stack.amount)
                    stack.amount -= take
                    remaining -= take
                    if (stack.amount <= 0) {
                        inventory.setItem(slot, null)
                    }
                }
            }

            val totalEarned = price * amount
            plugin.economyManager.deposit(player.uniqueId, totalEarned)
            recordTransaction(material, "SELL", amount)

            plugin.commsManager.send(player,
                Component.text("Sold ", NamedTextColor.YELLOW)
                    .append(Component.text("${amount}x ${formatMaterialName(material)}", NamedTextColor.WHITE))
                    .append(Component.text(" for ", NamedTextColor.YELLOW))
                    .append(Component.text(plugin.economyManager.format(totalEarned), NamedTextColor.GOLD))
                    .append(Component.text(".", NamedTextColor.YELLOW)),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.0f)
        }

        // Refresh the detail view
        openItemDetail(player, material)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun trendLabel(trend: Trend): String {
        return when (trend) {
            Trend.UP -> "Rising"
            Trend.DOWN -> "Falling"
            Trend.STABLE -> "Stable"
        }
    }

    private fun formatMaterialName(material: Material): String {
        return plugin.serverShopManager.formatMaterialName(material)
    }

    enum class SortMode {
        VOLUME, PRICE, TREND
    }
}
