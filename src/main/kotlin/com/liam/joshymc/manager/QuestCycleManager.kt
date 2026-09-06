package com.liam.joshymc.manager

import io.papermc.paper.event.player.PlayerTradeEvent
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.data.Ageable
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Animals
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ── Data model ──────────────────────────────────────────────────

enum class CycleCategory { MINING, WOODCUTTING, FARMING, COMBAT, ANIMALS, FISHING, CRAFTING, SMELTING, GENERAL, EXPLORATION, EXPERIENCE, ECONOMY, NETHER, SOCIAL }

enum class CycleQuestType { DAILY_MEDIUM, DAILY_HARD, WEEKLY }

enum class CycleObjective {
    MINE_BLOCK, BREAK_LOG, HARVEST_CROP, KILL_MOB, BREED_ANIMAL, CATCH_FISH,
    SMELT_ITEM, CRAFT_ITEM, TRAVEL, TRAVEL_NETHER, GAIN_XP, GAIN_LEVELS,
    ENCHANT_ITEM, TRADE_VILLAGER, PLACE_BLOCK
}

data class CycleQuest(
    val id: String,
    val name: String,
    val description: String,
    val category: CycleCategory,
    val type: CycleQuestType,
    val objective: CycleObjective,
    val target: String,
    val amount: Int,
    val reward: Double,
    val icon: Material,
    val enabled: Boolean
)

data class CycleProgress(val progress: Int, val completed: Boolean, val rewardClaimed: Boolean)
data class QuestMasterState(val dailySets: Int, val weeklyComplete: Boolean, val rewarded: Boolean)

/**
 * Unified Daily / Weekly / Quest Master quest system — the only quest system in
 * JoshyMC, opened with /quests (and its aliases /quest, /daily, /questboard, /questbook).
 *
 * A purpose-built pool of 120 quests (60 medium daily, 30 hard daily, 30 weekly)
 * defined in quest-cycle.yml. Server-wide rotation, per-player progress.
 */
class QuestCycleManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val PLACED_META = "joshymc_quest_placed"
        private val ORE_PRODUCTS = setOf("IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT")
        private val LOG_FAMILIES = setOf(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE",
            "CHERRY", "PALE_OAK", "CRIMSON", "WARPED"
        )
        private val ALWAYS_HARVESTABLE = setOf(
            "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO", "NETHER_WART",
            "COCOA", "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES", "WEEPING_VINES"
        )
    }

    // ── Config ──────────────────────────────────────────────────

    private var zone: ZoneId = ZoneId.of("America/New_York")

    private var selectedMedium = 2
    private var selectedHard = 1
    private var dailyResetHour = 0
    private var dailyResetMinute = 0
    private var dailyRepeatProtectionDays = 7
    private var dailyCompletionRewardEnabled = true
    private var dailyCompletionCommands: List<String> = emptyList()

    private var selectedWeekly = 3
    private var weeklyResetDay = DayOfWeek.MONDAY
    private var weeklyResetHour = 0
    private var weeklyResetMinute = 0
    private var weeklyRepeatProtectionWeeks = 4
    private var weeklyCompletionRewardEnabled = true
    private var weeklyCompletionCommands: List<String> = emptyList()

    private var questMasterEnabled = true
    private var requiredDailySets = 5
    private var requireWeeklyCompletion = true
    private var questMasterCommands: List<String> = emptyList()

    private fun loadConfig() {
        val cfg = plugin.config
        zone = runCatching { ZoneId.of(cfg.getString("quests.timezone", "America/New_York")) }
            .getOrDefault(ZoneId.of("America/New_York"))

        selectedMedium = cfg.getInt("quests.daily.selected-medium", 2)
        selectedHard = cfg.getInt("quests.daily.selected-hard", 1)
        dailyResetHour = cfg.getInt("quests.daily.reset.hour", 0)
        dailyResetMinute = cfg.getInt("quests.daily.reset.minute", 0)
        dailyRepeatProtectionDays = cfg.getInt("quests.daily.repeat-protection-days", 7)
        dailyCompletionRewardEnabled = cfg.getBoolean("quests.daily.completion-reward.enabled", true)
        dailyCompletionCommands = cfg.getStringList("quests.daily.completion-reward.commands")

        selectedWeekly = cfg.getInt("quests.weekly.selected", 3)
        weeklyResetDay = runCatching { DayOfWeek.valueOf((cfg.getString("quests.weekly.reset.day", "MONDAY") ?: "MONDAY").uppercase()) }
            .getOrDefault(DayOfWeek.MONDAY)
        weeklyResetHour = cfg.getInt("quests.weekly.reset.hour", 0)
        weeklyResetMinute = cfg.getInt("quests.weekly.reset.minute", 0)
        weeklyRepeatProtectionWeeks = cfg.getInt("quests.weekly.repeat-protection-weeks", 4)
        weeklyCompletionRewardEnabled = cfg.getBoolean("quests.weekly.completion-reward.enabled", true)
        weeklyCompletionCommands = cfg.getStringList("quests.weekly.completion-reward.commands")

        questMasterEnabled = cfg.getBoolean("quests.quest-master.enabled", true)
        requiredDailySets = cfg.getInt("quests.quest-master.required-daily-sets", 5)
        requireWeeklyCompletion = cfg.getBoolean("quests.quest-master.require-weekly-completion", true)
        questMasterCommands = cfg.getStringList("quests.quest-master.reward.commands")
    }

    // ── Quest definitions ──────────────────────────────────────

    private val allQuests = mutableListOf<CycleQuest>()
    private val questsById = mutableMapOf<String, CycleQuest>()

    private fun loadQuestDefinitions() {
        allQuests.clear()
        questsById.clear()

        val file = plugin.configFile("quest-cycle.yml")
        if (!file.exists()) {
            plugin.saveResource("quest-cycle.yml", false)
        } else {
            mergeMissingQuestDefaults(file)
        }

        val cfg = YamlConfiguration.loadConfiguration(file)
        val section = cfg.getConfigurationSection("quests") ?: run {
            plugin.logger.warning("[QuestCycle] quest-cycle.yml has no 'quests' section — no quests loaded.")
            return
        }

        var loaded = 0
        var skipped = 0
        val seenIds = mutableSetOf<String>()
        for (rawId in section.getKeys(false)) {
            val id = rawId.lowercase()
            if (!seenIds.add(id)) {
                plugin.logger.warning("[QuestCycle] Duplicate quest id '$id' — skipping duplicate.")
                skipped++
                continue
            }
            val s = section.getConfigurationSection(rawId) ?: continue
            try {
                val quest = parseQuest(id, s)
                if (quest == null) {
                    skipped++
                } else {
                    allQuests.add(quest)
                    questsById[quest.id] = quest
                    loaded++
                }
            } catch (e: Exception) {
                plugin.logger.warning("[QuestCycle] Failed to load quest '$id': ${e.message}")
                skipped++
            }
        }
        plugin.logger.info("[QuestCycle] Loaded $loaded quest definition(s)${if (skipped > 0) " ($skipped skipped)" else ""}.")
    }

    private fun parseQuest(id: String, s: ConfigurationSection): CycleQuest? {
        val name = s.getString("name") ?: return invalid(id, "missing name")
        val description = s.getString("description") ?: name
        val category = s.getString("category")?.uppercase()?.let { runCatching { CycleCategory.valueOf(it) }.getOrNull() }
            ?: return invalid(id, "invalid/missing category")
        val questType = s.getString("quest-type")?.uppercase()?.let { runCatching { CycleQuestType.valueOf(it) }.getOrNull() }
            ?: return invalid(id, "invalid/missing quest-type")
        val objective = s.getString("objective")?.uppercase()?.let { runCatching { CycleObjective.valueOf(it) }.getOrNull() }
            ?: return invalid(id, "invalid/missing objective")
        val target = (s.getString("target") ?: "ANY").uppercase()
        val amount = s.getInt("amount", -1)
        if (amount <= 0) return invalid(id, "amount must be positive")
        val reward = s.getDouble("reward", -1.0)
        if (reward < 0) return invalid(id, "reward must be >= 0")
        val icon = s.getString("icon")?.let { Material.matchMaterial(it) } ?: Material.STONE
        val enabled = s.getBoolean("enabled", true)
        return CycleQuest(id, name, description, category, questType, objective, target, amount, reward, icon, enabled)
    }

    private fun invalid(id: String, reason: String): CycleQuest? {
        plugin.logger.warning("[QuestCycle] Skipping quest '$id': $reason")
        return null
    }

    private fun mergeMissingQuestDefaults(userFile: File) {
        val defaultStream = plugin.getResource("quest-cycle.yml") ?: return
        val defaults = YamlConfiguration.loadConfiguration(defaultStream.bufferedReader())
        val userCfg = YamlConfiguration.loadConfiguration(userFile)

        val defaultsSection = defaults.getConfigurationSection("quests") ?: return
        val userSection = userCfg.getConfigurationSection("quests") ?: userCfg.createSection("quests")

        var added = 0
        for (id in defaultsSection.getKeys(false)) {
            if (userSection.contains(id)) continue
            userSection.set(id, defaultsSection.get(id))
            added++
        }
        if (added > 0) {
            try {
                userCfg.save(userFile)
                plugin.logger.info("[QuestCycle] Merged $added new quest definition(s) from bundled defaults.")
            } catch (e: Exception) {
                plugin.logger.warning("[QuestCycle] Failed to save merged quest-cycle.yml: ${e.message}")
            }
        }
    }

    /** Re-reads config.yml settings and quest-cycle.yml definitions. Does not touch active rotation or player progress. */
    fun reloadDefinitions() {
        loadConfig()
        loadQuestDefinitions()
    }

    // ── Cycle state ─────────────────────────────────────────────

    private var dailyCycleIdState = ""
    private var dailyQuestIdsState: List<String> = emptyList()
    private var weeklyCycleIdState = ""
    private var weeklyQuestIdsState: List<String> = emptyList()

    private val tasks = mutableListOf<org.bukkit.scheduler.BukkitTask>()

    fun dailyCycleId(): String = dailyCycleIdState
    fun weeklyCycleId(): String = weeklyCycleIdState
    fun getDailyPool(): List<CycleQuest> = dailyQuestIdsState.mapNotNull { questsById[it] }
    fun getWeeklyPool(): List<CycleQuest> = weeklyQuestIdsState.mapNotNull { questsById[it] }
    fun getQuestById(id: String): CycleQuest? = questsById[id.lowercase()]
    fun allQuestIds(): List<String> = allQuests.map { it.id }

    private fun currentDailyCycleId(now: ZonedDateTime): LocalDate {
        val resetToday = now.toLocalDate().atTime(dailyResetHour, dailyResetMinute).atZone(zone)
        return if (now.isBefore(resetToday)) now.toLocalDate().minusDays(1) else now.toLocalDate()
    }

    private fun currentWeeklyCycleId(now: ZonedDateTime): LocalDate {
        var d = now.toLocalDate()
        while (d.dayOfWeek != weeklyResetDay) d = d.minusDays(1)
        val resetInstant = d.atTime(weeklyResetHour, weeklyResetMinute).atZone(zone)
        return if (now.isBefore(resetInstant)) d.minusWeeks(1) else d
    }

    /** Which weekly cycle a given calendar date belongs to (used to credit Quest Master correctly across the Sun→Mon boundary). */
    private fun weeklyCycleIdForDate(date: LocalDate): LocalDate {
        var d = date
        while (d.dayOfWeek != weeklyResetDay) d = d.minusDays(1)
        return d
    }

    private fun nextDailyReset(now: ZonedDateTime): ZonedDateTime {
        var next = now.toLocalDate().atTime(dailyResetHour, dailyResetMinute).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return next
    }

    private fun nextWeeklyReset(now: ZonedDateTime): ZonedDateTime {
        var candidate = now.toLocalDate().atTime(weeklyResetHour, weeklyResetMinute).atZone(zone)
        while (candidate.dayOfWeek != weeklyResetDay || !candidate.isAfter(now)) {
            candidate = candidate.plusDays(1)
        }
        return candidate
    }

    fun dailySecondsLeft(): Long {
        val now = ZonedDateTime.now(zone)
        return Duration.between(now, nextDailyReset(now)).seconds.coerceAtLeast(0)
    }

    fun weeklySecondsLeft(): Long {
        val now = ZonedDateTime.now(zone)
        return Duration.between(now, nextWeeklyReset(now)).seconds.coerceAtLeast(0)
    }

    private fun loadCycleState() {
        plugin.databaseManager.query("SELECT cycle_type, cycle_id, quest_ids FROM quest_cycle_state") { rs ->
            val type = rs.getString("cycle_type")
            val cycleId = rs.getString("cycle_id")
            val ids = rs.getString("quest_ids").split(",").filter { it.isNotBlank() }
            if (type == "daily") {
                dailyCycleIdState = cycleId
                dailyQuestIdsState = ids
            } else if (type == "weekly") {
                weeklyCycleIdState = cycleId
                weeklyQuestIdsState = ids
            }
        }
    }

    private fun persistCycleState(type: String, cycleId: String, ids: List<String>) {
        plugin.databaseManager.execute(
            """INSERT INTO quest_cycle_state (cycle_type, cycle_id, quest_ids, started_at) VALUES (?, ?, ?, ?)
               ON CONFLICT(cycle_type) DO UPDATE SET cycle_id = ?, quest_ids = ?, started_at = ?""",
            type, cycleId, ids.joinToString(","), System.currentTimeMillis(),
            cycleId, ids.joinToString(","), System.currentTimeMillis()
        )
    }

    private fun initCycles() {
        loadCycleState()
        val now = ZonedDateTime.now(zone)
        val curDaily = currentDailyCycleId(now).toString()
        val curWeekly = currentWeeklyCycleId(now).toString()

        // A restart within the same cycle keeps the existing rotation (restart safety).
        // A stale cycle id (server was down across a reset boundary) regenerates once here (missed-reset safety).
        if (dailyCycleIdState != curDaily || dailyQuestIdsState.isEmpty()) {
            dailyCycleIdState = curDaily
            dailyQuestIdsState = generateDailyPool(curDaily)
            persistCycleState("daily", curDaily, dailyQuestIdsState)
        }
        if (weeklyCycleIdState != curWeekly || weeklyQuestIdsState.isEmpty()) {
            weeklyCycleIdState = curWeekly
            weeklyQuestIdsState = generateWeeklyPool(curWeekly)
            persistCycleState("weekly", curWeekly, weeklyQuestIdsState)
        }
    }

    private fun tickCycles() {
        val now = ZonedDateTime.now(zone)

        // Daily is always evaluated (and, if needed, rolled over) before weekly so that a
        // simultaneous Monday-midnight boundary always finalizes Sunday's daily cycle — and
        // credits its Quest Master Daily Set to the OLD weekly cycle — before the new week begins.
        val newDaily = currentDailyCycleId(now).toString()
        if (newDaily != dailyCycleIdState) {
            dailyCycleIdState = newDaily
            dailyQuestIdsState = generateDailyPool(newDaily)
            persistCycleState("daily", newDaily, dailyQuestIdsState)
            progressCache.clear()
            travelAcc.clear()
            travelNetherAcc.clear()
            broadcastReset("&6&l[Daily Quests] &eNew daily quests are available! Use &6/quests &eto view them.")
        }

        val newWeekly = currentWeeklyCycleId(now).toString()
        if (newWeekly != weeklyCycleIdState) {
            weeklyCycleIdState = newWeekly
            weeklyQuestIdsState = generateWeeklyPool(newWeekly)
            persistCycleState("weekly", newWeekly, weeklyQuestIdsState)
            progressCache.clear()
            broadcastReset("&d&l[Weekly Quests] &eNew weekly quests are available! Quest Master has reset. Use &6/quests &eto view them.")
        }
    }

    private fun broadcastReset(legacyMessage: String) {
        for (player in Bukkit.getOnlinePlayers()) {
            plugin.commsManager.send(player, plugin.commsManager.parseLegacy(legacyMessage))
        }
    }

    // ── Selection / rotation ────────────────────────────────────

    private fun pickDiverse(candidates: List<CycleQuest>, count: Int): List<CycleQuest> {
        if (count <= 0 || candidates.isEmpty()) return emptyList()
        val shuffled = candidates.shuffled()
        val chosen = mutableListOf<CycleQuest>()
        val usedCategories = mutableSetOf<CycleCategory>()
        for (q in shuffled) {
            if (chosen.size >= count) break
            if (q.category !in usedCategories) {
                chosen.add(q)
                usedCategories.add(q.category)
            }
        }
        if (chosen.size < count) {
            for (q in shuffled) {
                if (chosen.size >= count) break
                if (q !in chosen) chosen.add(q)
            }
        }
        return chosen
    }

    private fun protectedQuestIds(cycleType: String, cutoffMs: Long): Set<String> {
        return plugin.databaseManager.query(
            "SELECT DISTINCT quest_id FROM quest_cycle_history WHERE cycle_type = ? AND used_at >= ?",
            cycleType, cutoffMs
        ) { rs -> rs.getString("quest_id") }.toSet()
    }

    private fun recordHistory(cycleType: String, cycleId: String, ids: List<String>) {
        val now = System.currentTimeMillis()
        plugin.databaseManager.transaction {
            for (id in ids) {
                plugin.databaseManager.execute(
                    "INSERT OR IGNORE INTO quest_cycle_history (cycle_type, quest_id, cycle_id, used_at) VALUES (?, ?, ?, ?)",
                    cycleType, id, cycleId, now
                )
            }
        }
    }

    private fun selectPool(type: CycleQuestType, count: Int, cutoffMs: Long, cycleType: String): List<String> {
        val enabled = allQuests.filter { it.type == type && it.enabled }
        if (enabled.isEmpty()) return emptyList()
        val protectedIds = protectedQuestIds(cycleType, cutoffMs)
        var candidates = enabled.filterNot { it.id in protectedIds }
        // Gracefully relax repeat protection if it would make a valid rotation impossible.
        if (candidates.size < count) candidates = enabled
        return pickDiverse(candidates, count.coerceAtMost(candidates.size)).map { it.id }
    }

    private fun generateDailyPool(cycleId: String): List<String> {
        val cutoff = System.currentTimeMillis() - dailyRepeatProtectionDays * 86_400_000L
        val mediumIds = selectPool(CycleQuestType.DAILY_MEDIUM, selectedMedium, cutoff, "daily")
        val hardIds = selectPool(CycleQuestType.DAILY_HARD, selectedHard, cutoff, "daily")
        val ids = mediumIds + hardIds
        recordHistory("daily", cycleId, ids)
        return ids
    }

    private fun generateWeeklyPool(cycleId: String): List<String> {
        val cutoff = System.currentTimeMillis() - weeklyRepeatProtectionWeeks * 7 * 86_400_000L
        val ids = selectPool(CycleQuestType.WEEKLY, selectedWeekly, cutoff, "weekly")
        recordHistory("weekly", cycleId, ids)
        return ids
    }

    /** Re-rolls the pool for the CURRENT cycle without waiting for the real boundary. Admin/testing use. */
    fun forceDailyReset() {
        dailyQuestIdsState = generateDailyPool(dailyCycleIdState)
        persistCycleState("daily", dailyCycleIdState, dailyQuestIdsState)
        progressCache.clear()
        travelAcc.clear()
        travelNetherAcc.clear()
    }

    fun forceWeeklyReset() {
        weeklyQuestIdsState = generateWeeklyPool(weeklyCycleIdState)
        persistCycleState("weekly", weeklyCycleIdState, weeklyQuestIdsState)
        progressCache.clear()
    }

    // ── Lifecycle ───────────────────────────────────────────────

    fun start() {
        loadConfig()
        createTables()
        loadQuestDefinitions()
        initCycles()

        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickCycles() }, 600L, 600L)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickOpenGuis() }, 100L, 100L)
        tasks += Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable { flushAllProgress() }, 6000L, 6000L)

        plugin.logger.info(
            "[QuestCycle] Started. Daily cycle=$dailyCycleIdState (${dailyQuestIdsState.size} quests), " +
                "Weekly cycle=$weeklyCycleIdState (${weeklyQuestIdsState.size} quests)."
        )
    }

    fun stop() {
        tasks.forEach { it.cancel() }
        tasks.clear()
        flushAllProgress()
        progressCache.clear()
        travelAcc.clear()
        travelNetherAcc.clear()
        trackedGuiPlayers.clear()
    }

    private fun createTables() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_cycle_state (
                cycle_type TEXT PRIMARY KEY,
                cycle_id TEXT NOT NULL,
                quest_ids TEXT NOT NULL,
                started_at INTEGER NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_cycle_history (
                cycle_type TEXT NOT NULL,
                quest_id TEXT NOT NULL,
                cycle_id TEXT NOT NULL,
                used_at INTEGER NOT NULL,
                PRIMARY KEY (cycle_type, quest_id, cycle_id)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_cycle_progress (
                uuid TEXT NOT NULL,
                quest_id TEXT NOT NULL,
                cycle_id TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                reward_claimed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, quest_id, cycle_id)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_daily_completion (
                uuid TEXT NOT NULL,
                daily_cycle_id TEXT NOT NULL,
                bonus_claimed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, daily_cycle_id)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_weekly_completion (
                uuid TEXT NOT NULL,
                weekly_cycle_id TEXT NOT NULL,
                bonus_claimed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, weekly_cycle_id)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_master_progress (
                uuid TEXT NOT NULL,
                weekly_cycle_id TEXT NOT NULL,
                daily_sets INTEGER NOT NULL DEFAULT 0,
                weekly_complete INTEGER NOT NULL DEFAULT 0,
                rewarded INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, weekly_cycle_id)
            )
        """.trimIndent())
    }

    // ── Progress tracking ───────────────────────────────────────

    private val progressCache = ConcurrentHashMap<UUID, MutableMap<String, CycleProgress>>()
    private val travelAcc = ConcurrentHashMap<UUID, Double>()
    private val travelNetherAcc = ConcurrentHashMap<UUID, Double>()

    private fun cycleIdFor(quest: CycleQuest): String =
        if (quest.type == CycleQuestType.WEEKLY) weeklyCycleIdState else dailyCycleIdState

    private fun loadProgress(uuid: UUID): MutableMap<String, CycleProgress> {
        val map = mutableMapOf<String, CycleProgress>()
        plugin.databaseManager.query(
            "SELECT quest_id, progress, completed, reward_claimed FROM quest_cycle_progress WHERE uuid = ? AND cycle_id IN (?, ?)",
            uuid.toString(), dailyCycleIdState, weeklyCycleIdState
        ) { rs ->
            map[rs.getString("quest_id")] = CycleProgress(
                rs.getInt("progress"), rs.getInt("completed") == 1, rs.getInt("reward_claimed") == 1
            )
        }
        return map
    }

    fun getProgress(uuid: UUID, quest: CycleQuest): CycleProgress {
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        return map[quest.id] ?: CycleProgress(0, false, false)
    }

    private fun incrementProgress(player: Player, quest: CycleQuest, amount: Int) {
        if (amount <= 0) return
        val uuid = player.uniqueId
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val current = map[quest.id] ?: CycleProgress(0, false, false)
        if (current.completed) return
        val target = plugin.boosterManager.applyQuestBooster(plugin.resurgeManager.getEffectiveAmount(uuid, quest.amount))
        val newProgress = (current.progress + amount).coerceIn(0, target)
        val completed = newProgress >= target
        map[quest.id] = current.copy(progress = newProgress, completed = completed)
        if (completed) onQuestCompleted(player, quest)
    }

    /** Admin/testing: directly set a player's progress on one of the currently active quests. */
    fun setProgress(uuid: UUID, questId: String, amount: Int): Boolean {
        val quest = questsById[questId.lowercase()] ?: return false
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val target = plugin.boosterManager.applyQuestBooster(quest.amount)
        val clamped = amount.coerceIn(0, target)
        val wasCompleted = map[quest.id]?.completed == true
        map[quest.id] = CycleProgress(clamped, clamped >= target, map[quest.id]?.rewardClaimed ?: false)
        if (!wasCompleted && clamped >= target) {
            Bukkit.getPlayer(uuid)?.let { onQuestCompleted(it, quest) }
        }
        return true
    }

    private fun flushProgress(uuid: UUID) {
        val map = progressCache[uuid] ?: return
        plugin.databaseManager.transaction {
            for ((questId, entry) in map) {
                val quest = questsById[questId] ?: continue
                val cycleId = cycleIdFor(quest)
                val completedInt = if (entry.completed) 1 else 0
                plugin.databaseManager.execute(
                    """INSERT INTO quest_cycle_progress (uuid, quest_id, cycle_id, progress, completed, reward_claimed)
                       VALUES (?, ?, ?, ?, ?, ?)
                       ON CONFLICT(uuid, quest_id, cycle_id) DO UPDATE SET progress = ?, completed = ?""",
                    uuid.toString(), questId, cycleId, entry.progress, completedInt, if (entry.rewardClaimed) 1 else 0,
                    entry.progress, completedInt
                )
            }
        }
    }

    private fun flushAllProgress() {
        for (uuid in progressCache.keys.toList()) {
            try {
                flushProgress(uuid)
            } catch (e: Exception) {
                plugin.logger.warning("[QuestCycle] Failed to flush progress for $uuid: ${e.message}")
            }
        }
    }

    // ── Rewards ─────────────────────────────────────────────────

    /** Atomically claims a quest's individual reward exactly once (survives restarts/reconnects/duplicate ticks). */
    private fun claimQuestRewardOnce(uuid: String, quest: CycleQuest): Boolean {
        val cycleId = cycleIdFor(quest)
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO quest_cycle_progress (uuid, quest_id, cycle_id, progress, completed, reward_claimed) VALUES (?, ?, ?, ?, 1, 0)",
            uuid, quest.id, cycleId, quest.amount
        )
        return plugin.databaseManager.executeUpdate(
            "UPDATE quest_cycle_progress SET completed = 1, progress = ?, reward_claimed = 1 WHERE uuid = ? AND quest_id = ? AND cycle_id = ? AND reward_claimed = 0",
            quest.amount, uuid, quest.id, cycleId
        ) > 0
    }

    private fun claimDailyBonusOnce(uuid: String, cycleId: String): Boolean {
        plugin.databaseManager.execute("INSERT OR IGNORE INTO quest_daily_completion (uuid, daily_cycle_id, bonus_claimed) VALUES (?, ?, 0)", uuid, cycleId)
        return plugin.databaseManager.executeUpdate(
            "UPDATE quest_daily_completion SET bonus_claimed = 1 WHERE uuid = ? AND daily_cycle_id = ? AND bonus_claimed = 0", uuid, cycleId
        ) > 0
    }

    private fun claimWeeklyBonusOnce(uuid: String, cycleId: String): Boolean {
        plugin.databaseManager.execute("INSERT OR IGNORE INTO quest_weekly_completion (uuid, weekly_cycle_id, bonus_claimed) VALUES (?, ?, 0)", uuid, cycleId)
        return plugin.databaseManager.executeUpdate(
            "UPDATE quest_weekly_completion SET bonus_claimed = 1 WHERE uuid = ? AND weekly_cycle_id = ? AND bonus_claimed = 0", uuid, cycleId
        ) > 0
    }

    fun isDailyBonusClaimed(uuid: UUID, cycleId: String): Boolean =
        plugin.databaseManager.queryFirst(
            "SELECT bonus_claimed FROM quest_daily_completion WHERE uuid = ? AND daily_cycle_id = ?", uuid.toString(), cycleId
        ) { rs -> rs.getInt("bonus_claimed") == 1 } ?: false

    fun isWeeklyBonusClaimed(uuid: UUID, cycleId: String): Boolean =
        plugin.databaseManager.queryFirst(
            "SELECT bonus_claimed FROM quest_weekly_completion WHERE uuid = ? AND weekly_cycle_id = ?", uuid.toString(), cycleId
        ) { rs -> rs.getInt("bonus_claimed") == 1 } ?: false

    fun getQuestMasterProgress(uuid: UUID, weeklyCycleId: String): QuestMasterState =
        plugin.databaseManager.queryFirst(
            "SELECT daily_sets, weekly_complete, rewarded FROM quest_master_progress WHERE uuid = ? AND weekly_cycle_id = ?",
            uuid.toString(), weeklyCycleId
        ) { rs -> QuestMasterState(rs.getInt("daily_sets"), rs.getInt("weekly_complete") == 1, rs.getInt("rewarded") == 1) }
            ?: QuestMasterState(0, false, false)

    /** Lifetime count of Quest Master weekly cycles this player has been rewarded for. */
    fun getLifetimeQuestMasterCompletions(uuid: UUID): Int =
        plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) AS n FROM quest_master_progress WHERE uuid = ? AND rewarded = 1",
            uuid.toString()
        ) { rs -> rs.getInt("n") } ?: 0

    private fun runRewardCommands(player: Player, commands: List<String>) {
        for (template in commands) {
            val cmd = template.replace("{player}", player.name)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
        }
    }

    private fun onQuestCompleted(player: Player, quest: CycleQuest) {
        if (!claimQuestRewardOnce(player.uniqueId.toString(), quest)) return

        plugin.economyManager.deposit(player.uniqueId, quest.reward)
        plugin.commsManager.send(
            player,
            plugin.commsManager.parseLegacy("&a&l✔ Quest Complete! &e${quest.name} &7— &6+\$${plugin.economyManager.formatShort(quest.reward)}")
        )
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.2f)

        when (quest.type) {
            CycleQuestType.DAILY_MEDIUM, CycleQuestType.DAILY_HARD -> checkDailySetCompletion(player)
            CycleQuestType.WEEKLY -> checkWeeklySetCompletion(player)
        }
    }

    private fun checkDailySetCompletion(player: Player) {
        val uuid = player.uniqueId
        val pool = getDailyPool()
        if (pool.isEmpty()) return
        val map = progressCache[uuid] ?: return
        if (!pool.all { map[it.id]?.completed == true }) return

        val cycleId = dailyCycleIdState
        if (!claimDailyBonusOnce(uuid.toString(), cycleId)) return

        if (dailyCompletionRewardEnabled) runRewardCommands(player, dailyCompletionCommands)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.0f)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&6&l★ Daily Quests Complete! &eBonus reward granted!"))

        if (questMasterEnabled) creditQuestMasterDailySet(player, cycleId)
    }

    private fun checkWeeklySetCompletion(player: Player) {
        val uuid = player.uniqueId
        val pool = getWeeklyPool()
        if (pool.isEmpty()) return
        val map = progressCache[uuid] ?: return
        if (!pool.all { map[it.id]?.completed == true }) return

        val cycleId = weeklyCycleIdState
        if (!claimWeeklyBonusOnce(uuid.toString(), cycleId)) return

        if (weeklyCompletionRewardEnabled) runRewardCommands(player, weeklyCompletionCommands)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&d&l★ Weekly Quests Complete! &eBonus reward granted!"))

        if (questMasterEnabled) {
            ensureQuestMasterRow(uuid.toString(), cycleId)
            plugin.databaseManager.execute(
                "UPDATE quest_master_progress SET weekly_complete = 1 WHERE uuid = ? AND weekly_cycle_id = ?", uuid.toString(), cycleId
            )
            checkQuestMasterReward(player, cycleId)
        }
    }

    private fun ensureQuestMasterRow(uuid: String, weeklyCycleId: String) {
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO quest_master_progress (uuid, weekly_cycle_id, daily_sets, weekly_complete, rewarded) VALUES (?, ?, 0, 0, 0)",
            uuid, weeklyCycleId
        )
    }

    private fun creditQuestMasterDailySet(player: Player, dailyCycleId: String) {
        val weeklyCycleId = weeklyCycleIdForDate(LocalDate.parse(dailyCycleId)).toString()
        val uuid = player.uniqueId.toString()
        ensureQuestMasterRow(uuid, weeklyCycleId)
        plugin.databaseManager.execute(
            "UPDATE quest_master_progress SET daily_sets = daily_sets + 1 WHERE uuid = ? AND weekly_cycle_id = ?", uuid, weeklyCycleId
        )
        checkQuestMasterReward(player, weeklyCycleId)
    }

    private fun checkQuestMasterReward(player: Player, weeklyCycleId: String) {
        val uuid = player.uniqueId.toString()
        val state = getQuestMasterProgress(player.uniqueId, weeklyCycleId)
        if (state.rewarded) return
        if (state.dailySets < requiredDailySets) return
        if (requireWeeklyCompletion && !state.weeklyComplete) return

        val claimed = plugin.databaseManager.executeUpdate(
            "UPDATE quest_master_progress SET rewarded = 1 WHERE uuid = ? AND weekly_cycle_id = ? AND rewarded = 0", uuid, weeklyCycleId
        ) > 0
        if (!claimed) return

        runRewardCommands(player, questMasterCommands)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.5f)
        player.showTitle(
            Title.title(
                plugin.commsManager.parseLegacy("&6&l★ QUEST MASTER! ★"),
                plugin.commsManager.parseLegacy("&eYou earned the Quest Master reward!"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))
            )
        )
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&6&l★ Quest Master Complete! &eYou earned the special reward!"))
    }

    /** Admin/testing: force-complete an active quest for a player. */
    fun completeQuest(player: Player, questId: String): Boolean {
        val quest = questsById[questId.lowercase()] ?: return false
        val target = plugin.boosterManager.applyQuestBooster(quest.amount)
        return setProgress(player.uniqueId, quest.id, target)
    }

    // ── Anti-exploit / matching helpers ─────────────────────────

    private fun isExempt(player: Player) =
        player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR

    private fun activePool(): List<CycleQuest> = (dailyQuestIdsState + weeklyQuestIdsState).mapNotNull { questsById[it] }

    private fun matchingActiveQuests(objective: CycleObjective, predicate: (String) -> Boolean): List<CycleQuest> =
        activePool().filter { it.objective == objective && (it.target == "ANY" || predicate(it.target)) }

    private fun oreVariants(name: String): Set<String> {
        val set = mutableSetOf(name)
        if (name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) set.add(name.removePrefix("DEEPSLATE_"))
        if (!name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) set.add("DEEPSLATE_$name")
        return set
    }

    private fun logFamily(materialName: String): String? {
        for (family in LOG_FAMILIES) {
            if (materialName == "${family}_LOG" || materialName == "STRIPPED_${family}_LOG" ||
                materialName == "${family}_WOOD" || materialName == "STRIPPED_${family}_WOOD" ||
                materialName == "${family}_HYPHAE" || materialName == "STRIPPED_${family}_HYPHAE"
            ) return family
        }
        return null
    }

    private fun normalizeHarvestName(name: String): String = when (name) {
        "KELP_PLANT" -> "KELP"
        "TWISTING_VINES_PLANT" -> "TWISTING_VINES"
        "WEEPING_VINES_PLANT" -> "WEEPING_VINES"
        else -> name
    }

    // ── Event handlers ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (isExempt(player)) return
        val block = event.block

        processMinedBlock(player, block)

        val name = block.type.name
        val normalized = normalizeHarvestName(name)
        val blockData = block.blockData
        val eligible = (blockData is Ageable && blockData.age == blockData.maximumAge) || normalized in ALWAYS_HARVESTABLE
        if (eligible) {
            for (quest in matchingActiveQuests(CycleObjective.HARVEST_CROP) { target -> target.equals(normalized, true) }) {
                incrementProgress(player, quest, 1)
            }
        }
    }

    private fun processMinedBlock(player: Player, block: org.bukkit.block.Block) {
        val name = block.type.name
        val playerPlaced = block.hasMetadata(PLACED_META)
        block.removeMetadata(PLACED_META, plugin)

        // Anti-farm: a block the player placed themselves doesn't count toward mining/logging
        // quests (prevents place-then-break loops on e.g. a placed Diamond Ore block).
        if (playerPlaced) return

        for (quest in matchingActiveQuests(CycleObjective.MINE_BLOCK) { target -> oreVariants(name).any { it.equals(target, true) } }) {
            incrementProgress(player, quest, 1)
        }
        val brokenFamily = logFamily(name)
        if (brokenFamily != null) {
            for (quest in matchingActiveQuests(CycleObjective.BREAK_LOG) { target -> logFamily(target) == brokenFamily }) {
                incrementProgress(player, quest, 1)
            }
        }
    }

    /** External hook for listeners (e.g. Veinminer) that break blocks without firing a real BlockBreakEvent. */
    fun recordBlockBreak(player: Player, block: org.bukkit.block.Block) {
        if (isExempt(player)) return
        processMinedBlock(player, block)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        event.block.setMetadata(PLACED_META, FixedMetadataValue(plugin, true))
        val player = event.player
        if (isExempt(player)) return
        for (quest in matchingActiveQuests(CycleObjective.PLACE_BLOCK) { true }) {
            incrementProgress(player, quest, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (entity is Player) return
        val killer = entity.killer ?: return
        if (isExempt(killer)) return
        if (entity.scoreboardTags.contains("joshymc_combat_npc") || entity.scoreboardTags.contains("NPC")) return

        val name = entity.type.name
        val isHostile = entity is Monster
        val isNetherHostile = isHostile && entity.world.environment == World.Environment.NETHER
        val isPassiveAnimal = entity is Animals

        for (quest in matchingActiveQuests(CycleObjective.KILL_MOB) { target ->
            when (target) {
                "HOSTILE" -> isHostile
                "NETHER_HOSTILE" -> isNetherHostile
                "PASSIVE_ANIMAL" -> isPassiveAnimal
                else -> target.equals(name, true)
            }
        }) {
            incrementProgress(killer, quest, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val player = event.breeder as? Player ?: return
        if (isExempt(player)) return
        for (quest in matchingActiveQuests(CycleObjective.BREED_ANIMAL) { it.equals(event.entityType.name, true) }) {
            incrementProgress(player, quest, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        if (isExempt(player)) return
        val caughtName = (event.caught as? org.bukkit.entity.Item)?.itemStack?.type?.name
        for (quest in matchingActiveQuests(CycleObjective.CATCH_FISH) { target -> caughtName != null && target.equals(caughtName, true) }) {
            incrementProgress(player, quest, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val player = event.player
        if (isExempt(player)) return
        val name = event.itemType.name
        for (quest in matchingActiveQuests(CycleObjective.SMELT_ITEM) { target ->
            if (target == "ORE_PRODUCT") name in ORE_PRODUCTS else target.equals(name, true)
        }) {
            incrementProgress(player, quest, event.itemAmount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (isExempt(player)) return
        val result = event.recipe.result
        val amount = if (event.isShiftClick) {
            var minStack = Int.MAX_VALUE
            for (item in event.inventory.matrix) {
                if (item != null && item.type != Material.AIR) minStack = minOf(minStack, item.amount)
            }
            if (minStack == Int.MAX_VALUE) result.amount else minStack * result.amount
        } else result.amount
        for (quest in matchingActiveQuests(CycleObjective.CRAFT_ITEM) { true }) {
            incrementProgress(player, quest, amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val player = event.enchanter
        if (isExempt(player)) return
        for (quest in matchingActiveQuests(CycleObjective.ENCHANT_ITEM) { true }) {
            incrementProgress(player, quest, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onTrade(event: PlayerTradeEvent) {
        val player = event.player
        if (isExempt(player)) return
        for (quest in matchingActiveQuests(CycleObjective.TRADE_VILLAGER) { true }) {
            incrementProgress(player, quest, 1)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLevelChange(event: PlayerLevelChangeEvent) {
        val player = event.player
        if (isExempt(player)) return
        val gained = event.newLevel - event.oldLevel
        if (gained <= 0 || gained > 10) return
        for (quest in matchingActiveQuests(CycleObjective.GAIN_LEVELS) { true }) {
            incrementProgress(player, quest, gained)
        }
    }

    @EventHandler
    fun onExpChange(event: PlayerExpChangeEvent) {
        val player = event.player
        if (isExempt(player)) return
        val gained = event.amount
        if (gained <= 0) return
        for (quest in matchingActiveQuests(CycleObjective.GAIN_XP) { true }) {
            incrementProgress(player, quest, gained)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.world != to.world) return
        val player = event.player
        if (isExempt(player)) return
        if (player.isFlying || player.isGliding || player.isInsideVehicle) return

        val dx = to.x - from.x
        val dz = to.z - from.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        if (dist < 0.01) return
        val uuid = player.uniqueId

        val acc = travelAcc.getOrDefault(uuid, 0.0) + dist
        val blocks = acc.toInt()
        if (blocks >= 10) {
            travelAcc[uuid] = acc - blocks
            for (quest in matchingActiveQuests(CycleObjective.TRAVEL) { true }) incrementProgress(player, quest, blocks)
        } else {
            travelAcc[uuid] = acc
        }

        if (player.world.environment == World.Environment.NETHER) {
            val netherAcc = travelNetherAcc.getOrDefault(uuid, 0.0) + dist
            val netherBlocks = netherAcc.toInt()
            if (netherBlocks >= 10) {
                travelNetherAcc[uuid] = netherAcc - netherBlocks
                for (quest in matchingActiveQuests(CycleObjective.TRAVEL_NETHER) { true }) incrementProgress(player, quest, netherBlocks)
            } else {
                travelNetherAcc[uuid] = netherAcc
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        flushProgress(uuid)
        progressCache.remove(uuid)
        travelAcc.remove(uuid)
        travelNetherAcc.remove(uuid)
        trackedGuiPlayers.remove(uuid)
    }

    // ── GUI ─────────────────────────────────────────────────────

    private val trackedGuiPlayers = mutableSetOf<UUID>()

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    fun openGui(player: Player) {
        val gui = CustomGui(plugin.commsManager.parseLegacy("&6&lQuests"), 54)
        renderInto(gui, player)
        plugin.guiManager.open(player, gui)
        gui.onClose = { trackedGuiPlayers.remove(it.uniqueId) }
        trackedGuiPlayers.add(player.uniqueId)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun tickOpenGuis() {
        if (trackedGuiPlayers.isEmpty()) return
        for (uuid in trackedGuiPlayers.toList()) {
            val player = Bukkit.getPlayer(uuid)
            if (player == null) {
                trackedGuiPlayers.remove(uuid)
                continue
            }
            val gui = plugin.guiManager.getOpenGui(player)
            if (gui == null) {
                trackedGuiPlayers.remove(uuid)
                continue
            }
            renderInto(gui, player)
        }
    }

    private fun renderInto(gui: CustomGui, player: Player) {
        gui.border(BORDER.clone())
        gui.fill(FILLER.clone())

        val uuid = player.uniqueId

        val dailyPool = getDailyPool()
        gui.setItem(10, headerItem("Daily Quests", NamedTextColor.GOLD, dailySecondsLeft()))
        val dailySlots = listOf(19, 28, 37)
        for ((i, quest) in dailyPool.withIndex()) {
            if (i >= dailySlots.size) break
            val progress = getProgress(uuid, quest)
            gui.setItem(dailySlots[i], buildQuestItem(quest, progress)) { p, _ -> showQuestDetails(p, quest, getProgress(p.uniqueId, quest)) }
        }
        val dailyDone = dailyPool.count { getProgress(uuid, it).completed }
        gui.setItem(46, bonusItem("Daily Completion", dailyDone, dailyPool.size, 1, isDailyBonusClaimed(uuid, dailyCycleIdState)))

        val weeklyPool = getWeeklyPool()
        gui.setItem(16, headerItem("Weekly Quests", NamedTextColor.AQUA, weeklySecondsLeft()))
        val weeklySlots = listOf(25, 34, 43)
        for ((i, quest) in weeklyPool.withIndex()) {
            if (i >= weeklySlots.size) break
            val progress = getProgress(uuid, quest)
            gui.setItem(weeklySlots[i], buildQuestItem(quest, progress)) { p, _ -> showQuestDetails(p, quest, getProgress(p.uniqueId, quest)) }
        }
        val weeklyDone = weeklyPool.count { getProgress(uuid, it).completed }
        gui.setItem(52, bonusItem("Weekly Completion", weeklyDone, weeklyPool.size, 3, isWeeklyBonusClaimed(uuid, weeklyCycleIdState)))

        val qm = getQuestMasterProgress(uuid, weeklyCycleIdState)
        gui.setItem(13, questMasterItem(qm))

        gui.setItem(49, infoItem())
    }

    private fun showQuestDetails(player: Player, quest: CycleQuest, progress: CycleProgress) {
        val target = plugin.boosterManager.applyQuestBooster(quest.amount)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&e--- ${quest.name} ---"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  &7${quest.description}"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "  &7Progress: &a${progress.progress}&7/&a$target ${if (progress.completed) "&a✔ COMPLETED" else ""}"
        ))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  &7Reward: &6\$${plugin.economyManager.formatShort(quest.reward)}"))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
    }

    private fun headerItem(title: String, color: NamedTextColor, secondsLeft: Long): ItemStack {
        val item = ItemStack(Material.CLOCK)
        item.editMeta { meta ->
            meta.displayName(Component.text(title, color).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Resets in: ${formatTimeLeft(secondsLeft)}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ))
        }
        return item
    }

    private fun buildQuestItem(quest: CycleQuest, progress: CycleProgress): ItemStack {
        val target = plugin.boosterManager.applyQuestBooster(quest.amount)
        val item = ItemStack(quest.icon)
        item.editMeta { meta ->
            val color = when (quest.type) {
                CycleQuestType.DAILY_MEDIUM -> NamedTextColor.YELLOW
                CycleQuestType.DAILY_HARD -> NamedTextColor.RED
                CycleQuestType.WEEKLY -> NamedTextColor.LIGHT_PURPLE
            }
            meta.displayName(Component.text(quest.name, color).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))

            val lore = mutableListOf<Component>()
            lore += Component.empty()
            lore += Component.text("  ${quest.description}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            lore += Component.empty()
            val filled = if (target > 0) ((progress.progress.toDouble() / target) * 10).toInt().coerceIn(0, 10) else 10
            lore += plugin.commsManager.parseLegacy(
                "  &a${"█".repeat(filled)}&7${"░".repeat(10 - filled)} &f${progress.progress}/$target"
            ).decoration(TextDecoration.ITALIC, false)
            lore += Component.empty()
            lore += Component.text("  Reward: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("$" + plugin.economyManager.formatShort(quest.reward), NamedTextColor.GOLD))
            lore += Component.empty()
            lore += if (progress.completed) {
                Component.text("  ✔ COMPLETED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
            } else {
                Component.text("  IN PROGRESS", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
            }
            meta.lore(lore)
            if (progress.completed) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    private fun bonusItem(title: String, done: Int, total: Int, keys: Int, claimed: Boolean): ItemStack {
        val item = ItemStack(if (claimed) Material.LIME_DYE else if (done >= total && total > 0) Material.LIME_DYE else Material.GRAY_DYE)
        item.editMeta { meta ->
            meta.displayName(Component.text(title.uppercase(), NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            lore += Component.text("  $done / $total Quests Complete", if (done >= total && total > 0) NamedTextColor.GREEN else NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
            lore += Component.empty()
            lore += Component.text("  Reward: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("$keys Crate Key${if (keys != 1) "s" else ""}", NamedTextColor.AQUA))
            lore += Component.empty()
            lore += when {
                claimed -> Component.text("  ✔ REWARDED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                done >= total && total > 0 -> Component.text("  ✔ COMPLETED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                else -> Component.text("  Complete all quests to earn this!", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
            }
            meta.lore(lore)
            if (claimed) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    private fun questMasterItem(qm: QuestMasterState): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(Component.text("QUEST MASTER", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            val dailyColor = if (qm.dailySets >= requiredDailySets) NamedTextColor.GREEN else NamedTextColor.GRAY
            lore += Component.text("  Daily Sets: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("${qm.dailySets} / $requiredDailySets", dailyColor))
            val weeklyColor = if (!requireWeeklyCompletion || qm.weeklyComplete) NamedTextColor.GREEN else NamedTextColor.GRAY
            lore += Component.text("  Weekly Quests: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(if (requireWeeklyCompletion) (if (qm.weeklyComplete) "3 / 3 ✔" else "in progress") else "not required", weeklyColor))
            lore += Component.empty()
            lore += Component.text("  Reward: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text("100 Credits", NamedTextColor.GOLD))
            lore += Component.empty()
            lore += when {
                qm.rewarded -> Component.text("  ✔ REWARDED", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false)
                qm.dailySets >= requiredDailySets && (!requireWeeklyCompletion || qm.weeklyComplete) ->
                    Component.text("  Complete both requirements soon!", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)
                else -> Component.text("  Complete both before the weekly reset!", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
            }
            lore += Component.text("  Ends in: ${formatTimeLeft(weeklySecondsLeft())}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            meta.lore(lore)
            if (qm.rewarded) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    private fun infoItem(): ItemStack {
        val item = ItemStack(Material.BOOK)
        item.editMeta { meta ->
            meta.displayName(Component.text("How This Works", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Complete Daily & Weekly quests", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  for automatic rewards.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Finish 5 Daily sets + all Weekly", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  quests this week to become", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  QUEST MASTER!", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false)
            ))
        }
        return item
    }

    private fun formatTimeLeft(seconds: Long): String {
        val d = seconds / 86400
        val h = (seconds % 86400) / 3600
        val m = (seconds % 3600) / 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${seconds}s"
        }
    }
}
