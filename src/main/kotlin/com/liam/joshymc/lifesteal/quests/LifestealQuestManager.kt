package com.liam.joshymc.lifesteal.quests

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.bounty.BountyRules
import com.liam.joshymc.manager.PurchaseFulfillmentManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.milestone.ActivityClock
import com.liam.joshymc.milestone.CombatRules
import com.liam.joshymc.milestone.ExplorationRules
import com.liam.joshymc.milestone.LoopDetector
import com.liam.joshymc.milestone.PlacedBlockTracker
import com.liam.joshymc.milestone.RateWindow
import com.liam.joshymc.tutorial.TutorialRules
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.InputStreamReader
import java.util.UUID
import java.util.logging.Level

/**
 * The Lifesteal Daily & Weekly Quests (issue #1425): three personal Daily and three Weekly quests per
 * player, progress from legitimate Lifesteal gameplay, manual claims, rerolls. Lifesteal only, and
 * like the bounties it only runs where this server's `economy` table is Lifesteal's (a dedicated
 * Lifesteal server, [TutorialRules.economyBelongsTo]), so a quest can never pay Survival coins.
 * The Survival quests (QuestCycleManager, `/quests` outside Lifesteal) are untouched.
 *
 * - Boards are created and read on the database thread ([LifestealQuestStore.ensureBoard]); live
 *   progress is kept in memory per online player and saved every 30s, on quit, at shutdown and
 *   immediately when a quest completes. Saves can never lower progress or undo a claim/reroll.
 * - Claims and rerolls run on the main thread as single transactions that re-check everything the
 *   menu showed. Rewards are written as payout rows in the claim transaction and then handed to the
 *   store-delivery queue (PurchaseFulfillmentManager, reference `lsquest:<payout>`), which deposits
 *   Coins/Credits and delivers items safely, offline and after restarts. No command is ever run.
 * - Disabling (`enabled: false`) or an invalid file stops new progress, claims and rerolls; stored
 *   quests and already-claimed rewards are kept and still delivered.
 */
class LifestealQuestManager(private val plugin: Joshymc) {

    companion object {
        const val FILE_NAME = "lifesteal-quests.yml"
        const val SOURCE_PREFIX = PurchaseFulfillmentManager.QUEST_SOURCE_PREFIX
        private const val FLUSH_TICKS = 20L * 30
        private const val CYCLE_CHECK_TICKS = 20L * 20
        private const val SAMPLE_TICKS = 40L
        private const val PAYOUT_SWEEP_TICKS = 20L * 60
        private const val PRUNE_TICKS = 20L * 60 * 10
        private const val HOOK_MEMORY = 512
        private const val CONNECTION_CACHE_MILLIS = 10 * 60_000L
    }

    val store = LifestealQuestStore(plugin.databaseManager) { uuid, amount -> plugin.creditsManager.withdraw(uuid, amount) }
    val tracker = PlacedBlockTracker(plugin, "lifesteal_quest_placed")
    val menu = LifestealQuestMenu(plugin, this)

    @Volatile var settings: QuestSettings? = null
        private set
    @Volatile var problems: List<QuestConfig.Problem> = emptyList()
        private set
    @Volatile private var tablesReady = false
    private val tasks = ArrayList<BukkitTask>()

    // ── Per-player state (main thread) ──────────────────────

    /** One live quest. [progress] grows in memory and is saved in batches. */
    class Live(var row: LifestealQuestStore.Assignment) {
        var progress: Long = row.progress
        var state: LifestealQuestStore.State = row.state
        val id: Long get() = row.id
        val complete: Boolean get() = progress >= row.target
    }

    class Board(val cycle: QuestCycles.Cycle, val quests: MutableList<Live>, var usage: LifestealQuestStore.RerollUsage)

    class Book(val boards: MutableMap<QuestPeriod, Board>) {
        val dirty = HashSet<Long>()
        fun all(): List<Live> = boards.values.flatMap { it.quests }
        fun find(id: Long): Live? = all().firstOrNull { it.id == id }
    }

    private val books = HashMap<UUID, Book>()
    private val loading = HashSet<UUID>()
    private val busy = HashSet<UUID>()

    internal val clocks = HashMap<UUID, ActivityClock>()
    private val mobKills = HashMap<UUID, RateWindow>()
    private val pvpKills = HashMap<UUID, RateWindow>()
    private val lastPvp = HashMap<Pair<UUID, UUID>, Long>()
    /** victim -> attacker -> last eligible hit (assists, won fights). */
    private val hits = HashMap<UUID, HashMap<UUID, Long>>()
    private class DamageWindow(var start: Long, var counted: Long)
    private val damage = HashMap<Pair<UUID, UUID>, DamageWindow>()
    private val connections = HashMap<Pair<UUID, UUID>, Pair<Boolean, Long>>()
    private val connectionLookups = HashSet<Pair<UUID, UUID>>()
    private val lastFish = HashMap<UUID, Long>()
    private val countedHooks = object : LinkedHashMap<UUID, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?) = size > HOOK_MEMORY
    }

    internal class Exploration {
        var world: UUID? = null
        var x = 0.0
        var z = 0.0
        var yaw = 0f
        var pitch = 0f
        var lastSampleNanos = System.nanoTime()
        var lastInputAt = 0L
        val loop = LoopDetector()
        fun reset(location: Location?) {
            world = location?.world?.uid
            x = location?.x ?: 0.0
            z = location?.z ?: 0.0
            lastSampleNanos = System.nanoTime()
        }
    }
    internal val exploration = HashMap<UUID, Exploration>()

    // ── Status ──────────────────────────────────────────────

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    val economyIsLifesteal: Boolean
        get() = TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, plugin.networkIdentityManager.hostedGamemodes)

    /** Existing payouts are delivered (even while disabled or invalid). */
    val processing: Boolean get() = hosted && economyIsLifesteal && tablesReady

    /** New progress, claims and rerolls are accepted. */
    val running: Boolean get() = processing && settings?.enabled == true

    /**
     * Placed-block markers are kept up to date: also while the quests are disabled, so ores placed
     * during a pause never count once they are switched back on.
     */
    val tracking: Boolean get() = processing && settings != null

    fun isLifesteal(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    fun unavailableReason(player: Player?): String? = when {
        !hosted -> "Lifesteal quests only exist on the Lifesteal server."
        !economyIsLifesteal -> "Lifesteal quests are unavailable on this server (its coin balances aren't Lifesteal's)."
        !tablesReady -> "Lifesteal quests are unavailable right now. Please tell staff."
        settings == null -> "Lifesteal quests are paused right now (server configuration). Please tell staff."
        settings?.enabled != true -> plain(settings?.messages?.disabled ?: QuestMessages().disabled)
        player != null && !isLifesteal(player.world) -> "Lifesteal quests can only be used in Lifesteal."
        else -> null
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload (after lifesteal.yml and the gamemode locks). */
    fun refresh() {
        stop()
        if (!hosted) {
            settings = null
            return
        }
        val result = loadSettings()
        settings = result.settings
        problems = result.problems
        for (p in result.problems) {
            if (p.severe) plugin.logger.severe("[LifestealQuests] $FILE_NAME: ${p.message}") else plugin.logger.warning("[LifestealQuests] $FILE_NAME: ${p.message}")
        }
        if (!economyIsLifesteal) {
            plugin.logger.warning("[LifestealQuests] This server also hosts Survival, so its balances are Survival's; Lifesteal quests are off here (dedicated Lifesteal server only).")
            return
        }
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[LifestealQuests] Could not create the quest tables, so quests are off: ${e.message}")
                return
            }
        }
        val s = settings
        when {
            s == null -> plugin.logger.severe("[LifestealQuests] $FILE_NAME is invalid: no quest progress, claims or rerolls until it is fixed (stored quests are kept). Fix it, then /joshymc reload.")
            !s.enabled -> plugin.logger.info("[LifestealQuests] Disabled in $FILE_NAME: stored quests are kept, claimed rewards are still delivered.")
            else -> plugin.logger.info(
                "[LifestealQuests] On: ${s.pool(QuestPeriod.DAILY).count { it.enabled }} daily and ${s.pool(QuestPeriod.WEEKLY).count { it.enabled }} weekly quests, " +
                    "resets ${s.dailyResetTime} daily / ${s.weeklyResetDay} ${s.weeklyResetTime} (${s.zone})."
            )
        }
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sweepPayouts() }, 200L, PAYOUT_SWEEP_TICKS)
        if (s != null) tracker.tracked = trackedMaterials(s)
        if (s == null || !s.enabled) return
        loadPvpCooldowns(s)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flushAll() }, FLUSH_TICKS, FLUSH_TICKS)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { checkCycles() }, CYCLE_CHECK_TICKS, CYCLE_CHECK_TICKS)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sampleExploration() }, SAMPLE_TICKS, SAMPLE_TICKS)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { menu.refreshOpen() }, 40L, 40L)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { prune() }, PRUNE_TICKS, PRUNE_TICKS)
        for (player in Bukkit.getOnlinePlayers()) onJoin(player)
    }

    /** Saves every online player's progress synchronously and stops. Safe to call twice. */
    fun stop() {
        for (task in tasks) task.cancel()
        tasks.clear()
        val now = System.currentTimeMillis()
        if (tablesReady) {
            for ((uuid, book) in books) {
                val pending = drain(book)
                if (pending.isEmpty()) continue
                try {
                    store.saveProgress(pending, now)
                } catch (e: Exception) {
                    plugin.logger.severe("[LifestealQuests] Could not save quest progress of $uuid on stop: ${e.message}")
                }
            }
        }
        menu.closeAll()
        books.clear()
        loading.clear()
        busy.clear()
        clocks.clear()
        mobKills.clear()
        pvpKills.clear()
        lastPvp.clear()
        hits.clear()
        damage.clear()
        connections.clear()
        connectionLookups.clear()
        lastFish.clear()
        countedHooks.clear()
        exploration.clear()
        tracker.clear()
    }

    private fun loadSettings(): QuestConfig.Result {
        return try {
            val file = plugin.configFile(FILE_NAME)
            if (!file.exists()) plugin.saveResource(FILE_NAME, false)
            val yaml = YamlConfiguration()
            yaml.load(file)
            val bundled = plugin.getResource(FILE_NAME)?.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
            val source = object : QuestConfig.Source {
                // Scalars missing from the server's file come from the bundled one; pools never merge
                // (a template the owner deleted must stay deleted), they're only used when the
                // server's file has no pools section at all.
                override fun get(path: String): Any? {
                    val pool = path.startsWith("pools.")
                    val own = yaml.get(path, null)
                    if (own != null || (pool && yaml.contains("pools"))) return own
                    return bundled?.get(path, null)
                }
                override fun keys(path: String): List<String>? {
                    val own = yaml.get(path, null) as? ConfigurationSection
                    if (own != null) return own.getKeys(false).toList()
                    if (yaml.contains("pools")) return null
                    return (bundled?.get(path, null) as? ConfigurationSection)?.getKeys(false)?.toList()
                }
            }
            QuestConfig.parse(source, catalog())
        } catch (e: Exception) {
            QuestConfig.Result(null, listOf(QuestConfig.Problem(true, "Could not read $FILE_NAME: ${e.message}")))
        }
    }

    private fun catalog() = object : PurchaseRewards.Catalog {
        override fun crateExists(id: String): Boolean = try {
            plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(id)) != null
        } catch (_: UninitializedPropertyAccessException) { false }
        override fun moneyVoucherExists(id: String): Boolean = false
        override fun chatTagVoucherExists(id: String): Boolean = try {
            plugin.chatTagManager.getVoucherTag(id) != null
        } catch (_: UninitializedPropertyAccessException) { false }
    }

    private fun trackedMaterials(s: QuestSettings): Set<String> {
        val out = HashSet<String>()
        out += QuestBlocks.GENERATED
        out += QuestBlocks.PLACEMENT_MATTERS
        for (t in QuestPeriod.entries.flatMap { s.pool(it) }) {
            if (t.objective == QuestObjective.GATHER_BLOCK) out += t.match
        }
        org.bukkit.Material.values().filter { QuestBlocks.isOre(it.name) }.forEach { out += it.name }
        return out
    }

    private fun loadPvpCooldowns(s: QuestSettings) {
        val since = System.currentTimeMillis() - maxOf(s.antiAbuse.pvpSameVictimCooldownMillis, 3_600_000L)
        try {
            for (k in store.pvpKillsSince(since)) {
                lastPvp[k.killer to k.victim] = maxOf(lastPvp[k.killer to k.victim] ?: 0L, k.at)
                pvpKills.getOrPut(k.killer) { RateWindow(3_600_000L, s.antiAbuse.pvpMaxKillsPerHour) }.add(k.at)
            }
        } catch (e: Exception) {
            plugin.logger.warning("[LifestealQuests] Could not load PvP cooldowns: ${e.message}")
        }
    }

    private fun prune() {
        val s = settings ?: return
        val now = System.currentTimeMillis()
        val cutoff = now - maxOf(s.antiAbuse.pvpSameVictimCooldownMillis, 3_600_000L)
        lastPvp.values.removeIf { it < cutoff }
        damage.values.removeIf { now - it.start >= 3_600_000L }
        connections.values.removeIf { now - it.second >= CONNECTION_CACHE_MILLIS }
        hits.values.forEach { m -> m.values.removeIf { now - it > 600_000L } }
        hits.values.removeIf { it.isEmpty() }
        plugin.databaseManager.async("lifesteal quests prune") { store.prunePvpKills(cutoff) }
    }

    // ── Boards ──────────────────────────────────────────────

    fun book(player: Player): Book? = books[player.uniqueId]

    fun onJoin(player: Player) {
        if (!running) return
        touch(player)
        load(player, announce = true)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (player.isOnline) deliverPayouts(player.uniqueId) }, 100L)
    }

    fun onQuit(player: Player) {
        val uuid = player.uniqueId
        val book = books.remove(uuid)
        loading.remove(uuid)
        busy.remove(uuid)
        clocks.remove(uuid)
        mobKills.remove(uuid)
        lastFish.remove(uuid)
        exploration.remove(uuid)
        hits.remove(uuid)
        menu.forget(uuid)
        if (book != null) save(uuid, book)
    }

    private fun hasTeam(uuid: UUID): Boolean = try { plugin.lifestealTeamManager.isInTeam(uuid) } catch (_: UninitializedPropertyAccessException) { false }

    /** Loads (and on a new cycle assigns) [player]'s boards on the database thread. */
    fun load(player: Player, announce: Boolean = false) {
        val s = settings ?: return
        val uuid = player.uniqueId
        if (!running || !loading.add(uuid)) return
        val team = hasTeam(uuid)
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("lifesteal quests load $uuid") {
            val result = runCatching {
                QuestPeriod.entries.associateWith { period ->
                    val cycle = QuestCycles.current(period, now, s)
                    val rows = store.ensureBoard(uuid, cycle, s.questsPerCycle.getValue(period), s.pool(period), team, now)
                    Board(cycle, rows.map { Live(it) }.toMutableList(), store.rerollUsage(uuid, cycle.id))
                }
            }
            Bukkit.getScheduler().runTask(plugin, Runnable { finishLoad(uuid, result, announce) })
        }
    }

    private fun finishLoad(uuid: UUID, result: Result<Map<QuestPeriod, Board>>, announce: Boolean) {
        loading.remove(uuid)
        if (!running) return
        val player = Bukkit.getPlayer(uuid) ?: return
        result.onFailure { e ->
            // Never start from an empty board after a failed read: retry instead.
            plugin.logger.warning("[LifestealQuests] Could not load ${player.name}'s quests (${e.message}); retrying in 30s.")
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (running && player.isOnline) load(player, announce) }, 600L)
            return
        }
        val boards = result.getOrThrow()
        val old = books[uuid]
        // Keep unsaved progress of quests that are still on the new boards (a reload of the same cycle).
        if (old != null) {
            val pending = drain(old)
            for (live in boards.values.flatMap { it.quests }) pending[live.id]?.let { if (it > live.progress) { live.progress = it; } }
            val book = Book(boards.toMutableMap())
            book.dirty += pending.keys.filter { id -> book.find(id) != null }
            books[uuid] = book
            if (pending.isNotEmpty()) plugin.databaseManager.async("lifesteal quests save $uuid") { runCatching { store.saveProgress(pending, System.currentTimeMillis()) } }
        } else {
            books[uuid] = Book(boards.toMutableMap())
        }
        menu.refresh(player)
        val claimable = books[uuid]?.all()?.count { it.state == LifestealQuestStore.State.COMPLETED } ?: 0
        if (announce && claimable > 0 && isLifesteal(player.world)) {
            send(player, "&eYou have &6$claimable &ecompleted quest${if (claimable == 1) "" else "s"} to claim. &7Open &e/quests&7.")
        }
    }

    /** Every 20s: a player whose board belongs to an ended cycle gets the new one. */
    private fun checkCycles() {
        val s = settings ?: return
        val now = System.currentTimeMillis()
        for ((uuid, book) in books.toMap()) {
            val stale = QuestPeriod.entries.any { p -> book.boards[p]?.cycle?.id != QuestCycles.current(p, now, s).id }
            if (!stale) continue
            save(uuid, book)
            Bukkit.getPlayer(uuid)?.let { load(it) }
        }
    }

    private fun drain(book: Book): HashMap<Long, Long> {
        val out = HashMap<Long, Long>()
        for (id in book.dirty) book.find(id)?.let { out[id] = it.progress }
        book.dirty.clear()
        return out
    }

    private fun save(uuid: UUID, book: Book) {
        val pending = drain(book)
        if (pending.isEmpty() || !tablesReady) return
        plugin.databaseManager.async("lifesteal quests save $uuid") {
            try {
                store.saveProgress(pending, System.currentTimeMillis())
            } catch (e: Exception) {
                plugin.logger.warning("[LifestealQuests] Saving quest progress of $uuid failed (${e.message}); will retry.")
                Bukkit.getScheduler().runTask(plugin, Runnable { books[uuid]?.dirty?.addAll(pending.keys) })
            }
        }
    }

    private fun flushAll() {
        for ((uuid, book) in books) if (book.dirty.isNotEmpty()) save(uuid, book)
    }

    // ── Eligibility ─────────────────────────────────────────

    /** Whether [player]'s activity counts at all: Lifesteal world, survival/adventure, not staff mode, events or Ranked. */
    fun eligible(player: Player, world: World = player.world): Boolean {
        if (!running || !player.isOnline) return false
        if (!isLifesteal(world)) return false
        if (player.gameMode != GameMode.SURVIVAL && player.gameMode != GameMode.ADVENTURE) return false
        if (runCatching { plugin.modModeManager.isModMode(player) }.getOrDefault(false)) return false
        if (runCatching { plugin.eventManager.isInEventWorld(player) }.getOrDefault(false)) return false
        if (runCatching { plugin.lifestealRankedManager.holds(player.uniqueId) }.getOrDefault(false)) return false
        return true
    }

    fun isIdle(player: Player): Boolean {
        val s = settings ?: return true
        if (runCatching { plugin.afkManager.isAfk(player) }.getOrDefault(false)) return true
        return clocks.getOrPut(player.uniqueId) { ActivityClock(s.antiAbuse.idleMillis) }.isIdle(System.currentTimeMillis())
    }

    internal fun touch(player: Player) {
        val s = settings ?: return
        clocks.getOrPut(player.uniqueId) { ActivityClock(s.antiAbuse.idleMillis) }.touch(System.currentTimeMillis())
    }

    /** An eligible, active teammate of [player] within the team radius. */
    fun teammateNearby(player: Player): Boolean {
        val s = settings ?: return false
        val team = runCatching { plugin.lifestealTeamManager.teamOf(player.uniqueId) }.getOrNull() ?: return false
        val radiusSq = s.antiAbuse.teamRadius * s.antiAbuse.teamRadius
        for (uuid in plugin.lifestealTeamManager.roster(team.id)) {
            if (uuid == player.uniqueId) continue
            val mate = Bukkit.getPlayer(uuid) ?: continue
            if (mate.world != player.world || mate.location.distanceSquared(player.location) > radiusSq) continue
            if (!eligible(mate) || isIdle(mate)) continue
            return true
        }
        return false
    }

    // ── Recording ───────────────────────────────────────────

    /** Live quests of [player] that [objective] + [tokens] would advance right now. */
    private fun candidates(player: Player, objective: QuestObjective, tokens: Set<String>): List<Live> {
        val book = books[player.uniqueId] ?: return emptyList()
        val now = System.currentTimeMillis()
        val team = hasTeam(player.uniqueId)
        return book.all().filter { live ->
            live.state == LifestealQuestStore.State.ACTIVE && live.row.objective == objective && now < live.row.cycleEnd &&
                (live.row.match.isEmpty() || tokens.any { it in live.row.match }) &&
                (live.row.team != TeamRequirement.REQUIRED || team)
        }
    }

    /** Adds [amount] (stored units) to every matching quest of [player]. */
    fun record(player: Player, objective: QuestObjective, tokens: Set<String> = emptySet(), amount: Long = objective.scale) {
        if (!running || amount <= 0) return
        add(player, candidates(player, objective, tokens), amount)
    }

    private fun add(player: Player, quests: List<Live>, amount: Long) {
        val book = books[player.uniqueId] ?: return
        val completed = ArrayList<Live>()
        for (live in quests) {
            if (live.state != LifestealQuestStore.State.ACTIVE) continue
            val before = live.progress
            live.progress = minOf(live.row.target, live.progress + amount)
            if (live.progress == before) continue
            book.dirty += live.id
            if (live.complete) {
                live.state = LifestealQuestStore.State.COMPLETED
                completed += live
            }
        }
        if (completed.isEmpty()) return
        save(player.uniqueId, book)
        val s = settings ?: return
        for (live in completed) {
            if (s.completionMessages) {
                send(player, s.messages.completed.replace("{quest}", live.row.name).replace("{period}", live.row.period.label))
            }
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f)
        }
        menu.refresh(player)
    }

    // ── Survival activity (called by LifestealQuestListener) ─

    fun onBlockBreak(player: Player, block: org.bukkit.block.Block) {
        if (!running) return
        val s = settings ?: return
        if (block.type.isAir) return
        if (!eligible(player, block.world)) {
            tracker.unmark(block)
            return
        }
        val name = block.type.name
        val placed = tracker.isPlaced(block)
        if (QuestBlocks.isOre(name)) {
            val correct = !s.antiAbuse.requireCorrectTool || block.isPreferredTool(player.inventory.itemInMainHand)
            if (!placed && correct) {
                record(player, QuestObjective.MINE_ORE, setOf(name))
                if (teammateNearby(player)) record(player, QuestObjective.TEAM_MINE_ORE, setOf(name))
            }
        }
        val ageable = block.blockData as? org.bukkit.block.data.Ageable
        // The Event Quests' rule: no-growth-stage plants only when not player-placed, everything else only fully grown.
        if (QuestBlocks.isCrop(name) && com.liam.joshymc.quest.HarvestRules.countsForEventQuest(name, ageable?.age, ageable?.maximumAge, placed)) {
            record(player, QuestObjective.HARVEST_CROP, setOf(com.liam.joshymc.quest.HarvestRules.normalize(name)))
        }
        if (!placed && name in tracker.tracked) record(player, QuestObjective.GATHER_BLOCK, setOf(name))
        tracker.unmark(block)
    }

    fun onFishCatch(player: Player, hookId: UUID, materialName: String?) {
        if (!running || materialName == null) return
        val s = settings ?: return
        if (countedHooks.put(hookId, true) != null) return
        if (!eligible(player) || isIdle(player)) return
        val now = System.currentTimeMillis()
        if (!com.liam.joshymc.milestone.FishingRules.intervalOk(lastFish[player.uniqueId], now, s.antiAbuse.fishingMinIntervalMillis)) return
        lastFish[player.uniqueId] = now
        val tokens = setOf(materialName, com.liam.joshymc.milestone.FishingRules.classify(materialName, false).token)
        record(player, QuestObjective.CATCH_FISH, tokens)
    }

    fun onMobKill(killer: Player, entityType: String, spawnReason: String) {
        if (!running) return
        val s = settings ?: return
        if (!eligible(killer) || isIdle(killer)) return
        if (spawnReason !in s.antiAbuse.mobSpawnReasons) return
        val window = mobKills.getOrPut(killer.uniqueId) { RateWindow(60_000L, s.antiAbuse.maxMobKillsPerMinute) }
        if (!window.tryAcquire(System.currentTimeMillis())) return
        record(killer, QuestObjective.KILL_MOB, setOf(entityType))
        if (teammateNearby(killer)) record(killer, QuestObjective.TEAM_MOB_KILL, setOf(entityType))
    }

    // ── Exploration ─────────────────────────────────────────

    internal fun explorationState(player: Player): Exploration =
        exploration.getOrPut(player.uniqueId) { Exploration().also { it.reset(player.location) } }

    fun invalidateExploration(player: Player) {
        exploration[player.uniqueId]?.reset(null)
    }

    private fun sampleExploration() {
        val s = settings ?: return
        val nowMillis = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            if (books[player.uniqueId] == null) continue
            try {
                sampleOne(player, s, nowMillis)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "[LifestealQuests] Exploration sample failed for ${player.name}", e)
            }
        }
    }

    private fun sampleOne(player: Player, s: QuestSettings, nowMillis: Long) {
        val state = explorationState(player)
        val location = player.location
        if (location.yaw != state.yaw || location.pitch != state.pitch) {
            state.yaw = location.yaw
            state.pitch = location.pitch
            touch(player)
        }
        val world = location.world ?: return
        val seconds = (System.nanoTime() - state.lastSampleNanos) / 1_000_000_000.0
        if (state.world != world.uid || !eligible(player, world)) {
            state.reset(location)
            return
        }
        val input = player.currentInput
        val moving = input.isForward || input.isBackward || input.isLeft || input.isRight
        val sample = ExplorationRules.Sample(
            dx = location.x - state.x,
            dz = location.z - state.z,
            seconds = seconds,
            inVehicle = player.isInsideVehicle,
            gliding = player.isGliding,
            flying = player.isFlying,
            riptiding = player.isRiptiding,
            swimming = player.isSwimming,
            inWater = player.isInWater,
            sprinting = player.isSprinting,
            movementInput = moving || nowMillis - state.lastInputAt <= SAMPLE_TICKS * 50L + 500L,
            idle = isIdle(player),
        )
        state.reset(location)
        val verdict = ExplorationRules.evaluate(sample, s.antiAbuse.exploreMaxBlocksPerSecond)
        if (verdict.reason != ExplorationRules.Reason.COUNTED) return
        if (state.loop.record(location.x, location.z, verdict.distance)) return
        val scaled = (verdict.distance * QuestObjective.EXPLORE.scale).toLong()
        record(player, QuestObjective.EXPLORE, emptySet(), scaled)
        if (teammateNearby(player)) record(player, QuestObjective.TEAM_EXPLORE, emptySet(), scaled)
    }

    // ── PvP ─────────────────────────────────────────────────

    private fun facts(victim: Player, attacker: Player): BountyRules.Death? = try {
        plugin.lifestealBountyManager.pvpFacts(victim, attacker)
    } catch (_: UninitializedPropertyAccessException) {
        null
    }

    /** Same environment rules as a bounty claim: Lifesteal, no events/arenas/Ranked, not teammates or allies, team data known. */
    private fun pvpEligible(victim: Player, attacker: Player): Boolean {
        val f = facts(victim, attacker) ?: return false
        if (BountyRules.eligibility(f) !is BountyRules.Eligibility.Eligible) return false
        return eligible(attacker) && eligible(victim)
    }

    /** A shared-connection verdict, cached; null (unknown) starts an async lookup and doesn't count yet. */
    private fun sharedConnection(a: UUID, b: UUID, s: QuestSettings): Boolean? {
        if (!s.antiAbuse.pvpBlockSharedConnections) return false
        val key = if (a.toString() < b.toString()) a to b else b to a
        connections[key]?.let { (shared, at) -> if (System.currentTimeMillis() - at < CONNECTION_CACHE_MILLIS) return shared }
        if (connectionLookups.add(key)) {
            plugin.databaseManager.async("lifesteal quests connection check") {
                val shared = store.sharesConnection(key.first, key.second)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    connectionLookups.remove(key)
                    if (shared != null) connections[key] = shared to System.currentTimeMillis()
                })
            }
        }
        return null
    }

    /** MONITOR on an uncancelled player-vs-player hit. [amount] = final damage (capped to the victim's health). */
    fun onPvpDamage(attacker: Player, victim: Player, amount: Double) {
        if (!running || amount <= 0.0 || !amount.isFinite()) return
        val s = settings ?: return
        if (!pvpEligible(victim, attacker)) return
        val now = System.currentTimeMillis()
        hits.getOrPut(victim.uniqueId) { HashMap() }[attacker.uniqueId] = now
        if (isIdle(victim) || isIdle(attacker)) return
        if (sharedConnection(attacker.uniqueId, victim.uniqueId, s) != false) return
        val window = damage.getOrPut(attacker.uniqueId to victim.uniqueId) { DamageWindow(now, 0) }
        if (now - window.start >= 3_600_000L) { window.start = now; window.counted = 0 }
        val tenths = (amount * 10).toLong()
        val counted = minOf(tenths, QuestPvpRules.damageAllowance(window.counted, s.antiAbuse.pvpDamageCapHeartsPerVictimPerHour))
        if (counted <= 0) return
        window.counted += counted
        record(attacker, QuestObjective.PVP_DAMAGE, emptySet(), counted)
    }

    /** MONITOR on a real Lifesteal death caused by [killer] (`damageSource.causingEntity`). */
    fun onPvpKill(killer: Player, victim: Player) {
        if (!running) return
        val s = settings ?: return
        if (killer.uniqueId == victim.uniqueId || !pvpEligible(victim, killer)) return
        val now = System.currentTimeMillis()
        val victimHits = hits.remove(victim.uniqueId).orEmpty()
        val pair = killer.uniqueId to victim.uniqueId
        val hourly = pvpKills.getOrPut(killer.uniqueId) { RateWindow(3_600_000L, s.antiAbuse.pvpMaxKillsPerHour) }
        val decision = QuestPvpRules.kill(
            teammates = false, // pvpEligible already refused teammates, allies and unknown teams
            victimIdle = isIdle(victim),
            lastKillOfVictimAt = lastPvp[pair],
            now = now,
            a = s.antiAbuse,
            killsInLastHour = hourly.count(now),
        )
        if (decision != CombatRules.PvpDecision.COUNT) return
        // Reserve now so a second kill can't slip in while the connection check runs.
        lastPvp[pair] = now
        hourly.add(now)
        val won = QuestPvpRules.victory(hits[killer.uniqueId]?.get(victim.uniqueId), now, s.antiAbuse.victoryWindowMillis)
        val teamKill = teammateNearby(killer)
        val assisters = runCatching { plugin.lifestealTeamManager.teamOf(killer.uniqueId) }.getOrNull()?.let { team ->
            plugin.lifestealTeamManager.roster(team.id).filter { it != killer.uniqueId && QuestPvpRules.assist(victimHits[it], now, s.antiAbuse.assistWindowMillis) }
        }.orEmpty()
        val uniqueIds = candidates(killer, QuestObjective.UNIQUE_PLAYER_KILL, emptySet()).map { it.id }
        val killerId = killer.uniqueId
        val victimId = victim.uniqueId
        plugin.databaseManager.async("lifesteal quests pvp kill") {
            val shared = if (s.antiAbuse.pvpBlockSharedConnections) store.sharesConnection(killerId, victimId) else false
            val final = QuestPvpRules.connection(shared, s.antiAbuse.pvpBlockSharedConnections)
            var unique = emptyList<Long>()
            if (final == CombatRules.PvpDecision.COUNT) {
                runCatching { store.recordPvpKill(killerId, victimId, now) }
                unique = uniqueIds.filter { runCatching { store.addUniqueVictim(it, victimId, now) }.getOrDefault(false) }
            } else {
                runCatching { store.audit(now, killerId, "PVP_REFUSED", "victim $victimId: $final") }
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (final != CombatRules.PvpDecision.COUNT) return@Runnable
                val online = Bukkit.getPlayer(killerId) ?: return@Runnable
                record(online, QuestObjective.PLAYER_KILL)
                if (won) record(online, QuestObjective.PVP_VICTORY)
                if (teamKill) record(online, QuestObjective.TEAM_PVP_KILL)
                val book = books[killerId]
                if (book != null && unique.isNotEmpty()) add(online, unique.mapNotNull { book.find(it) }, 1)
                for (uuid in assisters) {
                    val mate = Bukkit.getPlayer(uuid) ?: continue
                    if (eligible(mate) && !isIdle(mate) && victimId != uuid) record(mate, QuestObjective.TEAM_ASSIST)
                }
            })
        }
    }

    /** A paid Lifesteal bounty claim (issue #1401). Bounty claims already refuse teammates and repeat pairs; quests also refuse shared connections. */
    fun onBountyClaim(killer: UUID, victim: UUID) {
        if (!running) return
        val s = settings ?: return
        plugin.databaseManager.async("lifesteal quests bounty") {
            val shared = if (s.antiAbuse.pvpBlockSharedConnections) store.sharesConnection(killer, victim) else false
            val ok = QuestPvpRules.connection(shared, s.antiAbuse.pvpBlockSharedConnections) == CombatRules.PvpDecision.COUNT
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!ok) return@Runnable
                val online = Bukkit.getPlayer(killer) ?: return@Runnable
                if (isLifesteal(online.world)) record(online, QuestObjective.BOUNTY_CLAIM)
            })
        }
    }

    // ── Claims ──────────────────────────────────────────────

    sealed interface ActionResult {
        data class Done(val message: String) : ActionResult
        data class Refused(val message: String) : ActionResult
    }

    /**
     * Claims quest [assignmentId] (from the menu). Everything is re-checked in the store's
     * transaction; the menu's view is never trusted. Main thread.
     */
    fun claim(player: Player, assignmentId: Long): ActionResult {
        unavailableReason(player)?.let { return ActionResult.Refused(it) }
        val s = settings ?: return ActionResult.Refused("Lifesteal quests aren't running.")
        val uuid = player.uniqueId
        val book = books[uuid] ?: return ActionResult.Refused("Your quests are still loading. Try again in a moment.")
        val live = book.find(assignmentId) ?: return ActionResult.Refused(RerollRules.Refusal.NOT_FOUND.message)
        val now = System.currentTimeMillis()
        val cycle = QuestCycles.current(live.row.period, now, s)
        if (!busy.add(uuid)) return ActionResult.Refused("Your previous quest action is still being processed.")
        try {
            val result = try {
                store.claim(uuid, assignmentId, cycle.id, live.progress, player.name, now)
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "[LifestealQuests] Claim of #$assignmentId by ${player.name} failed; nothing was claimed", e)
                return ActionResult.Refused("Couldn't claim right now (nothing was claimed). Try again.")
            }
            return when (result) {
                is LifestealQuestStore.ClaimResult.Claimed -> {
                    live.progress = live.row.target
                    live.state = LifestealQuestStore.State.CLAIMED
                    book.dirty.remove(live.id)
                    val rewards = describeRewards(result.assignment.rewards)
                    result.payouts.forEach { enqueue(it) }
                    player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f)
                    ActionResult.Done(s.messages.claimed.replace("{quest}", result.assignment.name).replace("{rewards}", rewards))
                }
                LifestealQuestStore.ClaimResult.AlreadyClaimed -> { live.state = LifestealQuestStore.State.CLAIMED; ActionResult.Refused("You already claimed that quest.") }
                LifestealQuestStore.ClaimResult.Expired -> { load(player); ActionResult.Refused("That quest's cycle has ended, so it can't be claimed anymore.") }
                LifestealQuestStore.ClaimResult.NotComplete -> ActionResult.Refused("That quest isn't complete yet.")
                LifestealQuestStore.ClaimResult.Rerolled, LifestealQuestStore.ClaimResult.NotFound -> { load(player); ActionResult.Refused(RerollRules.Refusal.NOT_FOUND.message) }
            }
        } finally {
            busy.remove(uuid)
        }
    }

    /** Hands one payout to the store-delivery queue; repeating it is harmless (UNIQUE reference). */
    private fun enqueue(p: LifestealQuestStore.Payout): Boolean {
        val result = try {
            plugin.purchaseFulfillmentManager.enqueue(p.uuid, p.playerName, p.spec, reference = "lsquest:${p.id}", source = "$SOURCE_PREFIX${p.assignmentId}")
        } catch (e: Exception) {
            plugin.logger.severe("[LifestealQuests] Queueing payout #${p.id} (${p.spec.describe()} for ${p.playerName}) threw: ${e.message}; retried by the sweep.")
            return false
        }
        val delivery = when (result) {
            is PurchaseFulfillmentManager.FulfillResult.Recorded -> result.delivery
            is PurchaseFulfillmentManager.FulfillResult.Duplicate -> result.existing
            else -> {
                plugin.logger.warning("[LifestealQuests] Payout #${p.id} (${p.spec.describe()} for ${p.playerName}) couldn't be queued: $result; retried by the sweep.")
                return false
            }
        }
        try {
            store.link(p.id, delivery.id.toLong())
        } catch (e: Exception) {
            plugin.logger.warning("[LifestealQuests] Payout #${p.id} was queued as delivery #${delivery.id} but couldn't be linked: ${e.message}")
        }
        return true
    }

    /** Every minute (and at startup): payouts a crash or a queue error left unqueued. Runs while disabled too. */
    private fun sweepPayouts() {
        if (!processing) return
        val rows = try { store.unlinkedPayouts(null) } catch (e: Exception) {
            plugin.logger.warning("[LifestealQuests] Could not list unqueued payouts: ${e.message}")
            return
        }
        var queued = 0
        for (row in rows) if (enqueue(row)) queued++
        if (queued > 0) plugin.logger.info("[LifestealQuests] Queued $queued quest reward(s) left over from an earlier claim.")
    }

    private fun deliverPayouts(uuid: UUID) {
        if (!processing) return
        val rows = try { store.unlinkedPayouts(uuid) } catch (_: Exception) { return }
        rows.forEach { enqueue(it) }
    }

    // ── Rerolls ─────────────────────────────────────────────

    sealed interface Quote {
        data class Ok(val price: RerollRules.Price) : Quote
        data class No(val reason: String) : Quote
    }

    /** What rerolling [live] costs right now (for the menu); the store decides again on confirm. */
    fun quote(player: Player, live: Live): Quote {
        val s = settings ?: return Quote.No("Lifesteal quests aren't running.")
        val board = books[player.uniqueId]?.boards?.get(live.row.period) ?: return Quote.No("Your quests are still loading.")
        when (live.state) {
            LifestealQuestStore.State.CLAIMED -> return Quote.No(RerollRules.Refusal.CLAIMED.message)
            LifestealQuestStore.State.COMPLETED -> return Quote.No(RerollRules.Refusal.COMPLETED.message)
            LifestealQuestStore.State.REROLLED -> return Quote.No(RerollRules.Refusal.STALE.message)
            LifestealQuestStore.State.ACTIVE -> Unit
        }
        if (live.complete) return Quote.No(RerollRules.Refusal.COMPLETED.message)
        val ineligible = !live.row.team.allows(hasTeam(player.uniqueId))
        val price = RerollRules.price(s.rerolls.getValue(live.row.period), board.usage.free, board.usage.paid, ineligible, s.freeRerollWhenIneligible)
        if (price == RerollRules.Price.LimitReached) return Quote.No(RerollRules.Refusal.LIMIT.message)
        return Quote.Ok(price)
    }

    /** Confirmed reroll of [assignmentId] (which showed [expectedQuestId] at [expectedPrice]). Main thread. */
    fun reroll(player: Player, assignmentId: Long, expectedQuestId: String, expectedPrice: RerollRules.Price): ActionResult {
        unavailableReason(player)?.let { return ActionResult.Refused(it) }
        val s = settings ?: return ActionResult.Refused("Lifesteal quests aren't running.")
        val uuid = player.uniqueId
        val book = books[uuid] ?: return ActionResult.Refused("Your quests are still loading. Try again in a moment.")
        val live = book.find(assignmentId) ?: return ActionResult.Refused(RerollRules.Refusal.NOT_FOUND.message)
        val period = live.row.period
        val now = System.currentTimeMillis()
        if (!busy.add(uuid)) return ActionResult.Refused("Your previous quest action is still being processed.")
        try {
            // Save this quest's progress first: the store refuses rerolling a quest that is really complete.
            if (live.id in book.dirty) {
                book.dirty.remove(live.id)
                runCatching { store.saveProgress(mapOf(live.id to live.progress), now) }
            }
            val request = LifestealQuestStore.RerollRequest(
                uuid = uuid,
                assignmentId = assignmentId,
                expectedQuestId = expectedQuestId,
                cycle = QuestCycles.current(period, now, s),
                settings = s.rerolls.getValue(period),
                freeWhenIneligible = s.freeRerollWhenIneligible,
                hasTeam = hasTeam(uuid),
                expectedPrice = expectedPrice,
                pool = s.pool(period),
                now = now,
            )
            val result = try {
                store.reroll(request)
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "[LifestealQuests] Reroll of #$assignmentId by ${player.name} failed and was rolled back (no Credits taken)", e)
                return ActionResult.Refused("Couldn't reroll right now (no Credits were taken). Try again.")
            }
            return when (result) {
                is LifestealQuestStore.RerollResult.Refused -> {
                    if (result.reason == RerollRules.Refusal.EXPIRED || result.reason == RerollRules.Refusal.STALE || result.reason == RerollRules.Refusal.NOT_FOUND) load(player)
                    ActionResult.Refused(result.reason.message)
                }
                is LifestealQuestStore.RerollResult.Rerolled -> {
                    val board = book.boards[period]
                    if (board != null) {
                        val index = board.quests.indexOfFirst { it.id == assignmentId }
                        if (index >= 0) board.quests[index] = Live(result.replacement) else board.quests += Live(result.replacement)
                        board.usage = runCatching { store.rerollUsage(uuid, board.cycle.id) }.getOrDefault(board.usage)
                    }
                    val cost = when (val p = result.price) {
                        is RerollRules.Price.Credits -> " for ${plugin.creditsManager.format(p.amount)} Credits"
                        else -> " for free"
                    }
                    ActionResult.Done(s.messages.rerolled.replace("{old}", result.old.name).replace("{new}", result.replacement.name).replace("{cost}", cost))
                }
            }
        } finally {
            busy.remove(uuid)
        }
    }

    // ── Text ────────────────────────────────────────────────

    fun rewardLabel(spec: PurchaseRewards.Spec): String = when (spec.kind) {
        PurchaseRewards.Kind.COINS -> plugin.economyManager.format(spec.param.toDoubleOrNull() ?: 0.0)
        PurchaseRewards.Kind.CREDITS -> "${plugin.creditsManager.format(spec.param.toDoubleOrNull() ?: 0.0)} Credits"
        PurchaseRewards.Kind.CRATE_KEY -> {
            val key = runCatching { plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(spec.param))?.keyName }.getOrNull()
            "${spec.quantity}x ${key?.let { plain(it) } ?: "${spec.param} key"}"
        }
        PurchaseRewards.Kind.CHAT_TAG_VOUCHER -> "${spec.quantity}x Chat Tag Voucher (${spec.param})"
        else -> spec.describe()
    }

    fun describeRewards(rewards: List<PurchaseRewards.Spec>): String = rewards.joinToString(", ") { rewardLabel(it) }

    fun plain(legacy: String): String =
        net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(plugin.commsManager.parseLegacy(legacy))

    fun send(player: Player, legacy: String) {
        if (player.isOnline) plugin.commsManager.send(player, plugin.commsManager.parseLegacy(legacy))
    }
}

/** Which blocks count as what for the Lifesteal quests (issue #1425), Bukkit-free. */
object QuestBlocks {
    /** Blocks generators make (lava + water); remembered so they never count as natural. */
    val GENERATED: Set<String> = com.liam.joshymc.milestone.MiningRules.GENERATED

    /** Crops/plants with no growth stage: placing and breaking them must never count. */
    val PLACEMENT_MATTERS: Set<String> = com.liam.joshymc.quest.HarvestRules.EVENT_ALWAYS_HARVESTABLE + setOf("KELP_PLANT", "TWISTING_VINES_PLANT", "WEEPING_VINES_PLANT")

    private val AGE_CROPS: Set<String> = setOf("WHEAT", "CARROTS", "POTATOES", "BEETROOTS", "NETHER_WART", "COCOA", "TORCHFLOWER_CROP", "PITCHER_CROP", "SWEET_BERRY_BUSH")

    fun isOre(name: String): Boolean = name.endsWith("_ORE") || name == "ANCIENT_DEBRIS"

    fun isCrop(name: String): Boolean = name in AGE_CROPS || com.liam.joshymc.quest.HarvestRules.normalize(name) in com.liam.joshymc.quest.HarvestRules.EVENT_ALWAYS_HARVESTABLE
}
