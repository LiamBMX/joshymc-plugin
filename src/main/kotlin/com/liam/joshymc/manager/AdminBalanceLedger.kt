package com.liam.joshymc.manager

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Outcome of an operator balance change (issue #1162). */
sealed class AdminBalanceResult {
    /** The balance went from [previous] to [current]. */
    data class Changed(val previous: Double, val current: Double) : AdminBalanceResult()
    /** A removal asked for more than the player holds; nothing changed. */
    data class Insufficient(val balance: Double) : AdminBalanceResult()
}

/**
 * Operator REMOVE / SET for a balance table (`economy` or `credits`, both keyed by `uuid`
 * with a `balance` column). The read, the write and [audit] all run in one database
 * transaction, which holds the connection lock, so no other balance update can land
 * between them and the audit row is rolled back with the change if anything fails.
 *
 * Balances are rounded to the currency's display [scale] (cents for Coins, tenths for
 * Credits), so what the operator sees is exactly what is stored.
 */
internal class AdminBalanceLedger(
    private val db: SqliteDatabase,
    private val table: String,
    private val scale: Int,
) {

    fun remove(uuid: UUID, amount: Double, audit: (AdminBalanceResult.Changed) -> Unit): AdminBalanceResult {
        require(amount.isFinite() && amount > 0.0) { "Invalid removal amount: $amount" }
        var result: AdminBalanceResult? = null
        db.transaction {
            val previous = round(read(uuid))
            val wanted = round(amount)
            if (previous < wanted) {
                result = AdminBalanceResult.Insufficient(previous.toDouble())
                return@transaction
            }
            val current = previous.subtract(wanted)
            write(uuid, current)
            val changed = AdminBalanceResult.Changed(previous.toDouble(), current.toDouble())
            audit(changed)
            result = changed
        }
        return result ?: error("Balance transaction produced no result")
    }

    fun set(uuid: UUID, amount: Double, audit: (AdminBalanceResult.Changed) -> Unit): AdminBalanceResult.Changed {
        require(amount.isFinite() && amount >= 0.0) { "Invalid balance: $amount" }
        var result: AdminBalanceResult.Changed? = null
        db.transaction {
            val previous = round(read(uuid))
            val current = round(amount)
            write(uuid, current)
            val changed = AdminBalanceResult.Changed(previous.toDouble(), current.toDouble())
            audit(changed)
            result = changed
        }
        return result ?: error("Balance transaction produced no result")
    }

    private fun read(uuid: UUID): Double =
        db.queryFirst("SELECT balance FROM $table WHERE uuid = ?", uuid.toString()) { rs -> rs.getDouble("balance") } ?: 0.0

    private fun write(uuid: UUID, balance: BigDecimal) {
        db.execute(
            "INSERT INTO $table (uuid, balance) VALUES (?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = excluded.balance",
            uuid.toString(), balance.toDouble()
        )
    }

    private fun round(value: Double): BigDecimal =
        BigDecimal.valueOf(if (value.isFinite()) value else 0.0).setScale(scale, RoundingMode.HALF_EVEN).max(BigDecimal.ZERO.setScale(scale))
}
