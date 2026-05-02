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

        // Wire up join/quit so newly-arriving players get put in the right
        // team automatically.
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // For every online player, register rank teams on their scoreboard
        // and add every online player's name to the appropriate team. This
        // is needed because ScoreboardManager swaps each player to a fresh
        // per-player scoreboard for the sidebar — teams registered only on
        // the main scoreboard never reach anyone.
        for (viewer in Bukkit.getOnlinePlayers()) {
            ensureRankTeamsOn(viewer.scoreboard)
            for (subject in Bukkit.getOnlinePlayers()) {
                addToRankTeam(viewer.scoreboard, subject)
            }
        }

        plugin.logger.info("[Ranks] Started with ${ranks.size} rank(s), ${playerRanks.size} assigned player(s).")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // Defer one tick so ScoreboardManager.onPlayerJoin has finished
        // setting up the new player's scoreboard. Otherwise we'd register
        // teams on the default scoreboard and then ScoreboardManager would
        // replace it with a fresh empty one.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!event.player.isOnline) return@Runnable
            applyTeamFor(event.player)
        }, 2L)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        // Remove the leaving player's entry from every viewer's scoreboard
        // so the team doesn't keep an orphan.
        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer == event.player) continue
            val board = viewer.scoreboard
            board.teams
                .filter { it.name.startsWith(TEAM_PREFIX) && it.hasEntry(event.player.name) }
                .forEach { it.removeEntry(event.player.name) }
        }
    }

    /**
     * Place [player] in the right rank team on every online viewer's
     * scoreboard, AND make sure every other online player is in their
     * correct rank team on [player]'s own scoreboard. This propagates the
     * rank prefix above the head from every viewing angle.
     *
     * Public so other managers (e.g. AFKManager) can restore the rank team
     * after temporarily moving the player to a custom team.
     */
    fun applyTeamFor(player: Player) {
        // 1. Add this player to the right team on every viewer's board so
        //    everyone sees their nameplate prefix.
        for (viewer in Bukkit.getOnlinePlayers()) {
            ensureRankTeamsOn(viewer.scoreboard)
            addToRankTeam(viewer.scoreboard, player)
        }
        // 2. On the player's OWN board, fill in every other online player so
        //    they see everyone else's rank too.
        ensureRankTeamsOn(player.scoreboard)
        for (other in Bukkit.getOnlinePlayers()) {
            if (other == player) continue
            addToRankTeam(player.scoreboard, other)
        }
    }

    /**
     * Register a JoshyMC rank team for every rank on [board] if not present.
     * Existing teams keep their entries — we only update the prefix to keep
     * config edits live.
     */
    private fun ensureRankTeamsOn(board: org.bukkit.scoreboard.Scoreboard) {
        val sorted = ranks.values.sortedByDescending { it.weight }
        for ((idx, rank) in sorted.withIndex()) {
            val name = teamNameFor(rank, idx)
            val existing = board.getTeam(name)
            val team = existing ?: board.registerNewTeam(name)
            // Always refresh the prefix so reloading config picks up tag
            // changes. Wrap in dark-gray brackets so the rank is visually
            // separated from the player name.
            team.prefix(legacy.deserialize("&8[${rank.displayTag}&8] &r"))
        }
    }

    /** Add [subject] to the team matching their current rank on [board]. */
    private fun addToRankTeam(board: org.bukkit.scoreboard.Scoreboard, subject: Player) {
        val rank = getPlayerRank(subject) ?: return
        val sorted = ranks.values.sortedByDescending { it.weight }
        val idx = sorted.indexOfFirst { it.id == rank.id }
        if (idx < 0) return
        val targetName = teamNameFor(rank, idx)
        val target = board.getTeam(targetName) ?: return

        // Remove from any other rank team on this board.
        board.teams
            .filter { it.name.startsWith(TEAM_PREFIX) && it.name != targetName && it.hasEntry(subject.name) }
            .forEach { it.removeEntry(subject.name) }

        if (!target.hasEntry(subject.name)) {
            target.addEntry(subject.name)
        }
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
