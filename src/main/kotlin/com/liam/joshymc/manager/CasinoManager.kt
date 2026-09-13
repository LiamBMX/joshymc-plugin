package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared backend for the `/casino` system (issue #727) — Mines, Roulette, Crash and
 * Towers all route their wager/payout/statistics through this manager so there is a
 * single Coins-only money path with one set of validation rules. Never touches
 * [CreditsManager] or any other currency — Casino is Coins ([EconomyManager]) only.
 */
class CasinoManager(private val plugin: Joshymc) {

    enum class Game(val key: String, val statLabel: String) {
        MINES("mines", "Mines"),
        ROULETTE("roulette", "Roulette"),
        CRASH("crash", "Crash"),
        TOWERS("towers", "Towers")
    }

    data class CasinoStats(
        val wagered: Double,
        val won: Double,
        val lost: Double,
        val biggestWin: Double,
        val minesPlayed: Int,
        val minesWins: Int,
        val roulettePlayed: Int,
        val rouletteWins: Int,
        val crashPlayed: Int,
        val crashWins: Int,
        val towersPlayed: Int,
        val towersWins: Int
    )

    /** A pending "type an amount in chat" prompt shared by every Casino game. */
    data class PendingBetInput(val expiresAt: Long, val onCancel: (Player) -> Unit, val onAmount: (Player, Double) -> Unit)

    var enabled = true; private set
    var minBet = 1000.0; private set
    var maxBet = -1.0; private set // -1 = unlimited

    private val gameEnabled = mutableMapOf<Game, Boolean>()
    private val gameHouseEdge = mutableMapOf<Game, Double>()

    val pendingBetInputs = ConcurrentHashMap<UUID, PendingBetInput>()

    fun start() {
        val cfg = plugin.config
        enabled = cfg.getBoolean("casino.enabled", true)
        minBet = cfg.getDouble("casino.min-bet", 1000.0)
        maxBet = cfg.getDouble("casino.max-bet", -1.0)

        for (game in Game.entries) {
            gameEnabled[game] = cfg.getBoolean("casino.${game.key}.enabled", true)
            gameHouseEdge[game] = (cfg.getDouble("casino.${game.key}.house-edge-percent", 3.0) / 100.0).coerceIn(0.0, 0.9)
        }

        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS casino_stats (
                uuid TEXT PRIMARY KEY,
                wagered REAL NOT NULL DEFAULT 0,
                won REAL NOT NULL DEFAULT 0,
                lost REAL NOT NULL DEFAULT 0,
                biggest_win REAL NOT NULL DEFAULT 0,
                mines_played INTEGER NOT NULL DEFAULT 0,
                mines_wins INTEGER NOT NULL DEFAULT 0,
                roulette_played INTEGER NOT NULL DEFAULT 0,
                roulette_wins INTEGER NOT NULL DEFAULT 0,
                crash_played INTEGER NOT NULL DEFAULT 0,
                crash_wins INTEGER NOT NULL DEFAULT 0,
                towers_played INTEGER NOT NULL DEFAULT 0,
                towers_wins INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        plugin.logger.info("[Casino] CasinoManager started.")
    }

    fun stop() {
        pendingBetInputs.clear()
    }

    fun isGameEnabled(game: Game): Boolean = enabled && (gameEnabled[game] ?: true)

    fun houseEdge(game: Game): Double = gameHouseEdge[game] ?: 0.03

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.CASINO)
    }

    /**
     * Validates a wager amount against the global Casino rules (enabled, min/max, balance).
     * Returns a player-facing error message, or null if the bet is valid.
     */
    fun validateBet(player: Player, game: Game, amount: Double): String? {
        if (!isGameEnabled(game)) return "That Casino game is currently disabled."
        if (amount.isNaN() || !amount.isFinite() || amount <= 0.0) return "Invalid bet amount."
        if (amount < minBet) return "Minimum Casino bet is ${plugin.economyManager.format(minBet)}."
        if (maxBet > 0 && amount > maxBet) return "Maximum Casino bet is ${plugin.economyManager.format(maxBet)}."
        if (plugin.economyManager.getBalance(player) < amount) return "You do not have enough money to place this bet."
        return null
    }

    /**
     * Withdraws the wager exactly once and records it against the player's stats.
     * Callers must have already validated with [validateBet] and must not call this
     * more than once per game session.
     */
    fun placeBet(player: Player, game: Game, amount: Double): Boolean {
        val error = validateBet(player, game, amount)
        if (error != null) {
            send(player, error, NamedTextColor.RED)
            return false
        }
        if (!plugin.economyManager.withdraw(player.uniqueId, amount)) {
            send(player, "Transaction failed.", NamedTextColor.RED)
            return false
        }

        ensureStatsRow(player.uniqueId)
        val playedColumn = "${game.key}_played"
        plugin.databaseManager.execute(
            "UPDATE casino_stats SET wagered = wagered + ?, $playedColumn = $playedColumn + 1 WHERE uuid = ?",
            amount, player.uniqueId.toString()
        )
        return true
    }

    /** Authoritative payout — deposits once and updates win stats. */
    fun payout(uuid: UUID, game: Game, amount: Double) {
        if (amount <= 0.0) return
        plugin.economyManager.deposit(uuid, amount)
        ensureStatsRow(uuid)
        val winColumn = "${game.key}_wins"
        plugin.databaseManager.execute(
            "UPDATE casino_stats SET won = won + ?, biggest_win = MAX(biggest_win, ?), $winColumn = $winColumn + 1 WHERE uuid = ?",
            amount, amount, uuid.toString()
        )
    }

    /** Records a lost wager (no payout) against the player's stats. */
    fun recordLoss(uuid: UUID, amount: Double) {
        if (amount <= 0.0) return
        ensureStatsRow(uuid)
        plugin.databaseManager.execute(
            "UPDATE casino_stats SET lost = lost + ? WHERE uuid = ?",
            amount, uuid.toString()
        )
    }

    private fun ensureStatsRow(uuid: UUID) {
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO casino_stats (uuid) VALUES (?)", uuid.toString()
        )
    }

    fun getStats(uuid: UUID): CasinoStats {
        return plugin.databaseManager.queryFirst(
            "SELECT * FROM casino_stats WHERE uuid = ?", uuid.toString()
        ) { rs ->
            CasinoStats(
                wagered = rs.getDouble("wagered"),
                won = rs.getDouble("won"),
                lost = rs.getDouble("lost"),
                biggestWin = rs.getDouble("biggest_win"),
                minesPlayed = rs.getInt("mines_played"),
                minesWins = rs.getInt("mines_wins"),
                roulettePlayed = rs.getInt("roulette_played"),
                rouletteWins = rs.getInt("roulette_wins"),
                crashPlayed = rs.getInt("crash_played"),
                crashWins = rs.getInt("crash_wins"),
                towersPlayed = rs.getInt("towers_played"),
                towersWins = rs.getInt("towers_wins")
            )
        } ?: CasinoStats(0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0)
    }

    /**
     * Shared bet-amount chat prompt used by every Casino game (Mines, Roulette, Crash,
     * Towers) so there's one input/parsing path — reuses [EconomyManager.parseAmount].
     * [onCancel] is the caller's "return to the game GUI I was on" hook — it fires on
     * `cancel` and on timeout so a chat prompt never strands the player outside every
     * Casino GUI (issue #733).
     */
    fun promptAmount(player: Player, onCancel: (Player) -> Unit, onAmount: (Player, Double) -> Unit) {
        pendingBetInputs[player.uniqueId] = PendingBetInput(System.currentTimeMillis() + 60_000L, onCancel, onAmount)
        player.closeInventory()
        send(player, "Enter your Casino bet amount in chat (e.g. 10k, 1.5m). Type cancel to cancel.", NamedTextColor.YELLOW)
    }

    fun isExpired(expiresAt: Long) = System.currentTimeMillis() > expiresAt

    /** Logs a meaningful Casino transaction (never per-click) for economy auditability. */
    fun log(message: String) {
        plugin.logger.info("[Casino] $message")
    }
}
