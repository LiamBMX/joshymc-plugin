package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.listener.CustomArmorListener
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
import java.util.UUID

class ServerShopManager(private val plugin: Joshymc) {

    data class ShopItem(val material: Material, val buyPrice: Double, val sellPrice: Double)
    data class ShopCategory(val id: String, val name: String, val icon: Material, val items: List<ShopItem>)

    // shop.yml (buy catalog, browsed/bought from the /shop GUI) and shop_sell.yml
    // (sell catalog, the only source /sell and other sellers look at) are kept as
    // fully separate category lists so the two never shadow each other.
    private val buyCategories = mutableListOf<ShopCategory>()
    private val sellCategories = mutableListOf<ShopCategory>()

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val ITEMS_PER_PAGE = 28 // rows 1-4, columns 1-7

    fun start() {
        buyCategories.clear()
        sellCategories.clear()

        // shop.yml is the buy catalog for the /shop GUI (buying only - the buy
        // shop no longer sells items). shop_sell.yml is the ONLY source /sell
        // (and the sell wand / auto-sellers) look at to decide what's sellable
        // and for how much. The two files are kept fully separate so neither
        // shadows the other.
        loadCategoriesInto(buyCategories, "shop.yml")
        loadCategoriesInto(sellCategories, "shop_sell.yml")

        plugin.logger.info(
            "Loaded ${buyCategories.size} buy categories (${buyCategories.sumOf { it.items.size }} items) " +
                "and ${sellCategories.size} sell categories (${sellCategories.sumOf { it.items.size }} items)"
        )
    }

    private fun loadCategoriesInto(target: MutableList<ShopCategory>, fileName: String) {
        val file = plugin.configFile(fileName)
        if (!file.exists()) {
            plugin.saveResource(fileName, false)
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

            val existing = target.indexOfFirst { it.id == categoryId }
            if (existing >= 0) {
                val merged = target[existing]
                target[existing] = merged.copy(items = merged.items + items)
            } else {
                target.add(ShopCategory(categoryId, name, icon, items))
            }
        }
    }

    /** Buy-shop categories (shop.yml) - used to browse/buy in the /shop GUI. */
    fun getCategories(): List<ShopCategory> = buyCategories.toList()

    fun getCategory(id: String): ShopCategory? = buyCategories.find { it.id == id }

    /** Sell-shop categories (shop_sell.yml) - the only source of truth for what's sellable. */
    fun getCategoryIdForMaterial(material: Material): String? {
        for (category in sellCategories) {
            if (category.items.any { it.material == material && it.sellPrice > 0 }) return category.id
        }
        return null
    }

    fun getSellPrice(material: Material): Double? {
        for (category in sellCategories) {
            val item = category.items.find { it.material == material && it.sellPrice > 0 }
            if (item != null) {
                return item.sellPrice * plugin.boosterManager.getSellMultiplier(material)
            }
        }
        return null
    }

    fun getBaseSellPrice(material: Material): Double? {
        for (category in sellCategories) {
            val item = category.items.find { it.material == material && it.sellPrice > 0 }
            if (item != null) return item.sellPrice
        }
        return null
    }

    /** Returns the sell price with the Flower Armor 1.2x crop bonus applied if applicable. */
    fun applyCropBonus(price: Double, material: Material, playerUuid: UUID): Double {
        return if (material in CustomArmorListener.FLOWER_CROP_MATERIALS &&
                   CustomArmorListener.hasFlowerSetBonus(playerUuid)) {
            price * 1.2
        } else {
            price
        }
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

        val centered = centerInRow(buyCategories.size, availableSlots)

        for ((index, slot) in centered.withIndex()) {
            val category = buyCategories[index]
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

                // Buy price
                if (shopItem.buyPrice > 0) {
                    lore.add(
                        plugin.commsManager.parseLegacy("&7Buy: &a${plugin.economyManager.format(shopItem.buyPrice)}")
                            .decoration(TextDecoration.ITALIC, false)
                    )
                } else {
                    lore.add(Component.text("Not for sale", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                }

                lore.add(Component.empty())

                // Action hints - the buy shop is buy-only, use /sell to sell items
                if (shopItem.buyPrice > 0) {
                    lore.add(
                        Component.text("Left-click to choose buy amount", NamedTextColor.GREEN)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                }

                meta.lore(lore)
            }
        }
    }

    // ── Click Handler ───────────────────────────────────────────────────

    private fun handleItemClick(player: Player, shopItem: ShopItem, clickType: ClickType) {
        val noBuy = { plugin.commsManager.send(player, Component.text("This item is not for sale.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY); player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f) }

        // The buy shop is buy-only - selling happens through /sell instead.
        when (clickType) {
            ClickType.LEFT, ClickType.SHIFT_LEFT ->
                if (shopItem.buyPrice > 0) openBuyQuantityGui(player, shopItem, shopItem.buyPrice) else noBuy()
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
            if (buyItem(p, shopItem.material, shopItem.buyPrice, amount.coerceIn(1, MAX_BUY))) {
                openMainMenu(p)
            }
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

    private fun buyItem(player: Player, material: Material, buyPrice: Double, amount: Int): Boolean {
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
            return false
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
        return true
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
