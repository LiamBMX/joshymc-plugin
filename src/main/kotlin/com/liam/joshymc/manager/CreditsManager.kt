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
 * "Credits" — a secondary currency only obtainable via /credits admin
 * grants or passively from playtime (0.1 credit per hour, tracked against
 * credited_hours so hours already paid out aren't double-counted).
 */
class CreditsManager(private val plugin: Joshymc) : Listener {

    companion object {
        const val CREDITS_PER_HOUR = 0.1
    }

    private val formatter = DecimalFormat("#,##0.0")
    private var tickTaskId: Int = -1

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS credits (
                uuid TEXT PRIMARY KEY,
                balance REAL NOT NULL DEFAULT 0.0,
                credited_hours INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        plugin.server.pluginManager.registerEvents(this, plugin)

        for (player in Bukkit.getOnlinePlayers()) {
            awardPlaytimeCredits(player.uniqueId)
        }

        // Every 5 minutes, check online players for newly-crossed playtime
        // hours and pay out 0.1 credits per hour since they were last paid.
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
        deposit(uuid, delta * CREDITS_PER_HOUR)
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
        plugin.databaseManager.execute(
            "INSERT INTO credits (uuid, balance, credited_hours) VALUES (?, ?, 0) ON CONFLICT(uuid) DO UPDATE SET balance = ?",
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

    fun format(amount: Double): String {
        return formatter.format(amount)
    }
}
