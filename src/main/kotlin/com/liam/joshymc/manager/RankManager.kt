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
import org.bukkit.event.player.PlayerChangedWorldEvent
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
        val weight: Int,          // Higher = more important
        val category: String = "Ranks"  // Grouping shown by /rank list, e.g. "Staff Ranks"
    )

    /**
     * A configurable EXTRA-slot perk for a purchasable rank (Scout, Pathfinder, ...).
     * These ranks live entirely in LuckPerms/permissions — not in [ranks] — so eligibility
     * is resolved via the "joshymc.rankperk.<key>" permission node, same pattern as /mcr.
     */
    data class RankPerk(
        val key: String,
        val auctionExtraListings: Int,
        val ordersExtraOrders: Int,
        val sellMultiplier: Double = 1.0
    )

    private val ranks = mutableMapOf<String, Rank>()
    private val playerRanks = mutableMapOf<UUID, MutableSet<String>>() // UUID -> set of rank IDs
    // UUID -> donor tiers JoshyMC granted (player_ranks rows that are DonorTiers, issue #1173)
    private val donorTierRecords = mutableMapOf<UUID, MutableSet<String>>()
    // Players with a donor-tier grant in flight; shared by Rank Vouchers and the Credit Shop.
    private val donorChangeLocks = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
    private val collisionStates = mutableMapOf<UUID, Boolean>()
    private val rankPerks = mutableListOf<RankPerk>()

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

        // Load rank-perk EXTRA-slot bonuses (Auction House / Buy Orders) from config
        loadRankPerks()

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
        collisionStates.remove(event.player.uniqueId)
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

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        refreshCollisionIfChanged(event.player)
    }

    /** Update all viewers only when a player's collision-team variant changes. */
    fun refreshCollisionIfChanged(player: Player) {
        if (!player.isOnline) return
        val collide = shouldCollide(player)
        if (collisionStates[player.uniqueId] != collide) applyTeamFor(player)
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
        collisionStates[player.uniqueId] = shouldCollide(player)
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
     * Existing teams keep their entries and settings. Two variants per rank:
     * `<name>_y` (collisions
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
                if (existing == null) {
                    val team = board.registerNewTeam(name)
                    team.prefix(legacy.deserialize("&8[${rank.displayTag}&8] &r"))
                    team.setOption(
                        org.bukkit.scoreboard.Team.Option.COLLISION_RULE,
                        if (collide) org.bukkit.scoreboard.Team.OptionStatus.ALWAYS
                        else org.bukkit.scoreboard.Team.OptionStatus.NEVER
                    )
                }
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
        private const val DONOR_CONFIRM_POLL_TICKS = 10L
        private const val DONOR_CONFIRM_TIMEOUT_TICKS = 200L
        /** Never granted to anyone and not in plugin.yml: only ops and wildcards hold it. */
        private const val WILDCARD_PROBE_NODE = "joshymc.internal.donortier.wildcardprobe"
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
        val section = plugin.config.getConfigurationSection("ranks.list")

        // No ranks.list in config at all (e.g. the admin deliberately removed it to manage
        // ranks entirely via LuckPerms). Fall back to a code-level "default" rank only, in
        // memory — never write example ranks back into config.yml. See issue #911.
        if (section == null) {
            plugin.logger.info("[Ranks] No ranks.list found in config; using a code-level 'default' rank fallback only.")
            ranks["default"] = Rank("default", "&8Player", 0, "Player Ranks")
            return
        }

        for (id in section.getKeys(false)) {
            val rankSection = section.getConfigurationSection(id) ?: continue
            val tag = rankSection.getString("tag", "&7$id") ?: "&7$id"
            val weight = rankSection.getInt("weight", 0)
            val category = rankSection.getString("category", "Ranks") ?: "Ranks"
            ranks[id] = Rank(id, tag, weight, category)
        }
    }

    private fun loadRankPerks() {
        rankPerks.clear()
        val section = plugin.config.getConfigurationSection("rank-perks") ?: return
        for (key in section.getKeys(false)) {
            val perkSection = section.getConfigurationSection(key) ?: continue
            // getInt() already falls back to 0 on missing/non-numeric YAML values; clamp negatives too.
            val auctionExtra = perkSection.getInt("auction-extra-listings", 0).coerceAtLeast(0)
            val ordersExtra = perkSection.getInt("orders-extra-orders", 0).coerceAtLeast(0)
            val sellMultiplier = perkSection.getDouble("sell-multiplier", 1.0).coerceAtLeast(0.0)
            rankPerks.add(RankPerk(key, auctionExtra, ordersExtra, sellMultiplier))
        }
    }

    private fun loadPlayerRanks() {
        playerRanks.clear()
        donorTierRecords.clear()
        val rows = plugin.databaseManager.query(
            "SELECT uuid, rank_id FROM player_ranks"
        ) { rs ->
            UUID.fromString(rs.getString("uuid")) to rs.getString("rank_id")
        }
        for ((uuid, rankId) in rows) {
            if (ranks.containsKey(rankId)) {
                playerRanks.getOrPut(uuid) { mutableSetOf() }.add(rankId)
            }
            // Donor tiers usually aren't in ranks.list, so the check above used to drop them
            // and vouchers forgot every donor after a restart (issue #1173). Keep them apart.
            DonorTiers.normalize(rankId)?.let { donorTierRecords.getOrPut(uuid) { mutableSetOf() }.add(it) }
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
     * Resolves the single highest-applicable purchasable rank-perk for this player, based on
     * the "joshymc.rankperk.<key>" permission node (granted per LuckPerms group externally).
     * Bonuses are NOT stacked across inherited groups — only the perk with the largest
     * auction-extra-listings (Buy Order extra as tiebreaker) among the player's granted
     * perks is used, so both fields always come from the same rank. Returns null if the
     * player has no rank-perk permission.
     */
    fun getRankPerkBonus(player: Player): RankPerk? {
        return rankPerks
            .filter { player.hasPermission("joshymc.rankperk.${it.key}") }
            .maxWithOrNull(compareBy({ it.auctionExtraListings }, { it.ordersExtraOrders }))
    }

    /**
     * The player's sell-price multiplier from the same rank-perk pool as the auction/order
     * bonuses above — resolved live off [getRankPerkBonus], so it's permission-based (never
     * display name/prefix), reflects rank changes immediately, and never stacks across
     * multiple eligible ranks. 1.0 (no bonus) if the player has no eligible rank-perk.
     */
    fun getSellMultiplier(player: Player): Double = getRankPerkBonus(player)?.sellMultiplier ?: 1.0

    /**
     * What happened on the LuckPerms side of a rank change. Changes go through LP's
     * console command, which LP applies asynchronously, so [REQUESTED] only means the
     * command was dispatched, never that LuckPerms has saved it.
     */
    enum class LuckPermsSync {
        /** LuckPerms isn't installed, or the JoshyMC rank set didn't change. */
        SKIPPED,
        /** The targeted `parent add` / `parent remove` command was dispatched. */
        REQUESTED,
        /** The command couldn't be dispatched (unknown player name, error). Logged. */
        FAILED
    }

    /** Result of [addRankDetailed] / [removeRankDetailed]. */
    data class RankChange(val changed: Boolean, val luckPerms: LuckPermsSync)

    /**
     * Add ONLY [rankId] to [uuid]'s rank set, leaving every other rank the
     * player already has untouched. Returns false (no changes made) if the
     * player already has this rank.
     */
    fun addRank(uuid: UUID, rankId: String): Boolean = addRankDetailed(uuid, rankId).changed

    /**
     * Remove ONLY [rankId] from [uuid]'s rank set, leaving every other rank
     * the player has untouched. Returns false (no changes made) if the
     * player doesn't have this rank.
     */
    fun removeRank(uuid: UUID, rankId: String): Boolean = removeRankDetailed(uuid, rankId).changed

    /**
     * [addRank], also reporting the LuckPerms side. Only ever issues `parent add` for
     * [rankId] (plus `default` when it was missing), so donor, staff and externally
     * managed LuckPerms groups are never touched. There is intentionally no "set rank"
     * operation: LP's `parent set` replaces every parent group (issue #1167).
     */
    fun addRankDetailed(uuid: UUID, rankId: String): RankChange {
        val set = playerRanks.getOrPut(uuid) { mutableSetOf() }
        if (rankId in set) return RankChange(false, LuckPermsSync.SKIPPED)
        set.add(rankId)
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO player_ranks (uuid, rank_id) VALUES (?, ?)",
            uuid.toString(), rankId
        )
        DonorTiers.normalize(rankId)?.let { donorTierRecords.getOrPut(uuid) { mutableSetOf() }.add(it) }
        // Preserve the default rank alongside any non-default rank so players
        // retain their base permissions/display when a special rank is added.
        if (rankId != "default" && ranks.containsKey("default") && set.add("default")) {
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO player_ranks (uuid, rank_id) VALUES (?, ?)",
                uuid.toString(), "default"
            )
            addLuckPermsGroup(uuid, "default")
        }

        Bukkit.getPlayer(uuid)?.let { applyTeamFor(it) }
        return RankChange(true, addLuckPermsGroup(uuid, rankId))
    }

    /**
     * [removeRank], also reporting the LuckPerms side. Only ever issues `parent remove`
     * for [rankId]; every other player_ranks row and LuckPerms parent is left alone.
     */
    fun removeRankDetailed(uuid: UUID, rankId: String): RankChange {
        val set = playerRanks[uuid]
        val donorSet = donorTierRecords[uuid]
        val inRanks = set != null && rankId in set
        val inDonor = donorSet != null && rankId in donorSet
        if (!inRanks && !inDonor) return RankChange(false, LuckPermsSync.SKIPPED)
        if (set != null && set.remove(rankId) && set.isEmpty()) playerRanks.remove(uuid)
        if (donorSet != null && donorSet.remove(rankId) && donorSet.isEmpty()) donorTierRecords.remove(uuid)
        plugin.databaseManager.execute(
            "DELETE FROM player_ranks WHERE uuid = ? AND rank_id = ?",
            uuid.toString(), rankId
        )

        Bukkit.getPlayer(uuid)?.let { applyTeamFor(it) }
        return RankChange(true, removeLuckPermsGroup(uuid, rankId))
    }

    /** Add a single LuckPerms parent node without touching any other group. */
    private fun addLuckPermsGroup(uuid: UUID, rankId: String): LuckPermsSync =
        dispatchLuckPerms(uuid, rankId, "add")

    /** Remove a single LuckPerms parent node without touching any other group. */
    private fun removeLuckPermsGroup(uuid: UUID, rankId: String): LuckPermsSync =
        dispatchLuckPerms(uuid, rankId, "remove")

    // Uses LP's console command surface, which avoids a hard compile-time dependency.
    // Only `parent add` / `parent remove` are ever sent, never `parent set`. The user is
    // addressed by UUID (LP accepts a UUID wherever it takes a username), so offline players,
    // renamed players and Floodgate accounts all resolve to the exact account (issue #1173).
    private fun dispatchLuckPerms(uuid: UUID, rankId: String, action: String): LuckPermsSync {
        if (!isLuckPermsAvailable()) return LuckPermsSync.SKIPPED
        return try {
            if (Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user $uuid parent $action $rankId")) {
                LuckPermsSync.REQUESTED
            } else {
                plugin.logger.warning("[Ranks] LuckPerms parent $action $rankId was not accepted for $uuid.")
                LuckPermsSync.FAILED
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Ranks] LuckPerms parent $action failed for $uuid ($rankId): ${e.message}")
            LuckPermsSync.FAILED
        }
    }

    fun isLuckPermsAvailable(): Boolean = Bukkit.getPluginManager().isPluginEnabled("LuckPerms")

    // ── Donor tiers (issue #1173) ───────────────────────

    /** Donor tiers JoshyMC itself granted [uuid] (its player_ranks records), lowest first. */
    fun getDonorTierRecords(uuid: UUID): List<String> =
        donorTierRecords[uuid].orEmpty().sortedBy { DonorTiers.index(it) }

    /** Donor tiers [player]'s live permissions show (LuckPerms groups or rank-perk nodes), lowest first. */
    fun liveDonorTiers(player: Player): Set<String> {
        val explicit = explicitPermissions(player)
        return DonorTiers.liveTiers(explicit, hasWildcard(player)) { player.hasPermission(it) }
    }

    /** Where a player's donor tier comes from: [live] is only known while they're online. */
    data class DonorTierStatus(val tier: String?, val recorded: List<String>, val live: Set<String>, val liveChecked: Boolean)

    /**
     * The single donor-tier resolver every donor system uses: the highest of JoshyMC's own
     * records (survive restarts and cover offline players) and, for an online player, the
     * tiers their live LuckPerms permissions show (covers direct LuckPerms assignments and
     * inheritance). Staff groups never count: only the seven [DonorTiers] are considered.
     */
    fun donorTierStatus(uuid: UUID): DonorTierStatus {
        val recorded = getDonorTierRecords(uuid)
        val online = Bukkit.getPlayer(uuid)
        val live = online?.let { liveDonorTiers(it) } ?: emptySet()
        return DonorTierStatus(DonorTiers.highest(recorded + live), recorded, live, online != null)
    }

    fun currentDonorTier(uuid: UUID): String? = donorTierStatus(uuid).tier

    /**
     * Claim [uuid]'s donor-change slot. Every donor grant (voucher or Credit Shop) holds it
     * from validation until the grant is confirmed or rolled back, so two purchases can't
     * both pass the ownership check and charge twice. False if a change is already running.
     */
    fun tryLockDonorChange(uuid: UUID): Boolean = donorChangeLocks.add(uuid)

    fun unlockDonorChange(uuid: UUID) {
        donorChangeLocks.remove(uuid)
    }

    fun isDonorChangeLocked(uuid: UUID): Boolean = uuid in donorChangeLocks

    enum class DonorGrantResult {
        /** LuckPerms shows the new group on the player; JoshyMC recorded the tier. */
        CONFIRMED,
        /** LuckPerms isn't installed, so nothing was sent. */
        UNAVAILABLE,
        /** The `parent add` command couldn't be dispatched; nothing changed. */
        DISPATCH_FAILED,
        /** LuckPerms didn't show the group within the timeout; a `parent remove` undid the request. */
        UNCONFIRMED,
        /** The player left before it could be confirmed. Callers keep it pending and settle it on their next join. */
        PLAYER_LEFT
    }

    /**
     * Grants [tier] to [player] through a single targeted `lp user <uuid> parent add`, then
     * waits (up to [DONOR_CONFIRM_TIMEOUT_TICKS]) until LuckPerms actually shows the group on
     * the player before calling [onResult] with [DonorGrantResult.CONFIRMED]. LuckPerms
     * applies console commands asynchronously, so a dispatched command proves nothing.
     *
     * On confirmation the tier is recorded in player_ranks and lower tiers that JoshyMC
     * itself recorded are removed (`parent remove`, one group each). Staff and every group
     * JoshyMC didn't grant are never touched, and `parent set` is never used.
     *
     * The caller must hold [tryLockDonorChange] and must already have checked that [tier] is
     * an upgrade. [onResult] always runs on the main thread, exactly once, unless the plugin
     * is disabled first (callers persist a pending row for that case).
     */
    fun grantDonorTier(player: Player, tier: String, onResult: (DonorGrantResult) -> Unit) {
        val uuid = player.uniqueId
        if (!isLuckPermsAvailable()) {
            onResult(DonorGrantResult.UNAVAILABLE)
            return
        }
        if (addLuckPermsGroup(uuid, tier) != LuckPermsSync.REQUESTED) {
            onResult(DonorGrantResult.DISPATCH_FAILED)
            return
        }
        object : org.bukkit.scheduler.BukkitRunnable() {
            var waited = 0L
            override fun run() {
                waited += DONOR_CONFIRM_POLL_TICKS
                if (!player.isOnline) {
                    cancel()
                    plugin.logger.info("[Ranks] ${player.name} ($uuid) left before LuckPerms confirmed donor tier $tier; it stays pending until they rejoin.")
                    onResult(DonorGrantResult.PLAYER_LEFT)
                    return
                }
                if (isDonorGroupConfirmed(player, tier)) {
                    cancel()
                    completeDonorGrant(uuid, tier)
                    onResult(DonorGrantResult.CONFIRMED)
                    return
                }
                if (waited >= DONOR_CONFIRM_TIMEOUT_TICKS) {
                    cancel()
                    plugin.logger.warning("[Ranks] LuckPerms did not confirm donor tier $tier for ${player.name} ($uuid) within ${DONOR_CONFIRM_TIMEOUT_TICKS / 20}s; sending parent remove to undo the request.")
                    revokeUnconfirmedDonorGrant(uuid, tier)
                    onResult(DonorGrantResult.UNCONFIRMED)
                }
            }
        }.runTaskTimer(plugin, DONOR_CONFIRM_POLL_TICKS, DONOR_CONFIRM_POLL_TICKS)
    }

    /** True once LuckPerms shows [tier]'s group on [player] (explicit node, or a live check for non-wildcard players). */
    fun isDonorGroupConfirmed(player: Player, tier: String): Boolean =
        DonorTiers.groupConfirmed(tier, explicitPermissions(player), hasWildcard(player)) { player.hasPermission(it) }

    /**
     * Bookkeeping after LuckPerms confirmed [tier]: record it in player_ranks so it survives
     * restarts, then drop the lower tiers JoshyMC recorded earlier (they're redundant). Lower
     * tiers assigned outside JoshyMC are left alone.
     */
    fun completeDonorGrant(uuid: UUID, tier: String) {
        val records = donorTierRecords.getOrPut(uuid) { mutableSetOf() }
        if (records.add(tier)) {
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO player_ranks (uuid, rank_id) VALUES (?, ?)",
                uuid.toString(), tier
            )
        }
        if (ranks.containsKey(tier)) {
            playerRanks.getOrPut(uuid) { mutableSetOf() }.add(tier)
            Bukkit.getPlayer(uuid)?.let { applyTeamFor(it) }
        }
        for (lower in DonorTiers.below(tier, records.toList())) {
            val change = removeRankDetailed(uuid, lower)
            if (change.luckPerms == LuckPermsSync.FAILED) {
                plugin.logger.warning("[Ranks] Could not remove redundant donor tier $lower from $uuid after upgrading to $tier; remove it in LuckPerms by hand.")
            }
        }
    }

    /** Undo a `parent add` for [tier] that never showed up (or never completed): one targeted `parent remove`. */
    fun revokeUnconfirmedDonorGrant(uuid: UUID, tier: String) {
        if (removeLuckPermsGroup(uuid, tier) == LuckPermsSync.FAILED) {
            plugin.logger.warning("[Ranks] Could not send parent remove $tier for $uuid; check that player's LuckPerms groups by hand.")
        }
    }

    private fun explicitPermissions(player: Player): Set<String> =
        player.effectivePermissions.filter { it.value }.mapTo(HashSet()) { it.permission }

    /** True for ops and wildcard holders: they answer yes to a node nobody is ever granted. */
    private fun hasWildcard(player: Player): Boolean = player.hasPermission(WILDCARD_PROBE_NODE)

    /**
     * Get the formatted prefix for a player's rank (with color codes).
     * Returns empty string if no rank.
     */
    fun getPrefix(player: Player): String {
        val rank = getPlayerRank(player) ?: return ""
        return "&8[${rank.displayTag}&8] &r"
    }
}
