package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/casino` Towers (issue #727) — level-climbing risk game. Each level has exactly
 * one losing tile out of [Difficulty.tiles]; harder difficulties shrink the tile
 * count (raising the loss chance and the per-level multiplier). One active session
 * per player.
 */
class CasinoTowersManager(private val plugin: Joshymc) {

    enum class Difficulty(val tiles: Int) {
        EASY(4),
        MEDIUM(3),
        HARD(2)
    }

    enum class Status { ACTIVE, CASHED_OUT, LOST, CANCELLED }

    data class Session(
        val id: Int,
        val uuid: UUID,
        val bet: Double,
        val difficulty: Difficulty,
        val badIndices: List<Int>,
        var level: Int,
        var status: Status
    )

    private val random = SecureRandom()
    private val active = ConcurrentHashMap<UUID, Session>()

    var levels = 8; private set

    fun start() {
        levels = plugin.config.getInt("casino.towers.levels", 8).coerceIn(1, 20)

        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS casino_towers_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uuid TEXT NOT NULL,
                bet REAL NOT NULL,
                difficulty TEXT NOT NULL,
                bad_indices TEXT NOT NULL,
                level INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                payout REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )
            """.trimIndent()
        )

        // Restart safety: same principle as Mines — an interrupted ACTIVE session
        // cannot be safely resumed, so refund the escrowed bet exactly once.
        val stuck = plugin.databaseManager.query(
            "SELECT id, uuid, bet FROM casino_towers_sessions WHERE status = ?", Status.ACTIVE.name
        ) { rs -> Triple(rs.getInt("id"), rs.getString("uuid"), rs.getDouble("bet")) }

        for ((id, uuidStr, bet) in stuck) {
            val rows = plugin.databaseManager.executeUpdate(
                "UPDATE casino_towers_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
                Status.CANCELLED.name, System.currentTimeMillis(), id, Status.ACTIVE.name
            )
            if (rows == 0) continue
            plugin.economyManager.deposit(UUID.fromString(uuidStr), bet)
            plugin.casinoManager.log("Recovered interrupted Towers session #$id — refunded $bet.")
        }

        plugin.logger.info("[Casino] Towers started.")
    }

    fun stop() {
        active.clear()
    }

    private fun send(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text(text, color), CommunicationsManager.Category.CASINO)
    }

    fun getSession(uuid: UUID): Session? = active[uuid]

    fun hasActiveSession(uuid: UUID): Boolean = active.containsKey(uuid)

    fun startGame(player: Player, bet: Double, difficulty: Difficulty): Session? {
        if (!plugin.casinoManager.isGameEnabled(CasinoManager.Game.TOWERS)) {
            send(player, "Towers is currently disabled.", NamedTextColor.RED)
            return null
        }
        if (hasActiveSession(player.uniqueId)) {
            send(player, "You already have an active Towers game.", NamedTextColor.RED)
            return null
        }
        if (!plugin.casinoManager.placeBet(player, CasinoManager.Game.TOWERS, bet)) return null

        val badIndices = (0 until levels).map { random.nextInt(difficulty.tiles) }

        plugin.databaseManager.execute(
            "INSERT INTO casino_towers_sessions (uuid, bet, difficulty, bad_indices, level, status, created_at) VALUES (?, ?, ?, ?, 0, ?, ?)",
            player.uniqueId.toString(), bet, difficulty.name, badIndices.joinToString(","), Status.ACTIVE.name, System.currentTimeMillis()
        )
        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { it.getInt("id") } ?: -1

        val session = Session(id, player.uniqueId, bet, difficulty, badIndices, 0, Status.ACTIVE)
        active[player.uniqueId] = session
        plugin.casinoManager.log("Towers #$id started — player=${player.name} bet=$bet difficulty=${difficulty.name}")
        return session
    }

    fun multiplierFor(difficulty: Difficulty, level: Int): Double {
        if (level <= 0) return 1.0
        val perLevel = difficulty.tiles.toDouble() / (difficulty.tiles - 1).toDouble()
        val fair = Math.pow(perLevel, level.toDouble())
        return fair * (1.0 - plugin.casinoManager.houseEdge(CasinoManager.Game.TOWERS))
    }

    fun currentMultiplier(session: Session): Double = multiplierFor(session.difficulty, session.level)

    sealed class ClimbResult {
        data class Safe(val multiplier: Double, val completed: Boolean) : ClimbResult()
        object Lost : ClimbResult()
        object Invalid : ClimbResult()
    }

    fun pick(player: Player, tileIndex: Int): ClimbResult {
        val session = active[player.uniqueId] ?: return ClimbResult.Invalid
        if (session.status != Status.ACTIVE) return ClimbResult.Invalid
        if (tileIndex < 0 || tileIndex >= session.difficulty.tiles) return ClimbResult.Invalid

        if (tileIndex == session.badIndices[session.level]) {
            session.status = Status.LOST
            active.remove(player.uniqueId)
            plugin.databaseManager.execute(
                "UPDATE casino_towers_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
                Status.LOST.name, System.currentTimeMillis(), session.id, Status.ACTIVE.name
            )
            plugin.casinoManager.recordLoss(player.uniqueId, session.bet)
            plugin.casinoManager.log("Towers #${session.id} lost — player=${player.name} bet=${session.bet} level=${session.level}")
            return ClimbResult.Lost
        }

        session.level++
        plugin.databaseManager.execute("UPDATE casino_towers_sessions SET level = ? WHERE id = ?", session.level, session.id)
        val multiplier = currentMultiplier(session)

        if (session.level >= levels) {
            cashOut(player)
            return ClimbResult.Safe(multiplier, true)
        }
        return ClimbResult.Safe(multiplier, false)
    }

    /** Atomic — the ACTIVE -> CASHED_OUT transition only succeeds once. */
    fun cashOut(player: Player): Double? {
        val session = active[player.uniqueId] ?: return null
        if (session.status != Status.ACTIVE || session.level <= 0) return null

        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE casino_towers_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
            Status.CASHED_OUT.name, System.currentTimeMillis(), session.id, Status.ACTIVE.name
        )
        if (rows == 0) return null

        active.remove(player.uniqueId)
        val multiplier = currentMultiplier(session)
        val payout = session.bet * multiplier
        plugin.databaseManager.execute("UPDATE casino_towers_sessions SET payout = ? WHERE id = ?", payout, session.id)
        plugin.casinoManager.payout(player.uniqueId, CasinoManager.Game.TOWERS, payout)
        plugin.casinoManager.log("Towers #${session.id} cashed out — player=${player.name} multiplier=$multiplier payout=$payout")
        return payout
    }

    /** Disconnect safety, same principle as Mines: auto-cashout only once a level has been cleared. */
    fun handleDisconnect(uuid: UUID) {
        val session = active[uuid] ?: return
        if (session.level <= 0) return
        val rows = plugin.databaseManager.executeUpdate(
            "UPDATE casino_towers_sessions SET status = ?, completed_at = ? WHERE id = ? AND status = ?",
            Status.CASHED_OUT.name, System.currentTimeMillis(), session.id, Status.ACTIVE.name
        )
        if (rows == 0) return
        active.remove(uuid)
        val multiplier = currentMultiplier(session)
        val payout = session.bet * multiplier
        plugin.databaseManager.execute("UPDATE casino_towers_sessions SET payout = ? WHERE id = ?", payout, session.id)
        plugin.casinoManager.payout(uuid, CasinoManager.Game.TOWERS, payout)
        plugin.casinoManager.log("Towers #${session.id} auto-cashed-out on disconnect — uuid=$uuid multiplier=$multiplier payout=$payout")
    }
}
