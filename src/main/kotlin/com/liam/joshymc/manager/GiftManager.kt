package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.util.giveItemSafely
import com.liam.joshymc.util.giveItemsSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player-to-player gifting via `/gift`: a GUI to send items + Coins to another player
 * (online or previously-seen offline), and a persistent `/gift mailbox` the recipient
 * claims from whenever they're ready. Mirrors GiveawayManager's escrow/persistence
 * conventions (Base64 ItemStack rows, atomic status-transition guards, hold-item-then-click
 * staging) but claiming is always an explicit player action — a gift that doesn't fit is
 * left untouched in the mailbox rather than auto-delivered piecemeal.
 */
class GiftManager(private val plugin: Joshymc) : Listener {

    companion object {
        private val BORDER = ItemStack(Material.CYAN_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.empty()) }
        }
        private const val PAGE_SIZE = 28
        private val CONTENT_SLOTS: List<Int> = (1..4).flatMap { row -> (1..7).map { col -> row * 9 + col } }
        private val PICKER_SLOTS: List<Int> = (9..35).toList()

        private fun title(text: String, color: TextColor = TextColor.color(0x55FFFF)): Component =
            Component.text("         ")
                .append(Component.text(text, color))
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false)
    }

    data class Gift(
        val id: Int,
        val senderUuid: UUID,
        val senderName: String,
        val recipientUuid: UUID,
        val recipientName: String,
        val coins: Double,
        val message: String?,
        val status: String,
        val createdAt: Long,
        val claimedAt: Long?
    )

    data class GiftItemRow(val rowId: Int, val giftId: Int, val item: ItemStack)

    enum class ChatPromptType { COINS, MESSAGE, OFFLINE_NAME }

    class PendingGift(val recipientUuid: UUID, val recipientName: String) {
        val items = mutableListOf<ItemStack>()
        var coins: Double = 0.0
        var message: String? = null
    }

    // Config
    private var maxPendingPerSender = 20
    private var maxMailboxSize = 100
    private var sendCooldownSeconds = 5
    private var allowCoins = true
    private var allowMessage = true
    private var maxMessageLength = 120
    private var maxGiftItems = 27

    private val pendingGifts = ConcurrentHashMap<UUID, PendingGift>()
    val pendingChatPrompts = ConcurrentHashMap<UUID, ChatPromptType>()
    private val awaitingChatInput = ConcurrentHashMap.newKeySet<UUID>()
    private val lastSentAt = ConcurrentHashMap<UUID, Long>()

    fun start() {
        val cfg = plugin.config
        maxPendingPerSender = cfg.getInt("gift.max-pending-per-sender", 20)
        maxMailboxSize = cfg.getInt("gift.max-mailbox-size", 100)
        sendCooldownSeconds = cfg.getInt("gift.send-cooldown-seconds", 5).coerceAtLeast(0)
        allowCoins = cfg.getBoolean("gift.allow-coins", true)
        allowMessage = cfg.getBoolean("gift.allow-message", true)
        maxMessageLength = cfg.getInt("gift.max-message-length", 120).coerceAtLeast(1)
        maxGiftItems = cfg.getInt("gift.max-gift-items", 27).coerceIn(1, 27)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS gifts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sender_uuid TEXT NOT NULL,
                sender_name TEXT NOT NULL,
                recipient_uuid TEXT NOT NULL,
                recipient_name TEXT NOT NULL,
                coins REAL NOT NULL DEFAULT 0,
                message TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                created_at INTEGER NOT NULL,
                claimed_at INTEGER
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS gift_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                gift_id INTEGER NOT NULL,
                item TEXT NOT NULL
            )
        """.trimIndent())

        plugin.logger.info("[Gift] GiftManager started.")
    }

    fun stop() {
        // Reload/shutdown safety net: return any in-progress composition drafts for
        // players who are still online rather than silently losing staged gifts.
        for ((uuid, pending) in pendingGifts) {
            val player = Bukkit.getPlayer(uuid)
            if (player != null) returnPending(player, pending)
        }
        pendingGifts.clear()
        pendingChatPrompts.clear()
        awaitingChatInput.clear()
        lastSentAt.clear()
    }

    // ---- Item serialization ----

    private fun serializeItem(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun deserializeItem(base64: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(base64))

    // ---- Row mappers ----

    private fun mapGift(rs: java.sql.ResultSet): Gift {
        val claimedAt = rs.getLong("claimed_at")
        return Gift(
            id = rs.getInt("id"),
            senderUuid = UUID.fromString(rs.getString("sender_uuid")),
            senderName = rs.getString("sender_name"),
            recipientUuid = UUID.fromString(rs.getString("recipient_uuid")),
            recipientName = rs.getString("recipient_name"),
            coins = rs.getDouble("coins"),
            message = rs.getString("message"),
            status = rs.getString("status"),
            createdAt = rs.getLong("created_at"),
            claimedAt = if (rs.wasNull()) null else claimedAt
        )
    }

    private fun mapItemRow(rs: java.sql.ResultSet): GiftItemRow =
        GiftItemRow(rs.getInt("id"), rs.getInt("gift_id"), deserializeItem(rs.getString("item")))

    // ---- Queries ----

    fun getGift(id: Int): Gift? = plugin.databaseManager.queryFirst("SELECT * FROM gifts WHERE id = ?", id) { mapGift(it) }

    fun getItemsFor(giftId: Int): List<GiftItemRow> = plugin.databaseManager.query(
        "SELECT * FROM gift_items WHERE gift_id = ?", giftId
    ) { mapItemRow(it) }

    fun getMailbox(uuid: UUID, page: Int, pageSize: Int = PAGE_SIZE): List<Gift> {
        val offset = page * pageSize
        return plugin.databaseManager.query(
            "SELECT * FROM gifts WHERE recipient_uuid = ? AND status = 'PENDING' ORDER BY created_at DESC LIMIT ? OFFSET ?",
            uuid.toString(), pageSize, offset
        ) { mapGift(it) }
    }

    fun getMailboxCount(uuid: UUID): Int = plugin.databaseManager.queryFirst(
        "SELECT COUNT(*) as cnt FROM gifts WHERE recipient_uuid = ? AND status = 'PENDING'", uuid.toString()
    ) { it.getInt("cnt") } ?: 0

    fun getSentGifts(uuid: UUID, page: Int, pageSize: Int = PAGE_SIZE): List<Gift> {
        val offset = page * pageSize
        return plugin.databaseManager.query(
            "SELECT * FROM gifts WHERE sender_uuid = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            uuid.toString(), pageSize, offset
        ) { mapGift(it) }
    }

    fun getPendingCountFor(uuid: UUID): Int = plugin.databaseManager.queryFirst(
        "SELECT COUNT(*) as cnt FROM gifts WHERE sender_uuid = ? AND status = 'PENDING'", uuid.toString()
    ) { it.getInt("cnt") } ?: 0

    fun getSentCount(uuid: UUID): Int = plugin.databaseManager.queryFirst(
        "SELECT COUNT(*) as cnt FROM gifts WHERE sender_uuid = ?", uuid.toString()
    ) { it.getInt("cnt") } ?: 0

    // ---- Player lifecycle ----

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        val count = getMailboxCount(player.uniqueId)
        if (count > 0) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                plugin.commsManager.send(
                    player,
                    Component.text("You have $count unclaimed gift(s). Use ", NamedTextColor.YELLOW)
                        .append(Component.text("/gift mailbox", NamedTextColor.GREEN))
                        .append(Component.text(" to view them.", NamedTextColor.YELLOW)),
                    CommunicationsManager.Category.ECONOMY
                )
            }, 40L)
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        pendingChatPrompts.remove(player.uniqueId)
        awaitingChatInput.remove(player.uniqueId)
        val pending = pendingGifts.remove(player.uniqueId) ?: return
        returnPending(player, pending)
    }

    private fun returnPending(player: Player, pending: PendingGift) {
        if (pending.coins > 0) plugin.economyManager.deposit(player.uniqueId, pending.coins)
        for (item in pending.items) {
            plugin.giveItemSafely(player, item)
        }
    }

    // ---- Recipient resolution ----

    /** Resolves a previously-seen player by name. Never allows a name that has never joined the server. */
    fun resolveKnownPlayer(name: String): OfflinePlayer? {
        val target = Bukkit.getOfflinePlayer(name)
        return if (target.hasPlayedBefore() || target.isOnline) target else null
    }

    // ---- Composition flow ----

    fun beginComposition(sender: Player, recipient: OfflinePlayer) {
        if (!sender.hasPermission("joshymc.gift.send")) {
            plugin.commsManager.send(sender, Component.text("No permission.", NamedTextColor.RED))
            return
        }
        if (recipient.uniqueId == sender.uniqueId) {
            plugin.commsManager.send(sender, Component.text("You cannot gift yourself.", NamedTextColor.RED))
            return
        }
        if (pendingGifts.containsKey(sender.uniqueId)) {
            plugin.commsManager.send(sender, Component.text("You're already composing a gift. Finish or cancel it first.", NamedTextColor.RED))
            return
        }
        val cooldownRemaining = cooldownRemainingSeconds(sender.uniqueId)
        if (cooldownRemaining > 0) {
            plugin.commsManager.send(sender, Component.text("Wait ${cooldownRemaining}s before sending another gift.", NamedTextColor.RED))
            return
        }
        if (maxPendingPerSender > 0 && getPendingCountFor(sender.uniqueId) >= maxPendingPerSender) {
            plugin.commsManager.send(sender, Component.text("You already have $maxPendingPerSender unclaimed gift(s) out. Wait for one to be claimed.", NamedTextColor.RED))
            return
        }
        if (maxMailboxSize > 0 && getMailboxCount(recipient.uniqueId) >= maxMailboxSize) {
            plugin.commsManager.send(sender, Component.text("${recipient.name ?: "That player"}'s mailbox is full.", NamedTextColor.RED))
            return
        }

        pendingGifts[sender.uniqueId] = PendingGift(recipient.uniqueId, recipient.name ?: "Unknown")
        openCompositionGui(sender)
    }

    private fun cooldownRemainingSeconds(uuid: UUID): Long {
        if (sendCooldownSeconds <= 0) return 0
        val last = lastSentAt[uuid] ?: return 0
        val elapsed = (System.currentTimeMillis() - last) / 1000
        return (sendCooldownSeconds - elapsed).coerceAtLeast(0)
    }

    private fun addHeldItemToPending(player: Player) {
        val pending = pendingGifts[player.uniqueId] ?: return
        if (pending.items.size >= maxGiftItems) {
            plugin.commsManager.send(player, Component.text("You can add at most $maxGiftItems item stack(s) to a gift.", NamedTextColor.RED))
            return
        }
        val held = player.inventory.itemInMainHand
        if (held.type == Material.AIR) {
            plugin.commsManager.send(player, Component.text("Hold the item you want to gift, then click.", NamedTextColor.RED))
            return
        }
        pending.items.add(held.clone())
        player.inventory.setItemInMainHand(null)
        player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f)
    }

    private fun removePendingItem(player: Player, index: Int) {
        val pending = pendingGifts[player.uniqueId] ?: return
        if (index < 0 || index >= pending.items.size) return
        val item = pending.items.removeAt(index)
        plugin.giveItemSafely(player, item)
    }

    fun promptCoins(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.COINS
        awaitingChatInput.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type the amount of Coins to include, or 'cancel':", NamedTextColor.YELLOW))
    }

    fun promptMessage(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.MESSAGE
        awaitingChatInput.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type a short message to include (up to $maxMessageLength characters), or 'cancel':", NamedTextColor.YELLOW))
    }

    fun promptOfflineName(player: Player) {
        pendingChatPrompts[player.uniqueId] = ChatPromptType.OFFLINE_NAME
        awaitingChatInput.add(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("Type the username of the player to gift, or 'cancel':", NamedTextColor.YELLOW))
    }

    fun handleChatInput(player: Player, type: ChatPromptType, raw: String) {
        if (raw.equals("cancel", ignoreCase = true)) {
            plugin.commsManager.send(player, Component.text("Cancelled.", NamedTextColor.GRAY))
            if (type == ChatPromptType.OFFLINE_NAME) openMainGui(player) else openCompositionGui(player)
            return
        }

        when (type) {
            ChatPromptType.OFFLINE_NAME -> {
                val target = resolveKnownPlayer(raw.trim())
                if (target == null) {
                    plugin.commsManager.send(player, Component.text("No known player named '$raw' has played on this server.", NamedTextColor.RED))
                    openMainGui(player)
                    return
                }
                beginComposition(player, target)
                return
            }
            else -> { /* handled below, requires an in-progress composition */ }
        }

        val pending = pendingGifts[player.uniqueId] ?: return

        when (type) {
            ChatPromptType.COINS -> {
                if (!allowCoins) {
                    plugin.commsManager.send(player, Component.text("Coin gifts are disabled.", NamedTextColor.RED))
                    openCompositionGui(player)
                    return
                }
                val amount = plugin.economyManager.parseAmount(raw)
                if (amount == null || amount < 0) {
                    plugin.commsManager.send(player, Component.text("Invalid amount.", NamedTextColor.RED))
                    openCompositionGui(player)
                    return
                }
                // Refund whatever was previously staged before re-withdrawing the new amount
                // so changing the Coins amount never double-charges the sender.
                if (pending.coins > 0) plugin.economyManager.deposit(player.uniqueId, pending.coins)
                if (!plugin.economyManager.withdraw(player.uniqueId, amount)) {
                    plugin.commsManager.send(player, Component.text("You don't have that much money.", NamedTextColor.RED))
                    pending.coins = 0.0
                    openCompositionGui(player)
                    return
                }
                pending.coins = amount
                plugin.commsManager.send(player, Component.text("Added ${plugin.economyManager.format(amount)} to the gift.", NamedTextColor.GREEN))
            }
            ChatPromptType.MESSAGE -> {
                pending.message = raw.trim().take(maxMessageLength)
                plugin.commsManager.send(player, Component.text("Message set.", NamedTextColor.GREEN))
            }
            ChatPromptType.OFFLINE_NAME -> Unit // handled above
        }
        openCompositionGui(player)
    }

    private fun confirmSend(player: Player) {
        val pending = pendingGifts[player.uniqueId] ?: run {
            player.closeInventory()
            return
        }
        if (pending.items.isEmpty() && pending.coins <= 0) {
            plugin.commsManager.send(player, Component.text("Add at least one item or some Coins first.", NamedTextColor.RED))
            return
        }
        if (maxMailboxSize > 0 && getMailboxCount(pending.recipientUuid) >= maxMailboxSize) {
            plugin.commsManager.send(player, Component.text("${pending.recipientName}'s mailbox is now full.", NamedTextColor.RED))
            return
        }

        pendingGifts.remove(player.uniqueId)
        lastSentAt[player.uniqueId] = System.currentTimeMillis()

        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "INSERT INTO gifts (sender_uuid, sender_name, recipient_uuid, recipient_name, coins, message, status, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?)",
            player.uniqueId.toString(), player.name, pending.recipientUuid.toString(), pending.recipientName, pending.coins, pending.message, now
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() as id") { it.getInt("id") } ?: -1
        for (item in pending.items) {
            plugin.databaseManager.execute("INSERT INTO gift_items (gift_id, item) VALUES (?, ?)", id, serializeItem(item))
        }

        player.closeInventory()
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f)
        plugin.commsManager.send(
            player,
            Component.text("Gift #$id sent to ", NamedTextColor.GREEN)
                .append(Component.text(pending.recipientName, NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
        plugin.logger.info("[Gift] ${player.name} sent gift #$id to ${pending.recipientName} (items=${pending.items.size}, coins=${pending.coins})")

        val recipient = Bukkit.getPlayer(pending.recipientUuid)
        if (recipient != null) {
            plugin.commsManager.send(
                recipient,
                Component.text("You received a gift from ", NamedTextColor.GREEN)
                    .append(Component.text(player.name, NamedTextColor.AQUA))
                    .append(Component.text("! Use ", NamedTextColor.GREEN))
                    .append(Component.text("/gift mailbox", NamedTextColor.YELLOW))
                    .append(Component.text(" to view it.", NamedTextColor.GREEN)),
                CommunicationsManager.Category.ECONOMY
            )
            recipient.playSound(recipient.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f)
        }
    }

    // ---- Cancellation ----

    fun cancelGift(player: Player, id: Int) {
        val gift = getGift(id)
        if (gift == null || gift.senderUuid != player.uniqueId) {
            plugin.commsManager.send(player, Component.text("Gift not found.", NamedTextColor.RED))
            return
        }
        if (gift.status != "PENDING") {
            plugin.commsManager.send(player, Component.text("That gift can no longer be cancelled.", NamedTextColor.RED))
            return
        }

        val items = getItemsFor(id).map { it.item }
        if (!fitsInInventory(player, items)) {
            plugin.commsManager.send(player, Component.text("You need more inventory space to cancel and reclaim this gift.", NamedTextColor.RED))
            return
        }

        // Atomic PENDING -> CANCELLED transition guards against a cancel racing the
        // recipient's claim of the same gift.
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE gifts SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'", id
        )
        if (rows == 0) {
            plugin.commsManager.send(player, Component.text("That gift can no longer be cancelled.", NamedTextColor.RED))
            return
        }

        if (gift.coins > 0) plugin.economyManager.deposit(player.uniqueId, gift.coins)
        plugin.giveItemsSafely(player, items)
        plugin.databaseManager.execute("DELETE FROM gift_items WHERE gift_id = ?", id)

        plugin.commsManager.send(player, Component.text("Gift #$id cancelled and refunded.", NamedTextColor.GREEN))
        plugin.logger.info("[Gift] ${player.name} cancelled gift #$id")
    }

    // ---- Claiming ----

    private fun fitsInInventory(player: Player, items: List<ItemStack>): Boolean {
        if (items.isEmpty()) return true
        val temp = Bukkit.createInventory(null, 36)
        temp.contents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val leftover = temp.addItem(*items.map { it.clone() }.toTypedArray())
        return leftover.isEmpty()
    }

    fun claimGift(player: Player, id: Int) {
        val gift = getGift(id)
        if (gift == null || gift.recipientUuid != player.uniqueId) {
            plugin.commsManager.send(player, Component.text("Gift not found.", NamedTextColor.RED))
            return
        }
        if (gift.status != "PENDING") {
            plugin.commsManager.send(player, Component.text("That gift has already been claimed.", NamedTextColor.YELLOW))
            return
        }

        val items = getItemsFor(id).map { it.item }
        if (!fitsInInventory(player, items)) {
            plugin.commsManager.send(player, Component.text("You need more inventory space to claim this gift.", NamedTextColor.RED))
            return
        }

        // Atomic PENDING -> CLAIMED transition, scoped to this recipient, guards against
        // double-click, reconnect, or a sender cancel racing the same gift.
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE gifts SET status = 'CLAIMED', claimed_at = ? WHERE id = ? AND recipient_uuid = ? AND status = 'PENDING'",
            System.currentTimeMillis(), id, player.uniqueId.toString()
        )
        if (rows == 0) {
            plugin.antiDupeManager.record(
                player,
                "Duplicate Gift Claim Attempt",
                AntiDupeManager.RiskLevel.MEDIUM,
                item = if (items.isEmpty()) null else "${items.size} item stack(s)",
                amount = if (gift.coins > 0) plugin.economyManager.format(gift.coins) else null,
                source = "Gift #$id",
                transactionId = "gift-$id"
            )
            plugin.commsManager.send(player, Component.text("That gift has already been claimed.", NamedTextColor.YELLOW))
            return
        }

        if (gift.coins > 0) plugin.economyManager.deposit(player.uniqueId, gift.coins)
        plugin.giveItemsSafely(player, items)
        plugin.databaseManager.execute("DELETE FROM gift_items WHERE gift_id = ?", id)

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
        plugin.commsManager.send(
            player,
            Component.text("Claimed gift #$id from ", NamedTextColor.GREEN)
                .append(Component.text(gift.senderName, NamedTextColor.AQUA))
                .append(Component.text("!", NamedTextColor.GREEN)),
            CommunicationsManager.Category.ECONOMY
        )
        plugin.logger.info("[Gift] ${player.name} claimed gift #$id from ${gift.senderName}")
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

    private fun contentsSummary(items: List<GiftItemRow>, coins: Double): String {
        val parts = mutableListOf<String>()
        if (items.isNotEmpty()) parts.add(if (items.size == 1) describeItem(items[0].item) else "${items.size} items")
        if (coins > 0) parts.add(plugin.economyManager.format(coins))
        return if (parts.isEmpty()) "Nothing" else parts.joinToString(" + ")
    }

    private fun formatTime(epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        val zoned = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        return java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm").format(zoned)
    }

    private fun playerHead(uuid: UUID, name: String, displayName: Component, lore: List<Component>): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            meta.owningPlayer = Bukkit.getOfflinePlayer(uuid)
            meta.displayName(displayName.decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return head
    }

    // ---- GUIs ----

    fun openMainGui(player: Player) {
        pendingChatPrompts.remove(player.uniqueId)
        val gui = CustomGui(title("Gifts"), 27)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 18..26) gui.inventory.setItem(i, BORDER.clone())
        for (i in 9..17) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        gui.setItem(
            10,
            simpleItem(
                Material.CHEST_MINECART, "Send Gift", NamedTextColor.GREEN,
                listOf(Component.text("  Send items and/or Coins to another player", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ -> openRecipientPicker(p) }

        val mailboxCount = getMailboxCount(player.uniqueId)
        gui.setItem(
            13,
            simpleItem(
                if (mailboxCount > 0) Material.CHEST else Material.ENDER_CHEST,
                "Mailbox", if (mailboxCount > 0) NamedTextColor.GOLD else NamedTextColor.AQUA,
                listOf(
                    Component.text(
                        if (mailboxCount > 0) "  $mailboxCount unclaimed gift(s)" else "  No unclaimed gifts",
                        if (mailboxCount > 0) NamedTextColor.YELLOW else NamedTextColor.DARK_GRAY
                    ).decoration(TextDecoration.ITALIC, false)
                )
            )
        ) { p, _ -> openMailboxGui(p) }

        gui.setItem(
            16,
            simpleItem(
                Material.WRITABLE_BOOK, "Sent Gifts", NamedTextColor.AQUA,
                listOf(Component.text("  View gifts you've sent", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ -> openSentGui(p) }

        gui.setItem(22, simpleItem(Material.BARRIER, "Close", NamedTextColor.RED)) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun openRecipientPicker(player: Player, page: Int = 0) {
        val gui = CustomGui(title("Select Recipient"), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        val online = Bukkit.getOnlinePlayers().filter { it.uniqueId != player.uniqueId }.sortedBy { it.name }
        val totalPages = maxOf(1, (online.size + PICKER_SLOTS.size - 1) / PICKER_SLOTS.size)
        val clampedPage = page.coerceIn(0, totalPages - 1)
        val pageEntries = online.drop(clampedPage * PICKER_SLOTS.size).take(PICKER_SLOTS.size)

        for ((idx, target) in pageEntries.withIndex()) {
            gui.setItem(
                PICKER_SLOTS[idx],
                playerHead(
                    target.uniqueId, target.name,
                    Component.text(target.name, NamedTextColor.WHITE),
                    listOf(Component.text("  Click to send a gift", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                )
            ) { p, _ -> beginComposition(p, target) }
        }

        if (clampedPage > 0) {
            gui.setItem(36, simpleItem(Material.ARROW, "Previous Page", NamedTextColor.YELLOW)) { p, _ -> openRecipientPicker(p, clampedPage - 1) }
        }
        if (clampedPage < totalPages - 1) {
            gui.setItem(37, simpleItem(Material.ARROW, "Next Page", NamedTextColor.YELLOW)) { p, _ -> openRecipientPicker(p, clampedPage + 1) }
        }

        gui.setItem(
            40,
            simpleItem(
                Material.NAME_TAG, "Offline Player", NamedTextColor.AQUA,
                listOf(Component.text("  Click to type a username", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ -> promptOfflineName(p) }

        gui.setItem(42, simpleItem(Material.ARROW, "Back", NamedTextColor.WHITE)) { p, _ -> openMainGui(p) }
        gui.setItem(44, simpleItem(Material.BARRIER, "Close", NamedTextColor.RED)) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    fun openCompositionGui(player: Player) {
        val pending = pendingGifts[player.uniqueId] ?: run { openMainGui(player); return }

        val gui = CustomGui(title("Gift for ${pending.recipientName}", TextColor.color(0x55FF55)), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        gui.inventory.setItem(
            4,
            playerHead(
                pending.recipientUuid, pending.recipientName,
                Component.text("To: ${pending.recipientName}", NamedTextColor.GOLD),
                emptyList()
            )
        )

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
            gui.setItem(slots[idx], icon) { p, _ -> removePendingItem(p, index); openCompositionGui(p) }
        }

        gui.setItem(
            36,
            simpleItem(
                Material.EMERALD, "Add Item", NamedTextColor.GREEN,
                listOf(
                    Component.text("  Hold an item, then click", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Staged: ${pending.items.size}/$maxGiftItems", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                )
            )
        ) { p, _ -> addHeldItemToPending(p); openCompositionGui(p) }

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

        if (allowMessage) {
            gui.setItem(
                38,
                simpleItem(
                    Material.WRITABLE_BOOK, "Add Message", NamedTextColor.AQUA,
                    listOf(Component.text("  Current: ${pending.message ?: "(none)"}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                )
            ) { p, _ -> promptMessage(p) }
        }

        gui.setItem(
            42,
            simpleItem(
                Material.BARRIER, "Cancel", NamedTextColor.RED,
                listOf(Component.text("  Returns all staged items & Coins", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ ->
            val cancelled = pendingGifts.remove(p.uniqueId)
            if (cancelled != null) returnPending(p, cancelled)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Gift creation cancelled.", NamedTextColor.GRAY))
        }

        gui.setItem(
            44,
            simpleItem(
                Material.LIME_WOOL, "Review & Confirm", NamedTextColor.GREEN,
                listOf(Component.text("  Click to review", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        ) { p, _ ->
            if (pending.items.isEmpty() && pending.coins <= 0) {
                plugin.commsManager.send(p, Component.text("Add at least one item or some Coins first.", NamedTextColor.RED))
            } else {
                openConfirmGui(p)
            }
        }

        // Only a genuine abandonment (ESC, no follow-up GUI/chat-prompt opened) returns the
        // staged gift — page navigation and chat prompts swap/close without triggering this
        // because GuiManager only fires onClose for the still-tracked GUI.
        gui.onClose = { p ->
            if (!awaitingChatInput.remove(p.uniqueId)) {
                val stillPending = pendingGifts.remove(p.uniqueId)
                if (stillPending != null) {
                    returnPending(p, stillPending)
                    plugin.commsManager.send(p, Component.text("Gift creation closed — items and Coins returned.", NamedTextColor.GRAY))
                }
            }
        }

        plugin.guiManager.open(player, gui)
    }

    private fun openConfirmGui(player: Player) {
        val pending = pendingGifts[player.uniqueId] ?: return

        val gui = CustomGui(title("Confirm Gift", TextColor.color(0x55FF55)), 27)

        val redGlass = simpleItem(Material.RED_STAINED_GLASS_PANE, "Back", NamedTextColor.RED)
        val greenGlass = simpleItem(Material.LIME_STAINED_GLASS_PANE, "Confirm", NamedTextColor.GREEN)

        for (i in 0 until 27) {
            val col = i % 9
            if (col < 4) {
                gui.setItem(i, redGlass.clone()) { p, _ -> openCompositionGui(p) }
            } else if (col > 4) {
                gui.setItem(i, greenGlass.clone()) { p, _ -> confirmSend(p) }
            }
        }

        val summary = if (pending.items.size == 1) pending.items[0].clone() else ItemStack(Material.CHEST)
        summary.editMeta { meta ->
            meta.displayName(
                Component.text("Gift for ${pending.recipientName}", NamedTextColor.GOLD)
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
            if (!pending.message.isNullOrBlank()) {
                lore.add(Component.empty())
                lore.add(Component.text("  \"${pending.message}\"", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            }
            meta.lore(lore)
        }
        gui.inventory.setItem(13, summary)

        plugin.guiManager.open(player, gui)
    }

    fun openMailboxGui(player: Player, page: Int = 0) {
        val gui = CustomGui(title("Mailbox"), 54)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (row in 1..4) {
            gui.inventory.setItem(row * 9, BORDER.clone())
            gui.inventory.setItem(row * 9 + 8, BORDER.clone())
        }
        for (i in 45..53) gui.inventory.setItem(i, FILLER.clone())
        for (slot in CONTENT_SLOTS) if (gui.inventory.getItem(slot) == null) gui.inventory.setItem(slot, FILLER.clone())

        val gifts = getMailbox(player.uniqueId, page)
        for ((idx, gift) in gifts.withIndex()) {
            if (idx >= CONTENT_SLOTS.size) break
            gui.setItem(CONTENT_SLOTS[idx], buildMailboxIcon(gift)) { p, _ -> openGiftDetailGui(p, gift.id, page) }
        }

        val total = getMailboxCount(player.uniqueId)
        val totalPages = maxOf(1, (total + PAGE_SIZE - 1) / PAGE_SIZE)

        if (page > 0) gui.setItem(46, simpleItem(Material.ARROW, "Previous Page", NamedTextColor.YELLOW)) { p, _ -> openMailboxGui(p, page - 1) }
        gui.setItem(49, simpleItem(Material.ARROW, "Back", NamedTextColor.WHITE)) { p, _ -> openMainGui(p) }
        if (page < totalPages - 1) gui.setItem(52, simpleItem(Material.ARROW, "Next Page", NamedTextColor.YELLOW)) { p, _ -> openMailboxGui(p, page + 1) }

        gui.inventory.setItem(
            4,
            simpleItem(
                Material.PAPER, "Page ${page + 1}/$totalPages", NamedTextColor.WHITE,
                listOf(Component.text("  $total unclaimed gift(s)", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        )

        plugin.guiManager.open(player, gui)
    }

    private fun buildMailboxIcon(gift: Gift): ItemStack {
        val items = getItemsFor(gift.id)
        val icon = when {
            items.size == 1 -> items[0].item.clone()
            items.isEmpty() && gift.coins > 0 -> ItemStack(Material.GOLD_INGOT)
            else -> ItemStack(Material.CHEST)
        }
        icon.amount = 1
        icon.editMeta { meta ->
            meta.displayName(
                Component.text("Gift #${gift.id} from ${gift.senderName}", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf(
                Component.empty(),
                Component.text("  Sent: ", NamedTextColor.GRAY).append(Component.text(formatTime(gift.createdAt), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Contents: ", NamedTextColor.GRAY).append(Component.text(contentsSummary(items, gift.coins), NamedTextColor.AQUA))
                    .decoration(TextDecoration.ITALIC, false)
            )
            if (!gift.message.isNullOrBlank()) {
                lore.add(Component.text("  \"${gift.message}\"", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())
            lore.add(Component.text("  Click to view", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            meta.lore(lore)
        }
        return icon
    }

    fun openGiftDetailGui(player: Player, id: Int, backPage: Int = 0) {
        val gift = getGift(id)
        if (gift == null) {
            plugin.commsManager.send(player, Component.text("That gift no longer exists.", NamedTextColor.RED))
            openMailboxGui(player, backPage)
            return
        }
        val items = getItemsFor(id)

        val gui = CustomGui(title("Gift #$id"), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        gui.inventory.setItem(
            4,
            playerHead(
                gift.senderUuid, gift.senderName,
                Component.text("From: ${gift.senderName}", NamedTextColor.GOLD),
                buildList {
                    add(Component.empty())
                    add(Component.text("  Sent: ", NamedTextColor.GRAY).append(Component.text(formatTime(gift.createdAt), NamedTextColor.WHITE)).decoration(TextDecoration.ITALIC, false))
                    if (!gift.message.isNullOrBlank()) {
                        add(Component.empty())
                        add(Component.text("  \"${gift.message}\"", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    }
                }
            )
        )

        val displaySlots = listOf(10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25)
        for ((idx, row) in items.withIndex()) {
            if (idx >= displaySlots.size) break
            gui.inventory.setItem(displaySlots[idx], row.item.clone())
        }
        if (gift.coins > 0) {
            val slot = displaySlots.getOrNull(items.size) ?: displaySlots.last()
            gui.inventory.setItem(
                slot,
                simpleItem(
                    Material.GOLD_INGOT, plugin.economyManager.format(gift.coins), NamedTextColor.GOLD,
                    listOf(Component.text("  Coins", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                )
            )
        }

        gui.setItem(36, simpleItem(Material.ARROW, "Back", NamedTextColor.WHITE)) { p, _ -> openMailboxGui(p, backPage) }

        val claimItem = if (gift.status == "PENDING") {
            simpleItem(
                Material.LIME_WOOL, "Claim", NamedTextColor.GREEN,
                listOf(Component.text("  Click to claim this gift", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            )
        } else {
            simpleItem(Material.BARRIER, "Already Claimed", NamedTextColor.RED)
        }
        gui.setItem(40, claimItem) { p, _ ->
            if (gift.status == "PENDING") {
                claimGift(p, id)
                openMailboxGui(p, backPage)
            }
        }

        gui.setItem(44, simpleItem(Material.BARRIER, "Close", NamedTextColor.RED)) { p, _ -> p.closeInventory() }

        plugin.guiManager.open(player, gui)
    }

    fun openSentGui(player: Player, page: Int = 0) {
        val gui = CustomGui(title("Sent Gifts"), 45)
        for (i in 0..8) gui.inventory.setItem(i, BORDER.clone())
        for (i in 36..44) gui.inventory.setItem(i, FILLER.clone())
        for (i in 9..35) if (gui.inventory.getItem(i) == null) gui.inventory.setItem(i, FILLER.clone())

        val sent = getSentGifts(player.uniqueId, page)
        val slots = (9..35).toList()
        for ((idx, gift) in sent.withIndex()) {
            if (idx >= slots.size) break
            val items = getItemsFor(gift.id)
            val icon = when {
                items.size == 1 -> items[0].item.clone()
                items.isEmpty() && gift.coins > 0 -> ItemStack(Material.GOLD_INGOT)
                else -> ItemStack(Material.CHEST)
            }
            icon.amount = 1
            val statusColor = when (gift.status) {
                "PENDING" -> NamedTextColor.YELLOW
                "CLAIMED" -> NamedTextColor.GREEN
                else -> NamedTextColor.RED
            }
            icon.editMeta { meta ->
                meta.displayName(
                    Component.text("Gift #${gift.id} to ${gift.recipientName}", NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
                )
                val lore = mutableListOf(
                    Component.empty(),
                    Component.text("  Sent: ", NamedTextColor.GRAY).append(Component.text(formatTime(gift.createdAt), NamedTextColor.WHITE)).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Contents: ", NamedTextColor.GRAY).append(Component.text(contentsSummary(items, gift.coins), NamedTextColor.AQUA)).decoration(TextDecoration.ITALIC, false),
                    Component.text("  Status: ", NamedTextColor.GRAY).append(Component.text(gift.status, statusColor)).decoration(TextDecoration.ITALIC, false)
                )
                if (gift.status == "PENDING") {
                    lore.add(Component.empty())
                    lore.add(Component.text("  Click to cancel & refund", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false))
                }
                meta.lore(lore)
            }
            gui.setItem(slots[idx], icon) { p, _ ->
                if (gift.status == "PENDING") {
                    cancelGift(p, gift.id)
                    openSentGui(p, page)
                }
            }
        }

        val total = getSentCount(player.uniqueId)
        val totalPages = maxOf(1, (total + slots.size - 1) / slots.size)
        if (page > 0) gui.setItem(37, simpleItem(Material.ARROW, "Previous Page", NamedTextColor.YELLOW)) { p, _ -> openSentGui(p, page - 1) }
        if (page < totalPages - 1) gui.setItem(39, simpleItem(Material.ARROW, "Next Page", NamedTextColor.YELLOW)) { p, _ -> openSentGui(p, page + 1) }

        gui.setItem(40, simpleItem(Material.ARROW, "Back", NamedTextColor.WHITE)) { p, _ -> openMainGui(p) }

        plugin.guiManager.open(player, gui)
    }
}
