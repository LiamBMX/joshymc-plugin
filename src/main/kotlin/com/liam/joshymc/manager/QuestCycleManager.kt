package com.liam.joshymc.manager

import io.papermc.paper.event.player.PlayerTradeEvent
import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.util.depositItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
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
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
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
import java.util.concurrent.ThreadLocalRandom

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

        /** /settings toggle for the quest progress action bar (issue #1278); on by default. */
        const val PROGRESS_NOTIFY_SETTING_KEY = "quest_progress_notifications"

        /** A progress notification waits while another system's action bar is this recent. */
        private const val OTHER_ACTION_BAR_GRACE_MS = 2000L

        /** How many recent fishing hooks are remembered so one catch never counts twice. */
        private const val COUNTED_HOOKS_MAX = 512

        /** Credits for finishing every Daily / every Weekly quest of a cycle (issue #1223). */
        const val DEFAULT_DAILY_COMPLETION_CREDITS = 250.0
        const val DEFAULT_WEEKLY_COMPLETION_CREDITS = 750.0

        /** Multiplier on the money reward of every Daily/Weekly quest (issue #1008). */
        private const val MONEY_REWARD_MULTIPLIER = 1.5
        private val ORE_PRODUCTS = setOf("IRON_INGOT", "GOLD_INGOT", "COPPER_INGOT")
        private val LOG_FAMILIES = setOf(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK", "MANGROVE",
            "CHERRY", "PALE_OAK", "CRIMSON", "WARPED"
        )
        private val ALWAYS_HARVESTABLE = setOf(
            "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO", "NETHER_WART",
            "COCOA", "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES", "WEEPING_VINES"
        )

        /**
         * Daily Quest completion reward: exactly one real crate key, weighted. These are
         * CrateManager crate type ids (see crates.yml / `/crate list`), delivered via
         * [CrateManager.createKeyStack] — NOT ItemManager custom item ids. A custom item
         * built from RetexturedKeys.kt looks like a key but carries no crate-key PDC tag,
         * so it silently fails to open any crate (issue #986).
         */
        private val DAILY_COMPLETION_KEY_WEIGHTS = listOf(
            "harvest" to 70,
            "stockpile" to 20,
            "homestead" to 10
        )

        /** Weekly Quest completion reward (issue #969): exactly one real crate key, weighted. */
        private val WEEKLY_KEY_WEIGHTS = listOf(
            "homestead" to 70,
            "campfire" to 25,
            "cabin" to 5
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
    private var dailyCompletionCredits = DEFAULT_DAILY_COMPLETION_CREDITS

    private var selectedWeekly = 3
    private var weeklyResetDay = DayOfWeek.MONDAY
    private var weeklyResetHour = 0
    private var weeklyResetMinute = 0
    private var weeklyRepeatProtectionWeeks = 4
    private var weeklyCompletionRewardEnabled = true
    private var weeklyCompletionCommands: List<String> = emptyList()
    private var weeklyCompletionCredits = DEFAULT_WEEKLY_COMPLETION_CREDITS

    private var questMasterEnabled = true
    private var requiredDailySets = 5
    private var requireWeeklyCompletion = true
    private var questMasterCommands: List<String> = emptyList()

    private var progressNotificationsEnabled = true
    private var progressNotifier = QuestProgressThrottle<UUID>(3000L)

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
        dailyCompletionCredits = cfg.getDouble("quests.daily.completion-reward.credits", DEFAULT_DAILY_COMPLETION_CREDITS)

        selectedWeekly = cfg.getInt("quests.weekly.selected", 3)
        weeklyResetDay = runCatching { DayOfWeek.valueOf((cfg.getString("quests.weekly.reset.day", "MONDAY") ?: "MONDAY").uppercase()) }
            .getOrDefault(DayOfWeek.MONDAY)
        weeklyResetHour = cfg.getInt("quests.weekly.reset.hour", 0)
        weeklyResetMinute = cfg.getInt("quests.weekly.reset.minute", 0)
        weeklyRepeatProtectionWeeks = cfg.getInt("quests.weekly.repeat-protection-weeks", 4)
        weeklyCompletionRewardEnabled = cfg.getBoolean("quests.weekly.completion-reward.enabled", true)
        weeklyCompletionCommands = cfg.getStringList("quests.weekly.completion-reward.commands")
        weeklyCompletionCredits = cfg.getDouble("quests.weekly.completion-reward.credits", DEFAULT_WEEKLY_COMPLETION_CREDITS)

        questMasterEnabled = cfg.getBoolean("quests.quest-master.enabled", true)
        requiredDailySets = cfg.getInt("quests.quest-master.required-daily-sets", 5)
        requireWeeklyCompletion = cfg.getBoolean("quests.quest-master.require-weekly-completion", true)
        questMasterCommands = cfg.getStringList("quests.quest-master.reward.commands")

        progressNotificationsEnabled = cfg.getBoolean("quests.progress-notifications.enabled", true)
        val intervalMs = (cfg.getDouble("quests.progress-notifications.interval-seconds", 3.0) * 1000).toLong().coerceAtLeast(500L)
        progressNotifier = QuestProgressThrottle(intervalMs)
    }

    /** Credits for finishing every Daily quest of a cycle, or null when that completion reward is off. */
    fun dailyCompletionCreditsReward(): Double? = if (dailyCompletionRewardEnabled) dailyCompletionCredits else null

    /** Credits for finishing every Weekly quest of a cycle, or null when that completion reward is off. */
    fun weeklyCompletionCreditsReward(): Double? = if (weeklyCompletionRewardEnabled) weeklyCompletionCredits else null

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
        val baseReward = s.getDouble("reward", -1.0)
        if (baseReward < 0) return invalid(id, "reward must be >= 0")
        // Applied once, here, so the payout and every GUI/chat preview (all read quest.reward) agree.
        val reward = baseReward * MONEY_REWARD_MULTIPLIER
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

        if (com.liam.joshymc.util.ConfigUtil.looksLikeParseFailure(userFile, userCfg)) {
            plugin.logger.severe("[QuestCycle] quest-cycle.yml failed to load (invalid YAML) — the existing file has been preserved and was NOT overwritten. Fix the syntax error and reload.")
            return
        }

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
                com.liam.joshymc.util.ConfigUtil.backup(userFile, plugin.logger, "QuestCycle")
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
            // Save cached progress under the OLD cycle ids before the cache is dropped, or
            // weekly progress made since the last periodic flush is lost (issue #1278).
            // Queued DB work runs before any later read, so the reload sees these rows.
            flushAllProgress()
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
            flushAllProgress()
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
        flushAllProgress()
        dailyQuestIdsState = generateDailyPool(dailyCycleIdState)
        persistCycleState("daily", dailyCycleIdState, dailyQuestIdsState)
        progressCache.clear()
        travelAcc.clear()
        travelNetherAcc.clear()
    }

    fun forceWeeklyReset() {
        flushAllProgress()
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
        // Snapshot on the main thread (the cached maps are mutated there); the write is queued on the DB thread.
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { flushAllProgress() }, 6000L, 6000L)
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickProgressNotifications() }, 5L, 5L)

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
        progressNotifier.forgetAll()
        hookCatches.clear()
        countedHooks.clear()
    }

    /**
     * Creates the quest schema. Called unconditionally on plugin startup (independent of
     * the "quests" feature toggle) so /quests never hits a missing-table error even when
     * the feature is disabled or hasn't been [start]ed yet — and again from [start] so
     * re-enabling the feature later (e.g. via reload) is a no-op against an existing schema.
     */
    fun createTables() {
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
        // reward_key: which key was rolled for this completion. reward_delivered: whether it has
        // actually reached the player's inventory/backup yet — lets a full-inventory delivery stay
        // recoverable (retried on next join) instead of being lost.
        try { plugin.databaseManager.execute("ALTER TABLE quest_daily_completion ADD COLUMN reward_key TEXT") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE quest_daily_completion ADD COLUMN reward_delivered INTEGER NOT NULL DEFAULT 0") } catch (_: Exception) {}

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

        // Records the single weighted-random crate key rolled for a player's Weekly Quest
        // completion. The row itself (not just quest_weekly_completion.bonus_claimed) is
        // the source of truth for "which key" so a delivery that didn't fit on the first
        // try (full inventory/backup) can be safely retried later without re-rolling.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_weekly_key_reward (
                uuid TEXT NOT NULL,
                weekly_cycle_id TEXT NOT NULL,
                key_id TEXT NOT NULL,
                delivered INTEGER NOT NULL DEFAULT 0,
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
            "SELECT quest_id, cycle_id, progress, completed, reward_claimed FROM quest_cycle_progress WHERE uuid = ? AND cycle_id IN (?, ?)",
            uuid.toString(), dailyCycleIdState, weeklyCycleIdState
        ) { rs ->
            // On the weekly reset day the daily and weekly cycle ids are the same date, so the
            // IN above also matches that day's daily rows for the rest of the week. Keep a row
            // only when it belongs to its quest's own current cycle, or an old day's daily
            // progress would carry over onto a quest picked again later (issue #1292).
            val questId = rs.getString("quest_id")
            val quest = questsById[questId] ?: return@query
            if (rs.getString("cycle_id") != cycleIdFor(quest)) return@query
            map[questId] = CycleProgress(
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
        val target = effectiveTarget(uuid, quest)
        val newProgress = (current.progress + amount).coerceIn(0, target)
        val completed = newProgress >= target
        map[quest.id] = current.copy(progress = newProgress, completed = completed)
        if (completed) {
            // The completion chat message covers it; drop a pending progress update for this quest.
            progressNotifier.discard(uuid, quest.id)
            onQuestCompleted(player, quest)
        } else if (progressNotificationsEnabled) {
            progressNotifier.offer(uuid, quest.id)
        }
    }

    /**
     * The one requirement a player must reach on [quest] (issue #1278): the base amount
     * scaled by their Resurge multiplier, then lowered by an active quest booster. Progress
     * tracking, the GUI, quest details, the admin commands and notifications all use it.
     */
    fun effectiveTarget(uuid: UUID, quest: CycleQuest): Int =
        plugin.boosterManager.applyQuestBooster(plugin.resurgeManager.getEffectiveAmount(uuid, quest.amount))

    /**
     * Shows each player's most recently advanced quest on the action bar, at most once per
     * configured interval (issue #1278). Skipped for players who turned it off in /settings,
     * and held back while they're combat-tagged or another system's action bar is recent,
     * so those aren't overwritten.
     */
    private fun tickProgressNotifications() {
        if (!progressNotificationsEnabled) return
        val now = System.currentTimeMillis()
        for ((uuid, questId) in progressNotifier.due(now)) {
            val player = Bukkit.getPlayer(uuid)
            val quest = questsById[questId]
            if (player == null || quest == null || (questId !in dailyQuestIdsState && questId !in weeklyQuestIdsState) ||
                !plugin.settingsManager.getSetting(player, PROGRESS_NOTIFY_SETTING_KEY)
            ) {
                progressNotifier.clear(uuid)
                continue
            }
            if (plugin.combatManager.isTagged(player)) continue
            if (now - plugin.commsManager.lastActionBarAt(player) < OTHER_ACTION_BAR_GRACE_MS) continue

            val progress = getProgress(uuid, quest)
            if (progress.completed) {
                progressNotifier.clear(uuid)
                continue
            }
            val target = effectiveTarget(uuid, quest)
            val shown = QuestProgressDisplay.shownProgress(progress.progress, target, false)
            player.sendActionBar(
                Component.text("Quest Progress: ", NamedTextColor.GOLD)
                    .append(Component.text(quest.name, NamedTextColor.YELLOW))
                    .append(Component.text(" $shown/$target", NamedTextColor.WHITE))
            )
            progressNotifier.markSent(uuid, now)
        }
    }

    /** Admin/testing: directly set a player's progress on one of the currently active quests. */
    fun setProgress(uuid: UUID, questId: String, amount: Int): Boolean {
        val quest = questsById[questId.lowercase()] ?: return false
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val target = effectiveTarget(uuid, quest)
        val clamped = amount.coerceIn(0, target)
        val wasCompleted = map[quest.id]?.completed == true
        map[quest.id] = CycleProgress(clamped, clamped >= target, map[quest.id]?.rewardClaimed ?: false)
        if (!wasCompleted && clamped >= target) {
            Bukkit.getPlayer(uuid)?.let { onQuestCompleted(it, quest) }
        }
        return true
    }

    /**
     * Snapshots the player's cached progress on the calling thread and queues the batch
     * upsert on the DB thread, so quit/periodic flushes never wait on disk (issue #1112).
     */
    private fun flushProgress(uuid: UUID) {
        val map = progressCache[uuid] ?: return
        val rows = map.mapNotNull { (questId, entry) ->
            val quest = questsById[questId] ?: return@mapNotNull null
            Triple(questId, cycleIdFor(quest), entry)
        }
        if (rows.isEmpty()) return
        plugin.databaseManager.async("quest progress flush for $uuid") {
            plugin.databaseManager.transaction {
                for ((questId, cycleId, entry) in rows) {
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
    private fun claimQuestRewardOnce(uuid: UUID, quest: CycleQuest): Boolean {
        val cycleId = cycleIdFor(quest)
        // The player's real (Resurge/booster) target, so a reloaded completed quest reads N/N.
        val target = effectiveTarget(uuid, quest)
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO quest_cycle_progress (uuid, quest_id, cycle_id, progress, completed, reward_claimed) VALUES (?, ?, ?, ?, 1, 0)",
            uuid.toString(), quest.id, cycleId, target
        )
        return plugin.databaseManager.executeUpdate(
            "UPDATE quest_cycle_progress SET completed = 1, progress = ?, reward_claimed = 1 WHERE uuid = ? AND quest_id = ? AND cycle_id = ? AND reward_claimed = 0",
            target, uuid.toString(), quest.id, cycleId
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
        if (!claimQuestRewardOnce(player.uniqueId, quest)) return

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
        val credits = if (dailyCompletionRewardEnabled) dailyCompletionCredits else 0.0
        val paid = claimBonusWithCredits(uuid, cycleId, credits) { claimDailyBonusOnce(uuid.toString(), cycleId) } ?: return

        if (dailyCompletionRewardEnabled) {
            val keyId = rollDailyCompletionKey()
            plugin.databaseManager.execute(
                "UPDATE quest_daily_completion SET reward_key = ? WHERE uuid = ? AND daily_cycle_id = ?",
                keyId, uuid.toString(), cycleId
            )
            deliverDailyCompletionReward(player, uuid.toString(), cycleId, keyId)
            runRewardCommands(player, dailyCompletionCommands)
        }
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.0f)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&6&l★ Daily Quests Complete! &eBonus reward granted!${creditsSuffix(paid)}"))

        if (questMasterEnabled) creditQuestMasterDailySet(player, cycleId)
    }

    /**
     * Claims a set-completion bonus through [claim] (the once-per-cycle conditional UPDATE)
     * and deposits [credits] Credits by UUID in the same transaction (issue #1223), so the
     * claim and the payout happen together or not at all: a restart or a second completion
     * check can never pay twice, and a failed deposit rolls the claim back instead of marking
     * it paid. Returns the Credits paid (0 when none are configured), or null when the bonus
     * was already claimed this cycle or the transaction failed.
     */
    private fun claimBonusWithCredits(uuid: UUID, cycleId: String, credits: Double, claim: () -> Boolean): Double? {
        var paid: Double? = null
        try {
            plugin.databaseManager.transaction {
                if (!claim()) return@transaction
                paid = if (credits > 0.0 && plugin.creditsManager.deposit(uuid, credits)) credits else 0.0
            }
        } catch (e: Exception) {
            plugin.logger.severe("[QuestCycle] Completion bonus for $uuid (cycle $cycleId) failed and was rolled back: ${e.message}")
            return null
        }
        return paid
    }

    private fun creditsSuffix(credits: Double): String =
        if (credits > 0.0) " &b+${formatCredits(credits)} Credits" else ""

    private fun formatCredits(credits: Double): String =
        if (credits % 1.0 == 0.0) "%,d".format(java.util.Locale.US, credits.toLong()) else plugin.creditsManager.format(credits)

    /** Weighted pick among the Daily Quest completion keys — Harvest 70% / Stockpile 20% / Homestead 10%. */
    private fun rollDailyCompletionKey(): String {
        val totalWeight = DAILY_COMPLETION_KEY_WEIGHTS.sumOf { it.second }
        var roll = (Math.random() * totalWeight).toInt()
        for ((id, weight) in DAILY_COMPLETION_KEY_WEIGHTS) {
            roll -= weight
            if (roll < 0) return id
        }
        return DAILY_COMPLETION_KEY_WEIGHTS.last().first
    }

    /**
     * Hands [keyId] to [player] via the authoritative staff-mode-aware delivery path. If it
     * doesn't fully fit, the reward stays flagged undelivered in `quest_daily_completion` and
     * is retried the next time this player joins ([onJoinDeliverPendingDailyReward]) — it is
     * never dropped on the ground or silently lost.
     */
    private fun deliverDailyCompletionReward(player: Player, uuid: String, cycleId: String, keyId: String) {
        val item = plugin.crateManager.createKeyStack(keyId)
        if (item == null) {
            plugin.logger.warning("[QuestCycle] Daily completion reward key '$keyId' is not a registered crate type — reward left pending.")
            return
        }
        val leftover = plugin.depositItemSafely(player, item)
        if (leftover == null) {
            plugin.databaseManager.execute(
                "UPDATE quest_daily_completion SET reward_delivered = 1 WHERE uuid = ? AND daily_cycle_id = ?", uuid, cycleId
            )
        } else {
            plugin.commsManager.send(
                player,
                plugin.commsManager.parseLegacy("&eYour Daily Quest key didn't fit — free up inventory space and rejoin to receive it.")
            )
        }
    }

    /** Retries any Daily Quest completion key rewards that couldn't be delivered (e.g. full inventory) when they were rolled. */
    @EventHandler
    fun onJoinDeliverPendingDailyReward(event: PlayerJoinEvent) {
        val player = event.player
        val uuid = player.uniqueId.toString()
        val pending = plugin.databaseManager.query(
            "SELECT daily_cycle_id, reward_key FROM quest_daily_completion WHERE uuid = ? AND reward_delivered = 0 AND reward_key IS NOT NULL",
            uuid
        ) { rs -> rs.getString("daily_cycle_id") to rs.getString("reward_key") }
        if (pending.isEmpty()) return

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            for ((cycleId, keyId) in pending) {
                deliverDailyCompletionReward(player, uuid, cycleId, keyId)
            }
        }, 20L)
    }

    private fun checkWeeklySetCompletion(player: Player) {
        val uuid = player.uniqueId
        val pool = getWeeklyPool()
        if (pool.isEmpty()) return
        val map = progressCache[uuid] ?: return
        if (!pool.all { map[it.id]?.completed == true }) return

        val cycleId = weeklyCycleIdState
        val credits = if (weeklyCompletionRewardEnabled) weeklyCompletionCredits else 0.0
        val paid = claimBonusWithCredits(uuid, cycleId, credits) { claimWeeklyBonusOnce(uuid.toString(), cycleId) } ?: return

        if (weeklyCompletionRewardEnabled) {
            runRewardCommands(player, weeklyCompletionCommands)
            awardWeeklyKeyReward(player, cycleId)
        }
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&d&l★ Weekly Quests Complete! &eBonus reward granted!${creditsSuffix(paid)}"))

        if (questMasterEnabled) {
            ensureQuestMasterRow(uuid.toString(), cycleId)
            plugin.databaseManager.execute(
                "UPDATE quest_master_progress SET weekly_complete = 1 WHERE uuid = ? AND weekly_cycle_id = ?", uuid.toString(), cycleId
            )
            checkQuestMasterReward(player, cycleId)
        }
    }

    private fun rollWeeklyKeyId(): String {
        val total = WEEKLY_KEY_WEIGHTS.sumOf { it.second }
        var roll = ThreadLocalRandom.current().nextInt(total)
        for ((id, weight) in WEEKLY_KEY_WEIGHTS) {
            if (roll < weight) return id
            roll -= weight
        }
        return WEEKLY_KEY_WEIGHTS.last().first
    }

    /**
     * Rolls and persists the single weighted crate key for this player's Weekly Quest
     * completion, then attempts immediate delivery. The roll is written with INSERT OR
     * IGNORE keyed on (uuid, weekly_cycle_id) — combined with [claimWeeklyBonusOnce] already
     * having gated this call to exactly once per cycle, a key can never be rolled twice for
     * the same player/cycle, even across a restart or a racing duplicate completion tick.
     */
    private fun awardWeeklyKeyReward(player: Player, cycleId: String) {
        val uuid = player.uniqueId.toString()
        val keyId = rollWeeklyKeyId()
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO quest_weekly_key_reward (uuid, weekly_cycle_id, key_id, delivered) VALUES (?, ?, ?, 0)",
            uuid, cycleId, keyId
        )
        deliverPendingWeeklyKeyRewards(player)
    }

    /**
     * Delivers any undelivered weekly crate key rewards for [player] via the authoritative
     * staff-mode-aware item delivery path ([depositItemSafely] — routes into the ModMode
     * backup inventory while active, live inventory otherwise). A key that
     * doesn't fit anywhere stays `delivered = 0` and is retried here again on next join or
     * quest completion — it is never dropped on the ground or silently lost.
     */
    private fun deliverPendingWeeklyKeyRewards(player: Player) {
        val uuid = player.uniqueId.toString()
        val pending = plugin.databaseManager.query(
            "SELECT weekly_cycle_id, key_id FROM quest_weekly_key_reward WHERE uuid = ? AND delivered = 0",
            uuid
        ) { rs -> Pair(rs.getString("weekly_cycle_id"), rs.getString("key_id")) }
        if (pending.isEmpty()) return

        for ((rewardCycleId, keyId) in pending) {
            val crate = plugin.crateManager.getCrate(keyId)
            val item = plugin.crateManager.createKeyStack(keyId)
            if (crate == null || item == null) {
                plugin.logger.warning("[QuestCycle] Unknown weekly key reward crate '$keyId' for $uuid — skipping delivery.")
                continue
            }
            val leftover = plugin.depositItemSafely(player, item)
            if (leftover != null) continue // still doesn't fit — stays pending for the next retry

            plugin.databaseManager.execute(
                "UPDATE quest_weekly_key_reward SET delivered = 1 WHERE uuid = ? AND weekly_cycle_id = ?",
                uuid, rewardCycleId
            )
            plugin.commsManager.send(
                player,
                Component.text("★ Weekly Quest Reward! ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                    .append(Component.text("You received a ", NamedTextColor.YELLOW))
                    .append(Component.text(crate.keyName, TextColor.color(0xFFAA00)))
                    .append(Component.text("!", NamedTextColor.YELLOW))
            )
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f)
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { deliverPendingWeeklyKeyRewards(player) }, 20L)
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
        return setProgress(player.uniqueId, quest.id, effectiveTarget(player.uniqueId, quest))
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
        processBrokenBlock(player, event.block)
    }

    /** Mining/log progress plus crop harvests for one block about to be broken. */
    private fun processBrokenBlock(player: Player, block: org.bukkit.block.Block) {
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

    /**
     * External hook for blocks broken without a real BlockBreakEvent: Veinminer, Void Drill,
     * Tree Feller and the Explosive enchant (issue #1278). Call it right before the block is
     * broken, once per block. Air (already broken by something else) never counts, and the
     * usual rules apply: creative/spectator players and player-placed blocks don't count.
     */
    fun recordBlockBreak(player: Player, block: org.bukkit.block.Block) {
        if (block.type.isAir || isExempt(player)) return
        processBrokenBlock(player, block)
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
        // Hosted Mob Waves mobs (issue #1192) are event-only: killing them never counts toward quests.
        if (entity.scoreboardTags.contains(com.liam.joshymc.communityevent.game.MobWavesGame.EVENT_MOB_TAG)) return

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

    /** What vanilla pulled up on each hook, read before FishingManager swaps in a custom fish. */
    private val hookCatches = HashMap<UUID, Material>()

    /** Hooks whose catch already counted, so a catch never counts twice (double-fired events, fallback). */
    private val countedHooks = object : LinkedHashMap<UUID, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Boolean>?): Boolean = size > COUNTED_HOOKS_MAX
    }

    /**
     * FishingManager (HIGHEST) replaces every catch with a custom fish whose base item has
     * nothing to do with the species, so the species is read here first (issue #1278).
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFishCaptureSpecies(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val type = (event.caught as? org.bukkit.entity.Item)?.itemStack?.type ?: return
        hookCatches[event.hook.uniqueId] = type
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val species = hookCatches.remove(event.hook.uniqueId)
        if (event.isCancelled) return
        recordFishCatch(event.player, event.hook.uniqueId, species ?: (event.caught as? org.bukkit.entity.Item)?.itemStack?.type)
    }

    /**
     * Counts one catch on [hookId] toward CATCH_FISH quests: every catch counts for "ANY",
     * and [species] (the vanilla catch, or for FishingManager's high-Luck fallback the
     * custom fish's base item) decides Cod/Salmon quests. A hook counts at most once.
     */
    fun recordFishCatch(player: Player, hookId: UUID, species: Material?) {
        if (isExempt(player)) return
        if (countedHooks.put(hookId, true) != null) return
        val name = species?.name
        for (quest in matchingActiveQuests(CycleObjective.CATCH_FISH) { target -> name != null && target.equals(name, true) }) {
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

    // ignoreCancelled: a trade another plugin cancelled never happened, so it never counts (issue #1292).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
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

    /**
     * Horizontal walking distance of one move, measured once by [com.liam.joshymc.listener.TravelTrackingListener]
     * (already same-world, on foot and over 0.01 blocks). No upper cap here, unlike Event Quests.
     */
    fun recordTravel(player: Player, dist: Double) {
        if (isExempt(player)) return
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
        progressNotifier.forget(uuid)
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
            gui.setItem(dailySlots[i], buildQuestItem(quest, progress, effectiveTarget(uuid, quest))) { p, _ -> showQuestDetails(p, quest, getProgress(p.uniqueId, quest)) }
        }
        val dailyDone = dailyPool.count { getProgress(uuid, it).completed }
        gui.setItem(46, bonusItem("Daily Completion", dailyDone, dailyPool.size, 1, if (dailyCompletionRewardEnabled) dailyCompletionCredits else 0.0, isDailyBonusClaimed(uuid, dailyCycleIdState)))

        val weeklyPool = getWeeklyPool()
        gui.setItem(16, headerItem("Weekly Quests", NamedTextColor.AQUA, weeklySecondsLeft()))
        val weeklySlots = listOf(25, 34, 43)
        for ((i, quest) in weeklyPool.withIndex()) {
            if (i >= weeklySlots.size) break
            val progress = getProgress(uuid, quest)
            gui.setItem(weeklySlots[i], buildQuestItem(quest, progress, effectiveTarget(uuid, quest))) { p, _ -> showQuestDetails(p, quest, getProgress(p.uniqueId, quest)) }
        }
        val weeklyDone = weeklyPool.count { getProgress(uuid, it).completed }
        gui.setItem(52, bonusItem("Weekly Completion", weeklyDone, weeklyPool.size, 1, if (weeklyCompletionRewardEnabled) weeklyCompletionCredits else 0.0, isWeeklyBonusClaimed(uuid, weeklyCycleIdState)))

        val qm = getQuestMasterProgress(uuid, weeklyCycleIdState)
        gui.setItem(13, questMasterItem(qm))

        gui.setItem(49, infoItem())
    }

    private fun showQuestDetails(player: Player, quest: CycleQuest, progress: CycleProgress) {
        val target = effectiveTarget(player.uniqueId, quest)
        val shown = QuestProgressDisplay.shownProgress(progress.progress, target, progress.completed)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&e--- ${quest.name} ---"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy("  &7${quest.description}"))
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "  &7Progress: &a$shown&7/&a$target ${if (progress.completed) "&a✔ COMPLETED" else ""}"
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

    private fun buildQuestItem(quest: CycleQuest, progress: CycleProgress, target: Int): ItemStack {
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
            val cells = QuestProgressDisplay.BAR_CELLS
            val filled = QuestProgressDisplay.filledCells(progress.progress, target, progress.completed)
            val shown = QuestProgressDisplay.shownProgress(progress.progress, target, progress.completed)
            lore += plugin.commsManager.parseLegacy(
                "  &a${"█".repeat(filled)}&7${"░".repeat(cells - filled)} &f$shown/$target"
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

    private fun bonusItem(title: String, done: Int, total: Int, keys: Int, credits: Double, claimed: Boolean): ItemStack {
        val item = ItemStack(if (claimed) Material.LIME_DYE else if (done >= total && total > 0) Material.LIME_DYE else Material.GRAY_DYE)
        item.editMeta { meta ->
            meta.displayName(Component.text(title.uppercase(), NamedTextColor.GOLD).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore += Component.empty()
            lore += Component.text("  $done / $total Quests Complete", if (done >= total && total > 0) NamedTextColor.GREEN else NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
            lore += Component.empty()
            lore += Component.text("  Reward: ", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)
                .append(Component.text(
                    (if (credits > 0.0) "${formatCredits(credits)} Credits + " else "") + "$keys Crate Key${if (keys != 1) "s" else ""}",
                    NamedTextColor.AQUA
                ))
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
                .append(Component.text(questMasterRewardLabel(), NamedTextColor.GOLD))
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

    /** GUI reward text, read from the configured `credits give {player} N` commands so it always matches the payout. */
    private fun questMasterRewardLabel(): String {
        val credits = questMasterCommands.sumOf { cmd ->
            Regex("""^/?credits\s+give\s+\{player}\s+(\d+)\s*$""", RegexOption.IGNORE_CASE)
                .find(cmd.trim())?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        }
        return if (credits > 0) "%,d Credits".format(java.util.Locale.US, credits) else "Special Reward"
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
