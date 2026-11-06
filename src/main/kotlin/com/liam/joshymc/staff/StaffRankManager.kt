package com.liam.joshymc.staff

import com.liam.joshymc.Joshymc
import com.liam.joshymc.punish.StaffLevel
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/**
 * Resolves staff ranks from authoritative data and answers the staff-protection questions
 * every sensitive action asks (issue #1383). Never reads display names, prefixes or chat tags.
 *
 * A player's rank comes from:
 * 1. the LuckPerms groups they hold, seen as explicitly granted `group.<name>` nodes (LuckPerms
 *    sets one for every inherited group), mapped by `config.yml > staff-ranks.groups`;
 * 2. JoshyMC's own `player_ranks` records (ranks given through `/rank` or `/admin`);
 * 3. ops and `*` holders count as [StaffRank.OWNER]: they already hold every permission.
 *
 * Offline players can't be asked, so the last rank seen while they were online is kept in
 * `staff_rank_cache` (written on join and quit; a player seen without a staff rank loses
 * their row). A player never seen as staff on this server resolves to no rank.
 */
class StaffRankManager(private val plugin: Joshymc) : Listener {

    @Volatile
    var groups: StaffRankGroups = StaffRankGroups()
        private set

    @Volatile
    var allowEqualRank: Boolean = false
        private set

    fun start() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS staff_rank_cache (
                uuid TEXT PRIMARY KEY,
                rank_id TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        reload()
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getOnlinePlayers().forEach { record(it) }
    }

    fun reload() {
        val section = plugin.config.getConfigurationSection("staff-ranks.groups")
        val configured = HashMap<StaffRank, List<String>>()
        if (section != null) {
            for (key in section.getKeys(false)) {
                val rank = StaffRank.fromId(key)
                if (rank == null) {
                    plugin.logger.warning("[StaffRanks] Unknown staff rank '$key' in staff-ranks.groups; ignored.")
                    continue
                }
                val list = section.getStringList(key).ifEmpty { listOfNotNull(section.getString(key)) }
                configured[rank] = list
            }
        }
        val built = StaffRankGroups(configured)
        built.problems.forEach { plugin.logger.warning("[StaffRanks] $it") }
        groups = built
        allowEqualRank = plugin.config.getBoolean("staff-ranks.allow-equal-rank-actions", false)
    }

    // ── Resolution ───────────────────────────────────────────

    /** The live staff rank of an online player. */
    fun rankOf(player: Player): StaffRank? {
        if (player.isOp || player.hasPermission(WILDCARD_PROBE_NODE)) return StaffRank.OWNER
        val explicit = player.effectivePermissions.filter { it.value }.mapTo(HashSet()) { it.permission }
        return groups.resolve(explicit, plugin.rankManager.getPlayerRankIds(player.uniqueId))
    }

    /** Online: live. Offline: the higher of the cached rank and JoshyMC's rank records (ops = Owner). */
    fun rankOf(uuid: UUID): StaffRank? {
        Bukkit.getPlayer(uuid)?.let { return rankOf(it) }
        val cached = try {
            plugin.databaseManager.queryFirst("SELECT rank_id FROM staff_rank_cache WHERE uuid = ?", uuid.toString()) { rs ->
                StaffRank.fromId(rs.getString("rank_id"))
            }
        } catch (e: Exception) {
            plugin.logger.warning("[StaffRanks] Could not read the cached rank of $uuid: ${e.message}")
            null
        }
        val recorded = groups.resolve(emptySet(), plugin.rankManager.getPlayerRankIds(uuid))
        val op = if (Bukkit.getOperators().any { it.uniqueId == uuid }) StaffRank.OWNER else null
        return StaffRank.highest(listOf(cached, recorded, op))
    }

    fun actor(sender: CommandSender): StaffActor =
        if (sender is Player) StaffActor.Staff(rankOf(sender)) else StaffActor.Console

    private fun target(sender: CommandSender, uuid: UUID): StaffTarget =
        StaffTarget(rankOf(uuid), self = (sender as? Player)?.uniqueId == uuid)

    /** The sender's `joshymc.punish.<level>`. Console and ops act as Sr Admin. */
    fun punishLevel(sender: CommandSender): StaffLevel? =
        if (sender !is Player || sender.isOp) StaffLevel.SRADMIN else StaffLevel.highest { sender.hasPermission(it.permission) }

    // ── Questions ────────────────────────────────────────────

    /** Null when [sender] may act on [targetUuid] (self, equal and higher staff refused). */
    fun protectionProblem(sender: CommandSender, targetUuid: UUID): String? =
        StaffProtection.decide(actor(sender), target(sender, targetUuid), allowEqualRank).message

    /** Null when [sender] may add/remove [rankId] on [targetUuid]. */
    fun rankChangeProblem(sender: CommandSender, targetUuid: UUID, rankId: String): String? =
        RankAssignmentRules.decide(actor(sender), target(sender, targetUuid), groups.rankForGroup(rankId), allowEqualRank).message

    /**
     * Null when [sender] may take a legacy punishment [action] against [targetUuid]: the level
     * cap ([LegacyPunishAuthority]) and, for anything that punishes, rank protection. Reversals
     * only refuse self-targets (a banned or muted staff member may be helped by anyone allowed).
     */
    fun legacyPunishProblem(sender: CommandSender, targetUuid: UUID, action: LegacyPunishAuthority.Action, durationMs: Long? = null): String? {
        LegacyPunishAuthority.problem(punishLevel(sender), action, durationMs)?.let { return it }
        val reversal = action == LegacyPunishAuthority.Action.UNBAN || action == LegacyPunishAuthority.Action.UNMUTE ||
            action == LegacyPunishAuthority.Action.UNWARN
        if (reversal) {
            return if (sender is Player && sender.uniqueId == targetUuid) StaffProtection.Decision.SELF.message else null
        }
        return protectionProblem(sender, targetUuid)
    }

    // ── Cache ────────────────────────────────────────────────

    private fun record(player: Player) {
        val rank = try { rankOf(player) } catch (_: Exception) { return }
        // Ops count as Owner for protection while online, but the cache only keeps real groups,
        // so de-opping someone doesn't leave them protected as Owner.
        val stored = if (player.isOp || player.hasPermission(WILDCARD_PROBE_NODE)) {
            val explicit = player.effectivePermissions.filter { it.value }.mapTo(HashSet()) { it.permission }
            groups.resolve(explicit, plugin.rankManager.getPlayerRankIds(player.uniqueId))
        } else rank
        val uuid = player.uniqueId.toString()
        plugin.databaseManager.async("staff rank cache for $uuid") {
            if (stored == null) {
                plugin.databaseManager.execute("DELETE FROM staff_rank_cache WHERE uuid = ?", uuid)
            } else {
                plugin.databaseManager.execute(
                    "INSERT INTO staff_rank_cache (uuid, rank_id, updated_at) VALUES (?, ?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET rank_id = excluded.rank_id, updated_at = excluded.updated_at",
                    uuid, stored.id, System.currentTimeMillis()
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) = record(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = record(event.player)

    companion object {
        /** Never granted and not in plugin.yml: only ops and `*` holders answer yes. */
        private const val WILDCARD_PROBE_NODE = "joshymc.internal.staffrank.wildcardprobe"
    }
}
