package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.crate.CrateRewardText
import com.liam.joshymc.crate.CrateSelection
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.gui.GuiStyle
import com.liam.joshymc.util.MinecraftColors
import com.liam.joshymc.util.depositItemSafely
import com.liam.joshymc.util.giveItemSafely
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import com.liam.joshymc.crate.CrateFacing
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID

class CrateManager(private val plugin: Joshymc) : Listener {

    companion object {
        private val CRATE_GUI_TITLE_PREFIX = "Crate: "
        private val PREVIEW_GUI_TITLE_PREFIX = "Preview: "
        private const val ACTIVATION_DEBOUNCE_MS = 500L
        /** How long a finished Spin / Pulse stays on its result screen before closing (issue #1514). */
        private const val RESULT_SCREEN_TICKS = 60L
    }

    /**
     * Shape-based reward layout for the Pick-a-Reward (Select / Select 3) GUIs.
     * The Preview GUI is sized to its rewards by [CratePreviewLayout] instead (issue #1414).
     */
    private object CrateLayout {
        const val USABLE_ROWS = 3
        const val USABLE_COLUMNS = 7
        const val PAGE_CAPACITY = USABLE_ROWS * USABLE_COLUMNS // 21

        // Predefined compact formations (row-by-row item counts, top to bottom).
        // Above this, rows are distributed as evenly as possible (see rowCounts).
        private val FIXED_SHAPES = mapOf(
            1 to listOf(1), 2 to listOf(2), 3 to listOf(3),
            4 to listOf(2, 2), 5 to listOf(3, 2), 6 to listOf(3, 3),
            7 to listOf(7),
            8 to listOf(4, 4), 9 to listOf(3, 3, 3), 10 to listOf(5, 5),
            11 to listOf(7, 4), 12 to listOf(6, 6), 13 to listOf(7, 6), 14 to listOf(7, 7)
        )

        /** Column indices (0..6) that symmetrically center [rowLen] items within a 7-wide row. */
        private fun columnIndices(rowLen: Int): List<Int> = when (rowLen) {
            0 -> emptyList()
            1 -> listOf(3)
            2 -> listOf(2, 4)
            3 -> listOf(2, 3, 4)
            4 -> listOf(1, 2, 4, 5)
            5 -> listOf(1, 2, 3, 4, 5)
            6 -> listOf(0, 1, 2, 4, 5, 6)
            else -> (0 until USABLE_COLUMNS).toList()
        }

        /** Per-row item counts (top to bottom) forming a compact formation for [count] items. */
        private fun rowCounts(count: Int): List<Int> {
            if (count <= 0) return emptyList()
            FIXED_SHAPES[count]?.let { return it }
            val capped = count.coerceAtMost(PAGE_CAPACITY)
            val base = capped / USABLE_ROWS
            val remainder = capped % USABLE_ROWS
            return (0 until USABLE_ROWS).map { row -> if (row < remainder) base + 1 else base }
        }

        /**
         * (row, col) grid positions, 0-indexed within a [USABLE_ROWS] x [USABLE_COLUMNS]
         * box, for [count] rewards (capped to one page) — horizontally and vertically centered.
         */
        fun positions(count: Int): List<Pair<Int, Int>> {
            val rows = rowCounts(count)
            val verticalOffset = (USABLE_ROWS - rows.size).coerceAtLeast(0) / 2
            val result = mutableListOf<Pair<Int, Int>>()
            for ((i, rowLen) in rows.withIndex()) {
                for (col in columnIndices(rowLen)) {
                    result.add((verticalOffset + i) to col)
                }
            }
            return result
        }

        fun pageCount(total: Int): Int = if (total <= 0) 1 else (total - 1) / PAGE_CAPACITY + 1
    }

    private fun navButton(label: String): ItemStack {
        val item = ItemStack(Material.ARROW)
        item.editMeta { meta ->
            meta.displayName(Component.text(label, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
        }
        return item
    }

    private fun pageIndicator(current: Int, total: Int): ItemStack {
        val item = ItemStack(Material.PAPER)
        item.editMeta { meta ->
            meta.displayName(Component.text("Page $current / $total", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        }
        return item
    }

    // --- Data classes ---

    data class CrateReward(
        val material: Material,
        val amount: Int,
        val weight: Int,
        val displayName: String,
        val enchantments: Map<Enchantment, Int>,
        /**
         * Optional Base64-encoded full ItemStack snapshot. If present, this is
         * used for the actual reward (preserves trims, custom items, PDC, etc.)
         * The other fields are still used for display purposes.
         */
        val itemBase64: String? = null,
        /**
         * JoshyMC custom enchants (id -> level) for rewards written by hand in crates.yml
         * (`custom-enchantments:`) without a snapshot. Snapshot rewards already carry theirs in
         * the item's PDC, so this stays empty for them (issue #1300).
         */
        val customEnchantments: Map<String, Int> = emptyMap(),
        /**
         * Issue #1514: crates.yml `rarity: <id>`, a presentation-only label from
         * `config.yml > crates.rarities`. Never changes the weight or the draw.
         */
        val rarity: String? = null,
        /**
         * Issue #1514: crates.yml `hidden: true`. The Preview and Spin/Pulse reels show it as a
         * mystery (its real chance is still shown); it is revealed when won or picked.
         */
        val hidden: Boolean = false
    )

    enum class CrateMode { RANDOM, SELECT, SELECT_3 }

    enum class AnimationType { SPIN, PULSE, INSTANT }

    data class CrateDef(
        val id: String,
        val displayName: String,
        val keyMaterial: Material,
        val keyName: String,
        /**
         * Optional custom ItemModel key applied to physical keys for this crate
         * (e.g. a retextured TRIAL_KEY). Purely cosmetic — key identity is always
         * determined by the [crateKeyKey] PDC tag, never by this model.
         */
        val keyItemModel: NamespacedKey? = null,
        val animationGlass: Material,
        val rewards: List<CrateReward>,
        val mode: CrateMode = CrateMode.RANDOM,
        val animationType: AnimationType = AnimationType.SPIN,
        val idleParticle: Particle = Particle.END_ROD,
        val winParticle: Particle = Particle.FIREWORK,
        /**
         * Color applied to colorable particle types (currently only Particle.DUST).
         * Null means "no color configured" — colorable particles fall back to a
         * default color and non-colorable particles are unaffected either way.
         */
        val particleColor: Color? = null,
        /** Issue #1514: crates.yml `listed: false` keeps the crate out of the `/crate` browser. */
        val listed: Boolean = true
    )

    data class CrateLocation(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val crateType: String,
        /** Issue #1472: this location's own facing; null = automatic (the crate type's `rotation`). */
        val facing: CrateFacing.Direction? = null,
    ) {
        fun isAt(block: Block): Boolean = world == block.world.name && x == block.x && y == block.y && z == block.z
    }

    /** In-progress "Select 3" pick state for one player. Lives only as long as the picker/confirm GUI is open. */
    private data class Select3Session(val crateId: String, val selectedIndices: MutableSet<Int> = mutableSetOf())

    /** True for modes where the player manually picks reward(s) instead of an animated random draw — key consumption is deferred until the pick is finalized. */
    private fun isManualPickMode(mode: CrateMode) = mode == CrateMode.SELECT || mode == CrateMode.SELECT_3

    // --- State ---

    val crateKeyKey = NamespacedKey(plugin, "crate_key")
    private val crates = mutableMapOf<String, CrateDef>()
    private val crateLocations = mutableListOf<CrateLocation>()
    private val activeAnimations = mutableSetOf<UUID>()
    private val select3Sessions = mutableMapOf<UUID, Select3Session>()
    /**
     * player -> id of the `crate_pending_rewards` row their running SPIN/PULSE animation will
     * pay out. The animation only pays while its id is still here; every other delivery path
     * (join, retry, shutdown) skips rows that are in flight.
     */
    private val inFlightRewards = mutableMapOf<UUID, String>()
    /**
     * crate id -> config values crates.yml had that loadCrates couldn't read (unknown mode,
     * animation type or reward material). The crate still loads so admins can preview and
     * edit it, but it refuses to open until the value is fixed instead of guessing a fallback.
     */
    private val configProblems = mutableMapOf<String, MutableList<String>>()
    /** crate id -> when its "refused to open" warning was last logged, so clicks don't flood the console. */
    private val lastRefusalLog = mutableMapOf<String, Long>()
    /** Renamed crate id -> current crate id, from crates.yml `crate-aliases`. Old keys resolve through it. */
    private val crateAliases = mutableMapOf<String, String>()
    /**
     * player -> when their last keyed crate open started. A second activation inside
     * [ACTIVATION_DEBOUNCE_MS] (e.g. another plugin force-opening the crate block right after the
     * click) is ignored so one interaction never spends two keys.
     */
    private val lastActivation = mutableMapOf<UUID, Long>()
    private var particleTask: BukkitTask? = null
    private var pendingRetryTask: BukkitTask? = null

    /** Custom crate chest models drawn over the crate blocks (issue #1389). Purely visual. */
    val models = com.liam.joshymc.crate.CrateModelDisplays(plugin, this)

    /**
     * The physical opening animation (issue #1503): cosmetic, player-specific, played before the
     * unchanged reward GUI. It never draws, stores or delivers a reward and never takes a key.
     */
    val opening = com.liam.joshymc.crate.CrateOpeningAnimator(plugin, models)

    /** The player-facing `/crate` browser (issue #1514). Only shows and previews; never opens a crate. */
    val browser = com.liam.joshymc.crate.CrateBrowserMenu(plugin, this)

    /**
     * One crate action per player, click kind and tick, shared by the block and the model hitbox
     * (issue #1451), so one click can never run the crate flow twice.
     */
    private val clickGate = com.liam.joshymc.crate.CrateClickGate()
    private lateinit var cratesFile: File
    private lateinit var cratesConfig: YamlConfiguration

    // --- Lifecycle ---

    fun start() {
        // Create DB table
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS crate_locations (
                world TEXT NOT NULL,
                x INTEGER NOT NULL,
                y INTEGER NOT NULL,
                z INTEGER NOT NULL,
                crate_type TEXT NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )
        """.trimIndent())
        // Issue #1472: per-location facing. NULL (every older row) = automatic, as before.
        val locationColumns = plugin.databaseManager.query("PRAGMA table_info(crate_locations)") { it.getString("name") }.toSet()
        if ("facing" !in locationColumns) plugin.databaseManager.execute("ALTER TABLE crate_locations ADD COLUMN facing TEXT")

        // Save default crates.yml if missing
        cratesFile = plugin.configFile("crates.yml")
        if (!cratesFile.exists()) {
            plugin.saveResource("crates.yml", false)
        }
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile)
        loadRarities()

        // A RANDOM crate's animated reward is written here the moment its key is consumed and
        // removed when it is delivered, so an interrupted animation (quit, reload, shutdown,
        // crash) never eats it. Rewards that didn't fit in the inventory wait here too.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS crate_pending_rewards (
                id TEXT PRIMARY KEY,
                owner_uuid TEXT NOT NULL,
                item TEXT NOT NULL,
                label TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        loadCrates()
        loadLocations()
        startParticleTask()
        // Never lets a model problem stop the crates themselves from starting.
        try {
            models.start()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Custom crate models failed to start; crates keep their vanilla look: ${e.message}")
        }
        // Never lets an animation config problem stop crates: they then open without it.
        try {
            opening.start()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Crate opening animations failed to start; crates open without them: ${e.message}")
        }

        // Retry held rewards for online players (inventory was full, or a reload interrupted them).
        pendingRetryTask?.cancel()
        pendingRetryTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { retryPendingRewards() }, 100L, 600L)

        plugin.logger.info("[Crates] Started with ${crates.size} crate type(s) and ${crateLocations.size} location(s).")
        if (crateLocations.isNotEmpty()) {
            // Print every location so admins can verify what's actually registered.
            for (loc in crateLocations) {
                plugin.logger.info("[Crates]   • ${loc.crateType} @ ${loc.world} ${loc.x}, ${loc.y}, ${loc.z}")
            }
        } else {
            plugin.logger.info("[Crates] No crate locations registered yet — run /crate setlocation <type> while looking at a block.")
        }
    }

    fun stop() {
        // First: removes every temporary animation model and re-shows the real ones. No reveal
        // runs; rewards already drawn are settled below like any interrupted animation.
        try {
            opening.stop()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not stop crate opening animations: ${e.message}")
        }
        try {
            models.stop()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not remove custom crate models: ${e.message}")
        }
        particleTask?.cancel()
        particleTask = null
        pendingRetryTask?.cancel()
        pendingRetryTask = null
        // Settle every animation that's still running instead of dropping it: pay it out now.
        // Anything that can't be delivered stays in crate_pending_rewards for the next login.
        for ((uuid, pendingId) in inFlightRewards.toMap()) {
            inFlightRewards.remove(uuid)
            val player = Bukkit.getPlayer(uuid) ?: continue
            try {
                claimPendingReward(player, pendingId)
            } catch (e: Exception) {
                plugin.logger.warning("[Crates] Could not settle pending reward $pendingId for ${player.name} on shutdown (kept for next login): ${e.message}")
            }
        }
        inFlightRewards.clear()
        activeAnimations.clear()
        select3Sessions.clear()
    }

    // --- Config loading ---

    /** Issue #1514: presentation-only rarity tiers from `config.yml > crates.rarities`. */
    var rarities: com.liam.joshymc.crate.CrateRarity.Settings = com.liam.joshymc.crate.CrateRarity.DEFAULT_SETTINGS
        private set

    private fun loadRarities() {
        val enabled = plugin.config.getBoolean("crates.rarities-enabled", true)
        val section = plugin.config.getConfigurationSection("crates.rarities")
        val raw = section?.getKeys(false)?.map { id ->
            val entry = section.getConfigurationSection(id)
            com.liam.joshymc.crate.CrateRarity.Raw(id, entry?.getString("label"), entry?.getString("color"))
        }
        rarities = com.liam.joshymc.crate.CrateRarity.parse(enabled, raw)
        for (problem in rarities.problems) plugin.logger.warning("[Crates] crates.$problem")
    }

    private fun loadCrates() {
        crates.clear()
        configProblems.clear()
        lastRefusalLog.clear()
        crateAliases.clear()
        val section = cratesConfig.getConfigurationSection("crates") ?: return

        for (id in section.getKeys(false)) {
            val crateSection = section.getConfigurationSection(id) ?: continue
            val problems = mutableListOf<String>()
            val displayName = crateSection.getString("display-name", id) ?: id
            val keyMaterialStr = crateSection.getString("key-material", "TRIPWIRE_HOOK") ?: "TRIPWIRE_HOOK"
            val keyMaterial = try { Material.valueOf(keyMaterialStr) } catch (_: Exception) { Material.TRIPWIRE_HOOK }
            val keyName = crateSection.getString("key-name", "$displayName Key") ?: "$displayName Key"
            val keyItemModel = crateSection.getString("key-item-model")?.takeIf { it.isNotBlank() }?.let { modelStr ->
                val parsed = NamespacedKey.fromString(modelStr)
                if (parsed == null) {
                    plugin.logger.warning("[Crates] Invalid key-item-model '$modelStr' for crate '$id' — falling back to default key appearance.")
                }
                parsed
            }
            val glassStr = crateSection.getString("animation-glass", "WHITE_STAINED_GLASS_PANE") ?: "WHITE_STAINED_GLASS_PANE"
            val animationGlass = try { Material.valueOf(glassStr) } catch (_: Exception) { Material.WHITE_STAINED_GLASS_PANE }

            val modeStr = crateSection.getString("mode", "random") ?: "random"
            val mode = try { CrateMode.valueOf(modeStr.uppercase()) } catch (_: Exception) {
                problems.add("unknown mode '$modeStr'")
                CrateMode.RANDOM
            }

            val animTypeStr = crateSection.getString("animation-type", "spin") ?: "spin"
            val animationType = try { AnimationType.valueOf(animTypeStr.uppercase()) } catch (_: Exception) {
                // Only RANDOM crates animate, so a bad value on a pick crate is harmless.
                if (mode == CrateMode.RANDOM) problems.add("unknown animation-type '$animTypeStr'")
                AnimationType.SPIN
            }

            val idleParticleStr = crateSection.getString("idle-particle", "END_ROD") ?: "END_ROD"
            val idleParticle = try { Particle.valueOf(idleParticleStr.uppercase()) } catch (_: Exception) { Particle.END_ROD }

            val winParticleStr = crateSection.getString("win-particle", "FIREWORK") ?: "FIREWORK"
            val winParticle = try { Particle.valueOf(winParticleStr.uppercase()) } catch (_: Exception) { Particle.FIREWORK }

            val particleColor = MinecraftColors.byId(crateSection.getString("particle-color"))?.color

            val rewards = mutableListOf<CrateReward>()
            val rewardsSection = crateSection.getConfigurationSection("rewards")
            if (rewardsSection != null) {
                for (rewardKey in rewardsSection.getKeys(false)) {
                    val rewardSection = rewardsSection.getConfigurationSection(rewardKey) ?: continue
                    val matStr = rewardSection.getString("material", "STONE") ?: "STONE"
                    val itemBase64 = rewardSection.getString("item-base64")?.takeIf { it.isNotBlank() }
                    val mat = try { Material.valueOf(matStr) } catch (_: Exception) {
                        // A stored item snapshot is what gets granted, so the material only matters without one.
                        if (itemBase64 == null) problems.add("reward '$rewardKey' has unknown material '$matStr'")
                        Material.STONE
                    }
                    if (itemBase64 != null && deserializeItem(itemBase64) == null) {
                        // Never hand out the plain base material in place of the stored item.
                        problems.add("reward '$rewardKey' has an item-base64 snapshot that can't be decoded")
                    }
                    val amount = rewardSection.getInt("amount", 1)
                    val configuredWeight = rewardSection.getInt("weight", 1)
                    // The editor only allows 1-1000; a hand-edited weight below 1 is clamped so
                    // the weighted draw never sees a zero or negative total.
                    val weight = if (configuredWeight < 1) {
                        plugin.logger.warning("[Crates] Crate '$id' reward '$rewardKey' has invalid weight $configuredWeight; using 1 instead.")
                        1
                    } else configuredWeight
                    val rewardDisplayName = rewardSection.getString("display-name", mat.name.lowercase().replace("_", " ")) ?: mat.name
                    val enchantments = mutableMapOf<Enchantment, Int>()

                    val enchSection = rewardSection.getConfigurationSection("enchantments")
                    if (enchSection != null) {
                        for (enchKey in enchSection.getKeys(false)) {
                            val enchantment = Enchantment.getByKey(NamespacedKey.minecraft(enchKey.lowercase()))
                            if (enchantment != null) {
                                enchantments[enchantment] = enchSection.getInt(enchKey)
                            }
                        }
                    }

                    // Resolved when the reward is built: custom enchants register after crates load.
                    val customEnchantments = mutableMapOf<String, Int>()
                    rewardSection.getConfigurationSection("custom-enchantments")?.let { section ->
                        for (enchId in section.getKeys(false)) customEnchantments[enchId.lowercase()] = section.getInt(enchId)
                    }

                    val rarity = rewardSection.getString("rarity")?.trim()?.takeIf { it.isNotEmpty() }
                    if (rarities.unknown(rarity)) {
                        // Presentation only: the crate still opens, the reward just shows no rarity.
                        plugin.logger.warning("[Crates] Crate '$id' reward '$rewardKey' has unknown rarity '$rarity' (not in config.yml crates.rarities); it shows no rarity.")
                    }
                    val hidden = rewardSection.getBoolean("hidden", false)
                    rewards.add(CrateReward(mat, amount, weight, rewardDisplayName, enchantments, itemBase64, customEnchantments, rarity, hidden))
                }
            }

            if (problems.isNotEmpty()) {
                configProblems[id] = problems
                plugin.logger.warning("[Crates] Crate '$id' is misconfigured and won't open until crates.yml is fixed: ${problems.joinToString("; ")}")
            }

            crates[id] = CrateDef(
                id, displayName, keyMaterial, keyName, keyItemModel, animationGlass, rewards,
                mode, animationType, idleParticle, winParticle, particleColor,
                listed = crateSection.getBoolean("listed", true)
            )
        }
        loadAliases()
    }

    /**
     * Reads `crate-aliases` (old crate id -> current crate id) from crates.yml so keys minted
     * before a crate was renamed keep opening it. Only explicit entries are used; nothing is
     * guessed, and an alias never shadows a crate that still exists under that id.
     */
    private fun loadAliases() {
        crateAliases.clear()
        val section = cratesConfig.getConfigurationSection("crate-aliases") ?: return
        for (oldId in section.getKeys(false)) {
            val target = section.getString(oldId)?.trim()?.takeIf { it.isNotEmpty() }
            when {
                target == null ->
                    plugin.logger.warning("[Crates] crate-aliases.$oldId has no target crate id; ignored.")
                crates.containsKey(oldId) ->
                    plugin.logger.warning("[Crates] crate-aliases.$oldId ignored: a crate named '$oldId' still exists.")
                !crates.containsKey(target) ->
                    plugin.logger.warning("[Crates] crate-aliases.$oldId points at unknown crate '$target'; ignored.")
                else -> crateAliases[oldId] = target
            }
        }
        if (crateAliases.isNotEmpty()) {
            plugin.logger.info("[Crates] Crate id aliases: ${crateAliases.entries.joinToString { "${it.key} -> ${it.value}" }}")
        }
    }

    /** The current crate id for [id]: itself if it's a crate, its configured alias target otherwise. */
    fun resolveCrateId(id: String): String = if (crates.containsKey(id)) id else crateAliases[id] ?: id

    private fun loadLocations() {
        crateLocations.clear()
        val rows = plugin.databaseManager.query(
            "SELECT world, x, y, z, crate_type, facing FROM crate_locations"
        ) { rs ->
            val world = rs.getString("world")
            val x = rs.getInt("x")
            val y = rs.getInt("y")
            val z = rs.getInt("z")
            // A facing this build can't read is treated as automatic and left in the row untouched.
            val facing = when (val stored = CrateFacing.parseStored(rs.getString("facing"))) {
                is CrateFacing.Stored.Face -> stored.direction
                is CrateFacing.Stored.Invalid -> {
                    plugin.logger.warning("[Crates] Crate location $world $x, $y, $z has an unknown facing '${stored.raw}'; using automatic facing. Fix it with /crate rotate.")
                    null
                }
                CrateFacing.Stored.Auto -> null
            }
            CrateLocation(world, x, y, z, rs.getString("crate_type"), facing)
        }
        crateLocations.addAll(rows)
    }

    // --- Public API ---

    fun getCrateTypes(): List<String> = crates.keys.toList()

    fun getCrate(id: String): CrateDef? = crates[id]

    fun getAllCrates(): Map<String, CrateDef> = crates.toMap()

    // --- Editor API ---

    fun createCrate(id: String, displayName: String): Boolean {
        if (crates.containsKey(id)) return false
        crates[id] = CrateDef(
            id, displayName, Material.TRIPWIRE_HOOK, "$displayName Key", null, Material.WHITE_STAINED_GLASS_PANE, emptyList(),
            CrateMode.RANDOM, AnimationType.SPIN, Particle.END_ROD, Particle.FIREWORK
        )
        saveCrates()
        return true
    }

    fun deleteCrate(id: String): Boolean {
        if (!crates.containsKey(id)) return false
        crates.remove(id)
        // Remove all locations for this crate type
        val removed = crateLocations.filter { it.crateType == id }
        crateLocations.removeAll(removed.toSet())
        for (loc in removed) {
            plugin.databaseManager.execute(
                "DELETE FROM crate_locations WHERE world = ? AND x = ? AND y = ? AND z = ?",
                loc.world, loc.x, loc.y, loc.z
            )
            Bukkit.getWorld(loc.world)?.let { refreshModel(it.getBlockAt(loc.x, loc.y, loc.z)) }
        }
        saveCrates()
        return true
    }

    fun addReward(crateId: String, reward: CrateReward): Boolean {
        val crate = crates[crateId] ?: return false
        val newRewards = crate.rewards + reward
        crates[crateId] = crate.copy(rewards = newRewards)
        saveCrates()
        return true
    }

    fun removeReward(crateId: String, index: Int): Boolean {
        val crate = crates[crateId] ?: return false
        if (index < 0 || index >= crate.rewards.size) return false
        val newRewards = crate.rewards.toMutableList().apply { removeAt(index) }
        crates[crateId] = crate.copy(rewards = newRewards)
        saveCrates()
        return true
    }

    fun updateRewardWeight(crateId: String, index: Int, newWeight: Int): Boolean {
        val crate = crates[crateId] ?: return false
        if (index < 0 || index >= crate.rewards.size) return false
        val newRewards = crate.rewards.toMutableList()
        newRewards[index] = newRewards[index].copy(weight = newWeight)
        crates[crateId] = crate.copy(rewards = newRewards)
        saveCrates()
        return true
    }

    fun setCrateDisplayName(crateId: String, newName: String): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(displayName = newName)
        saveCrates()
        return true
    }

    fun setCrateKeyMaterial(crateId: String, material: Material, keyName: String, keyItemModel: NamespacedKey? = null): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(keyMaterial = material, keyName = keyName, keyItemModel = keyItemModel)
        saveCrates()
        return true
    }

    /** Returns false (and leaves the mode unchanged) if switching to SELECT_3 without at least 3 rewards configured. */
    fun setCrateMode(crateId: String, mode: CrateMode): Boolean {
        val crate = crates[crateId] ?: return false
        if (mode == CrateMode.SELECT_3 && crate.rewards.size < 3) return false
        crates[crateId] = crate.copy(mode = mode)
        saveCrates()
        return true
    }

    fun setCrateAnimationType(crateId: String, animationType: AnimationType): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(animationType = animationType)
        saveCrates()
        return true
    }

    fun setCrateAnimationGlass(crateId: String, material: Material): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(animationGlass = material)
        saveCrates()
        return true
    }

    fun setCrateIdleParticle(crateId: String, particle: Particle): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(idleParticle = particle)
        saveCrates()
        return true
    }

    fun setCrateWinParticle(crateId: String, particle: Particle): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(winParticle = particle)
        saveCrates()
        return true
    }

    fun setCrateParticleColor(crateId: String, colorId: String): Boolean {
        val crate = crates[crateId] ?: return false
        val entry = MinecraftColors.byId(colorId) ?: return false
        crates[crateId] = crate.copy(particleColor = entry.color)
        saveCrates()
        return true
    }

    private fun saveCrates() {
        cratesConfig.set("crates", null)
        for ((id, crate) in crates) {
            val path = "crates.$id"
            cratesConfig.set("$path.display-name", crate.displayName)
            cratesConfig.set("$path.key-material", crate.keyMaterial.name)
            cratesConfig.set("$path.key-name", crate.keyName)
            cratesConfig.set("$path.key-item-model", crate.keyItemModel?.asString())
            cratesConfig.set("$path.animation-glass", crate.animationGlass.name)
            cratesConfig.set("$path.mode", crate.mode.name.lowercase())
            cratesConfig.set("$path.animation-type", crate.animationType.name.lowercase())
            cratesConfig.set("$path.idle-particle", crate.idleParticle.name)
            cratesConfig.set("$path.win-particle", crate.winParticle.name)
            val colorId = MinecraftColors.ALL.firstOrNull { it.color == crate.particleColor }?.id
            cratesConfig.set("$path.particle-color", colorId)
            cratesConfig.set("$path.listed", if (crate.listed) null else false)

            for ((idx, reward) in crate.rewards.withIndex()) {
                val rewardPath = "$path.rewards.reward_$idx"
                cratesConfig.set("$rewardPath.material", reward.material.name)
                cratesConfig.set("$rewardPath.amount", reward.amount)
                cratesConfig.set("$rewardPath.weight", reward.weight)
                cratesConfig.set("$rewardPath.display-name", reward.displayName)
                if (reward.enchantments.isNotEmpty()) {
                    for ((ench, level) in reward.enchantments) {
                        cratesConfig.set("$rewardPath.enchantments.${ench.key.key}", level)
                    }
                }
                for ((enchId, level) in reward.customEnchantments) {
                    cratesConfig.set("$rewardPath.custom-enchantments.$enchId", level)
                }
                if (reward.itemBase64 != null) {
                    cratesConfig.set("$rewardPath.item-base64", reward.itemBase64)
                }
                if (reward.rarity != null) cratesConfig.set("$rewardPath.rarity", reward.rarity)
                if (reward.hidden) cratesConfig.set("$rewardPath.hidden", true)
            }
        }
        cratesConfig.save(cratesFile)
        // The file now holds the values every crate actually loaded with (the editor rewrote the
        // unreadable ones), so the load-time problems no longer describe it.
        if (configProblems.isNotEmpty()) {
            plugin.logger.warning("[Crates] Saving crates.yml replaced unreadable values in ${configProblems.keys.joinToString()} with their defaults (mode random, animation-type spin, material STONE); check those crates in the editor.")
            configProblems.clear()
        }
    }

    fun setCrateLocation(block: Block, crateType: String): Boolean {
        if (!crates.containsKey(crateType)) return false

        // Re-registering a block keeps the facing it was given (issue #1472).
        val keptFacing = getLocationAt(block)?.facing

        // Remove existing location at this block if any
        removeCrateLocation(block)

        val loc = CrateLocation(block.world.name, block.x, block.y, block.z, crateType, keptFacing)
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO crate_locations (world, x, y, z, crate_type, facing) VALUES (?, ?, ?, ?, ?, ?)",
            loc.world, loc.x, loc.y, loc.z, loc.crateType, CrateFacing.storedValue(keptFacing)
        )
        crateLocations.add(loc)
        refreshModel(block)
        return true
    }

    /**
     * Re-reads `crates.custom-models` and redraws every crate model (issue #1460, after
     * `/crate models set`). Crates, keys, rewards and locations are untouched.
     */
    fun restartModels() {
        try {
            models.stop()
            models.start()
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Custom crate models failed to restart; crates keep their vanilla look: ${e.message}")
        }
    }

    /** Redraws (or clears) the custom model at [block]; a model failure never affects the crate. */
    private fun refreshModel(block: Block) {
        try {
            models.refresh(block.world, block.x, block.y, block.z)
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not refresh the custom model at ${block.world.name} ${block.x}, ${block.y}, ${block.z}: ${e.message}")
        }
    }

    fun removeCrateLocation(block: Block): Boolean {
        val removed = crateLocations.removeAll {
            it.world == block.world.name && it.x == block.x && it.y == block.y && it.z == block.z
        }
        if (removed) {
            plugin.databaseManager.execute(
                "DELETE FROM crate_locations WHERE world = ? AND x = ? AND y = ? AND z = ?",
                block.world.name, block.x, block.y, block.z
            )
            refreshModel(block)
        }
        return removed
    }

    fun getCrateTypeAt(block: Block): String? {
        // Exact match first — this is the common case.
        val exact = crateLocations.firstOrNull {
            it.world == block.world.name && it.x == block.x && it.y == block.y && it.z == block.z
        }?.crateType
        if (exact != null) return resolveCrateId(exact)

        // Fuzzy fallback: the 6 face-neighbours. Catches double chests where
        // only one half was registered, and 1-block off-by-one mistakes from
        // registering the wrong target block. Diagonals are intentionally
        // excluded so two adjacent crates can't bleed into each other.
        val world = block.world.name
        val offsets = listOf(
            Triple(1, 0, 0), Triple(-1, 0, 0),
            Triple(0, 1, 0), Triple(0, -1, 0),
            Triple(0, 0, 1), Triple(0, 0, -1),
        )
        for ((dx, dy, dz) in offsets) {
            val match = crateLocations.firstOrNull {
                it.world == world &&
                    it.x == block.x + dx &&
                    it.y == block.y + dy &&
                    it.z == block.z + dz
            }
            if (match != null) return resolveCrateId(match.crateType)
        }
        return null
    }

    /** The crate location registered at exactly [block] (no neighbour fallback), or null. */
    fun getLocationAt(block: Block): CrateLocation? = crateLocations.firstOrNull { it.isAt(block) }

    /** Outcome of [setCrateFacing]. */
    data class FacingChange(
        val location: CrateLocation,
        val previous: CrateFacing.Direction?,
        val block: CrateFacing.BlockAlign,
    )

    /** The direction the crate at [loc] currently shows (its facing, the configured rotation, or the block's). */
    fun currentFacing(loc: CrateLocation, block: Block): CrateFacing.Direction {
        val configured = models.settings.models[resolveCrateId(loc.crateType).lowercase()]?.rotation
        val blockFacing = (block.blockData as? org.bukkit.block.data.Directional)?.facing?.name
        return CrateFacing.currentDirection(loc.facing, configured, blockFacing)
    }

    /**
     * Issue #1472: gives the crate registered at exactly [block] its own [facing] (null =
     * automatic again), saves it on its `crate_locations` row, turns the vanilla block to match
     * when that is safe (`crates.rotation.rotate-block`), and redraws only this location's model.
     * Returns null when [block] is not a registered crate location. Crates, keys, rewards and
     * every other location are untouched.
     */
    fun setCrateFacing(block: Block, facing: CrateFacing.Direction?): FacingChange? {
        val index = crateLocations.indexOfFirst { it.isAt(block) }
        if (index < 0) return null
        val old = crateLocations[index]
        plugin.databaseManager.execute(
            "UPDATE crate_locations SET facing = ? WHERE world = ? AND x = ? AND y = ? AND z = ?",
            CrateFacing.storedValue(facing), old.world, old.x, old.y, old.z
        )
        val updated = old.copy(facing = facing)
        crateLocations[index] = updated
        val align = try {
            alignBlock(block, facing)
        } catch (e: Exception) {
            plugin.logger.warning("[Crates] Could not turn the crate block at ${old.world} ${old.x}, ${old.y}, ${old.z}: ${e.message}")
            CrateFacing.BlockAlign.NO_FACING
        }
        refreshModel(block)
        return FacingChange(updated, old.facing, align)
    }

    /**
     * Turns the crate block itself to [facing] when that can't hurt anything: only a block that
     * supports that facing, never half of a double chest (turning one half splits the pair),
     * the same block type (so its block entity and contents stay), and without physics so
     * neighbouring blocks aren't updated.
     */
    private fun alignBlock(block: Block, facing: CrateFacing.Direction?): CrateFacing.BlockAlign {
        val data = block.blockData as? org.bukkit.block.data.Directional
        val decision = CrateFacing.blockAlign(
            target = facing,
            enabled = plugin.config.getBoolean("crates.rotation.rotate-block", true),
            faces = data?.faces?.map { it.name }?.toSet() ?: emptySet(),
            blockFacing = data?.facing?.name,
            chestType = (data as? org.bukkit.block.data.type.Chest)?.type?.name,
        )
        if (decision != CrateFacing.BlockAlign.ROTATED || data == null || facing == null) return decision
        val turned = data.clone() as org.bukkit.block.data.Directional
        turned.facing = org.bukkit.block.BlockFace.valueOf(facing.name)
        block.setBlockData(turned, false)
        return decision
    }

    /** Admins who turned on quick rotation with `/crate rotate mode` (memory only, cleared on quit). */
    private val rotateMode = mutableSetOf<UUID>()

    fun isRotateMode(player: Player): Boolean = player.uniqueId in rotateMode

    fun setRotateMode(player: Player, on: Boolean) {
        if (on) rotateMode += player.uniqueId else rotateMode -= player.uniqueId
    }

    /** `crates.rotation.quick-rotate`: lets admins turn quick rotation off server-wide. */
    fun quickRotateEnabled(): Boolean = plugin.config.getBoolean("crates.rotation.quick-rotate", true)

    /**
     * Sneak + right-click with an empty hand while in rotate mode (issue #1472): turns the crate
     * one step (NORTH → EAST → SOUTH → WEST) instead of the preview. Returns false (normal crate
     * flow) for everyone and everything else, so keys, previews and mass opening are unchanged.
     */
    private fun tryQuickRotate(player: Player, block: Block): Boolean {
        if (player.uniqueId !in rotateMode) return false
        val admin = player.hasPermission("joshymc.crate.admin")
        if (!admin) rotateMode -= player.uniqueId
        val quick = CrateFacing.isQuickRotate(
            featureEnabled = quickRotateEnabled(),
            isAdmin = admin,
            inRotateMode = player.uniqueId in rotateMode,
            sneaking = player.isSneaking,
            rightClick = true,
            emptyHand = player.inventory.itemInMainHand.type.isAir,
        )
        if (!quick) return false
        val loc = getLocationAt(block)
        if (loc == null) {
            plugin.commsManager.send(player, Component.text("Only the registered crate block can be rotated; this is its neighbour.", NamedTextColor.RED))
            return true
        }
        val next = currentFacing(loc, block).next()
        val change = setCrateFacing(block, next) ?: return true
        plugin.commsManager.sendActionBar(player, Component.text("Crate now faces ${next.id}", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.4f)
        plugin.logger.info("[Crates] ${player.name} turned the ${loc.crateType} crate at ${loc.world} ${loc.x}, ${loc.y}, ${loc.z} to face ${next.name} (was ${change.previous?.name ?: "auto"}; block ${change.block.name.lowercase()}).")
        return true
    }

    /** Snapshot of every registered crate location, for `/crate locations`. */
    fun getAllLocations(): List<CrateLocation> = crateLocations.toList()

    /**
     * True for blocks that admins typically register as crates — chests,
     * ender chests, barrels, shulkers, and similar. Used to show a friendly
     * "not registered" message when a player right-clicks one with a key.
     */
    private fun isContainerLike(type: Material): Boolean {
        val name = type.name
        return type == Material.CHEST
                || type == Material.TRAPPED_CHEST
                || type == Material.ENDER_CHEST
                || type == Material.BARREL
                || name.endsWith("_SHULKER_BOX")
                || type == Material.SHULKER_BOX
                || type == Material.BEACON
                || type == Material.RESPAWN_ANCHOR
    }

    /**
     * Build an [ItemStack] for the given crate type's key without giving it.
     * Returns null if the crate type isn't registered.
     */
    fun createKeyStack(crateType: String, amount: Int = 1): ItemStack? {
        val crate = crates[crateType] ?: return null

        val key = ItemStack(crate.keyMaterial, amount.coerceAtLeast(1))
        key.editMeta { meta ->
            meta.displayName(
                Component.text(crate.keyName, TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )

            val lore = listOf(
                Component.empty(),
                Component.text("  Right-click a ", NamedTextColor.GRAY)
                    .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
                    .append(Component.text(" crate to open.", NamedTextColor.GRAY))
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            )
            meta.lore(lore)

            meta.persistentDataContainer.set(crateKeyKey, PersistentDataType.STRING, crateType)
            meta.setEnchantmentGlintOverride(true)

            // Cosmetic only — key identity always comes from the crateKeyKey PDC tag above.
            if (crate.keyItemModel != null) {
                meta.setItemModel(crate.keyItemModel)
            }
        }
        return key
    }

    fun giveKey(player: Player, crateType: String, amount: Int = 1): Boolean {
        val key = createKeyStack(crateType, amount) ?: return false
        plugin.giveItemSafely(player, key)
        return true
    }

    fun isKey(item: ItemStack?, crateType: String? = null): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        val storedType = meta.persistentDataContainer.get(crateKeyKey, PersistentDataType.STRING) ?: return false
        return crateType == null || resolveCrateId(storedType) == crateType
    }

    /**
     * Returns the crate type the key item opens, or null if it's not a key. A key tagged with a
     * renamed crate id resolves through `crate-aliases`; the item's own tag is left as it is.
     */
    fun getKeyType(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(crateKeyKey, PersistentDataType.STRING)?.let { resolveCrateId(it) }
    }

    private fun consumeOneKey(player: Player) {
        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.amount > 1) {
            itemInHand.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }
    }

    /**
     * True if every stack in [items] fits in the player's storage once the one key in
     * their main hand is consumed. Simulates on a copy with the same addItem rules the
     * real delivery uses, so partial stacks, max stack sizes and metadata differences
     * all count — not just empty slots.
     */
    private fun select3RewardsFit(player: Player, items: List<ItemStack>): Boolean {
        return storageAfterOneKey(player).addItem(*items.map { it.clone() }.toTypedArray()).isEmpty()
    }

    /** A scratch copy of the player's storage with one key taken from the main hand. */
    private fun storageAfterOneKey(player: Player): Inventory {
        val contents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        val heldSlot = player.inventory.heldItemSlot
        val held = contents.getOrNull(heldSlot)
        if (held != null) {
            if (held.amount > 1) held.amount -= 1 else contents[heldSlot] = null
        }
        val temp = Bukkit.createInventory(null, 36)
        temp.contents = contents
        return temp
    }

    /**
     * True if whatever the next mass-open key rolls is guaranteed to fit once that key is
     * taken. Checking every possible reward (not just the one rolled) means a nearly full
     * inventory can't be used to keep only the rolls that fit and reroll the rest for free.
     * A single reward is simulated with real addItem stacking; a multi-reward open (Select 3)
     * needs empty slots for its [perOpen] bulkiest rewards, which never undercounts.
     */
    private fun massOpenNextFits(player: Player, candidates: List<ItemStack>, perOpen: Int): Boolean {
        if (perOpen <= 1) {
            return candidates.all { storageAfterOneKey(player).addItem(it.clone()).isEmpty() }
        }
        val emptySlots = storageAfterOneKey(player).storageContents.count { it == null || it.type == Material.AIR }
        val needed = candidates
            .map { (it.amount + it.maxStackSize - 1) / it.maxStackSize.coerceAtLeast(1) }
            .sortedDescending()
            .take(perOpen)
            .sum()
        return needed <= emptySlots
    }

    /**
     * Delivers a mass-open reward without ever dropping it: anything that unexpectedly doesn't
     * fit is held in crate_pending_rewards and retried, like an interrupted animation reward.
     */
    private fun deliverMassOpenReward(player: Player, item: ItemStack, label: String) {
        val leftover = plugin.depositItemSafely(player, item) ?: return
        if (leftover.amount <= 0) return
        plugin.databaseManager.execute(
            "INSERT INTO crate_pending_rewards (id, owner_uuid, item, label, created_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), player.uniqueId.toString(), serializeItem(leftover), label, System.currentTimeMillis()
        )
        plugin.commsManager.send(
            player,
            Component.text("Your crate reward ", NamedTextColor.YELLOW)
                .append(Component.text(label, TextColor.color(0xFFAA00)))
                .append(Component.text(" is safely held and will be delivered once you have space.", NamedTextColor.YELLOW))
        )
    }

    /**
     * Why [crate] can't be opened, or null when it's ready. The one set of validity rules every
     * open path (click, mass-open, /crate force) checks before a key is touched. [randomDraw] is
     * true when the open picks rewards by weight (RANDOM crates, and any mass-open).
     */
    private fun crateProblem(crate: CrateDef, randomDraw: Boolean): String? {
        configProblems[crate.id]?.let { return it.joinToString("; ") }
        if (crate.rewards.isEmpty()) return "no rewards configured"
        crate.rewards.withIndex().firstOrNull { it.value.amount < 1 }?.let {
            return "reward #${it.index} (${it.value.displayName}) has amount ${it.value.amount}"
        }
        crate.rewards.withIndex().firstOrNull { it.value.itemBase64 != null && deserializeItem(it.value.itemBase64) == null }?.let {
            return "reward #${it.index} (${it.value.displayName}) has an item-base64 snapshot that can't be decoded"
        }
        for ((index, reward) in crate.rewards.withIndex()) {
            rewardEnchantProblem(reward)?.let { return "reward #$index (${reward.displayName}) $it" }
        }
        if (crate.mode == CrateMode.SELECT_3 && crate.rewards.size < 3) {
            return "Select 3 mode needs at least 3 rewards (has ${crate.rewards.size})"
        }
        if (randomDraw) {
            crate.rewards.withIndex().firstOrNull { it.value.weight < 0 }?.let {
                return "reward #${it.index} (${it.value.displayName}) has negative weight ${it.value.weight}"
            }
            if (crate.rewards.sumOf { it.weight } <= 0) return "reward weights add up to 0"
        }
        return null
    }

    /**
     * Why [crate] can't be opened where [player] stands because of the gamemode locks, or null.
     * A reward that's a gamemode-scoped custom item (e.g. the Survival-only Viking set) never
     * goes out where its gamemode is locked away, the same rule `/joshymc give` follows, so a
     * crate can't carry items across gamemodes (issues #1294, #1300).
     */
    private fun gamemodeProblem(player: Player, crate: CrateDef): String? {
        for ((index, reward) in crate.rewards.withIndex()) {
            val item = rewardTemplate(reward) ?: continue
            val scope = plugin.itemManager.getCustomItemId(item)?.let { plugin.itemManager.getItem(it) }?.gamemodeScope ?: continue
            if (!plugin.gamemodeLockManager.isAllowed(scope, player.world)) {
                return "reward #$index (${reward.displayName}) is only available on ${scope.gamemode?.displayName ?: scope.id}"
            }
        }
        return null
    }

    /**
     * Returns the crate if it's valid and ready to open; otherwise tells the player (their key
     * is untouched), logs the reason for staff, and returns null. Call before consuming a key.
     */
    private fun openableCrate(player: Player, crateType: String, massOpen: Boolean): CrateDef? {
        val crate = crates[crateType]
        val problem = if (crate == null) {
            "crate type '$crateType' is not defined in crates.yml"
        } else {
            crateProblem(crate, randomDraw = massOpen || crate.mode == CrateMode.RANDOM)
                ?: gamemodeProblem(player, crate)
        } ?: return crate

        plugin.commsManager.send(
            player,
            Component.text(
                if (crate == null) "This key can't be used right now — its crate no longer exists. Your key was not used."
                else "This crate is unavailable right now. Your key was not used.",
                NamedTextColor.RED
            )
        )
        if (player.hasPermission("joshymc.crate.admin")) {
            plugin.commsManager.send(player, Component.text("Admin: crate '$crateType' — $problem", NamedTextColor.GRAY))
        }
        player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)

        val now = System.currentTimeMillis()
        if (now - (lastRefusalLog[crateType] ?: 0L) >= 60_000L) {
            lastRefusalLog[crateType] = now
            plugin.logger.warning("[Crates] Refused to open crate '$crateType' for ${player.name} (no key used): $problem")
        }
        return null
    }

    /** Opens a crate without a key (/crate force). Keyed opens go through [handleCrateClick]. */
    fun openCrate(player: Player, crateType: String, block: Block) {
        if (activeAnimations.contains(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You already have a crate animation running.", NamedTextColor.RED))
            return
        }
        val crate = openableCrate(player, crateType, massOpen = false) ?: return
        beginOpening(player, crate, block)
    }

    /**
     * Starts the opening flow for a crate that already passed [openableCrate] (and had its key
     * taken, for RANDOM). With a crate [block] and animations on for this crate, the physical
     * opening animation (issue #1503) plays first and the same flow continues at its reveal.
     */
    private fun beginOpening(player: Player, crate: CrateDef, block: Block? = null) {
        if (block != null && opening.enabledFor(crate.id) && beginAnimatedOpening(player, crate, block)) return
        // SELECT mode: open a pick GUI instead of animating
        if (crate.mode == CrateMode.SELECT) {
            openSelectGui(player, crate)
            return
        }

        // SELECT_3 mode: open the multi-pick GUI instead of animating
        if (crate.mode == CrateMode.SELECT_3) {
            select3Sessions[player.uniqueId] = Select3Session(crate.id)
            openSelect3Gui(player, crate)
            return
        }

        activeAnimations.add(player.uniqueId)

        when (crate.animationType) {
            AnimationType.INSTANT -> openInstant(player, crate)
            AnimationType.PULSE -> openPulse(player, crate)
            AnimationType.SPIN -> openSpin(player, crate)
        }
    }

    /**
     * Issue #1503: plays the physical opening animation, then continues with the crate's normal
     * flow. Returns false when the animation didn't start (the caller then opens as before).
     *
     * - Select / Select 3: nothing is drawn or consumed yet (the key is still only taken when
     *   the pick is confirmed); the pick GUI opens at the reveal. Quitting or a reload during
     *   the animation just ends it.
     * - Random (Spin / Pulse / Instant): the key is already gone, so the winner is drawn with
     *   the same single weighted draw and written to `crate_pending_rewards` **before** the
     *   animation starts, and that stored reward is what the GUI then reveals and pays. If the
     *   animation is interrupted by a quit or a reload/shutdown, the existing recovery settles
     *   that row (join / [stop]); any other interruption only skips ahead to the GUI.
     */
    private fun beginAnimatedOpening(player: Player, crate: CrateDef, block: Block): Boolean {
        val uuid = player.uniqueId
        if (isManualPickMode(crate.mode)) {
            activeAnimations.add(uuid)
            val started = opening.play(player, block, crate.id) {
                activeAnimations.remove(uuid)
                if (player.isOnline) openManualPick(player, crate)
            }
            if (!started) activeAnimations.remove(uuid)
            return started
        }
        activeAnimations.add(uuid)
        val winner = selectWeightedReward(crate)
        val pendingId = registerPendingReward(player, winner)
        val started = opening.play(player, block, crate.id) { revealRandom(player, crate, winner, pendingId) }
        // Couldn't animate: the same stored winner goes straight into the normal GUI.
        if (!started) revealRandom(player, crate, winner, pendingId)
        return true
    }

    private fun openManualPick(player: Player, crate: CrateDef) {
        if (crate.mode == CrateMode.SELECT) {
            openSelectGui(player, crate)
        } else {
            select3Sessions[player.uniqueId] = Select3Session(crate.id)
            openSelect3Gui(player, crate)
        }
    }

    /** The reward GUI for an animated random open, paying exactly the reward stored as [pendingId]. */
    private fun revealRandom(player: Player, crate: CrateDef, winner: CrateReward, pendingId: String) {
        if (!player.isOnline || inFlightRewards[player.uniqueId] != pendingId) {
            // Already settled elsewhere (quit, shutdown): nothing to reveal or pay here.
            activeAnimations.remove(player.uniqueId)
            return
        }
        when (crate.animationType) {
            AnimationType.INSTANT -> {
                if (finishInFlight(player, pendingId, winMessage(crate, listOf(winner)))) {
                    spawnWinParticles(player, crate)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                }
                activeAnimations.remove(player.uniqueId)
            }
            AnimationType.PULSE -> openPulse(player, crate, winner, pendingId)
            AnimationType.SPIN -> openSpin(player, crate, winner, pendingId)
        }
    }

    // --- SELECT mode GUI ---

    private fun openSelectGui(player: Player, crate: CrateDef, page: Int = 0) {
        val title = Component.text("Pick a Reward: ")
            .append(Component.text(crate.displayName, crateNameColor))
            .decoration(TextDecoration.ITALIC, false)

        val totalPages = CrateLayout.pageCount(crate.rewards.size)
        val safePage = page.coerceIn(0, totalPages - 1)
        val pageStart = safePage * CrateLayout.PAGE_CAPACITY
        val pageRewards = crate.rewards.drop(pageStart).take(CrateLayout.PAGE_CAPACITY)

        val size = 45
        val gui = CustomGui(title, size)
        pickFrame(gui, crate)
        gui.setItem(4, pickHeader(crate, "Pick 1 Reward", listOf(
            "Choose the reward you want.",
            "You confirm it on the next screen;",
            "your key is only used then."
        )))

        val positions = CrateLayout.positions(pageRewards.size)
        for ((idx, reward) in pageRewards.withIndex()) {
            if (idx >= positions.size) break
            val (row, col) = positions[idx]
            val slot = (row + 1) * 9 + (col + 1)
            val globalIndex = pageStart + idx
            val item = rewardDisplay(
                crate, reward, CrateRewardText.Context.PICK, player,
                action = listOf(plain("  Click to choose this reward", NamedTextColor.GREEN))
            )
            gui.setItem(slot, item) { p, _ ->
                // The GUI is only a view: the crate must still be the one it was built from.
                if (pickStale(p, crate)) return@setItem
                GuiStyle.clickSound(p)
                openSelectConfirm(p, crate, globalIndex, safePage)
            }
        }

        addPickNavigation(gui, safePage, totalPages) { p, target -> if (!pickStale(p, crate)) openSelectGui(p, crate, target) }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /**
     * Issue #1514: Select mode's confirmation step, so one stray click never spends a key. The
     * chosen reward is highlighted; Confirm re-checks the crate, the key in hand and the space
     * on the server, then takes exactly one key and pays exactly once ([CrateSelection.OneShot]).
     */
    private fun openSelectConfirm(player: Player, crate: CrateDef, rewardIndex: Int, page: Int) {
        val reward = crate.rewards.getOrNull(rewardIndex)
        if (reward == null) {
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("This crate's rewards changed — please reopen and try again.", NamedTextColor.RED))
            return
        }
        val title = Component.text("Confirm Reward: ")
            .append(Component.text(crate.displayName, crateNameColor))
            .decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, 27)
        pickFrame(gui, crate)
        highlightFrame(gui)
        gui.setItem(4, pickHeader(crate, "Your Choice", listOf("Confirm to use 1 key", "and claim this reward.")))
        gui.setItem(13, rewardDisplay(
            crate, reward, CrateRewardText.Context.PICK, player,
            action = listOf(plain("  ✔ Selected", NamedTextColor.GREEN)),
            highlight = true
        ))

        val claim = CrateSelection.OneShot()
        gui.setItem(20, confirmButton("Use 1 key and claim this reward.")) { p, _ ->
            if (!claim.take()) return@setItem
            if (pickStale(p, crate)) return@setItem
            val keyInHand = p.inventory.itemInMainHand
            if (!isKey(keyInHand, crate.id)) {
                p.closeInventory()
                sendKeyMissing(p, crate)
                return@setItem
            }
            // Re-check inventory space — the player may have filled it while the GUI was open.
            // Main-hand slot frees up only when the key stack is size 1.
            val hasFreeSlot = p.inventory.firstEmpty() != -1 || keyInHand.amount == 1
            if (!hasFreeSlot) {
                p.closeInventory()
                plugin.commsManager.send(p, Component.text("Your inventory is full — clear some space and try again. No key was used.", NamedTextColor.RED))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }
            val rewardItem = buildRewardItem(reward)
            consumeOneKey(p)
            plugin.giveItemSafely(p, rewardItem)
            spawnWinParticles(p, crate)
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            plugin.commsManager.send(p, winMessage(crate, listOf(reward), "You selected "))
            openPickResult(p, crate, listOf(reward))
        }
        gui.setItem(22, GuiStyle.back("Choose Another")) { p, _ ->
            if (claim.used || pickStale(p, crate)) return@setItem
            GuiStyle.pageSound(p)
            openSelectGui(p, crate, page)
        }
        gui.setItem(24, cancelButton()) { p, _ ->
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Selection cancelled — no key was used.", NamedTextColor.YELLOW))
        }

        plugin.guiManager.open(player, gui)
    }

    // --- Shared pick-GUI pieces (issue #1514) ---

    /** Crate-coloured background for the pick and confirmation screens. */
    private fun pickFrame(gui: CustomGui, crate: CrateDef) {
        val filler = pane(crate.animationGlass)
        for (i in 0 until gui.size) gui.inventory.setItem(i, filler.clone())
    }

    /** Lime panes around the center slot (13) of a 27-slot confirmation / result screen. */
    private fun highlightFrame(gui: CustomGui) {
        val lime = pane(Material.LIME_STAINED_GLASS_PANE)
        for (slot in listOf(3, 5, 12, 14, 21, 23)) gui.inventory.setItem(slot, lime.clone())
    }

    private fun pickHeader(crate: CrateDef, heading: String, lines: List<String>): ItemStack {
        val item = ItemStack(Material.NAME_TAG)
        item.editMeta { meta ->
            meta.displayName(Component.text(heading, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true))
            val lore = mutableListOf<Component>(Component.empty())
            lore.add(plain("  ", NamedTextColor.GRAY).append(Component.text(crate.displayName, crateNameColor)))
            for (line in lines) lore.add(plain("  $line", NamedTextColor.GRAY))
            lore.add(Component.empty())
            meta.lore(lore)
        }
        return item
    }

    private fun confirmButton(detail: String): ItemStack = GuiStyle.item(
        Material.EMERALD_BLOCK,
        Component.text("Confirm", NamedTextColor.GREEN).decoration(TextDecoration.BOLD, true),
        listOf(Component.empty(), plain("  $detail", NamedTextColor.GRAY), Component.empty())
    )

    private fun cancelButton(): ItemStack = GuiStyle.item(
        Material.BARRIER,
        Component.text("Cancel", NamedTextColor.RED).decoration(TextDecoration.BOLD, true),
        listOf(Component.empty(), plain("  Discard this selection — no key used.", NamedTextColor.GRAY), Component.empty())
    )

    private fun addPickNavigation(gui: CustomGui, page: Int, totalPages: Int, open: (Player, Int) -> Unit) {
        if (totalPages <= 1) return
        if (page > 0) gui.setItem(39, navButton("« Previous Page")) { p, _ -> GuiStyle.pageSound(p); open(p, page - 1) }
        gui.setItem(40, pageIndicator(page + 1, totalPages))
        if (page < totalPages - 1) gui.setItem(41, navButton("Next Page »")) { p, _ -> GuiStyle.pageSound(p); open(p, page + 1) }
    }

    private fun sendKeyMissing(player: Player, crate: CrateDef) {
        plugin.commsManager.send(
            player,
            Component.text("You no longer have a ", NamedTextColor.RED)
                .append(Component.text(crate.keyName, defaultRewardColor))
                .append(Component.text(" in your hand. No key was used.", NamedTextColor.RED))
        )
    }

    /**
     * True (and the GUI closed, the player told) when a pick screen no longer matches the server:
     * the crate was edited or reloaded since the GUI opened (a new definition), or it can't be
     * opened here any more. A stale screen never pays, whatever it shows.
     */
    private fun pickStale(player: Player, crate: CrateDef): Boolean {
        if (crates[crate.id] !== crate) {
            select3Sessions.remove(player.uniqueId)
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("This crate changed while you were choosing — please open it again. No key was used.", NamedTextColor.RED))
            return true
        }
        val problem = crateProblem(crate, randomDraw = false) ?: gamemodeProblem(player, crate) ?: return false
        select3Sessions.remove(player.uniqueId)
        player.closeInventory()
        plugin.commsManager.send(player, Component.text("This crate is unavailable right now. No key was used.", NamedTextColor.RED))
        if (player.hasPermission("joshymc.crate.admin")) {
            plugin.commsManager.send(player, Component.text("Admin: crate '${crate.id}' — $problem", NamedTextColor.GRAY))
        }
        return true
    }

    // --- SELECT_3 mode GUI ---

    /**
     * "Select 3" pick GUI — same shape-based layout as [openSelectGui], but tracks up to
     * 3 picks in [select3Sessions] before handing off to [openSelect3Confirm]. Clicking an
     * already-picked reward deselects it. No key is consumed here — only at confirmation,
     * so closing this GUI without finishing never costs a key.
     */
    private fun openSelect3Gui(player: Player, crate: CrateDef, page: Int = 0) {
        val session = select3Sessions[player.uniqueId]
        if (session == null || session.crateId != crate.id) {
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
            return
        }

        val title = Component.text("Select 3: ")
            .append(Component.text(crate.displayName, crateNameColor))
            .decoration(TextDecoration.ITALIC, false)

        val totalPages = CrateLayout.pageCount(crate.rewards.size)
        val safePage = page.coerceIn(0, totalPages - 1)
        val pageStart = safePage * CrateLayout.PAGE_CAPACITY
        val pageRewards = crate.rewards.drop(pageStart).take(CrateLayout.PAGE_CAPACITY)

        val size = 45
        val gui = CustomGui(title, size)
        gui.onClose = { p -> select3Sessions.remove(p.uniqueId) }
        pickFrame(gui, crate)

        val picked = session.selectedIndices.sorted().mapNotNull { crate.rewards.getOrNull(it) }
        val progress = ItemStack(Material.NAME_TAG)
        progress.editMeta { meta ->
            meta.displayName(
                Component.text("Selected: ${session.selectedIndices.size}/${CrateSelection.SELECT_3_PICKS}", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf<Component>(Component.empty())
            for (reward in picked) {
                lore.add(plain("  ✔ ", NamedTextColor.GREEN).append(Component.text("${reward.displayName} x${reward.amount}", rewardColor(reward))))
            }
            repeat(CrateSelection.SELECT_3_PICKS - picked.size) { lore.add(plain("  ○ (empty)", NamedTextColor.DARK_GRAY)) }
            lore.add(Component.empty())
            lore.add(plain("  Pick 3 different rewards.", NamedTextColor.GRAY))
            lore.add(plain("  Click a selected reward again to deselect.", NamedTextColor.GRAY))
            lore.add(plain("  Your key is only used when you confirm.", NamedTextColor.GRAY))
            lore.add(Component.empty())
            meta.lore(lore)
        }
        gui.setItem(4, progress)

        val positions = CrateLayout.positions(pageRewards.size)

        for ((idxOnPage, reward) in pageRewards.withIndex()) {
            if (idxOnPage >= positions.size) break
            val globalIndex = pageStart + idxOnPage
            val (row, col) = positions[idxOnPage]
            val slot = (row + 1) * 9 + (col + 1)
            val isSelected = session.selectedIndices.contains(globalIndex)
            val action = if (isSelected) {
                listOf(plain("  ✔ Selected", NamedTextColor.GREEN), plain("  Click to deselect", NamedTextColor.GRAY))
            } else if (session.selectedIndices.size >= CrateSelection.SELECT_3_PICKS) {
                listOf(plain("  Deselect a reward to pick this one", NamedTextColor.GRAY))
            } else {
                listOf(plain("  Click to select!", NamedTextColor.GREEN))
            }
            val item = rewardDisplay(crate, reward, CrateRewardText.Context.PICK, player, action, highlight = isSelected)

            gui.setItem(slot, item) { p, _ ->
                val sess = select3Sessions[p.uniqueId]
                if (sess == null || sess.crateId != crate.id) {
                    p.closeInventory()
                    plugin.commsManager.send(p, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
                    return@setItem
                }
                if (pickStale(p, crate)) return@setItem
                when (val result = CrateSelection.toggle(sess.selectedIndices, globalIndex, crate.rewards.size)) {
                    CrateSelection.Toggle.Full, CrateSelection.Toggle.Invalid -> {
                        GuiStyle.errorSound(p)
                        return@setItem
                    }
                    is CrateSelection.Toggle.Changed -> {
                        val added = result.selected.size > sess.selectedIndices.size
                        sess.selectedIndices.clear()
                        sess.selectedIndices.addAll(result.selected)
                        p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.6f, if (added) 1.3f else 1.0f)
                    }
                }
                if (CrateSelection.complete(sess.selectedIndices, crate.rewards.size)) {
                    openSelect3Confirm(p, crate)
                } else {
                    openSelect3Gui(p, crate, safePage)
                }
            }
        }

        addPickNavigation(gui, safePage, totalPages) { p, target -> if (!pickStale(p, crate)) openSelect3Gui(p, crate, target) }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /** Final review step for Select 3 — shows the 3 chosen rewards and requires Confirm before payout. */
    private fun openSelect3Confirm(player: Player, crate: CrateDef) {
        val session = select3Sessions[player.uniqueId]
        if (session == null || session.crateId != crate.id || !CrateSelection.complete(session.selectedIndices, crate.rewards.size)) {
            select3Sessions.remove(player.uniqueId)
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
            return
        }
        val chosen = session.selectedIndices.sorted().mapNotNull { crate.rewards.getOrNull(it) }

        val title = Component.text("Confirm Selection: ")
            .append(Component.text(crate.displayName, crateNameColor))
            .decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, 27)
        gui.onClose = { p -> select3Sessions.remove(p.uniqueId) }
        pickFrame(gui, crate)
        val lime = pane(Material.LIME_STAINED_GLASS_PANE)
        for (slot in listOf(10, 12, 14, 16)) gui.inventory.setItem(slot, lime.clone())
        gui.setItem(4, pickHeader(crate, "Your 3 Rewards", listOf("Confirm to use 1 key", "and claim all three.")))

        val displaySlots = listOf(11, 13, 15)
        for ((i, reward) in chosen.withIndex()) {
            gui.inventory.setItem(displaySlots[i], rewardDisplay(
                crate, reward, CrateRewardText.Context.PICK, player,
                action = listOf(plain("  ✔ Selected", NamedTextColor.GREEN)),
                highlight = true
            ))
        }

        val claim = CrateSelection.OneShot()
        gui.setItem(20, confirmButton("Consume 1 key and claim these 3 rewards.")) { p, _ ->
            if (!claim.take()) return@setItem
            val currentSession = select3Sessions[p.uniqueId]
            if (currentSession == null || currentSession.crateId != crate.id ||
                !CrateSelection.complete(currentSession.selectedIndices, crate.rewards.size)) {
                select3Sessions.remove(p.uniqueId)
                p.closeInventory()
                plugin.commsManager.send(p, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
                return@setItem
            }
            if (pickStale(p, crate)) return@setItem
            val rewardsToGrant = currentSession.selectedIndices.sorted().mapNotNull { crate.rewards.getOrNull(it) }

            val keyInHand = p.inventory.itemInMainHand
            if (!isKey(keyInHand, crate.id)) {
                select3Sessions.remove(p.uniqueId)
                p.closeInventory()
                sendKeyMissing(p, crate)
                return@setItem
            }
            // Build the exact stacks that will be granted, then make sure all 3 fit before
            // the key is touched. One free slot is not enough for 3 rewards.
            val rewardItems = rewardsToGrant.map { buildRewardItem(it) }
            if (!select3RewardsFit(p, rewardItems)) {
                select3Sessions.remove(p.uniqueId)
                p.closeInventory()
                plugin.commsManager.send(
                    p,
                    Component.text("You need more inventory space to claim all 3 rewards — no key was used.", NamedTextColor.RED)
                )
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }

            consumeOneKey(p)
            select3Sessions.remove(p.uniqueId)

            for (rewardItem in rewardItems) {
                plugin.giveItemSafely(p, rewardItem)
            }
            spawnWinParticles(p, crate)
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            plugin.commsManager.send(p, winMessage(crate, rewardsToGrant, "You selected "))
            openPickResult(p, crate, rewardsToGrant)
        }

        gui.setItem(22, GuiStyle.back("Change Picks")) { p, _ ->
            if (claim.used || pickStale(p, crate)) return@setItem
            GuiStyle.pageSound(p)
            openSelect3Gui(p, crate)
        }

        gui.setItem(24, cancelButton()) { p, _ ->
            select3Sessions.remove(p.uniqueId)
            p.closeInventory()
            plugin.commsManager.send(p, Component.text("Selection cancelled — no key was used.", NamedTextColor.YELLOW))
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // --- INSTANT animation ---

    private fun openInstant(player: Player, crate: CrateDef) {
        val reward = selectWeightedReward(crate)
        val rewardItem = buildRewardItem(reward)
        plugin.giveItemSafely(player, rewardItem)
        spawnWinParticles(player, crate)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        plugin.commsManager.send(player, winMessage(crate, listOf(reward)))
        activeAnimations.remove(player.uniqueId)
    }

    // --- PULSE animation ---

    private fun openPulse(player: Player, crate: CrateDef, preDrawn: CrateReward? = null, preDrawnId: String? = null) {
        val title = Component.text(CRATE_GUI_TITLE_PREFIX)
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)
        val glass = ItemStack(crate.animationGlass)
        glass.editMeta { it.displayName(Component.empty()) }

        // Fill everything with glass
        for (i in 0 until 27) gui.inventory.setItem(i, glass.clone())

        // Pick 7 random rewards to show in the middle row
        val displayRewards = (0 until 7).map { selectWeightedReward(crate) }
        for ((i, reward) in displayRewards.withIndex()) {
            gui.inventory.setItem(10 + i, buildRewardDisplay(crate, reward))
        }

        plugin.guiManager.open(player, gui)

        // The winner is pre-selected and stored before the animation starts (issue #1503: an
        // opening animation already drew and stored it before playing).
        val winnerReward = preDrawn ?: selectWeightedReward(crate)
        val pendingId = preDrawnId ?: registerPendingReward(player, winnerReward)

        schedulePulseStep(player, gui.inventory, crate, displayRewards.toMutableList(), winnerReward, pendingId, 0, 12)
    }

    private fun schedulePulseStep(
        player: Player,
        inv: org.bukkit.inventory.Inventory,
        crate: CrateDef,
        displayRewards: MutableList<CrateReward>,
        winner: CrateReward,
        pendingId: String,
        step: Int,
        maxSteps: Int
    ) {
        val delay = when {
            step < maxSteps * 0.4 -> 4L
            step < maxSteps * 0.7 -> 8L
            else -> 12L
        }

        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            // Interrupted (quit, reload): the reward is still pending and gets delivered elsewhere.
            if (inFlightRewards[player.uniqueId] != pendingId || !player.isOnline) return@scheduleSyncDelayedTask

            val glass = ItemStack(crate.animationGlass)
            glass.editMeta { it.displayName(Component.empty()) }

            // Each step, blank out one of the remaining non-center slots
            val activeSlots = (10..16).filter { inv.getItem(it)?.type != crate.animationGlass }
            val centerSlot = 13

            if (step >= maxSteps || activeSlots.size <= 1) {
                // Final reveal — show the winner in center
                inv.setItem(centerSlot, buildRewardDisplay(crate, winner))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 2.0f)

                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                    if (!finishInFlight(player, pendingId, winMessage(crate, listOf(winner)))) return@scheduleSyncDelayedTask
                    // Issue #1514: paid first, then the GUI turns into the result screen.
                    showReelResult(inv, crate, winner)
                    spawnWinParticles(player, crate)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)

                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                        activeAnimations.remove(player.uniqueId)
                        if (player.isOnline && player.openInventory.topInventory == inv) player.closeInventory()
                    }, RESULT_SCREEN_TICKS)
                }, 10L)
                return@scheduleSyncDelayedTask
            }

            // Blank out a random non-center slot
            val candidates = activeSlots.filter { it != centerSlot }
            if (candidates.isNotEmpty()) {
                val blankSlot = candidates.random()
                inv.setItem(blankSlot, glass.clone())
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 0.8f + (step.toFloat() / maxSteps))
            }

            schedulePulseStep(player, inv, crate, displayRewards, winner, pendingId, step + 1, maxSteps)
        }, delay)
    }

    // --- SPIN animation (original) ---

    private fun openSpin(player: Player, crate: CrateDef, preDrawn: CrateReward? = null, preDrawnId: String? = null) {
        val title = Component.text(CRATE_GUI_TITLE_PREFIX)
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 27)

        // Fill top and bottom rows with animation glass
        val glass = ItemStack(crate.animationGlass)
        glass.editMeta { it.displayName(Component.empty()) }
        for (i in 0..8) {
            gui.inventory.setItem(i, glass.clone())
            gui.inventory.setItem(18 + i, glass.clone())
        }

        // Fill middle row edges with glass
        gui.inventory.setItem(9, glass.clone())
        gui.inventory.setItem(17, glass.clone())

        // Fill middle reward slots with random rewards initially. The reel keeps the
        // CrateReward behind each of slots 10..16 so the payout is the exact reward that
        // lands in slot 13, never one reconstructed from the GUI item.
        val reel = MutableList(7) { selectWeightedReward(crate) }
        for ((i, reward) in reel.withIndex()) {
            gui.inventory.setItem(10 + i, buildRewardDisplay(crate, reward))
        }

        // Point arrows at the winning slot (13) from top-middle (4) and bottom-middle (22)
        gui.inventory.setItem(4, winningSlotIndicator(pointingDown = true))
        gui.inventory.setItem(22, winningSlotIndicator(pointingDown = false))

        plugin.guiManager.open(player, gui)

        // The winner is drawn and stored now (the key is already gone), then fed into the reel
        // so it stops in slot 13 on the last step. Same single weighted draw as before.
        val winner = preDrawn ?: selectWeightedReward(crate)
        val pendingId = preDrawnId ?: registerPendingReward(player, winner)

        scheduleAnimationStep(player, gui.inventory, crate, reel, winner, pendingId, 2L, 0, 40)
    }

    private fun scheduleAnimationStep(
        player: Player,
        inv: org.bukkit.inventory.Inventory,
        crate: CrateDef,
        reel: MutableList<CrateReward>,
        winner: CrateReward,
        pendingId: String,
        delay: Long,
        step: Int,
        maxSteps: Int
    ) {
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            // Interrupted (quit, reload): the reward is still pending and gets delivered elsewhere.
            if (inFlightRewards[player.uniqueId] != pendingId || !player.isOnline) return@scheduleSyncDelayedTask

            // Shift rewards left: move 11->10, 12->11, ..., 16->15, new->16
            for (slot in 10..15) {
                inv.setItem(slot, inv.getItem(slot + 1))
            }
            // A reward entering at slot 16 sits in slot 13 three shifts later, so the winner
            // enters four steps before the end and stops in the center.
            val incoming = if (step == maxSteps - 4) winner else selectWeightedReward(crate)
            reel.removeAt(0)
            reel.add(incoming)
            inv.setItem(16, buildRewardDisplay(crate, incoming))

            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f + (step.toFloat() / maxSteps))

            val nextStep = step + 1

            if (nextStep >= maxSteps) {
                // Animation complete - center slot 13 (reel index 3) shows the stored winner
                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                    if (!finishInFlight(player, pendingId, winMessage(crate, listOf(winner)))) return@scheduleSyncDelayedTask
                    // Issue #1514: paid first, then the GUI turns into the result screen.
                    showReelResult(inv, crate, winner)
                    spawnWinParticles(player, crate)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)

                    // Close after a short look at the result (only if it's still this GUI).
                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                        activeAnimations.remove(player.uniqueId)
                        if (player.isOnline && player.openInventory.topInventory == inv) player.closeInventory()
                    }, RESULT_SCREEN_TICKS)
                }, 10L)
            } else {
                // Calculate next delay - speed decreases (delay increases) as we go
                val newDelay = when {
                    nextStep < maxSteps * 0.5 -> 2L
                    nextStep < maxSteps * 0.7 -> 3L
                    nextStep < maxSteps * 0.85 -> 5L
                    else -> 8L
                }

                scheduleAnimationStep(player, inv, crate, reel, winner, pendingId, newDelay, nextStep, maxSteps)
            }
        }, delay)
    }

    // --- Pending rewards (SPIN / PULSE) ---

    /**
     * Stores [reward] for [player] before its animation starts. The item is built once here,
     * so a voucher id minted for it is the one the player ends up holding.
     */
    private fun registerPendingReward(player: Player, reward: CrateReward): String {
        val id = UUID.randomUUID().toString()
        plugin.databaseManager.execute(
            "INSERT INTO crate_pending_rewards (id, owner_uuid, item, label, created_at) VALUES (?, ?, ?, ?, ?)",
            id, player.uniqueId.toString(), serializeItem(buildRewardItem(reward)),
            "${reward.displayName} x${reward.amount}", System.currentTimeMillis()
        )
        inFlightRewards[player.uniqueId] = id
        return id
    }

    /**
     * Pays out the animation that owns [pendingId]. Returns false (and pays nothing) if the
     * animation was interrupted in the meantime; that reward is delivered by another path.
     */
    private fun finishInFlight(player: Player, pendingId: String, wonMessage: Component? = null): Boolean {
        if (!player.isOnline || inFlightRewards[player.uniqueId] != pendingId) return false
        inFlightRewards.remove(player.uniqueId)
        claimPendingReward(player, pendingId, wonMessage)
        return true
    }

    /**
     * Delivers one pending reward row exactly once: the row is deleted first, and only the
     * caller whose DELETE removed it hands the item over. Whatever doesn't fit is written back
     * under the same id and retried later, never dropped on the ground.
     */
    private fun claimPendingReward(player: Player, pendingId: String, wonMessage: Component? = null): Boolean {
        val row = plugin.databaseManager.queryFirst(
            "SELECT item, label, created_at FROM crate_pending_rewards WHERE id = ? AND owner_uuid = ?",
            pendingId, player.uniqueId.toString()
        ) { rs -> Triple(rs.getString("item"), rs.getString("label"), rs.getLong("created_at")) } ?: return false
        val (itemBase64, label, createdAt) = row
        val item = deserializeItem(itemBase64)
        if (item == null) {
            plugin.logger.warning("[Crates] Pending reward $pendingId for ${player.name} could not be read; left in crate_pending_rewards.")
            return false
        }

        val claimed = plugin.databaseManager.executeUpdate(
            "DELETE FROM crate_pending_rewards WHERE id = ?", pendingId
        )
        if (claimed == 0) return false

        val originalAmount = item.amount
        val leftover = plugin.depositItemSafely(player, item)
        if (leftover != null && leftover.amount > 0) {
            plugin.databaseManager.execute(
                "INSERT INTO crate_pending_rewards (id, owner_uuid, item, label, created_at) VALUES (?, ?, ?, ?, ?)",
                pendingId, player.uniqueId.toString(), serializeItem(leftover), label, createdAt
            )
            plugin.commsManager.send(
                player,
                Component.text("Your inventory is full. Your crate reward ", NamedTextColor.YELLOW)
                    .append(Component.text(label, TextColor.color(0xFFAA00)))
                    .append(Component.text(" is safely held and will be delivered once you have space.", NamedTextColor.YELLOW))
            )
            if (leftover.amount >= originalAmount) return true
        }

        plugin.commsManager.send(
            player,
            wonMessage ?: Component.text("You won ", NamedTextColor.GREEN)
                .append(Component.text(label, TextColor.color(0xFFAA00)))
                .append(Component.text("!", NamedTextColor.GREEN))
        )
        return true
    }

    /**
     * Holds [item] in crate_pending_rewards for [player], to be retried by the pending-reward
     * loop. Used for Event World crate keys that couldn't be stored in /overflow (issue #1178).
     */
    fun holdPendingReward(player: Player, item: ItemStack) {
        val label = getKeyType(item)?.let { crates[it]?.keyName }?.let { "$it x${item.amount}" }
            ?: "${item.type.name.lowercase().replace('_', ' ')} x${item.amount}"
        plugin.databaseManager.execute(
            "INSERT INTO crate_pending_rewards (id, owner_uuid, item, label, created_at) VALUES (?, ?, ?, ?, ?)",
            UUID.randomUUID().toString(), player.uniqueId.toString(), serializeItem(item.clone()), label, System.currentTimeMillis()
        )
        plugin.logger.warning("[Crates] ${player.name}'s crate key couldn't be stored in /overflow; held $label as a pending crate reward.")
    }

    /** Delivers every held crate reward of [player] that isn't owned by a running animation. */
    fun deliverPendingRewards(player: Player) {
        val inFlight = inFlightRewards[player.uniqueId]
        val ids = plugin.databaseManager.query(
            "SELECT id FROM crate_pending_rewards WHERE owner_uuid = ? ORDER BY created_at ASC",
            player.uniqueId.toString()
        ) { rs -> rs.getString("id") }
        for (id in ids) {
            if (id == inFlight) continue
            claimPendingReward(player, id)
        }
    }

    private fun retryPendingRewards() {
        val owners = plugin.databaseManager.query(
            "SELECT DISTINCT owner_uuid FROM crate_pending_rewards"
        ) { rs -> rs.getString("owner_uuid") }
        for (owner in owners) {
            val uuid = try { UUID.fromString(owner) } catch (_: Exception) { continue }
            val player = Bukkit.getPlayer(uuid) ?: continue
            // Held rewards wait until the player leaves the event: the event inventory is
            // wiped on exit, and Event World keys would just bounce back into this table.
            if (plugin.eventManager.isInEventWorld(player)) continue
            // Don't re-send "inventory full" every 30s: only retry once a slot is free.
            if (!plugin.modModeManager.isModMode(player) && player.inventory.firstEmpty() == -1) continue
            deliverPendingRewards(player)
        }
    }

    /** How many rewards one key yields — the number of picks the crate's manual selection allows. */
    private fun rewardsPerOpen(crate: CrateDef): Int = if (crate.mode == CrateMode.SELECT_3) 3 else 1

    /**
     * Weighted draw of [count] distinct rewards (no repeats, matching the Select 3 picker,
     * which can't select the same reward twice). Capped at the pool size.
     */
    private fun selectDistinctWeightedRewards(crate: CrateDef, count: Int): List<CrateReward> {
        if (count <= 1) return listOf(selectWeightedReward(crate))
        val pool = crate.rewards.toMutableList()
        val picked = mutableListOf<CrateReward>()
        while (picked.size < count && pool.isNotEmpty()) {
            val totalWeight = pool.sumOf { it.weight }
            var random = (Math.random() * totalWeight).toInt()
            var index = pool.lastIndex
            for ((i, reward) in pool.withIndex()) {
                random -= reward.weight
                if (random < 0) { index = i; break }
            }
            picked.add(pool.removeAt(index))
        }
        return picked
    }

    private fun selectWeightedReward(crate: CrateDef): CrateReward {
        val totalWeight = crate.rewards.sumOf { it.weight }
        var random = (Math.random() * totalWeight).toInt()
        for (reward in crate.rewards) {
            random -= reward.weight
            if (random < 0) return reward
        }
        return crate.rewards.last()
    }

    /** Static arrow marker pointing toward the winning center slot (13) from the given side. */
    private fun winningSlotIndicator(pointingDown: Boolean): ItemStack {
        val arrow = ItemStack(Material.ARROW)
        arrow.editMeta { meta ->
            meta.displayName(
                Component.text(if (pointingDown) "▼ Winning Slot ▼" else "▲ Winning Slot ▲", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
        }
        return arrow
    }

    // --- Reward presentation (issue #1514) ---
    // Every crate GUI decorates rewards through rewardDisplay, so names, quantities, rarities,
    // chances and hidden rewards read the same everywhere. Display stacks are never delivered:
    // payouts always come from buildRewardItem / crate_pending_rewards.

    private val defaultRewardColor = TextColor.color(0xFFAA00)
    private val crateNameColor = TextColor.color(0x55FFFF)

    private fun plain(text: String, color: TextColor): Component =
        Component.text(text, color).decoration(TextDecoration.ITALIC, false)

    /** The reward's colour: its rarity's, else the gold every crate GUI used before. */
    fun rewardColor(reward: CrateReward): TextColor =
        rarities.tier(reward.rarity)?.let { TextColor.color(it.color) } ?: defaultRewardColor

    /** [withStatus]: only the Preview shows availability, so other screens skip those checks. */
    private fun rewardInfo(crate: CrateDef, reward: CrateReward, player: Player?, withStatus: Boolean): CrateRewardText.Info =
        CrateRewardText.Info(
            name = reward.displayName,
            amount = reward.amount,
            weight = reward.weight,
            totalWeight = crate.rewards.sumOf { it.weight },
            rarity = rarities.tier(reward.rarity),
            hidden = reward.hidden,
            unavailableReason = if (withStatus) rewardUnavailableReason(player, crate, reward) else null,
            pickCrate = isManualPickMode(crate.mode),
        )

    /**
     * Why [reward] can't be won right now, in player-facing words, or null. Uses the same checks
     * every open path runs ([crateProblem], [gamemodeProblem]); the GUI only reports them.
     */
    private fun rewardUnavailableReason(player: Player?, crate: CrateDef, reward: CrateReward): String? {
        val fixing = "this crate is being fixed by staff"
        if (configProblems[crate.id] != null) return fixing
        if (crate.mode == CrateMode.SELECT_3 && crate.rewards.size < 3) return fixing
        if (reward.amount < 1) return fixing
        if (reward.itemBase64 != null && deserializeItem(reward.itemBase64) == null) return fixing
        if (rewardEnchantProblem(reward) != null) return fixing
        if (player != null) {
            val item = rewardTemplate(reward)
            val scope = item?.let { plugin.itemManager.getCustomItemId(it) }?.let { plugin.itemManager.getItem(it) }?.gamemodeScope
            if (scope != null && !plugin.gamemodeLockManager.isAllowed(scope, player.world)) {
                return "only on ${scope.gamemode?.displayName ?: scope.id}"
            }
        }
        return null
    }

    /**
     * A display stack for [reward] in [context]: its real item (custom name, model, enchants and
     * own lore kept) with the shared detail lines, or a mystery icon when the context conceals a
     * hidden reward. [action] lines go at the bottom (e.g. "Click to select").
     */
    private fun rewardDisplay(
        crate: CrateDef,
        reward: CrateReward,
        context: CrateRewardText.Context,
        player: Player? = null,
        action: List<Component> = emptyList(),
        highlight: Boolean = false,
    ): ItemStack {
        val info = rewardInfo(crate, reward, player, withStatus = context == CrateRewardText.Context.PREVIEW)
        val concealed = CrateRewardText.conceals(info, context)
        val color = rewardColor(reward)
        val base = if (concealed) {
            ItemStack(Material.ENDER_EYE)
        } else {
            (rewardTemplate(reward) ?: ItemStack(reward.material)).also { it.amount = reward.amount.coerceIn(1, 99) }
        }
        val ownLore = if (concealed) emptyList() else base.itemMeta?.lore() ?: emptyList()
        base.editMeta { meta ->
            meta.displayName(
                Component.text(CrateRewardText.name(info, context), color)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf<Component>()
            if (concealed) {
                lore.add(Component.empty())
                lore.add(plain("  A secret reward. Win it to find out!", NamedTextColor.GRAY))
            } else if (ownLore.isNotEmpty()) {
                lore.add(Component.empty())
                lore.addAll(ownLore)
            } else if (reward.enchantments.isNotEmpty()) {
                lore.add(Component.empty())
                lore.add(plain("  Enchantments:", NamedTextColor.GRAY))
                for ((ench, level) in reward.enchantments) {
                    lore.add(plain("  - ${ench.key.key.replace("_", " ")} $level", NamedTextColor.DARK_GRAY))
                }
            }
            lore.add(Component.empty())
            for (line in CrateRewardText.lines(info, context)) {
                val valueColor: TextColor = when (line.kind) {
                    CrateRewardText.Kind.RARITY -> color
                    CrateRewardText.Kind.QUANTITY -> NamedTextColor.WHITE
                    CrateRewardText.Kind.CHANCE -> NamedTextColor.YELLOW
                    CrateRewardText.Kind.STATUS -> if (line.good) NamedTextColor.GREEN else NamedTextColor.RED
                }
                lore.add(plain("  ${line.label}: ", NamedTextColor.GRAY).append(Component.text(line.value, valueColor)))
            }
            if (action.isNotEmpty()) {
                lore.add(Component.empty())
                lore.addAll(action)
            }
            lore.add(Component.empty())
            meta.lore(lore)
            if (highlight) meta.setEnchantmentGlintOverride(true)
        }
        return base
    }

    /** Spin / Pulse reel entry: hidden rewards stay a mystery until the reveal. */
    private fun buildRewardDisplay(crate: CrateDef, reward: CrateReward): ItemStack =
        rewardDisplay(crate, reward, CrateRewardText.Context.REEL)

    /** The revealed reward, highlighted. */
    private fun resultDisplay(crate: CrateDef, reward: CrateReward): ItemStack =
        rewardDisplay(crate, reward, CrateRewardText.Context.RESULT, highlight = true)

    private fun pane(material: Material): ItemStack =
        ItemStack(material).also { stack -> stack.editMeta { it.displayName(Component.empty()) } }

    /** "You won!" banner for result screens: the reward(s), their rarity and the crate. */
    private fun resultBanner(crate: CrateDef, rewards: List<CrateReward>, title: String): ItemStack {
        val item = ItemStack(Material.NETHER_STAR)
        item.editMeta { meta ->
            meta.displayName(
                Component.text(title, NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf<Component>(Component.empty())
            for (reward in rewards) {
                var line = plain("  ${reward.displayName}", rewardColor(reward))
                    .append(Component.text(" x${reward.amount}", NamedTextColor.WHITE))
                rarities.tier(reward.rarity)?.let { line = line.append(Component.text(" (${it.label})", TextColor.color(it.color))) }
                lore.add(line)
            }
            lore.add(Component.empty())
            lore.add(plain("  From: ", NamedTextColor.GRAY).append(Component.text(crate.displayName, crateNameColor)))
            lore.add(Component.empty())
            meta.lore(lore)
            meta.setEnchantmentGlintOverride(true)
        }
        return item
    }

    /** Chat line for a finished open: "You won Diamond x3 (Legendary) from Rare Crate!" */
    private fun winMessage(crate: CrateDef, rewards: List<CrateReward>, verb: String = "You won "): Component {
        var msg: Component = Component.text(verb, NamedTextColor.GREEN)
        for ((i, reward) in rewards.withIndex()) {
            if (i > 0) msg = msg.append(Component.text(if (i == rewards.lastIndex) " and " else ", ", NamedTextColor.GREEN))
            msg = msg.append(Component.text(reward.displayName, rewardColor(reward)))
                .append(Component.text(" x${reward.amount}", NamedTextColor.GREEN))
            rarities.tier(reward.rarity)?.let { msg = msg.append(Component.text(" (${it.label})", TextColor.color(it.color))) }
        }
        return msg.append(Component.text(" from ", NamedTextColor.GREEN))
            .append(Component.text(crate.displayName, crateNameColor))
            .append(Component.text("!", NamedTextColor.GREEN))
    }

    /**
     * Turns a Spin / Pulse GUI into its result screen once the stored winner was paid: the winner
     * in the center slot (revealed, highlighted), the other reel slots dimmed, lime panes framing
     * the winner and the "You won!" banner on top. Only changes what the player sees.
     */
    private fun showReelResult(inv: Inventory, crate: CrateDef, winner: CrateReward) {
        val dim = pane(Material.GRAY_STAINED_GLASS_PANE)
        for (slot in 10..16) if (slot != 13) inv.setItem(slot, dim.clone())
        inv.setItem(13, resultDisplay(crate, winner))
        val lime = pane(Material.LIME_STAINED_GLASS_PANE)
        for (slot in listOf(3, 5, 12, 14, 21, 23)) inv.setItem(slot, lime.clone())
        inv.setItem(4, resultBanner(crate, listOf(winner), "You won!"))
    }

    /**
     * The result screen after a Select / Select 3 claim: display only, opened after the payout
     * is done, so nothing on it can pay again. Close is its only button.
     */
    private fun openPickResult(player: Player, crate: CrateDef, rewards: List<CrateReward>) {
        val title = Component.text("Reward Claimed: ")
            .append(Component.text(crate.displayName, crateNameColor))
            .decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, 27)
        val border = pane(crate.animationGlass)
        for (i in 0 until 27) gui.inventory.setItem(i, border.clone())
        val slots = when (rewards.size) {
            1 -> listOf(13)
            2 -> listOf(12, 14)
            else -> listOf(11, 13, 15)
        }
        for ((i, reward) in rewards.take(slots.size).withIndex()) {
            gui.inventory.setItem(slots[i], resultDisplay(crate, reward))
        }
        gui.inventory.setItem(4, resultBanner(crate, rewards, if (rewards.size == 1) "Reward claimed!" else "Rewards claimed!"))
        gui.setItem(22, GuiStyle.close()) { p, _ -> p.closeInventory() }
        plugin.guiManager.open(player, gui)
    }

    /**
     * The item [reward] hands out, before per-delivery changes (fresh voucher ids): the stored
     * snapshot (every component, PDC value and custom enchant kept as captured), or for a
     * hand-written crates.yml reward its material with the configured vanilla and custom
     * enchants. Custom enchant lore is rebuilt from the item's PDC levels, so previews and
     * the delivered item always list exactly the enchants that work. Null when the snapshot
     * can't be decoded. Every crate GUI previews this and [buildRewardItem] delivers it, so
     * what a player sees is what they get (issue #1300).
     */
    fun rewardTemplate(reward: CrateReward): ItemStack? {
        val item = if (reward.itemBase64 != null) {
            deserializeItem(reward.itemBase64) ?: return null
        } else {
            ItemStack(reward.material).also { built ->
                if (reward.enchantments.isNotEmpty()) {
                    built.editMeta { meta ->
                        for ((ench, level) in reward.enchantments) {
                            meta.addEnchant(ench, level, true)
                        }
                    }
                }
                // applyEnchant validates target, level and conflicts; rewardEnchantProblem
                // refuses the crate when one of these didn't take.
                for ((enchId, level) in reward.customEnchantments) {
                    plugin.customEnchantManager.applyEnchant(built, enchId, level)
                }
            }
        }
        item.amount = reward.amount
        // Only touch items that carry custom enchants: updateLore also resets the glint override.
        if (plugin.customEnchantManager.getEnchants(item).isNotEmpty()) {
            plugin.customEnchantManager.updateLore(item)
        }
        return item
    }

    /**
     * Why [reward]'s custom enchants can't be handed out, or null when they're fine: a stored
     * enchant that isn't registered, is past its max level, doesn't fit the item or conflicts
     * ([CustomEnchantManager.enchantProblems]), or a crates.yml `custom-enchantments` entry
     * that couldn't be applied. Such a crate is refused before any key is used.
     */
    private fun rewardEnchantProblem(reward: CrateReward): String? {
        val item = rewardTemplate(reward) ?: return null // undecodable snapshots are reported separately
        plugin.customEnchantManager.enchantProblems(item).firstOrNull()?.let { return "has an invalid custom enchant: $it" }
        for ((enchId, level) in reward.customEnchantments) {
            if (plugin.customEnchantManager.getLevel(item, enchId) != level) {
                return "has custom enchant '$enchId' level $level that can't be applied to ${reward.material.name.lowercase()}"
            }
        }
        return null
    }

    private fun buildRewardItem(reward: CrateReward): ItemStack {
        // Prefer the serialized item if available — preserves trims, custom items, PDC, NBT
        val serialized = if (reward.itemBase64 != null) rewardTemplate(reward) else null
        if (serialized != null) {
            // The stored reward template is a Base64 snapshot of whatever voucher was held
            // when the reward was configured — including that voucher's unique voucher_uuid.
            // Handing out the same snapshot verbatim would give every winner an item with an
            // IDENTICAL voucher_uuid, so only the first person to redeem it would succeed;
            // everyone else would hit the "already redeemed" duplicate-id check. Credit,
            // chat-tag and rank vouchers each burn that id in their own redemptions table.
            when {
                plugin.creditVoucherManager.isCreditVoucher(serialized) ->
                    mintFreshVoucherId(serialized, plugin.creditVoucherManager.voucherUuidKey)
                plugin.chatTagVoucherManager.isChatTagVoucher(serialized) ->
                    mintFreshVoucherId(serialized, plugin.chatTagVoucherManager.voucherUuidKey)
                plugin.rankVoucherManager.isRankVoucher(serialized) ->
                    mintFreshVoucherId(serialized, plugin.rankVoucherManager.voucherUuidKey)
            }
            return serialized
        }
        // A stored snapshot that won't decode must never turn into the plain base material.
        // crateProblem refuses such crates before any key is taken, so this is a last guard.
        check(reward.itemBase64 == null) { "crate reward '${reward.displayName}' has an item-base64 snapshot that can't be decoded" }

        return checkNotNull(rewardTemplate(reward))
    }

    /**
     * Gives a voucher copied from a reward template its own redemption id, so every delivered
     * voucher redeems independently. Only the id changes (rank, display, lore, model and the
     * rest of the PDC stay as configured), and the amount goes back to 1 since a legitimate
     * voucher never stacks.
     */
    private fun mintFreshVoucherId(voucher: ItemStack, voucherUuidKey: NamespacedKey) {
        voucher.amount = 1
        voucher.editMeta { meta ->
            meta.persistentDataContainer.set(voucherUuidKey, PersistentDataType.STRING, UUID.randomUUID().toString())
        }
    }

    /** Serialize an ItemStack to a Base64 string (preserves all NBT/PDC/trims). */
    fun serializeItem(item: ItemStack): String {
        val bytes = item.serializeAsBytes()
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    /** Deserialize a Base64-encoded ItemStack snapshot, or null if invalid/blank. */
    fun deserializeItem(base64: String?): ItemStack? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = java.util.Base64.getDecoder().decode(base64)
            ItemStack.deserializeBytes(bytes)
        } catch (_: Exception) {
            null
        }
    }

    private fun spawnWinParticles(player: Player, crate: CrateDef) {
        val loc = player.location.add(0.0, 1.0, 0.0)
        when (crate.winParticle) {
            Particle.FIREWORK -> {
                player.world.spawnParticle(Particle.FIREWORK, loc, 30, 0.5, 0.5, 0.5, 0.1)
            }
            Particle.TOTEM_OF_UNDYING -> {
                player.world.spawnParticle(Particle.TOTEM_OF_UNDYING, loc, 50, 0.5, 1.0, 0.5, 0.3)
            }
            Particle.EXPLOSION -> {
                player.world.spawnParticle(Particle.EXPLOSION, loc, 5, 0.3, 0.3, 0.3, 0.0)
            }
            else -> {
                spawnCrateParticle(player.world, loc, crate.winParticle, crate.particleColor, 30, 0.5, 0.5, 0.5, 0.1)
            }
        }
    }

    /**
     * The reward Preview. From a crate block (left-click / sneak) it keeps the #1414 look: sized
     * to the rewards, no glass. From the `/crate` browser ([back] set, issue #1514) it gets a
     * bottom row with Back, page buttons, a crate info item and Close. Every reward shows its
     * name, quantity, rarity (when configured), the exact chance from its weight and whether it
     * can be won right now; hidden rewards show as a mystery with their real chance.
     */
    fun openPreview(player: Player, crateType: String, page: Int = 0, back: ((Player) -> Unit)? = null) {
        val crate = crates[crateType] ?: return

        val title = Component.text(PREVIEW_GUI_TITLE_PREFIX)
            .append(Component.text(crate.displayName, crateNameColor))
            .decoration(TextDecoration.ITALIC, false)

        val total = crate.rewards.size
        val withMenu = back != null
        val totalPages = if (withMenu) com.liam.joshymc.crate.CrateMenuLayout.previewPageCount(total) else CratePreviewLayout.pageCount(total)
        val safePage = page.coerceIn(0, totalPages - 1)
        val pageStart = if (withMenu) com.liam.joshymc.crate.CrateMenuLayout.previewPageStart(total, safePage) else CratePreviewLayout.pageStart(total, safePage)
        val pageSize = if (withMenu) com.liam.joshymc.crate.CrateMenuLayout.previewPageSize(total, safePage) else CratePreviewLayout.pageSize(total, safePage)
        val pageRewards = crate.rewards.drop(pageStart).take(pageSize)

        val size = if (withMenu) com.liam.joshymc.crate.CrateMenuLayout.previewSize(total) else CratePreviewLayout.size(total)
        val gui = CustomGui(title, size)
        val slots = if (withMenu) com.liam.joshymc.crate.CrateMenuLayout.previewSlots(pageRewards.size) else CratePreviewLayout.slots(pageRewards.size)

        for ((idx, reward) in pageRewards.withIndex()) {
            if (idx >= slots.size) break
            gui.inventory.setItem(slots[idx], rewardDisplay(crate, reward, CrateRewardText.Context.PREVIEW, player))
        }

        val reopen: (Player, Int) -> Unit = { p, target -> GuiStyle.pageSound(p); openPreview(p, crateType, target, back) }
        if (back != null) {
            val L = com.liam.joshymc.crate.CrateMenuLayout
            gui.setItem(L.previewBack(size), GuiStyle.back("Back to Crates")) { p, _ -> GuiStyle.pageSound(p); back(p) }
            if (safePage > 0) gui.setItem(L.previewPrevious(size), GuiStyle.previousPage(safePage - 1, totalPages)) { p, _ -> reopen(p, safePage - 1) }
            gui.setItem(L.previewInfo(size), crateInfoItem(player, crate, safePage, totalPages))
            if (safePage < totalPages - 1) gui.setItem(L.previewNext(size), GuiStyle.nextPage(safePage + 1, totalPages)) { p, _ -> reopen(p, safePage + 1) }
            gui.setItem(L.previewClose(size), GuiStyle.close()) { p, _ -> p.closeInventory() }
        } else if (totalPages > 1) {
            if (safePage > 0) {
                gui.setItem(CratePreviewLayout.PREVIOUS_SLOT, navButton("« Previous Page")) { p, _ -> reopen(p, safePage - 1) }
            }
            gui.setItem(CratePreviewLayout.INDICATOR_SLOT, pageIndicator(safePage + 1, totalPages))
            if (safePage < totalPages - 1) {
                gui.setItem(CratePreviewLayout.NEXT_SLOT, navButton("Next Page »")) { p, _ -> reopen(p, safePage + 1) }
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // --- Crate browser support (issue #1514) ---

    /** How a crate opens, in player words. */
    fun modeDescription(crate: CrateDef): String = when (crate.mode) {
        CrateMode.RANDOM -> when (crate.animationType) {
            AnimationType.SPIN -> "Random reward (spin)"
            AnimationType.PULSE -> "Random reward (pulse)"
            AnimationType.INSTANT -> "Random reward (instant)"
        }
        CrateMode.SELECT -> "Choose 1 reward"
        CrateMode.SELECT_3 -> "Choose 3 rewards"
    }

    /** Crates shown in the `/crate` browser, in crates.yml order. */
    fun menuCrates(): List<CrateDef> = crates.values.filter { it.listed }

    /** Keys for [crateId] the player carries (inventory, armor and off-hand slots). */
    fun keyCount(player: Player, crateId: String): Int =
        player.inventory.contents.filterNotNull().filter { isKey(it, crateId) }.sumOf { it.amount }

    /** Registered crate blocks of this type. */
    fun locationCount(crateId: String): Int = crateLocations.count { it.crateType == crateId }

    /** Player-facing reason [crate] can't be opened by [player] right now, or null. Same rules as every open path. */
    fun availabilityProblem(player: Player, crate: CrateDef): String? {
        if (crateProblem(crate, randomDraw = crate.mode == CrateMode.RANDOM) != null) return "Temporarily unavailable"
        if (gamemodeProblem(player, crate) != null) return "Not available in this gamemode"
        if (locationCount(crate.id) == 0) return "No crate of this type is placed yet"
        return null
    }

    /** A plain display icon for [crate]: its key's look (material + key model), never a usable key. */
    fun crateIcon(crate: CrateDef): ItemStack {
        val icon = ItemStack(crate.keyMaterial)
        icon.editMeta { meta -> if (crate.keyItemModel != null) meta.setItemModel(crate.keyItemModel) }
        return icon
    }

    /** The info item: how the crate opens, the player's keys and how to open it. */
    private fun crateInfoItem(player: Player, crate: CrateDef, page: Int, totalPages: Int): ItemStack {
        val keys = keyCount(player, crate.id)
        val lore = mutableListOf<Component>(Component.empty())
        lore.add(plain("  ${modeDescription(crate)}", NamedTextColor.GRAY))
        lore.add(plain("  Rewards: ", NamedTextColor.GRAY).append(Component.text("${crate.rewards.size}", NamedTextColor.WHITE)))
        lore.add(plain("  Your ${crate.keyName}s: ", NamedTextColor.GRAY)
            .append(Component.text("$keys", if (keys > 0) NamedTextColor.GREEN else NamedTextColor.RED)))
        if (isManualPickMode(crate.mode)) {
            lore.add(plain("  Chances apply when mass-opening", NamedTextColor.DARK_GRAY))
            lore.add(plain("  (sneak + right-click with keys).", NamedTextColor.DARK_GRAY))
        }
        availabilityProblem(player, crate)?.let { lore.add(plain("  $it", NamedTextColor.RED)) }
        if (totalPages > 1) lore.add(plain("  ", NamedTextColor.GRAY).append(GuiStyle.pageLine(page, totalPages)))
        lore.add(Component.empty())
        return GuiStyle.item(Material.BOOK, Component.text(crate.displayName, crateNameColor).decoration(TextDecoration.BOLD, true), lore)
    }

    // --- Mass open ---

    private fun massOpen(player: Player, crateType: String, block: Block) {
        if (activeAnimations.contains(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You already have a crate animation running.", NamedTextColor.RED))
            return
        }

        val crate = openableCrate(player, crateType, massOpen = true) ?: return

        // Anti-dupe: re-validate key at the moment of consumption
        val itemInHand = player.inventory.itemInMainHand
        if (!isKey(itemInHand, crateType)) return

        val keyCount = itemInHand.amount

        // SELECT / SELECT_3 crates normally require the player to pick — mass open falls back to weighted random.
        if (isManualPickMode(crate.mode)) {
            plugin.commsManager.send(
                player,
                Component.text("Mass-opening a ", NamedTextColor.GRAY)
                    .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
                    .append(Component.text(" crate picks rewards randomly (no manual selection).", NamedTextColor.GRAY))
            )
        }

        // Keys never leave the main-hand slot: each one is taken only after its reward is known
        // to fit, so a stop part-way leaves every unopened key exactly where it was and there
        // is nothing to refund.
        val perOpen = rewardsPerOpen(crate)
        val candidates = crate.rewards.map { buildRewardItem(it) }
        val heldSlot = player.inventory.heldItemSlot

        val rewardSummary = mutableMapOf<String, Int>()
        var keysUsed = 0
        for (i in 0 until keyCount) {
            if (player.inventory.heldItemSlot != heldSlot || !isKey(player.inventory.itemInMainHand, crateType)) break
            if (!massOpenNextFits(player, candidates, perOpen)) break

            consumeOneKey(player)
            // One key = one open: a Select 3 crate grants 3 distinct rewards, like its manual pick.
            for (reward in selectDistinctWeightedRewards(crate, perOpen)) {
                deliverMassOpenReward(player, buildRewardItem(reward), "${reward.displayName} x${reward.amount}")
                rewardSummary[reward.displayName] = (rewardSummary[reward.displayName] ?: 0) + reward.amount
            }
            keysUsed++
        }

        if (keysUsed == 0) {
            plugin.commsManager.send(
                player,
                Component.text("You need more inventory space to mass-open this crate — no key was used.", NamedTextColor.RED)
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        val keysRefunded = keyCount - keysUsed

        // Send summary message
        plugin.commsManager.send(
            player,
            Component.text("Opened $keysUsed ", NamedTextColor.GREEN)
                .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
                .append(Component.text(" crate${if (keysUsed == 1) "" else "s"}!", NamedTextColor.GREEN))
        )

        for ((rewardName, totalAmount) in rewardSummary) {
            plugin.commsManager.send(
                player,
                Component.text("  - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(rewardName, TextColor.color(0xFFAA00)))
                    .append(Component.text(" x$totalAmount", NamedTextColor.GRAY))
            )
        }

        if (keysRefunded > 0) {
            plugin.commsManager.send(
                player,
                Component.text("Your inventory filled up — stopped with ", NamedTextColor.YELLOW)
                    .append(Component.text("$keysRefunded ", NamedTextColor.WHITE))
                    .append(Component.text("unopened key${if (keysRefunded == 1) "" else "s"} still in your hand.", NamedTextColor.YELLOW))
            )
        }

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        // Issue #1503: one short burst for the whole mass open, never an animation per key.
        opening.burst(player, block, crate.id)
    }

    // --- Particle task ---

    private fun startParticleTask() {
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (loc in crateLocations) {
                val world = Bukkit.getWorld(loc.world) ?: continue
                val particleLoc = Location(world, loc.x + 0.5, loc.y + 1.5, loc.z + 0.5)

                // Only spawn if players are nearby
                val nearbyPlayers = world.players.filter { it.location.distanceSquared(particleLoc) < 2500 } // 50 blocks
                if (nearbyPlayers.isEmpty()) continue

                val crate = crates[loc.crateType]
                val particle = crate?.idleParticle ?: Particle.END_ROD
                spawnCrateParticle(world, particleLoc, particle, crate?.particleColor, 3, 0.3, 0.5, 0.3, 0.02)
            }
        }, 10L, 10L)
    }

    /**
     * Spawns a crate particle, supplying DustOptions when the particle type
     * requires color data (currently only Particle.DUST). Non-colorable
     * particles are unaffected — this just avoids duplicating the DUST check
     * everywhere a crate particle is spawned.
     */
    private fun spawnCrateParticle(
        world: org.bukkit.World,
        loc: Location,
        particle: Particle,
        color: Color?,
        count: Int,
        offsetX: Double,
        offsetY: Double,
        offsetZ: Double,
        speed: Double
    ) {
        if (particle == Particle.DUST) {
            world.spawnParticle(particle, loc, count, offsetX, offsetY, offsetZ, speed, Particle.DustOptions(color ?: Color.RED, 1.2f))
        } else {
            world.spawnParticle(particle, loc, count, offsetX, offsetY, offsetZ, speed)
        }
    }

    // --- Event handlers ---

    /**
     * Belt-and-suspenders #1: catches the right-click at LOWEST priority,
     * BEFORE any other plugin (claim protection, anti-grief, region plugins,
     * etc.) can cancel it. If the clicked block is a registered crate we
     * immediately mark useInteractedBlock as DENY so vanilla can never open
     * the chest GUI, even if our HIGHEST handler somehow misses.
     *
     * The actual crate-open logic still lives in [onInteract] at HIGHEST so
     * other listeners get a chance to react first — this is purely defensive.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteractEarly(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_BLOCK && event.action != Action.LEFT_CLICK_BLOCK) return
        if (getCrateTypeAt(block) == null) return
        // Suppress vanilla container handling regardless of what other plugins do.
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        // ALSO deny the held-item action so vanilla doesn't try to place the
        // item being held. This was the candle bug: keyMaterial=CANDLE meant
        // every right-click also tried to place a candle on/near the crate,
        // so the crate never opened. Same fix protects buckets, item frames,
        // banners, and any other placeable held item.
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
    }

    /**
     * Belt-and-suspenders #2: catch the case where vanilla (or another plugin)
     * opens the chest GUI at a registered crate location anyway. We close that
     * inventory and run the crate flow as if the player had right-clicked the
     * block. This is the last line of defense if our PlayerInteractEvent
     * handlers somehow lose to a plugin that opens the inventory directly.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST, ignoreCancelled = false)
    fun onInventoryOpen(event: org.bukkit.event.inventory.InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        // Find the block backing the inventory. Double chests report the
        // location of one half — we still match via fuzzy lookup.
        val invHolder = event.inventory.holder
        val block = when (invHolder) {
            is org.bukkit.block.Chest -> invHolder.block
            is org.bukkit.block.Barrel -> invHolder.block
            is org.bukkit.block.EnderChest -> invHolder.block
            is org.bukkit.block.ShulkerBox -> invHolder.block
            is org.bukkit.block.DoubleChest -> (invHolder.leftSide as? org.bukkit.block.Chest)?.block
            else -> null
        } ?: return

        val crateType = getCrateTypeAt(block) ?: return

        if (plugin.config.getBoolean("crates.debug-interactions", false)) {
            plugin.logger.info("[Crates DEBUG] InventoryOpen intercepted at ${block.world.name} ${block.x},${block.y},${block.z} (crate=$crateType) — redirecting ${player.name} to crate GUI")
        }

        // Cancel the vanilla GUI and run the standard right-click flow.
        event.isCancelled = true
        // Defer one tick so the inventory is fully cancelled before we re-open ours.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            handleCrateClick(player, crateType, block, leftClick = false)
        })
    }

    /**
     * Shared crate-open flow used by both the right-click handler and the
     * InventoryOpenEvent interceptor. Encapsulates all the gating (animation
     * lock, key check, free-slot check, key consumption) so both code paths
     * have identical behaviour.
     */
    private fun handleCrateClick(player: Player, crateType: String, block: Block, leftClick: Boolean) {
        // Issue #1472: only crate admins in rotate mode, sneaking with an empty hand.
        if (!leftClick && tryQuickRotate(player, block)) return
        if (leftClick) {
            openPreview(player, crateType)
            return
        }

        if (activeAnimations.contains(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You already have a crate animation running.", NamedTextColor.RED))
            return
        }

        val itemInHand = player.inventory.itemInMainHand
        if (player.isSneaking && !isKey(itemInHand, crateType)) {
            openPreview(player, crateType)
            return
        }
        if (player.isSneaking && isKey(itemInHand, crateType)) {
            if (debounced(player)) return
            massOpen(player, crateType, block)
            return
        }
        if (!isKey(itemInHand, crateType)) {
            val crate = crates[crateType]
            if (crate != null) {
                plugin.commsManager.send(
                    player,
                    Component.text("You need a ", NamedTextColor.RED)
                        .append(Component.text(crate.keyName, TextColor.color(0xFFAA00)))
                        .append(Component.text(" to open this crate.", NamedTextColor.RED))
                )
                plugin.commsManager.send(
                    player,
                    Component.text("Sneak + right-click to preview rewards.", NamedTextColor.GRAY)
                )
            }
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        // The crate must be valid before anything else happens — the key is only taken below.
        val crate = openableCrate(player, crateType, massOpen = false) ?: return

        // Block opening if the inventory has no room for the reward — otherwise it would
        // drop on the ground and risk being lost (void/lava/despawn).
        // Main-hand slot will free up when we consume the key, but that only helps if
        // the player holds exactly one key; otherwise a fresh free slot is required.
        val hasFreeSlot = player.inventory.firstEmpty() != -1 ||
            (itemInHand.amount == 1 && isKey(itemInHand, crateType))
        if (!hasFreeSlot) {
            plugin.commsManager.send(
                player,
                Component.text("Your inventory is full — clear some space before opening.", NamedTextColor.RED)
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        if (debounced(player)) return

        // SELECT / SELECT_3 defer key consumption until the player actually finalizes a pick,
        // so closing the GUI without choosing does not cost a key.
        if (!isManualPickMode(crate.mode)) {
            consumeOneKey(player)
        }
        beginOpening(player, crate, block)
    }

    /**
     * True if [player] started a keyed crate open less than [ACTIVATION_DEBOUNCE_MS] ago, in which
     * case this activation repeats the same interaction and is dropped. Otherwise records this
     * activation and returns false. Called right before a key-spending open.
     */
    private fun debounced(player: Player): Boolean {
        val now = System.currentTimeMillis()
        val last = lastActivation[player.uniqueId]
        if (last != null && now - last < ACTIVATION_DEBOUNCE_MS) return true
        lastActivation[player.uniqueId] = now
        return false
    }

    // HIGHEST so we win against world/claim/etc. plugins that may have already
    // cancelled the click. ignoreCancelled stays false so we still fire and can
    // explicitly suppress vanilla container interaction below.
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST)
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player

        val crateType = getCrateTypeAt(block)

        if (plugin.config.getBoolean("crates.debug-interactions", false)) {
            plugin.logger.info("[Crates DEBUG] ${player.name} ${event.action} ${block.type.name} @ ${block.world.name} ${block.x},${block.y},${block.z} hand=${event.hand} crateType=$crateType cancelled=${event.isCancelled} useBlock=${event.useInteractedBlock()}")
        }
        if (crateType == null) {
            // If the player is holding a crate key and right-clicked something
            // that LOOKS like a crate but isn't registered, give them a clear
            // diagnostic instead of letting vanilla open the chest silently.
            if (event.action == Action.RIGHT_CLICK_BLOCK
                && event.hand == org.bukkit.inventory.EquipmentSlot.HAND
                && isKey(player.inventory.itemInMainHand)
                && isContainerLike(block.type)
            ) {
                val keyType = getKeyType(player.inventory.itemInMainHand)
                plugin.commsManager.send(
                    player,
                    Component.text("This isn't a registered crate.", NamedTextColor.RED)
                )
                if (player.hasPermission("joshymc.crate.admin")) {
                    plugin.commsManager.send(
                        player,
                        Component.text("Admin tip: use ", NamedTextColor.GRAY)
                            .append(Component.text("/crate setlocation ${keyType ?: "<type>"}", NamedTextColor.YELLOW))
                            .append(Component.text(" while looking at the block.", NamedTextColor.GRAY))
                    )
                    plugin.commsManager.send(
                        player,
                        Component.text("Block: ${block.type.name} @ ${block.x}, ${block.y}, ${block.z} in ${block.world.name}", NamedTextColor.DARK_GRAY)
                    )
                }
                player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            }
            return
        }

        // Anti-dupe: only process main hand to prevent double-fire
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        // Left-click crate = preview rewards GUI
        if (event.action == Action.LEFT_CLICK_BLOCK) {
            event.isCancelled = true
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            if (!clickGate.tryEnter(player.uniqueId, Bukkit.getCurrentTick(), leftClick = true)) return
            openPreview(player, crateType)
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        event.isCancelled = true
        // Force-deny vanilla container interaction so the underlying chest /
        // ender chest GUI cannot open even if another plugin tried to allow it.
        event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY)
        // Also deny the held-item action — needed when keyMaterial is a
        // placeable item like CANDLE / BANNER / ITEM_FRAME, otherwise vanilla
        // tries to place the held item instead of opening the crate.
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)

        // The same click already reached the crate through its model hitbox (issue #1451).
        if (!clickGate.tryEnter(player.uniqueId, Bukkit.getCurrentTick(), leftClick = false)) return
        handleCrateClick(player, crateType, block, leftClick = false)
    }

    /**
     * A click on a crate model's hitbox (issue #1451), routed into exactly the same flow as a
     * click on the crate [block] itself: the crate is resolved from the block (the registered
     * location stays the authority), then [handleCrateClick] runs its usual animation lock, key,
     * free-slot, validity and debounce checks. Returns false when [block] is no crate.
     */
    fun handleModelClick(player: Player, block: Block, leftClick: Boolean): Boolean {
        val crateType = getCrateTypeAt(block) ?: return false
        if (plugin.config.getBoolean("crates.debug-interactions", false)) {
            plugin.logger.info("[Crates DEBUG] ${player.name} ${if (leftClick) "left" else "right"}-clicked the model hitbox @ ${block.world.name} ${block.x},${block.y},${block.z} crateType=$crateType")
        }
        if (!clickGate.tryEnter(player.uniqueId, Bukkit.getCurrentTick(), leftClick)) return true
        handleCrateClick(player, crateType, block, leftClick)
        return true
    }

    /** True when [player] already ran a right-click crate action this tick (block or hitbox). */
    fun rightClickedCrateThisTick(player: Player): Boolean =
        clickGate.ranIn(player.uniqueId, Bukkit.getCurrentTick(), leftClick = false)

    @EventHandler
    fun onBlockBreak(event: BlockBreakEvent) {
        val crateType = getCrateTypeAt(event.block)
        if (crateType != null) {
            event.isCancelled = true
        }
    }

    /** Belt-and-suspenders: an in-progress Select 3 pick never survives a disconnect. */
    @EventHandler
    fun onPlayerQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
        select3Sessions.remove(event.player.uniqueId)
        // A running animation stops here; its reward stays in crate_pending_rewards and is
        // delivered when they log back in.
        inFlightRewards.remove(event.player.uniqueId)
        // Issue #1503: its opening animation (if any) ends silently; nothing reveals for it.
        opening.cancel(event.player.uniqueId)
        activeAnimations.remove(event.player.uniqueId)
        lastActivation.remove(event.player.uniqueId)
        clickGate.forget(event.player.uniqueId)
        rotateMode.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Short delay so other join handlers (Mod Mode restore, etc.) settle the inventory first.
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) deliverPendingRewards(player)
        }, 40L)
    }
}
