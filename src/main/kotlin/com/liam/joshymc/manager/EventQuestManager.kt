package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.util.depositItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.data.Ageable
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.scheduler.BukkitTask
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// ── Data model ──────────────────────────────────────────────────

enum class EventObjective {
    BREAK_BLOCK, MINE_ORE, PLACE_BLOCK, HARVEST_CROP, KILL_MOB, KILL_PLAYER,
    CATCH_FISH, CRAFT_ITEM, TRAVEL, SELL_ITEM, PLAY_TIME
}

data class EventQuestDef(
    val index: Int,
    val type: EventObjective,
    /** Material / entity filter, upper-cased. Null = anything. */
    val target: String?,
    val amount: Int,
    val name: String?,
    val description: String?
)

data class EventDef(
    val id: String,
    val name: String,
    val description: String,
    /** Explicit `display-item` icon override; null = show the reward item itself. */
    val displayItem: Material?,
    val rewardSource: String,
    val rewardItemId: String,
    val rewardAmount: Int,
    val start: ZonedDateTime?,
    val end: ZonedDateTime?,
    val claimAfterEnd: Boolean,
    val quests: List<EventQuestDef>,
    /** Exact reward stack for admin-created questlines (rewardSource "held-item"). */
    val rewardItem: ItemStack? = null
)

/**
 * Per-player state for one event. [index] is the 0-based active quest (== number of
 * completed quests); once every quest is done [completed] is true and [index] == quest count.
 */
data class EventProgress(
    val index: Int = 0,
    val progress: Int = 0,
    val completed: Boolean = false,
    val claimed: Boolean = false,
    val delivered: Boolean = false
)

/**
 * Event Quests — sequential quest chains that unlock a one-time custom item reward.
 * Opened with /eventquests (/eq); campaigns are defined in event-quests.yml.
 *
 * Fully independent of [QuestCycleManager]: its own table, listeners and config, so
 * Daily/Weekly/Quest Master quests are unaffected.
 */
class EventQuestManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val PLACED_META = "joshymc_eventquest_placed"
        private const val HELD_ITEM = "held-item"
        private const val CUSTOM_FILE = "event-quests-custom.yml"
        private val QUESTLINE_ID = Regex("[a-z0-9_-]{1,32}")
        private const val MIN_QUESTS = 2
        private const val MAX_QUESTS = 10
        private const val RESET_CONFIRM_MS = 30_000L
        private const val KILL_PLAYER_COOLDOWN_MS = 10 * 60_000L
        private const val PLACE_REPEAT_MS = 10_000L
        private val ZONE: ZoneId = ZoneId.of("America/New_York")
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy")
        private val ALWAYS_HARVESTABLE = setOf(
            "MELON", "PUMPKIN", "SUGAR_CANE", "CACTUS", "BAMBOO", "NETHER_WART",
            "COCOA", "CHORUS_FLOWER", "CHORUS_PLANT", "KELP", "TWISTING_VINES", "WEEPING_VINES"
        )
        private val REWARD_SOURCES = setOf("custom-item", "existing-custom-item", "crate-key", HELD_ITEM)
    }

    // ── State ───────────────────────────────────────────────────

    @Volatile private var systemEnabled = true
    @Volatile private var events: Map<String, EventDef> = emptyMap()
    @Volatile private var byObjective: Map<EventObjective, List<EventDef>> = emptyMap()

    private val cache = ConcurrentHashMap<UUID, MutableMap<String, EventProgress>>()
    private val dirty = ConcurrentHashMap.newKeySet<Pair<UUID, String>>()
    private val tasks = mutableListOf<BukkitTask>()
    private val travelAcc = ConcurrentHashMap<UUID, Double>()
    private val recentKills = ConcurrentHashMap<String, Long>()
    private val recentPlaces = ConcurrentHashMap<UUID, MutableMap<String, Long>>()
    private val pendingResets = ConcurrentHashMap<String, Long>()

    // ── Lifecycle ───────────────────────────────────────────────

    fun createTables() {
        plugin.databaseManager.createTable(
            """
            CREATE TABLE IF NOT EXISTS event_quest_progress (
                uuid TEXT NOT NULL,
                event_id TEXT NOT NULL,
                quest_index INTEGER NOT NULL DEFAULT 0,
                progress INTEGER NOT NULL DEFAULT 0,
                completed INTEGER NOT NULL DEFAULT 0,
                claimed INTEGER NOT NULL DEFAULT 0,
                delivered INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (uuid, event_id)
            )
            """.trimIndent()
        )
    }

    fun start() {
        createTables()
        loadEvents()
        tasks += Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickPlaytime() }, 1200L, 1200L)
        tasks += Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, Runnable { flushAll() }, 6000L, 6000L)
        plugin.logger.info("[EventQuests] Started with ${events.size} event(s).")
    }

    fun stop() {
        tasks.forEach { it.cancel() }
        tasks.clear()
        flushAll()
        cache.clear()
        dirty.clear()
        travelAcc.clear()
        recentKills.clear()
        recentPlaces.clear()
        pendingResets.clear()
    }

    // ── Config ──────────────────────────────────────────────────

    /**
     * Re-reads event-quests.yml. Player progress is never touched. If the file can't be
     * parsed the previously loaded events stay active. Returns the number of events loaded.
     */
    fun reload(): Int {
        loadEvents()
        return events.size
    }

    private fun loadEvents() {
        val file = plugin.configFile("event-quests.yml")
        if (!file.exists()) plugin.saveResource("event-quests.yml", false)

        val cfg = try {
            YamlConfiguration().also { it.load(file) }
        } catch (e: Exception) {
            plugin.logger.severe("[EventQuests] event-quests.yml is invalid (${e.message}) — keeping the previously loaded events.")
            return
        }

        systemEnabled = cfg.getBoolean("event-quests.enabled", true)
        val loaded = LinkedHashMap<String, EventDef>()
        val section = cfg.getConfigurationSection("event-quests.events")
        if (section == null) {
            plugin.logger.warning("[EventQuests] event-quests.yml has no 'event-quests.events' section — only admin-created questlines are loaded.")
        } else {
            for (rawId in section.getKeys(false)) {
                val id = rawId.lowercase()
                val s = section.getConfigurationSection(rawId)
                if (s == null) { eventError(rawId, "is not a section"); continue }
                if (!s.getBoolean("enabled", true)) continue
                if (id in loaded) { eventError(rawId, "duplicate event id"); continue }
                parseEvent(id, s)?.let { loaded[id] = it }
            }
        }

        // Admin-created questlines (/eq admin create). Drafts missing a reward or 2+ quests stay unloaded.
        val custom = customConfig().getConfigurationSection("questlines")
        for (rawId in custom?.getKeys(false) ?: emptySet()) {
            val id = rawId.lowercase()
            val s = custom?.getConfigurationSection(rawId) ?: continue
            if (!s.getBoolean("enabled", true) || !isComplete(s)) continue
            if (id in loaded) { eventError(rawId, "duplicate event id"); continue }
            parseEvent(id, s)?.let { loaded[id] = it }
        }

        events = loaded
        byObjective = loaded.values
            .flatMap { ev -> ev.quests.map { it.type }.toSet().map { it to ev } }
            .groupBy({ it.first }, { it.second })
        plugin.logger.info("[EventQuests] Loaded ${loaded.size} event(s).")
    }

    private fun eventError(id: String, reason: String) {
        plugin.logger.warning("[EventQuests] Event '$id' skipped: $reason.")
    }

    private fun parseEvent(id: String, s: ConfigurationSection): EventDef? {
        val name = s.getString("name")?.takeIf { it.isNotBlank() } ?: id

        val rewardSection = s.getConfigurationSection("reward")
        if (rewardSection == null) { eventError(id, "missing 'reward' section"); return null }
        val source = (rewardSection.getString("source") ?: "custom-item").lowercase()
        if (source !in REWARD_SOURCES) { eventError(id, "unknown reward source '$source'"); return null }
        val heldItem = if (source == HELD_ITEM) decodeItem(rewardSection.getString("item")) else null
        if (source == HELD_ITEM && heldItem == null) { eventError(id, "held-item reward is missing or unreadable"); return null }
        val itemId = if (source == HELD_ITEM) HELD_ITEM else rewardSection.getString("item-id")
        if (itemId.isNullOrBlank()) { eventError(id, "reward is missing 'item-id'"); return null }
        if (source != "crate-key" && source != HELD_ITEM && plugin.itemManager.getItem(itemId) == null) {
            eventError(id, "reward custom item '$itemId' does not exist")
            return null
        }

        val displayItem = s.getString("display-item")?.let { Material.matchMaterial(it) }

        val start = parseDate(id, s.getString("start"), false) ?: if (s.contains("start")) return null else null
        val end = parseDate(id, s.getString("end"), true) ?: if (s.contains("end")) return null else null
        if (start != null && end != null && !end.isAfter(start)) { eventError(id, "'end' must be after 'start'"); return null }

        val questSection = s.getConfigurationSection("quests")
        if (questSection == null) { eventError(id, "missing 'quests' section"); return null }
        val keys = questSection.getKeys(false)
        if (keys.size < MIN_QUESTS || keys.size > MAX_QUESTS) {
            eventError(id, "has ${keys.size} quests, needs between $MIN_QUESTS and $MAX_QUESTS")
            return null
        }
        val numbers = keys.map { it.toIntOrNull() }
        if (numbers.any { it == null } || numbers.map { it!! }.sorted() != (1..keys.size).toList()) {
            eventError(id, "quests must be numbered 1..${keys.size} with no gaps")
            return null
        }

        val quests = mutableListOf<EventQuestDef>()
        for (n in 1..keys.size) {
            val q = questSection.getConfigurationSection(n.toString())
            if (q == null) { eventError(id, "quest $n is not a section"); return null }
            val type = q.getString("type")?.uppercase()?.let { t -> EventObjective.entries.firstOrNull { it.name == t } }
            if (type == null) { eventError(id, "quest $n has unknown type '${q.getString("type")}'"); return null }
            val amount = q.getInt("amount", 0)
            if (amount <= 0) { eventError(id, "quest $n needs a positive 'amount'"); return null }

            val raw = (q.getString("material") ?: q.getString("entity"))?.uppercase()?.takeIf { it != "ANY" }
            if (raw != null) {
                if (!validTarget(type, raw)) { eventError(id, "quest $n has unknown material/entity '$raw'"); return null }
            }
            quests += EventQuestDef(n - 1, type, raw, amount, q.getString("name"), q.getString("description"))
        }

        return EventDef(
            id = id,
            name = name,
            description = s.getString("description") ?: "",
            displayItem = displayItem,
            rewardSource = source,
            rewardItemId = itemId,
            rewardAmount = 1,
            start = start,
            end = end,
            claimAfterEnd = s.getBoolean("claim-after-end", false),
            quests = quests,
            rewardItem = heldItem
        )
    }

    private fun validTarget(type: EventObjective, raw: String): Boolean = when (type) {
        EventObjective.KILL_MOB -> org.bukkit.entity.EntityType.entries.any { it.name == raw }
        EventObjective.KILL_PLAYER, EventObjective.TRAVEL, EventObjective.PLAY_TIME -> true
        else -> Material.matchMaterial(raw) != null
    }

    private fun decodeItem(raw: String?): ItemStack? {
        if (raw.isNullOrBlank()) return null
        return try { ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(raw)) } catch (e: Exception) { null }
    }

    /** Returns null for absent *or* invalid input; the caller distinguishes via `s.contains`. */
    private fun parseDate(id: String, raw: String?, endOfDay: Boolean): ZonedDateTime? {
        if (raw.isNullOrBlank()) return null
        return try {
            if (raw.trim().length <= 10) {
                val d = LocalDate.parse(raw.trim())
                (if (endOfDay) d.plusDays(1).atStartOfDay() else d.atStartOfDay()).atZone(ZONE)
            } else {
                LocalDateTime.parse(raw.trim().replace(' ', 'T')).atZone(ZONE)
            }
        } catch (e: Exception) {
            eventError(id, "bad date '$raw' (use yyyy-MM-dd or yyyy-MM-dd HH:mm)")
            null
        }
    }

    // ── Public queries ──────────────────────────────────────────

    fun getEvent(id: String): EventDef? = events[id.lowercase()]
    fun eventIds(): List<String> = events.keys.toList()

    fun isOpen(event: EventDef, now: ZonedDateTime = ZonedDateTime.now(ZONE)): Boolean =
        (event.start == null || !now.isBefore(event.start)) && (event.end == null || now.isBefore(event.end))

    private fun availability(event: EventDef): String {
        val now = ZonedDateTime.now(ZONE)
        return when {
            event.start != null && now.isBefore(event.start) -> "Starts ${event.start.format(DATE_FORMAT)}"
            event.end != null && !now.isBefore(event.end) -> "Ended ${event.end.format(DATE_FORMAT)}"
            event.end != null -> "Ends ${event.end.format(DATE_FORMAT)}"
            else -> "Always available"
        }
    }

    fun getProgress(uuid: UUID, eventId: String): EventProgress {
        val id = eventId.lowercase()
        return cache.getOrPut(uuid) { loadPlayer(uuid) }[id] ?: EventProgress()
    }

    private fun setState(uuid: UUID, eventId: String, state: EventProgress) {
        cache.getOrPut(uuid) { loadPlayer(uuid) }[eventId] = state
    }

    // ── Persistence ─────────────────────────────────────────────

    private fun loadPlayer(uuid: UUID): MutableMap<String, EventProgress> {
        val map = ConcurrentHashMap<String, EventProgress>()
        plugin.databaseManager.query(
            "SELECT event_id, quest_index, progress, completed, claimed, delivered FROM event_quest_progress WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            map[rs.getString("event_id")] = EventProgress(
                rs.getInt("quest_index"), rs.getInt("progress"),
                rs.getInt("completed") == 1, rs.getInt("claimed") == 1, rs.getInt("delivered") == 1
            )
        }
        return map
    }

    /**
     * Writes quest position/progress only. `completed`, `claimed` and `delivered` are never
     * written from here (they're set by dedicated conditional updates), so a stale cache
     * flush can never un-claim a reward.
     */
    private fun persistPosition(uuid: UUID, eventId: String, s: EventProgress) {
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO event_quest_progress (uuid, event_id) VALUES (?, ?)", uuid.toString(), eventId
        )
        plugin.databaseManager.execute(
            "UPDATE event_quest_progress SET quest_index = ?, progress = ? WHERE uuid = ? AND event_id = ? AND claimed = 0",
            s.index, s.progress, uuid.toString(), eventId
        )
    }

    private fun flushPlayer(uuid: UUID) {
        val map = cache[uuid] ?: return
        for (key in dirty.filter { it.first == uuid }) {
            dirty.remove(key)
            map[key.second]?.let { persistPosition(uuid, key.second, it) }
        }
    }

    private fun flushAll() {
        for (uuid in dirty.map { it.first }.toSet()) flushPlayer(uuid)
    }

    // ── Progress engine ─────────────────────────────────────────

    private fun isExempt(player: Player) =
        player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR ||
            plugin.modModeManager.isModMode(player)

    /** Calls [block] for each event whose *current* quest is of [type], is open, and whose filter matches. */
    private inline fun forActive(player: Player, type: EventObjective, matches: (String?) -> Boolean, block: (EventDef, EventQuestDef) -> Unit) {
        if (!systemEnabled) return
        val candidates = byObjective[type] ?: return
        val now = ZonedDateTime.now(ZONE)
        for (event in candidates) {
            if (!isOpen(event, now)) continue
            val state = getProgress(player.uniqueId, event.id)
            if (state.completed || state.index >= event.quests.size) continue
            val quest = event.quests[state.index]
            if (quest.type != type || !matches(quest.target)) continue
            block(event, quest)
        }
    }

    private fun advance(player: Player, event: EventDef, quest: EventQuestDef, amount: Int) {
        if (amount <= 0) return
        val uuid = player.uniqueId
        val state = getProgress(uuid, event.id)
        // Only the active quest collects progress; a stale reference to an earlier/later quest is ignored.
        if (state.completed || state.index != quest.index) return

        val newProgress = state.progress + amount
        if (newProgress < quest.amount) {
            setState(uuid, event.id, state.copy(progress = newProgress))
            dirty.add(uuid to event.id)
            return
        }

        // Quest done: unlock the next one. Overflow is discarded so a locked quest never gets a head start.
        val nextIndex = quest.index + 1
        val allDone = nextIndex >= event.quests.size
        val next = state.copy(index = nextIndex, progress = 0, completed = allDone)
        setState(uuid, event.id, next)
        dirty.remove(uuid to event.id)
        plugin.databaseManager.execute(
            "INSERT OR IGNORE INTO event_quest_progress (uuid, event_id) VALUES (?, ?)", uuid.toString(), event.id
        )
        plugin.databaseManager.execute(
            "UPDATE event_quest_progress SET quest_index = ?, progress = 0, completed = ? WHERE uuid = ? AND event_id = ? AND claimed = 0",
            nextIndex, if (allDone) 1 else 0, uuid.toString(), event.id
        )
        notifyAdvance(player, event, quest, allDone)
    }

    private fun notifyAdvance(player: Player, event: EventDef, done: EventQuestDef, allDone: Boolean) {
        val comms = plugin.commsManager
        if (allDone) {
            comms.send(player, Component.text("Event Quests: ", NamedTextColor.GOLD)
                .append(Component.text("You completed every quest in ${event.name}! Claim your reward in /eq.", NamedTextColor.GREEN)))
            player.showTitle(Title.title(
                Component.text("Event Complete!", NamedTextColor.GOLD),
                Component.text(event.name, NamedTextColor.YELLOW)
            ))
            player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
        } else {
            val next = event.quests[done.index + 1]
            comms.send(player, Component.text("Event Quests: ", NamedTextColor.GOLD)
                .append(Component.text("Quest ${done.index + 1} of ${event.name} complete! ", NamedTextColor.GREEN))
                .append(Component.text("Quest ${next.index + 1} unlocked: ", NamedTextColor.YELLOW))
                .append(Component.text(describe(next), NamedTextColor.WHITE)))
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f)
        }
    }

    // ── Objective text ──────────────────────────────────────────

    private fun pretty(raw: String?): String? =
        raw?.lowercase()?.split('_')?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    private fun describe(q: EventQuestDef): String {
        q.description?.takeIf { it.isNotBlank() }?.let { return it }
        val t = pretty(q.target)
        return when (q.type) {
            EventObjective.BREAK_BLOCK -> "Break ${q.amount} ${t ?: "blocks"}"
            EventObjective.MINE_ORE -> "Mine ${q.amount} ${t ?: "ore"}"
            EventObjective.PLACE_BLOCK -> "Place ${q.amount} ${t ?: "blocks"}"
            EventObjective.HARVEST_CROP -> "Harvest ${q.amount} ${t ?: "crops"}"
            EventObjective.KILL_MOB -> "Kill ${q.amount} ${t ?: "mobs"}"
            EventObjective.KILL_PLAYER -> "Kill ${q.amount} players"
            EventObjective.CATCH_FISH -> "Catch ${q.amount} ${t ?: "fish"}"
            EventObjective.CRAFT_ITEM -> "Craft ${q.amount} ${t ?: "items"}"
            EventObjective.TRAVEL -> "Travel ${q.amount} blocks on foot"
            EventObjective.SELL_ITEM -> "Sell ${q.amount} ${t ?: "items"}"
            EventObjective.PLAY_TIME -> "Play for ${q.amount} minutes"
        }
    }

    private fun questTitle(q: EventQuestDef): String = q.name?.takeIf { it.isNotBlank() } ?: "Quest ${q.index + 1}"

    // ── Listeners ───────────────────────────────────────────────

    private fun oreVariants(name: String): Set<String> {
        val set = mutableSetOf(name)
        if (name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) set.add(name.removePrefix("DEEPSLATE_"))
        if (!name.startsWith("DEEPSLATE_") && name.endsWith("_ORE")) set.add("DEEPSLATE_$name")
        return set
    }

    private fun normalizeHarvestName(name: String): String = when (name) {
        "KELP_PLANT" -> "KELP"
        "TWISTING_VINES_PLANT" -> "TWISTING_VINES"
        "WEEPING_VINES_PLANT" -> "WEEPING_VINES"
        else -> name
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        if (isExempt(player)) return
        processBreak(player, event.block, harvestCheck = true)
    }

    /** External hook for listeners (e.g. Veinminer) that break blocks without firing a real BlockBreakEvent. */
    fun recordBlockBreak(player: Player, block: Block) {
        if (isExempt(player)) return
        processBreak(player, block, harvestCheck = false)
    }

    private fun processBreak(player: Player, block: Block, harvestCheck: Boolean) {
        if (!systemEnabled || byObjective.isEmpty()) return
        val name = block.type.name
        val playerPlaced = block.hasMetadata(PLACED_META)
        block.removeMetadata(PLACED_META, plugin)
        // Anti-farm: blocks the player placed themselves never count (place-then-break loops).
        if (playerPlaced) return

        forActive(player, EventObjective.BREAK_BLOCK, { it == null || it == name }) { ev, q -> advance(player, ev, q, 1) }
        forActive(player, EventObjective.MINE_ORE, { it == null || it in oreVariants(name) }) { ev, q -> advance(player, ev, q, 1) }

        if (harvestCheck) {
            val normalized = normalizeHarvestName(name)
            val data = block.blockData
            val eligible = (data is Ageable && data.age == data.maximumAge) || normalized in ALWAYS_HARVESTABLE
            if (eligible) {
                forActive(player, EventObjective.HARVEST_CROP, { it == null || it == normalized }) { ev, q -> advance(player, ev, q, 1) }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        event.block.setMetadata(PLACED_META, FixedMetadataValue(plugin, true))
        val player = event.player
        if (isExempt(player)) return
        val name = event.block.type.name
        // Anti-farm: re-placing at the same spot within a few seconds (place/break loops) isn't counted.
        val now = System.currentTimeMillis()
        val key = "${event.block.world.name}:${event.block.x},${event.block.y},${event.block.z}"
        val recent = recentPlaces.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        if (recent.size > 64) recent.entries.removeIf { now - it.value > PLACE_REPEAT_MS }
        val last = recent.put(key, now)
        if (last != null && now - last < PLACE_REPEAT_MS) return
        forActive(player, EventObjective.PLACE_BLOCK, { it == null || it == name }) { ev, q -> advance(player, ev, q, 1) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val entity = event.entity
        val killer = entity.killer ?: return
        if (isExempt(killer)) return
        if (entity.scoreboardTags.contains("joshymc_combat_npc") || entity.scoreboardTags.contains("NPC")) return

        if (entity is Player) {
            if (entity.uniqueId == killer.uniqueId || isExempt(entity)) return
            // Anti-farm: no alt/same-IP kills, and the same victim only counts once per cooldown.
            val killerIp = killer.address?.address
            if (killerIp != null && killerIp == entity.address?.address) return
            val now = System.currentTimeMillis()
            val key = "${killer.uniqueId}:${entity.uniqueId}"
            val last = recentKills[key]
            if (last != null && now - last < KILL_PLAYER_COOLDOWN_MS) return
            recentKills[key] = now
            if (recentKills.size > 512) recentKills.entries.removeIf { now - it.value > KILL_PLAYER_COOLDOWN_MS }
            forActive(killer, EventObjective.KILL_PLAYER, { true }) { ev, q -> advance(killer, ev, q, 1) }
            return
        }

        val name = entity.type.name
        forActive(killer, EventObjective.KILL_MOB, { it == null || it == name }) { ev, q -> advance(killer, ev, q, 1) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val player = event.player
        if (isExempt(player)) return
        val caught = (event.caught as? org.bukkit.entity.Item)?.itemStack?.type?.name
        forActive(player, EventObjective.CATCH_FISH, { it == null || it == caught }) { ev, q -> advance(player, ev, q, 1) }
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
        val name = result.type.name
        forActive(player, EventObjective.CRAFT_ITEM, { it == null || it == name }) { ev, q -> advance(player, ev, q, amount) }
    }

    /** Called by the server shop when a player sells items. */
    fun recordSale(player: Player, material: Material, amount: Int) {
        if (amount <= 0 || isExempt(player)) return
        val name = material.name
        forActive(player, EventObjective.SELL_ITEM, { it == null || it == name }) { ev, q -> advance(player, ev, q, amount) }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (byObjective[EventObjective.TRAVEL] == null) return
        val from = event.from
        val to = event.to ?: return
        if (from.world != to.world) return
        val player = event.player
        if (isExempt(player)) return
        if (player.isFlying || player.isGliding || player.isInsideVehicle) return

        val dx = to.x - from.x
        val dz = to.z - from.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        // Ignore stationary jitter and teleport-like jumps.
        if (dist < 0.01 || dist > 8.0) return

        val uuid = player.uniqueId
        val acc = travelAcc.getOrDefault(uuid, 0.0) + dist
        val blocks = acc.toInt()
        if (blocks >= 5) {
            travelAcc[uuid] = acc - blocks
            forActive(player, EventObjective.TRAVEL, { true }) { ev, q -> advance(player, ev, q, blocks) }
        } else {
            travelAcc[uuid] = acc
        }
    }

    /** Once a minute: +1 minute of play time for every active, non-AFK player. */
    private fun tickPlaytime() {
        if (byObjective[EventObjective.PLAY_TIME] == null) return
        for (player in Bukkit.getOnlinePlayers()) {
            if (isExempt(player) || plugin.afkManager.isAfk(player)) continue
            forActive(player, EventObjective.PLAY_TIME, { true }) { ev, q -> advance(player, ev, q, 1) }
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            // Deliver rewards that were claimed but couldn't be delivered (full inventory, crash, ...).
            for (ev in events.values) {
                val s = getProgress(player.uniqueId, ev.id)
                if (s.claimed && !s.delivered) deliverReward(player, ev)
            }
        }, 40L)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        flushPlayer(uuid)
        cache.remove(uuid)
        travelAcc.remove(uuid)
        recentPlaces.remove(uuid)
    }

    // ── Rewards ─────────────────────────────────────────────────

    /** Builds the reward from the existing custom item system, preserving all of its metadata. */
    fun buildReward(event: EventDef): ItemStack? {
        val stack = when (event.rewardSource) {
            HELD_ITEM -> event.rewardItem?.clone()
            "crate-key" -> plugin.crateManager.createKeyStack(event.rewardItemId, 1)
            else -> plugin.itemManager.getItem(event.rewardItemId)?.createItemStack(1)
        }
        if (stack == null) {
            plugin.logger.warning("[EventQuests] Reward '${event.rewardItemId}' (${event.rewardSource}) for event '${event.id}' does not exist.")
        }
        return stack
    }

    private fun canClaim(event: EventDef, state: EventProgress): Boolean =
        state.completed && !state.claimed && (isOpen(event) || event.claimAfterEnd)

    /**
     * Claims the final reward. The claimed flag is set with a single conditional UPDATE
     * (completed = 1 AND claimed = 0), so double-clicks, GUI reopening, reconnects and
     * restarts can never award it twice.
     */
    private fun claim(player: Player, event: EventDef) {
        val comms = plugin.commsManager
        val uuid = player.uniqueId
        val state = getProgress(uuid, event.id)

        if (state.claimed) {
            if (!state.delivered) deliverReward(player, event)
            else comms.send(player, Component.text("You already claimed this reward.", NamedTextColor.RED))
            return
        }
        if (!state.completed) {
            comms.send(player, Component.text("Complete every quest first.", NamedTextColor.RED))
            return
        }
        if (!canClaim(event, state)) {
            comms.send(player, Component.text("This event has ended and its reward can no longer be claimed.", NamedTextColor.RED))
            return
        }
        // Make sure the reward can actually be built before burning the one-time claim.
        if (buildReward(event) == null) {
            comms.send(player, Component.text("This reward is unavailable right now. Please tell an admin.", NamedTextColor.RED))
            return
        }

        val updated = plugin.databaseManager.executeUpdate(
            "UPDATE event_quest_progress SET claimed = 1 WHERE uuid = ? AND event_id = ? AND completed = 1 AND claimed = 0",
            uuid.toString(), event.id
        )
        if (updated == 0) {
            // Cache disagreed with the database (e.g. admin reset) — resync and refuse.
            cache.remove(uuid)
            comms.send(player, Component.text("You can't claim this reward.", NamedTextColor.RED))
            return
        }
        setState(uuid, event.id, state.copy(claimed = true))
        deliverReward(player, event)
    }

    /**
     * Delivers an already-claimed reward exactly once. `delivered` is flipped with a conditional
     * UPDATE before the item is handed over (so a second call can never also deliver); if the
     * inventory is full it's flipped back and delivery is retried on the next click or join.
     * Uses the shared mod-mode-aware delivery path.
     */
    private fun deliverReward(player: Player, event: EventDef) {
        val comms = plugin.commsManager
        val uuid = player.uniqueId
        val stack = buildReward(event) ?: return

        val flipped = plugin.databaseManager.executeUpdate(
            "UPDATE event_quest_progress SET delivered = 1 WHERE uuid = ? AND event_id = ? AND claimed = 1 AND delivered = 0",
            uuid.toString(), event.id
        )
        if (flipped == 0) return

        val leftover = plugin.depositItemSafely(player, stack)
        if (leftover == null) {
            setState(uuid, event.id, getProgress(uuid, event.id).copy(delivered = true))
            comms.send(player, Component.text("Event Quests: ", NamedTextColor.GOLD)
                .append(Component.text("Reward claimed from ${event.name}!", NamedTextColor.GREEN)))
            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f)
        } else {
            plugin.databaseManager.execute(
                "UPDATE event_quest_progress SET delivered = 0 WHERE uuid = ? AND event_id = ?", uuid.toString(), event.id
            )
            comms.send(player, Component.text("Your inventory is full — free a slot and click the reward again (or rejoin) to receive it.", NamedTextColor.YELLOW))
        }
    }

    // ── Admin questline creation ────────────────────────────────

    private fun customConfig(): YamlConfiguration {
        val file = plugin.configFile(CUSTOM_FILE)
        return if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
    }

    private fun isComplete(s: ConfigurationSection): Boolean =
        s.contains("reward.item") && (s.getConfigurationSection("quests")?.getKeys(false)?.size ?: 0) >= MIN_QUESTS

    /** Questline ids created in-game (complete or draft). */
    fun customQuestlineIds(): List<String> =
        customConfig().getConfigurationSection("questlines")?.getKeys(false)?.toList() ?: emptyList()

    /** Objective filter suggestions for tab completion. */
    fun targetSuggestions(type: EventObjective): List<String> = when (type) {
        EventObjective.KILL_MOB -> org.bukkit.entity.EntityType.entries
            .filter { it != org.bukkit.entity.EntityType.UNKNOWN && it != org.bukkit.entity.EntityType.PLAYER }
            .map { it.name } + "ANY"
        EventObjective.KILL_PLAYER, EventObjective.TRAVEL, EventObjective.PLAY_TIME -> emptyList()
        else -> Material.entries.filter { !it.isLegacy && it.isItem }.map { it.name } + "ANY"
    }

    private fun saveCustom(cfg: YamlConfiguration) {
        val file = plugin.configFile(CUSTOM_FILE)
        file.parentFile?.mkdirs()
        cfg.save(file)
        loadEvents()
    }

    /** Status line for admins: quest count, whether a reward is set, and whether the questline is live. */
    fun questlineStatus(id: String): String {
        val s = customConfig().getConfigurationSection("questlines.${id.lowercase()}") ?: return "Unknown questline '$id'."
        val count = s.getConfigurationSection("quests")?.getKeys(false)?.size ?: 0
        val reward = if (s.contains("reward.item")) "reward set" else "no reward yet"
        val live = if (isComplete(s)) "LIVE in /eq" else "draft (needs $MIN_QUESTS-$MAX_QUESTS quests and a reward)"
        return "$count/$MAX_QUESTS quests, $reward — $live"
    }

    /** Returns an error message, or null on success. */
    fun createQuestline(rawId: String): String? {
        val id = rawId.lowercase()
        if (!QUESTLINE_ID.matches(id)) return "Name must be 1-32 characters: letters, digits, '-' or '_'."
        val cfg = customConfig()
        val mainFile = plugin.configFile("event-quests.yml")
        val inMain = mainFile.exists() && YamlConfiguration.loadConfiguration(mainFile).contains("event-quests.events.$id")
        if (cfg.contains("questlines.$id") || id in events || inMain) return "A quest set named '$id' already exists."
        cfg.set("questlines.$id.name", rawId)
        cfg.set("questlines.$id.enabled", true)
        saveCustom(cfg)
        return null
    }

    private fun anyProgress(id: String, column: String): Boolean {
        var found = false
        plugin.databaseManager.query(
            "SELECT 1 FROM event_quest_progress WHERE event_id = ? AND $column = 1 LIMIT 1", id
        ) { _ -> found = true }
        return found
    }

    /** Stores an exact copy of [stack] (components, enchants, model data, amount) as the reward. */
    fun setQuestlineReward(rawId: String, stack: ItemStack): String? {
        val id = rawId.lowercase()
        val cfg = customConfig()
        if (!cfg.contains("questlines.$id")) return "Unknown questline '$rawId'. Create it with /eq admin create first."
        if (stack.type == Material.AIR) return "Hold the item you want to use as the reward."
        if (anyProgress(id, "claimed")) return "Players have already claimed this reward, so it can't be changed."
        cfg.set("questlines.$id.reward.source", HELD_ITEM)
        cfg.set("questlines.$id.reward.item", java.util.Base64.getEncoder().encodeToString(stack.serializeAsBytes()))
        saveCustom(cfg)
        return null
    }

    /** Appends an objective. [rawTarget] null/ANY = no filter. Returns an error message, or null on success. */
    fun addQuestlineQuest(rawId: String, type: EventObjective, rawTarget: String?, amount: Int): String? {
        val id = rawId.lowercase()
        val cfg = customConfig()
        if (!cfg.contains("questlines.$id")) return "Unknown questline '$rawId'. Create it with /eq admin create first."
        if (amount <= 0) return "Amount must be a positive number."
        val target = rawTarget?.uppercase()?.takeIf { it != "ANY" }
        val key = if (type == EventObjective.KILL_MOB) "entity" else "material"
        if (target != null && !validTarget(type, target)) return "Unknown $key '$rawTarget' for ${type.name}."
        val count = cfg.getConfigurationSection("questlines.$id.quests")?.getKeys(false)?.size ?: 0
        if (count >= MAX_QUESTS) return "A questline can have at most $MAX_QUESTS quests."
        if (anyProgress(id, "completed")) return "Players have already completed this questline, so quests can't be added."
        val path = "questlines.$id.quests.${count + 1}"
        cfg.set("$path.type", type.name)
        if (target != null) cfg.set("$path.$key", target)
        cfg.set("$path.amount", amount)
        saveCustom(cfg)
        return null
    }

    // ── Admin ───────────────────────────────────────────────────

    fun describeProgress(uuid: UUID, event: EventDef): List<String> {
        if (Bukkit.getPlayer(uuid) != null) flushPlayer(uuid)
        val s = if (Bukkit.getPlayer(uuid) != null) getProgress(uuid, event.id) else loadPlayer(uuid)[event.id] ?: EventProgress()
        val lines = mutableListOf<String>()
        if (s.completed) {
            lines += "All ${event.quests.size} quests completed."
        } else {
            val q = event.quests[s.index.coerceIn(0, event.quests.size - 1)]
            lines += "Quest ${q.index + 1}/${event.quests.size}: ${describe(q)} (${s.progress}/${q.amount})"
        }
        lines += "Reward: " + when {
            s.claimed && s.delivered -> "claimed and delivered"
            s.claimed -> "claimed, awaiting delivery"
            s.completed -> "unlocked, unclaimed"
            else -> "locked"
        }
        return lines
    }

    /** Two-step reset: first call arms a confirmation, a repeat within 30s performs it. Returns true when performed. */
    fun requestReset(senderName: String, targetUuid: UUID, event: EventDef): Boolean {
        val key = "$senderName:$targetUuid:${event.id}"
        val now = System.currentTimeMillis()
        val armed = pendingResets[key]
        if (armed == null || now - armed > RESET_CONFIRM_MS) {
            pendingResets[key] = now
            return false
        }
        pendingResets.remove(key)
        // Drop the cached copy without flushing so stale progress can't be written back over the reset.
        dirty.remove(targetUuid to event.id)
        cache[targetUuid]?.remove(event.id)
        plugin.databaseManager.execute(
            "DELETE FROM event_quest_progress WHERE uuid = ? AND event_id = ?", targetUuid.toString(), event.id
        )
        plugin.logger.info("[EventQuests] $senderName reset event '${event.id}' progress and reward claim for $targetUuid.")
        return true
    }

    // ── GUI ─────────────────────────────────────────────────────

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply { editMeta { it.displayName(Component.empty()) } }

    private fun plain(text: String, color: NamedTextColor, bold: Boolean = false): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, bold)

    private fun percent(event: EventDef, s: EventProgress): Int {
        if (s.completed) return 100
        val q = event.quests[s.index.coerceIn(0, event.quests.size - 1)]
        val fraction = s.index + (s.progress.toDouble() / q.amount).coerceIn(0.0, 1.0)
        return (fraction / event.quests.size * 100).toInt()
    }

    fun openGui(player: Player) {
        val list = events.values.toList()
        val gui = CustomGui(plain("Event Quests", NamedTextColor.GOLD, true), 54)
        gui.fill(FILLER)
        if (!systemEnabled || list.isEmpty()) {
            gui.setItem(22, ItemStack(Material.BARRIER).apply {
                editMeta {
                    it.displayName(plain("No events right now", NamedTextColor.RED, true))
                    it.lore(listOf(plain("Check back soon!", NamedTextColor.GRAY)))
                }
            })
        }
        val slots = (10..16) + (19..25) + (28..34) + (37..43)
        list.take(slots.size).forEachIndexed { i, event -> gui.setItem(slots[i], eventIcon(player, event)) { p, _ -> openEventGui(p, event) } }
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun eventIcon(player: Player, event: EventDef): ItemStack {
        val s = getProgress(player.uniqueId, event.id)
        val pct = percent(event, s)
        // The icon is the real reward stack (fresh from buildReward, never a shared template), so custom
        // armor keeps its item model instead of looking like plain Netherite. A display-item override only
        // wins when it's a different material; one naming the reward's own base material would hide the model.
        val reward = buildReward(event)
        val item = when {
            reward != null && (event.displayItem == null || event.displayItem == reward.type) -> reward
            event.displayItem != null -> ItemStack(event.displayItem)
            else -> ItemStack(Material.NETHER_STAR)
        }
        item.editMeta { m ->
            m.displayName(plain(event.name, NamedTextColor.GOLD, true))
            val lore = mutableListOf<Component>()
            if (event.description.isNotBlank()) lore += plain(event.description, NamedTextColor.GRAY)
            lore += Component.empty()
            lore += plain("Reward: ${rewardName(event)}", NamedTextColor.LIGHT_PURPLE)
            lore += plain("Quests: ${event.quests.size}", NamedTextColor.GRAY)
            lore += plain("Progress: ${if (s.completed) event.quests.size else s.index}/${event.quests.size} ($pct%)", NamedTextColor.YELLOW)
            lore += plain(availability(event), if (isOpen(event)) NamedTextColor.GREEN else NamedTextColor.RED)
            lore += plain(
                when {
                    s.claimed -> "Reward claimed"
                    s.completed -> "Reward ready to claim!"
                    else -> "Reward unclaimed"
                },
                if (s.claimed) NamedTextColor.DARK_GREEN else if (s.completed) NamedTextColor.GREEN else NamedTextColor.GRAY
            )
            lore += Component.empty()
            lore += plain("Click to view", NamedTextColor.YELLOW)
            m.lore(lore)
        }
        return item
    }

    private fun rewardName(event: EventDef): String =
        buildReward(event)?.let { r ->
            r.itemMeta?.displayName()?.let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) }
        } ?: pretty(event.rewardItemId) ?: event.rewardItemId

    fun openEventGui(player: Player, event: EventDef) {
        val gui = CustomGui(plain(event.name.take(26), NamedTextColor.GOLD, true), 54)
        gui.fill(FILLER)
        val s = getProgress(player.uniqueId, event.id)

        // Reward preview / claim button
        val reward = buildReward(event) ?: ItemStack(Material.BARRIER)
        val rewardIcon = reward.clone()
        rewardIcon.editMeta { m ->
            val lore = (m.lore() ?: emptyList()).toMutableList()
            lore += Component.empty()
            when {
                s.claimed && s.delivered -> lore += plain("Already claimed", NamedTextColor.DARK_GREEN, true)
                s.claimed -> lore += plain("Click to receive your reward", NamedTextColor.YELLOW, true)
                canClaim(event, s) -> lore += plain("Click to claim!", NamedTextColor.GREEN, true)
                s.completed -> lore += plain("Event ended - reward can't be claimed", NamedTextColor.RED)
                else -> lore += plain("Complete all ${event.quests.size} quests to unlock", NamedTextColor.GRAY)
            }
            m.lore(lore)
        }
        gui.setItem(4, rewardIcon) { p, _ ->
            p.closeInventory()
            claim(p, event)
        }

        val slots = if (event.quests.size <= 5) listOf(20, 21, 22, 23, 24).let { all ->
            val offset = (5 - event.quests.size) / 2
            all.subList(offset, offset + event.quests.size)
        } else (19..23).toList() + (28..32).toList()

        event.quests.forEachIndexed { i, q ->
            val item = when {
                s.completed || q.index < s.index -> questItem(Material.LIME_STAINED_GLASS_PANE, q,
                    listOf(plain("✔ Completed", NamedTextColor.GREEN, true)), NamedTextColor.GREEN)
                q.index == s.index -> {
                    val pct = (s.progress * 100 / q.amount).coerceIn(0, 100)
                    questItem(Material.YELLOW_CONCRETE, q, listOf(
                        plain(describe(q), NamedTextColor.WHITE),
                        plain("Progress: ${s.progress}/${q.amount} ($pct%)", NamedTextColor.YELLOW),
                        plain("ACTIVE", NamedTextColor.GOLD, true)
                    ), NamedTextColor.GOLD).apply { editMeta { it.setEnchantmentGlintOverride(true) } }
                }
                else -> ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
                    editMeta {
                        it.displayName(plain("Quest ${q.index + 1}: ???", NamedTextColor.DARK_GRAY, true))
                        it.lore(listOf(plain("Complete previous quest", NamedTextColor.GRAY)))
                    }
                }
            }
            gui.setItem(slots[i], item)
        }

        val info = ItemStack(Material.PAPER).apply {
            editMeta {
                it.displayName(plain("${percent(event, s)}% complete", NamedTextColor.YELLOW, true))
                it.lore(listOf(
                    plain(availability(event), if (isOpen(event)) NamedTextColor.GREEN else NamedTextColor.RED),
                    plain("Quests must be completed in order.", NamedTextColor.GRAY)
                ))
            }
        }
        gui.setItem(40, info)
        gui.setItem(49, ItemStack(Material.ARROW).apply { editMeta { it.displayName(plain("Back", NamedTextColor.GRAY, true)) } }) { p, _ -> openGui(p) }
        plugin.guiManager.open(player, gui)
    }

    private fun questItem(mat: Material, q: EventQuestDef, lore: List<Component>, color: NamedTextColor): ItemStack =
        ItemStack(mat).apply {
            editMeta {
                it.displayName(plain(questTitle(q), color, true))
                it.lore(if (mat == Material.LIME_STAINED_GLASS_PANE) listOf(plain(describe(q), NamedTextColor.GRAY)) + lore else lore)
            }
        }
}
