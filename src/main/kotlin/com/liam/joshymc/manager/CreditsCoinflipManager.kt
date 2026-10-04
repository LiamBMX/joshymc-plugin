package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Credits-denominated twin of [CoinflipManager] (issue #1031). Same escrow model
 * (both wagers are taken the moment they commit, winner is chosen server-side
 * before the animation, no house cut) but it lives in its own `credits_coinflips`
 * table and only ever touches [CreditsManager], so Money and Credits flips can
 * never share a wager or pay out in the wrong currency. The wager limits are
 * enforced here, not in the GUI/command layer.
 */
class CreditsCoinflipManager(private val plugin: Joshymc) {

    companion object {
        const val MIN_WAGER = 1L
        const val MAX_WAGER = 10_000L
        const val PERMISSION = "joshymc.credits.coinflip"
        const val MAX_WAGER_MESSAGE = "The maximum Credits Coinflip wager is 10,000 Credits!"

        /** Parses a wager typed by a player. Whole numbers only (commas allowed); anything else is null. */
        fun parseWager(raw: String): Long? {
            val cleaned = raw.trim().replace(",", "")
            if (cleaned.isEmpty() || cleaned.length > 18) return null
            if (!cleaned.all { it in '0'..'9' }) return null
            return cleaned.toLongOrNull()
        }
    }

    data class Flip(
        val id: Int,
        val creatorUuid: UUID,
        val creatorName: String,
        val wager: Long,
        val challengerUuid: UUID?,
        val challengerName: String?,
        val status: CoinflipManager.Status,
        val winnerUuid: UUID?
    )

    /** uuid -> expiry timestamp for the "type your wager in chat" flow. */
    val pendingCreateInputs = ConcurrentHashMap<UUID, Long>()

    fun isExpired(expiresAt: Long) = System.currentTimeMillis() > expiresAt

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS credits_coinflips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                creator_uuid TEXT NOT NULL,
                creator_name TEXT NOT NULL,
                wager INTEGER NOT NULL,
                challenger_uuid TEXT,
                challenger_name TEXT,
                status TEXT NOT NULL,
                winner_uuid TEXT,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )
            """.trimIndent()
        )
        recoverInterrupted()
        plugin.logger.info("[CreditsCoinflip] CreditsCoinflipManager started.")
    }

    fun stop() {
        pendingCreateInputs.clear()
    }

    /** LOCKED/FLIPPING rows had their animation killed before payout: refund both sides once and cancel. */
    private fun recoverInterrupted() {
        val stuck = plugin.databaseManager.query(
            "SELECT * FROM credits_coinflips WHERE status = ? OR status = ?",
            CoinflipManager.Status.LOCKED.name, CoinflipManager.Status.FLIPPING.name
        ) { rs -> mapRow(rs) }

        for (cf in stuck) {
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE credits_coinflips SET status = ?, completed_at = ? WHERE id = ? AND (status = ? OR status = ?)",
                CoinflipManager.Status.CANCELLED.name, System.currentTimeMillis(), cf.id,
                CoinflipManager.Status.LOCKED.name, CoinflipManager.Status.FLIPPING.name
            )
            if (rows == 0) continue
            plugin.creditsManager.deposit(cf.creatorUuid, cf.wager.toDouble())
            if (cf.challengerUuid != null) plugin.creditsManager.deposit(cf.challengerUuid, cf.wager.toDouble())
            plugin.logger.warning("[CreditsCoinflip] Recovered interrupted Credits Coinflip #${cf.id} — refunded both sides.")
        }
    }

    private fun mapRow(rs: ResultSet): Flip = Flip(
        id = rs.getInt("id"),
        creatorUuid = UUID.fromString(rs.getString("creator_uuid")),
        creatorName = rs.getString("creator_name"),
        wager = rs.getLong("wager"),
        challengerUuid = rs.getString("challenger_uuid")?.let { UUID.fromString(it) },
        challengerName = rs.getString("challenger_name"),
        status = CoinflipManager.Status.valueOf(rs.getString("status")),
        winnerUuid = rs.getString("winner_uuid")?.let { UUID.fromString(it) }
    )

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.DEFAULT)
    }

    private fun announce(message: Component) {
        for (viewer in Bukkit.getOnlinePlayers()) {
            if (plugin.settingsManager.getSetting(viewer, CoinflipManager.NOTIFY_SETTING_KEY)) {
                plugin.commsManager.send(viewer, message, CommunicationsManager.Category.DEFAULT)
            }
        }
    }

    fun fmt(amount: Long): String = "%,d Credits".format(amount)

    // ---- Queries ----

    fun getFlip(id: Int): Flip? =
        plugin.databaseManager.queryFirst("SELECT * FROM credits_coinflips WHERE id = ?", id) { rs -> mapRow(rs) }

    fun getWaiting(page: Int, pageSize: Int = 36): List<Flip> =
        plugin.databaseManager.query(
            "SELECT * FROM credits_coinflips WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            CoinflipManager.Status.WAITING.name, pageSize, page * pageSize
        ) { rs -> mapRow(rs) }

    fun getTotalWaiting(): Int =
        plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM credits_coinflips WHERE status = ?", CoinflipManager.Status.WAITING.name
        ) { rs -> rs.getInt("cnt") } ?: 0

    fun getWaitingCountForPlayer(uuid: UUID): Int =
        plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM credits_coinflips WHERE creator_uuid = ? AND status = ?",
            uuid.toString(), CoinflipManager.Status.WAITING.name
        ) { rs -> rs.getInt("cnt") } ?: 0

    fun isPlayerBusy(uuid: UUID): Boolean =
        plugin.databaseManager.queryFirst(
            "SELECT 1 FROM credits_coinflips WHERE (creator_uuid = ? OR challenger_uuid = ?) AND (status = ? OR status = ?) LIMIT 1",
            uuid.toString(), uuid.toString(), CoinflipManager.Status.LOCKED.name, CoinflipManager.Status.FLIPPING.name
        ) { true } ?: false

    // ---- Core actions ----

    fun createFlip(player: Player, wager: Long) {
        if (!plugin.isFeatureEnabled("coinflip")) {
            send(player, "Coinflip is currently disabled.", NamedTextColor.RED)
            return
        }
        if (!player.hasPermission(PERMISSION)) {
            send(player, "No permission.", NamedTextColor.RED)
            return
        }
        if (wager < MIN_WAGER) {
            send(player, "The minimum Credits Coinflip wager is ${fmt(MIN_WAGER)}.", NamedTextColor.RED)
            return
        }
        if (wager > MAX_WAGER) {
            send(player, MAX_WAGER_MESSAGE, NamedTextColor.RED)
            return
        }
        if (isPlayerBusy(player.uniqueId)) {
            send(player, "You are already in an active Credits Coinflip.", NamedTextColor.RED)
            return
        }
        if (getWaitingCountForPlayer(player.uniqueId) >= plugin.coinflipManager.maxWaitingPerPlayer) {
            send(player, "You already have a Credits Coinflip waiting for an opponent.", NamedTextColor.RED)
            return
        }
        if (!plugin.creditsManager.withdraw(player.uniqueId, wager.toDouble())) {
            send(player, "You do not have enough Credits to create this Coinflip.", NamedTextColor.RED)
            return
        }

        plugin.databaseManager.execute(
            "INSERT INTO credits_coinflips (creator_uuid, creator_name, wager, status, created_at) VALUES (?, ?, ?, ?, ?)",
            player.uniqueId.toString(), player.name, wager, CoinflipManager.Status.WAITING.name, System.currentTimeMillis()
        )

        send(player, "Credits Coinflip created for ${fmt(wager)}.", NamedTextColor.GREEN)
        announce(
            Component.text("${player.name} created a Credits Coinflip for ${fmt(wager)}! ", NamedTextColor.YELLOW)
                .append(Component.text("[Join]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/ccoinflip")))
        )
    }

    fun cancelFlip(player: Player, id: Int) {
        val cf = getFlip(id)
        if (cf == null || cf.creatorUuid != player.uniqueId || cf.status != CoinflipManager.Status.WAITING) {
            send(player, "That Credits Coinflip can no longer be cancelled.", NamedTextColor.RED)
            return
        }
        // Delete only if still WAITING; if a challenger claimed it, this no-ops and we must not refund.
        val rows = plugin.databaseManager.executeUpdate(
            "DELETE FROM credits_coinflips WHERE id = ? AND creator_uuid = ? AND status = ?",
            id, player.uniqueId.toString(), CoinflipManager.Status.WAITING.name
        )
        if (rows == 0) {
            send(player, "That Credits Coinflip can no longer be cancelled.", NamedTextColor.RED)
            return
        }
        plugin.creditsManager.deposit(player.uniqueId, cf.wager.toDouble())
        send(player, "Credits Coinflip cancelled. ${fmt(cf.wager)} has been refunded.", NamedTextColor.GREEN)
    }

    fun joinFlip(player: Player, id: Int) {
        if (!plugin.isFeatureEnabled("coinflip")) {
            send(player, "Coinflip is currently disabled.", NamedTextColor.RED)
            return
        }
        if (!player.hasPermission(PERMISSION)) {
            send(player, "No permission.", NamedTextColor.RED)
            return
        }
        val cf = getFlip(id)
        if (cf == null || cf.status != CoinflipManager.Status.WAITING) {
            send(player, "That Credits Coinflip is no longer available.", NamedTextColor.RED)
            return
        }
        if (cf.creatorUuid == player.uniqueId) {
            send(player, "You cannot join your own Credits Coinflip.", NamedTextColor.YELLOW)
            return
        }
        if (isPlayerBusy(player.uniqueId)) {
            send(player, "You are already in an active Credits Coinflip.", NamedTextColor.RED)
            return
        }
        if (plugin.creditsManager.getBalance(player) < cf.wager) {
            send(player, "You do not have enough Credits to join this Coinflip.", NamedTextColor.RED)
            return
        }

        // Atomically claim the listing; only one of several simultaneous joiners matches the WAITING row.
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE credits_coinflips SET status = ?, challenger_uuid = ?, challenger_name = ? WHERE id = ? AND status = ?",
            CoinflipManager.Status.LOCKED.name, player.uniqueId.toString(), player.name, id, CoinflipManager.Status.WAITING.name
        )
        if (rows == 0) {
            send(player, "That Credits Coinflip is no longer available.", NamedTextColor.RED)
            return
        }

        if (!plugin.creditsManager.withdraw(player.uniqueId, cf.wager.toDouble())) {
            plugin.databaseManager.execute(
                "UPDATE credits_coinflips SET status = ?, challenger_uuid = NULL, challenger_name = NULL WHERE id = ?",
                CoinflipManager.Status.WAITING.name, id
            )
            send(player, "Transaction failed.", NamedTextColor.RED)
            return
        }

        send(player, "You joined ${cf.creatorName}'s ${fmt(cf.wager)} Credits Coinflip.", NamedTextColor.GREEN)
        startFlip(id)
    }

    private fun startFlip(id: Int) {
        val cf = getFlip(id) ?: return
        val challengerUuid = cf.challengerUuid ?: return

        val winner = if (ThreadLocalRandom.current().nextBoolean()) cf.creatorUuid else challengerUuid
        plugin.databaseManager.execute(
            "UPDATE credits_coinflips SET status = ?, winner_uuid = ? WHERE id = ?",
            CoinflipManager.Status.FLIPPING.name, winner.toString(), id
        )

        com.liam.joshymc.gui.coinflip.CreditsCoinflipGui.startAnimation(
            plugin, cf.copy(status = CoinflipManager.Status.FLIPPING, winnerUuid = winner)
        )
    }

    /** Authoritative payout. The FLIPPING -> COMPLETED transition only succeeds once, so this is idempotent. */
    fun completeFlip(cf: Flip) {
        val challengerUuid = cf.challengerUuid ?: return
        val winnerUuid = cf.winnerUuid ?: return

        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE credits_coinflips SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
            CoinflipManager.Status.COMPLETED.name, System.currentTimeMillis(), cf.id, CoinflipManager.Status.FLIPPING.name
        )
        if (rows == 0) return

        val pot = cf.wager * 2
        plugin.creditsManager.deposit(winnerUuid, pot.toDouble())

        val creatorWon = winnerUuid == cf.creatorUuid
        val loserUuid = if (creatorWon) challengerUuid else cf.creatorUuid
        val winnerName = if (creatorWon) cf.creatorName else cf.challengerName ?: "Unknown"

        Bukkit.getPlayer(winnerUuid)?.let { send(it, "You won the Credits Coinflip and received ${fmt(pot)}!", NamedTextColor.GREEN) }
        Bukkit.getPlayer(loserUuid)?.let { send(it, "$winnerName won the ${fmt(pot)} Credits Coinflip.", NamedTextColor.RED) }
        announce(Component.text("$winnerName won the Credits Coinflip and received ${fmt(pot)}!", NamedTextColor.GOLD))
    }
}
