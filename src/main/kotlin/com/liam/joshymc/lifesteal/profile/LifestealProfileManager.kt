package com.liam.joshymc.lifesteal.profile

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.ranked.rating.RatingType
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PurchaseFulfillmentManager
import com.liam.joshymc.manager.PurchaseRewards
import com.liam.joshymc.tutorial.TutorialRules
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifesteal player profiles, statistics, achievements and leaderboards (issue #1429).
 *
 * Lifesteal only: does nothing where Lifesteal isn't hosted (no tables, no listener, no commands).
 * Playtime and coin rewards are only used where this server is Lifesteal-only
 * ([TutorialRules.economyBelongsTo]), because a server that also hosts Survival shares one
 * `playtime` and one `economy` table with Survival.
 *
 * What it reuses (nothing is counted twice): PvP kills/deaths/streaks come from the heart system's
 * deduplicated `lifesteal_heart_transactions`; seasons from `lifesteal_seasons` (progression);
 * ranked numbers from the rating history and the Ranked statistics service; bounty claims from
 * the bounty payouts; team membership from the Lifesteal team tables; alt links from AltManager;
 * reward delivery from [PurchaseFulfillmentManager.enqueue]. It only counts what nothing counted
 * before (ores, crops, fish, mobs, dimensions), in its own counters.
 *
 * Threading: reads run on Bukkit's async pool, writes on the JoshyMC-DB queue; results and every
 * player-facing effect go back to the main thread.
 */
class LifestealProfileManager(private val plugin: Joshymc) {

    val store = ProfileStore(plugin.databaseManager)

    @Volatile var settings: ProfileSettings? = null
        private set
    @Volatile var achievements: List<AchievementDef> = emptyList()
        private set
    @Volatile private var byId: Map<String, AchievementDef> = emptyMap()
    @Volatile var problems: List<String> = emptyList()
        private set
    @Volatile private var tablesReady = false

    private val tasks = ArrayList<BukkitTask>()
    /** Activity not yet written: uuid -> season -> counter -> amount. Main thread only. */
    private val pending = HashMap<UUID, HashMap<String, HashMap<String, Long>>>()
    private val claiming = ConcurrentHashMap.newKeySet<String>()
    private val evaluating = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var recovering = false

    val menus = ProfileMenus(plugin, this)

    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** This server only hosts Lifesteal, so its playtime and economy are Lifesteal's. */
    val lifestealOnly: Boolean get() = TutorialRules.economyBelongsTo(ServerGamemode.LIFESTEAL, plugin.networkIdentityManager.hostedGamemodes)

    val running: Boolean get() = hosted && tablesReady && settings?.enabled == true

    fun isLifesteal(world: World?): Boolean = plugin.gamemodeLockManager.gamemodeOf(world) == ServerGamemode.LIFESTEAL

    fun achievement(id: String): AchievementDef? = byId[id]

    fun unavailableReason(player: Player? = null): String? = when {
        !hosted -> "Profiles only exist on the Lifesteal server."
        !tablesReady -> "Profiles are unavailable right now (database error). Please tell staff."
        settings?.enabled != true -> "Lifesteal profiles are turned off right now."
        player != null && !isLifesteal(player.world) -> "Profiles can only be used in Lifesteal."
        else -> null
    }

    // ── Lifecycle ───────────────────────────────────────────

    /** Startup and every reload (after lifesteal.yml, the crates and the chat tags are loaded). */
    fun refresh() {
        cancelTasks()
        if (!hosted) {
            settings = null
            return
        }
        loadConfig()
        if (!tablesReady) {
            try {
                store.createTables()
                tablesReady = true
            } catch (e: Exception) {
                plugin.logger.severe("[Profiles] Could not create the profile tables, so profiles are off: ${e.message}")
                return
            }
        }
        storedSeason = try { readStoredSeason() } catch (e: Exception) { null }
        val s = settings ?: return
        if (!s.enabled) {
            plugin.logger.info("[Profiles] Disabled in lifesteal-profiles.yml (player data is kept).")
            return
        }
        plugin.logger.info("[Profiles] On: ${achievements.size} achievement(s), ${s.categories.size} leaderboard(s), same-victim cooldown ${s.pairCooldownMillis / 60_000L} min.")
        val scheduler = Bukkit.getScheduler()
        tasks += scheduler.runTaskTimer(plugin, Runnable { flushAll() }, s.flushIntervalSeconds * 20L, s.flushIntervalSeconds * 20L)
        if (s.achievementsEnabled) {
            tasks += scheduler.runTaskTimer(plugin, Runnable { evaluateOnline() }, 200L, s.evaluateIntervalSeconds * 20L)
            tasks += scheduler.runTaskTimer(plugin, Runnable { recoverClaims() }, 300L, 60 * 20L)
        }
        if (s.boardsEnabled) tasks += scheduler.runTaskTimer(plugin, Runnable { rebuildBoards() }, 100L, s.boardRefreshSeconds * 20L)
        for (player in Bukkit.getOnlinePlayers()) if (isLifesteal(player.world)) recordDimension(player)
    }

    fun stop() {
        cancelTasks()
        if (!tablesReady) return
        // Synchronous: the database is still open, and nothing must be lost at shutdown.
        val now = System.currentTimeMillis()
        for ((uuid, seasons) in pending) for ((season, counters) in seasons) {
            try { store.addCounters(uuid, season, counters, now) } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Couldn't save activity of $uuid at shutdown: ${e.message}")
            }
        }
        pending.clear()
    }

    private fun cancelTasks() {
        tasks.forEach { it.cancel() }
        tasks.clear()
    }

    private fun loadConfig() {
        val file = File(plugin.dataFolder, FILE)
        if (!file.exists()) {
            try { plugin.saveResource(FILE, false) } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Couldn't write $FILE: ${e.message}")
            }
        }
        val yaml = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        val bundled = plugin.getResource(FILE)?.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        // Scalars missing from the server's copy fall back to the bundled file; the achievement list is
        // only taken from the bundled file when the server's copy has no achievements section at all.
        if (bundled != null) yaml.setDefaults(bundled)
        val root = toMap(yaml, includeDefaults = true).toMutableMap()
        root["achievements"] = (if (yaml.isConfigurationSection("achievements") && yaml.getConfigurationSection("achievements")?.getKeys(false)?.isNotEmpty() == true)
            yaml.getConfigurationSection("achievements") else bundled?.getConfigurationSection("achievements"))?.let { toMap(it, includeDefaults = false) }
        val parsed = ProfileConfig.parse(root)
        settings = parsed.settings
        val divisions = plugin.lifestealRankedStatsManager.divisions
        val ctx = AchievementParser.Context(
            catalog = plugin.purchaseFulfillmentManager.rewardCatalog,
            coinsAllowed = lifestealOnly,
            divisionIndex = divisions?.let { table -> { id: String -> table.divisions.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) } },
            playtimeAllowed = lifestealOnly,
        )
        @Suppress("UNCHECKED_CAST")
        val result = AchievementParser.parse(root["achievements"] as? Map<String, Any?>, ctx)
        achievements = if (parsed.settings.achievementsEnabled) result.achievements else emptyList()
        byId = achievements.associateBy { it.id }
        problems = parsed.problems + result.problems.filterNot { it.endsWith(": disabled") }
        for (p in problems) plugin.logger.warning("[Profiles] $FILE: $p")
    }

    private fun toMap(section: ConfigurationSection, includeDefaults: Boolean): Map<String, Any?> =
        section.getKeys(false).associateWith { key ->
            val value = if (includeDefaults) section.get(key) else section.get(key, null)
            when (value) {
                is ConfigurationSection -> toMap(value, includeDefaults)
                is List<*> -> value.map { if (it is ConfigurationSection) toMap(it, includeDefaults) else it }
                else -> value
            }
        }

    // ── Activity counters ───────────────────────────────────

    /** The ACTIVE `lifesteal_seasons` row as last read (startup, reload, every leaderboard rebuild). */
    @Volatile private var storedSeason: SeasonWindow? = null

    /**
     * The running season: the progression's live view of the ACTIVE season (so a season staff start
     * takes effect at once), else the stored ACTIVE row (progression disabled). The same row the
     * season windows of [ProfileCalculator] come from, so counters always land in the season the
     * profile shows.
     */
    fun currentSeason(): SeasonWindow? {
        val season = plugin.lifestealProgressionManager.progress().season ?: return storedSeason
        return SeasonWindow(season.id, season.launchAt, null, true)
    }

    private fun readStoredSeason(): SeasonWindow? = Seasons.current(Seasons.windows(store.seasons()))

    /** Main thread. Buffered and written every flush interval, on quit and at shutdown. */
    fun record(player: Player, stat: ProfileStat, amount: Long = 1) {
        val counter = stat.counter ?: return
        if (!running || amount <= 0 || !isLifesteal(player.world)) return
        val season = Seasons.counterSeason(currentSeason())
        pending.getOrPut(player.uniqueId) { HashMap() }.getOrPut(season) { HashMap() }.merge(counter, amount, Long::plus)
    }

    fun recordDimension(player: Player) {
        if (!running || !isLifesteal(player.world)) return
        val uuid = player.uniqueId
        val dimension = player.world.environment.name
        val season = Seasons.counterSeason(currentSeason())
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("profiles: dimension") { store.recordDimension(uuid, season, dimension, now) }
    }

    fun flush(uuid: UUID) {
        val seasons = pending.remove(uuid) ?: return
        val now = System.currentTimeMillis()
        plugin.databaseManager.async("profiles: activity") {
            for ((season, counters) in seasons) store.addCounters(uuid, season, counters, now)
        }
    }

    private fun flushAll() {
        for (uuid in pending.keys.toList()) flush(uuid)
    }

    fun onJoin(player: Player) {
        if (!running) return
        recordDimension(player)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (player.isOnline) evaluate(player) }, 100L)
    }

    fun onQuit(player: Player) {
        flush(player.uniqueId)
        evaluating.remove(player.uniqueId)
    }

    // ── Statistics (async reads) ────────────────────────────

    fun calculator(): ProfileCalculator {
        val s = settings ?: ProfileSettings.DEFAULTS
        val table = plugin.lifestealRankedStatsManager.divisions
        return ProfileCalculator(
            store, s.pairCooldownMillis,
            { team, rating -> table?.let { t -> t.indexOf(t.of(if (team) RatingType.TEAM_ELO else RatingType.PLAYER_ELO, rating)) + 1 } },
            lifestealOnly,
        )
    }

    fun <T> read(what: String, block: () -> T, done: (T?, String?) -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val value = try { block() } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Couldn't read $what: ${e.message}")
                onMain { done(null, "Couldn't load that right now; please try again.") }
                return@Runnable
            }
            onMain { done(value, null) }
        })
    }

    fun onMain(block: () -> Unit) {
        try {
            if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable { block() })
        } catch (_: Exception) {
            // Shutting down.
        }
    }

    data class Profile(
        val uuid: UUID,
        val name: String,
        val hearts: Int?,
        val periods: ProfileCalculator.Periods,
        val rows: List<ProfileStore.AchievementRow>,
        val settings: ProfileStore.Settings,
        val playtimeSeconds: Long?,
        val playerRating: Int?,
        val teamName: String?,
        val teamRating: Int?,
        val rank: String?,
    ) {
        fun status(def: AchievementDef, period: String?): Map<Int, String> =
            rows.filter { it.key.achievementId == def.id && it.key.period == period }.associate { it.key.tier to it.status }
    }

    /** Loads [uuid]'s profile; [done] gets null + a reason when there is none. Works for offline players. */
    fun loadProfile(uuid: UUID, name: String, done: (Profile?, String?) -> Unit) {
        unavailableReason()?.let { return done(null, it) }
        val calc = calculator()
        val team = plugin.lifestealTeamManager.teamOf(uuid)
        val rank = plugin.rankManager.getPlayerRankById(uuid)?.displayTag
        val season = currentSeason()
        val initialPlayer = plugin.lifestealRankedStatsManager.initial(RatingType.PLAYER_ELO)
        val initialTeam = plugin.lifestealRankedStatsManager.initial(RatingType.TEAM_ELO)
        val session = Bukkit.getPlayer(uuid)?.let { plugin.playtimeManager.getPlaytime(uuid) }
        val showPlaytime = lifestealOnly && settings?.showPlaytime == true
        read("the profile of $name", {
            val hasRecord = store.hasHeartRecord(uuid)
            val periods = calc.player(uuid, includePrevious = true)
            val rows = store.achievements(uuid)
            if (!hasRecord && rows.isEmpty() && periods.lifetime.values.values.all { it == 0L }) return@read null
            Profile(
                uuid, name, store.hearts(uuid), periods, rows, store.settings(uuid),
                if (showPlaytime) session ?: store.playtimeSeconds(uuid)[uuid] ?: 0L else null,
                season?.let { store.currentRating(it.id, RatingType.PLAYER_ELO.name, uuid.toString()) } ?: initialPlayer.takeIf { season != null },
                team?.name,
                team?.let { t -> season?.let { store.currentRating(it.id, RatingType.TEAM_ELO.name, t.id) } ?: initialTeam },
                rank,
            )
        }) { profile, problem -> done(profile, problem ?: if (profile == null) "$name has no Lifesteal profile yet." else null) }
    }

    // ── Achievements ────────────────────────────────────────

    private fun evaluateOnline() {
        for (player in Bukkit.getOnlinePlayers()) if (isLifesteal(player.world)) evaluate(player)
    }

    /** Records every tier [player] reached and tells them. Safe to call often: completion is once per primary key. */
    fun evaluate(player: Player, after: (() -> Unit)? = null) {
        val s = settings
        if (!running || s == null || !s.achievementsEnabled || achievements.isEmpty()) { after?.invoke(); return }
        val uuid = player.uniqueId
        if (!evaluating.add(uuid)) { after?.invoke(); return }
        flush(uuid)
        val defs = achievements
        val calc = calculator()
        // Runs after the flush on the same database queue, so the counters just written are included.
        plugin.databaseManager.async("profiles: evaluate") {
            val newly = try {
                val periods = calc.player(uuid, includePrevious = false)
                val seasonId = periods.current?.id
                val recorded = store.achievements(uuid).map { it.key }.toSet()
                val now = System.currentTimeMillis()
                AchievementRules.newlyReached(defs, periods.season, periods.lifetime, seasonId, recorded).filter { (def, tier) ->
                    val period = AchievementRules.period(def, seasonId) ?: return@filter false
                    store.complete(uuid, AchievementRules.Key(def.id, tier.number, period), AchievementRules.value(def, periods.season, periods.lifetime) ?: 0, now)
                }
            } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Couldn't evaluate achievements of $uuid: ${e.message}")
                emptyList()
            } finally {
                evaluating.remove(uuid)
            }
            onMain {
                for ((def, tier) in newly) announce(uuid, def, tier)
                after?.invoke()
            }
        }
    }

    private fun announce(uuid: UUID, def: AchievementDef, tier: AchievementTier) {
        val s = settings ?: return
        val player = Bukkit.getPlayer(uuid)
        if (player != null && s.announcePlayer) {
            plugin.commsManager.send(
                player,
                Component.text("Achievement complete: ", NamedTextColor.GREEN)
                    .append(Component.text(def.tierLabel(tier), NamedTextColor.GOLD))
                    .append(Component.text(if (tier.rewards.isEmpty()) "" else ". Claim it in /achievements.", NamedTextColor.GREEN)),
            )
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f)
        }
        if (s.announceBroadcast && def.broadcast && def.visibility == AchievementVisibility.VISIBLE) {
            val name = player?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: return
            val line = plugin.commsManager.parseLegacy(s.broadcastMessage.replace("{player}", name).replace("{achievement}", def.tierLabel(tier)))
            for (p in Bukkit.getOnlinePlayers()) if (isLifesteal(p.world)) plugin.commsManager.send(p, line)
        }
    }

    sealed interface ClaimOutcome {
        data class Claimed(val rewards: List<AchievementReward>) : ClaimOutcome
        data class Refused(val reason: String) : ClaimOutcome
        /** Recorded as claimed, but a reward couldn't be queued yet: the recovery retries by the same reference. */
        data object Pending : ClaimOutcome
    }

    /**
     * Claims one completed tier. The store's conditional COMPLETED -> CLAIMING update decides the
     * single winner (two clicks, two menus, two servers threads); rewards are snapshotted on the row,
     * then each is queued through PurchaseFulfillmentManager with a reference unique to this claim,
     * so a retry after a crash finds the existing delivery instead of paying twice.
     */
    fun claim(player: Player, key: AchievementRules.Key, done: (ClaimOutcome) -> Unit) {
        unavailableReason(player)?.let { return done(ClaimOutcome.Refused(it)) }
        val def = byId[key.achievementId] ?: return done(ClaimOutcome.Refused("That achievement no longer exists."))
        val tier = def.tier(key.tier) ?: return done(ClaimOutcome.Refused("That tier no longer exists."))
        val lock = "${player.uniqueId}|${AchievementRules.keyText(key)}"
        if (!claiming.add(lock)) return done(ClaimOutcome.Refused("That claim is already being processed."))
        val uuid = player.uniqueId
        val snapshot = ProfileStore.encodeRewards(tier.rewards)
        plugin.databaseManager.async("profiles: claim") {
            val start = try { store.beginClaim(uuid, key, snapshot, System.currentTimeMillis()) } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Claim of ${AchievementRules.keyText(key)} by $uuid failed: ${e.message}")
                null
            }
            onMain {
                when (start) {
                    is ProfileStore.ClaimStart.Started -> deliver(uuid, player.name, key, start.rewards) { ok ->
                        claiming.remove(lock)
                        done(if (ok) ClaimOutcome.Claimed(ProfileStore.decodeRewards(start.rewards)) else ClaimOutcome.Pending)
                    }
                    else -> {
                        claiming.remove(lock)
                        done(
                            ClaimOutcome.Refused(
                                when (start) {
                                    ProfileStore.ClaimStart.AlreadyClaimed -> "You already claimed that."
                                    ProfileStore.ClaimStart.InProgress -> "That claim is already being processed."
                                    ProfileStore.ClaimStart.NotCompleted -> "You haven't completed that yet."
                                    else -> "Couldn't claim that right now; please try again."
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    /** Main thread: queues every store reward of a CLAIMING row, then marks it CLAIMED. [done] gets false when something must be retried. */
    private fun deliver(uuid: UUID, name: String, key: AchievementRules.Key, snapshot: String, done: (Boolean) -> Unit) {
        val rewards = ProfileStore.decodeRewards(snapshot)
        var ok = true
        for ((index, reward) in rewards.withIndex()) {
            if (reward !is AchievementReward.Store) continue
            if (reward.spec.kind == PurchaseRewards.Kind.COINS && !lifestealOnly) {
                plugin.logger.warning("[Profiles] Not paying coins for ${AchievementRules.keyText(key)} to $name: this economy isn't Lifesteal's.")
                continue
            }
            when (val r = plugin.purchaseFulfillmentManager.enqueue(uuid, name, reward.spec, AchievementRules.rewardReference(key, index), SOURCE_PREFIX + key.achievementId)) {
                is PurchaseFulfillmentManager.FulfillResult.Recorded, is PurchaseFulfillmentManager.FulfillResult.Duplicate -> {}
                else -> {
                    ok = false
                    plugin.logger.warning("[Profiles] Reward ${reward.spec.describe()} of ${AchievementRules.keyText(key)} for $name wasn't queued ($r); retrying later.")
                }
            }
        }
        if (!ok) return done(false)
        plugin.databaseManager.async("profiles: claim done") {
            try { store.finishClaim(uuid, key, System.currentTimeMillis()) } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Couldn't mark ${AchievementRules.keyText(key)} of $uuid claimed (retried later): ${e.message}")
            }
            onMain { done(true) }
        }
    }

    /** Finishes claims a crash or a failed queue left CLAIMING, by the same references (no double payout). */
    fun recoverClaims() {
        if (!running || recovering) return
        recovering = true
        read("unfinished claims", { store.unfinishedClaims() }) { rows, _ ->
            val list = rows.orEmpty()
            if (list.isEmpty()) { recovering = false; return@read }
            var left = list.size
            for (row in list) {
                val name = Bukkit.getPlayer(row.uuid)?.name ?: plugin.playerResolver.lastKnownName(row.uuid) ?: row.uuid.toString()
                val lock = "${row.uuid}|${AchievementRules.keyText(row.key)}"
                if (!claiming.add(lock)) { if (--left == 0) recovering = false; continue }
                deliver(row.uuid, name, row.key, row.rewards.orEmpty()) { ok ->
                    claiming.remove(lock)
                    if (ok) plugin.logger.info("[Profiles] Finished interrupted claim ${AchievementRules.keyText(row.key)} for $name.")
                    if (--left == 0) recovering = false
                }
            }
        }
    }

    // ── Profile settings ────────────────────────────────────

    /** Titles [rows] unlocked: claimed tiers whose reward snapshot holds a title, key text -> title. */
    fun unlockedTitles(rows: List<ProfileStore.AchievementRow>): Map<String, String> =
        rows.filter { it.status == "CLAIMED" }.mapNotNull { row ->
            ProfileStore.decodeRewards(row.rewards).filterIsInstance<AchievementReward.Title>().firstOrNull()?.let { AchievementRules.keyText(row.key) to it.text }
        }.toMap()

    fun unlockedBorders(rows: List<ProfileStore.AchievementRow>): Set<String> =
        setOf(settings?.defaultBorder ?: ProfileSettings.DEFAULTS.defaultBorder) + rows.filter { it.status == "CLAIMED" }
            .flatMap { ProfileStore.decodeRewards(it.rewards).filterIsInstance<AchievementReward.Border>().map { b -> b.color } }

    /** Saves [next] after re-checking every choice against the database. */
    fun saveSettings(player: Player, next: ProfileStore.Settings, done: (Boolean) -> Unit) {
        unavailableReason(player)?.let { plugin.commsManager.send(player, Component.text(it, NamedTextColor.RED)); return done(false) }
        val s = settings ?: return done(false)
        if (next.private && !s.allowPrivate) return done(false)
        val uuid = player.uniqueId
        plugin.databaseManager.async("profiles: settings") {
            val ok = try {
                val rows = store.achievements(uuid)
                val borders = unlockedBorders(rows)
                store.saveSettings(uuid, next, { row -> ProfileStore.decodeRewards(row.rewards).any { it is AchievementReward.Title } }, { it in borders }, System.currentTimeMillis())
            } catch (e: Exception) {
                plugin.logger.warning("[Profiles] Couldn't save profile settings of $uuid: ${e.message}")
                false
            }
            onMain { done(ok) }
        }
    }

    // ── Leaderboards ────────────────────────────────────────

    data class Boards(val builtAt: Long, val seasonId: String?, val lists: Map<Pair<BoardCategory, BoardPeriod>, List<Leaderboards.Ranked>>)

    @Volatile var boards: Boards? = null
        private set

    /** Configured categories this server can show (no playtime board where playtime is Survival's). */
    fun boardCategories(): List<BoardCategory> =
        settings?.categories.orEmpty().filter { it != BoardCategory.PLAYTIME || lifestealOnly }
    @Volatile private var building = false

    /** One read of every source, then every board for the current season and all time. */
    fun rebuildBoards(done: (() -> Unit)? = null) {
        val s = settings
        if (!running || s == null || !s.boardsEnabled || building) { done?.invoke(); return }
        building = true
        val calc = calculator()
        val season = currentSeason()
        val categories = boardCategories().filterNot { it.ranked }
        val minimumFights = s.kdMinimumFights
        read("the leaderboards", {
            storedSeason = readStoredSeason()
            val rows = store.pvpRows()
            val linked = store.linkedPairs()
            val lists = HashMap<Pair<BoardCategory, BoardPeriod>, List<Leaderboards.Ranked>>()
            for (period in BoardPeriod.entries) {
                val window = if (period == BoardPeriod.SEASON) season ?: continue else null
                val pvp = calc.allPvp(window, rows, linked)
                for (category in categories) {
                    if (period == BoardPeriod.SEASON && !category.seasonal) continue
                    if (period == BoardPeriod.ALL_TIME && !category.allTime) continue
                    val values: Map<UUID, Long> = when (category) {
                        BoardCategory.KD -> pvp.mapNotNull { (k, v) -> PvpCounting.kdScaled(v.kills, v.deaths, minimumFights)?.let { k to it } }.toMap()
                        BoardCategory.ACHIEVEMENTS -> store.completedCounts(window)
                        else -> calc.all(category.stat ?: continue, window, pvp)
                    }
                    lists[category to period] = Leaderboards.rank(values, minimumValue = if (category == BoardCategory.KD) 0 else 1)
                }
            }
            Boards(System.currentTimeMillis(), season?.id, lists)
        }) { built, _ ->
            building = false
            if (built != null) boards = built
            done?.invoke()
        }
    }

    // ── Admin (read-only) ───────────────────────────────────

    fun statusLines(): List<String> {
        val s = settings
        return listOf(
            "Hosted: $hosted, Lifesteal-only server: $lifestealOnly, tables: $tablesReady",
            "Enabled: ${s?.enabled}, achievements: ${achievements.size} (${if (s?.achievementsEnabled == true) "on" else "off"}), leaderboards: ${s?.boardsEnabled}",
            "Season: ${currentSeason()?.id ?: "none"}, boards built: ${boards?.builtAt?.let { "${(System.currentTimeMillis() - it) / 1000}s ago" } ?: "never"}",
            "Config problems: ${problems.size}" + if (problems.isEmpty()) "" else " (see the console)",
        )
    }

    fun send(player: Player, message: Component) = plugin.commsManager.send(player, message, CommunicationsManager.Category.DEFAULT)

    companion object {
        const val FILE = "lifesteal-profiles.yml"
        const val SOURCE_PREFIX = PurchaseFulfillmentManager.ACHIEVEMENT_SOURCE_PREFIX
        const val PERMISSION_USE = "joshymc.lifesteal.profile.use"
        const val PERMISSION_OTHERS = "joshymc.lifesteal.profile.others"
        const val PERMISSION_PRIVATE_BYPASS = "joshymc.lifesteal.profile.private.bypass"
        const val PERMISSION_ACHIEVEMENTS = "joshymc.lifesteal.achievements.use"
        const val PERMISSION_LEADERBOARDS = "joshymc.lifesteal.leaderboards.use"
        const val PERMISSION_ADMIN = "joshymc.lifesteal.profile.admin"
    }
}
