package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.persistence.PersistentDataType
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import java.io.File

class ServerShopManager(private val plugin: Joshymc) {

    data class ShopItem(val material: Material, val buyPrice: Double, val sellPrice: Double)
    data class ShopCategory(val id: String, val name: String, val icon: Material, val items: List<ShopItem>)

    private val categories = mutableListOf<ShopCategory>()

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val ITEMS_PER_PAGE = 28 // rows 1-4, columns 1-7

    fun start() {
        categories.clear()

        val file = plugin.configFile("shop.yml")
        if (!file.exists()) {
            plugin.saveResource("shop.yml", false)
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val categoriesSection = config.getConfigurationSection("categories") ?: return

        for (categoryId in categoriesSection.getKeys(false)) {
            val section = categoriesSection.getConfigurationSection(categoryId) ?: continue
            val name = section.getString("name") ?: categoryId
            val iconName = section.getString("icon") ?: "CHEST"
            val icon = Material.matchMaterial(iconName) ?: Material.CHEST

            val items = mutableListOf<ShopItem>()
            val itemsSection = section.getConfigurationSection("items") ?: continue

            for (materialName in itemsSection.getKeys(false)) {
                val material = Material.matchMaterial(materialName) ?: continue
                val itemSection = itemsSection.getConfigurationSection(materialName) ?: continue
                val buyPrice = itemSection.getDouble("buy", 0.0)
                val sellPrice = itemSection.getDouble("sell", 0.0)
                items.add(ShopItem(material, buyPrice, sellPrice))
            }

            categories.add(ShopCategory(categoryId, name, icon, items))
        }

        plugin.logger.info("Loaded ${categories.size} shop categories with ${categories.sumOf { it.items.size }} items")
    }

    fun getCategories(): List<ShopCategory> = categories.toList()

    fun getCategory(id: String): ShopCategory? = categories.find { it.id == id }

    fun getSellPrice(material: Material): Double? {
        for (category in categories) {
            val item = category.items.find { it.material == material }
            if (item != null && item.sellPrice > 0) {
                // Apply market multiplier for dynamic pricing
                val multiplier = plugin.marketManager.getMultiplier(material)
                return item.sellPrice * multiplier
            }
        }
        return null
    }

    fun getBaseSellPrice(material: Material): Double? {
        for (category in categories) {
            val item = category.items.find { it.material == material }
            if (item != null && item.sellPrice > 0) return item.sellPrice
        }
        return null
    }

    // ── Main Menu ───────────────────────────────────────────────────────

    fun openMainMenu(player: Player) {
        val title = Component.text("Server Shop", NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)
        gui.fill(FILLER.clone())
        gui.border(BORDER.clone())

        // Place category icons in the middle area (row 1, columns 1-7)
        val availableSlots = mutableListOf<Int>()
        for (col in 1..7) {
            availableSlots.add(9 + col) // row 1
        }

        val centered = centerInRow(categories.size, availableSlots)

        for ((index, slot) in centered.withIndex()) {
            val category = categories[index]
            val icon = ItemStack(category.icon).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text(category.name, NamedTextColor.AQUA)
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("${category.items.size} items", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Click to browse", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    ))
                }
            }

            gui.setItem(slot, icon) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openCategory(p, category.id, 0)
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Category Page ───────────────────────────────────────────────────

    fun openCategory(player: Player, categoryId: String, page: Int) {
        val category = getCategory(categoryId) ?: return

        val title = Component.text(category.name, NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 54)

        // Fill everything with filler
        gui.fill(FILLER.clone())

        // Top row border
        for (i in 0..8) {
            gui.inventory.setItem(i, BORDER.clone())
        }

        // Bottom row border
        for (i in 45..53) {
            gui.inventory.setItem(i, BORDER.clone())
        }

        // Calculate pagination
        val totalPages = ((category.items.size - 1) / ITEMS_PER_PAGE).coerceAtLeast(0)
        val startIndex = page * ITEMS_PER_PAGE
        val endIndex = (startIndex + ITEMS_PER_PAGE).coerceAtMost(category.items.size)
        val pageItems = if (startIndex < category.items.size) category.items.subList(startIndex, endIndex) else emptyList()

        // Item slots: rows 1-4, columns 1-7
        val itemSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                itemSlots.add(row * 9 + col)
            }
        }

        for ((index, shopItem) in pageItems.withIndex()) {
            val slot = itemSlots[index]
            val icon = buildShopItemIcon(shopItem)

            gui.setItem(slot, icon) { p, event ->
                handleItemClick(p, shopItem, event.click)
            }
        }

        // Bottom navigation

        // Back button - slot 49 (center)
        val backItem = ItemStack(Material.BARRIER).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text("Back to Categories", NamedTextColor.RED)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
        }
        gui.setItem(49, backItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openMainMenu(p)
        }

        // Previous page - slot 46
        if (page > 0) {
            val prevItem = ItemStack(Material.ARROW).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text("Previous Page", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true)
                    )
                }
            }
            gui.setItem(46, prevItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openCategory(p, categoryId, page - 1)
            }
        }

        // Next page - slot 52
        if (page < totalPages) {
            val nextItem = ItemStack(Material.ARROW).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text("Next Page", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true)
                    )
                }
            }
            gui.setItem(52, nextItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openCategory(p, categoryId, page + 1)
            }
        }

        plugin.guiManager.open(player, gui)
    }

    // ── Item Icon Builder ───────────────────────────────────────────────

    private fun buildShopItemIcon(shopItem: ShopItem): ItemStack {
        return ItemStack(shopItem.material).apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(formatMaterialName(shopItem.material), NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )

                val lore = mutableListOf<Component>()

                // Dynamic prices from market
                val multiplier = plugin.marketManager.getMultiplier(shopItem.material)
                val liveBuy = if (shopItem.buyPrice > 0) shopItem.buyPrice * multiplier else 0.0
                val liveSell = if (shopItem.sellPrice > 0) shopItem.sellPrice * multiplier else 0.0
                val pctChange = ((multiplier - 1.0) * 100).toInt()
                val trendText = when {
                    pctChange > 2 -> " &a(+$pctChange%)"
                    pctChange < -2 -> " &c($pctChange%)"
                    else -> ""
                }

                // Buy price
                if (liveBuy > 0) {
                    lore.add(
                        plugin.commsManager.parseLegacy("&7Buy: &a${plugin.economyManager.format(liveBuy)}$trendText")
                            .decoration(TextDecoration.ITALIC, false)
                    )
                } else {
                    lore.add(Component.text("Not for sale", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                }

                // Sell price
                if (liveSell > 0) {
                    lore.add(
                        plugin.commsManager.parseLegacy("&7Sell: &e${plugin.economyManager.format(liveSell)}$trendText")
                            .decoration(TextDecoration.ITALIC, false)
                    )
                } else {
                    lore.add(Component.text("Cannot sell", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                }

                lore.add(Component.empty())

                // Action hints
                if (shopItem.buyPrice > 0) {
                    lore.add(
                        Component.text("Left-click to choose buy amount", NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }
                if (shopItem.sellPrice > 0) {
                    lore.add(
                        Component.text("Right-click to sell 1", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    lore.add(
                        Component.text("Shift+right to sell all", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }

                meta.lore(lore)
            }
        }
    }

    // ── Click Handler ───────────────────────────────────────────────────

    private fun handleItemClick(player: Player, shopItem: ShopItem, clickType: ClickType) {
        val multiplier = plugin.marketManager.getMultiplier(shopItem.material)
        val liveBuy = shopItem.buyPrice * multiplier
        val liveSell = shopItem.sellPrice * multiplier

        val noSell = { plugin.commsManager.send(player, Component.text("You cannot sell this item.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY); player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f) }
        val noBuy = { plugin.commsManager.send(player, Component.text("This item is not for sale.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY); player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f) }

        when (clickType) {
            ClickType.LEFT, ClickType.SHIFT_LEFT ->
                if (liveBuy > 0) openBuyQuantityGui(player, shopItem, liveBuy) else noBuy()
            ClickType.RIGHT -> if (liveSell > 0) sellItem(player, shopItem.material, liveSell, 1) else noSell()
            ClickType.SHIFT_RIGHT -> if (liveSell > 0) sellItem(player, shopItem.material, liveSell, -1) else noSell()
            else -> {}
        }
    }

    // ── Buy Quantity GUI ────────────────────────────────────────────────
    //
    // 27-slot GUI letting players pick how many to buy (1..640, i.e. up to 10 stacks). Layout:
    //   row 0:        gray border
    //   row 1: R64 R16 R8 R-1 ITEM G+1 G+8 G+16 G+64
    //   row 2:        confirm at slot 22
    // Confirm charges live price * amount and gives that many items.

    private val MAX_BUY = 640

    private fun openBuyQuantityGui(player: Player, shopItem: ShopItem, livePrice: Double) {
        var amount = 1

        val gui = CustomGui(
            Component.text("Buy ${formatMaterialName(shopItem.material)}", NamedTextColor.DARK_GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            27
        )

        // Fill background
        for (i in 0 until 27) gui.inventory.setItem(i, BORDER.clone())

        fun renderDynamic() {
            val total = livePrice * amount
            val itemDisplay = ItemStack(shopItem.material, amount.coerceIn(1, 64))
            itemDisplay.editMeta { meta ->
                meta.displayName(
                    Component.text(formatMaterialName(shopItem.material), NamedTextColor.WHITE)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("Buying: ", NamedTextColor.GRAY)
                        .append(Component.text("$amount", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true))
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("Unit price: ", NamedTextColor.GRAY)
                        .append(Component.text(plugin.economyManager.format(livePrice), NamedTextColor.GOLD))
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("Total: ", NamedTextColor.GRAY)
                        .append(Component.text(plugin.economyManager.format(total), NamedTextColor.GOLD))
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                ))
            }
            gui.inventory.setItem(13, itemDisplay)

            val confirm = ItemStack(Material.LIME_CONCRETE)
            confirm.editMeta { meta ->
                meta.displayName(
                    Component.text("Confirm Purchase", NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(listOf(
                    Component.text("Buy ", NamedTextColor.GRAY)
                        .append(Component.text("$amount", NamedTextColor.WHITE))
                        .append(Component.text(" for ", NamedTextColor.GRAY))
                        .append(Component.text(plugin.economyManager.format(livePrice * amount), NamedTextColor.GOLD))
                        .decoration(TextDecoration.ITALIC, false),
                ))
            }
            gui.inventory.setItem(22, confirm)
        }

        // Decrement panes (left of the item display, slot 9 = -64 stack button)
        for ((slot, delta) in listOf(9 to -64, 10 to -16, 11 to -8, 12 to -1)) {
            gui.setItem(slot, decBtn(delta)) { p, _ ->
                amount = (amount + delta).coerceIn(1, MAX_BUY)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.4f, 0.8f)
                renderDynamic()
            }
        }
        // Increment panes (right of the item display, slot 17 = +64 stack button)
        for ((slot, delta) in listOf(14 to 1, 15 to 8, 16 to 16, 17 to 64)) {
            gui.setItem(slot, incBtn(delta)) { p, _ ->
                amount = (amount + delta).coerceIn(1, MAX_BUY)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f)
                renderDynamic()
            }
        }
        // Confirm button — handler is bound here; renderDynamic() overwrites
        // the visual on each click but the bound handler persists.
        gui.setItem(22, ItemStack(Material.LIME_CONCRETE)) { p, _ ->
            p.closeInventory()
            // Re-fetch the live price on confirm so a market price tick
            // mid-GUI doesn't let the player lock in a stale rate.
            val currentPrice = shopItem.buyPrice * plugin.marketManager.getMultiplier(shopItem.material)
            buyItem(p, shopItem.material, currentPrice, amount.coerceIn(1, MAX_BUY))
        }

        renderDynamic()
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun decBtn(delta: Int): ItemStack {
        val item = ItemStack(Material.RED_STAINED_GLASS_PANE)
        item.editMeta { meta ->
            meta.displayName(
                Component.text("$delta", NamedTextColor.RED)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
        }
        return item
    }

    private fun incBtn(delta: Int): ItemStack {
        val item = ItemStack(Material.LIME_STAINED_GLASS_PANE)
        item.editMeta { meta ->
            meta.displayName(
                Component.text("+$delta", NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
        }
        return item
    }

    // ── Buy Logic ───────────────────────────────────────────────────────

    private fun buyItem(player: Player, material: Material, buyPrice: Double, amount: Int) {
        val totalCost = buyPrice * amount

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

        // Drop any items that didn't fit; tag them so quest progress is not counted
        for (remaining in overflow.values) {
            val dropped = player.world.dropItemNaturally(player.location, remaining)
            dropped.persistentDataContainer.set(plugin.questManager.shopDropKey, PersistentDataType.BYTE, 1)
        }

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

        // Record transaction for market price fluctuation
        plugin.marketManager.recordTransaction(material, "BUY", amount)
    }

    // ── Sell Logic ──────────────────────────────────────────────────────

    fun sellItem(player: Player, material: Material, sellPrice: Double, amount: Int) {
        val inventory = player.inventory

        if (amount == -1) {
            // Sell all of that material
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

            // Remove all of that material
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

            val totalEarned = sellPrice * totalCount
            plugin.economyManager.deposit(player.uniqueId, totalEarned)

            plugin.commsManager.send(player,
                Component.text("Sold ", NamedTextColor.YELLOW)
                    .append(Component.text("${totalCount}x ${formatMaterialName(material)}", NamedTextColor.WHITE))
                    .append(Component.text(" for ", NamedTextColor.YELLOW))
                    .append(Component.text(plugin.economyManager.format(totalEarned), NamedTextColor.GOLD))
                    .append(Component.text(".", NamedTextColor.YELLOW)),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.0f)
            plugin.marketManager.recordTransaction(material, "SELL", totalCount)
        } else {
            // Sell specific amount
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

            // Remove the items
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

            val totalEarned = sellPrice * amount
            plugin.economyManager.deposit(player.uniqueId, totalEarned)
            plugin.marketManager.recordTransaction(material, "SELL", amount)

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
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    fun formatMaterialName(material: Material): String {
        return material.name.lowercase().split('_').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    private fun centerInRow(count: Int, rowSlots: List<Int>): List<Int> {
        if (count >= rowSlots.size) return rowSlots.take(count)
        val offset = (rowSlots.size - count) / 2
        return rowSlots.subList(offset, offset + count)
    }
}
