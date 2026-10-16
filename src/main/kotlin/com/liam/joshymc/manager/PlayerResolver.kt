package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.entity.Player
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared name → player lookup for commands (issue #1110). Never touches the network,
 * so it's safe on the main thread and never sends Floodgate (Bedrock) names to Mojang,
 * unlike `Bukkit.getOfflinePlayer(String)`, which blocks on a profile lookup for unknown
 * names and hands back a made-up offline UUID when that fails.
 *
 * Order: online player → Paper's local user cache → JoshyMC's own `player_names` table
 * (every player who has joined, recorded on join). Unknown names return null.
 */
class PlayerResolver(private val plugin: Joshymc) : Listener {

    /** Lowercased last-known name → uuid of the player who most recently joined with it. */
    private val byName = ConcurrentHashMap<String, UUID>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_names (
                uuid TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                last_seen INTEGER NOT NULL
            )
        """.trimIndent())
        if (plugin.databaseManager.queryFirst("SELECT 1 FROM player_names LIMIT 1") { true } == null) backfill()

        // Oldest first, so a name that changed hands maps to whoever used it last.
        plugin.databaseManager.query("SELECT uuid, name FROM player_names ORDER BY last_seen ASC") { rs ->
            val raw = rs.getString("uuid")
            try {
                UUID.fromString(raw) to rs.getString("name")
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("[PlayerResolver] Skipping player_names row with malformed uuid '$raw'.")
                null
            }
        }.filterNotNull().forEach { (uuid, name) -> byName[key(name)] = uuid }

        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach { remember(it) }
        plugin.logger.info("[PlayerResolver] Loaded ${byName.size} known player name(s).")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        remember(event.player)
    }

    /** The player last known as [name] (case-insensitive), or null if this server has never seen them. */
    fun resolve(name: String): OfflinePlayer? {
        if (name.isBlank()) return null
        Bukkit.getPlayerExact(name)?.let { return it }
        Bukkit.getOfflinePlayerIfCached(name)?.let { return it }
        val uuid = byName[key(name)] ?: return null
        return Bukkit.getOfflinePlayer(uuid)
    }

    /**
     * The online player named exactly [name] (case-insensitive), or null (issue #1170).
     * Use this for anything that grants a reward: unlike `Bukkit.getPlayer(String)` it never
     * falls back to a player whose name merely starts with [name], so `/credits give Sam`
     * can't pay `Sammy123` while Sam is offline. Floodgate players match by the name the
     * server shows for them, prefix included (e.g. `.Steve`).
     */
    fun online(name: String): Player? = if (name.isBlank()) null else Bukkit.getPlayerExact(name)

    /**
     * First start only: seeds the table from the world's player data so players who joined
     * before this table existed (and have since dropped out of Paper's user cache) still
     * resolve. Names come from local player data files; nothing is fetched.
     */
    private fun backfill() {
        var count = 0
        try {
            plugin.databaseManager.transaction {
                for (offline in Bukkit.getOfflinePlayers()) {
                    val name = offline.name ?: continue
                    plugin.databaseManager.execute(
                        "INSERT OR IGNORE INTO player_names (uuid, name, last_seen) VALUES (?, ?, ?)",
                        offline.uniqueId.toString(), name, offline.lastSeen
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerResolver] Backfill from player data failed: ${e.message}")
            return
        }
        plugin.logger.info("[PlayerResolver] Backfilled $count player name(s) from existing player data.")
    }

    /** The name [uuid] last joined with, or null if they have never joined since this table existed. */
    fun lastKnownName(uuid: UUID): String? =
        plugin.databaseManager.queryFirst("SELECT name FROM player_names WHERE uuid = ?", uuid.toString()) { rs -> rs.getString("name") }

    private fun remember(player: Player) {
        byName[key(player.name)] = player.uniqueId
        try {
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO player_names (uuid, name, last_seen) VALUES (?, ?, ?)",
                player.uniqueId.toString(), player.name, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            plugin.logger.warning("[PlayerResolver] Failed to record name for ${player.uniqueId}: ${e.message}")
        }
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT)
}
