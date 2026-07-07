package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Base64

/**
 * "/cshop" — a shop that only accepts Credits (see [CreditsManager]). Unlike
 * [ServerShopManager], which is driven by a static material catalog in
 * shop.yml, admins stock this shop item-by-item at runtime via /cshop
 * additem/removeitem/category, so listings preserve the exact ItemStack
 * (custom name, lore, enchants, PDC tags, etc.) that was in their hand.
 */
class CreditShopManager(private val plugin: Joshymc) {

    data class CreditShopCategory(val id: String, val name: String, val icon: Material)
    data class CreditShopItem(val id: Int, val categoryId: String, val item: ItemStack, val price: Double)

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val BORDER = ItemStack(Material.PURPLE_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    private val ITEMS_PER_PAGE = 28 // rows 1-4, columns 1-7

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS credit_shop_categories (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                icon TEXT NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS credit_shop_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category_id TEXT NOT NULL,
                item TEXT NOT NULL,
                price REAL NOT NULL,
                added_at INTEGER NOT NULL
            )
        """.trimIndent())
    }

    // ── Admin management ─────────────────────────────────────────────────

    fun createCategory(name: String, icon: Material): CreditShopCategory? {
        val id = slugify(name)
        if (getCategory(id) != null) return null
        plugin.databaseManager.execute(
            "INSERT INTO credit_shop_categories (id, name, icon) VALUES (?, ?, ?)",
            id, name, icon.name
        )
        return CreditShopCategory(id, name, icon)
    }

    fun addItem(categoryId: String, item: ItemStack, price: Double): Int {
        plugin.databaseManager.execute(
            "INSERT INTO credit_shop_items (category_id, item, price, added_at) VALUES (?, ?, ?, ?)",
            categoryId, serializeItem(item), price, System.currentTimeMillis()
        )
        return plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { rs -> rs.getInt("id") } ?: -1
    }

    fun removeItem(id: Int): Boolean {
        return plugin.databaseManager.executeUpdate("DELETE FROM credit_shop_items WHERE id = ?", id) > 0
    }

    fun getCategories(): List<CreditShopCategory> {
        return plugin.databaseManager.query("SELECT * FROM credit_shop_categories ORDER BY name") { mapCategory(it) }
    }

    fun getCategory(id: String): CreditShopCategory? {
        return plugin.databaseManager.queryFirst("SELECT * FROM credit_shop_categories WHERE id = ?", id) { mapCategory(it) }
    }

    fun getItem(id: Int): CreditShopItem? {
        return plugin.databaseManager.queryFirst("SELECT * FROM credit_shop_items WHERE id = ?", id) { mapItem(it) }
    }

    fun getItemsForCategory(categoryId: String): List<CreditShopItem> {
        return plugin.databaseManager.query(
            "SELECT * FROM credit_shop_items WHERE category_id = ? ORDER BY id", categoryId
        ) { mapItem(it) }
    }

    fun slugify(name: String): String {
        val slug = name.lowercase().trim().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        return slug.ifEmpty { "category" }
    }

    // ── Serialization / row mapping ──────────────────────────────────────

    private fun serializeItem(item: ItemStack): String {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes())
    }

    private fun deserializeItem(base64: String): ItemStack {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))
    }

    private fun mapCategory(rs: java.sql.ResultSet): CreditShopCategory {
        return CreditShopCategory(
            id = rs.getString("id"),
            name = rs.getString("name"),
            icon = Material.matchMaterial(rs.getString("icon")) ?: Material.CHEST
        )
    }

    private fun mapItem(rs: java.sql.ResultSet): CreditShopItem {
        return CreditShopItem(
            id = rs.getInt("id"),
            categoryId = rs.getString("category_id"),
            item = deserializeItem(rs.getString("item")),
            price = rs.getDouble("price")
        )
    }

    // ── Main Menu ─────────────────────────────────────────────────────────

    fun openMainMenu(player: Player) {
        val categories = getCategories()

        val title = Component.text("Credits Shop", NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)
        gui.fill(FILLER.clone())
        gui.border(BORDER.clone())

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
                        Component.text(category.name, NamedTextColor.LIGHT_PURPLE)
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("${getItemsForCategory(category.id).size} items", NamedTextColor.GRAY)
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

        if (categories.isEmpty()) {
            val empty = ItemStack(Material.BARRIER).apply {
                editMeta { meta ->
                    meta.displayName(Component.text("No categories yet", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                }
            }
            gui.setItem(13, empty)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Category Page ────────────────────────────────────────────────────

    fun openCategory(player: Player, categoryId: String, page: Int) {
        val category = getCategory(categoryId) ?: return
        val items = getItemsForCategory(categoryId)

        val title = Component.text(category.name, NamedTextColor.LIGHT_PURPLE)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 54)
        gui.fill(FILLER.clone())

        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 45..53) gui.inventory.setItem(i, BORDER.clone())

        val totalPages = ((items.size - 1) / ITEMS_PER_PAGE).coerceAtLeast(0)
        val startIndex = page * ITEMS_PER_PAGE
        val endIndex = (startIndex + ITEMS_PER_PAGE).coerceAtMost(items.size)
        val pageItems = if (startIndex < items.size) items.subList(startIndex, endIndex) else emptyList()

        val itemSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                itemSlots.add(row * 9 + col)
            }
        }

        for ((index, shopItem) in pageItems.withIndex()) {
            val slot = itemSlots[index]
            val icon = buildShopItemIcon(shopItem, category, player)

            gui.setItem(slot, icon) { p, event ->
                val amount = if (event.click.isShiftClick) shopItem.item.maxStackSize else 1
                purchase(p, shopItem, amount)
                openCategory(p, categoryId, page)
            }
        }

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

    private fun buildShopItemIcon(shopItem: CreditShopItem, category: CreditShopCategory, viewer: Player): ItemStack {
        val icon = shopItem.item.clone()
        icon.amount = 1
        icon.editMeta { meta ->
            val lore = mutableListOf<Component>()
            val existingLore = meta.lore()
            if (!existingLore.isNullOrEmpty()) {
                lore.addAll(existingLore)
                lore.add(Component.empty())
            }

            lore.add(
                plugin.commsManager.parseLegacy("&7Price: &b${plugin.creditsManager.format(shopItem.price)} credits")
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                Component.text("Category: ${category.name}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (viewer.hasPermission("joshymc.cshop.admin")) {
                lore.add(
                    Component.text("ID: ${shopItem.id}", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            lore.add(Component.empty())
            lore.add(
                Component.text("Click to buy 1", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (shopItem.item.maxStackSize > 1) {
                lore.add(
                    Component.text("Shift-click to buy a stack (${shopItem.item.maxStackSize})", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }

            meta.lore(lore)
        }
        return icon
    }

    // ── Purchase Logic ───────────────────────────────────────────────────

    private fun purchase(player: Player, shopItem: CreditShopItem, amount: Int) {
        val totalCost = shopItem.price * amount

        if (plugin.creditsManager.getBalance(player) < totalCost) {
            plugin.commsManager.send(player,
                Component.text("You need ", NamedTextColor.RED)
                    .append(Component.text("${plugin.creditsManager.format(totalCost)} credits", NamedTextColor.AQUA))
                    .append(Component.text(" but only have ", NamedTextColor.RED))
                    .append(Component.text("${plugin.creditsManager.format(plugin.creditsManager.getBalance(player))} credits", NamedTextColor.AQUA))
                    .append(Component.text(".", NamedTextColor.RED)),
                CommunicationsManager.Category.ECONOMY
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        if (!plugin.creditsManager.withdraw(player.uniqueId, totalCost)) return

        val give = shopItem.item.clone()
        give.amount = amount
        val overflow = player.inventory.addItem(give)
        for (remaining in overflow.values) {
            player.world.dropItemNaturally(player.location, remaining)
        }

        plugin.commsManager.send(player,
            Component.text("Bought ", NamedTextColor.GREEN)
                .append(Component.text("${amount}x ", NamedTextColor.WHITE))
                .append(shopItem.item.displayName())
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text("${plugin.creditsManager.format(totalCost)} credits", NamedTextColor.AQUA))
                .append(Component.text(".", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun centerInRow(count: Int, rowSlots: List<Int>): List<Int> {
        if (count >= rowSlots.size) return rowSlots.take(count)
        val offset = (rowSlots.size - count) / 2
        return rowSlots.subList(offset, offset + count)
    }
}
