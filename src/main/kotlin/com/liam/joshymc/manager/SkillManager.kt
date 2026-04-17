package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityBreedEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTameEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SkillManager(private val plugin: Joshymc) : Listener {

    companion object {
        const val MAX_LEVEL = 100
        private const val SAVE_INTERVAL_TICKS = 20L * 60L * 5L // 5 minutes
        private const val ACTION_BAR_COOLDOWN_MS = 1000L
    }

    // ── Skill enum ──────────────────────────────────────────────────────

    enum class Skill(val displayName: String, val icon: Material, val color: String) {
        MINING("Mining", Material.DIAMOND_PICKAXE, "&b"),
        FARMING("Farming", Material.GOLDEN_HOE, "&a"),
        COMBAT("Combat", Material.DIAMOND_SWORD, "&c"),
        FISHING("Fishing", Material.FISHING_ROD, "&3"),
        WOODCUTTING("Woodcutting", Material.IRON_AXE, "&6"),
        EXCAVATION("Excavation", Material.IRON_SHOVEL, "&e"),
        ENCHANTING("Enchanting", Material.ENCHANTING_TABLE, "&d"),
        ALCHEMY("Alchemy", Material.BREWING_STAND, "&5"),
        TAMING("Taming", Material.LEAD, "&2")
    }

    data class SkillPerk(val level: Int, val description: String)

    // ── XP formula ──────────────────────────────────────────────────────

    fun xpForLevel(level: Int): Long {
        if (level <= 1) return 0
        return (100L * level * level)
    }

    // ── Cache: player -> skill -> (level, totalXp) ──────────────────────

    private val cache = ConcurrentHashMap<UUID, MutableMap<Skill, Pair<Int, Long>>>()
    private val actionBarCooldowns = ConcurrentHashMap<UUID, MutableMap<Skill, Long>>()
    private var saveTask: BukkitTask? = null

    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_skills (
                uuid TEXT NOT NULL,
                skill TEXT NOT NULL,
                xp INTEGER DEFAULT 0,
                level INTEGER DEFAULT 1,
                PRIMARY KEY (uuid, skill)
            )
        """.trimIndent())

        // Load online players (for /reload)
        Bukkit.getOnlinePlayers().forEach { loadPlayer(it.uniqueId) }

        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { saveAll() }, SAVE_INTERVAL_TICKS, SAVE_INTERVAL_TICKS)

        plugin.logger.info("[Skills] Skill manager started.")
    }

    fun stop() {
        saveAll()
        saveTask?.cancel()
        saveTask = null
        cache.clear()
        actionBarCooldowns.clear()
        plugin.logger.info("[Skills] Skill manager stopped.")
    }

    // ── Data access ─────────────────────────────────────────────────────

    fun getLevel(uuid: UUID, skill: Skill): Int {
        return getEntry(uuid, skill).first
    }

    fun getXp(uuid: UUID, skill: Skill): Long {
        return getEntry(uuid, skill).second
    }

    fun getXpForNextLevel(uuid: UUID, skill: Skill): Long {
        val (level, xp) = getEntry(uuid, skill)
        if (level >= MAX_LEVEL) return 0
        return xpForLevel(level + 1) - xp
    }

    fun getProgress(uuid: UUID, skill: Skill): Double {
        val (level, xp) = getEntry(uuid, skill)
        if (level >= MAX_LEVEL) return 1.0
        val currentLevelXp = xpForLevel(level)
        val nextLevelXp = xpForLevel(level + 1)
        val range = nextLevelXp - currentLevelXp
        if (range <= 0) return 1.0
        return ((xp - currentLevelXp).toDouble() / range).coerceIn(0.0, 1.0)
    }

    fun hasPerk(uuid: UUID, skill: Skill, perkLevel: Int): Boolean {
        return getLevel(uuid, skill) >= perkLevel
    }

    // ── XP mutation ─────────────────────────────────────────────────────

    fun addXp(player: Player, skill: Skill, amount: Int) {
        if (amount <= 0) return
        // No XP in creative or spectator
        if (player.gameMode == org.bukkit.GameMode.CREATIVE || player.gameMode == org.bukkit.GameMode.SPECTATOR) return
        val uuid = player.uniqueId
        val map = cache.getOrPut(uuid) { loadPlayerMap(uuid) }
        val (oldLevel, oldXp) = map.getOrDefault(skill, Pair(1, 0L))

        if (oldLevel >= MAX_LEVEL) return

        val newXp = oldXp + amount
        var newLevel = oldLevel

        // Check level ups
        while (newLevel < MAX_LEVEL && newXp >= xpForLevel(newLevel + 1)) {
            newLevel++
        }

        map[skill] = Pair(newLevel, newXp)

        // Action bar XP display (throttled)
        showActionBar(player, skill, amount, newLevel, newXp)

        // Level up notifications
        if (newLevel > oldLevel) {
            for (lvl in (oldLevel + 1)..newLevel) {
                onLevelUp(player, skill, lvl)
            }
        }
    }

    // ── Perks ───────────────────────────────────────────────────────────

    private val perkData: Map<Skill, List<SkillPerk>> = mapOf(
        Skill.MINING to listOf(
            SkillPerk(10, "+5% ore drops"),
            SkillPerk(25, "Chance to find money while mining (\$100-\$1000)"),
            SkillPerk(50, "Double ore drops (10% chance)"),
            SkillPerk(75, "Auto-smelt ores (25% chance)"),
            SkillPerk(100, "Permanent Haste I while holding a pickaxe")
        ),
        Skill.FARMING to listOf(
            SkillPerk(10, "+5% crop drops"),
            SkillPerk(25, "Crops occasionally drop seeds back"),
            SkillPerk(50, "Double harvest (10% chance)"),
            SkillPerk(75, "Auto-replant (25% chance)"),
            SkillPerk(100, "3x3 harvest radius")
        ),
        Skill.COMBAT to listOf(
            SkillPerk(10, "+5% damage"),
            SkillPerk(25, "+5% damage reduction"),
            SkillPerk(50, "+10% damage"),
            SkillPerk(75, "Lifesteal (2% of damage healed)"),
            SkillPerk(100, "+15% damage, +10% damage reduction")
        ),
        Skill.FISHING to listOf(
            SkillPerk(10, "+5% fish catch rate"),
            SkillPerk(25, "Chance to catch double fish"),
            SkillPerk(50, "Increased rare fish chance"),
            SkillPerk(75, "Auto-cook fish"),
            SkillPerk(100, "Legendary fish chance doubled")
        ),
        Skill.WOODCUTTING to listOf(
            SkillPerk(10, "+5% log drops"),
            SkillPerk(25, "Chance to get apples from leaves"),
            SkillPerk(50, "Double log drops (10% chance)"),
            SkillPerk(75, "Auto-strip logs"),
            SkillPerk(100, "Entire tree falls on chop")
        ),
        Skill.EXCAVATION to listOf(
            SkillPerk(10, "+5% drops"),
            SkillPerk(25, "Chance to find treasure (money, gems)"),
            SkillPerk(50, "Double drops (10% chance)"),
            SkillPerk(75, "3x3 dig radius"),
            SkillPerk(100, "Find ancient artifacts (rare items)")
        ),
        Skill.ENCHANTING to listOf(
            SkillPerk(10, "5% discount on enchant levels"),
            SkillPerk(25, "Chance to not consume lapis"),
            SkillPerk(50, "Occasional bonus enchantment"),
            SkillPerk(75, "Higher-level enchants available"),
            SkillPerk(100, "Never consume XP levels (10% chance)")
        ),
        Skill.ALCHEMY to listOf(
            SkillPerk(10, "10% longer potion duration"),
            SkillPerk(25, "Chance for double brew output"),
            SkillPerk(50, "25% longer duration"),
            SkillPerk(75, "Splash potions have larger radius"),
            SkillPerk(100, "Chance for enhanced potions (+1 amplifier)")
        ),
        Skill.TAMING to listOf(
            SkillPerk(10, "Animals breed faster"),
            SkillPerk(25, "Tamed animals have +10% HP"),
            SkillPerk(50, "Chance for twin babies on breed"),
            SkillPerk(75, "Tamed animals deal +25% damage"),
            SkillPerk(100, "All tamed animals have double HP")
        )
    )

    fun getSkillPerks(skill: Skill): List<SkillPerk> = perkData[skill] ?: emptyList()

    // ── XP tables per activity ──────────────────────────────────────────

    private val miningXp: Map<Material, Int> = buildMap {
        // Stone
        put(Material.STONE, 1)
        put(Material.COBBLESTONE, 1)
        put(Material.DEEPSLATE, 2)
        put(Material.COBBLED_DEEPSLATE, 2)

        // Coal
        put(Material.COAL_ORE, 3)
        put(Material.DEEPSLATE_COAL_ORE, 4)

        // Iron
        put(Material.IRON_ORE, 5)
        put(Material.DEEPSLATE_IRON_ORE, 6)

        // Copper
        put(Material.COPPER_ORE, 3)
        put(Material.DEEPSLATE_COPPER_ORE, 4)

        // Gold
        put(Material.GOLD_ORE, 7)
        put(Material.DEEPSLATE_GOLD_ORE, 8)
        put(Material.NETHER_GOLD_ORE, 5)

        // Lapis
        put(Material.LAPIS_ORE, 4)
        put(Material.DEEPSLATE_LAPIS_ORE, 5)

        // Redstone
        put(Material.REDSTONE_ORE, 4)
        put(Material.DEEPSLATE_REDSTONE_ORE, 5)

        // Diamond
        put(Material.DIAMOND_ORE, 15)
        put(Material.DEEPSLATE_DIAMOND_ORE, 16)

        // Emerald
        put(Material.EMERALD_ORE, 12)
        put(Material.DEEPSLATE_EMERALD_ORE, 13)

        // Nether
        put(Material.ANCIENT_DEBRIS, 30)
        put(Material.NETHER_QUARTZ_ORE, 4)

        // Amethyst
        put(Material.AMETHYST_CLUSTER, 5)
        put(Material.BUDDING_AMETHYST, 5)

        // Netherite
        put(Material.NETHERITE_BLOCK, 10)
    }

    private val farmingXp: Map<Material, Int> = buildMap {
        put(Material.WHEAT, 2)
        put(Material.CARROTS, 2)
        put(Material.POTATOES, 2)
        put(Material.BEETROOTS, 2)
        put(Material.SUGAR_CANE, 3)
        put(Material.MELON, 3)
        put(Material.PUMPKIN, 3)
        put(Material.NETHER_WART, 5)
        put(Material.COCOA, 4)
        put(Material.SWEET_BERRY_BUSH, 2)
        put(Material.BAMBOO, 1)
        put(Material.CACTUS, 2)
        put(Material.CHORUS_PLANT, 8)
        put(Material.CHORUS_FLOWER, 8)
    }

    private val excavationXp: Map<Material, Int> = buildMap {
        put(Material.DIRT, 1)
        put(Material.GRASS_BLOCK, 1)
        put(Material.COARSE_DIRT, 1)
        put(Material.ROOTED_DIRT, 1)
        put(Material.PODZOL, 1)
        put(Material.DIRT_PATH, 1)
        put(Material.SAND, 1)
        put(Material.RED_SAND, 1)
        put(Material.GRAVEL, 1)
        put(Material.CLAY, 3)
        put(Material.SOUL_SAND, 2)
        put(Material.SOUL_SOIL, 2)
        put(Material.MYCELIUM, 3)
        put(Material.MUD, 1)
        put(Material.MUDDY_MANGROVE_ROOTS, 1)
    }

    private val combatXp: Map<org.bukkit.entity.EntityType, Int> = buildMap {
        put(org.bukkit.entity.EntityType.ZOMBIE, 5)
        put(org.bukkit.entity.EntityType.SKELETON, 5)
        put(org.bukkit.entity.EntityType.SPIDER, 5)
        put(org.bukkit.entity.EntityType.CREEPER, 5)
        put(org.bukkit.entity.EntityType.CAVE_SPIDER, 5)
        put(org.bukkit.entity.EntityType.ZOMBIE_VILLAGER, 5)
        put(org.bukkit.entity.EntityType.HUSK, 5)
        put(org.bukkit.entity.EntityType.STRAY, 5)
        put(org.bukkit.entity.EntityType.DROWNED, 5)
        put(org.bukkit.entity.EntityType.PHANTOM, 8)
        put(org.bukkit.entity.EntityType.ENDERMAN, 12)
        put(org.bukkit.entity.EntityType.BLAZE, 15)
        put(org.bukkit.entity.EntityType.GHAST, 20)
        put(org.bukkit.entity.EntityType.WITHER_SKELETON, 18)
        put(org.bukkit.entity.EntityType.WITCH, 12)
        put(org.bukkit.entity.EntityType.GUARDIAN, 10)
        put(org.bukkit.entity.EntityType.ELDER_GUARDIAN, 50)
        put(org.bukkit.entity.EntityType.WITHER, 200)
        put(org.bukkit.entity.EntityType.ENDER_DRAGON, 500)
        put(org.bukkit.entity.EntityType.PLAYER, 25)
    }

    // ── Event listeners ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val mat = event.block.type
        val name = mat.name

        // Mining
        miningXp[mat]?.let { xp ->
            addXp(player, Skill.MINING, xp)
            return
        }

        // Woodcutting — any log type
        if (name.endsWith("_LOG") || name.endsWith("_WOOD") || name == "CRIMSON_STEM" || name == "WARPED_STEM" ||
            name == "CRIMSON_HYPHAE" || name == "WARPED_HYPHAE") {
            val xp = if (name.startsWith("STRIPPED_")) 3 else 2
            addXp(player, Skill.WOODCUTTING, xp)
            return
        }

        // Farming — crops must be fully grown (Ageable at max age)
        if (farmingXp.containsKey(mat)) {
            val blockData = event.block.blockData
            if (blockData is Ageable) {
                if (blockData.age < blockData.maximumAge) return
            }
            farmingXp[mat]?.let { xp -> addXp(player, Skill.FARMING, xp) }
            return
        }

        // Excavation
        excavationXp[mat]?.let { xp ->
            addXp(player, Skill.EXCAVATION, xp)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val killer = event.entity.killer ?: return
        val entityType = event.entityType
        val xp = combatXp[entityType] ?: return
        addXp(killer, Skill.COMBAT, xp)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        addXp(event.player, Skill.FISHING, 5)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val levelsSpent = event.expLevelCost
        val xp = (levelsSpent * 10 / 3).coerceIn(10, 50)
        addXp(event.enchanter, Skill.ENCHANTING, xp)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        // BrewEvent doesn't have a direct player reference — find the closest player within 10 blocks
        val brewLoc = event.block.location
        val nearestPlayer = brewLoc.world?.getNearbyEntities(brewLoc, 10.0, 10.0, 10.0)
            ?.filterIsInstance<Player>()
            ?.minByOrNull { it.location.distanceSquared(brewLoc) }
            ?: return

        // Count non-empty result slots to scale XP
        val resultCount = event.results.count { it != null && it.type != Material.AIR }
        val xp = (5 * resultCount).coerceIn(5, 15)
        addXp(nearestPlayer, Skill.ALCHEMY, xp)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreed(event: EntityBreedEvent) {
        val breeder = event.breeder as? Player ?: return
        addXp(breeder, Skill.TAMING, 5)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTame(event: EntityTameEvent) {
        val owner = event.owner as? Player ?: return
        addXp(owner, Skill.TAMING, 15)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        savePlayer(uuid)
        cache.remove(uuid)
        actionBarCooldowns.remove(uuid)
    }

    // ── GUI ─────────────────────────────────────────────────────────────

    fun openSkillsMenu(player: Player) {
        val uuid = player.uniqueId
        val gui = CustomGui(legacy.deserialize("&8Skills"), 54)

        // Black glass border
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
            editMeta { it.displayName(Component.text(" ")) }
        }
        gui.border(filler)

        // 3x3 grid centered: slots 20,21,22 / 29,30,31 / 38,39,40
        val slots = intArrayOf(20, 21, 22, 29, 30, 31, 38, 39, 40)

        Skill.entries.forEachIndexed { index, skill ->
            val level = getLevel(uuid, skill)
            val xp = getXp(uuid, skill)
            val progress = getProgress(uuid, skill)
            val nextLevelXp = if (level >= MAX_LEVEL) xp else xpForLevel(level + 1)
            val currentLevelXp = xpForLevel(level)
            val perks = getSkillPerks(skill)

            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(legacy.deserialize("&7Level: ${skill.color}$level"))

            // Progress bar
            val filled = (progress * 10).toInt().coerceIn(0, 10)
            val empty = 10 - filled
            val bar = "&a" + "\u2588".repeat(filled) + "&7" + "\u2591".repeat(empty)
            val xpDisplay = formatNumber(xp - currentLevelXp)
            val xpNeeded = formatNumber(nextLevelXp - currentLevelXp)
            if (level >= MAX_LEVEL) {
                lore.add(legacy.deserialize("$bar &7MAX LEVEL"))
            } else {
                lore.add(legacy.deserialize("$bar &7$xpDisplay/$xpNeeded XP"))
            }

            // Perks
            lore.add(Component.empty())
            lore.add(legacy.deserialize("&7Perks:"))
            for (perk in perks) {
                val color = if (level >= perk.level) "&a" else "&8"
                val check = if (level >= perk.level) "\u2714" else "\u2716"
                lore.add(legacy.deserialize("$color $check Lv${perk.level}: ${perk.description}"))
            }

            val item = ItemStack(skill.icon).apply {
                editMeta { meta ->
                    meta.displayName(legacy.deserialize("${skill.color}&l${skill.displayName}")
                        .decoration(TextDecoration.ITALIC, false))
                    meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
                }
            }

            gui.setItem(slots[index], item)
        }

        // Fill remaining empty slots
        gui.fill(filler)

        plugin.guiManager.open(player, gui)
    }

    // ── Internals ───────────────────────────────────────────────────────

    private fun getEntry(uuid: UUID, skill: Skill): Pair<Int, Long> {
        val map = cache.getOrPut(uuid) { loadPlayerMap(uuid) }
        return map.getOrDefault(skill, Pair(1, 0L))
    }

    private fun showActionBar(player: Player, skill: Skill, amount: Int, level: Int, totalXp: Long) {
        val now = System.currentTimeMillis()
        val cooldowns = actionBarCooldowns.getOrPut(player.uniqueId) { mutableMapOf() }
        val last = cooldowns[skill] ?: 0L
        if (now - last < ACTION_BAR_COOLDOWN_MS) return
        cooldowns[skill] = now

        val progress = getProgress(player.uniqueId, skill)
        val percent = (progress * 100).toInt()
        val msg = "${skill.color}+$amount ${skill.displayName} XP &7(Level $level \u2014 $percent%)"
        player.sendActionBar(legacy.deserialize(msg))
    }

    private fun onLevelUp(player: Player, skill: Skill, level: Int) {
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)

        plugin.commsManager.sendRaw(player,
            legacy.deserialize("&6&l\u2605 LEVEL UP! &e${skill.displayName} is now level $level!"))

        // Check perk unlocks
        val perks = getSkillPerks(skill)
        perks.filter { it.level == level }.forEach { perk ->
            plugin.commsManager.sendRaw(player,
                legacy.deserialize("&a&lPerk unlocked: ${perk.description}"))
        }

        // Title
        val title = Title.title(
            legacy.deserialize("&6\u2605"),
            legacy.deserialize("&e${skill.displayName} Level $level"),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))
        )
        player.showTitle(title)
    }

    // ── Persistence ─────────────────────────────────────────────────────

    private fun loadPlayer(uuid: UUID) {
        cache[uuid] = loadPlayerMap(uuid)
    }

    private fun loadPlayerMap(uuid: UUID): MutableMap<Skill, Pair<Int, Long>> {
        val map = mutableMapOf<Skill, Pair<Int, Long>>()
        val rows = plugin.databaseManager.query(
            "SELECT skill, xp, level FROM player_skills WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            Triple(rs.getString("skill"), rs.getLong("xp"), rs.getInt("level"))
        }
        for ((skillName, xp, level) in rows) {
            val skill = try { Skill.valueOf(skillName) } catch (_: Exception) { continue }
            map[skill] = Pair(level, xp)
        }
        return map
    }

    private fun savePlayer(uuid: UUID) {
        val map = cache[uuid] ?: return
        plugin.databaseManager.transaction {
            for ((skill, entry) in map) {
                val (level, xp) = entry
                plugin.databaseManager.execute(
                    """INSERT INTO player_skills (uuid, skill, xp, level) VALUES (?, ?, ?, ?)
                       ON CONFLICT(uuid, skill) DO UPDATE SET xp = ?, level = ?""",
                    uuid.toString(), skill.name, xp, level, xp, level
                )
            }
        }
    }

    private fun saveAll() {
        for (uuid in cache.keys.toList()) {
            savePlayer(uuid)
        }
    }

    // ── Leaderboard ─────────────────────────────────────────────────────

    fun getTopPlayers(skill: Skill, limit: Int = 10): List<Triple<String, Int, Long>> {
        return plugin.databaseManager.query(
            "SELECT uuid, level, xp FROM player_skills WHERE skill = ? ORDER BY xp DESC LIMIT ?",
            skill.name, limit
        ) { rs ->
            Triple(rs.getString("uuid"), rs.getInt("level"), rs.getLong("xp"))
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    private fun formatNumber(value: Long): String {
        return "%,d".format(value)
    }
}
