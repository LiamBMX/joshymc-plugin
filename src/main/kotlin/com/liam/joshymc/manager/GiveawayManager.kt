package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.util.giveItemSafely
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
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
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
        private const val CANCEL_WINDOW_MS = 30 * 60 * 1000L

        // Item Rewards editor GUI (drag-and-drop multi-item staging)
        private const val ITEM_EDITOR_SIZE = 54
        private const val ITEM_EDITOR_MAX_REWARD_SLOTS = 45 // rows 1-5; row 6 is reserved for controls
        private const val ITEM_EDITOR_CLEAR_SLOT = 45
        private const val ITEM_EDITOR_BACK_SLOT = 49
        private const val ITEM_EDITOR_SAVE_SLOT = 53

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
    // Guards the Create Giveaway GUI's onClose "abandonment" cleanup: set right before we
    // intentionally close it to swap to a chat prompt or the item editor, so that transition
    // isn't mistaken for the player walking away and refunded/wiped.
    private val transitioningAway = ConcurrentHashMap.newKeySet<UUID>()
    private val itemEditorInventories = ConcurrentHashMap<UUID, Inventory>()
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
        maxPrizeItems = cfg.getInt("giveaways.max-prize-items", 27).coerceIn(1, ITEM_EDITOR_MAX_REWARD_SLOTS)

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
        // Clean shutdown/reload safety net: fold any open item editors back into their
        // creation draft, then return any in-progress creation drafts for players who are
        // still online rather than silently losing staged prizes.
        for ((uuid, inv) in itemEditorInventories) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) commitItemEditorToPending(player, inv, itemEditorRewardSlotCount())
        }
        itemEditorInventories.clear()
        for ((uuid, pending) in pendingCreations) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) returnPending(player, pending)
        }
        pendingCreations.clear()
        pendingChatPrompts.clear()
        transitioningAway.clear()
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

    /** Creators may only cancel within [CANCEL_WINDOW_MS] of creation, checked against the persisted `createdAt`. */
    fun canCancel(giveaway: Giveaway): Boolean = System.currentTimeMillis() - giveaway.createdAt < CANCEL_WINDOW_MS

    fun cancelGiveaway(player: Player, id: Int) {
        val giveaway = getGiveaway(id)
        if (giveaway == null || giveaway.creatorUuid != player.uniqueId) {
            plugin.commsManager.send(player, Component.text("Giveaway not found.", NamedTextColor.RED))
            return
        }
        if (!canCancel(giveaway)) {
            plugin.commsManager.send(player, Component.text("This giveaway can no longer be cancelled — the 30-minute window has passed.", NamedTextColor.RED))
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
            // Route into the saved backup inventory (not the temp staff loadout) while
            // Moderator/Trainee Mode is active, same as every other reward delivery.
            val leftover = when {
                plugin.modModeManager.isModMode(player) -> plugin.modModeManager.addItemToBackup(player.uniqueId, item)
                plugin.traineeModeManager.isTraineeMode(player) -> plugin.traineeModeManager.addItemToBackup(player.uniqueId, item)
                else -> player.inventory.addItem(item).values.firstOrNull()
            }
            if (leftover == null) {
                plugin.databaseManager.execute("DELETE FROM giveaway_items WHERE id = ?", rowId)
                delivered++
            } else {
                // Only the part that didn't fit stays pending; what we already handed
                // over is gone from the row so it can never be delivered twice.
                plugin.databaseManager.execute(
                    "UPDATE giveaway_items SET item = ? WHERE id = ?",
                    serializeItem(leftover), rowId
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
        transitioningAway.remove(player.uniqueId)
        val editorInv = itemEditorInventories.remove(player.uniqueId)
        if (editorInv != null) commitItemEditorToPending(player, editorInv, itemEditorRewardSlotCount())
        val pending = pendingCreations.remove(player.uniqueId) ?: return
        returnPending(player, pending)
    }

    private fun returnPending(player: Player, pending: PendingCreation) {
        if (pending.coins > 0) plugin.economyManager.deposit(player.uniqueId, pending.coins)
        for (item in pending.items) {
            plugin.giveItemSafely(player, item)
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

    private fun removePendingItem(player: Player, index: Int) {
        val pending = pendingCreations[player.uniqueId] ?: return
        if (index < 0 || index >= pending.items.size) return
        val item = pending.items.removeAt(index)
        plugin.giveItemSafely(player, item)
    }

    fun promptCoins(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.COINS
        transitioningAway.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type the amount of Coins to add to the prize (or 'cancel'):", NamedTextColor.YELLOW))
    }

    fun promptTitle(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.TITLE
        transitioningAway.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type a title for your giveaway, up to $maxTitleLength characters (or 'cancel'):", NamedTextColor.YELLOW))
    }

    fun promptDescription(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.DESCRIPTION
        transitioningAway.add(player.uniqueId)
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
            val cancellable = canCancel(giveaway)
            val icon = buildListingIcon(player, giveaway)
            icon.editMeta { meta ->
                val lore = (meta.lore() ?: mutableListOf()).toMutableList()
                lore.add(Component.empty())
                if (cancellable) {
                    lore.add(Component.text("  Click to cancel & refund", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                } else {
                    lore.add(Component.text("  Cancellation Locked", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                    lore.add(Component.text("  Giveaways can only be cancelled", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    lore.add(Component.text("  within 30 minutes of creation.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                }
                meta.lore(lore)
            }
            gui.setItem(slots[idx], icon) { p, _ ->
                if (cancellable) cancelGiveaway(p, giveaway.id)
                openMyGiveawaysGui(p)
            }
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
                    Material.EMERALD, "Manage Prize Items", NamedTextColor.GREEN,
                    listOf(
                        Component.text("  Click to open the item editor", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                        Component.text("  Staged: ${pending.items.size}/$maxPrizeItems", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    )
                )
            ) { p, _ -> openItemRewardsGui(p) }
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
            if (!transitioningAway.remove(p.uniqueId)) {
                val stillPending = pendingCreations.remove(p.uniqueId)
                if (stillPending != null) {
                    returnPending(p, stillPending)
                    plugin.commsManager.send(p, Component.text("Giveaway creation closed — items and Coins returned.", NamedTextColor.GRAY))
                }
            }
        }

        plugin.guiManager.open(player, gui)
    }

    // ---- Item Rewards editor GUI ----
    //
    // Unlike every other Giveaway GUI, this one is opened as a raw Bukkit inventory (not
    // through GuiManager/CustomGui) because it needs genuine drag-and-drop item placement —
    // CustomGui locks every top-inventory slot down to click-handler-only. It's tracked in
    // its own [itemEditorInventories] map with its own click/drag/close listeners, matching
    // the pattern StorageManager and TradeManager use for the same reason.

    private fun itemEditorRewardSlotCount(): Int = minOf(maxPrizeItems, ITEM_EDITOR_MAX_REWARD_SLOTS)

    fun openItemRewardsGui(player: Player) {
        if (!allowItems) {
            plugin.commsManager.send(player, Component.text("Item prizes are disabled.", NamedTextColor.RED))
            return
        }
        val pending = pendingCreations[player.uniqueId] ?: return
        val rewardSlotCount = itemEditorRewardSlotCount()

        val inv = Bukkit.createInventory(null, ITEM_EDITOR_SIZE, title("Prize Items", TextColor.color(0x55FF55)))

        // Move (not clone) the staged items into the editor so nothing can be duplicated —
        // pending.items only holds whatever doesn't fit (should never happen in practice)
        // while the editor is open, and is rebuilt from the editor's contents on commit.
        val moveCount = minOf(pending.items.size, rewardSlotCount)
        for (idx in 0 until moveCount) inv.setItem(idx, pending.items[idx])
        repeat(moveCount) { pending.items.removeAt(0) }

        for (slot in rewardSlotCount until 45) inv.setItem(slot, FILLER.clone())
        for (slot in 45..53) inv.setItem(slot, FILLER.clone())

        inv.setItem(
            ITEM_EDITOR_CLEAR_SLOT,
            simpleItem(
                Material.TNT, "Clear Rewards", NamedTextColor.RED,
                listOf(Component.text("  Empties every reward slot", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        )
        inv.setItem(
            ITEM_EDITOR_BACK_SLOT,
            simpleItem(
                Material.ARROW, "Back (Discard Changes)", NamedTextColor.YELLOW,
                listOf(
                    Component.text("  Returns these items to you", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  without saving them as prizes", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
        )
        inv.setItem(
            ITEM_EDITOR_SAVE_SLOT,
            simpleItem(
                Material.LIME_WOOL, "Save & Return", NamedTextColor.GREEN,
                listOf(Component.text("  Saves these items to the giveaway", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        )

        itemEditorInventories[player.uniqueId] = inv
        transitioningAway.add(player.uniqueId)
        player.openInventory(inv)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /** Reads whatever is currently staged in the reward slots back into the creation draft. */
    private fun commitItemEditorToPending(player: Player, inv: Inventory, rewardSlotCount: Int) {
        val pending = pendingCreations[player.uniqueId]
        for (slot in 0 until rewardSlotCount) {
            val item = inv.getItem(slot) ?: continue
            if (item.type == Material.AIR) continue
            if (pending != null) {
                pending.items.add(item.clone())
            } else {
                // No active creation draft (e.g. it was already confirmed/cancelled elsewhere)
                // — return the item instead of losing it.
                plugin.giveItemSafely(player, item)
            }
        }
    }

    private fun clearItemEditorSlots(player: Player, inv: Inventory, rewardSlotCount: Int) {
        for (slot in 0 until rewardSlotCount) {
            val item = inv.getItem(slot) ?: continue
            if (item.type == Material.AIR) continue
            plugin.giveItemSafely(player, item)
            inv.setItem(slot, null)
        }
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f)
    }

    /**
     * Cancelling an [InventoryClickEvent]/[InventoryDragEvent] doesn't always beat the
     * client's local prediction of a NUMBER_KEY/SWAP_OFFHAND swap or a drag — the
     * container's state id only advances after our handler returns, so a resync sent
     * from inside the event can arrive with a stale state id and get ignored by the
     * client. Send one immediately (fixes the common case) and one on the next tick,
     * after the transaction has actually finished server-side, to guarantee the client
     * corrects itself even when the immediate resync loses that race.
     */
    private fun resyncNextTick(player: Player) {
        player.updateInventory()
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) player.updateInventory()
        })
    }

    @EventHandler
    fun onItemEditorClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        val inv = itemEditorInventories[player.uniqueId] ?: return
        if (event.inventory != inv) return

        // COLLECT_TO_CURSOR (double-click) gathers every matching-material stack from
        // BOTH inventories regardless of which slot was actually clicked — that would
        // scoop the Clear/Save buttons or filler glass off the control row if the
        // player happens to be holding a matching material. Block it outright.
        if (event.action == InventoryAction.COLLECT_TO_CURSOR) {
            event.isCancelled = true
            resyncNextTick(player)
            return
        }

        val clickedInventory = event.clickedInventory ?: return
        if (clickedInventory != inv) return // click landed in the player's own inventory — allow it

        val slot = event.rawSlot
        if (slot < 0 || slot >= inv.size) return
        val rewardSlotCount = itemEditorRewardSlotCount()

        if (slot >= rewardSlotCount) {
            event.isCancelled = true
            // Cancelling a NUMBER_KEY/hotbar-swap or SWAP_OFFHAND click doesn't always
            // stop the client from visually predicting the swap — force a resync so a
            // control item (the confirm wool, Clear/Back buttons) can never end up
            // looking like it moved into the player's hotbar/off-hand.
            resyncNextTick(player)
            when (slot) {
                ITEM_EDITOR_CLEAR_SLOT -> clearItemEditorSlots(player, inv, rewardSlotCount)
                ITEM_EDITOR_BACK_SLOT -> {
                    // True discard: hand back whatever is currently staged instead of
                    // committing it, so nothing gets folded into the giveaway's prize list.
                    itemEditorInventories.remove(player.uniqueId)
                    clearItemEditorSlots(player, inv, rewardSlotCount)
                    plugin.commsManager.send(player, Component.text("Discarded — no changes were saved to the giveaway.", NamedTextColor.YELLOW))
                    openCreateGui(player)
                }
                ITEM_EDITOR_SAVE_SLOT -> {
                    itemEditorInventories.remove(player.uniqueId)
                    commitItemEditorToPending(player, inv, rewardSlotCount)
                    val staged = pendingCreations[player.uniqueId]?.items?.size ?: 0
                    plugin.commsManager.send(player, Component.text("Prize items saved ($staged staged).", NamedTextColor.GREEN))
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f)
                    openCreateGui(player)
                }
            }
        }
        // slot < rewardSlotCount: a reward slot — leave default placement/removal behavior alone.
    }

    @EventHandler
    fun onItemEditorDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val inv = itemEditorInventories[player.uniqueId] ?: return
        if (event.inventory != inv) return
        val rewardSlotCount = itemEditorRewardSlotCount()
        if (event.rawSlots.any { it < inv.size && it >= rewardSlotCount }) {
            event.isCancelled = true
            resyncNextTick(player)
        }
    }

    @EventHandler
    fun onItemEditorClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        val inv = itemEditorInventories.remove(player.uniqueId) ?: return
        if (event.inventory != inv) return
        // A bare close (ESC, clicking outside the window, etc.) never goes through Save &
        // Return, so it must behave like Back — hand the staged items straight back to the
        // player instead of folding them into the creation draft. Without this, closing the
        // editor any way other than clicking Save silently committed whatever was staged,
        // which contradicts the editor's own "Save & Return" vs "Back (Discard Changes)" choice.
        clearItemEditorSlots(player, inv, itemEditorRewardSlotCount())
        plugin.commsManager.send(player, Component.text("Item editor closed — changes were not saved.", NamedTextColor.YELLOW))
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
