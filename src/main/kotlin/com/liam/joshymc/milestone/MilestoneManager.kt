package com.liam.joshymc.milestone

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.GamemodeFeature
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.util.depositItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.io.InputStreamReader
import java.util.Base64
import java.util.UUID
import java.util.logging.Level

/**
 * Milestones (issue #1420, Survival only): infinite per-player progression in five categories,
 * opened with `/milestones`.
 *
 * Progress lives in memory per online player ([MilestoneBook]) and is saved in batches (every 30s,
 * on quit, at shutdown) through [MilestoneStore], whose saves can never undo a claim. Claims run
 * synchronously on the main thread as one database transaction (claim row, next tier, Coins and
 * Credits, pending item rows), then the items go to the inventory from the pending rows; whatever
 * doesn't fit stays in Pending Rewards. Nothing is ever dropped on the ground.
 *
 * Trackers feed [record] through [MilestoneListener]; Veinminer, the Void Drill, Tree Feller, the
 * Explosive enchant and FishingManager's fallback catches call [Joshymc.milestoneBlockBreak] /
 * [Joshymc.milestoneFishCatch].
 */
class MilestoneManager(private val plugin: Joshymc) {

    companion object {
        const val FILE_NAME = "milestones.yml"
        private const val FLUSH_TICKS = 20L * 30
        private const val PRUNE_TICKS = 20L * 60 * 10
        private const val HOOK_MEMORY = 512
    }

    val store = MilestoneStore(plugin.databaseManager)
    val tracker = PlacedBlockTracker(plugin)
    val menus = MilestoneMenus(plugin, this)

    @Volatile var settings: MilestoneSettings? = null
        private set
    var running = false
        private set

    private var listener: MilestoneListener? = null
    private val tasks = ArrayList<BukkitTask>()

    private val books = HashMap<UUID, MilestoneBook>()
    private val loading = HashSet<UUID>()
    private val claiming = HashSet<UUID>()
    /** Rows whose save failed after the player left, retried on the next flush. */
    private val retrySaves = HashMap<UUID, List<MilestoneStore.Row>>()

    internal val clocks = HashMap<UUID, ActivityClock>()
    private val mobKills = HashMap<UUID, RateWindow>()
    private val pvpKills = HashMap<UUID, RateWindow>()
    private val lastPvp = HashMap<Pair<UUID, UUID>, Long>()
    private val lastFishCounted = HashMap<UUID, Long>()
    private val countedHooks = object : LinkedHashMap<UUID, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?) = size > HOOK_MEMORY
    }
    internal val exploration = HashMap<UUID, ExplorationState>()

    internal class ExplorationState(window: Int, minDistance: Double, maxSpan: Double) {
        var world: UUID? = null
        var x = 0.0
        var z = 0.0
        var yaw = 0f
        var pitch = 0f
        var lastSampleNanos = 0L
        var lastInputAt = 0L
        val loop = LoopDetector(window, minDistance, maxSpan)

        fun reset(location: Location?) {
            world = location?.world?.uid
            x = location?.x ?: 0.0
            z = location?.z ?: 0.0
            lastSampleNanos = System.nanoTime()
        }
    }

    // ── Lifecycle ───────────────────────────────────────────

    fun start() {
        if (running) return
        val loaded = loadSettings()
        settings = loaded
        for (problem in loaded.problems) plugin.logger.warning("[Milestones] $problem")
        if (!loaded.enabled) {
            plugin.logger.info("[Milestones] Disabled in $FILE_NAME.")
            return
        }
        store.createTables()
        tracker.tracked = trackedMaterials(loaded.catalog)
        loadPvpCooldowns(loaded)

        val l = MilestoneListener(plugin, this)
        com.liam.joshymc.gamemode.GamemodeScopedListeners.register(plugin, l, GamemodeFeature.MILESTONES)
        listener = l
        running = true

        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flushAll() }, FLUSH_TICKS, FLUSH_TICKS)
        val sample = loaded.sampleSeconds * 20L
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { sampleExploration() }, sample, sample)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { prune() }, PRUNE_TICKS, PRUNE_TICKS)

        for (player in Bukkit.getOnlinePlayers()) onJoin(player)
        val objectives = loaded.catalog.categories.sumOf { it.objectives.size }
        plugin.logger.info("[Milestones] Started: ${loaded.catalog.categories.size} categories, $objectives objectives.")
    }

    /** Saves every online player's progress synchronously and stops tracking. */
    fun stop() {
        if (!running) return
        running = false
        for (task in tasks) task.cancel()
        tasks.clear()
        listener?.let { HandlerList.unregisterAll(it) }
        listener = null
        val now = System.currentTimeMillis()
        for ((uuid, book) in books) {
            val rows = book.drainDirty().map { MilestoneStore.Row(it.key, it.tier, it.progress) }
            if (rows.isEmpty()) continue
            try {
                store.saveProgress(uuid, rows, now)
            } catch (e: Exception) {
                plugin.logger.severe("[Milestones] Could not save progress of $uuid on stop: ${e.message}")
            }
        }
        for ((uuid, rows) in retrySaves) {
            try { store.saveProgress(uuid, rows, now) } catch (e: Exception) {
                plugin.logger.severe("[Milestones] Could not save retried progress of $uuid on stop: ${e.message}")
            }
        }
        retrySaves.clear()
        books.clear()
        loading.clear()
        claiming.clear()
        clocks.clear()
        mobKills.clear()
        pvpKills.clear()
        lastPvp.clear()
        lastFishCounted.clear()
        countedHooks.clear()
        exploration.clear()
        tracker.clear()
        menus.closeAll()
    }

    private fun loadSettings(): MilestoneSettings {
        val file = plugin.configFile(FILE_NAME)
        if (!file.exists()) {
            try {
                plugin.saveResource(FILE_NAME, false)
            } catch (e: IllegalArgumentException) {
                plugin.logger.warning("[Milestones] $FILE_NAME is missing from the jar.")
            }
        }
        val yaml = YamlConfiguration.loadConfiguration(file)
        plugin.getResource(FILE_NAME)?.use { stream ->
            yaml.setDefaults(YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8)))
        }
        val source = object : MilestoneSource {
            override fun isSet(path: String) = yaml.contains(path)
            override fun get(path: String): Any? = yaml.get(path)
            override fun keys(path: String): List<String> = yaml.getConfigurationSection(path)?.getKeys(false)?.toList() ?: emptyList()
        }
        val catalog = object : MilestoneRewards.Catalog {
            override fun crateExists(id: String) = plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(id)) != null
            override fun chatTagExists(id: String) = plugin.chatTagManager.getVoucherTag(id) != null
            override fun customItemExists(id: String) = plugin.itemManager.getItem(id) != null
        }
        return MilestoneSettingsParser.parse(source, catalog)
    }

    private fun trackedMaterials(catalog: MilestoneCatalog): Set<String> {
        val out = HashSet<String>()
        out += catalog.tokens(MilestoneCategory.MINING)
        out += MiningRules.GENERATED
        for (name in listOf("SUGAR_CANE", "CACTUS", "BAMBOO", "KELP", "KELP_PLANT", "MELON", "PUMPKIN", "CHORUS_PLANT", "CHORUS_FLOWER")) {
            if (FarmingRules.placementMatters(name)) out += name
        }
        return out
    }

    private fun loadPvpCooldowns(s: MilestoneSettings) {
        if (!s.pvpEnabled || s.pvpCooldownMillis <= 0) return
        try {
            for ((killer, victim, at) in store.pvpKillsSince(System.currentTimeMillis() - s.pvpCooldownMillis)) {
                lastPvp[killer to victim] = at
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Milestones] Could not load PvP cooldowns: ${e.message}")
        }
    }

    // ── Players ─────────────────────────────────────────────

    fun book(player: Player): MilestoneBook? = books[player.uniqueId]

    fun onJoin(player: Player) {
        if (!running) return
        clocks.getOrPut(player.uniqueId) { ActivityClock(settings!!.idleMillis) }.touch(System.currentTimeMillis())
        load(player.uniqueId)
    }

    private fun load(uuid: UUID) {
        if (uuid in books || !loading.add(uuid)) return
        plugin.databaseManager.async("milestones load $uuid") {
            val result = runCatching { store.load(uuid) to store.pendingCount(uuid) }
            Bukkit.getScheduler().runTask(plugin, Runnable { finishLoad(uuid, result) })
        }
    }

    private fun finishLoad(uuid: UUID, result: Result<Pair<Map<String, Pair<Int, Long>>, Int>>) {
        loading.remove(uuid)
        val s = settings ?: return
        if (!running) return
        val player = Bukkit.getPlayer(uuid) ?: return
        result.onFailure { e ->
            // Never start from an empty book after a failed read: a later save would overwrite real progress.
            plugin.logger.warning("[Milestones] Could not load ${player.name}'s progress (${e.message}); retrying in 30s.")
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (running && player.isOnline) load(uuid) }, 600L)
            return
        }
        // An older load finishing after a newer one must never replace a live book.
        if (uuid in books) return
        val (rows, pending) = result.getOrThrow()
        books[uuid] = MilestoneBook(s.catalog).also { it.load(rows) }
        if (pending > 0) {
            plugin.commsManager.send(
                player,
                Component.text("You have $pending pending milestone reward${if (pending == 1) "" else "s"}. ", NamedTextColor.YELLOW)
                    .append(Component.text("/milestones", NamedTextColor.GOLD))
            )
        }
    }

    fun onQuit(player: Player) {
        val uuid = player.uniqueId
        val book = books.remove(uuid)
        loading.remove(uuid)
        clocks.remove(uuid)
        mobKills.remove(uuid)
        pvpKills.remove(uuid)
        lastFishCounted.remove(uuid)
        exploration.remove(uuid)
        claiming.remove(uuid)
        if (book != null) save(uuid, book)
    }

    private fun save(uuid: UUID, book: MilestoneBook) {
        val rows = book.drainDirty().map { MilestoneStore.Row(it.key, it.tier, it.progress) }
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("milestones save $uuid") {
            try {
                store.saveProgress(uuid, rows, now)
            } catch (e: Exception) {
                plugin.logger.warning("[Milestones] Saving progress of $uuid failed (${e.message}); will retry.")
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    val live = books[uuid]
                    if (live != null) live.markDirty(rows.map { it.key }) else retrySaves[uuid] = rows
                })
            }
        }
    }

    private fun flushAll() {
        for ((uuid, book) in books) if (book.hasDirty) save(uuid, book)
        if (retrySaves.isNotEmpty()) {
            val pending = HashMap(retrySaves)
            retrySaves.clear()
            val now = System.currentTimeMillis()
            for ((uuid, rows) in pending) {
                plugin.databaseManager.async("milestones retry $uuid") {
                    try { store.saveProgress(uuid, rows, now) } catch (e: Exception) {
                        Bukkit.getScheduler().runTask(plugin, Runnable { retrySaves.putIfAbsent(uuid, rows) })
                    }
                }
            }
        }
    }

    private fun prune() {
        val s = settings ?: return
        val cutoff = System.currentTimeMillis() - s.pvpCooldownMillis
        lastPvp.values.removeIf { it < cutoff }
        plugin.databaseManager.async("milestones prune pvp") { store.prunePvpKills(cutoff) }
    }

    // ── Eligibility ─────────────────────────────────────────

    fun eligible(player: Player, world: World = player.world): Boolean {
        val s = settings ?: return false
        if (!running) return false
        return MilestoneWorlds.check(
            worldName = world.name,
            worldGamemodeSurvival = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.SURVIVAL,
            featureAllowed = plugin.gamemodeLockManager.isAllowed(GamemodeFeature.MILESTONES, world),
            allowedWorlds = s.allowedWorlds,
            excludedWorlds = s.excludedWorlds,
            inEventWorld = plugin.eventManager.isInEventWorld(player),
            playerGameMode = player.gameMode.name,
            staffMode = plugin.modModeManager.isModMode(player),
        ) == MilestoneWorlds.Verdict.OK
    }

    fun isIdle(player: Player): Boolean = clocks[player.uniqueId]?.isIdle(System.currentTimeMillis()) ?: true

    internal fun touch(player: Player) {
        val s = settings ?: return
        clocks.getOrPut(player.uniqueId) { ActivityClock(s.idleMillis) }.touch(System.currentTimeMillis())
    }

    // ── Recording ───────────────────────────────────────────

    /** Adds an activity for [player]. Silently nothing while their progress is still loading. */
    fun record(player: Player, category: MilestoneCategory, tokens: Set<String>, amountScaled: Long) {
        val book = books[player.uniqueId] ?: return
        val result = book.record(category, tokens, amountScaled)
        for (key in result.completed) notifyCompleted(player, book, key)
    }

    private fun notifyCompleted(player: Player, book: MilestoneBook, key: String) {
        val s = settings ?: return
        val text = s.completedMessage
            .replace("{milestone}", s.catalog.displayName(key))
            .replace("{tier}", book.tier(key).toString())
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(text))
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f)
    }

    /** Farming and mining for one block about to be broken (BlockBreakEvent or a multi-break tool). */
    fun processBreak(player: Player, block: Block) {
        if (!running) return
        val s = settings ?: return
        val name = block.type.name
        if (block.type.isAir) return
        if (!eligible(player, block.world)) {
            tracker.unmark(block)
            return
        }
        val farming = FarmingRules.kind(name)
        if (farming != null) {
            val data = block.blockData
            val ageable = data as? org.bukkit.block.data.Ageable
            val berries = (data as? org.bukkit.block.data.type.CaveVinesPlant)?.isBerries
            var count = if (FarmingRules.countsBreak(name, ageable?.age, ageable?.maximumAge, berries, tracker.isPlaced(block))) 1 else 0
            if (farming == FarmingRules.Kind.COLUMN) {
                val family = FarmingRules.token(name)
                var above = block.getRelative(org.bukkit.block.BlockFace.UP)
                var steps = 0
                while (steps < FarmingRules.COLUMN_MAX && FarmingRules.token(above.type.name) == family) {
                    if (!tracker.isPlaced(above)) count++
                    tracker.unmark(above)
                    above = above.getRelative(org.bukkit.block.BlockFace.UP)
                    steps++
                }
            }
            if (count > 0) record(player, MilestoneCategory.FARMING, setOf(FarmingRules.token(name)), count * MilestoneMath.SCALE)
        } else if (name in s.catalog.tokens(MilestoneCategory.MINING)) {
            val correct = !s.requireCorrectTool || block.isPreferredTool(player.inventory.itemInMainHand)
            if (MiningRules.countsBreak(tracker.isPlaced(block), correct)) {
                record(player, MilestoneCategory.MINING, setOf(name), MilestoneMath.SCALE)
            }
        }
        tracker.unmark(block)
    }

    /** A right-click harvest (sweet berries, glow berries). */
    fun processHarvest(player: Player, block: Block) {
        if (!running || !eligible(player, block.world)) return
        val name = block.type.name
        if (!FarmingRules.countsRightClickHarvest(name)) return
        record(player, MilestoneCategory.FARMING, setOf(FarmingRules.token(name)), MilestoneMath.SCALE)
    }

    /** One fishing catch on [hookId]; [materialName] is the vanilla catch (read before FishingManager swaps it). */
    fun processCatch(player: Player, hookId: UUID, hookLocation: Location, materialName: String?, enchanted: Boolean) {
        if (!running || materialName == null) return
        val s = settings ?: return
        if (countedHooks.put(hookId, true) != null) return
        if (!eligible(player) || isIdle(player)) return
        val now = System.currentTimeMillis()
        if (!FishingRules.intervalOk(lastFishCounted[player.uniqueId], now, s.fishingMinIntervalMillis)) return
        lastFishCounted[player.uniqueId] = now
        val world = hookLocation.world ?: player.world
        val biome = runCatching { hookLocation.block.biome.key.key }.getOrDefault("")
        val tokens = FishingRules.tokens(materialName, enchanted, biome, world.hasStorm(), world.time)
        record(player, MilestoneCategory.FISHING, tokens, MilestoneMath.SCALE)
    }

    /** A mob killed by [killer] (melee or their own projectile). */
    fun processMobKill(killer: Player, entityType: String, spawnReason: String) {
        if (!running) return
        val s = settings ?: return
        if (!eligible(killer) || isIdle(killer)) return
        val amount = when (CombatRules.weight(spawnReason, s.fullSpawnReasons, s.reducedSpawnReasons)) {
            CombatRules.Weight.FULL -> MilestoneMath.SCALE
            CombatRules.Weight.REDUCED -> MilestoneMath.spawnerKillScaled(s.spawnerKillsPerUnit)
            CombatRules.Weight.NONE -> return
        }
        if (s.catalog.matching(MilestoneCategory.COMBAT, setOf(entityType)).isEmpty()) return
        val window = mobKills.getOrPut(killer.uniqueId) { RateWindow(60_000L, s.maxMobKillsPerMinute) }
        if (!window.tryAcquire(System.currentTimeMillis())) return
        record(killer, MilestoneCategory.COMBAT, setOf(entityType), amount)
    }

    /** A player kill. Cheap checks here; the shared-connection check runs on the database thread. */
    fun processPlayerKill(killer: Player, victim: Player) {
        if (!running) return
        val s = settings ?: return
        if (!s.pvpEnabled) return
        if (!eligible(killer) || !eligible(victim) || isIdle(killer)) return
        val now = System.currentTimeMillis()
        val pair = killer.uniqueId to victim.uniqueId
        val hourly = pvpKills.getOrPut(killer.uniqueId) { RateWindow(3_600_000L, s.pvpMaxPerHour) }
        val decision = CombatRules.pvp(
            sameAccount = killer.uniqueId == victim.uniqueId,
            teammates = runCatching { plugin.teamManager.isTeammate(killer.uniqueId, victim.uniqueId) }.getOrDefault(true),
            victimIdle = isIdle(victim),
            lastKillOfVictimAt = lastPvp[pair],
            now = now,
            cooldownMillis = s.pvpCooldownMillis,
            killsInLastHour = hourly.count(now),
            maxPerHour = s.pvpMaxPerHour,
        )
        if (decision != CombatRules.PvpDecision.COUNT) return
        // Reserve now so a second kill can't slip in while the connection check runs.
        lastPvp[pair] = now
        hourly.add(now)
        val killerId = killer.uniqueId
        val victimId = victim.uniqueId
        plugin.databaseManager.async("milestones pvp check") {
            val shared = if (s.pvpBlockSharedConnections) store.sharesConnection(killerId, victimId) else false
            val final = CombatRules.connection(shared)
            if (final == CombatRules.PvpDecision.COUNT) {
                runCatching { store.recordPvpKill(killerId, victimId, now) }
            }
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (final != CombatRules.PvpDecision.COUNT) return@Runnable
                val online = Bukkit.getPlayer(killerId) ?: return@Runnable
                record(online, MilestoneCategory.COMBAT, setOf("PLAYER"), MilestoneMath.SCALE)
            })
        }
    }

    // ── Exploration ─────────────────────────────────────────

    internal fun explorationState(player: Player): ExplorationState {
        val s = settings!!
        return exploration.getOrPut(player.uniqueId) {
            ExplorationState(s.loopWindow, s.loopMinDistance, s.loopMaxSpan).also { it.reset(player.location) }
        }
    }

    fun invalidateExploration(player: Player) {
        exploration[player.uniqueId]?.reset(null)
    }

    private fun sampleExploration() {
        val s = settings ?: return
        val nowMillis = System.currentTimeMillis()
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                sampleOne(player, s, nowMillis)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "[Milestones] Exploration sample failed for ${player.name}", e)
            }
        }
    }

    private fun sampleOne(player: Player, s: MilestoneSettings, nowMillis: Long) {
        val state = explorationState(player)
        val location = player.location
        if (location.yaw != state.yaw || location.pitch != state.pitch) {
            state.yaw = location.yaw
            state.pitch = location.pitch
            touch(player)
        }
        val world = location.world ?: return
        val now = System.nanoTime()
        val seconds = (now - state.lastSampleNanos) / 1_000_000_000.0
        if (state.world != world.uid || books[player.uniqueId] == null || !eligible(player, world)) {
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
            movementInput = moving || nowMillis - state.lastInputAt <= s.sampleSeconds * 1000L + 500L,
            idle = isIdle(player),
        )
        state.reset(location)
        val verdict = ExplorationRules.evaluate(sample, s.maxBlocksPerSecond)
        if (verdict.reason != ExplorationRules.Reason.COUNTED) return
        if (state.loop.record(location.x, location.z, verdict.distance)) return
        val tokens = HashSet<String>(4)
        verdict.movement?.let { tokens += it.token }
        when (world.environment) {
            World.Environment.NORMAL -> {
                tokens += "OVERWORLD"
                if (location.block.lightFromSky.toInt() == 0) tokens += "UNDERGROUND"
                if (location.y >= 120.0) tokens += "HIGHLANDS"
            }
            World.Environment.NETHER -> tokens += "NETHER"
            World.Environment.THE_END -> tokens += "THE_END"
            else -> {}
        }
        val scaled = (verdict.distance * MilestoneMath.SCALE).toLong()
        record(player, MilestoneCategory.EXPLORATION, tokens, scaled)
    }

    // ── Claims ──────────────────────────────────────────────

    sealed class ClaimResult {
        data class Claimed(val key: String, val tier: Int, val rewards: MilestoneRewards.TierRewards, val stored: Int) : ClaimResult()
        data class Refused(val reason: String) : ClaimResult()
    }

    /**
     * Claims the completed tier of [key]. Everything is re-checked here (never trusts the GUI). The
     * claim row, next tier, Coins, Credits and pending item rows commit together or not at all.
     */
    fun claim(player: Player, key: String, quiet: Boolean = false): ClaimResult {
        val s = settings ?: return ClaimResult.Refused("Milestones aren't running.")
        if (!running) return ClaimResult.Refused("Milestones aren't running.")
        if (plugin.gamemodeLockManager.gamemodeOf(player) != ServerGamemode.SURVIVAL) return ClaimResult.Refused("Milestones are only available in Survival.")
        val uuid = player.uniqueId
        val book = books[uuid] ?: return ClaimResult.Refused("Your milestones are still loading. Try again in a moment.")
        if (!s.catalog.isKnown(key)) return ClaimResult.Refused("That milestone doesn't exist.")
        if (!book.isClaimable(key)) return ClaimResult.Refused("That milestone isn't complete yet.")
        if (!claiming.add(uuid)) return ClaimResult.Refused("Already claiming.")
        try {
            val tier = book.tier(key)
            val rewards = s.catalog.rewardsFor(key, tier) ?: return ClaimResult.Refused("That milestone doesn't exist.")
            val items = ArrayList<MilestoneStore.PendingItem>()
            for (spec in rewards.physical) {
                val built = buildItems(spec)
                if (built == null) {
                    plugin.logger.warning("[Milestones] ${player.name}: reward '${spec.describe()}' of $key tier $tier can't be built (removed crate/tag/item?). Claim refused.")
                    return ClaimResult.Refused("A reward of this tier is unavailable right now. Please tell staff.")
                }
                items += built
            }
            val coins = rewards.totalCoins
            val credits = rewards.totalCredits
            val outcome = try {
                store.claim(uuid, key, tier, coins, credits, describe(rewards), items, System.currentTimeMillis()) {
                    if (coins > 0.0 && !plugin.economyManager.deposit(uuid, coins)) error("coin deposit refused")
                    if (credits > 0.0 && !plugin.creditsManager.deposit(uuid, credits)) error("credit deposit refused")
                }
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "[Milestones] Claim of $key tier $tier for ${player.name} failed; nothing was paid", e)
                return ClaimResult.Refused("Couldn't claim right now (nothing was taken or paid). Try again.")
            }
            when (outcome) {
                is MilestoneStore.ClaimOutcome.Claimed -> {
                    book.applyClaim(key, tier)
                    val stored = deliverRows(player, outcome.pendingIds)
                    afterClaim(player, key, tier, rewards, stored, quiet)
                    return ClaimResult.Claimed(key, tier, rewards, stored)
                }
                MilestoneStore.ClaimOutcome.AlreadyClaimed, MilestoneStore.ClaimOutcome.Stale -> {
                    plugin.logger.warning("[Milestones] ${player.name}: $key tier $tier was already claimed or moved on; reloading their progress.")
                    books.remove(uuid)?.let { save(uuid, it) }
                    load(uuid)
                    return ClaimResult.Refused("That tier was already claimed.")
                }
            }
        } finally {
            claiming.remove(uuid)
        }
    }

    /** Claims everything complete in [category] (Level first), each tier its own transaction. */
    fun claimAll(player: Player, category: MilestoneCategory): List<ClaimResult> {
        val book = books[player.uniqueId] ?: return listOf(ClaimResult.Refused("Your milestones are still loading."))
        val results = ArrayList<ClaimResult>()
        for (key in book.claimable(category)) {
            val result = claim(player, key, quiet = true)
            results += result
            if (result is ClaimResult.Refused) break
        }
        val claimed = results.filterIsInstance<ClaimResult.Claimed>()
        if (claimed.isNotEmpty()) {
            val coins = claimed.sumOf { it.rewards.totalCoins }
            val credits = claimed.sumOf { it.rewards.totalCredits }
            val stored = claimed.sumOf { it.stored }
            var line = Component.text("Claimed ${claimed.size} milestone tier${if (claimed.size == 1) "" else "s"}: ", NamedTextColor.GREEN)
                .append(Component.text(plugin.economyManager.format(coins), NamedTextColor.GOLD))
            if (credits > 0) line = line.append(Component.text(" + ${plugin.creditsManager.format(credits)} Credits", NamedTextColor.AQUA))
            plugin.commsManager.send(player, line)
            if (stored > 0) storedNotice(player, stored)
        }
        return results
    }

    private fun afterClaim(player: Player, key: String, tier: Int, rewards: MilestoneRewards.TierRewards, stored: Int, quiet: Boolean) {
        val s = settings ?: return
        val name = s.catalog.displayName(key)
        if (!quiet) {
            val text = s.claimedMessage
                .replace("{milestone}", name)
                .replace("{tier}", tier.toString())
                .replace("{rewards}", describe(rewards))
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy(text))
            if (stored > 0) storedNotice(player, stored)
        }
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.2f)
        // Once per tier: the claim row is unique, so this line runs once for it ever.
        if (s.announcementsEnabled && MilestoneMath.isAnnounced(tier)) {
            val text = s.broadcastMessage
                .replace("{player}", player.name)
                .replace("{milestone}", name)
                .replace("{tier}", tier.toString())
            plugin.commsManager.broadcast(plugin.commsManager.parseLegacy(text))
        }
    }

    private fun storedNotice(player: Player, stored: Int) {
        plugin.commsManager.send(
            player,
            Component.text("$stored reward item stack${if (stored == 1) "" else "s"} didn't fit and wait in ", NamedTextColor.YELLOW)
                .append(Component.text("/milestones → Pending Rewards", NamedTextColor.GOLD))
        )
    }

    fun describe(rewards: MilestoneRewards.TierRewards): String {
        val parts = ArrayList<String>()
        if (rewards.totalCoins > 0) parts += plugin.economyManager.format(rewards.totalCoins)
        if (rewards.totalCredits > 0) parts += "${plugin.creditsManager.format(rewards.totalCredits)} Credits"
        for (spec in rewards.physical) parts += rewardLabel(spec)
        return parts.joinToString(", ")
    }

    fun rewardLabel(spec: MilestoneRewards.Spec): String = when (spec.kind) {
        MilestoneRewards.Kind.COINS -> plugin.economyManager.format(spec.amount)
        MilestoneRewards.Kind.CREDITS -> "${plugin.creditsManager.format(spec.amount)} Credits"
        MilestoneRewards.Kind.CRATE_KEY ->
            "${spec.quantity}x ${plugin.crateManager.getCrate(plugin.crateManager.resolveCrateId(spec.param))?.keyName ?: "${spec.param} key"}"
        MilestoneRewards.Kind.CHAT_TAG -> "Chat Tag voucher (${spec.param})"
        MilestoneRewards.Kind.ITEM -> "${spec.quantity}x ${spec.param}"
    }

    /** The reward as stacks (split by max stack size), serialized; null when it can't be built. */
    private fun buildItems(spec: MilestoneRewards.Spec): List<MilestoneStore.PendingItem>? {
        val unit: ItemStack = when (spec.kind) {
            MilestoneRewards.Kind.CRATE_KEY -> plugin.crateManager.createKeyStack(plugin.crateManager.resolveCrateId(spec.param), 1)
            MilestoneRewards.Kind.CHAT_TAG -> plugin.chatTagVoucherManager.createVoucherStack(spec.param)
            MilestoneRewards.Kind.ITEM -> plugin.itemManager.getItem(spec.param)?.createItemStack(1)
            else -> null
        } ?: return null
        val label = rewardLabel(spec.copy(amount = 1.0)).removePrefix("1x ")
        val out = ArrayList<MilestoneStore.PendingItem>()
        var remaining = spec.quantity.coerceAtLeast(1)
        val max = unit.maxStackSize.coerceAtLeast(1)
        while (remaining > 0) {
            val n = minOf(max, remaining)
            out += MilestoneStore.PendingItem(label, encode(unit.clone().apply { amount = 1 }), n)
            remaining -= n
        }
        return out
    }

    private fun encode(item: ItemStack): String = Base64.getEncoder().encodeToString(item.serializeAsBytes())

    private fun decode(data: String, amount: Int): ItemStack? = try {
        ItemStack.deserializeBytes(Base64.getDecoder().decode(data)).apply { this.amount = amount }
    } catch (e: Exception) {
        null
    }

    // ── Pending rewards ─────────────────────────────────────

    /** Delivers the given pending rows; returns how many stayed pending. */
    private fun deliverRows(player: Player, ids: List<Long>): Int {
        var stored = 0
        for (id in ids) {
            val row = try { store.pending(player.uniqueId, id) } catch (e: Exception) { null } ?: continue
            if (!deliver(player, row)) stored++
        }
        return stored
    }

    /**
     * Gives one pending row to [player] if it fits. The row is deleted (conditionally) before the
     * item is given, so two clicks can never give it twice; anything that didn't fit goes back.
     */
    fun deliver(player: Player, row: MilestoneStore.PendingRow): Boolean {
        if (!player.isOnline) return false
        if (plugin.eventManager.isInEventWorld(player)) return false
        val item = decode(row.data, row.amount)
        if (item == null) {
            plugin.logger.warning("[Milestones] Pending reward #${row.id} of ${player.name} can't be read; left in storage.")
            return false
        }
        if (!fits(player, item)) return false
        val taken = try { store.takePending(player.uniqueId, row.id) } catch (e: Exception) {
            plugin.logger.warning("[Milestones] Could not take pending reward #${row.id}: ${e.message}")
            null
        } ?: return false
        val leftover = plugin.depositItemSafely(player, item)
        if (leftover != null && leftover.amount > 0) {
            try {
                store.addPending(player.uniqueId, taken.source, taken.tier, MilestoneStore.PendingItem(taken.label, encode(leftover.clone().apply { amount = 1 }), leftover.amount), System.currentTimeMillis())
            } catch (e: Exception) {
                // Never dropped: log the exact item so staff can restore it.
                plugin.logger.severe(
                    "[Milestones] Could not store the leftover of pending reward #${row.id} for ${player.name} (${player.uniqueId}): " +
                        "${leftover.amount}x ${taken.label}, data=${encode(leftover.clone().apply { amount = 1 })} (${e.message})"
                )
            }
            return false
        }
        return true
    }

    /** Withdraws as many pending rewards as fit. Returns (delivered, left). */
    fun withdrawAll(player: Player): Pair<Int, Int> {
        var delivered = 0
        val rows = try { store.listPending(player.uniqueId, 0, 200) } catch (e: Exception) {
            plugin.logger.warning("[Milestones] Could not list pending rewards: ${e.message}")
            return 0 to 0
        }
        for (row in rows) if (deliver(player, row)) delivered++
        val left = try { store.pendingCount(player.uniqueId) } catch (e: Exception) { rows.size - delivered }
        return delivered to left
    }

    private fun fits(player: Player, item: ItemStack): Boolean {
        if (plugin.modModeManager.isModMode(player)) return true
        var room = 0
        for (slot in player.inventory.storageContents) {
            room += when {
                slot == null || slot.type.isAir -> item.maxStackSize
                slot.isSimilar(item) -> (slot.maxStackSize - slot.amount).coerceAtLeast(0)
                else -> 0
            }
            if (room >= item.amount) return true
        }
        return false
    }

    fun decodeForDisplay(row: MilestoneStore.PendingRow): ItemStack? = decode(row.data, row.amount)
}
