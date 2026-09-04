package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import java.text.DecimalFormat
import java.util.UUID

class EconomyManager(private val plugin: Joshymc) {

    private val formatter = DecimalFormat("#,##0.00")

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS economy (
                uuid TEXT PRIMARY KEY,
                balance REAL NOT NULL DEFAULT 0.0
            )
        """.trimIndent())

        plugin.logger.info("[Economy] EconomyManager started.")
    }

    fun getBalance(uuid: UUID): Double {
        return plugin.databaseManager.queryFirst(
            "SELECT balance FROM economy WHERE uuid = ?",
            uuid.toString()
        ) { rs -> rs.getDouble("balance") } ?: 0.0
    }

    fun getBalance(player: Player): Double {
        return getBalance(player.uniqueId)
    }

    fun setBalance(uuid: UUID, amount: Double) {
        plugin.databaseManager.execute(
            "INSERT INTO economy (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = ?",
            uuid.toString(), amount, amount
        )
    }

    fun deposit(uuid: UUID, amount: Double) {
        val current = getBalance(uuid)
        setBalance(uuid, current + amount)
    }

    fun withdraw(uuid: UUID, amount: Double): Boolean {
        val current = getBalance(uuid)
        if (current < amount) return false
        setBalance(uuid, current - amount)
        return true
    }

    fun has(uuid: UUID, amount: Double): Boolean {
        return getBalance(uuid) >= amount
    }

    fun format(amount: Double): String {
        return "$${formatter.format(amount)}"
    }

    /**
     * Compact K/M/B/T formatting for large financial values (balances, market caps,
     * P/L, volume, shares, etc.). Display-only — never round the underlying stored value.
     * Handles negative amounts by formatting the sign then the magnitude.
     */
    fun formatShort(amount: Double): String {
        if (amount.isNaN()) return "0"
        if (amount.isInfinite()) return if (amount > 0) "∞" else "-∞"

        val sign = if (amount < 0) "-" else ""
        val magnitude = kotlin.math.abs(amount)

        val (scaled, suffix) = when {
            magnitude >= 1_000_000_000_000.0 -> magnitude / 1_000_000_000_000.0 to "T"
            magnitude >= 1_000_000_000.0 -> magnitude / 1_000_000_000.0 to "B"
            magnitude >= 1_000_000.0 -> magnitude / 1_000_000.0 to "M"
            magnitude >= 1_000.0 -> magnitude / 1_000.0 to "K"
            else -> magnitude to ""
        }

        var numeric = "%.2f".format(scaled)
        if (numeric.contains('.')) {
            numeric = numeric.trimEnd('0').trimEnd('.')
        }

        return "$sign$numeric$suffix"
    }

    /**
     * Parses shorthand amounts: 10k, 1.5m, 2b, 1t, or plain numbers.
     * Returns null if the input is invalid.
     */
    fun parseAmount(input: String): Double? {
        val cleaned = input.replace(",", "").replace("$", "").trim().lowercase()
        if (cleaned.isEmpty()) return null

        val suffixes = mapOf(
            'k' to 1_000.0,
            'm' to 1_000_000.0,
            'b' to 1_000_000_000.0,
            't' to 1_000_000_000_000.0
        )

        val lastChar = cleaned.last()
        val multiplier = suffixes[lastChar]

        return if (multiplier != null) {
            val number = cleaned.dropLast(1).toDoubleOrNull() ?: return null
            if (number < 0) return null
            number * multiplier
        } else {
            val number = cleaned.toDoubleOrNull() ?: return null
            if (number < 0) return null
            number
        }
    }

    fun getTopBalances(limit: Int): List<Pair<String, Double>> {
        return plugin.databaseManager.query(
            "SELECT uuid, balance FROM economy ORDER BY balance DESC LIMIT ?",
            limit
        ) { rs -> Pair(rs.getString("uuid"), rs.getDouble("balance")) }
    }
}
