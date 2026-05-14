package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.SellWandCommand
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
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
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceExtractEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class DailyQuestManager(private val plugin: Joshymc) : Listener {

    private val EST = ZoneId.of("America/New_York")

    private var currentDateStr = ""
    private var dailyPool: List<Quest> = emptyList()

    // uuid → questId → daily progress
    private val progressCache = ConcurrentHashMap<UUID, MutableMap<String, DailyProgress>>()
    private val dailyDistanceAcc = ConcurrentHashMap<UUID, Double>()

    data class DailyProgress(
        val questId: String,
        val progress: Int,
        val completed: Boolean,
        val claimed: Boolean
    )

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // ── Lifecycle ──────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS daily_quest_pool (
                date_str TEXT NOT NULL,
                quest_id TEXT NOT NULL,
                PRIMARY KEY (date_str, quest_id)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS daily_quest_progress (
                uuid TEXT NOT NULL,
                quest_id TEXT NOT NULL,
                date_str TEXT NOT NULL,
                progress INTEGER DEFAULT 0,
                completed INTEGER DEFAULT 0,
                claimed INTEGER DEFAULT 0,
                PRIMARY KEY (uuid, quest_id, date_str)
            )
        """.trimIndent())

        loadOrGeneratePool()

        // Check for day rollover every 30 seconds (600 ticks)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val today = todayStr()
            if (today != currentDateStr) rolloverToNewDay()
        }, 600L, 600L)

        // Flush progress every 5 minutes
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable { flushAllProgress() }, 6000L, 6000L)
    }

    fun stop() {
        flushAllProgress()
        progressCache.clear()
        dailyDistanceAcc.clear()
    }

    private fun todayStr(): String = LocalDate.now(EST).toString()

    private fun loadOrGeneratePool() {
        val today = todayStr()
        val existing = loadPoolFromDb(today)
        if (existing.isNotEmpty()) {
            currentDateStr = today
            dailyPool = existing
            plugin.logger.info("[DailyQuests] Loaded ${dailyPool.size} daily quests for $today.")
        } else {
            generateNewPool(today)
        }
    }

    private fun generateNewPool(date: String) {
        // Exclude quest types that don't track sensibly on a per-day basis
        val excluded = setOf(QuestType.TIME_PLAYED, QuestType.VISIT_BIOME)
        val candidates = plugin.questManager.getAllQuests()
            .filter { it.type !in excluded }
            .toList()

        dailyPool = if (candidates.size <= 5) {
            candidates
        } else {
            // Aim for at least one quest per difficulty so the pool is varied
            val byDiff = QuestDifficulty.entries.associateWith { diff ->
                candidates.filter { it.difficulty == diff }.shuffled()
            }
            val pool = mutableListOf<Quest>()
            for (diff in QuestDifficulty.entries) {
                val q = byDiff[diff]?.firstOrNull() ?: continue
                pool.add(q)
                if (pool.size == 5) break
            }
            val used = pool.toSet()
            val remaining = candidates.filter { it !in used }.shuffled()
            pool.addAll(remaining.take(5 - pool.size))
            pool
        }

        currentDateStr = date
        plugin.databaseManager.transaction {
            for (quest in dailyPool) {
                plugin.databaseManager.execute(
                    "INSERT OR IGNORE INTO daily_quest_pool (date_str, quest_id) VALUES (?, ?)",
                    date, quest.id
                )
            }
        }
        plugin.logger.info("[DailyQuests] Generated ${dailyPool.size} daily quests for $date.")
    }

    private fun loadPoolFromDb(date: String): List<Quest> {
        val ids = mutableListOf<String>()
        plugin.databaseManager.query(
            "SELECT quest_id FROM daily_quest_pool WHERE date_str = ?", date
        ) { rs -> ids.add(rs.getString("quest_id")) }
        return ids.mapNotNull { plugin.questManager.getQuest(it) }
    }

    private fun rolloverToNewDay() {
        progressCache.clear()
        dailyDistanceAcc.clear()
        generateNewPool(todayStr())
        for (player in Bukkit.getOnlinePlayers()) {
            plugin.commsManager.send(
                player,
                plugin.commsManager.parseLegacy("&6&l[Daily Quests] &eNew daily quests available! Type &6/daily &eto view.")
            )
        }
    }

    fun getDailyPool(): List<Quest> = dailyPool

    // ── Progress ────────────────────────────────────────────────

    private fun loadProgress(uuid: UUID): MutableMap<String, DailyProgress> {
        val map = mutableMapOf<String, DailyProgress>()
        plugin.databaseManager.query(
            "SELECT quest_id, progress, completed, claimed FROM daily_quest_progress WHERE uuid = ? AND date_str = ?",
            uuid.toString(), currentDateStr
        ) { rs ->
            val qid = rs.getString("quest_id")
            map[qid] = DailyProgress(
                questId = qid,
                progress = rs.getInt("progress"),
                completed = rs.getInt("completed") == 1,
                claimed = rs.getInt("claimed") == 1
            )
        }
        return map
    }

    fun getDailyProgress(uuid: UUID, questId: String): DailyProgress {
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        return map[questId] ?: DailyProgress(questId, 0, false, false)
    }

    private fun incrementDailyProgress(player: Player, questId: String, amount: Int = 1) {
        val quest = dailyPool.find { it.id == questId } ?: return
        val uuid = player.uniqueId
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val current = map[questId] ?: DailyProgress(questId, 0, false, false)
        if (current.completed) return
        val newProgress = (current.progress + amount).coerceAtMost(quest.amount)
        val completed = newProgress >= quest.amount
        map[questId] = current.copy(progress = newProgress, completed = completed)
        if (completed) onDailyQuestComplete(player, quest)
    }

    private fun flushProgress(uuid: UUID) {
        val map = progressCache[uuid] ?: return
        val date = currentDateStr
        plugin.databaseManager.transaction {
            for ((_, prog) in map) {
                plugin.databaseManager.execute(
                    """INSERT OR REPLACE INTO daily_quest_progress
                       (uuid, quest_id, date_str, progress, completed, claimed)
                       VALUES (?, ?, ?, ?, ?, ?)""",
                    uuid.toString(), prog.questId, date,
                    prog.progress,
                    if (prog.completed) 1 else 0,
                    if (prog.claimed) 1 else 0
                )
            }
        }
    }

    private fun flushAllProgress() {
        for (uuid in progressCache.keys) {
            try { flushProgress(uuid) } catch (e: Exception) {
                plugin.logger.warning("[DailyQuests] Failed to flush progress for $uuid: ${e.message}")
            }
        }
    }

    fun claimDailyReward(player: Player, questId: String): Boolean {
        val quest = dailyPool.find { it.id == questId } ?: return false
        val uuid = player.uniqueId
        val map = progressCache.getOrPut(uuid) { loadProgress(uuid) }
        val current = map[questId] ?: return false
        if (!current.completed || current.claimed) return false

        map[questId] = current.copy(claimed = true)

        val money = when (quest.difficulty) {
            QuestDifficulty.EASY -> Random.nextDouble(1_000.0, 25_000.0)
            QuestDifficulty.MEDIUM -> Random.nextDouble(25_000.0, 100_000.0)
            QuestDifficulty.HARD -> Random.nextDouble(100_000.0, 500_000.0)
            QuestDifficulty.LEGENDARY -> Random.nextDouble(500_000.0, 3_000_000.0)
        }
        plugin.economyManager.deposit(uuid, money)

        when (quest.difficulty) {
            QuestDifficulty.EASY -> {
                plugin.claimManager.addBlocks(uuid, 500)
                plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&a+500 Claim Blocks!"))
            }
            QuestDifficulty.MEDIUM -> {
                val dust = plugin.itemManager.getItem("enchanted_dust")?.createItemStack(4)
                if (dust != null) {
                    val leftover = player.inventory.addItem(dust)
                    for ((_, drop) in leftover) player.world.dropItemNaturally(player.location, drop)
                }
                plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&d+4 Enchanted Dust!"))
            }
            QuestDifficulty.HARD -> {
                // Try joshy crate key, fall back to rare
                if (!plugin.crateManager.giveKey(player, "joshy", 1)) {
                    plugin.crateManager.giveKey(player, "rare", 1)
                }
                plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&6+1 Joshy Key!"))
            }
            QuestDifficulty.LEGENDARY -> {
                val wand = SellWandCommand.createSellWand(plugin, 2.5, 25)
                val leftover = player.inventory.addItem(wand)
                for ((_, drop) in leftover) player.world.dropItemNaturally(player.location, drop)
                plugin.commsManager.send(player, plugin.commsManager.parseLegacy("&6+2.5x Sell Wand (25 uses)!"))
            }
        }

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
        plugin.commsManager.send(
            player,
            plugin.commsManager.parseLegacy(
                "&a&lDaily Quest Complete! &aYou received &6${plugin.economyManager.format(money)}&a!"
            )
        )
        return true
    }

    fun claimAllDailyRewards(player: Player): Int {
        var claimed = 0
        for (quest in dailyPool) {
            val progress = getDailyProgress(player.uniqueId, quest.id)
            if (progress.completed && !progress.claimed) {
                if (claimDailyReward(player, quest.id)) claimed++
            }
        }
        return claimed
    }

    // ── Completion Effects ──────────────────────────────────────

    private fun onDailyQuestComplete(player: Player, quest: Quest) {
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.1f)
        player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.35f, 1.0f)
        plugin.commsManager.send(
            player,
            plugin.commsManager.parseLegacy("&6&l★ Daily Quest Complete! &e${quest.name} &7— use /daily to claim reward")
        )
        player.showTitle(Title.title(
            plugin.commsManager.parseLegacy("&6&l★ DAILY COMPLETE! ★"),
            plugin.commsManager.parseLegacy("&e${quest.name}"),
            Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))
        ))
    }

    // ── GUI ─────────────────────────────────────────────────────

    fun openDailyGui(player: Player) {
        val uuid = player.uniqueId

        val now = ZonedDateTime.now(EST)
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay(EST)
        val secondsLeft = Duration.between(now, midnight).seconds

        val gui = CustomGui(plugin.commsManager.parseLegacy("&6&lDaily Quests"), 54)
        gui.border(BORDER.clone())
        gui.fill(FILLER.clone())

        // Info item (top center)
        val infoItem = ItemStack(Material.CLOCK)
        infoItem.editMeta { meta ->
            meta.displayName(
                Component.text("Daily Quests", NamedTextColor.GOLD)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Resets in: ${formatTimeLeft(secondsLeft)}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Complete quests, claim rewards!", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(4, infoItem)

        // Quest slots — spread across two rows (up to 5 quests)
        val questSlots = listOf(20, 22, 24, 29, 31)
        for ((i, quest) in dailyPool.withIndex()) {
            if (i >= questSlots.size) break
            val progress = getDailyProgress(uuid, quest.id)
            val item = buildQuestItem(quest, progress)
            val q = quest
            gui.setItem(questSlots[i], item) { p, _ ->
                val prog = getDailyProgress(p.uniqueId, q.id)
                if (prog.completed && !prog.claimed) {
                    if (claimDailyReward(p, q.id)) {
                        p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f)
                        openDailyGui(p)
                    }
                } else if (prog.claimed) {
                    plugin.commsManager.send(p, Component.text("Already claimed!", NamedTextColor.GRAY))
                } else {
                    val pct = if (q.amount > 0) (prog.progress * 100) / q.amount else 0
                    plugin.commsManager.send(p, plugin.commsManager.parseLegacy("&e--- ${q.name} ---"))
                    plugin.commsManager.send(p, plugin.commsManager.parseLegacy("  &7${q.description}"))
                    plugin.commsManager.send(p, plugin.commsManager.parseLegacy(
                        "  &7Progress: &a${prog.progress}&7/&a${q.amount} &7($pct%)"
                    ))
                    p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.0f)
                }
            }
        }

        // Claim All button (slot 53)
        val unclaimedCount = dailyPool.count {
            val prog = getDailyProgress(uuid, it.id)
            prog.completed && !prog.claimed
        }
        val claimBtn = ItemStack(if (unclaimedCount > 0) Material.LIME_DYE else Material.GRAY_DYE)
        claimBtn.editMeta { meta ->
            meta.displayName(
                Component.text("Claim All Rewards", NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text(
                    "  $unclaimedCount unclaimed",
                    if (unclaimedCount > 0) NamedTextColor.YELLOW else NamedTextColor.GRAY
                ).decoration(TextDecoration.ITALIC, false)
            ))
        }
        gui.setItem(53, claimBtn) { p, _ ->
            val count = claimAllDailyRewards(p)
            if (count > 0) {
                plugin.commsManager.send(p, Component.text("Claimed $count daily reward${if (count != 1) "s" else ""}!", NamedTextColor.GREEN))
            } else {
                plugin.commsManager.send(p, Component.text("No daily rewards to claim.", NamedTextColor.GRAY))
            }
            openDailyGui(p)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun buildQuestItem(quest: Quest, progress: DailyProgress): ItemStack {
        val iconMat = when {
            progress.claimed -> Material.LIME_STAINED_GLASS_PANE
            progress.completed -> Material.GREEN_STAINED_GLASS_PANE
            progress.progress > 0 -> Material.YELLOW_STAINED_GLASS_PANE
            else -> Material.LIGHT_GRAY_STAINED_GLASS_PANE
        }
        val item = ItemStack(iconMat)
        item.editMeta { meta ->
            val diffColor = when (quest.difficulty) {
                QuestDifficulty.EASY -> NamedTextColor.GREEN as TextColor
                QuestDifficulty.MEDIUM -> NamedTextColor.YELLOW as TextColor
                QuestDifficulty.HARD -> NamedTextColor.RED as TextColor
                QuestDifficulty.LEGENDARY -> TextColor.color(0xFFAA00)
            }
            meta.displayName(
                Component.text(quest.name, diffColor)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(
                Component.text("  ${quest.description}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            )
            lore.add(Component.empty())

            val filled = if (quest.amount > 0) {
                ((progress.progress.toDouble() / quest.amount) * 10).toInt().coerceIn(0, 10)
            } else 10
            lore.add(
                plugin.commsManager.parseLegacy(
                    "  &a${"█".repeat(filled)}&7${"░".repeat(10 - filled)} &f${progress.progress}/${quest.amount}"
                ).decoration(TextDecoration.ITALIC, false)
            )

            lore.add(Component.empty())
            lore.add(
                plugin.commsManager.parseLegacy("  Difficulty: ${quest.difficulty.color}${quest.difficulty.displayName}")
                    .decoration(TextDecoration.ITALIC, false)
            )

            lore.add(
                Component.text("  Rewards:", NamedTextColor.WHITE)
                    .decoration(TextDecoration.BOLD, true)
                    .decoration(TextDecoration.ITALIC, false)
            )
            val (moneyRange, itemReward) = when (quest.difficulty) {
                QuestDifficulty.EASY -> "\$1,000 - \$25,000" to "500 Claim Blocks"
                QuestDifficulty.MEDIUM -> "\$25,000 - \$100,000" to "4 Enchanted Dust"
                QuestDifficulty.HARD -> "\$100,000 - \$500,000" to "1 Joshy Key"
                QuestDifficulty.LEGENDARY -> "\$500,000 - \$3,000,000" to "2.5x Sell Wand (25 uses)"
            }
            lore.add(Component.text("    $moneyRange", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("    $itemReward", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())

            when {
                progress.claimed -> lore.add(
                    Component.text("  ✔ Claimed", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false)
                )
                progress.completed -> lore.add(
                    Component.text("  Click to claim reward!", NamedTextColor.GREEN)
                        .decoration(TextDecoration.BOLD, true)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }

            meta.lore(lore)
            if (progress.claimed) meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    private fun formatTimeLeft(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    // ── External hooks ──────────────────────────────────────────

    fun recordSell(player: Player, amount: Int) {
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.SELL_ITEMS, "") { incrementDailyProgress(player, it.id, amount) }
    }

    fun recordEarning(player: Player, amount: Double) {
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.EARN_MONEY, "") { incrementDailyProgress(player, it.id, amount.toInt().coerceAtLeast(1)) }
    }

    fun recordBlockBreak(player: Player, block: org.bukkit.block.Block) {
        if (isExempt(player)) return
        val name = block.type.name
        forMatchingDailyQuests(QuestType.BREAK_BLOCK, name) { incrementDailyProgress(player, it.id) }
        forMatchingDailyQuests(QuestType.MINE_ORE, name) { incrementDailyProgress(player, it.id) }
    }

    // ── Matching helpers ────────────────────────────────────────

    private fun forMatchingDailyQuests(type: QuestType, target: String, action: (Quest) -> Unit) {
        if (dailyPool.isEmpty()) return
        val candidates = oreVariants(target)
        for (quest in dailyPool) {
            if (quest.type != type) continue
            if (quest.target.isNotEmpty() && candidates.none { it.equals(quest.target, ignoreCase = true) }) continue
            action(quest)
        }
    }

    private fun oreVariants(name: String): Set<String> {
        val set = mutableSetOf(name)
        if (name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) {
            set.add(name.removePrefix("DEEPSLATE_"))
        }
        return set
    }

    private fun normalizeHarvestName(name: String): String = when (name) {
        "KELP_PLANT" -> "KELP"
        "BAMBOO_SAPLING" -> "BAMBOO"
        "TWISTING_VINES_PLANT" -> "TWISTING_VINES"
        "WEEPING_VINES_PLANT" -> "WEEPING_VINES"
        "TALL_SEAGRASS" -> "SEAGRASS"
        else -> name
    }

    private fun isExempt(player: Player) =
        player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR

    // ── Event Handlers ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (isExempt(player)) return
        val name = event.block.type.name

        forMatchingDailyQuests(QuestType.BREAK_BLOCK, name) { incrementDailyProgress(player, it.id) }
        forMatchingDailyQuests(QuestType.MINE_ORE, name) { incrementDailyProgress(player, it.id) }

        val blockData = event.block.blockData
        val normalized = normalizeHarvestName(name)
        val alwaysHarvestable = setOf(
            "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO",
            "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES",
            "WEEPING_VINES", "GLOW_LICHEN", "VINE", "NETHER_WART", "SWEET_BERRY_BUSH",
            "DANDELION", "POPPY", "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET",
            "RED_TULIP", "ORANGE_TULIP", "WHITE_TULIP", "PINK_TULIP",
            "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "WITHER_ROSE",
            "TORCHFLOWER", "CLOSED_EYEBLOSSOM", "OPEN_EYEBLOSSOM",
            "SPORE_BLOSSOM", "PINK_PETALS", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY"
        )
        val eligible = (blockData is org.bukkit.block.data.Ageable && blockData.age == blockData.maximumAge)
                || normalized in alwaysHarvestable
        if (eligible) {
            forMatchingDailyQuests(QuestType.HARVEST_CROP, normalized) { incrementDailyProgress(player, it.id) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.PLACE_BLOCK, event.block.type.name) { incrementDailyProgress(player, it.id) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        if (isExempt(killer)) return
        if (event.entity.scoreboardTags.contains("joshymc_combat_npc")) return
        if (event.entity.scoreboardTags.contains("NPC")) return

        if (event.entity is Player) {
            val victim = event.entity as Player
            if (isExempt(victim) || victim == killer) return
            forMatchingDailyQuests(QuestType.KILL_PLAYER, "") { incrementDailyProgress(killer, it.id) }
        }
        forMatchingDailyQuests(QuestType.KILL_MOB, event.entity.type.name) { incrementDailyProgress(killer, it.id) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.CATCH_FISH, "") { incrementDailyProgress(player, it.id) }
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
        forMatchingDailyQuests(QuestType.CRAFT_ITEM, result.type.name) { incrementDailyProgress(player, it.id, amount) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        val brewLoc = event.block.location
        val player = brewLoc.world?.getNearbyEntities(brewLoc, 10.0, 10.0, 10.0)
            ?.filterIsInstance<Player>()
            ?.minByOrNull { it.location.distanceSquared(brewLoc) } ?: return
        if (isExempt(player)) return
        val potionMaterials = setOf(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION)
        val brewedCount = event.results.count { it != null && it.type in potionMaterials }
        if (brewedCount == 0) return
        forMatchingDailyQuests(QuestType.CRAFT_ITEM, Material.POTION.name) { incrementDailyProgress(player, it.id, brewedCount) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val player = event.player
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.EAT_FOOD, "") { incrementDailyProgress(player, it.id) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (isExempt(player)) return
        if (event.item.persistentDataContainer.has(plugin.questManager.shopDropKey, PersistentDataType.BYTE)) return
        val item = event.item.itemStack
        forMatchingDailyQuests(QuestType.ITEM_PICKUP, item.type.name) { incrementDailyProgress(player, it.id, item.amount.coerceAtLeast(1)) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val player = event.enchanter
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.ENCHANT_ITEM, "") { incrementDailyProgress(player, it.id) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val player = event.breeder as? Player ?: return
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.BREED_ANIMAL, event.entityType.name) { incrementDailyProgress(player, it.id) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val player = event.owner as? Player ?: return
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.TAME_ANIMAL, event.entityType.name) { incrementDailyProgress(player, it.id) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFurnaceExtract(event: FurnaceExtractEvent) {
        val player = event.player
        if (isExempt(player)) return
        forMatchingDailyQuests(QuestType.SMELT_ITEM, event.itemType.name) { incrementDailyProgress(player, it.id, event.itemAmount) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLevelChange(event: PlayerLevelChangeEvent) {
        val player = event.player
        if (isExempt(player)) return
        val gained = event.newLevel - event.oldLevel
        if (gained <= 0 || gained > 10) return
        forMatchingDailyQuests(QuestType.LEVEL_UP, "") { incrementDailyProgress(player, it.id, gained) }
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
        val accumulated = dailyDistanceAcc.getOrDefault(uuid, 0.0) + dist
        val blocks = accumulated.toInt()
        if (blocks >= 20) {
            dailyDistanceAcc[uuid] = accumulated - blocks
            forMatchingDailyQuests(QuestType.WALK_DISTANCE, "") { incrementDailyProgress(player, it.id, blocks) }
        } else {
            dailyDistanceAcc[uuid] = accumulated
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        flushProgress(uuid)
        progressCache.remove(uuid)
        dailyDistanceAcc.remove(uuid)
    }
}
