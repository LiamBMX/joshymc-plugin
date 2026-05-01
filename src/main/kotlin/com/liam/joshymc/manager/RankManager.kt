package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

class RankManager(private val plugin: Joshymc) : Listener {

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

    private val legacy = LegacyComponentSerializer.legacyAmpersand()

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

        // Register scoreboard teams so the rank prefix shows above the
        // nameplate (and orders the tab list by weight).
        rebuildScoreboardTeams()

        // Wire up join/quit so newly-arriving players get put in the right
        // team automatically.
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // Players already online when this manager (re)starts after /reload.
        for (online in Bukkit.getOnlinePlayers()) {
            applyTeamFor(online)
        }

        plugin.logger.info("[Ranks] Started with ${ranks.size} rank(s), ${playerRanks.size} assigned player(s).")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        applyTeamFor(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        // Bukkit will remove the entry on its own when the player logs off,
        // but explicit removal keeps the main scoreboard tidy across reloads.
        val board = Bukkit.getScoreboardManager().mainScoreboard
        board.getEntryTeam(event.player.name)?.removeEntry(event.player.name)
    }

    /**
     * Wipe and recreate every JoshyMC rank team on the main scoreboard.
     * Called at startup and whenever ranks are reloaded so prefixes stay
     * in sync with config edits.
     */
    private fun rebuildScoreboardTeams() {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        // Drop any leftover joshymc_rank_* teams from a prior boot
        board.teams
            .filter { it.name.startsWith(TEAM_PREFIX) }
            .forEach { it.unregister() }

        val sorted = ranks.values.sortedByDescending { it.weight }
        for ((idx, rank) in sorted.withIndex()) {
            val teamName = teamNameFor(rank, idx)
            val team = board.registerNewTeam(teamName)
            // Adventure component prefix; Bukkit converts to legacy on the wire.
            team.prefix(legacy.deserialize("${rank.displayTag}&r "))
        }
    }

    /**
     * Place [player] into the scoreboard team matching their current rank.
     * No-op if the team can't be found (shouldn't happen unless config was
     * mid-edit).
     *
     * Public so other managers (e.g. AFKManager) can restore the rank team
     * after temporarily moving the player to a custom team.
     */
    fun applyTeamFor(player: Player) {
        val rank = getPlayerRank(player) ?: return
        val board = Bukkit.getScoreboardManager().mainScoreboard
        val team = board.teams
            .firstOrNull { it.name.startsWith(TEAM_PREFIX) && it.name.endsWith("_${rank.id}") }
            ?: return

        // Remove from any other rank team first (e.g. when rank changes).
        board.teams
            .filter { it.name.startsWith(TEAM_PREFIX) && it.hasEntry(player.name) }
            .forEach { it.removeEntry(player.name) }

        team.addEntry(player.name)
    }

    private fun teamNameFor(rank: Rank, sortIndex: Int): String {
        // Bukkit team names are limited to 16 chars and used for tab-list
        // ordering. Pad with the sort index so higher-weight ranks come first
        // alphabetically. Truncate the rank id if needed to keep under 16.
        val padded = sortIndex.coerceIn(0, 99).toString().padStart(2, '0')
        val safeId = rank.id.take(11)
        return "$TEAM_PREFIX${padded}_$safeId"
    }

    companion object {
        private const val TEAM_PREFIX = "jmc_"
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
     *
     * If LuckPerms is installed, this also updates the player's parent group so
     * permissions configured on the LP group (e.g. joshymc.anvil on "warrior")
     * actually apply — otherwise our /rank only changes the chat tag while LP's
     * permission state stays on whatever group was previously assigned.
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

        // Update the scoreboard team so the nameplate prefix reflects the
        // new rank immediately (no relog needed).
        Bukkit.getPlayer(uuid)?.let { applyTeamFor(it) }

        syncWithLuckPerms(uuid, rankId)
    }

    private fun syncWithLuckPerms(uuid: UUID, rankId: String?) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) return

        val name = Bukkit.getOfflinePlayer(uuid).name ?: return
        // Use LP's console command surface — avoids a hard compile-time dep and
        // sidesteps the full LP UserManager/NodeBuilder reflection dance.
        val cmd = if (rankId == null) {
            "lp user $name parent set default"
        } else {
            "lp user $name parent set $rankId"
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        } catch (e: Exception) {
            plugin.logger.warning("[Ranks] LuckPerms sync failed for $name ($rankId): ${e.message}")
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
