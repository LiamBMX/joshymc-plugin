package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.text.DecimalFormat
import java.util.UUID

/**
 * "Credits" — a secondary currency obtainable via /credits admin grants,
 * passively from playtime (credits.per-hour in config.yml, tracked
 * against credited_hours so hours already paid out aren't double-counted),
 * or from other players via /credits pay.
 */
class CreditsManager(private val plugin: Joshymc) : Listener {

    companion object {
        /** Largest single deposit, withdrawal or transfer; the same ceiling as `/credits set`. */
        const val MAX_AMOUNT = 1_000_000_000.0
    }

    private var creditsPerHour: Double = 1.0

    private val formatter = DecimalFormat("#,##0.0")
    private val adminLedger by lazy { AdminBalanceLedger(plugin.databaseManager, "credits", scale = 1) }
    private var tickTaskId: Int = -1

    fun start() {
        creditsPerHour = plugin.config.getDouble("credits.per-hour", 1.0)

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS credits (
                uuid TEXT PRIMARY KEY,
                balance REAL NOT NULL DEFAULT 0.0,
                credited_hours INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        plugin.databaseManager.createTable("CREATE INDEX IF NOT EXISTS idx_credits_balance ON credits (balance DESC, uuid ASC)")

        plugin.server.pluginManager.registerEvents(this, plugin)

        for (player in Bukkit.getOnlinePlayers()) {
            awardPlaytimeCredits(player.uniqueId)
        }

        // Every 5 minutes, check online players for newly-crossed playtime
        // hours and pay out credits.per-hour credits since they were last paid.
        tickTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                awardPlaytimeCredits(player.uniqueId)
            }
        }, 6000L, 6000L)

        plugin.logger.info("[Credits] CreditsManager started.")
    }

    fun stop() {
        if (tickTaskId != -1) {
            plugin.server.scheduler.cancelTask(tickTaskId)
            tickTaskId = -1
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        awardPlaytimeCredits(event.player.uniqueId)
    }

    private fun awardPlaytimeCredits(uuid: UUID) {
        val hours = plugin.playtimeManager.getPlaytime(uuid) / 3600
        val creditedHours = getCreditedHours(uuid)
        if (hours <= creditedHours) return

        val delta = hours - creditedHours
        deposit(uuid, delta * creditsPerHour)
        setCreditedHours(uuid, hours)
    }

    private fun getCreditedHours(uuid: UUID): Long {
        return plugin.databaseManager.queryFirst(
            "SELECT credited_hours FROM credits WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getLong("credited_hours") } ?: 0L
    }

    private fun setCreditedHours(uuid: UUID, hours: Long) {
        plugin.databaseManager.execute(
            "INSERT INTO credits (uuid, balance, credited_hours) VALUES (?, 0.0, ?) ON CONFLICT(uuid) DO UPDATE SET credited_hours = ?",
            uuid.toString(), hours, hours
        )
    }

    fun getBalance(uuid: UUID): Double {
        return plugin.databaseManager.queryFirst(
            "SELECT balance FROM credits WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getDouble("balance") } ?: 0.0
    }

    fun getBalance(player: Player): Double = getBalance(player.uniqueId)

    fun setBalance(uuid: UUID, amount: Double) {
        topCache = null
        plugin.databaseManager.execute(
            "INSERT INTO credits (uuid, balance, credited_hours) VALUES (?, ?, 0) ON CONFLICT(uuid) DO UPDATE SET balance = ?",
            uuid.toString(), amount, amount
        )
    }

    /**
     * Amount rule for [deposit] and [withdraw] (issue #1170): finite, greater than zero and
     * no more than [MAX_AMOUNT]. NaN compares false against everything, so without this a
     * NaN or Infinity slips past `<=` checks and gets written into the balance.
     */
    private fun isValidAmount(amount: Double): Boolean = amount.isFinite() && amount > 0.0 && amount <= MAX_AMOUNT

    /**
     * Adds [amount] in one atomic upsert (creates the row for a new player). Returns false
     * without touching the balance if [amount] is invalid (see [isValidAmount]), zero included.
     * A database error is logged and rethrown so an enclosing transaction rolls back.
     */
    fun deposit(uuid: UUID, amount: Double): Boolean {
        if (!isValidAmount(amount)) {
            if (amount != 0.0) plugin.logger.warning("[Credits] Rejected deposit of $amount to $uuid (invalid amount).")
            return false
        }
        topCache = null
        try {
            plugin.databaseManager.execute(
                "INSERT INTO credits (uuid, balance, credited_hours) VALUES (?, ?, 0) ON CONFLICT(uuid) DO UPDATE SET balance = balance + excluded.balance",
                uuid.toString(), amount
            )
        } catch (e: Exception) {
            plugin.logger.severe("[Credits] Deposit of $amount to $uuid failed: ${e.message}")
            throw e
        }
        topCache = null
        return true
    }

    /**
     * Subtracts [amount] in one conditional UPDATE that only matches when the balance covers
     * it. Returns false (balance unchanged) for insufficient funds, no balance row, or an
     * invalid amount; zero succeeds without a write. A database error is logged and rethrown.
     */
    fun withdraw(uuid: UUID, amount: Double): Boolean {
        if (amount == 0.0) return true
        if (!isValidAmount(amount)) {
            plugin.logger.warning("[Credits] Rejected withdrawal of $amount from $uuid (invalid amount).")
            return false
        }
        topCache = null
        val updated = try {
            plugin.databaseManager.executeUpdate(
                "UPDATE credits SET balance = balance - ? WHERE uuid = ? AND balance >= ?",
                amount, uuid.toString(), amount
            )
        } catch (e: Exception) {
            plugin.logger.severe("[Credits] Withdrawal of $amount from $uuid failed: ${e.message}")
            throw e
        }
        topCache = null
        return updated == 1
    }

    /**
     * Operator removal (issue #1162): atomically takes [amount] (rounded to tenths) if the
     * balance covers it, running [audit] inside the same transaction. Never goes negative.
     */
    fun adminRemove(uuid: UUID, amount: Double, audit: (AdminBalanceResult.Changed) -> Unit): AdminBalanceResult {
        topCache = null
        return adminLedger.remove(uuid, amount, audit).also { topCache = null }
    }

    /** Operator set (issue #1162): atomically replaces the balance, running [audit] in the same transaction. */
    fun adminSet(uuid: UUID, amount: Double, audit: (AdminBalanceResult.Changed) -> Unit): AdminBalanceResult.Changed {
        topCache = null
        return adminLedger.set(uuid, amount, audit).also { topCache = null }
    }

    /**
     * Atomically moves [amount] credits from [from] to [to]. The debit is a guarded
     * UPDATE (balance >= amount) inside one transaction with the credit, so either both
     * balances change or neither does. Returns false if the amount is invalid, the
     * accounts are the same, or the sender can't afford it.
     */
    @Synchronized
    fun transfer(from: UUID, to: UUID, amount: Double): Boolean {
        if (from == to || !isValidAmount(amount)) return false
        if (!(getBalance(to) + amount).isFinite()) return false
        topCache = null
        var ok = false
        try {
            plugin.databaseManager.transaction {
                val debited = plugin.databaseManager.executeUpdate(
                    "UPDATE credits SET balance = balance - ? WHERE uuid = ? AND balance >= ?",
                    amount, from.toString(), amount
                )
                if (debited != 1) return@transaction
                plugin.databaseManager.execute(
                    "INSERT INTO credits (uuid, balance, credited_hours) VALUES (?, ?, 0) ON CONFLICT(uuid) DO UPDATE SET balance = balance + ?",
                    to.toString(), amount, amount
                )
                ok = true
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Credits] Transfer failed: ${e.message}")
            return false
        }
        topCache = null
        return ok
    }

    @Volatile private var topCache: List<Pair<UUID, Double>>? = null
    @Volatile private var topCacheAt: Long = 0L

    /**
     * Top [limit] current balances (highest first, ties broken by UUID so order is stable).
     * Served from a short-lived cache that every balance write invalidates; the refresh is
     * a single indexed LIMIT query. Zero/negative balances are excluded.
     */
    fun getTopBalances(limit: Int = 10): List<Pair<UUID, Double>> {
        val cached = topCache
        if (cached != null && System.currentTimeMillis() - topCacheAt < 30_000L && cached.size >= limit) {
            return cached.take(limit)
        }
        val fresh = plugin.databaseManager.query(
            "SELECT uuid, balance FROM credits WHERE balance > 0 ORDER BY balance DESC, uuid ASC LIMIT ?",
            limit
        ) { rs -> UUID.fromString(rs.getString("uuid")) to rs.getDouble("balance") }
        topCache = fresh
        topCacheAt = System.currentTimeMillis()
        return fresh
    }

    fun format(amount: Double): String {
        return formatter.format(amount)
    }
}
