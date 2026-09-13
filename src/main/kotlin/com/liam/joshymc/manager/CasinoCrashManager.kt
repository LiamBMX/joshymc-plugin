package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.floor

/**
 * `/casino` Crash (issue #727) — one server-wide shared round at a time. The crash
 * point is rolled server-side the moment a round starts and never sent to clients;
 * the multiplier shown to players is just a deterministic function of elapsed time
 * that the manager evaluates on each tick, so nothing about the outcome depends on
 * GUI timing, clicks, or lag.
 */
class CasinoCrashManager(private val plugin: Joshymc) {

    enum class RoundStatus { BETTING, RUNNING, CRASHED, RESETTING }
    enum class BetStatus { PENDING, CASHED_OUT, LOST, REFUNDED }

    companion object {
        /** Exponential growth constant for the multiplier curve: multiplier(t) = e^(GROWTH_RATE * t). */
        private const val GROWTH_RATE = 0.14
        private const val CRASHED_DISPLAY_MS = 4_000L
        private const val RESETTING_MS = 2_000L
        private const val TICK_PERIOD = 4L // 0.2s
    }

    data class RoundState(val id: Int, var status: RoundStatus, val crashPoint: Double, var phaseStart: Long)

    data class LiveBet(val id: Int, val uuid: UUID, val name: String, val bet: Double, var status: BetStatus, var cashoutMultiplier: Double? = null)

    private val random = SecureRandom()
    private var task: BukkitTask? = null
    private var round: RoundState? = null
    private val liveBets = ConcurrentHashMap<UUID, LiveBet>()

    /** Players with the Crash GUI currently open, refreshed every tick. */
    val viewers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    var bettingSeconds = 10; private set

    fun start() {
        bettingSeconds = plugin.config.getInt("casino.crash.betting-seconds", 10).coerceAtLeast(3)

        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS casino_crash_rounds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                crash_point REAL NOT NULL,
                status TEXT NOT NULL,
                started_at INTEGER,
                crashed_at INTEGER,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS casino_crash_bets (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                round_id INTEGER NOT NULL,
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                bet REAL NOT NULL,
                cashout_multiplier REAL,
                payout REAL NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        recoverInterrupted()
        beginNewRound()

        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, TICK_PERIOD, TICK_PERIOD)
        plugin.logger.info("[Casino] Crash started.")
    }

    fun stop() {
        task?.cancel()
        task = null
        viewers.clear()
        liveBets.clear()
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.CASINO)
    }

    /**
     * Restart safety: any round left BETTING/RUNNING from before the restart can no
     * longer be trusted (the in-memory multiplier clock is gone), so it's marked
     * CRASHED and every still-PENDING bet in it is refunded exactly once. Rounds
     * that already reached CRASHED/RESETTING were already fully resolved.
     */
    private fun recoverInterrupted() {
        val stuckRounds = plugin.databaseManager.query(
            "SELECT id FROM casino_crash_rounds WHERE status = ? OR status = ?",
            RoundStatus.BETTING.name, RoundStatus.RUNNING.name
        ) { rs -> rs.getInt("id") }

        for (roundId in stuckRounds) {
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE casino_crash_rounds SET status = ?, crashed_at = ? WHERE id = ? AND (status = ? OR status = ?)",
                RoundStatus.CRASHED.name, System.currentTimeMillis(), roundId, RoundStatus.BETTING.name, RoundStatus.RUNNING.name
            )
            if (rows == 0) continue

            val pendingBets = plugin.databaseManager.query(
                "SELECT id, uuid, bet FROM casino_crash_bets WHERE round_id = ? AND status = ?",
                roundId, BetStatus.PENDING.name
            ) { rs -> Triple(rs.getInt("id"), rs.getString("uuid"), rs.getDouble("bet")) }

            for ((betId, uuidStr, bet) in pendingBets) {
                val betRows = plugin.databaseManager.executeUpdate(
                    "UPDATE casino_crash_bets SET status = ? WHERE id = ? AND status = ?",
                    BetStatus.REFUNDED.name, betId, BetStatus.PENDING.name
                )
                if (betRows == 0) continue
                plugin.economyManager.deposit(UUID.fromString(uuidStr), bet)
                plugin.casinoManager.log("Recovered interrupted Crash round #$roundId — refunded bet #$betId ($bet).")
            }
        }
    }

    private fun beginNewRound() {
        val houseEdge = plugin.casinoManager.houseEdge(CasinoManager.Game.CRASH)
        val r = random.nextDouble().coerceAtMost(0.999999)
        val raw = (1.0 - houseEdge) / (1.0 - r)
        val crashPoint = floor(raw * 100.0) / 100.0

        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "INSERT INTO casino_crash_rounds (crash_point, status, created_at) VALUES (?, ?, ?)",
            crashPoint, RoundStatus.BETTING.name, now
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { it.getInt("id") } ?: -1

        liveBets.clear()
        round = RoundState(id, RoundStatus.BETTING, crashPoint.coerceAtLeast(1.0), now)
        plugin.casinoManager.log("Crash round #$id started BETTING (crash point hidden server-side).")
    }

    private fun tick() {
        val state = round ?: return
        val now = System.currentTimeMillis()

        when (state.status) {
            RoundStatus.BETTING -> {
                if (now - state.phaseStart >= bettingSeconds * 1000L) {
                    state.status = RoundStatus.RUNNING
                    state.phaseStart = now
                    plugin.databaseManager.execute(
                        "UPDATE casino_crash_rounds SET status = ?, started_at = ? WHERE id = ?",
                        RoundStatus.RUNNING.name, now, state.id
                    )
                }
            }
            RoundStatus.RUNNING -> {
                if (currentMultiplier(state) >= state.crashPoint) {
                    crashRound(state, now)
                }
            }
            RoundStatus.CRASHED -> {
                if (now - state.phaseStart >= CRASHED_DISPLAY_MS) {
                    state.status = RoundStatus.RESETTING
                    state.phaseStart = now
                }
            }
            RoundStatus.RESETTING -> {
                if (now - state.phaseStart >= RESETTING_MS) {
                    beginNewRound()
                }
            }
        }

        refreshViewers()
    }

    private fun crashRound(state: RoundState, now: Long) {
        state.status = RoundStatus.CRASHED
        state.phaseStart = now
        plugin.databaseManager.execute(
            "UPDATE casino_crash_rounds SET status = ?, crashed_at = ? WHERE id = ?",
            RoundStatus.CRASHED.name, now, state.id
        )

        for (bet in liveBets.values) {
            if (bet.status != BetStatus.PENDING) continue
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE casino_crash_bets SET status = ? WHERE id = ? AND status = ?",
                BetStatus.LOST.name, bet.id, BetStatus.PENDING.name
            )
            if (rows == 0) continue
            bet.status = BetStatus.LOST
            plugin.casinoManager.recordLoss(bet.uuid, bet.bet)
            Bukkit.getPlayer(bet.uuid)?.let {
                send(it, "Crash hit ${"%.2f".format(state.crashPoint)}x — you lost ${plugin.economyManager.formatShort(bet.bet)}.", NamedTextColor.RED)
            }
        }
        plugin.casinoManager.log("Crash round #${state.id} crashed at ${state.crashPoint}x.")
    }

    private fun refreshViewers() {
        if (viewers.isEmpty()) return
        for (uuid in viewers) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            com.liam.joshymc.gui.casino.crash.CrashGui.refresh(plugin, player)
        }
    }

    fun currentMultiplier(): Double {
        val state = round ?: return 1.0
        return currentMultiplier(state)
    }

    private fun currentMultiplier(state: RoundState): Double {
        if (state.status == RoundStatus.CRASHED || state.status == RoundStatus.RESETTING) return state.crashPoint
        if (state.status != RoundStatus.RUNNING) return 1.0
        val elapsedSec = (System.currentTimeMillis() - state.phaseStart) / 1000.0
        return exp(GROWTH_RATE * elapsedSec).coerceAtMost(state.crashPoint)
    }

    fun getRoundStatus(): RoundStatus = round?.status ?: RoundStatus.BETTING
    fun getCrashPointIfRevealed(): Double? = round?.takeIf { it.status == RoundStatus.CRASHED || it.status == RoundStatus.RESETTING }?.crashPoint
    fun bettingSecondsRemaining(): Long {
        val state = round ?: return 0L
        if (state.status != RoundStatus.BETTING) return 0L
        return ((bettingSeconds * 1000L) - (System.currentTimeMillis() - state.phaseStart)).coerceAtLeast(0L) / 1000L
    }

    fun getLiveBet(uuid: UUID): LiveBet? = liveBets[uuid]

    fun placeBet(player: Player, amount: Double): Boolean {
        val state = round
        if (state == null || state.status != RoundStatus.BETTING) {
            send(player, "Betting is closed for this Crash round — wait for the next one.", NamedTextColor.RED)
            return false
        }
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.CRASH)) {
            send(player, "Crash is currently disabled.", NamedTextColor.RED)
            return false
        }
        if (liveBets.containsKey(player.uniqueId)) {
            send(player, "You already have a bet in this Crash round.", NamedTextColor.RED)
            return false
        }
        if (!plugin.casinoManager.placeBet(player, CasinoManager.Game.CRASH, amount)) return false

        plugin.databaseManager.execute(
            "INSERT INTO casino_crash_bets (round_id, uuid, name, bet, status, created_at) VALUES (?, ?, ?, ?, ?, ?)",
            state.id, player.uniqueId.toString(), player.name, amount, BetStatus.PENDING.name, System.currentTimeMillis()
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { it.getInt("id") } ?: -1
        liveBets[player.uniqueId] = LiveBet(id, player.uniqueId, player.name, amount, BetStatus.PENDING)
        plugin.casinoManager.log("Crash round #${state.id} bet placed — player=${player.name} amount=$amount")
        return true
    }

    /** Atomic — the PENDING -> CASHED_OUT transition only succeeds once, and only while RUNNING. */
    fun cashOut(player: Player): Double? {
        val state = round ?: return null
        if (state.status != RoundStatus.RUNNING) return null
        val bet = liveBets[player.uniqueId] ?: return null
        if (bet.status != BetStatus.PENDING) return null

        val multiplier = currentMultiplier(state)
        if (multiplier >= state.crashPoint) return null // crashed this same tick — reject, no race payout

        val payout = bet.bet * multiplier
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE casino_crash_bets SET status = ?, cashout_multiplier = ?, payout = ? WHERE id = ? AND status = ?",
            BetStatus.CASHED_OUT.name, multiplier, payout, bet.id, BetStatus.PENDING.name
        )
        if (rows == 0) return null

        bet.status = BetStatus.CASHED_OUT
        bet.cashoutMultiplier = multiplier
        plugin.casinoManager.payout(player.uniqueId, CasinoManager.Game.CRASH, payout)
        plugin.casinoManager.log("Crash round #${state.id} cashout — player=${player.name} multiplier=$multiplier payout=$payout")
        return payout
    }
}
