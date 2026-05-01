package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Statistic
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ── Enums ──────────────────────────────────────────────────────

enum class QuestType {
    BREAK_BLOCK,
    PLACE_BLOCK,
    KILL_MOB,
    KILL_PLAYER,
    CATCH_FISH,
    CRAFT_ITEM,
    MINE_ORE,
    HARVEST_CROP,
    WALK_DISTANCE,
    EAT_FOOD,
    ENCHANT_ITEM,
    BREED_ANIMAL,
    TRADE_VILLAGER,
    SMELT_ITEM,
    LEVEL_UP,
    SELL_ITEMS,
    EARN_MONEY,
    TAME_ANIMAL,
    TIME_PLAYED,
    VISIT_BIOME,
    ITEM_PICKUP
}

enum class QuestDifficulty(val color: String, val displayName: String) {
    EASY("&a", "Easy"),
    MEDIUM("&e", "Medium"),
    HARD("&c", "Hard"),
    LEGENDARY("&6&l", "Legendary")
}

enum class QuestCategory(val displayName: String, val icon: Material) {
    MINING("Mining", Material.DIAMOND_PICKAXE),
    FARMING("Farming", Material.WHEAT),
    COMBAT("Combat", Material.DIAMOND_SWORD),
    FISHING("Fishing", Material.FISHING_ROD),
    EXPLORATION("Exploration", Material.COMPASS),
    TIME_PLAYED("Time Played", Material.CLOCK),
    CRAFTING("Crafting", Material.CRAFTING_TABLE),
    ECONOMY("Economy", Material.GOLD_INGOT),
    SOCIAL("Social", Material.PLAYER_HEAD),
    MISC("Miscellaneous", Material.NETHER_STAR)
}

// ── Data Classes ───────────────────────────────────────────────

data class Quest(
    val id: String,
    val name: String,
    val description: String,
    val category: QuestCategory,
    val difficulty: QuestDifficulty,
    val type: QuestType,
    val target: String,
    val amount: Int,
    val rewards: QuestRewards,
    val prerequisite: String? = null
)

data class QuestRewards(
    val money: Double = 0.0,
    val xp: Int = 0,
    val items: List<Pair<Material, Int>> = emptyList()
)

data class PlayerQuestProgress(
    val questId: String,
    val progress: Int,
    val completed: Boolean,
    val claimedReward: Boolean
)

// ── Manager ────────────────────────────────────────────────────

class QuestManager(private val plugin: Joshymc) : Listener {

    private val quests = mutableMapOf<String, Quest>()
    private val progressCache = ConcurrentHashMap<UUID, MutableMap<String, PlayerQuestProgress>>()
    private val distanceAccumulator = ConcurrentHashMap<UUID, Double>()
    private val visitedBiomes = ConcurrentHashMap<UUID, MutableSet<String>>()
    // TIME_PLAYED quests without an explicit prerequisite get chained by ascending
    // amount so only the next-tier quest is active (others show as locked).
    private val implicitPrerequisites = mutableMapOf<String, String>()

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // ── Lifecycle ──────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_progress (
                uuid TEXT NOT NULL,
                quest_id TEXT NOT NULL,
                progress INTEGER DEFAULT 0,
                completed INTEGER DEFAULT 0,
                claimed INTEGER DEFAULT 0,
                PRIMARY KEY (uuid, quest_id)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS quest_biomes (
                uuid TEXT NOT NULL,
                biome TEXT NOT NULL,
                PRIMARY KEY (uuid, biome)
            )
        """.trimIndent())

        loadQuests()
        reconcileStaleProgress()

        // Periodic flush every 5 minutes (6000 ticks)
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable { flushAllProgress() }, 6000L, 6000L)

        // TIME_PLAYED sync: every 60 seconds, set each online player's TIME_PLAYED
        // quest progress from their real PLAY_ONE_MINUTE statistic. Using the live
        // stat (instead of incrementing) keeps every quest in sync with the same
        // number and wipes stale progress left over from old quest definitions.
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                syncAllTimePlayedQuests(player)
            }
        }, 1200L, 1200L)

        // Biome discovery tick: every 3 seconds, check each online player's current biome
        // and record it if they haven't visited it before.
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                if (isExempt(player)) continue
                recordBiome(player)
            }
        }, 60L, 60L)

        plugin.logger.info("[Quests] Loaded ${quests.size} quests.")
    }

    fun stop() {
        flushAllProgress()
        progressCache.clear()
        distanceAccumulator.clear()
        visitedBiomes.clear()
    }

    /**
     * When a quest's amount or type changes between releases, previously saved
     * progress rows can fall out of sync with the new definition. Two cases:
     *
     *  1. amount grew (e.g. WALK_DISTANCE 500 → TIME_PLAYED 3600): old rows with
     *     completed=1 now have progress < amount. Unclaim + uncomplete.
     *  2. type changed and amount shrank (e.g. WALK_DISTANCE amount=700 →
     *     VISIT_BIOME amount=50): stored progress like "320 walked blocks" is
     *     nonsense for the new type and blows past the new max, so UIs show
     *     "320/50". For VISIT_BIOME we recompute from the authoritative
     *     quest_biomes table; for everything else we clamp to amount.
     */
    private fun reconcileStaleProgress() {
        // Build uuid → distinct biome count so we can recompute VISIT_BIOME
        // progress from the authoritative table rather than trusting stale rows.
        val biomeCountByUuid = mutableMapOf<String, Int>()
        plugin.databaseManager.query(
            "SELECT uuid, COUNT(*) AS n FROM quest_biomes GROUP BY uuid"
        ) { rs ->
            biomeCountByUuid[rs.getString("uuid")] = rs.getInt("n")
        }

        data class Update(
            val uuid: String,
            val questId: String,
            val newProgress: Int,
            val newCompleted: Boolean,
            val clearClaimed: Boolean
        )

        val updates = mutableListOf<Update>()
        plugin.databaseManager.query(
            "SELECT uuid, quest_id, progress, completed, claimed FROM quest_progress"
        ) { rs ->
            val uuid = rs.getString("uuid")
            val questId = rs.getString("quest_id")
            val progress = rs.getInt("progress")
            val completed = rs.getInt("completed") == 1
            val claimed = rs.getInt("claimed") == 1
            val quest = quests[questId] ?: return@query

            val effective = if (quest.type == QuestType.VISIT_BIOME) {
                biomeCountByUuid[uuid] ?: 0
            } else {
                progress
            }
            val clamped = effective.coerceAtMost(quest.amount).coerceAtLeast(0)
            val shouldBeCompleted = clamped >= quest.amount

            if (clamped == progress && shouldBeCompleted == completed) return@query

            updates += Update(
                uuid = uuid,
                questId = questId,
                newProgress = clamped,
                newCompleted = shouldBeCompleted,
                clearClaimed = !shouldBeCompleted && claimed
            )
        }

        for (u in updates) {
            if (u.clearClaimed) {
                plugin.databaseManager.execute(
                    "UPDATE quest_progress SET progress = ?, completed = ?, claimed = 0 WHERE uuid = ? AND quest_id = ?",
                    u.newProgress, if (u.newCompleted) 1 else 0, u.uuid, u.questId
                )
            } else {
                plugin.databaseManager.execute(
                    "UPDATE quest_progress SET progress = ?, completed = ? WHERE uuid = ? AND quest_id = ?",
                    u.newProgress, if (u.newCompleted) 1 else 0, u.uuid, u.questId
                )
            }
        }

        if (updates.isNotEmpty()) {
            plugin.logger.info("[Quests] Reconciled ${updates.size} stale quest progress row(s) after definition changes.")
        }
    }

    // ── Biome Tracking ─────────────────────────────────────────

    private fun recordBiome(player: Player) {
        val uuid = player.uniqueId
        val biome = player.location.block.biome.key.toString() // e.g. "minecraft:plains"
        val set = visitedBiomes.getOrPut(uuid) { loadBiomes(uuid) }
        if (set.add(biome)) {
            plugin.databaseManager.execute(
                "INSERT OR IGNORE INTO quest_biomes (uuid, biome) VALUES (?, ?)",
                uuid.toString(), biome
            )
            // Bump every active VISIT_BIOME quest by 1.
            findMatchingQuests(QuestType.VISIT_BIOME, "").forEach { quest ->
                if (canStart(uuid, quest.id)) incrementProgress(player, quest.id, 1)
            }
        }
    }

    private fun loadBiomes(uuid: UUID): MutableSet<String> {
        val set = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        plugin.databaseManager.query(
            "SELECT biome FROM quest_biomes WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            set.add(rs.getString("biome"))
        }
        return set
    }

    // ── Quest Registry ─────────────────────────────────────────

    fun getQuest(id: String): Quest? = quests[id]

    fun getAllQuests(): Collection<Quest> = quests.values

    fun getQuestsByCategory(category: QuestCategory): List<Quest> =
        quests.values.filter { it.category == category }

    // ── Player Progress ────────────────────────────────────────

    fun getPlayerProgress(uuid: UUID, questId: String): PlayerQuestProgress {
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        return playerMap[questId] ?: PlayerQuestProgress(questId, 0, false, false)
    }

    fun incrementProgress(player: Player, questId: String, amount: Int = 1) {
        val quest = quests[questId] ?: return
        val uuid = player.uniqueId
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val current = playerMap[questId] ?: PlayerQuestProgress(questId, 0, false, false)

        if (current.completed) return

        val newProgress = (current.progress + amount).coerceAtMost(quest.amount)
        val completed = newProgress >= quest.amount

        val updated = current.copy(progress = newProgress, completed = completed)
        playerMap[questId] = updated

        if (completed) {
            onQuestComplete(player, quest)
        }
    }

    fun isCompleted(uuid: UUID, questId: String): Boolean =
        getPlayerProgress(uuid, questId).completed

    fun canStart(uuid: UUID, questId: String): Boolean {
        val quest = quests[questId] ?: return false
        val prereq = effectivePrerequisite(quest) ?: return true
        return isCompleted(uuid, prereq)
    }

    private fun effectivePrerequisite(quest: Quest): String? =
        quest.prerequisite ?: implicitPrerequisites[quest.id]

    // ── TIME_PLAYED sync ───────────────────────────────────────

    private fun livePlaytimeSeconds(player: Player): Int {
        val ticks = player.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong()
        return (ticks / 20L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun syncAllTimePlayedQuests(player: Player) {
        if (isExempt(player)) return
        val seconds = livePlaytimeSeconds(player)
        // Sort by amount so that when quest N completes in-loop, quest N+1 unlocks
        // via canStart() and its progress gets synced in the same pass.
        findMatchingQuests(QuestType.TIME_PLAYED, "")
            .sortedBy { it.amount }
            .forEach { quest -> syncTimePlayedQuest(player, quest, seconds) }
    }

    private fun syncTimePlayedQuest(player: Player, quest: Quest, seconds: Int) {
        // Do NOT gate on canStart(): TIME_PLAYED quests chain via implicit
        // prerequisites (tier N requires tier N-1 complete), but the underlying
        // progress value should always reflect live playtime. Gating here leaves
        // later tiers stuck on whatever stale value was in the DB from a prior
        // quest-type definition (e.g. old WALK_DISTANCE numbers surviving type
        // changes), which is what caused "2h quest shows 16m" style bugs.
        val uuid = player.uniqueId
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val current = playerMap[quest.id] ?: PlayerQuestProgress(quest.id, 0, false, false)
        if (current.completed) return

        val newProgress = minOf(seconds, quest.amount)
        val completed = newProgress >= quest.amount
        if (newProgress == current.progress && completed == current.completed) return

        playerMap[quest.id] = current.copy(progress = newProgress, completed = completed)
        // Only fire the completion toast if this tier is actually reachable —
        // otherwise finishing tier 5 while tier 1 is still locked would spam
        // completion effects for quests the player never unlocked.
        if (completed && canStart(uuid, quest.id)) onQuestComplete(player, quest)
    }

    fun claimReward(player: Player, questId: String): Boolean {
        val quest = quests[questId] ?: return false
        val uuid = player.uniqueId
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val current = playerMap[questId] ?: return false

        if (!current.completed || current.claimedReward) return false

        playerMap[questId] = current.copy(claimedReward = true)

        // Give money
        if (quest.rewards.money > 0) {
            plugin.economyManager.deposit(uuid, quest.rewards.money)
        }

        // Give XP
        if (quest.rewards.xp > 0) {
            player.giveExp(quest.rewards.xp)
        }

        // Give items
        for ((material, count) in quest.rewards.items) {
            val remaining = player.inventory.addItem(ItemStack(material, count))
            // Drop overflow on the ground
            for (overflow in remaining.values) {
                player.world.dropItemNaturally(player.location, overflow)
            }
        }

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "&aReward claimed for &e${quest.name}&a!"
        ))

        return true
    }

    fun claimAllRewards(player: Player): Int {
        val uuid = player.uniqueId
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        var claimed = 0

        for ((questId, progress) in playerMap.toMap()) {
            if (progress.completed && !progress.claimedReward) {
                if (claimReward(player, questId)) claimed++
            }
        }

        return claimed
    }

    fun getUnclaimedCount(uuid: UUID): Int {
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        return playerMap.values.count { it.completed && !it.claimedReward }
    }

    fun getCompletedCount(uuid: UUID): Int {
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        return playerMap.values.count { it.completed }
    }

    fun getActiveQuests(uuid: UUID): List<Quest> {
        val playerMap = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        return playerMap.values
            .filter { it.progress > 0 && !it.completed }
            .mapNotNull { quests[it.questId] }
    }

    // ── Public tracking methods for external callers ───────────

    fun recordSell(player: Player, amount: Int) {
        findMatchingQuests(QuestType.SELL_ITEMS, "").forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) {
                incrementProgress(player, quest.id, amount)
            }
        }
    }

    fun recordEarning(player: Player, amount: Double) {
        findMatchingQuests(QuestType.EARN_MONEY, "").forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) {
                incrementProgress(player, quest.id, amount.toInt().coerceAtLeast(1))
            }
        }
    }

    // ── GUIs ───────────────────────────────────────────────────

    fun openCategoryMenu(player: Player) {
        syncAllTimePlayedQuests(player)

        val gui = CustomGui(
            plugin.commsManager.parseLegacy("&6&lQuests"),
            54
        )
        gui.border(BORDER.clone())
        gui.fill(FILLER.clone())

        val uuid = player.uniqueId
        val categories = QuestCategory.entries.filter { getQuestsByCategory(it).isNotEmpty() }
        val slots = centerSlots(categories.size)

        for ((i, category) in categories.withIndex()) {
            val categoryQuests = getQuestsByCategory(category)
            val completed = categoryQuests.count { isCompleted(uuid, it.id) }
            val total = categoryQuests.size

            val item = ItemStack(category.icon)
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(category.displayName, NamedTextColor.GOLD)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  $completed/$total Completed", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty(),
                    Component.text("  Click to view", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                ))
            }

            // Capture explicit local copy to avoid any closure surprises
            val cat = category
            gui.setItem(slots[i], item) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openQuestList(p, cat, 0)
            }
        }

        // Claim All Rewards button (slot 53, bottom right)
        val unclaimed = getUnclaimedCount(uuid)
        val claimAllItem = ItemStack(if (unclaimed > 0) Material.LIME_DYE else Material.GRAY_DYE)
        claimAllItem.editMeta { meta ->
            meta.displayName(
                Component.text("Claim All Rewards", NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  $unclaimed unclaimed reward${if (unclaimed != 1) "s" else ""}", if (unclaimed > 0) NamedTextColor.YELLOW else NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to claim all!", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(53, claimAllItem) { p, _ ->
            val count = claimAllRewards(p)
            if (count > 0) {
                plugin.commsManager.send(p, Component.text("Claimed $count reward${if (count != 1) "s" else ""}!", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(p, Component.text("No rewards to claim.", NamedTextColor.GRAY))
            }
            openCategoryMenu(p) // Refresh
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    fun openQuestList(player: Player, category: QuestCategory, page: Int) {
        if (category == QuestCategory.TIME_PLAYED) syncAllTimePlayedQuests(player)

        val baseCategoryQuests = getQuestsByCategory(category)
        val categoryQuests = if (category == QuestCategory.TIME_PLAYED) {
            // Within the Time Played menu, order by amount so the chain reads
            // 1h → 2h → 3h → ... instead of YAML insertion order.
            baseCategoryQuests.sortedBy { it.amount }
        } else {
            baseCategoryQuests.sortedBy { it.difficulty.ordinal }
        }
        val perPage = 28
        val totalPages = ((categoryQuests.size - 1) / perPage).coerceAtLeast(0)
        val safePage = page.coerceIn(0, totalPages)
        val start = safePage * perPage
        val pageQuests = categoryQuests.drop(start).take(perPage)

        val gui = CustomGui(
            plugin.commsManager.parseLegacy("&6&l${category.displayName} Quests"),
            54
        )
        gui.border(BORDER.clone())
        gui.fill(FILLER.clone())

        val uuid = player.uniqueId

        // Content slots: rows 1-4, columns 1-7 (28 slots)
        val contentSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                contentSlots.add(row * 9 + col)
            }
        }

        // Show an empty state if no quests exist for this category
        if (pageQuests.isEmpty()) {
            val emptyItem = ItemStack(Material.BARRIER)
            emptyItem.editMeta { meta ->
                meta.displayName(
                    Component.text("No quests yet", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                meta.lore(listOf(
                    Component.empty(),
                    Component.text("  No ${category.displayName} quests are loaded.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.text("  Check that quests.yml is up to date.", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                    Component.empty()
                ))
            }
            gui.setItem(22, emptyItem)
        }

        for ((i, quest) in pageQuests.withIndex()) {
            val progress = getPlayerProgress(uuid, quest.id)
            val canStartQuest = canStart(uuid, quest.id)

            val icon = try {
                questIcon(quest, progress, canStartQuest)
            } catch (e: Exception) {
                plugin.logger.warning("[Quests] Failed to render icon for quest '${quest.id}': ${e.message}")
                ItemStack(quest.category.icon)
            }
            val questRef = quest
            val progressRef = progress
            gui.setItem(contentSlots[i], icon) { p, _ ->
                if (progressRef.completed && !progressRef.claimedReward) {
                    if (claimReward(p, questRef.id)) {
                        p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
                        openQuestList(p, category, safePage) // refresh
                    }
                } else {
                    // Show quest info in chat for incomplete quests
                    val percent = if (questRef.amount > 0) (progressRef.progress * 100) / questRef.amount else 0
                    plugin.commsManager.send(p, plugin.commsManager.parseLegacy("&e--- ${questRef.name} ---"))
                    plugin.commsManager.send(p, plugin.commsManager.parseLegacy("  &7${questRef.description}"))
                    plugin.commsManager.send(p, plugin.commsManager.parseLegacy(
                        "  &7Progress: &a${progressRef.progress}&7/&a${questRef.amount} &7($percent%)"
                    ))
                    p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                }
            }
        }

        // Back button (slot 49)
        val backItem = ItemStack(Material.ARROW)
        backItem.editMeta { meta ->
            meta.displayName(
                Component.text("Back", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
        }
        gui.setItem(49, backItem) { p, _ ->
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
            openCategoryMenu(p)
        }

        // Previous page (slot 46)
        if (safePage > 0) {
            val prevItem = ItemStack(Material.ARROW)
            prevItem.editMeta { meta ->
                meta.displayName(
                    Component.text("Previous Page", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            gui.setItem(46, prevItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openQuestList(p, category, safePage - 1)
            }
        }

        // Next page (slot 52)
        if (safePage < totalPages) {
            val nextItem = ItemStack(Material.ARROW)
            nextItem.editMeta { meta ->
                meta.displayName(
                    Component.text("Next Page", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }
            gui.setItem(52, nextItem) { p, _ ->
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                openQuestList(p, category, safePage + 1)
            }
        }

        // Claim All Rewards (slot 53)
        val unclaimedCount = getUnclaimedCount(player.uniqueId)
        val claimBtn = ItemStack(if (unclaimedCount > 0) Material.LIME_DYE else Material.GRAY_DYE)
        claimBtn.editMeta { meta ->
            meta.displayName(Component.text("Claim All Rewards", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true).decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.empty(),
                Component.text("  $unclaimedCount unclaimed", if (unclaimedCount > 0) NamedTextColor.YELLOW else NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(53, claimBtn) { p, _ ->
            val count = claimAllRewards(p)
            if (count > 0) plugin.commsManager.send(p, Component.text("Claimed $count reward${if (count != 1) "s" else ""}!", NamedTextColor.GREEN))
            else plugin.commsManager.send(p, Component.text("No rewards to claim.", NamedTextColor.GRAY))
            openQuestList(p, category, safePage)
        }

        plugin.guiManager.open(player, gui)
    }

    // ── Anti-Abuse Helper ────────────────────────────────────

    /** Returns true if this player should be excluded from quest progress (creative, spectator, etc.) */
    private fun isExempt(player: Player): Boolean {
        return player.gameMode == org.bukkit.GameMode.CREATIVE
                || player.gameMode == org.bukkit.GameMode.SPECTATOR
    }

    // ── Event Handlers ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (isExempt(player)) return

        val materialName = event.block.type.name

        findMatchingQuests(QuestType.BREAK_BLOCK, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
        findMatchingQuests(QuestType.MINE_ORE, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }

        // Harvest crop tracking. Includes:
        //   - Fully grown Ageable crops (wheat, carrots, potatoes, beetroot, nether wart, etc.)
        //   - Always-harvestable plants (melon, pumpkin, sugar cane, cactus, bamboo, chorus, kelp)
        //
        // Plant variants whose top/stem use different Material names (KELP vs
        // KELP_PLANT, BAMBOO vs BAMBOO_SAPLING, TWISTING_VINES vs
        // TWISTING_VINES_PLANT, etc.) are normalized to the harvested-item
        // name so target matching works regardless of which segment was broken.
        val block = event.block
        val blockData = block.blockData
        val normalized = normalizeHarvestName(materialName)
        val alwaysHarvestable = setOf(
            "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO",
            "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES",
            "WEEPING_VINES", "GLOW_LICHEN", "VINE", "NETHER_WART",
            "SWEET_BERRY_BUSH",
        )
        val eligible = (blockData is org.bukkit.block.data.Ageable && blockData.age == blockData.maximumAge)
                || normalized in alwaysHarvestable
        if (eligible) {
            findMatchingQuests(QuestType.HARVEST_CROP, normalized).forEach { quest ->
                if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
            }
        }
    }

    /**
     * Map plant block variants to the canonical harvested-item name so quest
     * targets like `KELP` match when the player breaks a `KELP_PLANT` segment.
     */
    private fun normalizeHarvestName(name: String): String = when (name) {
        "KELP_PLANT" -> "KELP"
        "BAMBOO_SAPLING" -> "BAMBOO"
        "TWISTING_VINES_PLANT" -> "TWISTING_VINES"
        "WEEPING_VINES_PLANT" -> "WEEPING_VINES"
        "TALL_SEAGRASS" -> "SEAGRASS"
        else -> name
    }

    /**
     * Strip the DEEPSLATE_ prefix from ore variants so a quest targeting
     * IRON_ORE / REDSTONE_ORE / etc. matches breaks on either the regular or
     * deepslate version. Returns both names so findMatchingQuests sees a hit
     * for either form.
     */
    private fun oreVariants(name: String): Set<String> {
        val set = mutableSetOf(name)
        if (name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) {
            set.add(name.removePrefix("DEEPSLATE_"))
        }
        return set
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (isExempt(player)) return

        val materialName = event.block.type.name
        findMatchingQuests(QuestType.PLACE_BLOCK, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
    }

    /**
     * External hook for veinminer / treefeller / etc. to record block breaks
     * that didn't go through a vanilla BlockBreakEvent. Mirrors what
     * onBlockBreak does for a single block (BREAK_BLOCK + MINE_ORE +
     * fully-grown HARVEST_CROP), so vein-mined ores count toward "Mine N
     * iron ore" / "Break N stone" quests just like manually-broken ones.
     */
    fun recordBlockBreak(player: Player, block: org.bukkit.block.Block) {
        if (isExempt(player)) return
        val materialName = block.type.name

        findMatchingQuests(QuestType.BREAK_BLOCK, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
        findMatchingQuests(QuestType.MINE_ORE, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
        val blockData = block.blockData
        val normalized = normalizeHarvestName(materialName)
        val alwaysHarvestable = setOf(
            "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO",
            "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES",
            "WEEPING_VINES", "GLOW_LICHEN", "VINE", "NETHER_WART",
            "SWEET_BERRY_BUSH",
        )
        val eligible = (blockData is org.bukkit.block.data.Ageable && blockData.age == blockData.maximumAge)
                || normalized in alwaysHarvestable
        if (eligible) {
            findMatchingQuests(QuestType.HARVEST_CROP, normalized).forEach { quest ->
                if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (isExempt(killer)) return

        // Don't count kills on NPCs, armor stands, or combat log villagers
        if (event.entity.scoreboardTags.contains("joshymc_combat_npc")) return
        if (event.entity.scoreboardTags.contains("NPC")) return

        if (event.entity is Player) {
            val victim = event.entity as Player
            // Don't count creative/spectator kills or self-kills
            if (isExempt(victim) || victim == killer) return

            findMatchingQuests(QuestType.KILL_PLAYER, "").forEach { quest ->
                if (canStart(killer.uniqueId, quest.id)) incrementProgress(killer, quest.id)
            }
        }

        val entityName = event.entity.type.name
        findMatchingQuests(QuestType.KILL_MOB, entityName).forEach { quest ->
            if (canStart(killer.uniqueId, quest.id)) incrementProgress(killer, quest.id)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        if (isExempt(player)) return

        findMatchingQuests(QuestType.CATCH_FISH, "").forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        if (isExempt(player)) return

        val result = event.recipe.result
        val materialName = result.type.name

        val amount = if (event.isShiftClick) {
            val matrix = event.inventory.matrix
            var minStack = Int.MAX_VALUE
            for (item in matrix) {
                if (item != null && item.type != Material.AIR) {
                    minStack = minOf(minStack, item.amount)
                }
            }
            if (minStack == Int.MAX_VALUE) result.amount else minStack * result.amount
        } else {
            result.amount
        }

        findMatchingQuests(QuestType.CRAFT_ITEM, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id, amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val player = event.player
        if (isExempt(player)) return

        findMatchingQuests(QuestType.EAT_FOOD, "").forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
    }

    /**
     * Track item-pickup quests (e.g. "Collect 48 Apples"). Each pickup
     * counts the stack's amount, so picking up a stack of 16 apples
     * advances the quest by 16. Without this, "collect" quests had to
     * be implemented as BREAK_BLOCK with empty target — which falsely
     * matched ANY block break (mining dirt completed apples quests).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (isExempt(player)) return

        val item = event.item.itemStack
        val materialName = item.type.name
        val amount = item.amount.coerceAtLeast(1)

        findMatchingQuests(QuestType.ITEM_PICKUP, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id, amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val player = event.enchanter
        if (isExempt(player)) return

        findMatchingQuests(QuestType.ENCHANT_ITEM, "").forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val player = event.breeder as? Player ?: return
        if (isExempt(player)) return

        val entityName = event.entityType.name
        findMatchingQuests(QuestType.BREED_ANIMAL, entityName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val player = event.owner as? Player ?: return
        if (isExempt(player)) return

        val entityName = event.entityType.name
        findMatchingQuests(QuestType.TAME_ANIMAL, entityName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: org.bukkit.event.inventory.FurnaceExtractEvent) {
        val player = event.player
        if (isExempt(player)) return

        val materialName = event.itemType.name
        val amount = event.itemAmount
        findMatchingQuests(QuestType.SMELT_ITEM, materialName).forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id, amount)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLevelChange(event: PlayerLevelChangeEvent) {
        val player = event.player
        if (isExempt(player)) return

        val gained = event.newLevel - event.oldLevel
        if (gained <= 0) return
        // Cap XP level gains to prevent /xp command abuse
        if (gained > 10) return

        findMatchingQuests(QuestType.LEVEL_UP, "").forEach { quest ->
            if (canStart(player.uniqueId, quest.id)) incrementProgress(player, quest.id, gained)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to ?: return
        if (from.world != to.world) return

        val player = event.player

        // Only count walking — not flying, gliding, in vehicle, creative, spectator.
        // We deliberately do NOT bail on `player.allowFlight`: ops have fly
        // permission permanently, but they can still legitimately walk and
        // shouldn't be locked out of WALK_DISTANCE quests just because they
        // *could* fly. We only exclude when they're actually airborne.
        if (isExempt(player)) return
        if (player.isFlying || player.isGliding || player.isInsideVehicle) return

        // Only count horizontal movement (not falling/jumping)
        val dx = to.x - from.x
        val dz = to.z - from.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        if (dist < 0.01) return

        val uuid = player.uniqueId
        val accumulated = distanceAccumulator.getOrDefault(uuid, 0.0) + dist
        val blocks = accumulated.toInt()

        if (blocks >= 20) {
            distanceAccumulator[uuid] = accumulated - blocks
            findMatchingQuests(QuestType.WALK_DISTANCE, "").forEach { quest ->
                if (canStart(uuid, quest.id)) incrementProgress(player, quest.id, blocks)
            }
        } else {
            distanceAccumulator[uuid] = accumulated
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        flushProgress(uuid)
        progressCache.remove(uuid)
        distanceAccumulator.remove(uuid)
        visitedBiomes.remove(uuid)
    }

    /**
     * Wipe all quest progress for a player (and the cached/persistent biome list).
     * Called by /quests reset <player>. Returns the number of progress rows removed.
     */
    fun resetAllProgress(uuid: UUID): Int {
        val removed = plugin.databaseManager.executeUpdate(
            "DELETE FROM quest_progress WHERE uuid = ?",
            uuid.toString()
        )
        plugin.databaseManager.execute(
            "DELETE FROM quest_biomes WHERE uuid = ?",
            uuid.toString()
        )
        progressCache.remove(uuid)
        visitedBiomes.remove(uuid)
        distanceAccumulator.remove(uuid)
        return removed
    }

    /**
     * Wipe progress for a single quest. Returns true if a row was removed.
     */
    fun resetQuestProgress(uuid: UUID, questId: String): Boolean {
        val removed = plugin.databaseManager.executeUpdate(
            "DELETE FROM quest_progress WHERE uuid = ? AND quest_id = ?",
            uuid.toString(), questId
        )
        progressCache[uuid]?.remove(questId)
        return removed > 0
    }

    // ── Internals ──────────────────────────────────────────────

    private fun findMatchingQuests(type: QuestType, target: String): List<Quest> {
        // Accept ore variants so deepslate ores count toward base-ore quests
        // and vice versa.
        val candidates = oreVariants(target)
        return quests.values.filter { quest ->
            quest.type == type && (
                quest.target.isEmpty() ||
                candidates.any { it.equals(quest.target, ignoreCase = true) }
            )
        }
    }

    private fun onQuestComplete(player: Player, quest: Quest) {
        // Quieter than vanilla so it's not earsplitting when several quests
        // chain in a row (e.g. veinminer popping a bunch of "mine N ore" quests
        // back-to-back).
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.35f, 1.0f)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.35f, 1.0f)

        plugin.commsManager.send(player, plugin.commsManager.parseLegacy(
            "&6&l\u2605 Quest Complete! &e${quest.name} &7\u2014 use /quests to claim reward"
        ))

        // Big on-screen title \u2014 gold "QUEST COMPLETE!" with the quest name as
        // subtitle. Stays up for 4 seconds so it's actually noticeable.
        player.showTitle(Title.title(
            plugin.commsManager.parseLegacy("&6&l\u2605 QUEST COMPLETE! \u2605"),
            plugin.commsManager.parseLegacy("&e${quest.name}"),
            Title.Times.times(
                Duration.ofMillis(300),  // fade in
                Duration.ofSeconds(4),   // stay
                Duration.ofMillis(800)   // fade out
            )
        ))

        // Action bar with the reward summary so the player knows what they
        // earned without opening /quests. Action bar text sits above the
        // hotbar and is much harder to miss than the title.
        val rewardSummary = buildList {
            if (quest.rewards.money > 0) add("&6+${plugin.economyManager.format(quest.rewards.money)}")
            if (quest.rewards.xp > 0) add("&a+${quest.rewards.xp} XP")
            if (quest.rewards.items.isNotEmpty()) {
                val total = quest.rewards.items.sumOf { it.second }
                add("&b+$total item${if (total != 1) "s" else ""}")
            }
        }
        if (rewardSummary.isNotEmpty()) {
            player.sendActionBar(plugin.commsManager.parseLegacy(
                "&6\u2605 &eClaim with /rewards: " + rewardSummary.joinToString("  &7| ") + " &6\u2605"
            ))
        }
    }

    private fun questIcon(quest: Quest, progress: PlayerQuestProgress, canStartQuest: Boolean): ItemStack {
        val iconMaterial = when {
            progress.completed && progress.claimedReward -> Material.LIME_STAINED_GLASS_PANE
            progress.completed -> Material.GREEN_STAINED_GLASS_PANE
            !canStartQuest -> Material.RED_STAINED_GLASS_PANE
            progress.progress > 0 -> Material.YELLOW_STAINED_GLASS_PANE
            else -> Material.LIGHT_GRAY_STAINED_GLASS_PANE
        }

        val item = try {
            ItemStack(iconMaterial)
        } catch (_: Exception) {
            ItemStack(quest.category.icon)
        }
        item.editMeta { meta ->
            // Name colored by difficulty
            val diffColor = parseDifficultyColor(quest.difficulty)
            meta.displayName(
                Component.text(quest.name, diffColor)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())

            // Description
            lore.add(Component.text("  ${quest.description}", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())

            // Status
            if (!canStartQuest) {
                val prereqId = effectivePrerequisite(quest)
                val prereqQuest = prereqId?.let { quests[it] }
                val prereqName = prereqQuest?.name ?: prereqId ?: "Unknown"
                lore.add(Component.text("  \u26D4 Requires: $prereqName", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false))
            } else {
                // Progress bar
                lore.add(plugin.commsManager.parseLegacy("  ${progressBar(progress.progress, quest.amount, quest.type)}")
                    .decoration(TextDecoration.ITALIC, false))
            }

            lore.add(Component.empty())

            // Difficulty
            lore.add(plugin.commsManager.parseLegacy("  Difficulty: ${quest.difficulty.color}${quest.difficulty.displayName}")
                .decoration(TextDecoration.ITALIC, false))

            // Rewards
            lore.add(Component.text("  Rewards:", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
            if (quest.rewards.money > 0) {
                lore.add(Component.text("    ${plugin.economyManager.format(quest.rewards.money)}", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false))
            }
            if (quest.rewards.xp > 0) {
                lore.add(Component.text("    ${quest.rewards.xp} XP", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false))
            }
            for ((mat, count) in quest.rewards.items) {
                val name = mat.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                lore.add(Component.text("    ${count}x $name", NamedTextColor.AQUA)
                    .decoration(TextDecoration.ITALIC, false))
            }

            lore.add(Component.empty())

            // Click hint
            if (progress.completed && !progress.claimedReward) {
                lore.add(Component.text("  Click to claim reward!", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true))
            } else if (progress.completed && progress.claimedReward) {
                lore.add(Component.text("  \u2714 Completed", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false))
            }

            meta.lore(lore)

            if (progress.completed && progress.claimedReward) {
                meta.setEnchantmentGlintOverride(true)
            }
        }

        return item
    }

    private fun parseDifficultyColor(difficulty: QuestDifficulty): TextColor = when (difficulty) {
        QuestDifficulty.EASY -> NamedTextColor.GREEN
        QuestDifficulty.MEDIUM -> NamedTextColor.YELLOW
        QuestDifficulty.HARD -> NamedTextColor.RED
        QuestDifficulty.LEGENDARY -> TextColor.color(0xFFAA00)
    }

    private fun progressBar(current: Int, max: Int, type: QuestType = QuestType.BREAK_BLOCK): String {
        val bars = 10
        val filled = ((current.toDouble() / max) * bars).toInt().coerceIn(0, bars)
        val empty = bars - filled
        val label = when (type) {
            // TIME_PLAYED amounts are in seconds — display as human time so the
            // quest description ("Play for 1h") matches what the bar shows.
            QuestType.TIME_PLAYED -> "${formatSecondsShort(current)}/${formatSecondsShort(max)}"
            else -> "$current/$max"
        }
        return "&a" + "\u2588".repeat(filled) + "&7" + "\u2591".repeat(empty) + " &f$label"
    }

    private fun formatSecondsShort(total: Int): String {
        if (total < 60) return "${total}s"
        val days = total / 86400
        val hours = (total % 86400) / 3600
        val minutes = (total % 3600) / 60
        return when {
            days > 0 && hours > 0 -> "${days}d ${hours}h"
            days > 0 -> "${days}d"
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /**
     * Returns centered slots in the middle area of a 54-slot chest for category icons.
     */
    private fun centerSlots(count: Int): List<Int> {
        // Use rows 2-3 (slots 19-25 and 28-34) for centering
        val available = listOf(19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34)
        if (count >= available.size) return available.take(count)

        return if (count % 2 == 1 && count <= 7) {
            // Odd count fits perfectly in a single centered row
            val row = listOf(19, 20, 21, 22, 23, 24, 25)
            val offset = (7 - count) / 2
            row.subList(offset, offset + count)
        } else {
            // Even count or 8+: split into two centered rows
            val topCount = (count + 1) / 2
            val bottomCount = count - topCount
            val topRow = listOf(19, 20, 21, 22, 23, 24, 25)
            val bottomRow = listOf(28, 29, 30, 31, 32, 33, 34)
            val topOffset = (7 - topCount) / 2
            val bottomOffset = (7 - bottomCount) / 2
            topRow.subList(topOffset, topOffset + topCount) + bottomRow.subList(bottomOffset, bottomOffset + bottomCount)
        }
    }

    // ── Quest Loading ──────────────────────────────────────────

    private fun loadQuests() {
        quests.clear()

        val file = plugin.configFile("quests.yml")
        // quests.yml is plugin-managed (we generate it). Always overwrite so that
        // quest type / amount fixes in new releases actually reach live servers —
        // otherwise saveResource(false) would leave stale entries in place forever
        // (e.g. timeplayed_001 stuck as WALK_DISTANCE with amount 500 from an old
        // build even after we fix it in source).
        try {
            plugin.saveResource("quests.yml", true)
        } catch (_: Exception) {
            if (!file.exists()) {
                plugin.logger.warning("[Quests] quests.yml not found in JAR — no quests loaded.")
                return
            }
        }

        val config = YamlConfiguration.loadConfiguration(file)
        val questsSection = config.getConfigurationSection("quests") ?: return

        for (id in questsSection.getKeys(false)) {
            val section = questsSection.getConfigurationSection(id) ?: continue
            try {
                val name = section.getString("name") ?: id
                val description = section.getString("description") ?: ""
                val category = QuestCategory.valueOf(section.getString("category") ?: "MISC")
                val difficulty = QuestDifficulty.valueOf(section.getString("difficulty") ?: "EASY")
                val type = QuestType.valueOf(section.getString("type") ?: "BREAK_BLOCK")
                val target = section.getString("target") ?: ""
                val amount = section.getInt("amount", 1)
                val prerequisite = section.getString("prerequisite")?.takeIf { it != "null" && it.isNotBlank() }

                // Parse rewards
                val rewardsSection = section.getConfigurationSection("rewards")
                val money = rewardsSection?.getDouble("money", 0.0) ?: 0.0
                val xp = rewardsSection?.getInt("xp", 0) ?: 0
                val items = mutableListOf<Pair<Material, Int>>()
                val rawItems = rewardsSection?.getList("items")
                if (rawItems != null) {
                    for (entry in rawItems) {
                        if (entry is List<*> && entry.size >= 2) {
                            val mat = Material.getMaterial(entry[0].toString().uppercase()) ?: continue
                            val count = (entry[1] as? Number)?.toInt() ?: 1
                            items.add(mat to count)
                        }
                    }
                }

                quests[id] = Quest(
                    id = id,
                    name = name,
                    description = description,
                    category = category,
                    difficulty = difficulty,
                    type = type,
                    target = target,
                    amount = amount,
                    rewards = QuestRewards(money, xp, items),
                    prerequisite = prerequisite
                )
            } catch (e: Exception) {
                plugin.logger.warning("[Quests] Failed to load quest '$id': ${e.message}")
            }
        }

        computeImplicitPrerequisites()

        // Log loaded counts per category
        for (cat in QuestCategory.entries) {
            val count = quests.values.count { it.category == cat }
            if (count > 0) plugin.logger.info("[Quests] ${cat.displayName}: $count quests")
        }
    }

    /**
     * TIME_PLAYED quests without an explicit prerequisite get chained by ascending
     * amount — each quest requires the previous tier to be completed. This avoids
     * showing the entire category as simultaneously-active yellow panes, and makes
     * the "current" quest obvious at a glance.
     */
    private fun computeImplicitPrerequisites() {
        implicitPrerequisites.clear()
        val chain = quests.values
            .filter { it.type == QuestType.TIME_PLAYED && it.prerequisite == null }
            .sortedBy { it.amount }
        for (i in 1 until chain.size) {
            implicitPrerequisites[chain[i].id] = chain[i - 1].id
        }
    }

    // ── Progress Persistence ───────────────────────────────────

    private fun loadProgress(uuid: UUID): MutableMap<String, PlayerQuestProgress> {
        val map = mutableMapOf<String, PlayerQuestProgress>()
        plugin.databaseManager.query(
            "SELECT quest_id, progress, completed, claimed FROM quest_progress WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            val questId = rs.getString("quest_id")
            map[questId] = PlayerQuestProgress(
                questId = questId,
                progress = rs.getInt("progress"),
                completed = rs.getInt("completed") == 1,
                claimedReward = rs.getInt("claimed") == 1
            )
        }
        return map
    }

    private fun flushProgress(uuid: UUID) {
        val playerMap = progressCache[uuid] ?: return
        plugin.databaseManager.transaction {
            for ((_, progress) in playerMap) {
                plugin.databaseManager.execute(
                    """INSERT OR REPLACE INTO quest_progress (uuid, quest_id, progress, completed, claimed)
                       VALUES (?, ?, ?, ?, ?)""",
                    uuid.toString(),
                    progress.questId,
                    progress.progress,
                    if (progress.completed) 1 else 0,
                    if (progress.claimedReward) 1 else 0
                )
            }
        }
    }

    private fun flushAllProgress() {
        for (uuid in progressCache.keys) {
            try {
                flushProgress(uuid)
            } catch (e: Exception) {
                plugin.logger.warning("[Quests] Failed to flush progress for $uuid: ${e.message}")
            }
        }
    }
}
