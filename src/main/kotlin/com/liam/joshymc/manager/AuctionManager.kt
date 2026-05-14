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
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AuctionManager(private val plugin: Joshymc) {

    companion object {
        private val MAIN_TITLE: Component = Component.text("         ")
            .append(Component.text("Auction House", TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val CONFIRM_TITLE: Component = Component.text("         ")
            .append(Component.text("Confirm Purchase", TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val YOUR_LISTINGS_TITLE: Component = Component.text("         ")
            .append(Component.text("Your Listings", TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val EXPIRED_TITLE: Component = Component.text("         ")
            .append(Component.text("Expired Items", TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val BIDS_TITLE: Component = Component.text("         ")
            .append(Component.text("Active Bids", TextColor.color(0xFFAA00)))
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)

        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val BORDER = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
    }

    data class AuctionListing(
        val id: Int,
        val sellerUuid: UUID,
        val sellerName: String,
        val item: ItemStack,
        val price: Double,
        val listedAt: Long,
        val expiresAt: Long
    )

    data class BidListing(
        val id: Int,
        val sellerUuid: UUID,
        val sellerName: String,
        val item: ItemStack,
        val startingPrice: Double,
        val currentBid: Double,
        val currentBidderUuid: UUID?,
        val currentBidderName: String?,
        val listedAt: Long,
        val expiresAt: Long
    )

    data class ExpiredItem(
        val id: Int,
        val ownerUuid: UUID,
        val item: ItemStack,
        val expiredAt: Long
    )

    // Config
    private var listingDurationHours: Int = 48
    private var bidDurationHours: Int = 2
    private var maxListings: Int = 10
    private var minPrice: Double = 1.0
    private var maxPrice: Double = 1_000_000_000.0
    private var taxPercent: Double = 5.0

    // State tracking
    private val playerPages = mutableMapOf<UUID, Int>()
    val pendingBidInputs = ConcurrentHashMap<UUID, BidListing>()

    private var expiryTask: BukkitTask? = null

    // ---- Lifecycle ----

    fun start() {
        val cfg = plugin.config
        listingDurationHours = cfg.getInt("auction.listing-duration-hours", 48)
        bidDurationHours = cfg.getInt("auction.bid-duration-hours", 2)
        maxListings = cfg.getInt("auction.max-listings-per-player", 10)
        minPrice = cfg.getDouble("auction.min-price", 1.0)
        maxPrice = cfg.getDouble("auction.max-price", 1_000_000_000.0)
        taxPercent = cfg.getDouble("auction.tax-percent", 5.0)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS auction_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid TEXT NOT NULL,
                seller_name TEXT NOT NULL,
                item TEXT NOT NULL,
                price REAL NOT NULL,
                listed_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS auction_expired (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                owner_uuid TEXT NOT NULL,
                item TEXT NOT NULL,
                expired_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS auction_bid_listings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seller_uuid TEXT NOT NULL,
                seller_name TEXT NOT NULL,
                item TEXT NOT NULL,
                starting_price REAL NOT NULL,
                current_bid REAL NOT NULL,
                current_bidder_uuid TEXT,
                current_bidder_name TEXT,
                listed_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
        """.trimIndent())

        // Expiry check every 60 seconds (1200 ticks)
        expiryTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            checkExpired()
            checkExpiredBids()
        }, 1200L, 1200L)

        plugin.logger.info("[Auction] AuctionManager started (duration: ${listingDurationHours}h, bid: ${bidDurationHours}h, tax: $taxPercent%).")
    }

    fun stop() {
        expiryTask?.cancel()
        expiryTask = null
        playerPages.clear()
        pendingBidInputs.clear()
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes())
    }

    private fun deserializeItem(base64: String): ItemStack {
        return ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))
    }

    // ---- Row mapper ----

    private fun mapListing(rs: java.sql.ResultSet): AuctionListing {
        return AuctionListing(
            id = rs.getInt("id"),
            sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
            sellerName = rs.getString("seller_name"),
            item = deserializeItem(rs.getString("item")),
            price = rs.getDouble("price"),
            listedAt = rs.getLong("listed_at"),
            expiresAt = rs.getLong("expires_at")
        )
    }

    private fun mapExpired(rs: java.sql.ResultSet): ExpiredItem {
        return ExpiredItem(
            id = rs.getInt("id"),
            ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
            item = deserializeItem(rs.getString("item")),
            expiredAt = rs.getLong("expired_at")
        )
    }

    private fun mapBidListing(rs: java.sql.ResultSet): BidListing {
        val bidderUuidStr = rs.getString("current_bidder_uuid")
        return BidListing(
            id = rs.getInt("id"),
            sellerUuid = UUID.fromString(rs.getString("seller_uuid")),
            sellerName = rs.getString("seller_name"),
            item = deserializeItem(rs.getString("item")),
            startingPrice = rs.getDouble("starting_price"),
            currentBid = rs.getDouble("current_bid"),
            currentBidderUuid = if (bidderUuidStr != null) UUID.fromString(bidderUuidStr) else null,
            currentBidderName = rs.getString("current_bidder_name"),
            listedAt = rs.getLong("listed_at"),
            expiresAt = rs.getLong("expires_at")
        )
    }

    // ---- Core methods ----

    fun listItem(player: Player, price: Double) {
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to sell.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (price < minPrice || price > maxPrice) {
            plugin.commsManager.send(player, Component.text("Price must be between ${plugin.economyManager.format(minPrice)} and ${plugin.economyManager.format(maxPrice)}.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val currentListings = getPlayerListings(player.uniqueId)
        if (currentListings.size >= maxListings) {
            plugin.commsManager.send(player, Component.text("You have reached the maximum of $maxListings listings.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + (listingDurationHours * 3_600_000L)
        val serialized = serializeItem(held)

        plugin.databaseManager.execute(
            "INSERT INTO auction_listings (seller_uuid, seller_name, item, price, listed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
            player.uniqueId.toString(), player.name, serialized, price, now, expiresAt
        )

        player.inventory.setItemInMainHand(null)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)

        plugin.commsManager.send(
            player,
            Component.text("Listed ", NamedTextColor.GREEN)
                .append(held.displayName())
                .append(Component.text(" for ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(price), NamedTextColor.GOLD)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    fun buyItem(player: Player, listingId: Int) {
        // Read the listing first for validation
        val listing = plugin.databaseManager.queryFirst(
            "SELECT * FROM auction_listings WHERE id = ? AND expires_at > ?",
            listingId, System.currentTimeMillis()
        ) { rs -> mapListing(rs) }

        if (listing == null) {
            plugin.commsManager.send(player, Component.text("That listing no longer exists.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (listing.sellerUuid == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You cannot buy your own listing.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.economyManager.getBalance(player) < listing.price) {
            plugin.commsManager.send(player, Component.text("You cannot afford this item.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Anti-dupe: atomically delete the listing FIRST. If another call already
        // deleted it, rowsAffected will be 0 and we abort before moving any money/items.
        val rowsDeleted = plugin.databaseManager.executeUpdate(
            "DELETE FROM auction_listings WHERE id = ?", listingId
        )
        if (rowsDeleted == 0) {
            plugin.commsManager.send(player, Component.text("That listing no longer exists.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Listing is now claimed by this buyer — safe to proceed
        if (!plugin.economyManager.withdraw(player.uniqueId, listing.price)) {
            // Withdraw failed — re-insert the listing to undo the delete
            plugin.databaseManager.execute(
                "INSERT INTO auction_listings (id, seller_uuid, seller_name, item, price, listed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                listing.id, listing.sellerUuid.toString(), listing.sellerName,
                serializeItem(listing.item), listing.price, listing.listedAt, listing.expiresAt
            )
            plugin.commsManager.send(player, Component.text("Transaction failed.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Calculate tax and pay seller
        val tax = listing.price * (taxPercent / 100.0)
        val sellerPayout = listing.price - tax
        plugin.economyManager.deposit(listing.sellerUuid, sellerPayout)

        // Give item to buyer
        val leftover = player.inventory.addItem(listing.item)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            plugin.commsManager.send(player, Component.text("Inventory full - item dropped at your feet.", NamedTextColor.YELLOW), CommunicationsManager.Category.DEFAULT)
        }

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.5f)
        plugin.commsManager.send(
            player,
            Component.text("Purchased for ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(listing.price), NamedTextColor.GOLD))
                .append(Component.text("!", NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )

        // Notify seller if online
        val seller = Bukkit.getPlayer(listing.sellerUuid)
        if (seller != null) {
            plugin.commsManager.send(
                seller,
                Component.text("Your listing was purchased by ", NamedTextColor.GREEN)
                    .append(Component.text(player.name, NamedTextColor.WHITE))
                    .append(Component.text(" for ", NamedTextColor.GREEN))
                    .append(Component.text(plugin.economyManager.format(listing.price), NamedTextColor.GOLD))
                    .append(Component.text(" (${plugin.economyManager.format(tax)} tax).", NamedTextColor.GRAY)),
                CommunicationsManager.Category.DEFAULT
            )
        }
    }

    fun cancelListing(player: Player, listingId: Int) {
        val listing = plugin.databaseManager.queryFirst(
            "SELECT * FROM auction_listings WHERE id = ? AND seller_uuid = ?",
            listingId, player.uniqueId.toString()
        ) { rs -> mapListing(rs) }

        if (listing == null) {
            plugin.commsManager.send(player, Component.text("Listing not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Anti-dupe: atomically delete. If already deleted (bought/cancelled), abort.
        val rowsDeleted = plugin.databaseManager.executeUpdate(
            "DELETE FROM auction_listings WHERE id = ? AND seller_uuid = ?",
            listingId, player.uniqueId.toString()
        )
        if (rowsDeleted == 0) {
            plugin.commsManager.send(player, Component.text("Listing not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val leftover = player.inventory.addItem(listing.item)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            plugin.commsManager.send(player, Component.text("Inventory full - item dropped at your feet.", NamedTextColor.YELLOW), CommunicationsManager.Category.DEFAULT)
        }

        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f)
        plugin.commsManager.send(player, Component.text("Listing cancelled. Item returned.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
    }

    fun getActiveListings(page: Int, pageSize: Int = 28): List<AuctionListing> {
        val offset = page * pageSize
        return plugin.databaseManager.query(
            "SELECT * FROM auction_listings WHERE expires_at > ? ORDER BY listed_at DESC LIMIT ? OFFSET ?",
            System.currentTimeMillis(), pageSize, offset
        ) { rs -> mapListing(rs) }
    }

    fun getPlayerListings(uuid: UUID): List<AuctionListing> {
        return plugin.databaseManager.query(
            "SELECT * FROM auction_listings WHERE seller_uuid = ? AND expires_at > ? ORDER BY listed_at DESC",
            uuid.toString(), System.currentTimeMillis()
        ) { rs -> mapListing(rs) }
    }

    fun getExpiredItems(uuid: UUID): List<ExpiredItem> {
        return plugin.databaseManager.query(
            "SELECT * FROM auction_expired WHERE owner_uuid = ? ORDER BY expired_at DESC",
            uuid.toString()
        ) { rs -> mapExpired(rs) }
    }

    fun claimExpired(player: Player, expiredId: Int) {
        val expired = plugin.databaseManager.queryFirst(
            "SELECT * FROM auction_expired WHERE id = ? AND owner_uuid = ?",
            expiredId, player.uniqueId.toString()
        ) { rs -> mapExpired(rs) }

        if (expired == null) {
            plugin.commsManager.send(player, Component.text("Expired item not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Anti-dupe: atomically delete. If already claimed, abort.
        val rowsDeleted = plugin.databaseManager.executeUpdate(
            "DELETE FROM auction_expired WHERE id = ? AND owner_uuid = ?",
            expiredId, player.uniqueId.toString()
        )
        if (rowsDeleted == 0) {
            plugin.commsManager.send(player, Component.text("Expired item not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val leftover = player.inventory.addItem(expired.item)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            plugin.commsManager.send(player, Component.text("Inventory full - item dropped at your feet.", NamedTextColor.YELLOW), CommunicationsManager.Category.DEFAULT)
        }

        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f)
        plugin.commsManager.send(player, Component.text("Expired item claimed.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
    }

    fun listBidItem(player: Player, startingPrice: Double) {
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to bid out.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (startingPrice < minPrice || startingPrice > maxPrice) {
            plugin.commsManager.send(player, Component.text("Starting price must be between ${plugin.economyManager.format(minPrice)} and ${plugin.economyManager.format(maxPrice)}.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val totalListings = getPlayerListings(player.uniqueId).size + getPlayerBidListings(player.uniqueId).size
        if (totalListings >= maxListings) {
            plugin.commsManager.send(player, Component.text("You have reached the maximum of $maxListings listings.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val now = System.currentTimeMillis()
        val expiresAt = now + (bidDurationHours * 3_600_000L)
        val serialized = serializeItem(held)

        plugin.databaseManager.execute(
            "INSERT INTO auction_bid_listings (seller_uuid, seller_name, item, starting_price, current_bid, current_bidder_uuid, current_bidder_name, listed_at, expires_at) VALUES (?, ?, ?, ?, ?, NULL, NULL, ?, ?)",
            player.uniqueId.toString(), player.name, serialized, startingPrice, startingPrice, now, expiresAt
        )

        player.inventory.setItemInMainHand(null)
        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f)

        plugin.commsManager.send(
            player,
            Component.text("Listed ", NamedTextColor.GREEN)
                .append(held.displayName())
                .append(Component.text(" for bidding. Starting at ", NamedTextColor.GREEN))
                .append(Component.text(plugin.economyManager.format(startingPrice), NamedTextColor.GOLD))
                .append(Component.text(". Ends in ${bidDurationHours}h.", NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    fun placeBid(player: Player, listingId: Int, amount: Double) {
        val listing = plugin.databaseManager.queryFirst(
            "SELECT * FROM auction_bid_listings WHERE id = ? AND expires_at > ?",
            listingId, System.currentTimeMillis()
        ) { rs -> mapBidListing(rs) }

        if (listing == null) {
            plugin.commsManager.send(player, Component.text("That bid listing no longer exists.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (listing.sellerUuid == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You cannot bid on your own listing.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (listing.currentBidderUuid == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You are already the highest bidder.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (amount < listing.startingPrice) {
            plugin.commsManager.send(player, Component.text("Bid must be at least ${plugin.economyManager.format(listing.startingPrice)}.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (amount <= listing.currentBid && listing.currentBidderUuid != null) {
            plugin.commsManager.send(player, Component.text("Bid must be higher than the current bid of ${plugin.economyManager.format(listing.currentBid)}.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (plugin.economyManager.getBalance(player) < amount) {
            plugin.commsManager.send(player, Component.text("You cannot afford that bid.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Atomically update the listing to claim this bid slot
        val rowsUpdated = plugin.databaseManager.executeUpdate(
            "UPDATE auction_bid_listings SET current_bid = ?, current_bidder_uuid = ?, current_bidder_name = ? WHERE id = ? AND expires_at > ? AND (current_bidder_uuid IS NULL OR (current_bid < ?))",
            amount, player.uniqueId.toString(), player.name, listingId, System.currentTimeMillis(), amount
        )

        if (rowsUpdated == 0) {
            plugin.commsManager.send(player, Component.text("You were outbid or the listing expired. Try again.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Deduct funds from new bidder
        if (!plugin.economyManager.withdraw(player.uniqueId, amount)) {
            // Rollback the DB update
            if (listing.currentBidderUuid != null) {
                plugin.databaseManager.execute(
                    "UPDATE auction_bid_listings SET current_bid = ?, current_bidder_uuid = ?, current_bidder_name = ? WHERE id = ?",
                    listing.currentBid, listing.currentBidderUuid.toString(), listing.currentBidderName, listingId
                )
            } else {
                plugin.databaseManager.execute(
                    "UPDATE auction_bid_listings SET current_bid = ?, current_bidder_uuid = NULL, current_bidder_name = NULL WHERE id = ?",
                    listing.startingPrice, listingId
                )
            }
            plugin.commsManager.send(player, Component.text("Transaction failed.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        // Refund previous highest bidder
        if (listing.currentBidderUuid != null) {
            plugin.economyManager.deposit(listing.currentBidderUuid, listing.currentBid)
            val prevBidder = Bukkit.getPlayer(listing.currentBidderUuid)
            if (prevBidder != null) {
                plugin.commsManager.send(
                    prevBidder,
                    Component.text("You were outbid on ", NamedTextColor.YELLOW)
                        .append(listing.item.displayName())
                        .append(Component.text(". Your ", NamedTextColor.YELLOW))
                        .append(Component.text(plugin.economyManager.format(listing.currentBid), NamedTextColor.GOLD))
                        .append(Component.text(" has been refunded.", NamedTextColor.YELLOW)),
                    CommunicationsManager.Category.DEFAULT
                )
            }
        }

        player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f)
        plugin.commsManager.send(
            player,
            Component.text("Bid of ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(amount), NamedTextColor.GOLD))
                .append(Component.text(" placed! You are the highest bidder.", NamedTextColor.GREEN)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    fun cancelBidListing(player: Player, listingId: Int) {
        val listing = plugin.databaseManager.queryFirst(
            "SELECT * FROM auction_bid_listings WHERE id = ? AND seller_uuid = ?",
            listingId, player.uniqueId.toString()
        ) { rs -> mapBidListing(rs) }

        if (listing == null) {
            plugin.commsManager.send(player, Component.text("Bid listing not found.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        if (listing.currentBidderUuid != null) {
            plugin.commsManager.send(player, Component.text("You cannot cancel a bid listing that already has bids.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val rowsDeleted = plugin.databaseManager.executeUpdate(
            "DELETE FROM auction_bid_listings WHERE id = ? AND seller_uuid = ? AND current_bidder_uuid IS NULL",
            listingId, player.uniqueId.toString()
        )
        if (rowsDeleted == 0) {
            plugin.commsManager.send(player, Component.text("Cannot cancel — someone may have just placed a bid.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
            return
        }

        val leftover = player.inventory.addItem(listing.item)
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            plugin.commsManager.send(player, Component.text("Inventory full - item dropped at your feet.", NamedTextColor.YELLOW), CommunicationsManager.Category.DEFAULT)
        }

        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.5f, 1f)
        plugin.commsManager.send(player, Component.text("Bid listing cancelled. Item returned.", NamedTextColor.GREEN), CommunicationsManager.Category.DEFAULT)
    }

    fun getActiveBidListings(page: Int, pageSize: Int = 28): List<BidListing> {
        val offset = page * pageSize
        return plugin.databaseManager.query(
            "SELECT * FROM auction_bid_listings WHERE expires_at > ? ORDER BY listed_at DESC LIMIT ? OFFSET ?",
            System.currentTimeMillis(), pageSize, offset
        ) { rs -> mapBidListing(rs) }
    }

    fun getPlayerBidListings(uuid: UUID): List<BidListing> {
        return plugin.databaseManager.query(
            "SELECT * FROM auction_bid_listings WHERE seller_uuid = ? AND expires_at > ? ORDER BY listed_at DESC",
            uuid.toString(), System.currentTimeMillis()
        ) { rs -> mapBidListing(rs) }
    }

    fun getTotalBidListings(): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM auction_bid_listings WHERE expires_at > ?",
            System.currentTimeMillis()
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun checkExpiredBids() {
        val now = System.currentTimeMillis()
        val expired = plugin.databaseManager.query(
            "SELECT * FROM auction_bid_listings WHERE expires_at <= ?",
            now
        ) { rs -> mapBidListing(rs) }

        if (expired.isEmpty()) return

        plugin.databaseManager.transaction {
            for (listing in expired) {
                plugin.databaseManager.execute("DELETE FROM auction_bid_listings WHERE id = ?", listing.id)

                if (listing.currentBidderUuid != null) {
                    // Winner gets the item; seller gets paid
                    val tax = listing.currentBid * (taxPercent / 100.0)
                    val sellerPayout = listing.currentBid - tax
                    plugin.economyManager.deposit(listing.sellerUuid, sellerPayout)

                    // Give item to winner
                    val winner = Bukkit.getPlayer(listing.currentBidderUuid)
                    if (winner != null) {
                        val leftover = winner.inventory.addItem(listing.item)
                        if (leftover.isNotEmpty()) {
                            leftover.values.forEach { winner.world.dropItemNaturally(winner.location, it) }
                        }
                        plugin.commsManager.send(
                            winner,
                            Component.text("You won the bid on ", NamedTextColor.GREEN)
                                .append(listing.item.displayName())
                                .append(Component.text(" for ", NamedTextColor.GREEN))
                                .append(Component.text(plugin.economyManager.format(listing.currentBid), NamedTextColor.GOLD))
                                .append(Component.text("!", NamedTextColor.GREEN)),
                            CommunicationsManager.Category.DEFAULT
                        )
                    } else {
                        // Winner offline — move item to their expired items
                        plugin.databaseManager.execute(
                            "INSERT INTO auction_expired (owner_uuid, item, expired_at) VALUES (?, ?, ?)",
                            listing.currentBidderUuid.toString(), serializeItem(listing.item), now
                        )
                    }

                    val seller = Bukkit.getPlayer(listing.sellerUuid)
                    if (seller != null) {
                        plugin.commsManager.send(
                            seller,
                            Component.text("Your bid listing was won by ", NamedTextColor.GREEN)
                                .append(Component.text(listing.currentBidderName ?: "Unknown", NamedTextColor.WHITE))
                                .append(Component.text(" for ", NamedTextColor.GREEN))
                                .append(Component.text(plugin.economyManager.format(listing.currentBid), NamedTextColor.GOLD))
                                .append(Component.text(" (${plugin.economyManager.format(tax)} tax).", NamedTextColor.GRAY)),
                            CommunicationsManager.Category.DEFAULT
                        )
                    }
                } else {
                    // No bids — return item to seller
                    plugin.databaseManager.execute(
                        "INSERT INTO auction_expired (owner_uuid, item, expired_at) VALUES (?, ?, ?)",
                        listing.sellerUuid.toString(), serializeItem(listing.item), now
                    )

                    val seller = Bukkit.getPlayer(listing.sellerUuid)
                    if (seller != null) {
                        plugin.commsManager.send(
                            seller,
                            Component.text("Your bid listing received no bids. Use ", NamedTextColor.YELLOW)
                                .append(Component.text("/ah", NamedTextColor.GOLD))
                                .append(Component.text(" to claim it back.", NamedTextColor.YELLOW)),
                            CommunicationsManager.Category.DEFAULT
                        )
                    }
                }
            }
        }
    }

    fun getTotalListings(): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM auction_listings WHERE expires_at > ?",
            System.currentTimeMillis()
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun checkExpired() {
        val now = System.currentTimeMillis()
        val expired = plugin.databaseManager.query(
            "SELECT * FROM auction_listings WHERE expires_at <= ?",
            now
        ) { rs -> mapListing(rs) }

        if (expired.isEmpty()) return

        plugin.databaseManager.transaction {
            for (listing in expired) {
                // Move to expired table
                plugin.databaseManager.execute(
                    "INSERT INTO auction_expired (owner_uuid, item, expired_at) VALUES (?, ?, ?)",
                    listing.sellerUuid.toString(), serializeItem(listing.item), now
                )
                plugin.databaseManager.execute("DELETE FROM auction_listings WHERE id = ?", listing.id)

                // Notify if online
                val player = Bukkit.getPlayer(listing.sellerUuid)
                if (player != null) {
                    plugin.commsManager.send(
                        player,
                        Component.text("Your listing has expired. Use ", NamedTextColor.YELLOW)
                            .append(Component.text("/ah", NamedTextColor.GOLD))
                            .append(Component.text(" to claim it back.", NamedTextColor.YELLOW)),
                        CommunicationsManager.Category.DEFAULT
                    )
                }
            }
        }
    }

    // ---- GUIs ----

    fun openMainGui(player: Player, page: Int = 0) {
        val gui = CustomGui(MAIN_TITLE, 54)

        // Row 0 border
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())

        // Rows 1-4, columns 0 and 8 border
        for (row in 1..4) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }

        // Row 5 border
        for (i in 45..53) gui.inventory.setItem(i, BORDER.clone())

        // Fill remaining empty slots with filler
        for (row in 1..4) {
            for (col in 1..7) {
                val slot = row * 9 + col
                if (gui.inventory.getItem(slot) == null) gui.inventory.setItem(slot, FILLER.clone())
            }
        }

        // Listings
        val listings = getActiveListings(page)
        val listingSlots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) listingSlots.add(row * 9 + col)

        for ((index, listing) in listings.withIndex()) {
            if (index >= listingSlots.size) break
            val slot = listingSlots[index]
            gui.setItem(slot, createListingIcon(listing)) { p, _ ->
                openConfirmGui(p, listing)
            }
        }

        // Navigation - Row 5
        val totalListings = getTotalListings()
        val totalPages = maxOf(1, (totalListings + 27) / 28)

        // Previous page (slot 46)
        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { meta ->
                meta.displayName(
                    Component.text("Previous Page", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            gui.setItem(46, prev) { p, _ -> openMainGui(p, page - 1) }
        }

        // Active Bids (slot 47)
        val bidsBtn = ItemStack(Material.GOLDEN_HELMET)
        bidsBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Active Bids", TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click to browse bid auctions", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(47, bidsBtn) { p, _ -> openBidsGui(p) }

        // Your Listings (slot 49)
        val yourListings = ItemStack(Material.BOOK)
        yourListings.editMeta { meta ->
            meta.displayName(
                Component.text("Your Listings", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click to manage your listings", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(49, yourListings) { p, _ -> openYourListingsGui(p) }

        // Expired Items (slot 51)
        val expiredBtn = ItemStack(Material.CLOCK)
        expiredBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Expired Items", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click to claim expired items", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(51, expiredBtn) { p, _ -> openExpiredGui(p) }

        // Next page (slot 52)
        if (page < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { meta ->
                meta.displayName(
                    Component.text("Next Page", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            gui.setItem(52, next) { p, _ -> openMainGui(p, page + 1) }
        }

        // Page indicator in center of top border
        val pageInfo = ItemStack(Material.PAPER)
        pageInfo.editMeta { meta ->
            meta.displayName(
                Component.text("Page ${page + 1}/$totalPages", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.text("  $totalListings total listing(s)", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.inventory.setItem(4, pageInfo)

        playerPages[player.uniqueId] = page
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun openConfirmGui(player: Player, listing: AuctionListing) {
        val gui = CustomGui(CONFIRM_TITLE, 27)

        // Left half red glass (cancel)
        val redGlass = ItemStack(Material.RED_STAINED_GLASS_PANE)
        redGlass.editMeta { meta ->
            meta.displayName(
                Component.text("Cancel", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
        }

        // Right half green glass (confirm)
        val greenGlass = ItemStack(Material.LIME_STAINED_GLASS_PANE)
        greenGlass.editMeta { meta ->
            meta.displayName(
                Component.text("Confirm Purchase", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
        }

        for (i in 0 until 27) {
            val col = i % 9
            if (col < 4) {
                gui.setItem(i, redGlass.clone()) { p, _ ->
                    p.closeInventory()
                    openMainGui(p)
                }
            } else if (col > 4) {
                gui.setItem(i, greenGlass.clone()) { p, _ ->
                    p.closeInventory()
                    buyItem(p, listing.id)
                }
            }
        }

        // Center item (slot 13)
        val displayItem = listing.item.clone()
        displayItem.editMeta { meta ->
            val existingLore = meta.lore() ?: mutableListOf()
            val newLore = existingLore.toMutableList()
            newLore.add(Component.empty())
            newLore.add(
                Component.text("  Price: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(plugin.economyManager.format(listing.price), NamedTextColor.GOLD))
            )
            newLore.add(
                Component.text("  Seller: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(listing.sellerName, NamedTextColor.WHITE))
            )
            meta.lore(newLore)
        }
        gui.inventory.setItem(13, displayItem)

        plugin.guiManager.open(player, gui)
    }

    private fun openYourListingsGui(player: Player, page: Int = 0) {
        val gui = CustomGui(YOUR_LISTINGS_TITLE, 54)

        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }
        for (i in 45..53) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            for (col in 1..7) {
                val slot = row * 9 + col
                if (gui.inventory.getItem(slot) == null) gui.inventory.setItem(slot, FILLER.clone())
            }
        }

        // Combine sell and bid listings, sorted by listed_at desc
        data class CombinedEntry(val item: ItemStack, val expiresAt: Long, val listedAt: Long, val onClick: (Player) -> Unit)

        val sellListings = getPlayerListings(player.uniqueId).map { listing ->
            val icon = listing.item.clone()
            icon.editMeta { meta ->
                val newLore = (meta.lore() ?: mutableListOf()).toMutableList()
                newLore.add(Component.empty())
                newLore.add(Component.text("  [SELL]", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                newLore.add(Component.text("  Price: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(plugin.economyManager.format(listing.price), NamedTextColor.GOLD)))
                newLore.add(Component.text("  Time left: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(formatTimeLeft(listing.expiresAt), NamedTextColor.YELLOW)))
                newLore.add(Component.empty())
                newLore.add(Component.text("  Click to cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                meta.lore(newLore)
            }
            CombinedEntry(icon, listing.expiresAt, listing.listedAt) { p -> p.closeInventory(); cancelListing(p, listing.id) }
        }

        val bidListings = getPlayerBidListings(player.uniqueId).map { listing ->
            val icon = listing.item.clone()
            icon.editMeta { meta ->
                val newLore = (meta.lore() ?: mutableListOf()).toMutableList()
                newLore.add(Component.empty())
                newLore.add(Component.text("  [BID]", TextColor.color(0xFFAA00)).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
                newLore.add(Component.text("  Current Bid: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(plugin.economyManager.format(listing.currentBid), NamedTextColor.GOLD)))
                if (listing.currentBidderName != null) {
                    newLore.add(Component.text("  Top Bidder: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(listing.currentBidderName, NamedTextColor.WHITE)))
                }
                newLore.add(Component.text("  Time left: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false).append(Component.text(formatTimeLeft(listing.expiresAt), NamedTextColor.YELLOW)))
                newLore.add(Component.empty())
                if (listing.currentBidderUuid == null) {
                    newLore.add(Component.text("  Click to cancel", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                } else {
                    newLore.add(Component.text("  Bids placed — cannot cancel", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
                }
                meta.lore(newLore)
            }
            CombinedEntry(icon, listing.expiresAt, listing.listedAt) { p ->
                if (listing.currentBidderUuid == null) {
                    p.closeInventory()
                    cancelBidListing(p, listing.id)
                } else {
                    plugin.commsManager.send(p, Component.text("You cannot cancel a bid listing that already has bids.", NamedTextColor.RED), CommunicationsManager.Category.DEFAULT)
                }
            }
        }

        val allEntries = (sellListings + bidListings).sortedByDescending { it.listedAt }
        val pageSize = 28
        val pageEntries = allEntries.drop(page * pageSize).take(pageSize)

        val listingSlots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) listingSlots.add(row * 9 + col)

        for ((index, entry) in pageEntries.withIndex()) {
            if (index >= listingSlots.size) break
            val slot = listingSlots[index]
            val captured = entry
            gui.setItem(slot, entry.item) { p, _ -> captured.onClick(p) }
        }

        val back = ItemStack(Material.BARRIER)
        back.editMeta { meta -> meta.displayName(Component.text("Back", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)) }
        gui.setItem(49, back) { p, _ -> openMainGui(p) }

        val totalPages = maxOf(1, (allEntries.size + pageSize - 1) / pageSize)
        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { meta -> meta.displayName(Component.text("Previous Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(46, prev) { p, _ -> openYourListingsGui(p, page - 1) }
        }
        if (page < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { meta -> meta.displayName(Component.text("Next Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(52, next) { p, _ -> openYourListingsGui(p, page + 1) }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun openExpiredGui(player: Player, page: Int = 0) {
        val gui = CustomGui(EXPIRED_TITLE, 54)

        // Border
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }
        for (i in 45..53) gui.inventory.setItem(i, BORDER.clone())

        // Fill
        for (row in 1..4) {
            for (col in 1..7) {
                val slot = row * 9 + col
                if (gui.inventory.getItem(slot) == null) gui.inventory.setItem(slot, FILLER.clone())
            }
        }

        val expired = getExpiredItems(player.uniqueId)
        val pageSize = 28
        val startIndex = page * pageSize
        val pageExpired = expired.drop(startIndex).take(pageSize)

        val listingSlots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) listingSlots.add(row * 9 + col)

        for ((index, item) in pageExpired.withIndex()) {
            if (index >= listingSlots.size) break
            val slot = listingSlots[index]

            val icon = item.item.clone()
            icon.editMeta { meta ->
                val existingLore = meta.lore() ?: mutableListOf()
                val newLore = existingLore.toMutableList()
                newLore.add(Component.empty())
                newLore.add(
                    Component.text("  Click to claim", NamedTextColor.GREEN)
                        .decoration(TextDecoration.ITALIC, false)
                )
                meta.lore(newLore)
            }
            gui.setItem(slot, icon) { p, _ ->
                p.closeInventory()
                claimExpired(p, item.id)
            }
        }

        // Back button (slot 49)
        val back = ItemStack(Material.BARRIER)
        back.editMeta { meta ->
            meta.displayName(
                Component.text("Back", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
        }
        gui.setItem(49, back) { p, _ -> openMainGui(p) }

        // Pagination
        val totalPages = maxOf(1, (expired.size + pageSize - 1) / pageSize)
        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { meta ->
                meta.displayName(Component.text("Previous Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
            gui.setItem(46, prev) { p, _ -> openExpiredGui(p, page - 1) }
        }
        if (page < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { meta ->
                meta.displayName(Component.text("Next Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
            gui.setItem(52, next) { p, _ -> openExpiredGui(p, page + 1) }
        }

        plugin.guiManager.open(player, gui)
    }

    fun openBidsGui(player: Player, page: Int = 0) {
        val gui = CustomGui(BIDS_TITLE, 54)

        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }
        for (i in 45..53) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            for (col in 1..7) {
                val slot = row * 9 + col
                if (gui.inventory.getItem(slot) == null) gui.inventory.setItem(slot, FILLER.clone())
            }
        }

        val listings = getActiveBidListings(page)
        val listingSlots = mutableListOf<Int>()
        for (row in 1..4) for (col in 1..7) listingSlots.add(row * 9 + col)

        for ((index, listing) in listings.withIndex()) {
            if (index >= listingSlots.size) break
            val slot = listingSlots[index]
            gui.setItem(slot, createBidListingIcon(listing)) { p, _ ->
                promptBidInput(p, listing)
            }
        }

        val totalBids = getTotalBidListings()
        val totalPages = maxOf(1, (totalBids + 27) / 28)

        if (page > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { meta -> meta.displayName(Component.text("Previous Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(46, prev) { p, _ -> openBidsGui(p, page - 1) }
        }

        val back = ItemStack(Material.BARRIER)
        back.editMeta { meta ->
            meta.displayName(Component.text("Back", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
        }
        gui.setItem(49, back) { p, _ -> openMainGui(p) }

        if (page < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { meta -> meta.displayName(Component.text("Next Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)) }
            gui.setItem(52, next) { p, _ -> openBidsGui(p, page + 1) }
        }

        val pageInfo = ItemStack(Material.PAPER)
        pageInfo.editMeta { meta ->
            meta.displayName(Component.text("Page ${page + 1}/$totalPages", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(Component.text("  $totalBids active bid(s)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)))
        }
        gui.inventory.setItem(4, pageInfo)

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun promptBidInput(player: Player, listing: BidListing) {
        pendingBidInputs[player.uniqueId] = listing
        player.closeInventory()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f)

        val minBid = if (listing.currentBidderUuid != null) listing.currentBid + 1.0 else listing.startingPrice
        plugin.commsManager.send(
            player,
            Component.text("Type your bid amount in chat. Current bid: ", NamedTextColor.YELLOW)
                .append(Component.text(plugin.economyManager.format(listing.currentBid), NamedTextColor.GOLD))
                .append(Component.text(". Minimum: ", NamedTextColor.YELLOW))
                .append(Component.text(plugin.economyManager.format(minBid), NamedTextColor.GOLD))
                .append(Component.text(". Type 'cancel' to abort.", NamedTextColor.GRAY)),
            CommunicationsManager.Category.DEFAULT
        )
    }

    private fun createBidListingIcon(listing: BidListing): ItemStack {
        val icon = listing.item.clone()
        icon.editMeta { meta ->
            val existingLore = meta.lore() ?: mutableListOf()
            val newLore = existingLore.toMutableList()
            newLore.add(Component.empty())
            newLore.add(
                Component.text("  [BID]", TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            newLore.add(
                Component.text("  Current Bid: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(plugin.economyManager.format(listing.currentBid), NamedTextColor.GOLD))
            )
            if (listing.currentBidderName != null) {
                newLore.add(
                    Component.text("  Top Bidder: ", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(listing.currentBidderName, NamedTextColor.WHITE))
                )
            } else {
                newLore.add(
                    Component.text("  No bids yet", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            newLore.add(
                Component.text("  Seller: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(listing.sellerName, NamedTextColor.WHITE))
            )
            newLore.add(
                Component.text("  Time left: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(formatTimeLeft(listing.expiresAt), NamedTextColor.YELLOW))
            )
            newLore.add(Component.empty())
            newLore.add(
                Component.text("  Click to place a bid", TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(newLore)
        }
        return icon
    }

    private fun createListingIcon(listing: AuctionListing): ItemStack {
        val icon = listing.item.clone()
        icon.editMeta { meta ->
            val existingLore = meta.lore() ?: mutableListOf()
            val newLore = existingLore.toMutableList()
            newLore.add(Component.empty())
            newLore.add(
                Component.text("  Price: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(plugin.economyManager.format(listing.price), NamedTextColor.GOLD))
            )
            newLore.add(
                Component.text("  Seller: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(listing.sellerName, NamedTextColor.WHITE))
            )
            newLore.add(
                Component.text("  Time left: ", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(formatTimeLeft(listing.expiresAt), NamedTextColor.YELLOW))
            )
            newLore.add(Component.empty())
            newLore.add(
                Component.text("  Click to purchase", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(newLore)
        }
        return icon
    }

    private fun formatTimeLeft(expiresAt: Long): String {
        val remaining = expiresAt - System.currentTimeMillis()
        if (remaining <= 0) return "Expired"
        val hours = remaining / 3_600_000
        val minutes = (remaining % 3_600_000) / 60_000
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

}
