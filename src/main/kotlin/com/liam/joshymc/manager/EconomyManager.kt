package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import java.text.DecimalFormat
import java.util.UUID

class EconomyManager(private val plugin: Joshymc) {

    companion object {
        // Largest-first so formatShort can pick the biggest suffix a magnitude qualifies for.
        private val SHORT_SCALE_TIERS = listOf(
            1e33 to "Dc",
            1e30 to "No",
            1e27 to "Oc",
            1e24 to "Sp",
            1e21 to "Sx",
            1e18 to "Qi",
            1e15 to "Q",
            1_000_000_000_000.0 to "T",
            1_000_000_000.0 to "B",
            1_000_000.0 to "M",
            1_000.0 to "K"
        )
    }

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
     * Same as [format], but for per-share stock prices that can crash to fractions of a
     * cent — the normal 2-decimal [formatter] would round anything below $0.01 down to
     * "$0.00", hiding that the stock still has a real, tradable, nonzero price. Below one
     * cent this shows just enough decimal places to reveal the first two significant
     * digits (e.g. 0.00001 -> "$0.00001", 0.0042 -> "$0.0042") instead of a fixed count.
     */
    fun formatStockPrice(price: Double): String {
        if (price.isNaN() || !price.isFinite() || price <= 0.0) return format(0.0)
        if (price >= 0.01) return format(price)

        val magnitude = kotlin.math.floor(kotlin.math.log10(price)).toInt()
        val decimals = (-magnitude + 1).coerceIn(2, 10)
        var digits = DecimalFormat("0." + "0".repeat(decimals)).format(price)
        if (digits.contains('.')) {
            digits = digits.trimEnd('0')
            if (digits.endsWith('.')) digits += "0"
        }
        return "$$digits"
    }

    /**
     * Compact K/M/B/T/Q/Qi/Sx/Sp/Oc/No/Dc formatting for large financial values (balances,
     * market caps, P/L, volume, shares, etc.). Display-only — never round the underlying
     * stored value. Handles negative amounts by formatting the sign then the magnitude.
     * Ordered largest-first so magnitude picks the biggest suffix it qualifies for; also
     * re-checks after rounding to 2 decimals so e.g. 999.996T doesn't round up to "1000T"
     * when a bigger suffix ("1Q") is available.
     */
    fun formatShort(amount: Double): String {
        if (amount.isNaN()) return "0"
        if (amount.isInfinite()) return if (amount > 0) "∞" else "-∞"

        val sign = if (amount < 0) "-" else ""
        val magnitude = kotlin.math.abs(amount)

        var index = SHORT_SCALE_TIERS.indexOfFirst { (threshold, _) -> magnitude >= threshold }
        if (index == -1) {
            return "$sign${trimNumeric("%.2f".format(magnitude))}"
        }

        var scaled = magnitude / SHORT_SCALE_TIERS[index].first
        while (scaled >= 1000.0 && index > 0) {
            index--
            scaled = magnitude / SHORT_SCALE_TIERS[index].first
        }

        return "$sign${trimNumeric("%.2f".format(scaled))}${SHORT_SCALE_TIERS[index].second}"
    }

    private fun trimNumeric(numeric: String): String {
        return if (numeric.contains('.')) numeric.trimEnd('0').trimEnd('.') else numeric
    }

    /**
     * Parses shorthand amounts: 10k, 1.5m, 2b, 1t, 1q, 1qi, 1sx, 1sp, 1oc, 1no, 1dc,
     * or plain numbers. Returns null if the input is invalid.
     */
    fun parseAmount(input: String): Double? {
        val cleaned = input.replace(",", "").replace("$", "").trim().lowercase()
        if (cleaned.isEmpty()) return null

        // Longest suffix first so "qi" isn't swallowed by a stray single-char match.
        val suffixes = listOf(
            "dc" to 1e33,
            "no" to 1e30,
            "oc" to 1e27,
            "sp" to 1e24,
            "sx" to 1e21,
            "qi" to 1e18,
            "q" to 1e15,
            "t" to 1_000_000_000_000.0,
            "b" to 1_000_000_000.0,
            "m" to 1_000_000.0,
            "k" to 1_000.0
        )

        val match = suffixes.firstOrNull { (suffix, _) -> cleaned.endsWith(suffix) }

        return if (match != null) {
            val number = cleaned.removeSuffix(match.first).toDoubleOrNull() ?: return null
            if (number < 0) return null
            number * match.second
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
