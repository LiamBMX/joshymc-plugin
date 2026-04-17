package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.BlockFace
import org.bukkit.block.Chest
import org.bukkit.block.DoubleChest
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

class SignShopManager(private val plugin: Joshymc) : Listener {

    data class ShopData(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val ownerUuid: UUID,
        val ownerName: String,
        val item: String,
        val buyPrice: Double?,
        val sellPrice: Double?,
        val chestWorld: String,
        val chestX: Int,
        val chestY: Int,
        val chestZ: Int
    ) {
        val signLocation: Location
            get() = Location(Bukkit.getWorld(world), x.toDouble(), y.toDouble(), z.toDouble())

        val chestLocation: Location
            get() = Location(Bukkit.getWorld(chestWorld), chestX.toDouble(), chestY.toDouble(), chestZ.toDouble())
    }

    companion object {
        private val SIGN_TYPES = Material.entries.filter { it.name.endsWith("_SIGN") && !it.name.contains("HANGING") && !it.name.contains("WALL").not().let { false } }

        private val WALL_SIGN_TYPES = Material.entries.filter { it.name.endsWith("_WALL_SIGN") }
        private val STANDING_SIGN_TYPES = Material.entries.filter { it.name.endsWith("_SIGN") && !it.name.contains("WALL") && !it.name.contains("HANGING") }
        private val ALL_SIGN_TYPES = WALL_SIGN_TYPES + STANDING_SIGN_TYPES

        private val CHEST_FACES = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP)

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.YELLOW_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS sign_shops (
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                owner_uuid TEXT NOT NULL,
                owner_name TEXT NOT NULL,
                item TEXT NOT NULL,
                buy_price REAL,
                sell_price REAL,
                chest_world TEXT NOT NULL,
                chest_x INTEGER NOT NULL,
                chest_y INTEGER NOT NULL,
                chest_z INTEGER NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )
        """.trimIndent())

        val shopCount = plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM sign_shops"
        ) { rs -> rs.getInt("cnt") } ?: 0

        plugin.logger.info("[SignShop] SignShopManager started ($shopCount shop(s) loaded).")
    }

    // ---- Database Operations ----

    private fun saveShop(shop: ShopData) {
        plugin.databaseManager.execute(
            """INSERT INTO sign_shops (world, x, y, z, owner_uuid, owner_name, item, buy_price, sell_price, chest_world, chest_x, chest_y, chest_z)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(world, x, y, z) DO UPDATE SET
               owner_uuid = ?, owner_name = ?, item = ?, buy_price = ?, sell_price = ?,
               chest_world = ?, chest_x = ?, chest_y = ?, chest_z = ?""",
            shop.world, shop.x, shop.y, shop.z,
            shop.ownerUuid.toString(), shop.ownerName, shop.item, shop.buyPrice, shop.sellPrice,
            shop.chestWorld, shop.chestX, shop.chestY, shop.chestZ,
            // ON CONFLICT values
            shop.ownerUuid.toString(), shop.ownerName, shop.item, shop.buyPrice, shop.sellPrice,
            shop.chestWorld, shop.chestX, shop.chestY, shop.chestZ
        )
    }

    fun getShop(world: String, x: Int, y: Int, z: Int): ShopData? {
        return plugin.databaseManager.queryFirst(
            "SELECT * FROM sign_shops WHERE world = ? AND x = ? AND y = ? AND z = ?",
            world, x, y, z
        ) { rs ->
            ShopData(
                world = rs.getString("world"),
                x = rs.getInt("x"),
                y = rs.getInt("y"),
                z = rs.getInt("z"),
                ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
                ownerName = rs.getString("owner_name"),
                item = rs.getString("item"),
                buyPrice = rs.getDouble("buy_price").let { if (rs.wasNull()) null else it },
                sellPrice = rs.getDouble("sell_price").let { if (rs.wasNull()) null else it },
                chestWorld = rs.getString("chest_world"),
                chestX = rs.getInt("chest_x"),
                chestY = rs.getInt("chest_y"),
                chestZ = rs.getInt("chest_z")
            )
        }
    }

    fun getShopAtLocation(location: Location): ShopData? {
        val world = location.world?.name ?: return null
        return getShop(world, location.blockX, location.blockY, location.blockZ)
    }

    fun removeShop(world: String, x: Int, y: Int, z: Int) {
        plugin.databaseManager.execute(
            "DELETE FROM sign_shops WHERE world = ? AND x = ? AND y = ? AND z = ?",
            world, x, y, z
        )
    }

    fun getShopsByOwner(uuid: UUID): List<ShopData> {
        return plugin.databaseManager.query(
            "SELECT * FROM sign_shops WHERE owner_uuid = ?",
            uuid.toString()
        ) { rs ->
            ShopData(
                world = rs.getString("world"),
                x = rs.getInt("x"),
                y = rs.getInt("y"),
                z = rs.getInt("z"),
                ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
                ownerName = rs.getString("owner_name"),
                item = rs.getString("item"),
                buyPrice = rs.getDouble("buy_price").let { if (rs.wasNull()) null else it },
                sellPrice = rs.getDouble("sell_price").let { if (rs.wasNull()) null else it },
                chestWorld = rs.getString("chest_world"),
                chestX = rs.getInt("chest_x"),
                chestY = rs.getInt("chest_y"),
                chestZ = rs.getInt("chest_z")
            )
        }
    }

    private fun getShopWithChest(chestWorld: String, chestX: Int, chestY: Int, chestZ: Int): ShopData? {
        return plugin.databaseManager.queryFirst(
            "SELECT * FROM sign_shops WHERE chest_world = ? AND chest_x = ? AND chest_y = ? AND chest_z = ?",
            chestWorld, chestX, chestY, chestZ
        ) { rs ->
            ShopData(
                world = rs.getString("world"),
                x = rs.getInt("x"),
                y = rs.getInt("y"),
                z = rs.getInt("z"),
                ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
                ownerName = rs.getString("owner_name"),
                item = rs.getString("item"),
                buyPrice = rs.getDouble("buy_price").let { if (rs.wasNull()) null else it },
                sellPrice = rs.getDouble("sell_price").let { if (rs.wasNull()) null else it },
                chestWorld = rs.getString("chest_world"),
                chestX = rs.getInt("chest_x"),
                chestY = rs.getInt("chest_y"),
                chestZ = rs.getInt("chest_z")
            )
        }
    }

    // ---- Sign Creation ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        val player = event.player
        val line0 = event.line(0)?.let { plainText(it) }?.trim() ?: return

        if (!line0.equals("[Shop]", ignoreCase = true)) return

        if (!player.hasPermission("joshymc.shop.create")) {
            plugin.commsManager.send(player, Component.text("You don't have permission to create shops.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val block = event.block

        // Find attached chest
        val chestBlock = findAttachedChest(block)
        if (chestBlock == null) {
            plugin.commsManager.send(player, Component.text("Shop sign must be placed on a chest.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        // Check that the player owns the chest area (basic check: no other shop on this chest)
        val existingShop = getShopWithChest(
            chestBlock.world.name, chestBlock.x, chestBlock.y, chestBlock.z
        )
        if (existingShop != null && existingShop.ownerUuid != player.uniqueId) {
            plugin.commsManager.send(player, Component.text("This chest already has a shop owned by someone else.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        // Parse item (line 2)
        val line1Text = event.line(1)?.let { plainText(it) }?.trim() ?: ""
        val itemMaterial: Material
        val itemDisplayName: String

        if (line1Text.equals("[hand]", ignoreCase = true)) {
            val handItem = player.inventory.itemInMainHand
            if (handItem.type == Material.AIR) {
                plugin.commsManager.send(player, Component.text("Hold an item in your hand when using [hand].", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return
            }
            itemMaterial = handItem.type
            itemDisplayName = formatMaterialName(itemMaterial)
        } else {
            val mat = matchMaterial(line1Text)
            if (mat == null) {
                plugin.commsManager.send(player, Component.text("Unknown item: $line1Text", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                return
            }
            itemMaterial = mat
            itemDisplayName = formatMaterialName(itemMaterial)
        }

        // Parse prices (line 3)
        val line2Text = event.line(2)?.let { plainText(it) }?.trim() ?: ""
        val (buyPrice, sellPrice) = parsePrices(line2Text)

        if (buyPrice == null && sellPrice == null) {
            plugin.commsManager.send(player, Component.text("Invalid price format. Use: B <price>, S <price>, or B <price> S <price>", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        if ((buyPrice != null && buyPrice <= 0) || (sellPrice != null && sellPrice <= 0)) {
            plugin.commsManager.send(player, Component.text("Prices must be greater than zero.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        // Create shop data
        val shop = ShopData(
            world = block.world.name,
            x = block.x,
            y = block.y,
            z = block.z,
            ownerUuid = player.uniqueId,
            ownerName = player.name,
            item = itemMaterial.name,
            buyPrice = buyPrice,
            sellPrice = sellPrice,
            chestWorld = chestBlock.world.name,
            chestX = chestBlock.x,
            chestY = chestBlock.y,
            chestZ = chestBlock.z
        )

        saveShop(shop)

        // Format the sign
        event.line(0, Component.text("[Shop]", TextColor.color(0x0000AA)).decoration(TextDecoration.BOLD, true))
        event.line(1, Component.text(itemDisplayName, NamedTextColor.WHITE))
        event.line(2, buildPriceLine(buyPrice, sellPrice))
        event.line(3, Component.text(player.name, NamedTextColor.GRAY))

        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
        plugin.commsManager.send(player, Component.text("Shop created!", NamedTextColor.GREEN), CommunicationsManager.Category.ECONOMY)
    }

    // ---- Sign Interaction ----

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return
        val block = event.clickedBlock ?: return

        if (block.type !in ALL_SIGN_TYPES) return

        val shop = getShopAtLocation(block.location) ?: return

        event.isCancelled = true

        // Restore sign text if it was wiped (chunk reload, corruption, etc.)
        restoreSignText(block, shop)

        val player = event.player

        if (player.uniqueId == shop.ownerUuid) {
            showOwnerInfo(player, shop)
        } else {
            openShopGui(player, shop)
        }
    }

    private fun showOwnerInfo(player: Player, shop: ShopData) {
        val itemName = formatMaterialName(Material.valueOf(shop.item))
        val stock = countChestStock(shop)

        val msg = Component.text()
            .append(Component.text("--- Your Shop ---", TextColor.color(0xFFD700)).decoration(TextDecoration.BOLD, true))
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

        plugin.commsManager.send(player, msg.build(), CommunicationsManager.Category.ECONOMY)
    }

    private fun openShopGui(player: Player, shop: ShopData) {
        val itemMaterial = try { Material.valueOf(shop.item) } catch (_: Exception) { Material.BARRIER }
        val itemName = formatMaterialName(itemMaterial)
        val stock = countChestStock(shop)

        val title = Component.text("         ")
            .append(Component.text(shop.ownerName, NamedTextColor.GRAY))
            .append(Component.text("'s Shop", NamedTextColor.GRAY))
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)

        // Fill with glass
        for (i in 0 until 27) gui.inventory.setItem(i, FILLER.clone())

        // Borders (top and bottom rows)
        for (i in 0..8) { gui.inventory.setItem(i, BORDER.clone()) }
        for (i in 18..26) { gui.inventory.setItem(i, BORDER.clone()) }

        // Display item in center
        val displayItem = ItemStack(itemMaterial)
        displayItem.editMeta { meta ->
            meta.displayName(Component.text(itemName, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(Component.text("  Stock: $stock", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            if (shop.buyPrice != null) {
                lore.add(Component.text("  Buy: ${plugin.economyManager.format(shop.buyPrice)}", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
            }
            if (shop.sellPrice != null) {
                lore.add(Component.text("  Sell: ${plugin.economyManager.format(shop.sellPrice)}", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
        }
        gui.setItem(13, displayItem)

        // Buy button (slot 11)
        if (shop.buyPrice != null) {
            val buyButton = ItemStack(Material.LIME_STAINED_GLASS_PANE)
            buyButton.editMeta { meta ->
                meta.displayName(Component.text("Buy 1x $itemName", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  Price: ${plugin.economyManager.format(shop.buyPrice)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to buy", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ))
            }

            gui.setItem(11, buyButton) { buyer, _ ->
                handleBuy(buyer, shop)
                buyer.closeInventory()
            }
        }

        // Sell button (slot 15)
        if (shop.sellPrice != null) {
            val sellButton = ItemStack(Material.ORANGE_STAINED_GLASS_PANE)
            sellButton.editMeta { meta ->
                meta.displayName(Component.text("Sell 1x $itemName", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  Price: ${plugin.economyManager.format(shop.sellPrice)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to sell", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                ))
            }

            gui.setItem(15, sellButton) { seller, _ ->
                handleSell(seller, shop)
                seller.closeInventory()
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ---- Buy / Sell Logic ----

    private fun handleBuy(buyer: Player, shop: ShopData) {
        val price = shop.buyPrice ?: return
        val itemMaterial = try { Material.valueOf(shop.item) } catch (_: Exception) {
            plugin.commsManager.send(buyer, Component.text("This shop has an invalid item.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }
        val itemName = formatMaterialName(itemMaterial)

        // Check buyer has enough money
        if (!plugin.economyManager.has(buyer.uniqueId, price)) {
            plugin.commsManager.send(buyer, Component.text("You need ${plugin.economyManager.format(price)} to buy this.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            buyer.playSound(buyer.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Check chest has stock
        val chestInv = getChestInventory(shop)
        if (chestInv == null) {
            plugin.commsManager.send(buyer, Component.text("Shop chest not found.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val stock = countItemsInInventory(chestInv, itemMaterial)
        if (stock < 1) {
            plugin.commsManager.send(buyer, Component.text("This shop is out of stock.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            buyer.playSound(buyer.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Check buyer has inventory space
        if (buyer.inventory.firstEmpty() == -1) {
            // Try to stack
            val canStack = buyer.inventory.contents.any {
                it != null && it.type == itemMaterial && it.amount < it.maxStackSize
            }
            if (!canStack) {
                plugin.commsManager.send(buyer, Component.text("Your inventory is full.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
                buyer.playSound(buyer.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
                return
            }
        }

        // Anti-dupe: remove the item from the chest FIRST, verify it was actually removed
        val beforeCount = countItemsInInventory(chestInv, itemMaterial)
        removeItemFromInventory(chestInv, itemMaterial, 1)
        val afterCount = countItemsInInventory(chestInv, itemMaterial)

        if (afterCount >= beforeCount) {
            // Item removal failed (chest was modified between check and removal)
            plugin.commsManager.send(buyer, Component.text("This shop is out of stock.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            buyer.playSound(buyer.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Item successfully removed from chest — now transfer money
        plugin.databaseManager.transaction {
            plugin.economyManager.withdraw(buyer.uniqueId, price)
            plugin.economyManager.deposit(shop.ownerUuid, price)
        }

        buyer.inventory.addItem(ItemStack(itemMaterial, 1))

        buyer.playSound(buyer.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
        plugin.commsManager.send(
            buyer,
            Component.text("Bought 1x $itemName for ${plugin.economyManager.format(price)}.", NamedTextColor.GREEN),
            CommunicationsManager.Category.ECONOMY
        )

        // Notify owner if online
        val owner = Bukkit.getPlayer(shop.ownerUuid)
        if (owner != null) {
            plugin.commsManager.send(
                owner,
                Component.text("${buyer.name} bought 1x $itemName from your shop for ${plugin.economyManager.format(price)}.", NamedTextColor.GREEN),
                CommunicationsManager.Category.ECONOMY
            )
        }
    }

    private fun handleSell(seller: Player, shop: ShopData) {
        val price = shop.sellPrice ?: return
        val itemMaterial = try { Material.valueOf(shop.item) } catch (_: Exception) {
            plugin.commsManager.send(seller, Component.text("This shop has an invalid item.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }
        val itemName = formatMaterialName(itemMaterial)

        // Check seller has the item
        val sellerHas = countItemsInInventory(seller.inventory, itemMaterial)
        if (sellerHas < 1) {
            plugin.commsManager.send(seller, Component.text("You don't have any $itemName to sell.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            seller.playSound(seller.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Check shop owner has enough money
        if (!plugin.economyManager.has(shop.ownerUuid, price)) {
            plugin.commsManager.send(seller, Component.text("The shop owner can't afford to buy this.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            seller.playSound(seller.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Check chest has space
        val chestInv = getChestInventory(shop)
        if (chestInv == null) {
            plugin.commsManager.send(seller, Component.text("Shop chest not found.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            return
        }

        val hasSpace = chestInv.firstEmpty() != -1 || chestInv.contents.any {
            it != null && it.type == itemMaterial && it.amount < it.maxStackSize
        }
        if (!hasSpace) {
            plugin.commsManager.send(seller, Component.text("The shop chest is full.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            seller.playSound(seller.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Anti-dupe: remove the item from seller FIRST, verify it was actually removed
        val beforeCount = countItemsInInventory(seller.inventory, itemMaterial)
        removeItemFromInventory(seller.inventory, itemMaterial, 1)
        val afterCount = countItemsInInventory(seller.inventory, itemMaterial)

        if (afterCount >= beforeCount) {
            // Item removal failed
            plugin.commsManager.send(seller, Component.text("You don't have any $itemName to sell.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            seller.playSound(seller.location, Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return
        }

        // Item successfully taken from seller — now transfer money and stock chest
        plugin.databaseManager.transaction {
            plugin.economyManager.withdraw(shop.ownerUuid, price)
            plugin.economyManager.deposit(seller.uniqueId, price)
        }

        chestInv.addItem(ItemStack(itemMaterial, 1))

        seller.playSound(seller.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
        plugin.commsManager.send(
            seller,
            Component.text("Sold 1x $itemName for ${plugin.economyManager.format(price)}.", NamedTextColor.GREEN),
            CommunicationsManager.Category.ECONOMY
        )

        // Notify owner if online
        val owner = Bukkit.getPlayer(shop.ownerUuid)
        if (owner != null) {
            plugin.commsManager.send(
                owner,
                Component.text("${seller.name} sold 1x $itemName to your shop for ${plugin.economyManager.format(price)}.", NamedTextColor.GREEN),
                CommunicationsManager.Category.ECONOMY
            )
        }
    }

    // ---- Break Protection ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val block = event.block
        val player = event.player

        // Protect shop signs
        if (block.type in ALL_SIGN_TYPES) {
            val shop = getShopAtLocation(block.location) ?: return
            if (player.uniqueId == shop.ownerUuid || player.hasPermission("joshymc.shop.admin")) {
                removeShop(block.world.name, block.x, block.y, block.z)
                plugin.commsManager.send(player, Component.text("Shop sign removed.", NamedTextColor.YELLOW), CommunicationsManager.Category.ECONOMY)
            } else {
                event.isCancelled = true
                plugin.commsManager.send(player, Component.text("You can't break someone else's shop sign.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            }
            return
        }

        // Protect chests with shop signs
        if (block.type == Material.CHEST || block.type == Material.TRAPPED_CHEST) {
            val shop = getShopWithChest(block.world.name, block.x, block.y, block.z)
            if (shop != null) {
                event.isCancelled = true
                plugin.commsManager.send(player, Component.text("Remove the shop sign first before breaking this chest.", NamedTextColor.RED), CommunicationsManager.Category.ECONOMY)
            }
        }
    }

    // ---- Utility ----

    private fun restoreSignText(block: org.bukkit.block.Block, shop: ShopData) {
        val state = block.state as? Sign ?: return
        val side = state.getSide(Side.FRONT)
        val line0 = plainText(side.line(0))

        // If sign already has [Shop] text, no restoration needed
        if (line0.contains("Shop", ignoreCase = true)) return

        val itemMaterial = try { Material.valueOf(shop.item) } catch (_: Exception) { return }
        val itemName = formatMaterialName(itemMaterial)

        side.line(0, Component.text("[Shop]", TextColor.color(0x0000AA)).decoration(TextDecoration.BOLD, true))
        side.line(1, Component.text(itemName, NamedTextColor.WHITE))
        side.line(2, buildPriceLine(shop.buyPrice, shop.sellPrice))
        side.line(3, Component.text(shop.ownerName, NamedTextColor.GRAY))
        state.update()
    }

    private fun findAttachedChest(signBlock: org.bukkit.block.Block): org.bukkit.block.Block? {
        // For wall signs, the chest is the block the sign is attached to
        val blockData = signBlock.blockData
        if (blockData is org.bukkit.block.data.type.WallSign) {
            val attached = signBlock.getRelative(blockData.facing.oppositeFace)
            if (attached.type == Material.CHEST || attached.type == Material.TRAPPED_CHEST) {
                return attached
            }
        }

        // For standing signs, check adjacent blocks
        for (face in CHEST_FACES) {
            val adjacent = signBlock.getRelative(face)
            if (adjacent.type == Material.CHEST || adjacent.type == Material.TRAPPED_CHEST) {
                return adjacent
            }
        }

        return null
    }

    private fun getChestInventory(shop: ShopData): org.bukkit.inventory.Inventory? {
        val world = Bukkit.getWorld(shop.chestWorld) ?: return null
        val block = world.getBlockAt(shop.chestX, shop.chestY, shop.chestZ)
        val state = block.state

        return when (state) {
            is Chest -> state.inventory
            else -> null
        }
    }

    fun countChestStock(shop: ShopData): Int {
        val inv = getChestInventory(shop) ?: return 0
        val mat = try { Material.valueOf(shop.item) } catch (_: Exception) { return 0 }
        return countItemsInInventory(inv, mat)
    }

    private fun countItemsInInventory(inventory: org.bukkit.inventory.Inventory, material: Material): Int {
        return inventory.contents.filterNotNull().filter { it.type == material }.sumOf { it.amount }
    }

    private fun removeItemFromInventory(inventory: org.bukkit.inventory.Inventory, material: Material, amount: Int) {
        var remaining = amount
        for (i in 0 until inventory.size) {
            val item = inventory.getItem(i) ?: continue
            if (item.type != material) continue
            if (item.amount <= remaining) {
                remaining -= item.amount
                inventory.setItem(i, null)
            } else {
                item.amount -= remaining
                remaining = 0
            }
            if (remaining <= 0) break
        }
    }

    private fun parsePrices(text: String): Pair<Double?, Double?> {
        var buyPrice: Double? = null
        var sellPrice: Double? = null

        val upper = text.uppercase().trim()
        // Patterns: "B 10", "S 5", "B 10 S 5", "B 10 : S 5"
        val parts = upper.replace(":", " ").split("\\s+".toRegex())

        var i = 0
        while (i < parts.size) {
            when (parts[i]) {
                "B", "BUY" -> {
                    if (i + 1 < parts.size) {
                        buyPrice = plugin.economyManager.parseAmount(parts[i + 1])
                        i += 2
                    } else i++
                }
                "S", "SELL" -> {
                    if (i + 1 < parts.size) {
                        sellPrice = plugin.economyManager.parseAmount(parts[i + 1])
                        i += 2
                    } else i++
                }
                else -> i++
            }
        }

        return Pair(buyPrice, sellPrice)
    }

    private fun buildPriceLine(buyPrice: Double?, sellPrice: Double?): Component {
        val builder = Component.text()
        if (buyPrice != null) {
            builder.append(Component.text("B ", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true))
            builder.append(Component.text(plugin.economyManager.format(buyPrice), NamedTextColor.GREEN).decoration(TextDecoration.BOLD, false))
        }
        if (buyPrice != null && sellPrice != null) {
            builder.append(Component.text(" ", NamedTextColor.DARK_GRAY))
        }
        if (sellPrice != null) {
            builder.append(Component.text("S ", NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, true))
            builder.append(Component.text(plugin.economyManager.format(sellPrice), NamedTextColor.YELLOW).decoration(TextDecoration.BOLD, false))
        }
        return builder.build()
    }

    private fun matchMaterial(name: String): Material? {
        val cleaned = name.trim().uppercase().replace(" ", "_")
        return try {
            Material.valueOf(cleaned)
        } catch (_: Exception) {
            // Try fuzzy match
            Material.entries.firstOrNull { it.name.equals(cleaned, ignoreCase = true) }
        }
    }

    private fun formatMaterialName(material: Material): String {
        return material.name.lowercase().split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
    }

    private fun plainText(component: Component): String {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component)
    }
}
