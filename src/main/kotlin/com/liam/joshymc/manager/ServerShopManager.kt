package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.listener.CustomArmorListener
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.potion.PotionType
import java.io.File
import java.util.UUID

class ServerShopManager(private val plugin: Joshymc) {

    enum class ShopItemKind { MATERIAL, POTION, SPAWNER }

    data class ShopItem(
        val material: Material,
        val buyPrice: Double,
        val sellPrice: Double,
        val kind: ShopItemKind = ShopItemKind.MATERIAL,
        val displayName: String? = null,
        val potionEffect: PotionEffectType? = null,
        val potionAmplifier: Int = 0,
        val spawnerTypeId: String? = null
    )
    data class ShopCategory(val id: String, val name: String, val icon: Material, val items: List<ShopItem>)

    // shop.yml is the single buy/sell catalog browsed from the /shop GUI. Every
    // item carries both a "buy" and a "sell" price.
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

        mergeMissingCategoriesFromDefaults("shop.yml")
        loadCategoriesInto(categories, "shop.yml")

        plugin.logger.info("Loaded ${categories.size} shop categories with ${categories.sumOf { it.items.size }} items")
    }

    /**
     * Merge any top-level categories that exist in the bundled `shop.yml` resource but are
     * missing from the user's saved file (e.g. a new "spawners" category shipped in an
     * update). Existing categories/items (with admin tweaks) are left untouched — same
     * pattern as SpawnerManager.mergeMissingFromDefaults.
     */
    private fun mergeMissingCategoriesFromDefaults(fileName: String) {
        val file = plugin.configFile(fileName)
        if (!file.exists()) {
            plugin.saveResource(fileName, false)
            return
        }

        val defaultStream = plugin.getResource(fileName) ?: return
        val defaults = YamlConfiguration.loadConfiguration(defaultStream.bufferedReader())
        val userCfg = YamlConfiguration.loadConfiguration(file)

        val defaultsSection = defaults.getConfigurationSection("categories") ?: return
        val userSection = userCfg.getConfigurationSection("categories") ?: userCfg.createSection("categories")

        var added = 0
        for (categoryId in defaultsSection.getKeys(false)) {
            if (userSection.contains(categoryId)) continue
            userSection.set(categoryId, defaultsSection.get(categoryId))
            added++
        }
        if (added > 0) {
            try {
                userCfg.save(file)
                plugin.logger.info("[Shop] Merged $added new shop categor${if (added == 1) "y" else "ies"} from bundled defaults.")
            } catch (e: Exception) {
                plugin.logger.warning("[Shop] Failed to save merged shop.yml: ${e.message}")
            }
        }
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
            if (!section.getBoolean("enabled", true)) continue
            val name = section.getString("name") ?: categoryId
            val iconName = section.getString("icon") ?: "CHEST"
            val icon = Material.matchMaterial(iconName) ?: Material.CHEST

            val items = mutableListOf<ShopItem>()
            val itemsSection = section.getConfigurationSection("items") ?: continue

            for (key in itemsSection.getKeys(false)) {
                val itemSection = itemsSection.getConfigurationSection(key) ?: continue
                if (!itemSection.getBoolean("enabled", true)) continue

                // Most entries key directly off the Material name (e.g. "WHEAT:"). Entries that
                // need a custom id (multiple potions sharing Material.SPLASH_POTION, or any
                // number of spawner entries sharing Material.SPAWNER) instead specify an
                // explicit "material:" field (spawner entries default it to SPAWNER).
                val spawnerTypeId = itemSection.getString("spawner")
                val material = if (spawnerTypeId != null) {
                    Material.SPAWNER
                } else {
                    Material.matchMaterial(itemSection.getString("material") ?: key) ?: continue
                }
                val buyPrice = itemSection.getDouble("buy", 0.0)
                val sellPrice = itemSection.getDouble("sell", 0.0)
                val displayName = itemSection.getString("name")

                val effectName = itemSection.getString("effect")
                when {
                    spawnerTypeId != null ->
                        items.add(ShopItem(material, buyPrice, sellPrice, ShopItemKind.SPAWNER, displayName, spawnerTypeId = spawnerTypeId))
                    effectName != null -> {
                        val effect = Registry.EFFECT.get(NamespacedKey.minecraft(effectName.lowercase())) ?: continue
                        val amplifier = itemSection.getInt("amplifier", 0)
                        items.add(ShopItem(material, buyPrice, sellPrice, ShopItemKind.POTION, displayName, effect, amplifier))
                    }
                    else -> items.add(ShopItem(material, buyPrice, sellPrice, ShopItemKind.MATERIAL, displayName))
                }
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

    fun getCategories(): List<ShopCategory> = categories.toList()

    fun getCategory(id: String): ShopCategory? = categories.find { it.id == id }

    fun getCategoryIdForMaterial(material: Material): String? {
        for (category in categories) {
            if (category.items.any { it.material == material && it.sellPrice > 0 }) return category.id
        }
        return null
    }

    fun getSellPrice(material: Material): Double? {
        val base = getBaseSellPrice(material) ?: return null
        return base * plugin.boosterManager.getSellMultiplier(material)
    }

    fun getBaseSellPrice(material: Material): Double? {
        for (category in categories) {
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

    private data class MenuButton(val name: String, val icon: Material, val itemCount: Int, val onClick: (Player) -> Unit)

    /** Category buttons are driven entirely by shop.yml, including the Spawners category. */
    private fun buildMenuButtons(): List<MenuButton> {
        return categories.map { category ->
            MenuButton(category.name, category.icon, category.items.size) { p -> openCategory(p, category.id, 0) }
        }
    }

    fun openMainMenu(player: Player) {
        val title = Component.text("Server Shop", NamedTextColor.AQUA)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)
        gui.fill(FILLER.clone())
        gui.border(BORDER.clone())

        val buttons = buildMenuButtons()

        // Place category icons in the middle area (row 1, columns 1-7)
        val availableSlots = mutableListOf<Int>()
        for (col in 1..7) {
            availableSlots.add(9 + col) // row 1
        }

        val centered = centerInRow(buttons.size, availableSlots)

        for ((index, slot) in centered.withIndex()) {
            val button = buttons[index]
            val icon = ItemStack(button.icon).apply {
                editMeta { meta ->
                    meta.displayName(
                        Component.text(button.name, NamedTextColor.AQUA)
                            .decoration(TextDecoration.BOLD, true)
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("${button.itemCount} items", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty(),
                        Component.text("Click to browse", NamedTextColor.YELLOW)
                            .decoration(TextDecoration.ITALIC, false)
                    ))
                }
            }

            gui.setItem(slot, icon) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                button.onClick(p)
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

    // ── Potion Items ─────────────────────────────────────────────────────
    //
    // Some shop entries (e.g. "Splash Potion of Strength II") have no vanilla brewing
    // recipe. We build them directly against PotionMeta: base potion type WATER (so the
    // client doesn't show a stale "no effects" tooltip) plus a single custom effect.

    private fun buildPotionItem(shopItem: ShopItem): ItemStack {
        val effect = shopItem.potionEffect ?: return ItemStack(shopItem.material)
        // Instant effects (Instant Health/Damage) apply immediately; duration is irrelevant.
        // Everything else uses vanilla's tier-II duration (1:30) to match player expectations.
        val durationTicks = when (effect) {
            PotionEffectType.INSTANT_HEALTH, PotionEffectType.INSTANT_DAMAGE -> 1
            else -> 1800
        }

        val item = ItemStack(shopItem.material)
        item.editMeta { meta ->
            val potionMeta = meta as PotionMeta
            potionMeta.basePotionType = PotionType.WATER
            potionMeta.addCustomEffect(PotionEffect(effect, durationTicks, shopItem.potionAmplifier), true)
        }
        return item
    }

    private fun displayLabel(shopItem: ShopItem): String = shopItem.displayName ?: formatMaterialName(shopItem.material)

    // ── Item Icon Builder ───────────────────────────────────────────────

    /**
     * Spawner entries reuse SpawnerManager.createSpawnerItem so the delivered item is
     * identical (mob type, PDC tag, drop-table lore) to a spawner bought via /spawner —
     * there is no separate "shop spawner" format that could ever preserve the wrong mob.
     */
    private fun buildSpawnerIcon(shopItem: ShopItem): ItemStack {
        val typeId = shopItem.spawnerTypeId ?: return ItemStack(Material.BARRIER)
        val item = plugin.spawnerManager.createSpawnerItem(typeId) ?: return ItemStack(Material.BARRIER)
        item.editMeta { meta ->
            val lore = (meta.lore() ?: emptyList()).toMutableList()
            if (shopItem.buyPrice > 0) {
                lore.add(
                    plugin.commsManager.parseLegacy("&7Buy: &a${plugin.economyManager.format(shopItem.buyPrice)}")
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(Component.empty())
                lore.add(
                    Component.text("Left-click to choose buy amount", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                )
            } else {
                lore.add(Component.text("Not for sale", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
        }
        return item
    }

    private fun buildShopItemIcon(shopItem: ShopItem): ItemStack {
        if (shopItem.kind == ShopItemKind.SPAWNER) return buildSpawnerIcon(shopItem)
        val base = if (shopItem.kind == ShopItemKind.POTION) buildPotionItem(shopItem) else ItemStack(shopItem.material)
        return base.apply {
            editMeta { meta ->
                meta.displayName(
                    Component.text(displayLabel(shopItem), NamedTextColor.WHITE)
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

                // Sell price
                if (shopItem.sellPrice > 0) {
                    lore.add(
                        plugin.commsManager.parseLegacy("&7Sell: &e${plugin.economyManager.format(shopItem.sellPrice)}")
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
        val noSell = { plugin.commsManager.send(player, Component.text("You cannot sell this item.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY); player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f) }
        val noBuy = { plugin.commsManager.send(player, Component.text("This item is not for sale.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY); player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f) }

        when (clickType) {
            ClickType.LEFT, ClickType.SHIFT_LEFT ->
                if (shopItem.buyPrice > 0) openBuyQuantityGui(player, shopItem) else noBuy()
            ClickType.RIGHT -> if (shopItem.sellPrice > 0) sellItem(player, shopItem.material, applyCropBonus(shopItem.sellPrice, shopItem.material, player.uniqueId), 1) else noSell()
            ClickType.SHIFT_RIGHT -> if (shopItem.sellPrice > 0) sellItem(player, shopItem.material, applyCropBonus(shopItem.sellPrice, shopItem.material, player.uniqueId), -1) else noSell()
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

    private fun openBuyQuantityGui(player: Player, shopItem: ShopItem) {
        var amount = 1
        val livePrice = shopItem.buyPrice

        val gui = CustomGui(
            Component.text("Buy ${displayLabel(shopItem)}", NamedTextColor.DARK_GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            27
        )

        // Fill background
        for (i in 0 until 27) gui.inventory.setItem(i, BORDER.clone())

        fun renderDynamic() {
            val total = livePrice * amount
            val itemDisplay = when (shopItem.kind) {
                ShopItemKind.POTION -> buildPotionItem(shopItem)
                ShopItemKind.SPAWNER -> shopItem.spawnerTypeId?.let { plugin.spawnerManager.createSpawnerItem(it) } ?: ItemStack(Material.BARRIER)
                ShopItemKind.MATERIAL -> ItemStack(shopItem.material)
            }
            itemDisplay.amount = amount.coerceIn(1, 64)
            itemDisplay.editMeta { meta ->
                meta.displayName(
                    Component.text(displayLabel(shopItem), NamedTextColor.WHITE)
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
            if (buyItem(p, shopItem, amount.coerceIn(1, MAX_BUY))) {
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

    private fun buyItem(player: Player, shopItem: ShopItem, amount: Int): Boolean {
        val totalCost = shopItem.buyPrice * amount

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

        if (!plugin.economyManager.withdraw(player.uniqueId, totalCost)) {
            plugin.commsManager.send(player, Component.text("Purchase failed.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return false
        }

        // Potions cap at 1 per stack (vanilla), so deliver them one at a time rather than
        // as a single ItemStack with an oversized amount. Spawners are delivered via
        // SpawnerManager so the correct mob type/PDC tag is always preserved.
        val overflow = when (shopItem.kind) {
            ShopItemKind.POTION -> {
                val leftovers = mutableListOf<ItemStack>()
                repeat(amount) { leftovers.addAll(player.inventory.addItem(buildPotionItem(shopItem)).values) }
                leftovers
            }
            ShopItemKind.SPAWNER -> {
                val stack = shopItem.spawnerTypeId?.let { plugin.spawnerManager.createSpawnerItem(it, amount) }
                if (stack == null) {
                    // Delivery is impossible (misconfigured spawner id) — refund instead of
                    // silently keeping the player's money for an item that can't be given.
                    plugin.economyManager.deposit(player.uniqueId, totalCost)
                    plugin.commsManager.send(player, Component.text("Purchase failed; you have been refunded.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                    return false
                }
                player.inventory.addItem(stack).values.toList()
            }
            ShopItemKind.MATERIAL -> player.inventory.addItem(ItemStack(shopItem.material, amount)).values.toList()
        }

        // Drop any items that didn't fit
        for (remaining in overflow) {
            player.world.dropItemNaturally(player.location, remaining)
        }

        val name = displayLabel(shopItem)
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

    // ── Sell Logic ──────────────────────────────────────────────────────

    fun sellItem(player: Player, material: Material, sellPrice: Double, amount: Int) {
        val inventory = player.inventory

        if (amount == -1) {
            // Sell all of that material — compute earnings per slot to honour mutation multipliers
            var totalCount = 0
            var totalEarned = 0.0
            for (slot in 0 until inventory.size) {
                val stack = inventory.getItem(slot) ?: continue
                if (stack.type != material) continue
                val mutMult = plugin.mutationsManager.getMutationMultiplier(stack)
                totalEarned += sellPrice * mutMult * stack.amount
                totalCount += stack.amount
                inventory.setItem(slot, null)
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

            plugin.economyManager.deposit(player.uniqueId, totalEarned)
            plugin.marketManager.recordTransaction(material, "SELL", totalCount)

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
            // Sell specific amount — drain slots in order and apply per-slot mutation multipliers
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
            var totalEarned = 0.0
            for (slot in 0 until inventory.size) {
                if (remaining <= 0) break
                val stack = inventory.getItem(slot) ?: continue
                if (stack.type != material) continue
                val take = remaining.coerceAtMost(stack.amount)
                val mutMult = plugin.mutationsManager.getMutationMultiplier(stack)
                totalEarned += sellPrice * mutMult * take
                stack.amount -= take
                remaining -= take
                if (stack.amount <= 0) inventory.setItem(slot, null)
            }

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
