package com.liam.joshymc.lifesteal.teams.progression

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bounty.BountyMoney
import com.liam.joshymc.lifesteal.hearts.HeartRules
import com.liam.joshymc.lifesteal.ranked.Side
import com.liam.joshymc.lifesteal.ranked.rating.RatingStore
import com.liam.joshymc.lifesteal.teams.LifestealTeamStore.Team
import com.liam.joshymc.milestone.PlacedBlockTracker
import com.liam.joshymc.tutorial.TutorialRules
import com.liam.joshymc.util.Msg
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifesteal Team Upgrades and Shared Progression (issue #1426). Lifesteal only: on a server that
 * doesn't host Lifesteal it creates no tables, registers no listener and every action says so; the
 * Survival `TeamManager` is never touched.
 *
 * XP only comes from events other systems already verified:
 * - PvP: a kill the heart service accepted as legitimate ([HeartRules.Decision], so teammates,
 *   allies, the same-victim cooldown, grace period and unknown team state never count), plus this
 *   system's own same-victim window and shared-connection (alt) refusal.
 * - Bounties: a claim that was paid ([com.liam.joshymc.lifesteal.bounty.LifestealBountyManager]).
 * - Ranked Team Wars: a match the rating service rated (public, same-opponent limit; tests and
 *   unrated challenges never count).
 * - Mining: naturally generated ores (player-placed ores are marked in chunk data and never count).
 * - Exploration: sampled real movement, no AFK, no teleports, no circling ([ExplorationMeter]).
 *
 * Every award, purchase, deposit and claim is one [TeamProgressionStore] transaction on the
 * database thread; this class only gathers events, keeps a read cache for menus and tells players.
 */
class LifestealTeamProgressionManager(private val plugin: Joshymc) {

    val store = TeamProgressionStore(plugin.databaseManager, object : TeamProgressionStore.Wallet {
        override fun withdraw(uuid: UUID, coins: Double): Boolean = plugin.economyManager.withdraw(uuid, coins)
    })

    @Volatile var settings: TeamProgressionSettings? = null
        private set
    @Volatile private var tablesReady = false

    private val snapshots = ConcurrentHashMap<String, TeamProgressionStore.Snapshot>()
    private val busy = ConcurrentHashMap.newKeySet<UUID>()

    /** Ores mined since the last flush (main thread only). */
    private val pendingOres = HashMap<UUID, Long>()
    private val meters = HashMap<UUID, ExplorationMeter>()
    /** Travelled blocks not yet worth a full XP step (carried between flushes). */
    private val pendingBlocks = HashMap<UUID, Long>()
    private val flushSeq = AtomicLong()
    private val bootId = UUID.randomUUID().toString().substring(0, 8)
    private val tasks = mutableListOf<BukkitTask>()

    /** Player-placed ores never count (issue #1426); its own chunk key, separate from Milestones'. */
    val placed = PlacedBlockTracker(plugin, "team_xp_placed")

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** The `economy` table is Lifesteal's (dedicated Lifesteal server), so the bank may take coins. */
    val economyIsLifesteal: Boolean
        get() = TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, plugin.networkIdentityManager.hostedGamemodes)

    /** Progression is read and earned (teams running, settings valid and enabled). */
    val active: Boolean get() = hosted && tablesReady && settings?.enabled == true && plugin.lifestealTeamManager.active

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    fun seasonId(): String = try { plugin.lifestealProgressionManager.progress().season?.id } catch (_: Exception) { null } ?: DEFAULT_SEASON

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload, after lifestealTeamManager.refresh(). */
    fun refresh() {
        stopTasks()
        snapshots.clear()
        if (!hosted) {
            settings = null
            return
        }
        val result = plugin.lifestealConfigManager.teamProgression
        settings = result.settings
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[TeamProgression] Could not create the team progression tables, so Team Upgrades are off: ${e.message}")
                return
            }
        }
        val s = settings
        when {
            s == null -> {
                plugin.logger.severe("[TeamProgression] lifesteal.yml > team-progression is invalid: no Team XP, purchases, deposits or claims until it is fixed (stored progression is kept).")
                return
            }
            !s.enabled -> {
                plugin.logger.info("[TeamProgression] Disabled in lifesteal.yml (team-progression.enabled: false); stored levels, upgrades and banks are kept.")
                return
            }
        }
        placed.tracked = s!!.xp.ores
        if (!economyIsLifesteal) {
            plugin.logger.warning("[TeamProgression] This server also hosts Survival, so its coin balances are Survival's: the team bank takes no deposits here (Team XP still works).")
        }
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sampleExploration() }, 100L, SAMPLE_TICKS)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flush() }, FLUSH_TICKS, FLUSH_TICKS)
        db("load snapshots") {
            val season = seasonId()
            val cycle = cycle(s, season)
            for (id in store.activeTeamIds()) snapshots[id] = store.snapshot(id, cycle, season)
            store.pruneDaily(TeamProgressCalendar.day(System.currentTimeMillis() - 8L * 24 * 3_600_000L, s.xp.timezone))
            store.pruneUnawarded(System.currentTimeMillis() - REFUSED_EVENT_RETENTION_MS)
        }
        plugin.logger.info("[TeamProgression] On: level formula ${s.levels.base} x level^${s.levels.exponent}, ${s.objectives.list.size} objective(s) (${s.objectives.cycle.id}), season policy ${s.seasons.resetPolicy.id}.")
    }

    /** Shutdown: hands the last mining/exploration counts to the database queue (it drains before closing). */
    fun stop() {
        try { flush() } catch (e: Exception) { plugin.logger.warning("[TeamProgression] Couldn't save the last Team XP batch: ${e.message}") }
        stopTasks()
        meters.clear()
        pendingBlocks.clear()
        placed.clear()
        busy.clear()
    }

    private fun stopTasks() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    fun onQuit(player: Player) {
        // Under one batch of travel is dropped on quit (memory stays bounded by online players).
        meters.remove(player.uniqueId)
        pendingBlocks.remove(player.uniqueId)
    }

    // ── Reads (cache) ───────────────────────────────────────

    fun snapshot(teamId: String): TeamProgressionStore.Snapshot? = snapshots[teamId]

    fun perks(teamId: String): TeamPerks? {
        val s = settings ?: return null
        return TeamPerks.of(snapshots[teamId]?.tiers.orEmpty(), s)
    }

    fun levelProgress(teamId: String): TeamLevels.Progress? {
        val s = settings ?: return null
        return TeamLevels.progress(snapshots[teamId]?.totalXp ?: 0, s.levels)
    }

    fun level(teamId: String): Int = levelProgress(teamId)?.level ?: 1

    /** Extra team storage slots bought (for the future Lifesteal team storage; 0 until it exists). */
    fun storageSlotBonus(teamId: String): Int = perks(teamId)?.storageSlots ?: 0

    /** Extra team homes bought (for future Lifesteal team homes; 0 until they exist). */
    fun extraHomes(teamId: String): Int = perks(teamId)?.extraHomes ?: 0

    /** The team name in its chosen colour (cosmetic only), with its title if one is set. */
    fun styledName(team: Team): Component {
        val snap = snapshots[team.id]
        val color = snap?.color?.let { NamedTextColor.NAMES.value(it) } ?: NamedTextColor.GOLD
        val name = Component.text(team.name, color as TextColor).decorate(TextDecoration.BOLD)
        val title = snap?.title ?: return name
        return name.append(Component.text(" [$title]", NamedTextColor.GRAY).decoration(TextDecoration.BOLD, false))
    }

    /** Reloads [teamId]'s snapshot on the database thread, then runs [then] on the main thread. */
    fun reload(teamId: String, then: (() -> Unit)? = null) {
        val s = settings ?: return
        db("reload $teamId") {
            val season = seasonId()
            snapshots[teamId] = store.snapshot(teamId, cycle(s, season), season)
            if (then != null) main(then)
        }
    }

    /** Runs a read on the database thread and hands the result to [callback] on the main thread (null on failure). */
    fun <T> read(description: String, query: (TeamProgressionStore) -> T, callback: (T?) -> Unit) {
        plugin.databaseManager.async("team progression: $description") {
            val value = try { query(store) } catch (e: Exception) {
                plugin.logger.warning("[TeamProgression] $description failed: ${e.message}"); null
            }
            main { callback(value) }
        }
    }

    fun cycle(s: TeamProgressionSettings, season: String = seasonId()): String =
        TeamProgressCalendar.cycle(s.objectives.cycle, System.currentTimeMillis(), s.xp.timezone, season)

    /** Why [player] can't use Team Upgrades right now, or null. */
    fun unavailableReason(player: Player? = null): String? = when {
        !hosted -> "Team Upgrades only exist on the Lifesteal server."
        !tablesReady -> "Team Upgrades are unavailable right now. Please tell staff."
        settings == null -> "Team Upgrades are paused right now (server configuration). Please tell staff."
        settings?.enabled != true -> "Team Upgrades are turned off right now."
        plugin.lifestealTeamManager.unavailableReason() != null -> plugin.lifestealTeamManager.unavailableReason()
        player != null && !isLifesteal(player) -> "Team Upgrades only work in Lifesteal."
        player != null && !player.hasPermission(PERMISSION_USE) -> Msg.NO_PERMISSION
        else -> null
    }

    // ── XP sources (verified events only) ───────────────────

    /**
     * The heart service settled a Lifesteal kill ([decision] from [HeartRules.decide]). Only kills it
     * accepted as legitimate count: refusals (teammates, allies, unknown team state, the same-victim
     * cooldown, before launch / grace / unknown progression) never earn Team XP.
     */
    fun onPvpKill(killer: UUID, victim: UUID, decision: HeartRules.Decision, dedupeKey: String) {
        if (!active) return
        val legit = when (decision) {
            is HeartRules.Decision.Transfer, is HeartRules.Decision.VictimLosesOnly,
            HeartRules.Decision.VictimAtMinimum, HeartRules.Decision.KillerAtMaximum -> true
            else -> false
        }
        if (!legit || killer == victim) return
        val s = settings ?: return
        val now = System.currentTimeMillis()
        val season = seasonId()
        db("pvp $dedupeKey") {
            if (s.xp.blockSharedConnections && sharedConnection(killer, victim)) {
                plugin.logger.info("[TeamProgression] No Team XP for a kill between players who have shared a connection ($killer -> $victim).")
                return@db
            }
            award(TeamProgressionStore.XpEvent(
                "team:$dedupeKey", XpSource.PVP_KILL, killer, null, s.xp.amounts[XpSource.PVP_KILL] ?: 0,
                mapOf(ObjectiveType.PVP_KILLS to 1L, ObjectiveType.UNIQUE_VICTIMS to 1L), victim.toString(), season, now,
            ), s)
        }
    }

    /** A Lifesteal bounty was paid to [hunter] for killing [target] (once per payout). */
    fun onBountyClaimed(hunter: UUID, target: UUID, payoutId: String) {
        if (!active) return
        val s = settings ?: return
        val now = System.currentTimeMillis()
        val season = seasonId()
        db("bounty $payoutId") {
            if (s.xp.blockSharedConnections && sharedConnection(hunter, target)) return@db
            award(TeamProgressionStore.XpEvent(
                "team:bounty:$payoutId", XpSource.BOUNTY_CLAIM, hunter, null, s.xp.amounts[XpSource.BOUNTY_CLAIM] ?: 0,
                mapOf(ObjectiveType.BOUNTIES to 1L), target.toString(), season, now,
            ), s)
        }
    }

    /** The rating service rated a Ranked Team Wars match (committed, once). Both teams played; one won. */
    fun onRankedRated(result: RatingStore.Finalized.Rated) {
        if (!active) return
        val s = settings ?: return
        val now = System.currentTimeMillis()
        val winnerId = if (result.winner == Side.A) result.teamAId else result.teamBId
        db("ranked ${result.matchId}") {
            for ((teamId, opponent) in listOf(result.teamAId to result.teamBId, result.teamBId to result.teamAId)) {
                award(TeamProgressionStore.XpEvent(
                    "team:ranked:${result.matchId}:$teamId", XpSource.RANKED_MATCH, null, teamId, s.xp.amounts[XpSource.RANKED_MATCH] ?: 0,
                    mapOf(ObjectiveType.RANKED_MATCHES to 1L), opponent, result.seasonId, now,
                ), s)
                if (teamId == winnerId) award(TeamProgressionStore.XpEvent(
                    "team:rankedwin:${result.matchId}:$teamId", XpSource.RANKED_WIN, null, teamId, s.xp.amounts[XpSource.RANKED_WIN] ?: 0,
                    mapOf(ObjectiveType.RANKED_WINS to 1L), opponent, result.seasonId, now,
                ), s)
            }
        }
    }

    /** MONITOR, not cancelled: a block broken by [player]. Only naturally generated configured ores count. */
    fun onBlockBroken(player: Player, block: Block) {
        val s = settings ?: return
        if (!active || block.type.name !in s.xp.ores) return
        if (placed.isPlaced(block)) { placed.unmark(block); return }
        if (!eligible(player)) return
        if (plugin.lifestealTeamManager.teamOf(player.uniqueId) == null) return
        pendingOres.merge(player.uniqueId, 1L, Long::plus)
    }

    private fun eligible(player: Player): Boolean =
        isLifesteal(player) && (player.gameMode == GameMode.SURVIVAL || player.gameMode == GameMode.ADVENTURE) &&
            !afk(player.uniqueId) && !player.hasMetadata("NPC")

    private fun afk(uuid: UUID): Boolean = try { plugin.afkManager.isAfk(uuid) } catch (_: UninitializedPropertyAccessException) { false }

    private fun sampleExploration() {
        if (!active) return
        for (player in Bukkit.getOnlinePlayers()) {
            if (plugin.lifestealTeamManager.teamOf(player.uniqueId) == null) { meters.remove(player.uniqueId); continue }
            val meter = meters.getOrPut(player.uniqueId) { ExplorationMeter() }
            val loc = player.location
            meter.sample(player.world.uid.toString(), loc.x, loc.z, eligible(player) && !player.isFlying)
        }
    }

    /** Turns the gathered ores and travelled blocks into one XP event per player and source. */
    private fun flush() {
        val s = settings ?: return
        if (!active) { pendingOres.clear(); return }
        val now = System.currentTimeMillis()
        val season = seasonId()
        val events = mutableListOf<TeamProgressionStore.XpEvent>()
        for ((uuid, count) in pendingOres) {
            events += TeamProgressionStore.XpEvent(
                "team:ore:$bootId:$uuid:${flushSeq.incrementAndGet()}", XpSource.ORE_MINED, uuid, null,
                count * (s.xp.amounts[XpSource.ORE_MINED] ?: 0), mapOf(ObjectiveType.ORES to count), null, season, now,
            )
        }
        pendingOres.clear()
        for ((uuid, meter) in meters) {
            val carried = (pendingBlocks[uuid] ?: 0) + meter.take()
            // One event per EXPLORATION_BATCH blocks (not one per player per minute); the rest carries over.
            if (carried < EXPLORATION_BATCH) { pendingBlocks[uuid] = carried; continue }
            val steps = carried / BLOCKS_PER_STEP
            pendingBlocks[uuid] = carried % BLOCKS_PER_STEP
            events += TeamProgressionStore.XpEvent(
                "team:explore:$bootId:$uuid:${flushSeq.incrementAndGet()}", XpSource.EXPLORATION, uuid, null,
                steps * (s.xp.amounts[XpSource.EXPLORATION] ?: 0), mapOf(ObjectiveType.BLOCKS_TRAVELLED to steps * BLOCKS_PER_STEP), null, season, now,
            )
        }
        if (events.isEmpty()) return
        db("batch of ${events.size}") { for (event in events) award(event, s) }
    }

    /** On the database thread: one award, then the cache and the team's messages. */
    private fun award(event: TeamProgressionStore.XpEvent, s: TeamProgressionSettings) {
        val result = try { store.award(event, s) } catch (e: Exception) {
            plugin.logger.severe("[TeamProgression] Team XP event ${event.dedupeKey} failed and was rolled back: ${e.message}")
            return
        }
        val awarded = result as? TeamProgressionStore.AwardResult.Awarded ?: return
        val season = event.seasonId
        snapshots[awarded.teamId] = store.snapshot(awarded.teamId, TeamProgressCalendar.cycle(s.objectives.cycle, event.now, s.xp.timezone, season), season)
        val before = TeamLevels.levelFor(awarded.totalBefore, s.levels)
        val after = TeamLevels.levelFor(awarded.totalAfter, s.levels)
        if (after == before && awarded.completed.isEmpty()) return
        main {
            val team = plugin.lifestealTeamManager.team(awarded.teamId) ?: return@main
            for (done in awarded.completed) {
                tellTeam(team, fill(s.gui.objectiveDone, "objective" to done.objective.name, "xp" to done.xp.toString(), "team" to team.name))
            }
            if (after > before) {
                tellTeam(team, fill(s.gui.levelUp, "team" to team.name, "level" to after.toString()))
                plugin.logger.info("[TeamProgression] Team ${team.name} (${team.id}) reached Level $after.")
                if ((before + 1..after).any { TeamLevels.isMilestone(it, s.milestones) }) {
                    val reached = (before + 1..after).last { TeamLevels.isMilestone(it, s.milestones) }
                    tellTeam(team, fill(s.gui.milestoneReady, "level" to reached.toString(), "team" to team.name))
                }
            }
        }
    }

    // ── Actions (re-checked in the store transaction) ───────

    /** A member pays [cents] of their own coins into the team bank. [reference] makes a GUI double click count once. */
    fun deposit(player: Player, cents: Long, reference: String, onDone: (() -> Unit)? = null) {
        unavailableReason(player)?.let { return Msg.error(player, it) }
        val s = settings ?: return
        if (!economyIsLifesteal) return Msg.error(player, "The team bank is unavailable on this server (its coin balances aren't Lifesteal's).")
        if (!s.bank.depositsEnabled) return Msg.error(player, "Team bank deposits are turned off right now.")
        if (plugin.lifestealTeamManager.teamOf(player.uniqueId) == null) return Msg.error(player, "You're not in a Lifesteal team.")
        if (cents < s.bank.minimumDepositCents) return Msg.error(player, "The smallest deposit is ${BountyMoney.format(s.bank.minimumDepositCents)}.")
        if (!busy.add(player.uniqueId)) return Msg.error(player, "Your last team bank action is still being processed.")
        val uuid = player.uniqueId
        val name = player.name
        db("deposit $reference") {
            val result = try { store.deposit(TeamProgressionStore.DepositRequest(reference, uuid, name, cents, System.currentTimeMillis()), s) } catch (e: Exception) {
                plugin.logger.severe("[TeamProgression] Deposit $reference by $name failed and was rolled back; no coins were taken: ${e.message}")
                null
            }
            if (result is TeamProgressionStore.DepositResult.Deposited) {
                snapshots[result.teamId] = store.snapshot(result.teamId, cycle(s), seasonId())
                plugin.logger.info("[TeamProgression] $name deposited ${BountyMoney.format(cents)} into team bank ${result.teamId} (balance ${BountyMoney.format(result.balanceAfter)}).")
            }
            main {
                busy.remove(uuid)
                val online = Bukkit.getPlayer(uuid) ?: return@main
                when (result) {
                    null -> Msg.error(online, "That deposit failed and no coins were taken. Please try again.")
                    is TeamProgressionStore.DepositResult.Deposited -> {
                        Msg.success(online, "Deposited ${BountyMoney.format(result.cents)} into the team bank. Balance: ${BountyMoney.format(result.balanceAfter)}.")
                        plugin.lifestealTeamManager.team(result.teamId)?.let { team ->
                            tellTeam(team, Component.text("$name deposited ${BountyMoney.format(result.cents)} into the team bank.", NamedTextColor.GREEN), except = uuid)
                        }
                    }
                    TeamProgressionStore.DepositResult.Duplicate -> Msg.error(online, "That deposit was already made.")
                    TeamProgressionStore.DepositResult.NotInTeam -> Msg.error(online, "You're not in a Lifesteal team.")
                    TeamProgressionStore.DepositResult.TeamInactive -> Msg.error(online, "Your team no longer exists.")
                    is TeamProgressionStore.DepositResult.BelowMinimum -> Msg.error(online, "The smallest deposit is ${BountyMoney.format(result.minimumCents)}.")
                    is TeamProgressionStore.DepositResult.OverCapacity -> Msg.error(online,
                        "The team bank holds at most ${BountyMoney.format(result.capacityCents)}; there's room for ${BountyMoney.format(result.roomCents)}. Team Economy upgrades raise the limit.")
                    TeamProgressionStore.DepositResult.InsufficientFunds -> Msg.error(online, "You don't have ${BountyMoney.format(cents)}.")
                }
                onDone?.invoke()
            }
        }
    }

    /**
     * The leader buys the next tier of [category]. Called by the confirmation screen with the tier
     * and team it showed; the store re-checks leadership, team state, level, price and funds.
     */
    fun purchase(player: Player, teamId: String, category: UpgradeCategory, expectedTier: Int, transactionId: String, onDone: (() -> Unit)? = null) {
        unavailableReason(player)?.let { return Msg.error(player, it) }
        val s = settings ?: return
        val team = plugin.lifestealTeamManager.teamOf(player.uniqueId)
        if (team == null || team.id != teamId) return Msg.error(player, "You're no longer in that team.")
        if (team.leader != player.uniqueId) return Msg.error(player, "Only the team leader can buy upgrades.")
        if (!category.systemAvailable) return Msg.error(player, "${category.label} needs ${category.requiresSystem}, which isn't available yet.")
        if (!busy.add(player.uniqueId)) return Msg.error(player, "Your last team purchase is still being processed.")
        val uuid = player.uniqueId
        val name = player.name
        db("purchase $transactionId") {
            val result = try {
                store.purchase(TeamProgressionStore.PurchaseRequest(transactionId, uuid, name, teamId, category, expectedTier, System.currentTimeMillis()), s)
            } catch (e: Exception) {
                plugin.logger.severe("[TeamProgression] Purchase $transactionId by $name failed and was rolled back; nothing was charged: ${e.message}")
                null
            }
            snapshots[teamId] = store.snapshot(teamId, cycle(s), seasonId())
            if (result is TeamProgressionStore.PurchaseResult.Purchased) {
                plugin.logger.info("[TeamProgression] $name bought ${category.label} tier ${result.tier} for team $teamId (${BountyMoney.format(result.costCents)}, bank ${BountyMoney.format(result.balanceAfter)}).")
            }
            main {
                busy.remove(uuid)
                val online = Bukkit.getPlayer(uuid)
                when (result) {
                    is TeamProgressionStore.PurchaseResult.Purchased -> plugin.lifestealTeamManager.team(teamId)?.let { t ->
                        tellTeam(t, Component.text("$name bought ", NamedTextColor.GREEN)
                            .append(Component.text("${category.label} ${TeamUpgradesMenu.roman(result.tier)}", NamedTextColor.GOLD))
                            .append(Component.text(" for ${BountyMoney.format(result.costCents)} from the team bank.", NamedTextColor.GREEN)))
                    }
                    else -> online?.let { Msg.error(it, purchaseRefusal(result, category)) }
                }
                onDone?.invoke()
            }
        }
    }

    private fun purchaseRefusal(result: TeamProgressionStore.PurchaseResult?, category: UpgradeCategory): String = when (result) {
        null -> "That purchase failed and nothing was charged. Please try again."
        TeamProgressionStore.PurchaseResult.Duplicate -> "That purchase was already made."
        TeamProgressionStore.PurchaseResult.NotInTeam -> "You're no longer in that team."
        TeamProgressionStore.PurchaseResult.TeamInactive -> "That team no longer exists."
        TeamProgressionStore.PurchaseResult.NotLeader -> "Only the team leader can buy upgrades."
        TeamProgressionStore.PurchaseResult.Unavailable -> "${category.label} upgrades are not available right now."
        is TeamProgressionStore.PurchaseResult.Stale -> "That screen was out of date (${category.label} is now tier ${result.currentTier}). Nothing was charged."
        TeamProgressionStore.PurchaseResult.MaxTier -> "${category.label} is already at its highest tier."
        is TeamProgressionStore.PurchaseResult.LevelTooLow -> "That tier needs Team Level ${result.required} (your team is Level ${result.level})."
        is TeamProgressionStore.PurchaseResult.InsufficientFunds ->
            "The team bank has ${BountyMoney.format(result.balanceCents)}; that tier costs ${BountyMoney.format(result.costCents)}."
        is TeamProgressionStore.PurchaseResult.Purchased -> ""
    }

    /** Any member claims a reached milestone for the team (once per team, ever). */
    fun claimMilestone(player: Player, level: Int, onDone: (() -> Unit)? = null) {
        unavailableReason(player)?.let { return Msg.error(player, it) }
        val s = settings ?: return
        val team = plugin.lifestealTeamManager.teamOf(player.uniqueId) ?: return Msg.error(player, "You're not in a Lifesteal team.")
        if (!busy.add(player.uniqueId)) return Msg.error(player, "Your last team action is still being processed.")
        val uuid = player.uniqueId
        val name = player.name
        val teamId = team.id
        db("milestone $teamId $level") {
            val result = try { store.claimMilestone(uuid, name, teamId, level, seasonId(), System.currentTimeMillis(), s) } catch (e: Exception) {
                plugin.logger.severe("[TeamProgression] Milestone claim (team $teamId, level $level) failed and was rolled back: ${e.message}")
                null
            }
            snapshots[teamId] = store.snapshot(teamId, cycle(s), seasonId())
            main {
                busy.remove(uuid)
                val online = Bukkit.getPlayer(uuid)
                when (result) {
                    is TeamProgressionStore.ClaimResult.Claimed -> plugin.lifestealTeamManager.team(teamId)?.let { t ->
                        val parts = buildList {
                            if (result.reward.coinsCents > 0) add("${BountyMoney.format(result.reward.coinsCents)} for the team bank")
                            result.reward.title?.let { add("the title \"$it\"") }
                        }.ifEmpty { listOf("the milestone badge") }
                        tellTeam(t, Component.text("$name claimed the Level $level milestone: ${parts.joinToString(" and ")}.", NamedTextColor.LIGHT_PURPLE))
                        plugin.logger.info("[TeamProgression] $name claimed milestone $level for team $teamId (${result.reward}).")
                    }
                    TeamProgressionStore.ClaimResult.AlreadyClaimed -> online?.let { Msg.error(it, "Your team already claimed the Level $level milestone.") }
                    TeamProgressionStore.ClaimResult.NotInTeam -> online?.let { Msg.error(it, "You're no longer in that team.") }
                    TeamProgressionStore.ClaimResult.TeamInactive -> online?.let { Msg.error(it, "That team no longer exists.") }
                    TeamProgressionStore.ClaimResult.NotMilestone -> online?.let { Msg.error(it, "Level $level isn't a milestone.") }
                    is TeamProgressionStore.ClaimResult.NotReached -> online?.let { Msg.error(it, "Your team is Level ${result.level}; that milestone needs Level $level.") }
                    null -> online?.let { Msg.error(it, "That didn't work and nothing changed. Please try again.") }
                }
                onDone?.invoke()
            }
        }
    }

    /** The leader picks the team colour / title (both must be unlocked). */
    fun setStyle(player: Player, color: String?, title: String?, onDone: (() -> Unit)? = null) {
        unavailableReason(player)?.let { return Msg.error(player, it) }
        val s = settings ?: return
        val team = plugin.lifestealTeamManager.teamOf(player.uniqueId) ?: return Msg.error(player, "You're not in a Lifesteal team.")
        if (team.leader != player.uniqueId) return Msg.error(player, "Only the team leader can change the team style.")
        if (!busy.add(player.uniqueId)) return Msg.error(player, "Your last team action is still being processed.")
        val uuid = player.uniqueId
        val name = player.name
        db("style ${team.id}") {
            val result = try { store.setStyle(uuid, name, team.id, color, title, System.currentTimeMillis(), s) } catch (e: Exception) {
                plugin.logger.warning("[TeamProgression] Style change for ${team.id} failed: ${e.message}"); null
            }
            snapshots[team.id] = store.snapshot(team.id, cycle(s), seasonId())
            main {
                busy.remove(uuid)
                val online = Bukkit.getPlayer(uuid)
                when (result) {
                    is TeamProgressionStore.StyleResult.Changed -> online?.let { Msg.success(it, "Team style updated.") }
                    TeamProgressionStore.StyleResult.Locked -> online?.let { Msg.error(it, "That isn't unlocked for your team.") }
                    TeamProgressionStore.StyleResult.NotLeader -> online?.let { Msg.error(it, "Only the team leader can change the team style.") }
                    TeamProgressionStore.StyleResult.NotInTeam, TeamProgressionStore.StyleResult.TeamInactive -> online?.let { Msg.error(it, "You're no longer in that team.") }
                    null -> online?.let { Msg.error(it, "That didn't work. Please try again.") }
                }
                onDone?.invoke()
            }
        }
    }

    // ── Plumbing ────────────────────────────────────────────

    /** AltManager's `player_connections` (salted hashes of connection addresses), as the bounty system reads it. */
    private fun sharedConnection(a: UUID, b: UUID): Boolean = try {
        plugin.databaseManager.queryFirst(
            "SELECT 1 AS x FROM player_connections pa JOIN player_connections pb ON pb.connection_hash = pa.connection_hash WHERE pa.uuid = ? AND pb.uuid = ? LIMIT 1",
            a.toString(), b.toString()
        ) { it.getInt("x") } != null
    } catch (_: Exception) {
        false
    }

    private fun db(description: String, block: () -> Unit) {
        plugin.databaseManager.async("team progression: $description") {
            try { block() } catch (e: Exception) { plugin.logger.severe("[TeamProgression] $description failed: ${e.message}") }
        }
    }

    private fun main(block: () -> Unit) {
        if (!plugin.isEnabled) return
        if (Bukkit.isPrimaryThread()) block() else try {
            Bukkit.getScheduler().runTask(plugin, Runnable(block))
        } catch (_: org.bukkit.plugin.IllegalPluginAccessException) {
        }
    }

    private fun tellTeam(team: Team, message: Component, except: UUID? = null) {
        for (member in team.members) {
            if (member.uuid == except) continue
            Bukkit.getPlayer(member.uuid)?.let { if (it.isOnline) plugin.commsManager.send(it, message) }
        }
    }

    private fun fill(template: String, vararg values: Pair<String, String>): Component {
        var text = template
        for ((key, value) in values) text = text.replace("{$key}", value)
        return plugin.commsManager.parseLegacy(text)
    }

    companion object {
        const val PERMISSION_USE = "joshymc.lifesteal.team.upgrades"
        const val DEFAULT_SEASON = "season-1"
        private const val SAMPLE_TICKS = 100L
        private const val FLUSH_TICKS = 20L * 60
        const val BLOCKS_PER_STEP = 100L
        const val EXPLORATION_BATCH = 500L
        /** Event rows that awarded nothing are kept this long (dedupe and the PvP window need far less). */
        private const val REFUSED_EVENT_RETENTION_MS = 30L * 24 * 3_600_000L
    }
}
