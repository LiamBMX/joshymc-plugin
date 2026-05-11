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
    private val playerRanks = mutableMapOf<UUID, MutableSet<String>>() // UUID -> set of rank IDs

    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    fun start() {
        // Create DB table for player ranks (composite PK allows multiple ranks per player)
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_ranks (
                uuid TEXT NOT NULL,
                rank_id TEXT NOT NULL,
                PRIMARY KEY (uuid, rank_id)
            )
        """.trimIndent())

        migrateToMultiRankSchema()

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
     * config edits live. Two variants per rank: `<name>_y` (collisions
     * enabled) and `<name>_n` (collisions disabled). Same prefix on both,
     * differs only by COLLISION_RULE — the right variant is chosen per
     * player based on [shouldCollide].
     */
    private fun ensureRankTeamsOn(board: org.bukkit.scoreboard.Scoreboard) {
        val sorted = ranks.values.sortedByDescending { it.weight }
        for ((idx, rank) in sorted.withIndex()) {
            for (collide in listOf(true, false)) {
                val name = teamNameFor(rank, idx, collide)
                val existing = board.getTeam(name)
                val team = existing ?: board.registerNewTeam(name)
                team.prefix(legacy.deserialize("&8[${rank.displayTag}&8] &r"))
                team.setOption(
                    org.bukkit.scoreboard.Team.Option.COLLISION_RULE,
                    if (collide) org.bukkit.scoreboard.Team.OptionStatus.ALWAYS
                    else org.bukkit.scoreboard.Team.OptionStatus.NEVER
                )
            }
        }
    }

    /**
     * Add [subject] to the right rank-team variant on [board]. The variant
     * is chosen by [shouldCollide]: combat-tagged or in-arena or in a world
     * not on the no-collision list → collide variant; otherwise → no-collide.
     */
    private fun addToRankTeam(board: org.bukkit.scoreboard.Scoreboard, subject: Player) {
        val rank = getPlayerRank(subject) ?: return
        val sorted = ranks.values.sortedByDescending { it.weight }
        val idx = sorted.indexOfFirst { it.id == rank.id }
        if (idx < 0) return
        val collide = shouldCollide(subject)
        val targetName = teamNameFor(rank, idx, collide)
        val target = board.getTeam(targetName) ?: return

        // Remove from any other rank team variant on this board.
        board.teams
            .filter { it.name.startsWith(TEAM_PREFIX) && it.name != targetName && it.hasEntry(subject.name) }
            .forEach { it.removeEntry(subject.name) }

        if (!target.hasEntry(subject.name)) {
            target.addEntry(subject.name)
        }
    }

    /**
     * Whether the player should currently collide with other players.
     * Order of checks (return as soon as one wins):
     *   - combat-tagged → ALWAYS collide (PvP needs collision)
     *   - inside an arena polygon → ALWAYS collide
     *   - current world is in worlds.no-collision config → NEVER collide
     *   - otherwise → collide
     */
    fun shouldCollide(player: Player): Boolean {
        if (plugin.combatManager.isTagged(player)) return true
        if (plugin.arenaManager.isInArena(player)) return true
        val noCollideWorlds = plugin.config.getStringList("worlds.no-collision")
        if (player.world.name in noCollideWorlds) return false
        return true
    }

    private fun teamNameFor(rank: Rank, sortIndex: Int, collide: Boolean): String {
        // Bukkit team names are limited to 16 chars and used for tab-list
        // ordering. Pad with the sort index so higher-weight ranks come first
        // alphabetically. Truncate the rank id if needed to keep under 16,
        // then append _y / _n for the collision variant.
        val padded = sortIndex.coerceIn(0, 99).toString().padStart(2, '0')
        // Reserve 2 chars for the variant suffix (_y / _n) so the total
        // stays under the 16-char team name limit.
        val safeId = rank.id.take(9)
        val suffix = if (collide) "y" else "n"
        return "$TEAM_PREFIX${padded}_${safeId}_$suffix"
    }

    companion object {
        private const val TEAM_PREFIX = "jmc_"
    }

    private fun migrateToMultiRankSchema() {
        val tableSql = plugin.databaseManager.queryFirst(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='player_ranks'"
        ) { rs -> rs.getString("sql") } ?: return

        // Old schema used "uuid TEXT PRIMARY KEY" (single rank per player).
        // New schema uses a composite PRIMARY KEY (uuid, rank_id).
        if (!tableSql.contains("PRIMARY KEY (uuid", ignoreCase = true)) {
            plugin.logger.info("[Ranks] Migrating player_ranks to multi-rank schema...")
            plugin.databaseManager.execute("""
                CREATE TABLE IF NOT EXISTS player_ranks_new (
                    uuid TEXT NOT NULL,
                    rank_id TEXT NOT NULL,
                    PRIMARY KEY (uuid, rank_id)
                )
            """.trimIndent())
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO player_ranks_new (uuid, rank_id) SELECT uuid, rank_id FROM player_ranks"
            )
            plugin.databaseManager.execute("DROP TABLE player_ranks")
            plugin.databaseManager.execute("ALTER TABLE player_ranks_new RENAME TO player_ranks")
            plugin.logger.info("[Ranks] Multi-rank migration complete.")
        }
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
                playerRanks.getOrPut(uuid) { mutableSetOf() }.add(rankId)
            }
        }
    }

    // ── Public API ──────────────────────────────────────

    fun getRank(id: String): Rank? = ranks[id]

    fun getAllRanks(): Collection<Rank> = ranks.values.sortedByDescending { it.weight }

    fun getRankIds(): Set<String> = ranks.keys

    /**
     * Get the player's displayed rank (highest weight among all assigned ranks).
     * Falls back to "default" rank if none assigned.
     */
    fun getPlayerRank(player: Player): Rank? {
        val rankIds = playerRanks[player.uniqueId]
        if (!rankIds.isNullOrEmpty()) {
            return rankIds.mapNotNull { ranks[it] }.maxByOrNull { it.weight }
        }
        return ranks["default"]
    }

    fun getPlayerRankById(uuid: UUID): Rank? {
        val rankIds = playerRanks[uuid]
        if (!rankIds.isNullOrEmpty()) {
            return rankIds.mapNotNull { ranks[it] }.maxByOrNull { it.weight }
        }
        return ranks["default"]
    }

    /** All rank IDs explicitly assigned to this player, empty if none. */
    fun getPlayerRankIds(uuid: UUID): Set<String> = playerRanks[uuid] ?: emptySet()

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
            val set = playerRanks.getOrPut(uuid) { mutableSetOf() }
            set.add(rankId)
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO player_ranks (uuid, rank_id) VALUES (?, ?)",
                uuid.toString(), rankId
            )
            // Preserve the default rank alongside any non-default rank so players
            // retain their base permissions/display when a special rank is assigned.
            if (rankId != "default" && ranks.containsKey("default")) {
                set.add("default")
                plugin.databaseManager.execute(
                    "INSERT OR IGNORE INTO player_ranks (uuid, rank_id) VALUES (?, ?)",
                    uuid.toString(), "default"
                )
            }
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
        //
        // Per Joshy's request, players keep the `default` LuckPerms group even
        // after being given a rank, so default-group permissions still apply.
        // We `parent set` the new rank to clear any stale parent, then
        // `parent add default` so the player inherits both.
        try {
            if (rankId == null) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user $name parent set default")
            } else {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user $name parent set $rankId")
                if (rankId != "default") {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user $name parent add default")
                }
            }
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
