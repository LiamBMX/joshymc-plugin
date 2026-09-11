package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.sql.ResultSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 1v1 Player-vs-Player Coinflip betting (issue #642). Both players' bets are
 * escrowed into `coinflips` the moment they commit (create/join), the winner
 * is chosen server-side before any animation plays, and the payout is driven
 * entirely by the manager — the animation GUI is a cosmetic viewer, not the
 * source of truth. No house cut: pot = both bets, winner gets it all.
 */
class CoinflipManager(private val plugin: Joshymc) {

    enum class Status { WAITING, LOCKED, FLIPPING, COMPLETED, CANCELLED }

    data class CoinflipInfo(
        val id: Int,
        val creatorUuid: UUID,
        val creatorName: String,
        val betAmount: Double,
        val challengerUuid: UUID?,
        val challengerName: String?,
        val status: Status,
        val winnerUuid: UUID?,
        val createdAt: Long,
        val completedAt: Long?
    )

    var minBet = 1.0; private set
    var maxBet = -1.0; private set // -1 = unlimited
    var maxWaitingPerPlayer = 1; private set
    var animationDurationTicks = 80L; private set
    var resultDisplayTicks = 50L; private set

    /** uuid -> expiry timestamp for the "type your Coinflip amount in chat" flow. */
    val pendingCreateInputs = ConcurrentHashMap<UUID, Long>()

    fun isExpired(expiresAt: Long) = System.currentTimeMillis() > expiresAt

    fun start() {
        val cfg = plugin.config
        minBet = cfg.getDouble("coinflip.min-bet", 1.0)
        maxBet = cfg.getDouble("coinflip.max-bet", -1.0)
        maxWaitingPerPlayer = cfg.getInt("coinflip.max-waiting-per-player", 1).coerceAtLeast(1)
        animationDurationTicks = cfg.getLong("coinflip.animation-duration-ticks", 80L).coerceAtLeast(20L)
        resultDisplayTicks = cfg.getLong("coinflip.result-display-ticks", 50L).coerceAtLeast(10L)

        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS coinflips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                creator_uuid TEXT NOT NULL,
                creator_name TEXT NOT NULL,
                bet_amount REAL NOT NULL,
                challenger_uuid TEXT,
                challenger_name TEXT,
                status TEXT NOT NULL,
                winner_uuid TEXT,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )
            """.trimIndent()
        )

        // Any Coinflip still LOCKED/FLIPPING at this point had its animation task
        // killed (JVM restart, or /reload's scheduler.cancelTasks) before it could
        // pay out. The pot hasn't moved yet, so the only money-safe recovery is to
        // refund both escrowed bets exactly once and cancel the row. WAITING rows
        // are left untouched — their escrow is still correctly held and they simply
        // reappear in the GUI.
        recoverInterrupted()

        plugin.logger.info("[Coinflip] CoinflipManager started.")
    }

    fun stop() {
        pendingCreateInputs.clear()
    }

    private fun recoverInterrupted() {
        val stuck = plugin.databaseManager.query(
            "SELECT * FROM coinflips WHERE status = ? OR status = ?",
            Status.LOCKED.name, Status.FLIPPING.name
        ) { rs -> mapRow(rs) }

        for (cf in stuck) {
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE coinflips SET status = ?, completed_at = ? WHERE id = ? AND (status = ? OR status = ?)",
                Status.CANCELLED.name, System.currentTimeMillis(), cf.id, Status.LOCKED.name, Status.FLIPPING.name
            )
            if (rows == 0) continue

            plugin.economyManager.deposit(cf.creatorUuid, cf.betAmount)
            if (cf.challengerUuid != null) plugin.economyManager.deposit(cf.challengerUuid, cf.betAmount)
            plugin.logger.warning("[Coinflip] Recovered interrupted Coinflip #${cf.id} — refunded both sides.")
        }
    }

    private fun mapRow(rs: ResultSet): CoinflipInfo {
        val challengerUuidStr = rs.getString("challenger_uuid")
        val winnerUuidStr = rs.getString("winner_uuid")
        val id = rs.getInt("id")
        val creatorUuid = UUID.fromString(rs.getString("creator_uuid"))
        val creatorName = rs.getString("creator_name")
        val betAmount = rs.getDouble("bet_amount")
        val status = Status.valueOf(rs.getString("status"))
        val createdAt = rs.getLong("created_at")
        val completedAtRaw = rs.getLong("completed_at")
        val completedAt = if (rs.wasNull()) null else completedAtRaw
        return CoinflipInfo(
            id = id,
            creatorUuid = creatorUuid,
            creatorName = creatorName,
            betAmount = betAmount,
            challengerUuid = challengerUuidStr?.let { UUID.fromString(it) },
            challengerName = rs.getString("challenger_name"),
            status = status,
            winnerUuid = winnerUuidStr?.let { UUID.fromString(it) },
            createdAt = createdAt,
            completedAt = completedAt
        )
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.DEFAULT)
    }

    // ---- Queries ----

    fun getCoinflip(id: Int): CoinflipInfo? {
        return plugin.databaseManager.queryFirst("SELECT * FROM coinflips WHERE id = ?", id) { rs -> mapRow(rs) }
    }

    fun getWaitingCoinflips(page: Int, pageSize: Int = 36): List<CoinflipInfo> {
        return plugin.databaseManager.query(
            "SELECT * FROM coinflips WHERE status = ? ORDER BY created_at DESC LIMIT ? OFFSET ?",
            Status.WAITING.name, pageSize, page * pageSize
        ) { rs -> mapRow(rs) }
    }

    fun getTotalWaiting(): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM coinflips WHERE status = ?", Status.WAITING.name
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun getWaitingCountForPlayer(uuid: UUID): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM coinflips WHERE creator_uuid = ? AND status = ?",
            uuid.toString(), Status.WAITING.name
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    /** True if the player is the creator or challenger of a Coinflip that's currently matched and flipping. */
    fun isPlayerBusy(uuid: UUID): Boolean {
        return plugin.databaseManager.queryFirst(
            "SELECT 1 FROM coinflips WHERE (creator_uuid = ? OR challenger_uuid = ?) AND (status = ? OR status = ?) LIMIT 1",
            uuid.toString(), uuid.toString(), Status.LOCKED.name, Status.FLIPPING.name
        ) { true } ?: false
    }

    // ---- Core actions ----

    fun createCoinflip(player: Player, amount: Double) {
        if (!plugin.isFeatureEnabled("coinflip")) {
            send(player, "Coinflip is currently disabled.", NamedTextColor.RED)
            return
        }
        if (!player.hasPermission("joshymc.coinflip.create")) {
            send(player, "No permission.", NamedTextColor.RED)
            return
        }
        if (amount.isNaN() || !amount.isFinite() || amount <= 0.0) {
            send(player, "Invalid amount.", NamedTextColor.RED)
            return
        }
        if (amount < minBet) {
            send(player, "Minimum Coinflip bet is ${plugin.economyManager.format(minBet)}.", NamedTextColor.RED)
            return
        }
        if (maxBet > 0 && amount > maxBet) {
            send(player, "Maximum Coinflip bet is ${plugin.economyManager.format(maxBet)}.", NamedTextColor.RED)
            return
        }
        if (isPlayerBusy(player.uniqueId)) {
            send(player, "You are already in an active Coinflip.", NamedTextColor.RED)
            return
        }
        if (getWaitingCountForPlayer(player.uniqueId) >= maxWaitingPerPlayer) {
            send(player, "You already have a Coinflip waiting for an opponent.", NamedTextColor.RED)
            return
        }
        if (plugin.economyManager.getBalance(player) < amount) {
            send(player, "You do not have enough money to create this Coinflip.", NamedTextColor.RED)
            return
        }
        if (!plugin.economyManager.withdraw(player.uniqueId, amount)) {
            send(player, "Transaction failed.", NamedTextColor.RED)
            return
        }

        plugin.databaseManager.execute(
            "INSERT INTO coinflips (creator_uuid, creator_name, bet_amount, status, created_at) VALUES (?, ?, ?, ?, ?)",
            player.uniqueId.toString(), player.name, amount, Status.WAITING.name, System.currentTimeMillis()
        )

        send(player, "Coinflip created for ${plugin.economyManager.formatShort(amount)}.", NamedTextColor.GREEN)
    }

    fun cancelCoinflip(player: Player, id: Int) {
        val cf = plugin.databaseManager.queryFirst(
            "SELECT * FROM coinflips WHERE id = ? AND creator_uuid = ?",
            id, player.uniqueId.toString()
        ) { rs -> mapRow(rs) }

        if (cf == null || cf.status != Status.WAITING) {
            send(player, "That Coinflip can no longer be cancelled.", NamedTextColor.RED)
            return
        }

        // Anti-dupe: atomically delete only if it's still WAITING. If a challenger
        // claimed it a moment ago, this is a no-op and we must NOT refund.
        val rows = plugin.databaseManager.executeUpdate(
            "DELETE FROM coinflips WHERE id = ? AND creator_uuid = ? AND status = ?",
            id, player.uniqueId.toString(), Status.WAITING.name
        )
        if (rows == 0) {
            send(player, "That Coinflip can no longer be cancelled.", NamedTextColor.RED)
            return
        }

        plugin.economyManager.deposit(player.uniqueId, cf.betAmount)
        send(player, "Coinflip cancelled. ${plugin.economyManager.formatShort(cf.betAmount)} has been refunded.", NamedTextColor.GREEN)
    }

    fun adminCancel(sender: org.bukkit.command.CommandSender, id: Int) {
        val cf = getCoinflip(id)
        if (cf == null || cf.status != Status.WAITING) {
            sender.sendMessage(Component.text("Coinflip #$id is not waiting for an opponent — cannot cancel.", NamedTextColor.RED))
            return
        }

        val rows = plugin.databaseManager.executeUpdate(
            "DELETE FROM coinflips WHERE id = ? AND status = ?", id, Status.WAITING.name
        )
        if (rows == 0) {
            sender.sendMessage(Component.text("Coinflip #$id is not waiting for an opponent — cannot cancel.", NamedTextColor.RED))
            return
        }

        plugin.economyManager.deposit(cf.creatorUuid, cf.betAmount)
        sender.sendMessage(
            Component.text("Cancelled Coinflip #$id — refunded ${plugin.economyManager.formatShort(cf.betAmount)} to ${cf.creatorName}.", NamedTextColor.GREEN)
        )

        Bukkit.getPlayer(cf.creatorUuid)?.let {
            send(it, "Your Coinflip was cancelled by an admin. ${plugin.economyManager.formatShort(cf.betAmount)} has been refunded.", NamedTextColor.YELLOW)
        }
    }

    fun joinCoinflip(player: Player, id: Int) {
        if (!plugin.isFeatureEnabled("coinflip")) {
            send(player, "Coinflip is currently disabled.", NamedTextColor.RED)
            return
        }

        val cf = getCoinflip(id)
        if (cf == null || cf.status != Status.WAITING) {
            send(player, "That Coinflip is no longer available.", NamedTextColor.RED)
            return
        }
        if (cf.creatorUuid == player.uniqueId) {
            send(player, "You cannot join your own Coinflip.", NamedTextColor.YELLOW)
            return
        }
        if (isPlayerBusy(player.uniqueId)) {
            send(player, "You are already in an active Coinflip.", NamedTextColor.RED)
            return
        }
        if (plugin.economyManager.getBalance(player) < cf.betAmount) {
            send(player, "You do not have enough money to join this Coinflip.", NamedTextColor.RED)
            return
        }

        // Anti-dupe: atomically claim the listing first. If two challengers click at
        // once, only one UPDATE will match the still-WAITING row.
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE coinflips SET status = ?, challenger_uuid = ?, challenger_name = ? WHERE id = ? AND status = ?",
            Status.LOCKED.name, player.uniqueId.toString(), player.name, id, Status.WAITING.name
        )
        if (rows == 0) {
            send(player, "That Coinflip is no longer available.", NamedTextColor.RED)
            return
        }

        if (!plugin.economyManager.withdraw(player.uniqueId, cf.betAmount)) {
            // Roll back the claim so the listing is joinable again.
            plugin.databaseManager.execute(
                "UPDATE coinflips SET status = ?, challenger_uuid = NULL, challenger_name = NULL WHERE id = ?",
                Status.WAITING.name, id
            )
            send(player, "Transaction failed.", NamedTextColor.RED)
            return
        }

        send(player, "You joined ${cf.creatorName}'s ${plugin.economyManager.formatShort(cf.betAmount)} Coinflip.", NamedTextColor.GREEN)
        startFlip(id)
    }

    private fun startFlip(id: Int) {
        val cf = getCoinflip(id) ?: return
        val challengerUuid = cf.challengerUuid ?: return

        // Winner is decided here, before any animation plays, so the visual flip
        // can never be timed/clicked into a different outcome.
        val winner = if (ThreadLocalRandom.current().nextBoolean()) cf.creatorUuid else challengerUuid

        plugin.databaseManager.execute(
            "UPDATE coinflips SET status = ?, winner_uuid = ? WHERE id = ?",
            Status.FLIPPING.name, winner.toString(), id
        )

        com.liam.joshymc.gui.coinflip.CoinflipAnimationGui.start(plugin, cf.copy(status = Status.FLIPPING, winnerUuid = winner))
    }

    /**
     * Authoritative payout — called once by the animation the instant it lands on
     * the winner. Idempotent: the FLIPPING -> COMPLETED transition only succeeds
     * once, so a duplicate call (e.g. a stray reschedule) is a safe no-op.
     */
    fun completeCoinflip(cf: CoinflipInfo) {
        val challengerUuid = cf.challengerUuid ?: return
        val winnerUuid = cf.winnerUuid ?: return

        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE coinflips SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
            Status.COMPLETED.name, System.currentTimeMillis(), cf.id, Status.FLIPPING.name
        )
        if (rows == 0) return

        val pot = cf.betAmount * 2.0
        plugin.economyManager.deposit(winnerUuid, pot)

        val isCreatorWinner = winnerUuid == cf.creatorUuid
        val loserUuid = if (isCreatorWinner) challengerUuid else cf.creatorUuid
        val winnerName = if (isCreatorWinner) cf.creatorName else cf.challengerName ?: "Unknown"

        Bukkit.getPlayer(winnerUuid)?.let {
            send(it, "You won the Coinflip and received ${plugin.economyManager.formatShort(pot)}!", NamedTextColor.GREEN)
        }
        Bukkit.getPlayer(loserUuid)?.let {
            send(it, "$winnerName won the ${plugin.economyManager.formatShort(pot)} Coinflip.", NamedTextColor.RED)
        }
    }
}
