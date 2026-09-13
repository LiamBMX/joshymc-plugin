package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.util.UUID

/**
 * `/casino` Roulette (issue #727) — the result is chosen server-side the instant the
 * bet is confirmed and persisted before any animation plays, so the spin is purely
 * cosmetic and a disconnect/restart can always resolve the bet safely and exactly once.
 */
class CasinoRouletteManager(private val plugin: Joshymc) {

    enum class BetType { RED, BLACK, GREEN, ODD, EVEN, LOW, HIGH, NUMBER }
    enum class Status { PENDING, RESOLVED }

    companion object {
        val RED_NUMBERS = setOf(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36)

        fun colorOf(number: Int): NamedTextColor = when {
            number == 0 -> NamedTextColor.GREEN
            number in RED_NUMBERS -> NamedTextColor.RED
            else -> NamedTextColor.DARK_GRAY
        }
    }

    data class Bet(
        val id: Int,
        val uuid: UUID,
        val name: String,
        val betType: BetType,
        val betValue: Int?,
        val amount: Double,
        val result: Int,
        val status: Status
    )

    private val random = SecureRandom()

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS casino_roulette_bets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                bet_type TEXT NOT NULL,
                bet_value INTEGER,
                amount REAL NOT NULL,
                result INTEGER NOT NULL,
                payout REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                resolved_at INTEGER
            )
            """.trimIndent()
        )

        // Restart safety: the result was already generated server-side before the
        // restart, so resolving now (instead of refunding) is the fair outcome — the
        // spin had already happened, only the payout step was interrupted.
        val pending = plugin.databaseManager.query(
            "SELECT * FROM casino_roulette_bets WHERE status = ?", Status.PENDING.name
        ) { rs -> mapRow(rs) }

        for (bet in pending) {
            resolve(bet.id)
            plugin.casinoManager.log("Recovered interrupted Roulette bet #${bet.id} on restart — resolved.")
        }

        plugin.logger.info("[Casino] Roulette started.")
    }

    fun stop() {}

    private fun mapRow(rs: java.sql.ResultSet): Bet {
        val betValueRaw = rs.getInt("bet_value")
        return Bet(
            id = rs.getInt("id"),
            uuid = UUID.fromString(rs.getString("uuid")),
            name = rs.getString("name"),
            betType = BetType.valueOf(rs.getString("bet_type")),
            betValue = if (rs.wasNull()) null else betValueRaw,
            amount = rs.getDouble("amount"),
            result = rs.getInt("result"),
            status = Status.valueOf(rs.getString("status"))
        )
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.CASINO)
    }

    fun hasPendingBet(uuid: UUID): Boolean {
        return plugin.databaseManager.queryFirst(
            "SELECT 1 FROM casino_roulette_bets WHERE uuid = ? AND status = ? LIMIT 1",
            uuid.toString(), Status.PENDING.name
        ) { true } ?: false
    }

    fun getBet(id: Int): Bet? = plugin.databaseManager.queryFirst("SELECT * FROM casino_roulette_bets WHERE id = ?", id) { mapRow(it) }

    /** Gross payout multiplier (includes the stake) for a winning bet of this type, house-edge applied. */
    fun payoutMultiplier(betType: BetType): Double {
        val houseEdge = plugin.casinoManager.houseEdge(CasinoManager.Game.ROULETTE)
        val fair = if (betType == BetType.NUMBER) 37.0 else 37.0 / 18.0
        return fair * (1.0 - houseEdge)
    }

    private fun wins(betType: BetType, betValue: Int?, result: Int): Boolean = when (betType) {
        BetType.RED -> result in RED_NUMBERS
        BetType.BLACK -> result != 0 && result !in RED_NUMBERS
        BetType.GREEN -> result == 0
        BetType.ODD -> result != 0 && result % 2 == 1
        BetType.EVEN -> result != 0 && result % 2 == 0
        BetType.LOW -> result in 1..18
        BetType.HIGH -> result in 19..36
        BetType.NUMBER -> betValue != null && result == betValue
    }

    /**
     * Places the bet: withdraws once, rolls the result immediately (secure server-side
     * RNG, before any animation), and persists it as PENDING. Returns the bet row so
     * the GUI can animate toward [Bet.result] cosmetically.
     */
    fun placeBet(player: Player, betType: BetType, betValue: Int?, amount: Double): Bet? {
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.ROULETTE)) {
            send(player, "Roulette is currently disabled.", NamedTextColor.RED)
            return null
        }
        if (hasPendingBet(player.uniqueId)) {
            send(player, "You already have a Roulette bet in progress.", NamedTextColor.RED)
            return null
        }
        if (betType == BetType.NUMBER && (betValue == null || betValue !in 0..36)) {
            send(player, "Invalid number.", NamedTextColor.RED)
            return null
        }
        if (!plugin.casinoManager.placeBet(player, CasinoManager.Game.ROULETTE, amount)) return null

        val result = random.nextInt(37)
        plugin.databaseManager.execute(
            "INSERT INTO casino_roulette_bets (uuid, name, bet_type, bet_value, amount, result, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            player.uniqueId.toString(), player.name, betType.name, betValue, amount, result, Status.PENDING.name, System.currentTimeMillis()
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { it.getInt("id") } ?: -1
        plugin.casinoManager.log("Roulette #$id placed — player=${player.name} type=${betType.name} value=$betValue amount=$amount result=$result")
        return getBet(id)
    }

    /**
     * Authoritative resolution — called once the (cosmetic) animation finishes, or
     * immediately on recovery from an interrupted restart. Atomic: the PENDING ->
     * RESOLVED transition only succeeds once.
     */
    fun resolve(id: Int) {
        val bet = getBet(id) ?: return
        if (bet.status != Status.PENDING) return

        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE casino_roulette_bets SET status = ?, resolved_at = ? WHERE id = ? AND status = ?",
            Status.RESOLVED.name, System.currentTimeMillis(), id, Status.PENDING.name
        )
        if (rows == 0) return

        val won = wins(bet.betType, bet.betValue, bet.result)
        if (won) {
            val payout = bet.amount * payoutMultiplier(bet.betType)
            plugin.databaseManager.execute("UPDATE casino_roulette_bets SET payout = ? WHERE id = ?", payout, id)
            plugin.casinoManager.payout(bet.uuid, CasinoManager.Game.ROULETTE, payout)
            Bukkit.getPlayer(bet.uuid)?.let {
                send(it, "Roulette landed on ${bet.result} — you won ${plugin.economyManager.formatShort(payout)}!", NamedTextColor.GREEN)
            }
        } else {
            plugin.casinoManager.recordLoss(bet.uuid, bet.amount)
            Bukkit.getPlayer(bet.uuid)?.let {
                send(it, "Roulette landed on ${bet.result} — you lost ${plugin.economyManager.formatShort(bet.amount)}.", NamedTextColor.RED)
            }
        }
        plugin.casinoManager.log("Roulette #$id resolved — player=${bet.name} result=${bet.result} won=$won")
    }
}
