package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/casino` Mines (issue #727) — 5x5 board, server-generated mine layout, probability
 * based multiplier with configurable house edge. One active session per player.
 */
class CasinoMinesManager(private val plugin: Joshymc) {

    companion object {
        const val TILES = 25
    }

    enum class Status { ACTIVE, CASHED_OUT, LOST, CANCELLED }

    data class Session(
        val id: Int,
        val uuid: UUID,
        val bet: Double,
        val mines: Int,
        val mineTiles: Set<Int>,
        val revealed: MutableSet<Int>,
        var status: Status
    )

    private val random = SecureRandom()
    private val active = ConcurrentHashMap<UUID, Session>()

    var minMines = 1; private set
    var maxMines = 10; private set

    fun start() {
        minMines = plugin.config.getInt("casino.mines.min-mines", 1).coerceAtLeast(1)
        maxMines = plugin.config.getInt("casino.mines.max-mines", 10).coerceIn(minMines, TILES - 1)

        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS casino_mines_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                bet REAL NOT NULL,
                mines INTEGER NOT NULL,
                mine_tiles TEXT NOT NULL,
                revealed TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL,
                payout REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )
            """.trimIndent()
        )

        // Restart safety: any session still ACTIVE lost its in-memory board when the
        // JVM stopped. There is no safe way to resume it, so refund the escrowed bet
        // exactly once rather than silently erasing or re-resolving it.
        val stuck = plugin.databaseManager.query(
            "SELECT id, uuid, bet FROM casino_mines_sessions WHERE status = ?", Status.ACTIVE.name
        ) { rs -> Triple(rs.getInt("id"), rs.getString("uuid"), rs.getDouble("bet")) }

        for ((id, uuidStr, bet) in stuck) {
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE casino_mines_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
                Status.CANCELLED.name, System.currentTimeMillis(), id, Status.ACTIVE.name
            )
            if (rows == 0) continue
            plugin.economyManager.deposit(UUID.fromString(uuidStr), bet)
            plugin.casinoManager.log("Recovered interrupted Mines session #$id — refunded $bet.")
        }

        plugin.logger.info("[Casino] Mines started.")
    }

    fun stop() {
        active.clear()
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.CASINO)
    }

    fun getSession(uuid: UUID): Session? = active[uuid]

    fun hasActiveSession(uuid: UUID): Boolean = active.containsKey(uuid)

    fun startGame(player: Player, bet: Double, mines: Int): Session? {
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.MINES)) {
            send(player, "Mines is currently disabled.", NamedTextColor.RED)
            return null
        }
        if (hasActiveSession(player.uniqueId)) {
            send(player, "You already have an active Mines game.", NamedTextColor.RED)
            return null
        }
        if (mines < minMines || mines > maxMines) {
            send(player, "Mine count must be between $minMines and $maxMines.", NamedTextColor.RED)
            return null
        }
        if (!plugin.casinoManager.placeBet(player, CasinoManager.Game.MINES, bet)) return null

        val mineTiles = mutableSetOf<Int>()
        while (mineTiles.size < mines) {
            mineTiles.add(random.nextInt(TILES))
        }

        plugin.databaseManager.execute(
            "INSERT INTO casino_mines_sessions (uuid, bet, mines, mine_tiles, revealed, status, created_at) VALUES (?, ?, ?, ?, '', ?, ?)",
            player.uniqueId.toString(), bet, mines, mineTiles.joinToString(","), Status.ACTIVE.name, System.currentTimeMillis()
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { it.getInt("id") } ?: -1

        val session = Session(id, player.uniqueId, bet, mines, mineTiles, mutableSetOf(), Status.ACTIVE)
        active[player.uniqueId] = session
        plugin.casinoManager.log("Mines #$id started — player=${player.name} bet=$bet mines=$mines")
        return session
    }

    /** Fair multiplier for surviving [revealed] safe reveals out of [TILES] tiles with [mines] mines, minus house edge. */
    fun multiplierFor(mines: Int, revealed: Int): Double {
        if (revealed <= 0) return 1.0
        var probability = 1.0
        for (i in 0 until revealed) {
            probability *= (TILES - mines - i).toDouble() / (TILES - i).toDouble()
        }
        val fair = 1.0 / probability
        return fair * (1.0 - plugin.casinoManager.houseEdge(CasinoManager.Game.MINES))
    }

    fun currentMultiplier(session: Session): Double = multiplierFor(session.mines, session.revealed.size)

    sealed class RevealResult {
        data class Safe(val multiplier: Double) : RevealResult()
        object Mine : RevealResult()
        object Invalid : RevealResult()
    }

    fun reveal(player: Player, tile: Int): RevealResult {
        val session = active[player.uniqueId] ?: return RevealResult.Invalid
        if (session.status != Status.ACTIVE) return RevealResult.Invalid
        if (tile < 0 || tile >= TILES || tile in session.revealed) return RevealResult.Invalid

        if (tile in session.mineTiles) {
            session.status = Status.LOST
            active.remove(player.uniqueId)
            plugin.databaseManager.execute(
                "UPDATE casino_mines_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
                Status.LOST.name, System.currentTimeMillis(), session.id, Status.ACTIVE.name
            )
            plugin.casinoManager.recordLoss(player.uniqueId, session.bet)
            plugin.casinoManager.log("Mines #${session.id} lost — player=${player.name} bet=${session.bet}")
            return RevealResult.Mine
        }

        session.revealed.add(tile)
        plugin.databaseManager.execute(
            "UPDATE casino_mines_sessions SET revealed = ? WHERE id = ?",
            session.revealed.joinToString(","), session.id
        )
        val multiplier = currentMultiplier(session)

        // A player can safely reveal every non-mine tile — at that point the board is
        // fully cleared and there is nothing left to click, so auto-cashout.
        if (session.revealed.size == TILES - session.mines) {
            cashOut(player)
        }

        return RevealResult.Safe(multiplier)
    }

    /** Atomic — the ACTIVE -> CASHED_OUT transition only succeeds once. */
    fun cashOut(player: Player): Double? {
        val session = active[player.uniqueId] ?: return null
        if (session.status != Status.ACTIVE || session.revealed.isEmpty()) return null

        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE casino_mines_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
            Status.CASHED_OUT.name, System.currentTimeMillis(), session.id, Status.ACTIVE.name
        )
        if (rows == 0) return null

        active.remove(player.uniqueId)
        val multiplier = currentMultiplier(session)
        val payout = session.bet * multiplier
        plugin.databaseManager.execute("UPDATE casino_mines_sessions SET payout = ? WHERE id = ?", payout, session.id)
        plugin.casinoManager.payout(player.uniqueId, CasinoManager.Game.MINES, payout)
        plugin.casinoManager.log("Mines #${session.id} cashed out — player=${player.name} multiplier=$multiplier payout=$payout")
        return payout
    }

    /**
     * Disconnect safety: if a cashout is available (at least one safe reveal), lock in
     * the current multiplier automatically. If the player disconnects before revealing
     * anything, the game is left active for them to resume on rejoin rather than
     * refunding — a free reroll would let players bail out of a bad mine layout for
     * free.
     */
    fun handleDisconnect(uuid: UUID) {
        val session = active[uuid] ?: return
        if (session.revealed.isEmpty()) return
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE casino_mines_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
            Status.CASHED_OUT.name, System.currentTimeMillis(), session.id, Status.ACTIVE.name
        )
        if (rows == 0) return
        active.remove(uuid)
        val multiplier = currentMultiplier(session)
        val payout = session.bet * multiplier
        plugin.databaseManager.execute("UPDATE casino_mines_sessions SET payout = ? WHERE id = ?", payout, session.id)
        plugin.casinoManager.payout(uuid, CasinoManager.Game.MINES, payout)
        plugin.casinoManager.log("Mines #${session.id} auto-cashed-out on disconnect — uuid=$uuid multiplier=$multiplier payout=$payout")
    }
}
