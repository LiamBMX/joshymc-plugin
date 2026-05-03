package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID

/**
 * Periodic leaderboard hologram updater. Each registered leaderboard owns
 * a regular hologram (managed by [HologramManager]) whose lines we
 * overwrite every 60 seconds with a freshly-computed top-N from the
 * relevant DB table.
 *
 * Storage table:
 *   leaderboards(id PK, type, hologram_id, top_n, title)
 *
 * The hologram itself is created via HologramManager.createHologram so it
 * persists / despawns / moves like any other hologram. We just keep the
 * line content fresh.
 */
class LeaderboardManager(private val plugin: Joshymc) {

    enum class Type(val displayName: String, val icon: String) {
        MONEY("Richest Players", "&6\$"),
        KILLS("Top Killers", "&c⚔"),
        DEATHS("Most Deaths", "&8☠"),
        PLAYTIME("Most Played", "&b⏱"),
        QUESTS("Top Questers", "&e☆"),
    }

    data class LeaderboardEntry(
        val id: String,
        val type: Type,
        val hologramId: String,
        val topN: Int,
        val title: String,
    )

    private val entries = mutableMapOf<String, LeaderboardEntry>()
    private var refreshTaskId = -1

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS leaderboards (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                hologram_id TEXT NOT NULL,
                top_n INTEGER NOT NULL,
                title TEXT NOT NULL
            )
        """.trimIndent())

        loadEntries()
        // Refresh every 60 seconds (1200 ticks). Holograms tolerate stale
        // data fine for that long; cheaper than recomputing every tick.
        refreshTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            refreshAll()
        }, 100L, 1200L)

        plugin.logger.info("[Leaderboards] Loaded ${entries.size} leaderboard hologram(s).")
    }

    fun stop() {
        if (refreshTaskId != -1) {
            plugin.server.scheduler.cancelTask(refreshTaskId)
            refreshTaskId = -1
        }
    }

    private fun loadEntries() {
        entries.clear()
        plugin.databaseManager.query(
            "SELECT id, type, hologram_id, top_n, title FROM leaderboards"
        ) { rs ->
            val type = try { Type.valueOf(rs.getString("type")) } catch (e: Exception) { return@query null }
            LeaderboardEntry(
                id = rs.getString("id"),
                type = type,
                hologramId = rs.getString("hologram_id"),
                topN = rs.getInt("top_n"),
                title = rs.getString("title")
            )
        }.filterNotNull().forEach { entries[it.id] = it }
    }

    fun create(id: String, type: Type, location: Location, topN: Int, title: String? = null): Boolean {
        if (entries.containsKey(id)) return false
        val finalTitle = title ?: "&6&l${type.displayName}"
        val hologramId = "lb_$id"
        val initialLines = listOf(finalTitle) + (1..topN).map { "&7$it. &7..." }
        val ok = plugin.hologramManager.createHologram(hologramId, location, initialLines)
        if (!ok) return false

        plugin.databaseManager.execute(
            "INSERT INTO leaderboards (id, type, hologram_id, top_n, title) VALUES (?, ?, ?, ?, ?)",
            id, type.name, hologramId, topN, finalTitle
        )
        val entry = LeaderboardEntry(id, type, hologramId, topN, finalTitle)
        entries[id] = entry
        refresh(entry)
        return true
    }

    fun delete(id: String): Boolean {
        val entry = entries.remove(id) ?: return false
        plugin.hologramManager.deleteHologram(entry.hologramId)
        plugin.databaseManager.execute("DELETE FROM leaderboards WHERE id = ?", id)
        return true
    }

    fun list(): Collection<LeaderboardEntry> = entries.values.toList()

    fun refreshAll() {
        for (entry in entries.values) refresh(entry)
    }

    private fun refresh(entry: LeaderboardEntry) {
        val rows = computeTop(entry.type, entry.topN)
        val lines = mutableListOf(entry.title)
        for ((idx, row) in rows.withIndex()) {
            lines.add("&7${idx + 1}. &f${row.first} &8» ${entry.type.icon} &f${row.second}")
        }
        // Pad with placeholders if fewer than topN rows so the hologram size
        // stays constant.
        for (i in rows.size until entry.topN) {
            lines.add("&7${i + 1}. &7...")
        }
        plugin.hologramManager.updateLines(entry.hologramId, lines)
    }

    /**
     * Returns top-N rows for a type as `(displayName, formattedValue)`.
     * Pulled directly from the relevant DB tables to avoid coupling to
     * each manager's in-memory state.
     */
    private fun computeTop(type: Type, n: Int): List<Pair<String, String>> {
        return when (type) {
            Type.MONEY -> plugin.economyManager.getTopBalances(n).map { (name, bal) ->
                name to plugin.economyManager.format(bal)
            }
            Type.KILLS -> queryUuidIntTop(
                "SELECT uuid, kills AS n FROM player_stats WHERE kills > 0 ORDER BY kills DESC LIMIT ?",
                n
            ).map { (uuid, count) -> nameOf(uuid) to count.toString() }
            Type.DEATHS -> queryUuidIntTop(
                "SELECT uuid, deaths AS n FROM player_stats WHERE deaths > 0 ORDER BY deaths DESC LIMIT ?",
                n
            ).map { (uuid, count) -> nameOf(uuid) to count.toString() }
            Type.PLAYTIME -> queryUuidLongTop(
                "SELECT uuid, total_seconds AS n FROM playtime WHERE total_seconds > 0 ORDER BY total_seconds DESC LIMIT ?",
                n
            ).map { (uuid, sec) -> nameOf(uuid) to plugin.playtimeManager.formatPlaytime(sec) }
            Type.QUESTS -> queryUuidIntTop(
                "SELECT uuid, COUNT(*) AS n FROM quest_progress WHERE completed = 1 GROUP BY uuid ORDER BY n DESC LIMIT ?",
                n
            ).map { (uuid, count) -> nameOf(uuid) to count.toString() }
        }
    }

    private fun queryUuidIntTop(sql: String, limit: Int): List<Pair<UUID, Int>> {
        return plugin.databaseManager.query(sql, limit) { rs ->
            try {
                UUID.fromString(rs.getString("uuid")) to rs.getInt("n")
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()
    }

    private fun queryUuidLongTop(sql: String, limit: Int): List<Pair<UUID, Long>> {
        return plugin.databaseManager.query(sql, limit) { rs ->
            try {
                UUID.fromString(rs.getString("uuid")) to rs.getLong("n")
            } catch (e: Exception) {
                null
            }
        }.filterNotNull()
    }

    private fun nameOf(uuid: UUID): String = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)
}
