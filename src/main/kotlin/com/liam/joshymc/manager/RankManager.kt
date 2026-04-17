package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import java.util.UUID

class RankManager(private val plugin: Joshymc) {

    /**
     * A rank with an ID, display tag (with color codes), weight for ordering,
     * and the chat format prefix.
     */
    data class Rank(
        val id: String,
        val displayTag: String,   // e.g., "&c&lAdmin"
        val weight: Int           // Higher = more important
    )

    private val ranks = mutableMapOf<String, Rank>()
    private val playerRanks = mutableMapOf<UUID, String>() // UUID -> rank ID

    fun start() {
        // Create DB table for player ranks
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_ranks (
                uuid TEXT PRIMARY KEY,
                rank_id TEXT NOT NULL
            )
        """.trimIndent())

        // Load ranks from config
        loadRanks()

        // Load player ranks from DB
        loadPlayerRanks()

        plugin.logger.info("[Ranks] Started with ${ranks.size} rank(s), ${playerRanks.size} assigned player(s).")
    }

    private fun loadRanks() {
        ranks.clear()
        var section = plugin.config.getConfigurationSection("ranks.list")

        // If no ranks in config, add defaults and save
        if (section == null) {
            plugin.logger.info("[Ranks] No ranks found in config, creating defaults...")
            val defaults = mapOf(
                "owner" to ("&4&lOwner" to 100),
                "admin" to ("&c&lAdmin" to 90),
                "mod" to ("&9&lMod" to 80),
                "helper" to ("&a&lHelper" to 70),
                "vip" to ("&6&lVIP" to 50),
                "member" to ("&7Member" to 10),
                "default" to ("&8Player" to 0)
            )
            for ((id, pair) in defaults) {
                plugin.config.set("ranks.list.$id.tag", pair.first)
                plugin.config.set("ranks.list.$id.weight", pair.second)
            }
            plugin.saveConfig()
            section = plugin.config.getConfigurationSection("ranks.list")
        }

        if (section == null) return

        for (id in section.getKeys(false)) {
            val rankSection = section.getConfigurationSection(id) ?: continue
            val tag = rankSection.getString("tag", "&7$id") ?: "&7$id"
            val weight = rankSection.getInt("weight", 0)
            ranks[id] = Rank(id, tag, weight)
        }
    }

    private fun loadPlayerRanks() {
        playerRanks.clear()
        val rows = plugin.databaseManager.query(
            "SELECT uuid, rank_id FROM player_ranks"
        ) { rs ->
            UUID.fromString(rs.getString("uuid")) to rs.getString("rank_id")
        }
        for ((uuid, rankId) in rows) {
            if (ranks.containsKey(rankId)) {
                playerRanks[uuid] = rankId
            }
        }
    }

    // ── Public API ──────────────────────────────────────

    fun getRank(id: String): Rank? = ranks[id]

    fun getAllRanks(): Collection<Rank> = ranks.values.sortedByDescending { it.weight }

    fun getRankIds(): Set<String> = ranks.keys

    /**
     * Get the player's rank. Falls back to "default" rank if none assigned.
     */
    fun getPlayerRank(player: Player): Rank? {
        val rankId = playerRanks[player.uniqueId]
        if (rankId != null) return ranks[rankId]
        // Fall back to default rank
        return ranks["default"]
    }

    fun getPlayerRankById(uuid: UUID): Rank? {
        val rankId = playerRanks[uuid]
        if (rankId != null) return ranks[rankId]
        return ranks["default"]
    }

    /**
     * Set a player's rank. Pass null to remove their rank.
     */
    fun setPlayerRank(uuid: UUID, rankId: String?) {
        if (rankId == null) {
            playerRanks.remove(uuid)
            plugin.databaseManager.execute("DELETE FROM player_ranks WHERE uuid = ?", uuid.toString())
        } else {
            playerRanks[uuid] = rankId
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO player_ranks (uuid, rank_id) VALUES (?, ?)",
                uuid.toString(), rankId
            )
        }
    }

    /**
     * Get the formatted prefix for a player's rank (with color codes).
     * Returns empty string if no rank.
     */
    fun getPrefix(player: Player): String {
        val rank = getPlayerRank(player) ?: return ""
        return "&8[${rank.displayTag}&8] &r"
    }
}
