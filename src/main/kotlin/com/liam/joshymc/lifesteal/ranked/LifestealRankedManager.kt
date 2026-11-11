package com.liam.joshymc.lifesteal.ranked

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.LifestealSettings
import com.liam.joshymc.lifesteal.RankedForfeitHeartLoss
import com.liam.joshymc.lifesteal.combat.CombatLogRules
import com.liam.joshymc.lifesteal.combat.npc.CombatNpcRules
import com.liam.joshymc.lifesteal.eligibility.CompetitiveEligibility
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.hearts.HeartStore
import com.liam.joshymc.lifesteal.progression.ProgressionRules
import com.liam.joshymc.lifesteal.ranked.monitor.RankedFarmingMonitor
import com.liam.joshymc.lifesteal.ranked.monitor.RankedMonitorRules
import com.liam.joshymc.lifesteal.ranked.queue.TestMatchPlan
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore
import com.liam.joshymc.recovery.SafeSpots
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.EnderCrystal
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.BoundingBox
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Ranked Team Wars arena and match infrastructure (issue #1361). Lifesteal only: on a server that
 * doesn't host Lifesteal it creates no tables, its listener isn't registered and every action
 * refuses, so Survival teams, PvP, inventories, arenas and data are never touched.
 *
 * Matches are created by staff (`/lifesteal admin ranked testmatch`, [createMatch]) or the ranked
 * queue ([createQueuedMatch], issue #1363). Elo (issue #1365) is applied after a match is recorded
 * COMPLETED, by [com.liam.joshymc.lifesteal.ranked.rating.LifestealRatingManager].
 *
 * **Inventory**: players fight with their real Lifesteal gear. Before anything happens to them,
 * their whole inventory (36 storage slots, armor, off-hand, as Paper's lossless
 * `serializeAsBytes`, so durability, enchantments, custom enchants, PDC custom item ids and Heart
 * serials are kept exactly), held slot and return location are written to `data.db` in the same
 * transaction as the match. Afterwards (win, loss, cancel, death, disconnect, crash) the inventory
 * is *set* back to that snapshot exactly once (PENDING → RESTORED), so nothing used, dropped or
 * picked up in the match is gained or lost. While a match holds a player, every way an item could
 * leave their inventory for good (dropping, containers, ender chest, trades, commands, vouchers,
 * Heart items, block placing) is refused, so restoring can never duplicate anything.
 *
 * **Hearts** are untouched by this system: kills go through the normal heart service
 * ([com.liam.joshymc.lifesteal.hearts.LifestealHeartManager.onDeath]) with its limits, cooldown
 * and team checks. Restoring an inventory never restores hearts. The one exception (issue #1465)
 * is a counted disconnect from an ACTIVE fight ([onQuit], [RankedMatchRules.disconnect]): the
 * leaver forfeits the match, and the same heart service may move one heart to the opponent who hit
 * them most recently ([RankedMatchRules.forfeitRecipient]), under every normal heart rule.
 *
 * **Database work**: only at lifecycle edges (match creation, state changes, restorations), each one
 * short transaction on the local SQLite file, like the heart system. Deaths and disconnects during
 * combat are queued through `databaseManager.async`; nothing reads or writes per hit or per tick.
 */
class LifestealRankedManager(private val plugin: Joshymc) {

    // ── Runtime model ───────────────────────────────────────

    /** A match that is not over. Main thread only. Holds UUIDs, never Player references. */
    inner class Match(
        val id: String,
        val arena: RankedArena,
        val teamA: LifestealTeamStore.Team,
        val teamB: LifestealTeamStore.Team,
        val size: Int,
        val kind: MatchKind,
        val seasonId: String,
        val createdBy: String,
        val createdAt: Long,
        var roster: MatchRoster,
    ) {
        val test: Boolean get() = kind == MatchKind.TEST
        var state: MatchState = MatchState.CREATED
            internal set
        var startedAt: Long? = null
            internal set
        val spectators = LinkedHashSet<UUID>()
        internal val tasks = mutableListOf<BukkitTask>()
        internal var decisionPending = false
        /** Participants whose `allowFlight` this match turned off (given back afterwards). */
        internal val flightTaken = HashSet<UUID>()
        /** Issue #1465: opponent hits on each participant during the fight (bounded), for disconnect forfeits. */
        internal val recentHits = HashMap<UUID, List<HeartRules.RecentHit>>()
        /** Issue #1465: participants who went out since the last decision (same-tick tie-break). */
        internal val outsSinceDecision = mutableListOf<MatchParticipant>()
        /**
         * Issue #1474: what the heart service decided for each kill/forfeit of this match, in order,
         * for the end-of-match summary (bounded by [RankedHeartSummary.MAX_ENTRIES]; gone with the match).
         */
        var heartOutcomes: List<RankedHeartSummary.Entry> = emptyList()
            internal set
        /**
         * Issue #1498: sides that had a player in a restricted state during the fight. Written with
         * the winner, so a win by such a side is never rated ([RankedIntegrity.ratingBlocked]).
         */
        val ineligibleSides = HashSet<Side>()

        fun team(side: Side): LifestealTeamStore.Team = if (side == Side.A) teamA else teamB
        val label: String get() = "${teamA.name} vs ${teamB.name} (${size}v$size${when (kind) { MatchKind.TEST -> ", TEST"; MatchKind.CHALLENGE -> ", challenge"; MatchKind.PUBLIC -> "" }})"

        internal fun track(task: BukkitTask) {
            tasks.removeIf { it.isCancelled }
            tasks += task
        }

        internal fun cancelTasks() {
            for (t in tasks) t.cancel()
            tasks.clear()
        }
    }

    private val store = RankedStore(plugin.databaseManager)

    /** Issue #1474: logging-only anti-farming monitoring of finished matches. */
    val monitor = RankedFarmingMonitor(plugin)

    @Volatile private var tablesReady = false
    @Volatile var settings: LifestealSettings.RankedTeamWars? = null
        private set
    private var recoveredThisBoot = false

    private val arenas = LinkedHashMap<String, RankedStore.LoadedArena>()
    private val matches = LinkedHashMap<String, Match>()
    /** Participants currently held by a match (not defeated). */
    private val held = HashMap<UUID, String>()
    /** Staff spectating a match. */
    private val spectating = HashMap<UUID, String>()
    /** Players with a PENDING snapshot (loaded at startup, kept in step afterwards), so joins don't query the DB. */
    private val pendingRestore: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    /** Snapshots of this session's matches, so restoring an online player doesn't read the DB. */
    private val cachedSnapshots = HashMap<UUID, RankedStore.Snapshot>()
    /** Players being restored right now (re-entry guard). */
    private val restoring = HashSet<UUID>()
    /** Teleports this system started, by player: the listener lets exactly these through. */
    private val expectedTeleports = HashMap<UUID, Location>()

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes
    val active: Boolean get() = hosted && tablesReady && settings != null

    fun isLifesteal(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    // ── Read API (memory only; safe from listeners) ─────────

    /** [uuid] is held by a match: inventory protected, teleports, commands and transfers restricted. */
    fun holds(uuid: UUID): Boolean = held.containsKey(uuid)

    fun holds(player: Player): Boolean = holds(player.uniqueId)

    fun matchOf(uuid: UUID): Match? = held[uuid]?.let { matches[it] }

    fun spectatorMatch(uuid: UUID): Match? = spectating[uuid]?.let { matches[it] }

    fun isSpectator(uuid: UUID): Boolean = spectating.containsKey(uuid)

    /**
     * Issue #1478: the opponent hits [uuid]'s match recorded on them (landed hits between opposite
     * sides of the ACTIVE fight, bounded, dropped by their defeat and with the match). A copy.
     */
    fun opponentHitsOn(uuid: UUID): List<HeartRules.RecentHit> = matchOf(uuid)?.recentHits?.get(uuid).orEmpty()

    /**
     * Issue #1478: [attacker] may be credited with [victim]'s environmental death in [matchId]: that
     * match is still ACTIVE (a death before the fight cancels it, so it never is) and the two are on
     * opposite roster sides. A defeated attacker keeps their side, so a hit that sent the victim
     * into lava still counts after the attacker died too; never a teammate, never another match.
     */
    fun isCreditableOpponent(matchId: String, attacker: UUID, victim: UUID): Boolean {
        val match = matches[matchId] ?: return false
        return match.state == MatchState.ACTIVE && match.roster.sameSide(attacker, victim) == false
    }

    /** Both are still fighting in the same ACTIVE match (no combat tag, PvP toggles don't apply). */
    fun isMatchFight(a: Player, b: Player): Boolean {
        val m = matchOf(a.uniqueId) ?: return false
        return m.state == MatchState.ACTIVE && held[b.uniqueId] == m.id && m.roster.isActive(a.uniqueId) && m.roster.isActive(b.uniqueId)
    }

    /** Fighting in an ACTIVE match right now. */
    fun inActiveFight(player: Player): Boolean {
        val m = matchOf(player.uniqueId) ?: return false
        return m.state == MatchState.ACTIVE && m.roster.isActive(player.uniqueId)
    }

    /**
     * Issue #1371: same side (true) / opposite sides (false) of one open match, or null when [a]
     * and [b] aren't both on its roster. Looked up by roster, not [held], so it still answers for a
     * participant who was just defeated (the heart service runs after the death took them out).
     */
    fun matchSides(a: UUID, b: UUID): Boolean? {
        if (matches.isEmpty()) return null
        val id = held[a] ?: held[b]
        val match = id?.let { matches[it] } ?: matches.values.firstOrNull { it.roster.of(a) != null && it.roster.of(b) != null }
        return match?.roster?.sameSide(a, b)
    }

    fun hasPendingRestore(uuid: UUID): Boolean = pendingRestore.contains(uuid)

    val anyMatch: Boolean get() = matches.isNotEmpty()

    fun openMatches(): List<Match> = matches.values.toList()

    fun match(id: String): Match? = matches[id]

    /**
     * Issue #1499 (A-1): the match that isn't over in which team [teamId] plays (either side), or
     * null. Derived from the open matches themselves (removed when a match completes or is
     * cancelled, and nothing survives a restart), so there is no separate lock that could get stuck;
     * the database refuses a second open match per team as well (see [RankedStore]).
     */
    fun teamMatch(teamId: String): Match? =
        matches.values.firstOrNull { !it.state.terminal && (it.teamA.id == teamId || it.teamB.id == teamId) }

    /** Matches whose arena is in [world] and that hold players. */
    fun heldArenasIn(world: World): List<Match> = matches.values.filter { it.state.holdsPlayers && it.arena.world == world.name }

    fun isExpectedTeleport(uuid: UUID, to: Location): Boolean {
        val expected = expectedTeleports[uuid] ?: return false
        return expected.world == to.world && expected.distanceSquared(to) < 4.0
    }

    // ── Arenas ──────────────────────────────────────────────

    fun arenaList(): List<RankedStore.LoadedArena> = arenas.values.toList()

    fun arena(id: String): RankedStore.LoadedArena? = arenas[RankedArenaRules.canonicalId(id)]

    fun holderOf(arenaId: String): Match? = matches.values.firstOrNull { it.arena.id == arenaId }

    fun worldProblem(arena: RankedArena): String? {
        val world = Bukkit.getWorld(arena.world) ?: return "the world '${arena.world}' isn't loaded (arenas never load or create worlds)"
        if (!isLifesteal(world)) return "the world '${arena.world}' isn't a Lifesteal world"
        return null
    }

    fun validation(loaded: RankedStore.LoadedArena): RankedArenaRules.Validation {
        val v = RankedArenaRules.validate(loaded.arena, worldProblem(loaded.arena), arenas.values.map { it.arena }, settings?.teamSizes ?: emptyList())
        val problem = loaded.problem ?: return v
        return RankedArenaRules.Validation(ArenaReadiness.INVALID, listOf(problem) + v.problems)
    }

    fun availability(loaded: RankedStore.LoadedArena): ArenaAvailability =
        RankedArenaRules.availability(loaded.arena, validation(loaded).ready, holderOf(loaded.arena.id)?.state)

    sealed interface ArenaResult {
        data class Done(val message: String) : ArenaResult
        data class Refused(val reason: String) : ArenaResult
        data class NeedsConfirmation(val preview: String) : ArenaResult
    }

    private fun arenaUnavailable(): String? = when {
        !hosted -> "Ranked Team Wars only exist on the Lifesteal server."
        !active -> "Ranked Team Wars are unavailable (lifesteal.yml invalid or the database failed); see the console."
        else -> null
    }

    fun createArena(sender: CommandSender, rawId: String, rawName: String?, world: World): ArenaResult {
        arenaUnavailable()?.let { return ArenaResult.Refused(it) }
        val id = RankedArenaRules.canonicalId(rawId)
        if (!RankedArenaRules.validId(id)) return ArenaResult.Refused("Arena ids are 1-32 characters: a-z, 0-9, _ and -.")
        if (!isLifesteal(world)) return ArenaResult.Refused("Stand in a Lifesteal world to create an arena there.")
        val name = RankedArenaRules.cleanName(rawName ?: id) ?: return ArenaResult.Refused("The display name must be 1-${RankedArenaRules.MAX_NAME_LENGTH} characters.")
        val now = System.currentTimeMillis()
        val created = try {
            store.createArena(RankedArena(id, name, world.name, null, sizes = settings?.teamSizes?.toSet() ?: RankedArenaRules.ALL_SIZES), now)
        } catch (e: Exception) {
            return ArenaResult.Refused("The arena couldn't be saved (${e.message}); nothing changed.")
        }
        if (!created) return ArenaResult.Refused("An arena called '$id' already exists.")
        reloadArena(id)
        plugin.logger.info("[Ranked] ${sender.name} created arena $id in ${world.name}.")
        return ArenaResult.Done("Arena '$id' created in ${world.name}. Next: setcorner 1/2, setspawn a/b, then enable.")
    }

    /** Applies [edit] to arena [rawId] (refused while a match holds it), saved conditionally on its version. */
    fun editArena(sender: CommandSender, rawId: String, what: String, edit: (RankedArena) -> Pair<RankedArena?, String?>): ArenaResult {
        arenaUnavailable()?.let { return ArenaResult.Refused(it) }
        val loaded = arena(rawId) ?: return ArenaResult.Refused("No arena called '$rawId'.")
        holderOf(loaded.arena.id)?.let { return ArenaResult.Refused("Match ${it.id} is using '${loaded.arena.id}'; edit it after the match.") }
        val (next, problem) = edit(loaded.arena)
        if (next == null) return ArenaResult.Refused(problem ?: "Nothing changed.")
        try {
            store.updateArena(next, System.currentTimeMillis())
        } catch (e: RankedStore.StaleWriteException) {
            reloadArena(loaded.arena.id)
            return ArenaResult.Refused("The arena changed meanwhile; nothing changed. Try again.")
        } catch (e: Exception) {
            return ArenaResult.Refused("The arena couldn't be saved (${e.message}); nothing changed.")
        }
        reloadArena(loaded.arena.id)
        plugin.logger.info("[Ranked] ${sender.name} changed arena ${loaded.arena.id}: $what.")
        val after = arena(loaded.arena.id)
        val v = after?.let { validation(it) }
        // An enabled arena that an edit made unusable is switched off, never left selectable.
        if (after != null && v != null && after.arena.enabled && !v.ready) {
            try {
                store.updateArena(after.arena.copy(enabled = false), System.currentTimeMillis())
                reloadArena(after.arena.id)
                return ArenaResult.Done("$what. The arena is no longer ready (${v.problems.joinToString("; ")}), so it was disabled.")
            } catch (_: Exception) { }
        }
        return ArenaResult.Done(what + if (v != null && !v.ready) ". Not ready yet: ${v.problems.joinToString("; ")}" else ".")
    }

    fun setEnabled(sender: CommandSender, rawId: String, enabled: Boolean): ArenaResult = editArena(sender, rawId, if (enabled) "Enabled" else "Disabled") { arena ->
        if (arena.enabled == enabled) return@editArena null to "It is already ${if (enabled) "enabled" else "disabled"}."
        if (enabled) {
            val v = validation(RankedStore.LoadedArena(arena.copy(enabled = true), arenas[arena.id]?.problem))
            if (!v.ready) return@editArena null to "It can't be enabled: ${v.problems.joinToString("; ")}."
        }
        arena.copy(enabled = enabled) to null
    }

    fun deleteArena(sender: CommandSender, rawId: String, confirmed: Boolean): ArenaResult {
        arenaUnavailable()?.let { return ArenaResult.Refused(it) }
        val loaded = arena(rawId) ?: return ArenaResult.Refused("No arena called '$rawId'.")
        holderOf(loaded.arena.id)?.let { return ArenaResult.Refused("Match ${it.id} is using '${loaded.arena.id}'; it can't be deleted until the match is over.") }
        if (!confirmed) return ArenaResult.NeedsConfirmation("This deletes arena '${loaded.arena.id}' (${loaded.arena.displayName}). Match records that used it are kept. World blocks are never touched.")
        val result = try {
            store.deleteArena(loaded.arena.id, System.currentTimeMillis())
        } catch (e: Exception) {
            return ArenaResult.Refused("The arena couldn't be deleted (${e.message}); nothing changed.")
        }
        reloadArena(loaded.arena.id)
        return when (result) {
            RankedStore.DeleteResult.DELETED -> {
                plugin.logger.warning("[Ranked] ${sender.name} deleted arena ${loaded.arena.id}.")
                ArenaResult.Done("Arena '${loaded.arena.id}' deleted.")
            }
            RankedStore.DeleteResult.NOT_FOUND -> ArenaResult.Refused("No arena called '$rawId'.")
            RankedStore.DeleteResult.IN_USE -> ArenaResult.Refused("A match that isn't over still uses '${loaded.arena.id}'.")
        }
    }

    private fun reloadArena(id: String) {
        try {
            val loaded = store.arena(id)
            if (loaded == null) arenas.remove(id) else arenas[id] = loaded
        } catch (e: Exception) {
            plugin.logger.warning("[Ranked] Couldn't reload arena $id: ${e.message}")
        }
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealConfigManager.load(). Running matches keep going. */
    fun refresh() {
        if (!hosted) {
            settings = null
            return
        }
        val lifesteal = plugin.lifestealConfigManager.settings
        if (lifesteal == null) {
            // Running matches finish normally; no new match can start until lifesteal.yml is fixed.
            settings = null
            plugin.logger.severe("[Ranked] lifesteal.yml is invalid, so no ranked match can start (inventories waiting to be restored are still restored).")
        } else {
            settings = lifesteal.rankedTeamWars
        }
        try {
            store.createTables()
            tablesReady = true
        } catch (e: Exception) {
            tablesReady = false
            plugin.logger.severe("[Ranked] Could not create the ranked tables; Ranked Team Wars are off: ${e.message}")
            return
        }
        if (!recoveredThisBoot) {
            recoveredThisBoot = true
            recover()
        }
        monitor.refresh()
        try {
            val loaded = store.arenas()
            arenas.clear()
            for (a in loaded) arenas[a.arena.id] = a
            pendingRestore.clear()
            pendingRestore += store.pendingSnapshotOwners()
        } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Could not read the ranked arenas/snapshots: ${e.message}")
        }
        for (a in arenas.values) a.problem?.let { plugin.logger.warning("[Ranked] Arena ${a.arena.id} is unreadable: $it") }
        plugin.logger.info("[Ranked] ${arenas.size} arena(s), ${pendingRestore.size} inventory snapshot(s) waiting for their players.")
        // Players already online (plugman / reload) who still have a snapshot waiting.
        for (player in Bukkit.getOnlinePlayers()) if (!holds(player)) restoreIfPending(player, "startup")
    }

    /** No match survives a restart: inventories first, never resume a fight from a crash. */
    private fun recover() {
        val recovered = try {
            store.recoverInterrupted(System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Could not recover interrupted matches: ${e.message}")
            return
        }
        for (r in recovered) {
            plugin.logger.warning("[Ranked] Match ${r.matchId} was interrupted (${r.from}); recorded as ${r.to}. Its players' inventories are restored when they're next online.")
        }
    }

    /** Shutdown: cancel every match and restore everyone online now (synchronously, before the database closes). */
    fun stop() {
        for (m in matches.values.toList()) {
            if (m.state == MatchState.FINISHING) complete(m, shutdown = true)
            else cancel(m, "server shutdown", shutdown = true)
        }
        matches.clear()
        held.clear()
        spectating.clear()
        spectateRequests.clear()
        expectedTeleports.clear()
    }

    // ── Match creation ──────────────────────────────────────

    sealed interface CreateResult {
        data class Started(val match: Match) : CreateResult
        data class Refused(val problems: List<String>) : CreateResult
    }

    /**
     * Issue #1491 (F-3): the only way a staff TEST match is created, and only by
     * [com.liam.joshymc.lifesteal.ranked.queue.RankedTestMatchService] after the staff member
     * confirmed [plan] and every one of its players accepted proposal [proposalId] themselves. Exactly
     * those players, on those sides: the same validation, snapshot and preparation as every other
     * match (a player who went offline, changed team or became ineligible refuses it). Hearts move
     * under the normal heart rules; TEST matches are never rated.
     */
    fun createTestMatch(actor: String, actorName: String, plan: TestMatchPlan, proposalId: String): CreateResult {
        arenaUnavailable()?.let { return CreateResult.Refused(listOf(it)) }
        val teams = plugin.lifestealTeamManager
        val teamA = teams.team(plan.teamAId)
        val teamB = teams.team(plan.teamBId)
        return startMatch(
            actor, "$actorName (staff test $proposalId)", teamA, teamA?.name ?: plan.teamAName, teamB, teamB?.name ?: plan.teamBName,
            plan.size, plan.arenaId, MatchKind.TEST, proposalId, plan.sideA, plan.sideB,
        )
    }

    /**
     * The ranked queue's way in (issue #1363): a match between the current Lifesteal teams
     * [teamAId] and [teamBId] with exactly the players who accepted ([sideA], [sideB]) in [arenaId].
     * Runs the very same validation, snapshot and preparation as every other match; the queue never
     * touches inventories, positions or hearts itself.
     */
    fun createQueuedMatch(teamAId: String, teamBId: String, size: Int, arenaId: String, kind: MatchKind, sideA: List<UUID>, sideB: List<UUID>, reference: String): CreateResult {
        require(kind != MatchKind.TEST) { "queued matches are PUBLIC or CHALLENGE" }
        arenaUnavailable()?.let { return CreateResult.Refused(listOf(it)) }
        val teams = plugin.lifestealTeamManager
        val teamA = teams.team(teamAId)
        val teamB = teams.team(teamBId)
        return startMatch("queue:$reference", "matchmaking ($reference)", teamA, teamA?.name ?: teamAId, teamB, teamB?.name ?: teamBId, size, arenaId, kind, reference, sideA, sideB)
    }

    private fun startMatch(
        createdBy: String, actorName: String,
        teamA: LifestealTeamStore.Team?, teamAName: String, teamB: LifestealTeamStore.Team?, teamBName: String,
        size: Int, arenaId: String?, kind: MatchKind, queueRef: String?, sideA: List<UUID>, sideB: List<UUID>,
    ): CreateResult {
        val settings = settings ?: return CreateResult.Refused(listOf("lifesteal.yml is invalid."))
        val teams = plugin.lifestealTeamManager
        val now = System.currentTimeMillis()

        val arenaChoice = chooseArena(arenaId, size)
        val arena = arenaChoice.first
        fun pick(uuids: List<UUID>, team: LifestealTeamStore.Team?, side: Side): List<RankedMatchRules.ParticipantFacts> {
            if (team == null) return emptyList()
            return uuids.mapNotNull { Bukkit.getPlayer(it)?.takeIf { p -> p.isOnline } }.map { facts(it, side, team) }
        }
        val participants = pick(sideA, teamA, Side.A) + pick(sideB, teamB, Side.B)
        val heartLimits = plugin.lifestealHeartManager.limits
        val request = RankedMatchRules.Request(
            teamA = teamFacts(teamA, teamAName),
            teamB = teamFacts(teamB, teamBName),
            size = size,
            allowedSizes = settings.teamSizes,
            participants = participants,
            heartLimits = heartLimits?.let { it.minimum..it.maximum },
            rankedUnlocked = rankedUnlocked(now),
            teamsAvailable = teams.active,
            heartsAvailable = plugin.lifestealHeartManager.active,
            arenaProblem = arenaChoice.second,
            busyTeams = listOfNotNull(teamA?.id, teamB?.id).filter { teamMatch(it) != null }.toSet(),
        )
        val problems = RankedMatchRules.validate(request).toMutableList()
        if (problems.isEmpty() && arena != null) outsidersIn(arena, participants.map { it.uuid }.toSet())?.let { problems += it }
        if (problems.isNotEmpty() || arena == null || teamA == null || teamB == null) {
            return CreateResult.Refused(problems.ifEmpty { listOf("The match couldn't be validated.") })
        }

        val id = RankedMatchRules.newMatchId()
        val roster = MatchRoster(participants.map { MatchParticipant(it.uuid, it.side, if (it.side == Side.A) teamA.id else teamB.id) })
        val seasonId = plugin.lifestealProgressionManager.progress(now).season?.id ?: "none"
        val match = Match(id, arena, teamA, teamB, size, kind, seasonId, createdBy, now, roster)
        match.state = MatchState.VALIDATING

        // Reserve: from here until the snapshot is stored nothing else can run (same tick), and the
        // listener already protects these players.
        val players = roster.uuids.mapNotNull { Bukkit.getPlayer(it) }
        if (players.size != roster.uuids.size) return CreateResult.Refused(listOf("A participant went offline."))
        val snapshots = try {
            players.map { capture(it) }
        } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Couldn't snapshot the inventories for a match ($id): ${e.message}")
            return CreateResult.Refused(listOf("An inventory couldn't be saved (${e.message}); nothing changed."))
        }
        try {
            store.insertMatch(
                RankedStore.NewMatch(id, seasonId, arena.id, teamA.id, teamA.name, teamB.id, teamB.name, size, match.test, match.createdBy, now, kind, queueRef),
                roster.participants, snapshots,
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Ranked] Match $id couldn't be recorded, so it didn't start: ${e.message}")
            return CreateResult.Refused(listOf("The match couldn't be recorded (a player, a team or the arena may already be in another match): ${e.message}"))
        }
        match.state = MatchState.PREPARING
        matches[id] = match
        for (s in snapshots) {
            held[s.uuid] = id
            pendingRestore += s.uuid
            cachedSnapshots[s.uuid] = RankedStore.Snapshot(
                store.snapshotId(id, s.uuid), id, s.uuid, s.slots, s.heldSlot, s.returnWorld, s.returnPoint, "PENDING", now,
                xpLevel = s.xp?.level, xpProgress = s.xp?.progress?.toDouble(), xpTotal = s.xp?.total,
                effects = s.effects?.let { RankedPlayerState.encodeEffects(it) },
            )
        }
        plugin.logger.info("[Ranked] $actorName created match $id: ${match.label} in arena ${arena.id}.")
        prepare(match)
        return CreateResult.Started(match)
    }

    private fun teamFacts(team: LifestealTeamStore.Team?, name: String) = RankedMatchRules.TeamFacts(
        id = team?.id ?: "", name = team?.name ?: name, exists = team != null,
        onlineMembers = team?.members?.count { Bukkit.getPlayer(it.uuid)?.isOnline == true } ?: 0,
    )

    private fun facts(player: Player, side: Side, team: LifestealTeamStore.Team): RankedMatchRules.ParticipantFacts {
        val uuid = player.uniqueId
        val restricted = when {
            safe { plugin.modModeManager.isModMode(player) } -> "is in Mod Mode"
            safe { plugin.adminManager.isFrozen(uuid) } -> "is frozen"
            safe { plugin.tradeManager.isTrading(player) } -> "is trading"
            safe { plugin.eventManager.isInEventWorld(player) } -> "is in the event world"
            // Issue #1498: God Mode, Vanish, Creative/Spectator, Mod Mode, over-limit gear (the #1487 rule).
            else -> RankedIntegrity.eligibilityProblem(competitiveReasons(player))
        }
        return RankedMatchRules.ParticipantFacts(
            uuid = uuid,
            name = player.name,
            side = side,
            member = plugin.lifestealTeamManager.teamOf(uuid)?.id == team.id,
            online = player.isOnline,
            inLifesteal = isLifesteal(player.world),
            sessionLoaded = plugin.lifestealHeartManager.heartsOf(uuid) != null,
            hearts = plugin.lifestealHeartManager.heartsOf(uuid),
            inOtherMatch = holds(uuid) || isSpectator(uuid),
            pendingRestore = pendingRestore.contains(uuid),
            transferring = safe { plugin.transferManager.isTransferPending(uuid) },
            combatTagged = plugin.combatManager.isTagged(player),
            dead = player.isDead,
            survivalMode = player.gameMode == GameMode.SURVIVAL,
            restricted = restricted,
        )
    }

    /**
     * Issue #1498: [player]'s restricted competitive states right now, by the one #1487 rule
     * (game mode, `/god`, `/vanish`, Mod Mode, over-limit worn gear). If the tracker isn't running
     * the player counts as Mod Mode, so nobody gets in unchecked.
     */
    fun competitiveReasons(player: Player): Set<CompetitiveEligibility.Reason> = try {
        plugin.competitiveEligibility.currentReasons(player)
    } catch (_: Exception) {
        setOf(CompetitiveEligibility.Reason.MOD_MODE)
    }

    /** A check that throws (system not started) counts as true, so it refuses rather than lets through. */
    private inline fun safe(check: () -> Boolean): Boolean = try { check() } catch (_: Exception) { true }

    /** Unlocked by the season schedule (or the progression is disabled); unknown state = locked. */
    fun rankedUnlocked(now: Long = System.currentTimeMillis()): Boolean =
        plugin.lifestealProgressionManager.rankedStatus(now) != ProgressionRules.RankedStatus.LOCKED

    // ── For the ranked queue (issue #1363; memory only) ─────

    /**
     * Why [player] couldn't play for [team] right now (null = they could), by exactly the rules a
     * match start applies ([RankedMatchRules.participantProblem]): membership, online, Lifesteal
     * world, loaded hearts, combat tag, other match, waiting restore, transfer, death, game mode,
     * staff states.
     */
    fun participantProblem(player: Player, team: LifestealTeamStore.Team): String? {
        arenaUnavailable()?.let { return it }
        val hearts = plugin.lifestealHeartManager
        val limits = hearts.limits?.takeIf { hearts.active } ?: return "the heart system isn't running, so hearts couldn't be at stake"
        return RankedMatchRules.participantProblem(facts(player, Side.A, team), limits.minimum..limits.maximum)
    }

    /** The first AVAILABLE arena hosting [size] that isn't in [exclude] (arenas other proposals are waiting on). */
    fun availableArenaFor(size: Int, exclude: Set<String> = emptySet()): RankedArena? =
        arenas.values.firstOrNull { it.arena.id !in exclude && size in it.arena.sizes && availability(it) == ArenaAvailability.AVAILABLE }?.arena

    /** Some enabled, ready arena hosts [size] (it may be busy right now). */
    fun hostsSize(size: Int): Boolean =
        arenas.values.any { size in it.arena.sizes && availability(it) != ArenaAvailability.DISABLED }

    /** Arenas a running match holds right now (for the queue diagnostics). */
    fun heldArenaIds(): List<String> = matches.values.map { it.arena.id }

    /** The arena to use: [requested] if given, else the first AVAILABLE one that hosts [size]. */
    private fun chooseArena(requested: String?, size: Int): Pair<RankedArena?, String?> {
        if (requested != null) {
            val loaded = arena(requested) ?: return null to "No arena called '$requested'."
            return when (val a = availability(loaded)) {
                ArenaAvailability.AVAILABLE ->
                    if (size in loaded.arena.sizes) loaded.arena to null else null to "Arena '${loaded.arena.id}' doesn't host ${size}v$size."
                ArenaAvailability.DISABLED -> null to "Arena '${loaded.arena.id}' is disabled or not ready" + validation(loaded).problems.takeIf { it.isNotEmpty() }?.joinToString("; ", " (", ")").orEmpty() + "."
                else -> null to "Arena '${loaded.arena.id}' is ${a.label.lowercase()} (another match uses it)."
            }
        }
        val found = arenas.values.firstOrNull { availability(it) == ArenaAvailability.AVAILABLE && size in it.arena.sizes }
        return if (found != null) found.arena to null else null to "No available arena hosts ${size}v$size right now."
    }

    /** Refuses an arena that has other players standing in it (they'd be inside the fight). */
    private fun outsidersIn(arena: RankedArena, participants: Set<UUID>): String? {
        val world = Bukkit.getWorld(arena.world) ?: return "The arena world isn't loaded."
        val box = arena.bounds ?: return "The arena has no boundary."
        val inside = world.players.filter { it.uniqueId !in participants && box.contains(it.location.x, it.location.y, it.location.z) }
        return if (inside.isEmpty()) null else "Players are standing inside the arena: ${inside.joinToString { it.name }}."
    }

    // ── Snapshots ───────────────────────────────────────────

    /**
     * The player's whole inventory, exactly. The cursor and 2x2 crafting grid are emptied into the
     * inventory first (closing it), so nothing is outside the snapshot. Issue #1490: also their
     * experience and active potion effects (see [RankedPlayerState]).
     */
    private fun capture(player: Player): RankedStore.SnapshotData {
        // Always close: the server puts the cursor and 2x2 grid back into the inventory (anything that
        // doesn't fit drops at their feet, before the match), so the snapshot holds everything they own.
        player.closeInventory()
        val inventory = player.inventory
        val slots = inventory.contents.map { stack -> stack?.takeIf { !it.type.isAir && it.amount > 0 }?.let { Base64.getEncoder().encodeToString(it.serializeAsBytes()) } }
        val loc = player.location
        val xp = RankedPlayerState.Xp(player.level, player.exp, player.totalExperience)
        val effects = player.activePotionEffects.map { effectOf(it) }
        return RankedStore.SnapshotData(player.uniqueId, slots, inventory.heldItemSlot, loc.world?.name, Point(loc.x, loc.y, loc.z, loc.yaw, loc.pitch), xp, effects)
    }

    private fun effectOf(effect: PotionEffect) = CombatNpcRules.Effect(
        type = effect.type.key.toString(),
        amplifier = effect.amplifier,
        durationTicks = if (effect.isInfinite) CombatNpcRules.INFINITE_DURATION else effect.duration,
        ambient = effect.isAmbient,
        particles = effect.hasParticles(),
        icon = effect.hasIcon(),
    )

    /** The Paper effect for a stored one, or null when its type no longer exists (logged, never invented). */
    private fun potionOf(effect: CombatNpcRules.Effect): PotionEffect? {
        val type = NamespacedKey.fromString(effect.type)?.let { Registry.EFFECT.get(it) } ?: return null
        return PotionEffect(type, effect.durationTicks, effect.amplifier, effect.ambient, effect.particles, effect.icon)
    }

    private fun decode(snapshot: RankedStore.Snapshot): Array<ItemStack?> =
        snapshot.slots.map { it?.let { b64 -> ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)) } }.toTypedArray()

    /** Restores [player] from their PENDING snapshot (if any) and moves them back. */
    fun restoreIfPending(player: Player, reason: String, moveBack: Boolean = true): Boolean {
        if (!pendingRestore.contains(player.uniqueId) || holds(player) || player.isDead) return false
        val snapshot = cachedSnapshots[player.uniqueId]?.takeIf { it.status == "PENDING" } ?: try {
            store.pendingSnapshot(player.uniqueId)
        } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Couldn't read ${player.name}'s ranked inventory snapshot: ${e.message}. It stays pending.")
            return false
        }
        if (snapshot == null) {
            pendingRestore.remove(player.uniqueId)
            cachedSnapshots.remove(player.uniqueId)
            return false
        }
        return restore(player, snapshot, reason, moveBack)
    }

    /**
     * Sets the inventory, XP and potion effects back to [snapshot], saves the player, then marks it
     * RESTORED. Applying it twice gives the same result (it is a set, not an add; effects count down
     * from the snapshot time, so a second apply never extends them), so a crash between the save
     * and the mark is harmless; if the snapshot can't be read nothing is changed and it stays PENDING.
     */
    private fun restore(player: Player, snapshot: RankedStore.Snapshot, reason: String, moveBack: Boolean): Boolean {
        val uuid = player.uniqueId
        if (!restoring.add(uuid)) return false
        try {
            val now = System.currentTimeMillis()
            // Everything is read and checked before the player is touched (issue #1490).
            val (contents, state) = try {
                decode(snapshot) to RankedPlayerState.plan(snapshot.xpLevel, snapshot.xpProgress, snapshot.xpTotal, snapshot.effects, snapshot.createdAt, now)
            } catch (e: Exception) {
                plugin.logger.severe("[Ranked] ${player.name}'s snapshot ${snapshot.id} can't be read (${e.message}); nothing was changed and it stays pending. Staff: /lifesteal admin ranked restore ${player.name} after fixing it.")
                tell(player, "Your ranked inventory couldn't be restored yet; staff have been notified. Nothing was lost.", NamedTextColor.RED)
                return false
            }
            // Nothing outside the inventory: cursor and crafting grid are match items, discarded.
            player.setItemOnCursor(null)
            val top = player.openInventory.topInventory
            if (top.type == InventoryType.CRAFTING) top.clear()
            player.closeInventory()
            val inventory = player.inventory
            if (contents.size == inventory.contents.size) {
                inventory.contents = contents
            } else {
                // Defensive: a different inventory layout. Same slots where they exist, never fewer items.
                inventory.clear()
                inventory.setArmorContents(arrayOfNulls(4))
                inventory.setItemInOffHand(null)
                for ((i, stack) in contents.withIndex()) if (stack != null) inventory.setItem(i, stack)
            }
            if (snapshot.heldSlot in 0..8) inventory.heldItemSlot = snapshot.heldSlot
            player.updateInventory()
            applyState(player, snapshot, state)
            try { player.saveData() } catch (e: Exception) { plugin.logger.warning("[Ranked] Couldn't save ${player.name}'s data right after restoring: ${e.message}") }
            val marked = try {
                store.markRestored(snapshot.id, reason, System.currentTimeMillis())
            } catch (e: Exception) {
                plugin.logger.severe("[Ranked] Restored ${player.name}'s inventory but couldn't mark snapshot ${snapshot.id} restored: ${e.message}. It will be applied again on their next join (same items, nothing duplicated unless they change their inventory meanwhile).")
                false
            }
            // Only forget it once the database agrees it's no longer pending (else the next join retries).
            val settled = marked || try { store.snapshot(snapshot.id)?.status != "PENDING" } catch (_: Exception) { false }
            if (settled) {
                pendingRestore.remove(uuid)
                cachedSnapshots.remove(uuid)
            }
            if (moveBack) moveToReturn(player, snapshot)
            val what = if (state.xp != null) "inventory, equipment, XP and effects were" else "inventory and equipment were"
            tell(player, "Your $what restored to how they were before the ranked match.", NamedTextColor.GREEN)
            return true
        } finally {
            restoring.remove(uuid)
        }
    }

    /**
     * Issue #1490: XP and effects back to the pre-match state, as a set. Older snapshots (no stored
     * state) leave that part untouched. Every current effect is removed first, so nothing gained or
     * strengthened in the match (potions, golden apples, Ominous Bottles, beacons, ...) survives.
     */
    private fun applyState(player: Player, snapshot: RankedStore.Snapshot, state: RankedPlayerState.Plan) {
        state.xp?.let { xp ->
            player.level = xp.level
            player.exp = xp.progress
            player.totalExperience = xp.total
        }
        val effects = state.effects ?: return
        for (active in player.activePotionEffects) player.removePotionEffect(active.type)
        val restored = effects.mapNotNull { e ->
            potionOf(e) ?: run {
                plugin.logger.warning("[Ranked] ${player.name}'s snapshot ${snapshot.id} has an unknown effect '${e.type}'; it was skipped.")
                null
            }
        }
        if (restored.isNotEmpty()) player.addPotionEffects(restored)
    }

    /** The authoritative return spot of [snapshot], or null when its world is gone. */
    fun returnLocation(snapshot: RankedStore.Snapshot): Location? {
        val world = snapshot.returnWorld?.let { Bukkit.getWorld(it) } ?: return null
        val p = snapshot.returnPoint ?: return null
        return Location(world, p.x, p.y, p.z, p.yaw, p.pitch)
    }

    fun pendingReturnLocation(uuid: UUID): Location? {
        val snapshot = cachedSnapshots[uuid] ?: try { store.pendingSnapshot(uuid) } catch (_: Exception) { null } ?: return null
        return returnLocation(snapshot)
    }

    /** A safe place in a Lifesteal world: the return spot, else the Lifesteal world's spawn. */
    private fun fallbackLocation(): Location? {
        val world = Bukkit.getWorlds().firstOrNull { isLifesteal(it) && it.environment == World.Environment.NORMAL } ?: Bukkit.getWorlds().firstOrNull()
        return world?.spawnLocation
    }

    private fun moveToReturn(player: Player, snapshot: RankedStore.Snapshot) {
        val target = returnLocation(snapshot) ?: fallbackLocation() ?: return
        teleport(player, target).thenAccept { ok ->
            if (ok == true) {
                if (target.isChunkLoaded && SafeSpots.problem(player.location) != null) {
                    // The return spot changed meanwhile (lava, blocks): go to spawn instead.
                    fallbackLocation()?.let { teleport(player, it) }
                }
            } else {
                fallbackLocation()?.let { teleport(player, it) }
            }
        }
    }

    /** A teleport this system makes (lets it past the listener's own teleport rules). */
    private fun teleport(player: Player, target: Location): CompletableFuture<Boolean> {
        val uuid = player.uniqueId
        // A rider can't be teleported; get them off first.
        if (player.isInsideVehicle) player.leaveVehicle()
        expectedTeleports[uuid] = target.clone()
        return player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN).handle { ok, error ->
            if (expectedTeleports[uuid] == target) expectedTeleports.remove(uuid)
            if (error != null) plugin.logger.warning("[Ranked] Teleporting ${player.name} failed: ${error.message}")
            error == null && ok == true
        }
    }

    // ── Preparation ─────────────────────────────────────────

    private fun prepare(match: Match) {
        val world = Bukkit.getWorld(match.arena.world)
        if (world == null) return cancel(match, "the arena world isn't loaded")
        val targets = LinkedHashMap<UUID, Location>()
        for (side in Side.entries) {
            for ((i, p) in match.roster.side(side).withIndex()) {
                val point = match.arena.spawnFor(side, i) ?: return cancel(match, "${side.label} has no spawn")
                targets[p.uuid] = Location(world, point.x, point.y, point.z, point.yaw, point.pitch)
            }
        }
        for (uuid in targets.keys) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (player.allowFlight && player.gameMode == GameMode.SURVIVAL) {
                player.isFlying = false
                player.allowFlight = false
                match.flightTaken += uuid
            }
            if (player.isGliding) player.isGliding = false
            plugin.commsManager.send(player, Component.text("[Ranked] ", NamedTextColor.GOLD).append(Component.text("${match.label}: preparing the arena...", NamedTextColor.YELLOW)))
        }
        match.track(plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (match.state == MatchState.PREPARING) cancel(match, "preparation timed out")
        }, PREPARATION_TIMEOUT_TICKS))
        val chunks = targets.values.map { it.blockX shr 4 to (it.blockZ shr 4) }.distinct()
        val loads = chunks.map { (cx, cz) -> world.getChunkAtAsync(cx, cz, false) }
        CompletableFuture.allOf(*loads.toTypedArray()).whenComplete { _, error ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (match.state != MatchState.PREPARING) return@Runnable
                if (error != null || loads.any { it.getNow(null) == null }) return@Runnable cancel(match, "the arena chunks couldn't be loaded")
                val unsafe = targets.values.mapNotNull { loc -> SafeSpots.problem(loc)?.let { "(${loc.blockX}, ${loc.blockY}, ${loc.blockZ}) $it" } }
                if (unsafe.isNotEmpty()) return@Runnable cancel(match, "unsafe spawn ${unsafe.first()}")
                teleportIn(match, targets)
            })
        }
    }

    private fun teleportIn(match: Match, targets: Map<UUID, Location>) {
        val futures = targets.map { (uuid, loc) ->
            val player = Bukkit.getPlayer(uuid)
            if (player == null || !player.isOnline || held[uuid] != match.id) CompletableFuture.completedFuture(false)
            else teleport(player, loc)
        }
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (match.state != MatchState.PREPARING) return@Runnable
                if (futures.any { it.getNow(false) != true }) return@Runnable cancel(match, "a participant couldn't be moved into the arena")
                if (!checkIntegrity(match, "preparation")) return@Runnable
                startCountdown(match)
            })
        }
    }

    private fun move(match: Match, to: MatchState): Boolean {
        val from = match.state
        if (!from.canMoveTo(to)) {
            plugin.logger.warning("[Ranked] Refused invalid transition $from -> $to for match ${match.id}.")
            return false
        }
        val ok = try {
            store.moveState(match.id, from, to, System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Couldn't record $from -> $to for match ${match.id}: ${e.message}")
            false
        }
        if (!ok) return false
        match.state = to
        if (to == MatchState.ACTIVE) match.startedAt = System.currentTimeMillis()
        return true
    }

    private fun startCountdown(match: Match) {
        if (!move(match, MatchState.COUNTDOWN)) return cancel(match, "the countdown couldn't be recorded")
        var left = settings?.countdownSeconds ?: 10
        match.track(plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (match.state != MatchState.COUNTDOWN) return@Runnable
            if (!checkIntegrity(match, "countdown")) return@Runnable
            if (left <= 0) {
                begin(match)
                return@Runnable
            }
            title(match, Component.text(left.toString(), NamedTextColor.GOLD), Component.text(match.label, NamedTextColor.GRAY))
            left--
        }, 0L, 20L))
    }

    private fun begin(match: Match) {
        // Issue #1498: the last check before anything is at stake.
        if (!checkIntegrity(match, "activation")) return
        if (!move(match, MatchState.ACTIVE)) return cancel(match, "the match start couldn't be recorded")
        title(match, Component.text("FIGHT!", NamedTextColor.RED), Component.text("Hearts are at stake. Your gear comes back afterwards.", NamedTextColor.GRAY))
        val minutes = settings?.maxMatchMinutes ?: 20
        match.track(plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (match.state == MatchState.ACTIVE) cancel(match, "time limit ($minutes min) reached without a winner")
        }, minutes * 60L * 20L))
        // Issue #1498: backstop for restricted states that weren't blocked before they happened.
        match.track(plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (match.state == MatchState.ACTIVE) checkIntegrity(match, "fight")
        }, INTEGRITY_CHECK_TICKS, INTEGRITY_CHECK_TICKS))
        // A participant who left between the last teleport and now is decided at once.
        scheduleDecision(match)
    }

    private fun title(match: Match, main: Component, sub: Component) {
        val times = Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(200))
        for (uuid in match.roster.uuids + match.spectators) {
            Bukkit.getPlayer(uuid)?.showTitle(Title.title(main, sub, times))
        }
    }

    // ── During the match ────────────────────────────────────

    /**
     * A participant stopped fighting. The roster changes now; the record is queued (no database
     * work during combat); who won is decided on the next tick, so deaths in the same tick count
     * together.
     */
    fun defeat(match: Match, uuid: UUID, reason: DefeatReason) {
        val next = match.roster.defeat(uuid, reason) ?: return
        match.roster = next
        next.of(uuid)?.let { match.outsSinceDecision += it }
        match.recentHits.remove(uuid)
        held.remove(uuid)
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("ranked defeat ${match.id}") { store.defeat(match.id, uuid, reason, now) }
        val name = Bukkit.getPlayer(uuid)?.name ?: plugin.lifestealTeamManager.nameOf(uuid)
        broadcast(match, Component.text("$name is out (${reason.label}).", NamedTextColor.GRAY))
        scheduleDecision(match)
    }

    private fun scheduleDecision(match: Match) {
        if (match.state != MatchState.ACTIVE || match.decisionPending) return
        match.decisionPending = true
        match.track(plugin.server.scheduler.runTask(plugin, Runnable {
            match.decisionPending = false
            val outs = match.outsSinceDecision.toList()
            match.outsSinceDecision.clear()
            if (match.state != MatchState.ACTIVE) return@Runnable
            when (val d = RankedMatchRules.decide(match.roster, outs)) {
                RankedMatchRules.Decision.Continue -> Unit
                RankedMatchRules.Decision.NoWinner -> cancel(match, "both teams were out at the same time (no reliable winner)")
                is RankedMatchRules.Decision.Winner -> win(match, d.side)
            }
        }))
    }

    private fun win(match: Match, side: Side) {
        val team = match.team(side)
        // Issue #1498: the winners still fighting are checked once more; a restricted state now
        // keeps the result but makes it unrated (they aren't taken out: that would change the winner).
        for (p in match.roster.active(side)) {
            val player = Bukkit.getPlayer(p.uuid)?.takeIf { it.isOnline } ?: continue
            val reasons = competitiveReasons(player)
            if (reasons.isEmpty()) continue
            match.ineligibleSides += side
            auditIntegrity(match, player.uniqueId, player.name, "RANKED_INELIGIBLE_AT_RESULT", "stage=result reasons=${codeOf(reasons)}")
        }
        val suppressed = side in match.ineligibleSides
        val ok = try {
            store.finish(match.id, side, team.id, System.currentTimeMillis(), match.ineligibleSides.toSet())
        } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Couldn't record the winner of ${match.id}: ${e.message}")
            false
        }
        if (!ok) return cancel(match, "the result couldn't be recorded")
        match.state = MatchState.FINISHING
        broadcast(match, Component.text("${team.name} won the ranked match!", NamedTextColor.GOLD))
        heartSummary(match)
        if (suppressed) {
            broadcast(match, Component.text("This result is unrated: a player on ${team.name} used a restricted ability during the match.", NamedTextColor.RED))
            auditIntegrity(match, null, team.name, "RANKED_RESULT_SUPPRESSED", "winner=${side.name} team=${team.id} ineligible_sides=${RankedIntegrity.encodeSides(match.ineligibleSides)}")
        }
        plugin.logger.info("[Ranked] Match ${match.id} (${match.label}) won by ${team.name}.")
        complete(match, shutdown = false)
        monitorEnded(match)
    }

    /** FINISHING → COMPLETED: cleanup, restore, record. */
    private fun complete(match: Match, shutdown: Boolean) {
        cleanup(match, shutdown)
        val ok = try { store.complete(match.id, System.currentTimeMillis()) } catch (e: Exception) {
            plugin.logger.severe("[Ranked] Couldn't record match ${match.id} as completed: ${e.message} (it is completed at the next start)."); false
        }
        match.state = MatchState.COMPLETED
        release(match)
        if (ok) plugin.logger.info("[Ranked] Match ${match.id} completed.")
        if (match.test) auditTestEnd(match, "RANKED_TESTMATCH_COMPLETED", "recorded=$ok")
        // Issue #1365: Elo is computed from the stored record, off the main thread. At shutdown (or if
        // the COMPLETED write failed) the rating sweep picks it up after the next start instead.
        if (ok && !shutdown) plugin.lifestealRatingManager.onMatchCompleted(match.id)
    }

    /** Cancels [match] (no winner): restores everyone online, records why. Never used once FINISHING. */
    fun cancel(match: Match, reason: String, shutdown: Boolean = false, leaving: UUID? = null) {
        if (match.state.terminal) return
        if (match.state == MatchState.FINISHING) return complete(match, shutdown)
        val persisted = match.state != MatchState.CREATED && match.state != MatchState.VALIDATING
        cleanup(match, shutdown, leaving)
        if (persisted) {
            try { store.cancel(match.id, reason, System.currentTimeMillis()) } catch (e: Exception) {
                plugin.logger.severe("[Ranked] Couldn't record match ${match.id} as cancelled: ${e.message} (it is cancelled at the next start).")
            }
        }
        val fought = match.startedAt != null
        match.state = MatchState.CANCELLED
        broadcast(match, Component.text("The ranked match was cancelled: $reason. No winner.", NamedTextColor.RED))
        // Issue #1474: hearts already stolen stay stolen, so a cancelled match still says which kills moved one.
        heartSummary(match)
        plugin.logger.warning("[Ranked] Match ${match.id} (${match.label}) cancelled: $reason.")
        release(match)
        if (match.test) auditTestEnd(match, "RANKED_TESTMATCH_MATCH_CANCELLED", "reason=$reason")
        if (fought && !shutdown) monitorEnded(match)
    }

    // ── Heart outcomes and monitoring (issue #1474) ─────────

    /**
     * Adds one heart decision to [matchId]'s summary. Called by the heart service with the result
     * `HeartStore.transferPvp` already returned (kills), and by [forfeitHeart] (forfeits); never
     * evaluates or changes anything itself. A match that is already over (or unknown) ignores it.
     * The decision is also queued to the match's event log for staff.
     */
    fun recordHeartOutcome(matchId: String, entry: RankedHeartSummary.Entry) {
        val match = matches[matchId] ?: return
        if (match.state.terminal) return
        val before = match.heartOutcomes
        match.heartOutcomes = RankedHeartSummary.add(before, entry)
        if (match.heartOutcomes === before) return
        val now = System.currentTimeMillis()
        val detail = "${entry.source.name} killer=${entry.killer} outcome=${entry.outcome.name}"
        plugin.databaseManager.async("ranked heart outcome ${match.id}") { store.logEvent(match.id, "HEART", entry.victim, detail, now) }
    }

    /** The heart section of the end-of-match message (to the match's players and spectators only). */
    private fun heartSummary(match: Match) {
        for (line in RankedHeartSummary.lines(match.heartOutcomes)) {
            broadcast(match, Component.text(line.text, if (line.moved) NamedTextColor.GREEN else NamedTextColor.GRAY))
        }
    }

    /**
     * Issue #1491: one `admin_logs` row when a staff TEST match ends, with the initiating staff
     * member, the proposal and every heart decision of the match (what the heart service already
     * decided; nothing is re-evaluated). Never throws.
     */
    private fun auditTestEnd(match: Match, action: String, extra: String) {
        try {
            val hearts = match.heartOutcomes.joinToString(",") { "${it.killer}>${it.victim}:${it.outcome.name}" }
            val moved = match.heartOutcomes.count { it.outcome.moved }
            val details = "match=${match.id} initiator=${match.createdBy} teams=${match.teamA.id},${match.teamB.id} size=${match.size}v${match.size} " +
                "players=${match.roster.uuids.joinToString(",")} $extra hearts_moved=$moved/${match.heartOutcomes.size} hearts=$hearts"
            plugin.adminManager.logAction(null, RankedFarmingMonitor.SYSTEM_ACTOR, action, null, null, details.take(500))
        } catch (e: Exception) {
            plugin.logger.warning("[Ranked] Couldn't write the staff log for test match ${match.id}: ${e.message}")
        }
    }

    /** Hands a match that reached the fight to the logging-only monitor (after its result is recorded). */
    private fun monitorEnded(match: Match) {
        try {
            val roster = match.roster.participants
            val facts = RankedMonitorRules.MatchFacts(
                matchId = match.id,
                teamAId = match.teamA.id, teamAName = match.teamA.name,
                teamBId = match.teamB.id, teamBName = match.teamB.name,
                sideA = roster.filter { it.side == Side.A }.map { it.uuid },
                sideB = roster.filter { it.side == Side.B }.map { it.uuid },
                forfeited = roster.filter { it.defeatReason == DefeatReason.DISCONNECT }.map { it.uuid },
                transfers = match.heartOutcomes.filter { it.outcome.moved }.map { it.killer to it.victim },
                // Issue #1491: staff TEST matches are monitored too (counted against other TEST matches), with who started them.
                test = match.test,
                initiator = if (match.test) initiatorLabel(match.createdBy) else null,
            )
            val names = roster.associate { it.uuid to (Bukkit.getPlayer(it.uuid)?.name ?: plugin.lifestealTeamManager.nameOf(it.uuid)) }
            monitor.onMatchEnded(facts, names)
        } catch (e: Exception) {
            plugin.logger.warning("[Ranked] Couldn't queue the monitoring check of match ${match.id}: ${e.message}")
        }
    }

    /** Ends tasks and protections, restores every online participant and clears the arena's loose entities. */
    private fun cleanup(match: Match, shutdown: Boolean, leaving: UUID? = null) {
        match.cancelTasks()
        for (uuid in match.roster.uuids) held.remove(uuid)
        for (uuid in match.roster.uuids) {
            // A player who is disconnecting right now keeps their snapshot pending: restored (and
            // moved out of the arena) when they log back in, never mid-quit.
            if (uuid == leaving) continue
            val player = Bukkit.getPlayer(uuid) ?: continue
            if (!player.isOnline) continue
            if (plugin.combatManager.isTagged(player)) plugin.combatManager.untag(player)
            if (uuid in match.flightTaken && (player.hasPermission("joshymc.fly") || safe { plugin.flightTimeManager.hasTemporaryFlight(player) })) player.allowFlight = true
            if (player.isDead) continue // restored when they respawn
            if (shutdown) restoreForShutdown(player) else restoreIfPending(player, "match ${match.state.name.lowercase()}")
        }
        clearEntities(match)
    }

    /** Shutdown can't wait for an async teleport: set the inventory and move synchronously. */
    private fun restoreForShutdown(player: Player) {
        val snapshot = cachedSnapshots[player.uniqueId] ?: return
        if (restore(player, snapshot, "server shutdown", moveBack = false)) {
            (returnLocation(snapshot) ?: fallbackLocation())?.let {
                expectedTeleports[player.uniqueId] = it
                try { player.teleport(it, PlayerTeleportEvent.TeleportCause.PLUGIN) } finally { expectedTeleports.remove(player.uniqueId) }
            }
        }
    }

    /** Only loose match leftovers inside the boundary, in loaded chunks: items, arrows/tridents, crystals, XP. Never blocks. */
    private fun clearEntities(match: Match) {
        val world = Bukkit.getWorld(match.arena.world) ?: return
        val b = match.arena.bounds ?: return
        val box = BoundingBox(b.minX.toDouble(), b.minY.toDouble(), b.minZ.toDouble(), b.maxX + 1.0, b.maxY + 1.0, b.maxZ + 1.0)
        var removed = 0
        for (e in world.getNearbyEntities(box)) {
            if (e is Item || e is AbstractArrow || e is EnderCrystal || e is ExperienceOrb) {
                e.remove()
                removed++
            }
        }
        if (removed > 0) plugin.logger.info("[Ranked] Cleared $removed leftover entit${if (removed == 1) "y" else "ies"} from arena ${match.arena.id}.")
    }

    private fun release(match: Match) {
        match.cancelTasks()
        for (uuid in match.roster.uuids) {
            held.remove(uuid)
            expectedTeleports.remove(uuid)
        }
        for (uuid in match.spectators) {
            spectating.remove(uuid)
            Bukkit.getPlayer(uuid)?.let { tell(it, "The match you were watching is over.", NamedTextColor.GRAY) }
        }
        match.spectators.clear()
        matches.remove(match.id)
    }

    private fun broadcast(match: Match, message: Component) {
        val prefix = Component.text("[Ranked] ", NamedTextColor.GOLD)
        for (uuid in match.roster.uuids + match.spectators) {
            Bukkit.getPlayer(uuid)?.let { plugin.commsManager.send(it, prefix.append(message)) }
        }
    }

    private fun tell(player: Player, text: String, color: NamedTextColor) {
        plugin.commsManager.send(player, Component.text("[Ranked] ", NamedTextColor.GOLD).append(Component.text(text, color)))
    }

    // ── Player events (from the listener) ───────────────────

    fun onJoin(player: Player) {
        if (!hosted) return
        // They logged in where they left (usually inside an arena). Dead players are restored at respawn.
        if (pendingRestore.contains(player.uniqueId)) restoreIfPending(player, "rejoined")
        forfeitNotices.remove(player.uniqueId)?.let { lines -> for (line in lines) tell(player, line, NamedTextColor.YELLOW) }
    }

    // ── Disconnects (issue #1465) ───────────────────────────

    /** What a forfeit did, told to the leaver when they're back (memory only; a restart drops it). */
    private val forfeitNotices = ConcurrentHashMap<UUID, List<String>>()

    /**
     * An opponent's damage on [victim] during the fight (MONITOR, not cancelled, so only hits that
     * landed). Only real match fights between opposite roster sides are recorded: these are the
     * only candidates for a disconnect forfeit's heart.
     */
    fun recordHit(attacker: Player, victim: Player) {
        val match = matchOf(victim.uniqueId) ?: return
        if (match.state != MatchState.ACTIVE || !isMatchFight(attacker, victim)) return
        if (match.roster.sameSide(attacker.uniqueId, victim.uniqueId) != false) return
        val hit = HeartRules.RecentHit(attacker.uniqueId, System.currentTimeMillis())
        match.recentHits[victim.uniqueId] = RankedMatchRules.recordHit(match.recentHits[victim.uniqueId].orEmpty(), hit)
    }

    /**
     * A participant or spectator is disconnecting. [quitReason] / [kickCause] are Paper enum names
     * (the kick cause from the PlayerKickEvent just before, if any). What happens is decided only by
     * [RankedMatchRules.disconnect]: before the fight the match is cancelled; in the fight a counted
     * disconnect is a forfeit (out of the match, their side loses if nobody is left, Elo by the
     * normal rating of the finished match) and may cost one heart; staff kicks and server errors
     * only take them out; a shutdown is left to [stop]. Their inventory comes back on rejoin.
     */
    fun onQuit(player: Player, quitReason: String? = null, kickCause: String? = null) {
        val uuid = player.uniqueId
        spectating.remove(uuid)?.let { matches[it]?.spectators?.remove(uuid) }
        spectateRequests.remove(uuid)
        val match = matchOf(uuid) ?: return
        val now = System.currentTimeMillis()
        val transferPending = runCatching { plugin.transferManager.isTransferPending(uuid) }.getOrDefault(false)
        val kind = CombatLogRules.classify(quitReason, kickCause, Bukkit.isStopping(), transferPending)
        val lifesteal = plugin.lifestealConfigManager.settings
        val policy = RankedMatchRules.disconnect(
            match.state, match.roster.isActive(uuid), kind,
            countTimeouts = lifesteal?.combatLogging?.countTimeouts ?: true,
            heartLoss = (settings ?: lifesteal?.rankedTeamWars)?.disconnectHeartLoss ?: RankedForfeitHeartLoss.NONE,
        )
        val detail = match.state.name + " " + kind.name + " " + policy.action.name
        plugin.databaseManager.async("ranked disconnect ${match.id}") { store.logEvent(match.id, "DISCONNECT", uuid, detail, now) }
        when (policy.action) {
            // Before the fight: the match can't start fair, cancel it. Their snapshot waits for their return.
            RankedMatchRules.DisconnectAction.CANCEL_MATCH -> {
                held.remove(uuid)
                cancel(match, "${player.name} disconnected before the start", leaving = uuid)
            }
            RankedMatchRules.DisconnectAction.FORFEIT -> {
                // Pick the recipient before the defeat drops this player's recorded hits.
                val recipient = if (policy.heartForfeit) forfeitRecipient(match, uuid, now) else null
                defeat(match, uuid, DefeatReason.DISCONNECT)
                val lines = mutableListOf("You disconnected during a ranked match (${match.label}), so you forfeited it.")
                if (policy.heartForfeit) lines += forfeitHeart(match, player, recipient, kind)
                forfeitNotices[uuid] = lines
            }
            RankedMatchRules.DisconnectAction.LEAVE_EXEMPT -> {
                defeat(match, uuid, DefeatReason.DISCONNECT_EXEMPT)
                forfeitNotices[uuid] = listOf("You left a ranked match (${kind.label}); no heart was taken.")
            }
            // Shutdown cancels every match and restores everyone (stop()); nothing is decided here.
            RankedMatchRules.DisconnectAction.SHUTDOWN -> Unit
            RankedMatchRules.DisconnectAction.NONE -> held.remove(uuid)
        }
    }

    /** Most recent opposing hitter who is still fighting in this match and online, or null. */
    private fun forfeitRecipient(match: Match, victim: UUID, now: Long): Player? {
        val hits = match.recentHits[victim].orEmpty()
        val chosen = RankedMatchRules.forfeitRecipient(victim, hits, now, plugin.combatManager.tagDurationMillis) { attacker ->
            val p = Bukkit.getPlayer(attacker)
            p != null && p.isOnline && !p.isDead && held[attacker] == match.id &&
                match.roster.isActive(attacker) && match.roster.sameSide(attacker, victim) == false
        }
        return chosen?.let { Bukkit.getPlayer(it) }
    }

    /** The heart part of a forfeit, through the normal heart service. Returns the line for the leaver. */
    private fun forfeitHeart(match: Match, victim: Player, recipient: Player?, kind: CombatLogRules.DisconnectKind): String {
        if (recipient == null) {
            plugin.logger.info("[Ranked] ${victim.name} forfeited match ${match.id} (${kind.label}); no opponent had hit them recently, so no heart moved.")
            return "No opponent had hit you recently, so no heart was taken."
        }
        val result = plugin.lifestealHeartManager.onRankedForfeit(
            RankedMatchRules.forfeitDedupeKey(match.id, victim.uniqueId), victim, recipient, RankedMatchRules.forfeitNote(match.id, kind),
        )
        val done = result as? HeartStore.PvpResult.Done
        plugin.logger.info("[Ranked] ${victim.name} forfeited match ${match.id} (${kind.label}); heart forfeit to ${recipient.name}: ${done?.decision ?: result ?: "not evaluated"}.")
        // Issue #1474: the stored decision, for the end-of-match summary (a Duplicate was already reported).
        if (result != HeartStore.PvpResult.Duplicate) {
            val outcome = done?.let { RankedHeartSummary.Outcome.of(it.decision) } ?: RankedHeartSummary.Outcome.UNAVAILABLE
            recordHeartOutcome(match.id, RankedHeartSummary.Entry(recipient.uniqueId, recipient.name, victim.uniqueId, victim.name, outcome, RankedHeartSummary.Source.FORFEIT))
        }
        broadcast(match, Component.text("${victim.name} disconnected and forfeited (last hit by ${recipient.name}).", NamedTextColor.GRAY))
        if (done == null) return "No heart was taken."
        return when (done.decision) {
            is HeartRules.Decision.Transfer, is HeartRules.Decision.VictimLosesOnly ->
                "You lost a heart to ${recipient.name}, who hit you last (you have ${done.victimAfter} now)."
            HeartRules.Decision.VictimAtMinimum -> "You were at your last heart, so you kept it."
            is HeartRules.Decision.Cooldown -> "No heart was taken: ${recipient.name} took one from you recently."
            HeartRules.Decision.KillerAtMaximum -> "No heart was taken: ${recipient.name} is at the maximum."
            else -> "No heart was taken."
        }
    }

    /**
     * Death of a held participant: nothing drops (their real gear comes back from the snapshot),
     * no XP orbs, and they are out of the match. Hearts are handled by the normal heart service.
     */
    fun onDeath(player: Player): Boolean {
        val match = matchOf(player.uniqueId) ?: return false
        if (match.state == MatchState.ACTIVE) defeat(match, player.uniqueId, DefeatReason.DEATH)
        else {
            held.remove(player.uniqueId)
            cancel(match, "${player.name} died before the fight started")
        }
        return true
    }

    // ── Match integrity (issue #1498) ───────────────────────

    private val integrityThrottle = CompetitiveEligibility.Throttle()

    private fun codeOf(reasons: Set<CompetitiveEligibility.Reason>): String = reasons.sortedBy { it.ordinal }.joinToString(",") { it.id }

    /**
     * Checks every participant [match] still holds. Flight is taken away again (and logged); a
     * restricted competitive state is resolved by [RankedIntegrity.resolve]: before the fight the
     * match is cancelled unrated, in the fight the player is out as a forfeit and their side's
     * win can't be rated. Returns false when the match is no longer running afterwards.
     */
    fun checkIntegrity(match: Match, stage: String): Boolean {
        if (match.state.terminal) return false
        for (p in match.roster.participants) {
            if (held[p.uuid] != match.id) continue
            val player = Bukkit.getPlayer(p.uuid)?.takeIf { it.isOnline } ?: continue
            if (player.allowFlight && player.gameMode == GameMode.SURVIVAL) {
                player.isFlying = false
                player.allowFlight = false
                auditIntegrity(match, player.uniqueId, player.name, "RANKED_RESTRICTED_ABILITY_BLOCKED", "ability=flight stage=$stage")
            }
            val reasons = competitiveReasons(player)
            if (reasons.isEmpty()) continue
            val problem = RankedIntegrity.eligibilityProblem(reasons) ?: "used a restricted ability"
            when (RankedIntegrity.resolve(match.state, match.roster.isActive(p.uuid))) {
                RankedIntegrity.Resolution.NONE -> Unit
                RankedIntegrity.Resolution.CANCEL_MATCH -> {
                    auditIntegrity(match, player.uniqueId, player.name, "RANKED_MATCH_INVALIDATED", "stage=$stage reasons=${codeOf(reasons)} action=cancel")
                    cancel(match, "${player.name} $problem before the fight")
                    return false
                }
                RankedIntegrity.Resolution.FORFEIT -> {
                    match.ineligibleSides += p.side
                    auditIntegrity(match, player.uniqueId, player.name, "RANKED_MATCH_INVALIDATED", "stage=$stage reasons=${codeOf(reasons)} action=forfeit side=${p.side.name}")
                    tell(player, "You're out of the ranked match: you $problem. Your team's win can't be rated.", NamedTextColor.RED)
                    defeat(match, p.uuid, DefeatReason.INELIGIBLE)
                    // Out like a staff removal: the snapshot comes back now and they leave the arena (no heart moves).
                    if (!player.isDead) restoreIfPending(player, "out of the match (restricted ability)")
                }
            }
        }
        return !match.state.terminal
    }

    /** Issue #1498: a held participant's attempt to switch on a restricted [ability] was refused before it happened. */
    fun logBlockedAbility(player: Player, ability: String, cause: String) {
        val match = matchOf(player.uniqueId) ?: return
        auditIntegrity(match, player.uniqueId, player.name, "RANKED_RESTRICTED_ABILITY_BLOCKED", "ability=$ability cause=$cause state=${match.state.name}")
    }

    /** Issue #1498 (F-5): a command that would have run as held [player] through /sudo, an NPC or a portal was refused. */
    fun logBlockedDelegated(player: Player, source: String, command: String) {
        val match = matchOf(player.uniqueId) ?: return
        auditIntegrity(match, player.uniqueId, player.name, "RANKED_DELEGATED_COMMAND_BLOCKED", "source=$source command=/${command.take(120)}")
    }

    /** One `admin_logs` row (AdminAudit's table) + console line, at most once per match + player + action per minute. */
    private fun auditIntegrity(match: Match, uuid: UUID?, name: String, action: String, details: String) {
        if (!integrityThrottle.allow(RankedIntegrity.throttleKey(match.id, uuid?.toString() ?: name, action), System.currentTimeMillis())) return
        val full = "match=${match.id} kind=${match.kind.name} $details".take(500)
        try {
            plugin.adminManager.logAction(null, RankedFarmingMonitor.SYSTEM_ACTOR, action, uuid, name, full)
        } catch (e: Exception) {
            plugin.logger.warning("[Ranked] Couldn't write the staff log ($action) for ${match.id}: ${e.message}")
        }
        plugin.logger.warning("[Ranked] $action: $name | $full")
        if (uuid != null) plugin.databaseManager.async("ranked integrity ${match.id}") { store.logEvent(match.id, "INTEGRITY", uuid, "$action $details", System.currentTimeMillis()) }
    }

    /**
     * Issue #1498 (F-4): the server-side check every staff command that changes another player
     * (or the sender themselves) runs before it acts. True = refused: [sender] is told why and the
     * attempt is logged (AdminAudit, throttled per sender + target + action).
     */
    fun refusesStaffAction(sender: CommandSender, target: Player, action: RankedIntegrity.StaffAction): Boolean {
        val isHeld = holds(target)
        val pending = hasPendingRestore(target.uniqueId)
        if (!isHeld && !pending) return false
        val self = sender is Player && sender.uniqueId == target.uniqueId
        val refusal = RankedIntegrity.staffActionRefusal(action, target.name, isHeld, pending, self) ?: return false
        com.liam.joshymc.util.Msg.error(sender, refusal)
        val actor = (sender as? Player)?.uniqueId?.toString() ?: sender.name
        if (integrityThrottle.allow(RankedIntegrity.throttleKey(actor, target.uniqueId.toString(), action.id), System.currentTimeMillis())) {
            com.liam.joshymc.staff.AdminAudit.log(plugin, sender, "RANKED_STAFF_ACTION_BLOCKED", target.uniqueId, target.name,
                "action=${action.id} match=${held[target.uniqueId] ?: "none"} held=$isHeld pending_restore=$pending")
        }
        return true
    }

    /** Issue #1498: whether [sender] may cancel [match] or remove its players (null = may). Console always may. */
    fun interventionProblem(sender: CommandSender, match: Match): String? {
        val actor = sender as? Player ?: return null
        val onRoster = match.roster.of(actor.uniqueId) != null
        val team = try { plugin.lifestealTeamManager.teamOf(actor.uniqueId)?.id } catch (_: Exception) { null }
        val onTeam = team != null && (team == match.teamA.id || team == match.teamB.id)
        return RankedIntegrity.interventionRefusal(onRoster, onTeam, match.test)
    }

    // ── Admin recovery ──────────────────────────────────────

    /** Takes [target] out of their match now (restored and moved back), or cancels it before it starts. */
    fun removeParticipant(sender: CommandSender, target: Player): String? {
        val match = matchOf(target.uniqueId) ?: return "${target.name} isn't in a ranked match."
        plugin.logger.warning("[Ranked] ${sender.name} removed ${target.name} from match ${match.id}.")
        if (match.state == MatchState.ACTIVE) {
            defeat(match, target.uniqueId, DefeatReason.REMOVED)
            restoreIfPending(target, "removed by staff")
        } else {
            cancel(match, "staff removed ${target.name}")
        }
        return null
    }

    /** Restores a waiting snapshot for an online player who isn't in a match. */
    fun adminRestore(target: Player): String? {
        if (holds(target)) return "${target.name} is still in a match; use remove first."
        if (target.isDead) return "${target.name} is dead; they're restored when they respawn."
        if (!pendingRestore.contains(target.uniqueId)) {
            // Re-read in case the memory set is behind the database.
            val pending = try { store.pendingSnapshot(target.uniqueId) } catch (e: Exception) { return "Couldn't read the snapshot: ${e.message}" }
            if (pending == null) return "${target.name} has no ranked inventory waiting to be restored."
            pendingRestore += target.uniqueId
        }
        return if (restoreIfPending(target, "staff restore")) null else "The snapshot couldn't be restored; see the console."
    }

    /** Issue #1511: players whose spectate request is loading its destination (one at a time each). */
    private val spectateRequests = HashSet<UUID>()

    /** Fresh [RankedSpectateRules.Facts] for [player] watching [matchId] (main thread). */
    private fun spectateFacts(player: Player, matchId: String): RankedSpectateRules.Facts {
        val match = matches[matchId]
        return RankedSpectateRules.Facts(
            matchId = matchId,
            state = match?.state,
            arenaId = match?.arena?.id,
            participant = holds(player),
            pendingRestore = hasPendingRestore(player.uniqueId),
            dead = player.isDead,
            // The one combat rule every player-initiated teleport uses (issue #1356).
            teleportProblem = com.liam.joshymc.teleport.CombatTeleportGuard.problem(plugin, player),
            hasSpectatorPoint = match?.arena?.spectators?.isNotEmpty() == true,
            worldLoaded = match != null && Bukkit.getWorld(match.arena.world) != null,
        )
    }

    /**
     * Starts watching [matchId] (issue #1511, D-L7). Checked by [RankedSpectateRules] before the
     * spectator point's chunk loads and again right before the teleport; the player only becomes a
     * spectator once the teleport succeeded, so a refusal changes nothing. [onDone] gets null on
     * success, the reason otherwise (not called when the player left meanwhile).
     */
    fun spectate(staff: Player, matchId: String, onDone: (String?) -> Unit) {
        val uuid = staff.uniqueId
        RankedSpectateRules.problem(spectateFacts(staff, matchId))?.let { return onDone(it) }
        if (uuid in spectateRequests) return onDone(RankedSpectateRules.MSG_IN_PROGRESS)
        val match = matches.getValue(matchId)
        val point = match.arena.spectators.first()
        val world = Bukkit.getWorld(match.arena.world) ?: return onDone(RankedSpectateRules.MSG_WORLD)
        val target = Location(world, point.x, point.y, point.z, point.yaw, point.pitch)
        spectateRequests += uuid
        world.getChunkAtAsync(target.blockX shr 4, target.blockZ shr 4, false).whenComplete { chunk, error ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!spectateRequests.remove(uuid)) return@Runnable // stop() ran meanwhile
                val player = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline } ?: return@Runnable
                val loaded = error == null && chunk != null
                // The same match object: a match that ended meanwhile is never watched.
                val facts = spectateFacts(player, matchId).let { if (matches[matchId] === match) it else it.copy(state = null) }
                val problem = RankedSpectateRules.arrivalProblem(facts, loaded, if (loaded) SafeSpots.problem(target) else null)
                if (problem != null) return@Runnable onDone(problem)
                spectateRequests += uuid
                teleport(player, target).whenComplete { ok, _ ->
                    val finish = Runnable {
                        spectateRequests.remove(uuid)
                        if (Bukkit.getPlayer(uuid)?.isOnline != true) return@Runnable
                        if (ok != true) return@Runnable onDone(RankedSpectateRules.MSG_TELEPORT_FAILED)
                        if (matches[matchId] !== match || !RankedSpectateRules.spectatable(match.state)) {
                            return@Runnable onDone(RankedSpectateRules.noMatch(matchId))
                        }
                        spectating.remove(uuid)?.let { matches[it]?.spectators?.remove(uuid) }
                        spectating[uuid] = match.id
                        match.spectators += uuid
                        onDone(null)
                    }
                    if (Bukkit.isPrimaryThread()) finish.run() else plugin.server.scheduler.runTask(plugin, finish)
                }
            })
        }
    }

    fun unspectate(staff: Player): String? {
        val id = spectating.remove(staff.uniqueId) ?: return "You aren't spectating a match."
        matches[id]?.spectators?.remove(staff.uniqueId)
        return null
    }

    // ── Records ─────────────────────────────────────────────

    fun recentMatches(limit: Int) = store.recentMatches(limit)
    fun record(id: String) = store.match(id)
    fun recordParticipants(id: String) = store.participants(id)
    fun recordEvents(id: String, limit: Int) = store.events(id, limit)
    fun recordSnapshots(id: String) = store.snapshotsOf(id)

    /** "Name (uuid)" for a player actor key, the key itself otherwise (console). */
    private fun initiatorLabel(actor: String): String {
        val uuid = runCatching { UUID.fromString(actor) }.getOrNull() ?: return actor
        val name = Bukkit.getPlayer(uuid)?.name ?: plugin.lifestealTeamManager.nameOf(uuid)
        return "$name ($uuid)"
    }

    companion object {
        const val ADMIN_PERMISSION = "joshymc.lifesteal.ranked.admin"
        const val SPECTATE_PERMISSION = "joshymc.lifesteal.ranked.spectate"
        /** Chunk loads and teleports into the arena must finish within this, or the match is cancelled. */
        const val PREPARATION_TIMEOUT_TICKS = 20L * 20L
        /** Issue #1498: how often the fight re-checks its participants for restricted states (1s). */
        const val INTEGRITY_CHECK_TICKS = 20L
    }
}
