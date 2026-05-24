package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import java.util.UUID
import kotlin.math.pow

class InvestManager(private val plugin: Joshymc) {

    companion object {
        private const val INTEREST_RATE = 0.0025  // 0.25% per hour
    }

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS bank_investments (
                uuid TEXT PRIMARY KEY,
                balance REAL NOT NULL DEFAULT 0.0,
                last_updated INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.logger.info("[Invest] InvestManager started.")
    }

    fun getBalance(uuid: UUID): Double {
        val row = plugin.databaseManager.queryFirst(
            "SELECT balance, last_updated FROM bank_investments WHERE uuid = ?",
            uuid.toString()
        ) { rs -> Pair(rs.getDouble("balance"), rs.getLong("last_updated")) } ?: return 0.0

        val (balance, lastUpdated) = row
        if (balance <= 0.0) return 0.0
        val hoursElapsed = (System.currentTimeMillis() / 1000 - lastUpdated) / 3600.0
        return balance * (1.0 + INTEREST_RATE).pow(hoursElapsed)
    }

    fun deposit(uuid: UUID, amount: Double) {
        val current = getBalance(uuid)
        val now = System.currentTimeMillis() / 1000
        plugin.databaseManager.execute(
            "INSERT INTO bank_investments (uuid, balance, last_updated) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = ?, last_updated = ?",
            uuid.toString(), current + amount, now, current + amount, now
        )
    }

    fun withdraw(uuid: UUID, amount: Double): Boolean {
        val current = getBalance(uuid)
        if (current < amount) return false
        val now = System.currentTimeMillis() / 1000
        val remaining = current - amount
        plugin.databaseManager.execute(
            "INSERT INTO bank_investments (uuid, balance, last_updated) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET balance = ?, last_updated = ?",
            uuid.toString(), remaining, now, remaining, now
        )
        return true
    }
}
