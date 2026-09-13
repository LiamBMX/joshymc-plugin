package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.scheduler.BukkitTask
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Player-created giveaways: browse/enter via `/giveaway` (alias `/gwy`), create through a
 * GUI flow that stages prize items (hold item + click, matching the existing Auction/Crate
 * "hold item then click" convention instead of raw deposit slots), escrows Coins immediately,
 * and settles automatically after a fixed duration with a fair random winner.
 */
class GiveawayManager(private val plugin: Joshymc) : Listener {

    companion object {
        private val BORDER = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private const val PAGE_SIZE = 28
        private val CONTENT_SLOTS: List<Int> = (1..4).flatMap { row -> (1..7).map { col -> row * 9 + col } }

        private fun title(text: String, color: TextColor = TextColor.color(0x55FFFF)): Component =
            Component.text("         ")
                .append(Component.text(text, color))
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
    }

    data class Giveaway(
        val id: Int,
        val creatorUuid: UUID,
        val creatorName: String,
        val title: String?,
        val description: String?,
        val coins: Double,
        val createdAt: Long,
        val endsAt: Long,
        val status: String,
        val winnerUuid: UUID?,
        val winnerName: String?,
        val pendingDeliveryUuid: UUID?
    )

    data class GiveawayItemRow(val rowId: Int, val giveawayId: Int, val item: ItemStack)

    enum class ChatPromptType { COINS, TITLE, DESCRIPTION }

    class PendingCreation {
        val items = mutableListOf<ItemStack>()
        var coins: Double = 0.0
        var title: String? = null
        var description: String? = null
    }

    // Config
    private var durationHours = 24
    private var maxActivePerPlayer = 1
    private var maxActiveTotal = -1
    private var allowCoins = true
    private var allowItems = true
    private var allowDescription = true
    private var maxDescriptionLength = 120
    private var maxTitleLength = 32
    private var maxPrizeItems = 27

    private val pendingCreations = ConcurrentHashMap<UUID, PendingCreation>()
    val pendingChatPrompts = ConcurrentHashMap<UUID, ChatPromptType>()
    private val awaitingChatInput = ConcurrentHashMap.newKeySet<UUID>()
    private var tickTask: BukkitTask? = null

    fun start() {
        val cfg = plugin.config
        durationHours = cfg.getInt("giveaways.duration-hours", 24).coerceAtLeast(1)
        maxActivePerPlayer = cfg.getInt("giveaways.max-active-per-player", 1)
        maxActiveTotal = cfg.getInt("giveaways.max-active-total", -1)
        allowCoins = cfg.getBoolean("giveaways.allow-coins", true)
        allowItems = cfg.getBoolean("giveaways.allow-items", true)
        allowDescription = cfg.getBoolean("giveaways.allow-description", true)
        maxDescriptionLength = cfg.getInt("giveaways.max-description-length", 120).coerceAtLeast(1)
        maxTitleLength = cfg.getInt("giveaways.max-title-length", 32).coerceAtLeast(1)
        maxPrizeItems = cfg.getInt("giveaways.max-prize-items", 27).coerceIn(1, 27)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS giveaways (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                creator_uuid TEXT NOT NULL,
                creator_name TEXT NOT NULL,
                title TEXT,
                description TEXT,
                coins REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                ends_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                winner_uuid TEXT,
                winner_name TEXT,
                pending_delivery_uuid TEXT
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS giveaway_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                giveaway_id INTEGER NOT NULL,
                item TEXT NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS giveaway_entries (
                giveaway_id INTEGER NOT NULL,
                player_uuid TEXT NOT NULL,
                entered_at INTEGER NOT NULL,
                PRIMARY KEY (giveaway_id, player_uuid)
            )
        """.trimIndent())

        // Catch up on anything that expired while the server was offline, then tick every 30s.
        processExpired()
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { processExpired() }, 600L, 600L)

        plugin.logger.info("[Giveaway] GiveawayManager started (duration: ${durationHours}h).")
    }

    fun stop() {
        tickTask?.cancel()
        tickTask = null
        // Clean shutdown/reload safety net: return any in-progress creation drafts for
        // players who are still online rather than silently losing staged prizes.
        for ((uuid, pending) in pendingCreations) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) returnPending(player, pending)
        }
        pendingCreations.clear()
        pendingChatPrompts.clear()
        awaitingChatInput.clear()
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))

    // ---- Row mappers ----

    private fun mapGiveaway(rs: java.sql.ResultSet): Giveaway {
        val winnerUuidStr = rs.getString("winner_uuid")
        val pendingStr = rs.getString("pending_delivery_uuid")
        return Giveaway(
            id = rs.getInt("id"),
            creatorUuid = UUID.fromString(rs.getString("creator_uuid")),
            creatorName = rs.getString("creator_name"),
            title = rs.getString("title"),
            description = rs.getString("description"),
            coins = rs.getDouble("coins"),
            createdAt = rs.getLong("created_at"),
            endsAt = rs.getLong("ends_at"),
            status = rs.getString("status"),
            winnerUuid = winnerUuidStr?.let { UUID.fromString(it) },
            winnerName = rs.getString("winner_name"),
            pendingDeliveryUuid = pendingStr?.let { UUID.fromString(it) }
        )
    }

    private fun mapItemRow(rs: java.sql.ResultSet): GiveawayItemRow {
        return GiveawayItemRow(rs.getInt("id"), rs.getInt("giveaway_id"), deserializeItem(rs.getString("item")))
    }

    // ---- Queries ----

    fun getActiveGiveaways(page: Int, pageSize: Int = PAGE_SIZE): List<Giveaway> {
        val offset = page * pageSize
        return plugin.databaseManager.query(
            "SELECT * FROM giveaways WHERE status = 'ACTIVE' ORDER BY ends_at ASC LIMIT ? OFFSET ?",
            pageSize, offset
        ) { rs -> mapGiveaway(rs) }
    }

    fun getTotalActive(): Int = plugin.databaseManager.queryFirst(
        "SELECT COUNT(*) as cnt FROM giveaways WHERE status = 'ACTIVE'"
    ) { it.getInt("cnt") } ?: 0

    fun getGiveaway(id: Int): Giveaway? = plugin.databaseManager.queryFirst(
        "SELECT * FROM giveaways WHERE id = ?", id
    ) { mapGiveaway(it) }

    fun getItemsFor(giveawayId: Int): List<GiveawayItemRow> = plugin.databaseManager.query(
        "SELECT * FROM giveaway_items WHERE giveaway_id = ?", giveawayId
    ) { mapItemRow(it) }

    fun getEntryCount(giveawayId: Int): Int = plugin.databaseManager.queryFirst(
        "SELECT COUNT(*) as cnt FROM giveaway_entries WHERE giveaway_id = ?", giveawayId
    ) { it.getInt("cnt") } ?: 0

    fun hasEntered(giveawayId: Int, uuid: UUID): Boolean = plugin.databaseManager.queryFirst(
        "SELECT 1 FROM giveaway_entries WHERE giveaway_id = ? AND player_uuid = ?", giveawayId, uuid.toString()
    ) { true } ?: false

    fun getActiveCountFor(uuid: UUID): Int = plugin.databaseManager.queryFirst(
        "SELECT COUNT(*) as cnt FROM giveaways WHERE creator_uuid = ? AND status = 'ACTIVE'", uuid.toString()
    ) { it.getInt("cnt") } ?: 0

    fun getMyGiveaways(uuid: UUID): List<Giveaway> = plugin.databaseManager.query(
        "SELECT * FROM giveaways WHERE creator_uuid = ? AND status = 'ACTIVE' ORDER BY created_at DESC", uuid.toString()
    ) { mapGiveaway(it) }

    fun hasPendingDelivery(uuid: UUID): Boolean = plugin.databaseManager.queryFirst(
        "SELECT 1 FROM giveaway_items gi JOIN giveaways g ON gi.giveaway_id = g.id WHERE g.pending_delivery_uuid = ? LIMIT 1",
        uuid.toString()
    ) { true } ?: false

    // ---- Entry ----

    fun enterGiveaway(player: Player, id: Int) {
        val giveaway = getGiveaway(id)
        if (giveaway == null || giveaway.status != "ACTIVE" || giveaway.endsAt <= System.currentTimeMillis()) {
            plugin.commsManager.send(player, Component.text("That giveaway is no longer active.", NamedTextColor.RED))
            return
        }
        if (giveaway.creatorUuid == player.uniqueId) {
            plugin.commsManager.send(player, Component.text("You cannot enter your own giveaway.", NamedTextColor.RED))
            return
        }

        val rows = plugin.databaseManager.executeUpdate(
            "INSERT OR IGNORE INTO giveaway_entries (giveaway_id, player_uuid, entered_at) VALUES (?, ?, ?)",
            id, player.uniqueId.toString(), System.currentTimeMillis()
        )
        if (rows == 0) {
            plugin.commsManager.send(player, Component.text("You have already entered this giveaway.", NamedTextColor.YELLOW))
            return
        }

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f)
        plugin.commsManager.send(
            player,
            Component.text("You entered ", NamedTextColor.GREEN)
                .append(Component.text(giveaway.title ?: "${giveaway.creatorName}'s giveaway", NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.GREEN))
        )
        plugin.logger.info("[Giveaway] ${player.name} entered giveaway #$id")
    }

    // ---- Cancellation ----

    fun cancelGiveaway(player: Player, id: Int) {
        val giveaway = getGiveaway(id)
        if (giveaway == null || giveaway.creatorUuid != player.uniqueId) {
            plugin.commsManager.send(player, Component.text("Giveaway not found.", NamedTextColor.RED))
            return
        }

        // Atomic ACTIVE -> CANCELLED transition guards against a cancel racing an
        // expiry tick that's already ending the same giveaway.
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE giveaways SET status = 'CANCELLED', pending_delivery_uuid = ? WHERE id = ? AND status = 'ACTIVE'",
            player.uniqueId.toString(), id
        )
        if (rows == 0) {
            plugin.commsManager.send(player, Component.text("That giveaway can no longer be cancelled.", NamedTextColor.RED))
            return
        }

        if (giveaway.coins > 0) plugin.economyManager.deposit(player.uniqueId, giveaway.coins)
        deliverPending(player)

        plugin.commsManager.send(player, Component.text("Giveaway #$id cancelled. Prize refunded.", NamedTextColor.GREEN))
        plugin.logger.info("[Giveaway] ${player.name} cancelled giveaway #$id")
    }

    // ---- Expiry / winner selection ----

    fun processExpired() {
        val now = System.currentTimeMillis()
        val expired = plugin.databaseManager.query(
            "SELECT * FROM giveaways WHERE status = 'ACTIVE' AND ends_at <= ?", now
        ) { mapGiveaway(it) }

        for (giveaway in expired) endGiveaway(giveaway)
    }

    private fun endGiveaway(giveaway: Giveaway) {
        val entrants = plugin.databaseManager.query(
            "SELECT player_uuid FROM giveaway_entries WHERE giveaway_id = ?", giveaway.id
        ) { it.getString("player_uuid") }

        if (entrants.isEmpty()) {
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE giveaways SET status = 'REFUNDED', pending_delivery_uuid = ? WHERE id = ? AND status = 'ACTIVE'",
                giveaway.creatorUuid.toString(), giveaway.id
            )
            if (rows == 0) return // already processed elsewhere

            if (giveaway.coins > 0) plugin.economyManager.deposit(giveaway.creatorUuid, giveaway.coins)
            plugin.logger.info("[Giveaway] Giveaway #${giveaway.id} ended with no entries — refunded to ${giveaway.creatorName}")

            val creator = Bukkit.getPlayer(giveaway.creatorUuid)
            if (creator != null) {
                plugin.commsManager.send(
                    creator,
                    Component.text("Your giveaway ", NamedTextColor.YELLOW)
                        .append(Component.text(giveaway.title ?: "#${giveaway.id}", NamedTextColor.AQUA))
                        .append(Component.text(" ended with no entries. The prize has been refunded.", NamedTextColor.YELLOW))
                )
                deliverPending(creator)
            }
            return
        }

        val winnerUuid = UUID.fromString(entrants[ThreadLocalRandom.current().nextInt(entrants.size)])
        val winnerName = Bukkit.getOfflinePlayer(winnerUuid).name ?: "Unknown"

        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE giveaways SET status = 'ENDED', winner_uuid = ?, winner_name = ?, pending_delivery_uuid = ? WHERE id = ? AND status = 'ACTIVE'",
            winnerUuid.toString(), winnerName, winnerUuid.toString(), giveaway.id
        )
        if (rows == 0) return // already processed elsewhere

        if (giveaway.coins > 0) plugin.economyManager.deposit(winnerUuid, giveaway.coins)
        plugin.logger.info("[Giveaway] Giveaway #${giveaway.id} ended. Winner: $winnerName")

        val winner = Bukkit.getPlayer(winnerUuid)
        if (winner != null) {
            plugin.commsManager.send(
                winner,
                Component.text("You won ", NamedTextColor.GOLD)
                    .append(Component.text(giveaway.creatorName, NamedTextColor.AQUA))
                    .append(Component.text("'s giveaway!", NamedTextColor.GOLD)),
                CommunicationsManager.Category.ECONOMY
            )
            winner.playSound(winner.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
            deliverPending(winner)
        }

        val creator = Bukkit.getPlayer(giveaway.creatorUuid)
        if (creator != null) {
            plugin.commsManager.send(
                creator,
                Component.text("Your giveaway ended. Winner: ", NamedTextColor.YELLOW)
                    .append(Component.text(winnerName, NamedTextColor.AQUA))
            )
        }
    }

    /**
     * Delivers any prize items held for [player] (as the winner, the refunded creator, or a
     * cancelling creator). Coins are deposited straight to the balance table at settlement
     * time regardless of online status — only physical items need inventory space, so those
     * stay parked in `giveaway_items` until they fit, and are never dropped on the ground.
     */
    fun deliverPending(player: Player) {
        val rows = plugin.databaseManager.query(
            "SELECT gi.id as row_id, gi.item as item FROM giveaway_items gi JOIN giveaways g ON gi.giveaway_id = g.id WHERE g.pending_delivery_uuid = ?",
            player.uniqueId.toString()
        ) { rs -> Pair(rs.getInt("row_id"), rs.getString("item")) }

        if (rows.isEmpty()) return

        var delivered = 0
        for ((rowId, itemBase64) in rows) {
            val item = deserializeItem(itemBase64)
            val leftover = player.inventory.addItem(item)
            if (leftover.isEmpty()) {
                plugin.databaseManager.execute("DELETE FROM giveaway_items WHERE id = ?", rowId)
                delivered++
            } else {
                // Only the part that didn't fit stays pending; what we already handed
                // over is gone from the row so it can never be delivered twice.
                plugin.databaseManager.execute(
                    "UPDATE giveaway_items SET item = ? WHERE id = ?",
                    serializeItem(leftover.values.first()), rowId
                )
            }
        }

        plugin.databaseManager.execute(
            "UPDATE giveaways SET pending_delivery_uuid = NULL WHERE pending_delivery_uuid = ? AND id NOT IN (SELECT giveaway_id FROM giveaway_items)",
            player.uniqueId.toString()
        )

        if (delivered > 0) {
            plugin.commsManager.send(player, Component.text("Delivered $delivered pending giveaway prize item(s).", NamedTextColor.GREEN))
        }
        if (hasPendingDelivery(player.uniqueId)) {
            plugin.commsManager.send(
                player,
                Component.text("Some prize items didn't fit — free up inventory space and reopen /giveaway to claim them.", NamedTextColor.YELLOW)
            )
        }
    }

    // ---- Player lifecycle ----

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { deliverPending(player) }, 20L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        pendingChatPrompts.remove(player.uniqueId)
        awaitingChatInput.remove(player.uniqueId)
        val pending = pendingCreations.remove(player.uniqueId) ?: return
        returnPending(player, pending)
    }

    private fun returnPending(player: Player, pending: PendingCreation) {
        if (pending.coins > 0) plugin.economyManager.deposit(player.uniqueId, pending.coins)
        for (item in pending.items) {
            val leftover = player.inventory.addItem(item)
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
    }

    // ---- Creation flow ----

    fun beginCreation(player: Player) {
        if (!player.hasPermission("joshymc.giveaway.create")) {
            plugin.commsManager.send(player, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        if (!allowItems && !allowCoins) {
            plugin.commsManager.send(player, Component.text("Giveaways are not accepting prizes right now.", NamedTextColor.RED))
            return
        }
        if (maxActivePerPlayer > 0 && getActiveCountFor(player.uniqueId) >= maxActivePerPlayer) {
            plugin.commsManager.send(player, Component.text("You already have the maximum of $maxActivePerPlayer active giveaway(s).", NamedTextColor.RED))
            return
        }
        if (maxActiveTotal > 0 && getTotalActive() >= maxActiveTotal) {
            plugin.commsManager.send(player, Component.text("The server has reached its giveaway limit. Try again later.", NamedTextColor.RED))
            return
        }
        pendingCreations.putIfAbsent(player.uniqueId, PendingCreation())
        openCreateGui(player)
    }

    private fun addHeldItemToPending(player: Player) {
        val pending = pendingCreations[player.uniqueId] ?: return
        if (!allowItems) {
            plugin.commsManager.send(player, Component.text("Item prizes are disabled.", NamedTextColor.RED))
            return
        }
        if (pending.items.size >= maxPrizeItems) {
            plugin.commsManager.send(player, Component.text("You can add at most $maxPrizeItems prize item stack(s).", NamedTextColor.RED))
            return
        }
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to add as a prize.", NamedTextColor.RED))
            return
        }
        pending.items.add(held.clone())
        player.inventory.setItemInMainHand(null)
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f)
    }

    private fun removePendingItem(player: Player, index: Int) {
        val pending = pendingCreations[player.uniqueId] ?: return
        if (index < 0 || index >= pending.items.size) return
        val item = pending.items.removeAt(index)
        val leftover = player.inventory.addItem(item)
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    fun promptCoins(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.COINS
        awaitingChatInput.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type the amount of Coins to add to the prize (or 'cancel'):", NamedTextColor.YELLOW))
    }

    fun promptTitle(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.TITLE
        awaitingChatInput.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type a title for your giveaway, up to $maxTitleLength characters (or 'cancel'):", NamedTextColor.YELLOW))
    }

    fun promptDescription(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.DESCRIPTION
        awaitingChatInput.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type a description for your giveaway, up to $maxDescriptionLength characters (or 'cancel'):", NamedTextColor.YELLOW))
    }

    fun handleChatInput(player: Player, type: ChatPromptType, raw: String) {
        val pending = pendingCreations[player.uniqueId] ?: return

        if (raw.equals("cancel", ignoreCase = true)) {
            plugin.commsManager.send(player, Component.text("Cancelled.", NamedTextColor.GRAY))
            openCreateGui(player)
            return
        }

        when (type) {
            ChatPromptType.COINS -> {
                val amount = plugin.economyManager.parseAmount(raw)
                if (amount == null || amount < 0) {
                    plugin.commsManager.send(player, Component.text("Invalid amount.", NamedTextColor.RED))
                    openCreateGui(player)
                    return
                }
                // Refund whatever was previously staged before re-withdrawing the new amount
                // so changing the Coins prize never double-charges the creator.
                if (pending.coins > 0) plugin.economyManager.deposit(player.uniqueId, pending.coins)
                if (!plugin.economyManager.withdraw(player.uniqueId, amount)) {
                    plugin.commsManager.send(player, Component.text("You don't have that much money.", NamedTextColor.RED))
                    pending.coins = 0.0
                    openCreateGui(player)
                    return
                }
                pending.coins = amount
                plugin.commsManager.send(player, Component.text("Added ${plugin.economyManager.format(amount)} to the giveaway prize.", NamedTextColor.GREEN))
            }
            ChatPromptType.TITLE -> {
                pending.title = raw.trim().take(maxTitleLength)
                plugin.commsManager.send(player, Component.text("Title set.", NamedTextColor.GREEN))
            }
            ChatPromptType.DESCRIPTION -> {
                pending.description = raw.trim().take(maxDescriptionLength)
                plugin.commsManager.send(player, Component.text("Description set.", NamedTextColor.GREEN))
            }
        }
        openCreateGui(player)
    }

    private fun confirmCreation(player: Player) {
        val pending = pendingCreations[player.uniqueId] ?: run {
            player.closeInventory()
            return
        }
        if (pending.items.isEmpty() && pending.coins <= 0) {
            plugin.commsManager.send(player, Component.text("Add at least one prize item or some Coins first.", NamedTextColor.RED))
            return
        }
        if (maxActivePerPlayer > 0 && getActiveCountFor(player.uniqueId) >= maxActivePerPlayer) {
            plugin.commsManager.send(player, Component.text("You already have the maximum of $maxActivePerPlayer active giveaway(s).", NamedTextColor.RED))
            return
        }
        if (maxActiveTotal > 0 && getTotalActive() >= maxActiveTotal) {
            plugin.commsManager.send(player, Component.text("The server has reached its giveaway limit. Try again later.", NamedTextColor.RED))
            return
        }

        pendingCreations.remove(player.uniqueId)

        val now = System.currentTimeMillis()
        val endsAt = now + durationHours * 3_600_000L

        plugin.databaseManager.execute(
            "INSERT INTO giveaways (creator_uuid, creator_name, title, description, coins, created_at, ends_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')",
            player.uniqueId.toString(), player.name, pending.title, pending.description, pending.coins, now, endsAt
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() as id") { it.getInt("id") } ?: -1
        for (item in pending.items) {
            plugin.databaseManager.execute("INSERT INTO giveaway_items (giveaway_id, item) VALUES (?, ?)", id, serializeItem(item))
        }

        player.closeInventory()
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f)
        plugin.commsManager.send(player, Component.text("Giveaway #$id created! It will run for ${durationHours}h.", NamedTextColor.GREEN))
        plugin.logger.info("[Giveaway] ${player.name} created giveaway #$id (items=${pending.items.size}, coins=${pending.coins})")

        Bukkit.broadcast(
            Component.text("🎁 ", NamedTextColor.GOLD)
                .append(Component.text(player.name, NamedTextColor.AQUA))
                .append(Component.text(" started a giveaway! Use ", NamedTextColor.YELLOW))
                .append(Component.text("/giveaway", NamedTextColor.GREEN))
                .append(Component.text(" to enter.", NamedTextColor.YELLOW))
        )
    }

    // ---- Display helpers ----

    private fun simpleItem(material: Material, name: String, color: NamedTextColor, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        item.editMeta { meta ->
            meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
            if (lore.isNotEmpty()) meta.lore(listOf(Component.empty()) + lore)
        }
        return item
    }

    private fun describeItem(item: ItemStack): String {
        val name = item.itemMeta?.takeIf { it.hasDisplayName() }?.let { meta ->
            PlainTextComponentSerializer.plainText().serialize(meta.displayName()!!)
        } ?: item.type.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }
        return "${item.amount}x $name"
    }

    private fun prizeSummary(items: List<GiveawayItemRow>, coins: Double): String {
        val parts = mutableListOf<String>()
        if (items.isNotEmpty()) {
            parts.add(if (items.size == 1) describeItem(items[0].item) else "${items.size} items")
        }
        if (coins > 0) parts.add(plugin.economyManager.format(coins))
        return if (parts.isEmpty()) "Nothing" else parts.joinToString(" + ")
    }

    private fun formatTimeLeft(endsAt: Long): String {
        val remaining = endsAt - System.currentTimeMillis()
        if (remaining <= 0) return "Ending..."
        val hours = remaining / 3_600_000
        val minutes = (remaining % 3_600_000) / 60_000
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun buildListingIcon(viewer: Player, giveaway: Giveaway): ItemStack {
        val items = getItemsFor(giveaway.id)
        val icon = when {
            items.size == 1 -> items[0].item.clone()
            items.isEmpty() && giveaway.coins > 0 -> ItemStack(Material.GOLD_INGOT)
            else -> ItemStack(Material.CHEST)
        }
        icon.amount = 1

        val entered = hasEntered(giveaway.id, viewer.uniqueId)
        val isOwn = giveaway.creatorUuid == viewer.uniqueId

        icon.editMeta { meta ->
            meta.displayName(
                Component.text(giveaway.title ?: "Giveaway #${giveaway.id}", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(
                Component.text("  Hosted by: ", NamedTextColor.GRAY).append(Component.text(giveaway.creatorName, NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                Component.text("  Prize: ", NamedTextColor.GRAY).append(Component.text(prizeSummary(items, giveaway.coins), NamedTextColor.AQUA))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                Component.text("  Entries: ", NamedTextColor.GRAY).append(Component.text("${getEntryCount(giveaway.id)}", NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                Component.text("  Ends in: ", NamedTextColor.GRAY).append(Component.text(formatTimeLeft(giveaway.endsAt), NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(
                when {
                    isOwn -> Component.text("  Status: Your Giveaway", NamedTextColor.LIGHT_PURPLE)
                    entered -> Component.text("  Status: Already Entered", NamedTextColor.GREEN)
                    else -> Component.text("  Status: Not Entered", NamedTextColor.RED)
                }.decoration(TextDecoration.ITALIC, false)
            )
            lore.add(Component.empty())
            lore.add(Component.text("  Click to view", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return icon
    }

    // ---- GUIs ----

    fun openMainGui(player: Player, page: Int = 0) {
        val gui = CustomGui(title("Giveaways"), 54)

        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }
        for (i in 45..53) gui.inventory.setItem(i, FILLER.clone())
        for (slot in CONTENT_SLOTS) if (gui.inventory.getItem(slot) == null) gui.inventory.setItem(slot, FILLER.clone())

        val giveaways = getActiveGiveaways(page)
        for ((index, giveaway) in giveaways.withIndex()) {
            if (index >= CONTENT_SLOTS.size) break
            gui.setItem(CONTENT_SLOTS[index], buildListingIcon(player, giveaway)) { p, _ -> openDetailsGui(p, giveaway.id, page) }
        }

        val total = getTotalActive()
        val totalPages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)

        if (page > 0) {
            gui.setItem(46, simpleItem(Material.ARROW, "Previous Page", NamedTextColor.YELLOW)) { p, _ -> openMainGui(p, page - 1) }
        }

        gui.setItem(
            47,
            simpleItem(
                Material.BOOK, "My Giveaways", NamedTextColor.AQUA,
                listOf(
                    Component.text("  Active: ${getActiveCountFor(player.uniqueId)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Click to manage", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
        ) { p, _ -> openMyGiveawaysGui(p) }

        gui.setItem(49, simpleItem(Material.BARRIER, "Close", NamedTextColor.RED)) { p, _ -> p.closeInventory() }

        val hasPending = hasPendingDelivery(player.uniqueId)
        gui.setItem(
            51,
            simpleItem(
                if (hasPending) Material.CHEST_MINECART else Material.CHEST, "Pending Rewards",
                if (hasPending) NamedTextColor.GOLD else NamedTextColor.GRAY,
                listOf(
                    if (hasPending)
                        Component.text("  You have unclaimed prizes!", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                    else
                        Component.text("  Nothing pending.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Click to claim", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
        ) { p, _ -> deliverPending(p); openMainGui(p, page) }

        if (page < totalPages - 1) {
            gui.setItem(52, simpleItem(Material.ARROW, "Next Page", NamedTextColor.YELLOW)) { p, _ -> openMainGui(p, page + 1) }
        }

        gui.setItem(
            53,
            simpleItem(
                Material.EMERALD, "Create Giveaway", NamedTextColor.GREEN,
                listOf(Component.text("  Click to host your own giveaway", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ -> beginCreation(p) }

        gui.inventory.setItem(
            4,
            simpleItem(
                Material.PAPER, "Page ${page + 1}/$totalPages", NamedTextColor.WHITE,
                listOf(Component.text("  $total active giveaway(s)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        )

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun openDetailsGui(player: Player, id: Int, backPage: Int = 0) {
        val giveaway = getGiveaway(id)
        if (giveaway == null) {
            plugin.commsManager.send(player, Component.text("That giveaway no longer exists.", NamedTextColor.RED))
            openMainGui(player, backPage)
            return
        }
        val items = getItemsFor(id)

        val gui = CustomGui(title("Giveaway #$id"), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = Bukkit.getOfflinePlayer(giveaway.creatorUuid)
            meta.displayName(
                Component.text(giveaway.title ?: "Giveaway #$id", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf(
                Component.empty(),
                Component.text("  Hosted by: ", NamedTextColor.GRAY).append(Component.text(giveaway.creatorName, NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Ends in: ", NamedTextColor.GRAY).append(Component.text(formatTimeLeft(giveaway.endsAt), NamedTextColor.YELLOW))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Entries: ", NamedTextColor.GRAY).append(Component.text("${getEntryCount(id)}", NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (!giveaway.description.isNullOrBlank()) {
                lore.add(Component.empty())
                lore.add(Component.text("  ${giveaway.description}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
        }
        gui.inventory.setItem(4, head)

        val displaySlots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        for ((idx, row) in items.withIndex()) {
            if (idx >= displaySlots.size) break
            gui.inventory.setItem(displaySlots[idx], row.item.clone())
        }
        if (giveaway.coins > 0) {
            val slot = displaySlots.getOrNull(items.size) ?: displaySlots.last()
            gui.inventory.setItem(
                slot,
                simpleItem(
                    Material.GOLD_INGOT, plugin.economyManager.format(giveaway.coins), NamedTextColor.GOLD,
                    listOf(Component.text("  Coins prize", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                )
            )
        }

        gui.setItem(36, simpleItem(Material.ARROW, "Back", NamedTextColor.WHITE)) { p, _ -> openMainGui(p, backPage) }

        val isOwn = giveaway.creatorUuid == player.uniqueId
        val entered = hasEntered(id, player.uniqueId)
        val statusItem = when {
            isOwn -> simpleItem(
                Material.BARRIER, "Your Giveaway", NamedTextColor.LIGHT_PURPLE,
                listOf(Component.text("  You cannot enter your own giveaway.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
            entered -> simpleItem(Material.LIME_DYE, "Already Entered", NamedTextColor.GREEN)
            giveaway.status != "ACTIVE" -> simpleItem(Material.BARRIER, "Ended", NamedTextColor.RED)
            else -> simpleItem(
                Material.LIME_WOOL, "Enter Giveaway", NamedTextColor.GREEN,
                listOf(Component.text("  Click to enter for free", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        }
        gui.setItem(40, statusItem) { p, _ ->
            if (!isOwn && !entered && giveaway.status == "ACTIVE") {
                enterGiveaway(p, id)
                openDetailsGui(p, id, backPage)
            }
        }

        gui.setItem(44, simpleItem(Material.BARRIER, "Close", NamedTextColor.RED)) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    fun openMyGiveawaysGui(player: Player) {
        val gui = CustomGui(title("My Giveaways"), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        val mine = getMyGiveaways(player.uniqueId)
        val slots = (9..35).toList()
        for ((idx, giveaway) in mine.withIndex()) {
            if (idx >= slots.size) break
            val icon = buildListingIcon(player, giveaway)
            icon.editMeta { meta ->
                val lore = (meta.lore() ?: mutableListOf()).toMutableList()
                lore.add(Component.empty())
                lore.add(Component.text("  Click to cancel & refund", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                meta.lore(lore)
            }
            gui.setItem(slots[idx], icon) { p, _ -> cancelGiveaway(p, giveaway.id); openMyGiveawaysGui(p) }
        }

        gui.setItem(40, simpleItem(Material.ARROW, "Back", NamedTextColor.WHITE)) { p, _ -> openMainGui(p) }

        plugin.guiManager.open(player, gui)
    }

    fun openCreateGui(player: Player) {
        val pending = pendingCreations.getOrPut(player.uniqueId) { PendingCreation() }

        val gui = CustomGui(title("Create Giveaway", TextColor.color(0x55FF55)), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        val slots = (9..35).toList()
        for ((idx, item) in pending.items.withIndex()) {
            if (idx >= slots.size) break
            val icon = item.clone()
            icon.editMeta { meta ->
                val lore = (meta.lore() ?: mutableListOf()).toMutableList()
                lore.add(Component.empty())
                lore.add(Component.text("  Click to remove", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                meta.lore(lore)
            }
            val index = idx
            gui.setItem(slots[idx], icon) { p, _ -> removePendingItem(p, index); openCreateGui(p) }
        }

        if (allowItems) {
            gui.setItem(
                36,
                simpleItem(
                    Material.EMERALD, "Add Prize Item", NamedTextColor.GREEN,
                    listOf(
                        Component.text("  Hold an item, then click", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("  Staged: ${pending.items.size}/$maxPrizeItems", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    )
                )
            ) { p, _ -> addHeldItemToPending(p); openCreateGui(p) }
        }

        if (allowCoins) {
            gui.setItem(
                37,
                simpleItem(
                    Material.GOLD_INGOT, "Add Coins", NamedTextColor.GOLD,
                    listOf(
                        Component.text("  Current: ${plugin.economyManager.format(pending.coins)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("  Click to set an amount", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    )
                )
            ) { p, _ -> promptCoins(p) }
        }

        gui.setItem(
            38,
            simpleItem(
                Material.PAPER, "Set Title", NamedTextColor.AQUA,
                listOf(Component.text("  Current: ${pending.title ?: "(none)"}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ -> promptTitle(p) }

        if (allowDescription) {
            gui.setItem(
                39,
                simpleItem(
                    Material.WRITABLE_BOOK, "Set Description", NamedTextColor.AQUA,
                    listOf(Component.text("  Current: ${pending.description ?: "(none)"}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                )
            ) { p, _ -> promptDescription(p) }
        }

        gui.setItem(
            42,
            simpleItem(
                Material.BARRIER, "Cancel", NamedTextColor.RED,
                listOf(Component.text("  Returns all staged items & Coins", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ ->
            val cancelled = pendingCreations.remove(p.uniqueId)
            if (cancelled != null) returnPending(p, cancelled)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Giveaway creation cancelled.", NamedTextColor.GRAY))
        }

        gui.setItem(
            44,
            simpleItem(
                Material.LIME_WOOL, "Review & Confirm", NamedTextColor.GREEN,
                listOf(
                    Component.text("  Duration: ${durationHours}h", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Click to review", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
        ) { p, _ ->
            if (pending.items.isEmpty() && pending.coins <= 0) {
                plugin.commsManager.send(p, Component.text("Add at least one prize item or some Coins first.", NamedTextColor.RED))
            } else {
                openConfirmGui(p)
            }
        }

        // Only a genuine abandonment (ESC, no follow-up GUI opened) should return the
        // staged prize — page navigation and chat prompts swap/close without triggering
        // this because GuiManager only fires onClose for the still-tracked GUI.
        gui.onClose = { p ->
            if (!awaitingChatInput.remove(p.uniqueId)) {
                val stillPending = pendingCreations.remove(p.uniqueId)
                if (stillPending != null) {
                    returnPending(p, stillPending)
                    plugin.commsManager.send(p, Component.text("Giveaway creation closed — items and Coins returned.", NamedTextColor.GRAY))
                }
            }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun openConfirmGui(player: Player) {
        val pending = pendingCreations[player.uniqueId] ?: return

        val gui = CustomGui(title("Confirm Giveaway", TextColor.color(0x55FF55)), 27)

        val redGlass = simpleItem(Material.RED_STAINED_GLASS_PANE, "Back", NamedTextColor.RED)
        val greenGlass = simpleItem(Material.LIME_STAINED_GLASS_PANE, "Confirm", NamedTextColor.GREEN)

        for (i in 0 until 27) {
            val col = i % 9
            if (col < 4) {
                gui.setItem(i, redGlass.clone()) { p, _ -> openCreateGui(p) }
            } else if (col > 4) {
                gui.setItem(i, greenGlass.clone()) { p, _ -> confirmCreation(p) }
            }
        }

        val summary = if (pending.items.size == 1) pending.items[0].clone() else ItemStack(Material.CHEST)
        summary.editMeta { meta ->
            meta.displayName(
                Component.text(pending.title ?: "New Giveaway", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf(Component.empty())
            if (pending.items.isNotEmpty()) {
                lore.add(
                    Component.text("  Items: ", NamedTextColor.GRAY)
                        .append(Component.text(pending.items.joinToString(", ") { describeItem(it) }, NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            if (pending.coins > 0) {
                lore.add(
                    Component.text("  Coins: ", NamedTextColor.GRAY).append(Component.text(plugin.economyManager.format(pending.coins), NamedTextColor.GOLD))
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            lore.add(
                Component.text("  Duration: ", NamedTextColor.GRAY).append(Component.text("${durationHours}h", NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (!pending.description.isNullOrBlank()) {
                lore.add(Component.empty())
                lore.add(Component.text("  ${pending.description}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
        }
        gui.inventory.setItem(13, summary)

        plugin.guiManager.open(player, gui)
    }
}
