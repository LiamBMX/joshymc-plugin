package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.util.MinecraftColors
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
import org.bukkit.block.Block
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.player.PlayerInteractEvent
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
    }

    /**
     * Shared shape-based reward layout used by BOTH the Preview and Pick-a-Reward
     * GUIs, so the same crate always renders as the same compact formation in both
     * places instead of a plain row-by-row fill.
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
        val itemBase64: String? = null
    )

    enum class CrateMode { RANDOM, SELECT, SELECT_3 }

    enum class AnimationType { SPIN, PULSE, INSTANT }

    data class CrateDef(
        val id: String,
        val displayName: String,
        val keyMaterial: Material,
        val keyName: String,
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
        val particleColor: Color? = null
    )

    data class CrateLocation(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val crateType: String
    )

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
    private var particleTask: BukkitTask? = null
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

        // Save default crates.yml if missing
        cratesFile = plugin.configFile("crates.yml")
        if (!cratesFile.exists()) {
            plugin.saveResource("crates.yml", false)
        }
        cratesConfig = YamlConfiguration.loadConfiguration(cratesFile)

        loadCrates()
        loadLocations()
        startParticleTask()

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
        particleTask?.cancel()
        particleTask = null
        activeAnimations.clear()
        select3Sessions.clear()
    }

    // --- Config loading ---

    private fun loadCrates() {
        crates.clear()
        val section = cratesConfig.getConfigurationSection("crates") ?: return

        for (id in section.getKeys(false)) {
            val crateSection = section.getConfigurationSection(id) ?: continue
            val displayName = crateSection.getString("display-name", id) ?: id
            val keyMaterialStr = crateSection.getString("key-material", "TRIPWIRE_HOOK") ?: "TRIPWIRE_HOOK"
            val keyMaterial = try { Material.valueOf(keyMaterialStr) } catch (_: Exception) { Material.TRIPWIRE_HOOK }
            val keyName = crateSection.getString("key-name", "$displayName Key") ?: "$displayName Key"
            val glassStr = crateSection.getString("animation-glass", "WHITE_STAINED_GLASS_PANE") ?: "WHITE_STAINED_GLASS_PANE"
            val animationGlass = try { Material.valueOf(glassStr) } catch (_: Exception) { Material.WHITE_STAINED_GLASS_PANE }

            val modeStr = crateSection.getString("mode", "random") ?: "random"
            val mode = try { CrateMode.valueOf(modeStr.uppercase()) } catch (_: Exception) { CrateMode.RANDOM }

            val animTypeStr = crateSection.getString("animation-type", "spin") ?: "spin"
            val animationType = try { AnimationType.valueOf(animTypeStr.uppercase()) } catch (_: Exception) { AnimationType.SPIN }

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
                    val mat = try { Material.valueOf(matStr) } catch (_: Exception) { Material.STONE }
                    val amount = rewardSection.getInt("amount", 1)
                    val weight = rewardSection.getInt("weight", 1)
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

                    val itemBase64 = rewardSection.getString("item-base64")?.takeIf { it.isNotBlank() }
                    rewards.add(CrateReward(mat, amount, weight, rewardDisplayName, enchantments, itemBase64))
                }
            }

            crates[id] = CrateDef(id, displayName, keyMaterial, keyName, animationGlass, rewards, mode, animationType, idleParticle, winParticle, particleColor)
        }
    }

    private fun loadLocations() {
        crateLocations.clear()
        val rows = plugin.databaseManager.query(
            "SELECT world, x, y, z, crate_type FROM crate_locations"
        ) { rs ->
            CrateLocation(
                rs.getString("world"),
                rs.getInt("x"),
                rs.getInt("y"),
                rs.getInt("z"),
                rs.getString("crate_type")
            )
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
        crates[id] = CrateDef(id, displayName, Material.TRIPWIRE_HOOK, "$displayName Key", Material.WHITE_STAINED_GLASS_PANE, emptyList(),
            CrateMode.RANDOM, AnimationType.SPIN, Particle.END_ROD, Particle.FIREWORK)
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

    fun setCrateKeyMaterial(crateId: String, material: Material, keyName: String): Boolean {
        val crate = crates[crateId] ?: return false
        crates[crateId] = crate.copy(keyMaterial = material, keyName = keyName)
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
            cratesConfig.set("$path.animation-glass", crate.animationGlass.name)
            cratesConfig.set("$path.mode", crate.mode.name.lowercase())
            cratesConfig.set("$path.animation-type", crate.animationType.name.lowercase())
            cratesConfig.set("$path.idle-particle", crate.idleParticle.name)
            cratesConfig.set("$path.win-particle", crate.winParticle.name)
            val colorId = MinecraftColors.ALL.firstOrNull { it.color == crate.particleColor }?.id
            cratesConfig.set("$path.particle-color", colorId)

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
                if (reward.itemBase64 != null) {
                    cratesConfig.set("$rewardPath.item-base64", reward.itemBase64)
                }
            }
        }
        cratesConfig.save(cratesFile)
    }

    fun setCrateLocation(block: Block, crateType: String): Boolean {
        if (!crates.containsKey(crateType)) return false

        // Remove existing location at this block if any
        removeCrateLocation(block)

        val loc = CrateLocation(block.world.name, block.x, block.y, block.z, crateType)
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO crate_locations (world, x, y, z, crate_type) VALUES (?, ?, ?, ?, ?)",
            loc.world, loc.x, loc.y, loc.z, loc.crateType
        )
        crateLocations.add(loc)
        return true
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
        }
        return removed
    }

    fun getCrateTypeAt(block: Block): String? {
        // Exact match first — this is the common case.
        val exact = crateLocations.firstOrNull {
            it.world == block.world.name && it.x == block.x && it.y == block.y && it.z == block.z
        }?.crateType
        if (exact != null) return exact

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
            if (match != null) return match.crateType
        }
        return null
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
        }
        return key
    }

    fun giveKey(player: Player, crateType: String, amount: Int = 1): Boolean {
        val key = createKeyStack(crateType, amount) ?: return false
        val leftover = player.inventory.addItem(key)
        for ((_, item) in leftover) {
            player.world.dropItemNaturally(player.location, item)
        }
        return true
    }

    fun isKey(item: ItemStack?, crateType: String? = null): Boolean {
        if (item == null || item.type == Material.AIR) return false
        val meta = item.itemMeta ?: return false
        val storedType = meta.persistentDataContainer.get(crateKeyKey, PersistentDataType.STRING) ?: return false
        return crateType == null || storedType == crateType
    }

    /** Returns the crate type tagged on the key item, or null if it's not a key. */
    fun getKeyType(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(crateKeyKey, PersistentDataType.STRING)
    }

    /**
     * Consume one key from the player's main hand, but only if the crate is in
     * RANDOM mode. SELECT-mode crates consume the key when the player picks a
     * reward, so calling this for them would double-charge.
     */
    fun consumeOneKeyIfAuto(player: Player, crateType: String) {
        if (!isManualPickMode(crates[crateType]?.mode ?: CrateMode.RANDOM)) {
            consumeOneKey(player)
        }
    }

    private fun consumeOneKey(player: Player) {
        val itemInHand = player.inventory.itemInMainHand
        if (itemInHand.amount > 1) {
            itemInHand.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
        }
    }

    fun openCrate(player: Player, crateType: String, block: Block) {
        val crate = crates[crateType] ?: return

        if (crate.rewards.isEmpty()) {
            plugin.commsManager.send(player, Component.text("This crate has no rewards configured.", NamedTextColor.RED))
            return
        }

        if (activeAnimations.contains(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You already have a crate animation running.", NamedTextColor.RED))
            return
        }

        // SELECT mode: open a pick GUI instead of animating
        if (crate.mode == CrateMode.SELECT) {
            openSelectGui(player, crate)
            return
        }

        // SELECT_3 mode: open the multi-pick GUI instead of animating
        if (crate.mode == CrateMode.SELECT_3) {
            if (crate.rewards.size < 3) {
                plugin.commsManager.send(player, Component.text("This crate is misconfigured for Select 3 (needs at least 3 rewards) — contact an admin.", NamedTextColor.RED))
                return
            }
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

    // --- SELECT mode GUI ---

    private fun openSelectGui(player: Player, crate: CrateDef, page: Int = 0) {
        val title = Component.text("Pick a Reward: ")
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val totalPages = CrateLayout.pageCount(crate.rewards.size)
        val safePage = page.coerceIn(0, totalPages - 1)
        val pageStart = safePage * CrateLayout.PAGE_CAPACITY
        val pageRewards = crate.rewards.drop(pageStart).take(CrateLayout.PAGE_CAPACITY)

        val size = 45
        val gui = CustomGui(title, size)

        // Fill everything with glass so the formation's empty gaps look intentional.
        val filler = ItemStack(crate.animationGlass)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until size) gui.inventory.setItem(i, filler.clone())

        val positions = CrateLayout.positions(pageRewards.size)
        gui.border(filler)

        // Place rewards centered inside the inner 7-wide content area, balanced across rows.
        val startRow = 1
        val endRow = (size / 9) - 2
        val contentRows = (startRow..endRow).map { row -> (1..7).map { col -> row * 9 + col } }

        val rewardCount = crate.rewards.size
        val rowsNeeded = if (rewardCount == 0) 0 else (rewardCount + 6) / 7
        val verticalOffset = (contentRows.size - rowsNeeded).coerceAtLeast(0) / 2
        val rowSizes = getBalancedRowSizes(rewardCount, rowsNeeded)

        val slots = mutableListOf<Int>()
        for (i in 0 until rowsNeeded) {
            val rowIndex = verticalOffset + i
            if (rowIndex >= contentRows.size) break
            slots.addAll(getCenteredRewardSlots(contentRows[rowIndex], rowSizes[i]))
        }

        for ((idx, reward) in pageRewards.withIndex()) {
            if (idx >= positions.size) break
            val (row, col) = positions[idx]
            val slot = (row + 1) * 9 + (col + 1)

            // Use the actual stored item (preserves trims, custom items)
            val item = deserializeItem(reward.itemBase64)
                ?: ItemStack(reward.material, reward.amount.coerceIn(1, 64))
            item.amount = reward.amount.coerceIn(1, 64)
            val existingLore = item.itemMeta?.lore() ?: emptyList()
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(reward.displayName, TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text("  Amount: ", NamedTextColor.GRAY)
                        .append(Component.text("${reward.amount}", NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                )
                if (existingLore.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.addAll(existingLore)
                } else if (reward.enchantments.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.add(Component.text("  Enchantments:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    for ((ench, level) in reward.enchantments) {
                        lore.add(
                            Component.text("  - ${ench.key.key.replace("_", " ")} $level", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                }
                lore.add(Component.empty())
                lore.add(Component.text("  Click to select!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
                meta.lore(lore)
            }

            val rewardRef = reward
            val crateId = crate.id
            gui.setItem(slot, item) { p, _ ->
                // Re-validate and consume key at click time (anti-dupe + anti-refund abuse)
                val keyInHand = p.inventory.itemInMainHand
                if (!isKey(keyInHand, crateId)) {
                    p.closeInventory()
                    plugin.commsManager.send(
                        p,
                        Component.text("You no longer have a ", NamedTextColor.RED)
                            .append(Component.text(crate.keyName, TextColor.color(0xFFAA00)))
                            .append(Component.text(".", NamedTextColor.RED))
                    )
                    return@setItem
                }
                // Re-check inventory space — the player may have filled it while the GUI was open.
                // Main-hand slot frees up only when the key stack is size 1.
                val hasFreeSlot = p.inventory.firstEmpty() != -1 || keyInHand.amount == 1
                if (!hasFreeSlot) {
                    p.closeInventory()
                    plugin.commsManager.send(
                        p,
                        Component.text("Your inventory is full — clear some space and try again.", NamedTextColor.RED)
                    )
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
                }
                consumeOneKey(p)
                p.closeInventory()
                val rewardItem = buildRewardItem(rewardRef)
                val leftover = p.inventory.addItem(rewardItem)
                for ((_, drop) in leftover) {
                    p.world.dropItemNaturally(p.location, drop)
                }
                spawnWinParticles(p, crate)
                p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                plugin.commsManager.send(
                    p,
                    Component.text("You selected ", NamedTextColor.GREEN)
                        .append(Component.text(rewardRef.displayName, TextColor.color(0xFFAA00)))
                        .append(Component.text(" x${rewardRef.amount}", NamedTextColor.GREEN))
                        .append(Component.text("!", NamedTextColor.GREEN))
                )
            }
        }

        if (totalPages > 1) {
            if (safePage > 0) {
                gui.setItem(39, navButton("« Previous Page")) { p, _ -> openSelectGui(p, crate, safePage - 1) }
            }
            gui.setItem(40, pageIndicator(safePage + 1, totalPages))
            if (safePage < totalPages - 1) {
                gui.setItem(41, navButton("Next Page »")) { p, _ -> openSelectGui(p, crate, safePage + 1) }
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
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
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val totalPages = CrateLayout.pageCount(crate.rewards.size)
        val safePage = page.coerceIn(0, totalPages - 1)
        val pageStart = safePage * CrateLayout.PAGE_CAPACITY
        val pageRewards = crate.rewards.drop(pageStart).take(CrateLayout.PAGE_CAPACITY)

        val size = 45
        val gui = CustomGui(title, size)
        gui.onClose = { p -> select3Sessions.remove(p.uniqueId) }

        val filler = ItemStack(crate.animationGlass)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until size) gui.inventory.setItem(i, filler.clone())
        gui.border(filler)

        val progress = ItemStack(Material.NAME_TAG)
        progress.editMeta { meta ->
            meta.displayName(
                Component.text("Selected: ${session.selectedIndices.size}/3", NamedTextColor.YELLOW)
                    .decoration(TextDecoration.ITALIC, false)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Pick 3 different rewards.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  Click a selected reward again to deselect.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(4, progress)

        val positions = CrateLayout.positions(pageRewards.size)

        for ((idxOnPage, reward) in pageRewards.withIndex()) {
            if (idxOnPage >= positions.size) break
            val globalIndex = pageStart + idxOnPage
            val (row, col) = positions[idxOnPage]
            val slot = (row + 1) * 9 + (col + 1)
            val isSelected = session.selectedIndices.contains(globalIndex)

            val item = deserializeItem(reward.itemBase64)
                ?: ItemStack(reward.material, reward.amount.coerceIn(1, 64))
            item.amount = reward.amount.coerceIn(1, 64)
            val existingLore = item.itemMeta?.lore() ?: emptyList()
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(reward.displayName, TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text("  Amount: ", NamedTextColor.GRAY)
                        .append(Component.text("${reward.amount}", NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                )
                if (existingLore.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.addAll(existingLore)
                } else if (reward.enchantments.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.add(Component.text("  Enchantments:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    for ((ench, level) in reward.enchantments) {
                        lore.add(
                            Component.text("  - ${ench.key.key.replace("_", " ")} $level", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                }
                lore.add(Component.empty())
                if (isSelected) {
                    lore.add(Component.text("  Selected", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                    lore.add(Component.text("  Click to deselect", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                } else {
                    lore.add(Component.text("  Click to select!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false))
                }
                lore.add(Component.empty())
                meta.lore(lore)
                if (isSelected) meta.setEnchantmentGlintOverride(true)
            }

            gui.setItem(slot, item) { p, _ ->
                val sess = select3Sessions[p.uniqueId]
                if (sess == null || sess.crateId != crate.id) {
                    p.closeInventory()
                    plugin.commsManager.send(p, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
                    return@setItem
                }
                if (sess.selectedIndices.contains(globalIndex)) {
                    sess.selectedIndices.remove(globalIndex)
                    p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.0f)
                    openSelect3Gui(p, crate, safePage)
                    return@setItem
                }
                if (sess.selectedIndices.size >= 3) return@setItem
                sess.selectedIndices.add(globalIndex)
                p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.6f, 1.3f)
                if (sess.selectedIndices.size >= 3) {
                    openSelect3Confirm(p, crate)
                } else {
                    openSelect3Gui(p, crate, safePage)
                }
            }
        }

        if (totalPages > 1) {
            if (safePage > 0) {
                gui.setItem(39, navButton("« Previous Page")) { p, _ -> openSelect3Gui(p, crate, safePage - 1) }
            }
            gui.setItem(40, pageIndicator(safePage + 1, totalPages))
            if (safePage < totalPages - 1) {
                gui.setItem(41, navButton("Next Page »")) { p, _ -> openSelect3Gui(p, crate, safePage + 1) }
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /** Final review step for Select 3 — shows the 3 chosen rewards and requires Confirm before payout. */
    private fun openSelect3Confirm(player: Player, crate: CrateDef) {
        val session = select3Sessions[player.uniqueId]
        if (session == null || session.crateId != crate.id || session.selectedIndices.size != 3) {
            select3Sessions.remove(player.uniqueId)
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
            return
        }
        val chosen = session.selectedIndices.mapNotNull { crate.rewards.getOrNull(it) }
        if (chosen.size != 3) {
            select3Sessions.remove(player.uniqueId)
            player.closeInventory()
            plugin.commsManager.send(player, Component.text("This crate's rewards changed — please reopen and try again.", NamedTextColor.RED))
            return
        }

        val title = Component.text("Confirm Selection: ")
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, 27)
        gui.onClose = { p -> select3Sessions.remove(p.uniqueId) }

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until 27) gui.inventory.setItem(i, filler.clone())

        val displaySlots = listOf(11, 13, 15)
        for ((i, reward) in chosen.withIndex()) {
            gui.inventory.setItem(displaySlots[i], buildRewardDisplay(reward))
        }

        val confirmItem = ItemStack(Material.EMERALD_BLOCK)
        confirmItem.editMeta { meta ->
            meta.displayName(
                Component.text("Confirm", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Consume 1 key and claim these 3 rewards.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(20, confirmItem) { p, _ ->
            val currentSession = select3Sessions[p.uniqueId]
            if (currentSession == null || currentSession.crateId != crate.id || currentSession.selectedIndices.size != 3) {
                select3Sessions.remove(p.uniqueId)
                p.closeInventory()
                plugin.commsManager.send(p, Component.text("Your selection session expired — try opening the crate again.", NamedTextColor.RED))
                return@setItem
            }
            val rewardsToGrant = currentSession.selectedIndices.mapNotNull { crate.rewards.getOrNull(it) }
            if (rewardsToGrant.size != 3) {
                select3Sessions.remove(p.uniqueId)
                p.closeInventory()
                plugin.commsManager.send(p, Component.text("This crate's rewards changed — please reopen and try again.", NamedTextColor.RED))
                return@setItem
            }

            val keyInHand = p.inventory.itemInMainHand
            if (!isKey(keyInHand, crate.id)) {
                select3Sessions.remove(p.uniqueId)
                p.closeInventory()
                plugin.commsManager.send(
                    p,
                    Component.text("You no longer have a ", NamedTextColor.RED)
                        .append(Component.text(crate.keyName, TextColor.color(0xFFAA00)))
                        .append(Component.text(".", NamedTextColor.RED))
                )
                return@setItem
            }
            val hasFreeSlot = p.inventory.firstEmpty() != -1 || keyInHand.amount == 1
            if (!hasFreeSlot) {
                p.closeInventory()
                plugin.commsManager.send(
                    p,
                    Component.text("Your inventory is full — clear some space and try again.", NamedTextColor.RED)
                )
                p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                return@setItem
            }

            consumeOneKey(p)
            select3Sessions.remove(p.uniqueId)
            p.closeInventory()

            for (reward in rewardsToGrant) {
                val rewardItem = buildRewardItem(reward)
                val leftover = p.inventory.addItem(rewardItem)
                for ((_, drop) in leftover) p.world.dropItemNaturally(p.location, drop)
            }
            spawnWinParticles(p, crate)
            p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            plugin.commsManager.send(p, Component.text("You selected:", NamedTextColor.GREEN))
            for (reward in rewardsToGrant) {
                plugin.commsManager.send(
                    p,
                    Component.text("  - ", NamedTextColor.DARK_GRAY)
                        .append(Component.text(reward.displayName, TextColor.color(0xFFAA00)))
                        .append(Component.text(" x${reward.amount}", NamedTextColor.GREEN))
                )
            }
        }

        val cancelItem = ItemStack(Material.BARRIER)
        cancelItem.editMeta { meta ->
            meta.displayName(
                Component.text("Cancel", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Discard this selection — no key used.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(24, cancelItem) { p, _ ->
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
        val leftover = player.inventory.addItem(rewardItem)
        for ((_, item) in leftover) {
            player.world.dropItemNaturally(player.location, item)
        }
        spawnWinParticles(player, crate)
        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        plugin.commsManager.send(
            player,
            Component.text("You won ", NamedTextColor.GREEN)
                .append(Component.text(reward.displayName, TextColor.color(0xFFAA00)))
                .append(Component.text(" x${reward.amount}", NamedTextColor.GREEN))
                .append(Component.text("!", NamedTextColor.GREEN))
        )
        activeAnimations.remove(player.uniqueId)
    }

    // --- PULSE animation ---

    private fun openPulse(player: Player, crate: CrateDef) {
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
            gui.inventory.setItem(10 + i, buildRewardDisplay(reward))
        }

        plugin.guiManager.open(player, gui)

        // The winner is pre-selected
        val winnerReward = selectWeightedReward(crate)

        schedulePulseStep(player, gui.inventory, crate, displayRewards.toMutableList(), winnerReward, 0, 12)
    }

    private fun schedulePulseStep(
        player: Player,
        inv: org.bukkit.inventory.Inventory,
        crate: CrateDef,
        displayRewards: MutableList<CrateReward>,
        winner: CrateReward,
        step: Int,
        maxSteps: Int
    ) {
        val delay = when {
            step < maxSteps * 0.4 -> 4L
            step < maxSteps * 0.7 -> 8L
            else -> 12L
        }

        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            if (!activeAnimations.contains(player.uniqueId) || !player.isOnline) {
                activeAnimations.remove(player.uniqueId)
                return@scheduleSyncDelayedTask
            }

            val glass = ItemStack(crate.animationGlass)
            glass.editMeta { it.displayName(Component.empty()) }

            // Each step, blank out one of the remaining non-center slots
            val activeSlots = (10..16).filter { inv.getItem(it)?.type != crate.animationGlass }
            val centerSlot = 13

            if (step >= maxSteps || activeSlots.size <= 1) {
                // Final reveal — show the winner in center
                inv.setItem(centerSlot, buildRewardDisplay(winner))
                player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 2.0f)

                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                    if (!player.isOnline) { activeAnimations.remove(player.uniqueId); return@scheduleSyncDelayedTask }
                    spawnWinParticles(player, crate)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
                    val rewardItem = buildRewardItem(winner)
                    val leftover = player.inventory.addItem(rewardItem)
                    for ((_, item) in leftover) player.world.dropItemNaturally(player.location, item)
                    plugin.commsManager.send(player,
                        Component.text("You won ", NamedTextColor.GREEN)
                            .append(Component.text(winner.displayName, TextColor.color(0xFFAA00)))
                            .append(Component.text(" x${winner.amount}!", NamedTextColor.GREEN)))

                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                        activeAnimations.remove(player.uniqueId)
                        if (player.isOnline) player.closeInventory()
                    }, 40L)
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

            schedulePulseStep(player, inv, crate, displayRewards, winner, step + 1, maxSteps)
        }, delay)
    }

    // --- SPIN animation (original) ---

    private fun openSpin(player: Player, crate: CrateDef) {
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

        // Fill middle reward slots with random rewards initially
        for (slot in 10..16) {
            gui.inventory.setItem(slot, buildRewardDisplay(selectWeightedReward(crate)))
        }

        plugin.guiManager.open(player, gui)

        scheduleAnimationStep(player, gui.inventory, crate, 2L, 0, 40)
    }

    private fun scheduleAnimationStep(
        player: Player,
        inv: org.bukkit.inventory.Inventory,
        crate: CrateDef,
        delay: Long,
        step: Int,
        maxSteps: Int
    ) {
        Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
            if (!activeAnimations.contains(player.uniqueId)) return@scheduleSyncDelayedTask
            if (!player.isOnline) {
                activeAnimations.remove(player.uniqueId)
                return@scheduleSyncDelayedTask
            }

            // Shift rewards left: move 11->10, 12->11, ..., 16->15, new->16
            for (slot in 10..15) {
                inv.setItem(slot, inv.getItem(slot + 1))
            }
            inv.setItem(16, buildRewardDisplay(selectWeightedReward(crate)))

            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 0.5f, 1.0f + (step.toFloat() / maxSteps))

            val nextStep = step + 1

            if (nextStep >= maxSteps) {
                // Animation complete - center slot 13 is the winner
                val winnerItem = inv.getItem(13)
                val winnerReward = findRewardForDisplay(crate, winnerItem)

                Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                    if (!player.isOnline) {
                        activeAnimations.remove(player.uniqueId)
                        return@scheduleSyncDelayedTask
                    }

                    spawnWinParticles(player, crate)
                    player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)

                    // Give reward
                    if (winnerReward != null) {
                        val rewardItem = buildRewardItem(winnerReward)
                        val leftover = player.inventory.addItem(rewardItem)
                        for ((_, item) in leftover) {
                            player.world.dropItemNaturally(player.location, item)
                        }

                        plugin.commsManager.send(
                            player,
                            Component.text("You won ", NamedTextColor.GREEN)
                                .append(Component.text(winnerReward.displayName, TextColor.color(0xFFAA00)))
                                .append(Component.text(" x${winnerReward.amount}", NamedTextColor.GREEN))
                                .append(Component.text("!", NamedTextColor.GREEN))
                        )
                    }

                    // Close after short delay
                    Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, {
                        activeAnimations.remove(player.uniqueId)
                        if (player.isOnline) player.closeInventory()
                    }, 40L)
                }, 10L)
            } else {
                // Calculate next delay - speed decreases (delay increases) as we go
                val newDelay = when {
                    nextStep < maxSteps * 0.5 -> 2L
                    nextStep < maxSteps * 0.7 -> 3L
                    nextStep < maxSteps * 0.85 -> 5L
                    else -> 8L
                }

                scheduleAnimationStep(player, inv, crate, newDelay, nextStep, maxSteps)
            }
        }, delay)
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

    private fun buildRewardDisplay(reward: CrateReward): ItemStack {
        // If we have a serialized item (custom items, trims), use it as the base
        // and overwrite the display name/lore for clarity in the GUI.
        val base = deserializeItem(reward.itemBase64) ?: ItemStack(reward.material, reward.amount)
        if (base.amount != reward.amount) base.amount = reward.amount

        val existingLore = base.itemMeta?.lore() ?: emptyList()

        base.editMeta { meta ->
            meta.displayName(
                Component.text(reward.displayName, TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(Component.text("  x${reward.amount}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            if (existingLore.isNotEmpty()) {
                lore.add(Component.empty())
                lore.addAll(existingLore)
            }
            lore.add(Component.empty())
            meta.lore(lore)
        }
        return base
    }

    private fun buildRewardItem(reward: CrateReward): ItemStack {
        // Prefer the serialized item if available — preserves trims, custom items, PDC, NBT
        val serialized = deserializeItem(reward.itemBase64)
        if (serialized != null) {
            serialized.amount = reward.amount
            return serialized
        }

        val item = ItemStack(reward.material, reward.amount)
        if (reward.enchantments.isNotEmpty()) {
            item.editMeta { meta ->
                for ((ench, level) in reward.enchantments) {
                    meta.addEnchant(ench, level, true)
                }
            }
        }
        return item
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

    private fun findRewardForDisplay(crate: CrateDef, displayItem: ItemStack?): CrateReward? {
        if (displayItem == null) return crate.rewards.firstOrNull()
        // Match by display name stored in lore to handle duplicate materials
        val displayName = displayItem.itemMeta?.displayName()
        if (displayName != null) {
            val plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(displayName)
            val match = crate.rewards.firstOrNull { it.displayName == plain }
            if (match != null) return match
        }
        return crate.rewards.firstOrNull { it.material == displayItem.type }
            ?: crate.rewards.firstOrNull()
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

    fun openPreview(player: Player, crateType: String, page: Int = 0) {
        val crate = crates[crateType] ?: return

        val title = Component.text(PREVIEW_GUI_TITLE_PREFIX)
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val totalPages = CrateLayout.pageCount(crate.rewards.size)
        val safePage = page.coerceIn(0, totalPages - 1)
        val pageStart = safePage * CrateLayout.PAGE_CAPACITY
        val pageRewards = crate.rewards.drop(pageStart).take(CrateLayout.PAGE_CAPACITY)

        val size = 45
        val gui = CustomGui(title, size)

        // Fill with black glass
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until size) gui.inventory.setItem(i, filler.clone())

        // Place rewards in a compact, centered shape-based formation.
        val totalWeight = crate.rewards.sumOf { it.weight }
        val positions = CrateLayout.positions(pageRewards.size)

        for ((idx, reward) in pageRewards.withIndex()) {
            if (idx >= positions.size) break
            val (row, col) = positions[idx]
            val slot = (row + 1) * 9 + (col + 1)
            val percentage = (reward.weight.toDouble() / totalWeight * 100).let { "%.1f".format(it) }

            // Use serialized item if present (preserves trims, custom items)
            val item = deserializeItem(reward.itemBase64) ?: ItemStack(reward.material, reward.amount)
            item.amount = reward.amount.coerceIn(1, 64)
            val existingLore = item.itemMeta?.lore() ?: emptyList()
            item.editMeta { meta ->
                meta.displayName(
                    Component.text(reward.displayName, TextColor.color(0xFFAA00))
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )

                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(
                    Component.text("  Amount: ", NamedTextColor.GRAY)
                        .append(Component.text("${reward.amount}", NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false)
                )
                lore.add(
                    Component.text("  Chance: ", NamedTextColor.GRAY)
                        .append(Component.text("$percentage%", NamedTextColor.YELLOW))
                        .decoration(TextDecoration.ITALIC, false)
                )

                if (existingLore.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.addAll(existingLore)
                } else if (reward.enchantments.isNotEmpty()) {
                    lore.add(Component.empty())
                    lore.add(Component.text("  Enchantments:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                    for ((ench, level) in reward.enchantments) {
                        val enchName = ench.key.key.replace("_", " ")
                        lore.add(
                            Component.text("  - $enchName $level", NamedTextColor.DARK_GRAY)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                }

                lore.add(Component.empty())
                meta.lore(lore)
            }

            gui.inventory.setItem(slot, item)
        }

        if (totalPages > 1) {
            if (safePage > 0) {
                gui.setItem(39, navButton("« Previous Page")) { p, _ -> openPreview(p, crateType, safePage - 1) }
            }
            gui.setItem(40, pageIndicator(safePage + 1, totalPages))
            if (safePage < totalPages - 1) {
                gui.setItem(41, navButton("Next Page »")) { p, _ -> openPreview(p, crateType, safePage + 1) }
            }
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /**
     * Maps [count] rewards onto [rowSlots] (the 7 inner, non-glass slots of one content row,
     * left to right) so the row reads as visually centered/symmetrical.
     */
    private fun getCenteredRewardSlots(rowSlots: List<Int>, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        if (count >= 7) return rowSlots.take(7)
        val a = rowSlots[0]; val b = rowSlots[1]; val c = rowSlots[2]; val d = rowSlots[3]
        val e = rowSlots[4]; val f = rowSlots[5]; val g = rowSlots[6]
        return when (count) {
            1 -> listOf(d)
            2 -> listOf(c, e)
            3 -> listOf(c, d, e)
            4 -> listOf(b, c, e, f)
            5 -> listOf(b, c, d, e, f)
            6 -> listOf(a, b, c, e, f, g)
            else -> rowSlots.take(count)
        }
    }

    /**
     * Splits [count] items across [rows] rows as evenly as possible, front-loading the
     * remainder onto the earlier rows (e.g. 9 across 2 rows -> [5, 4]) instead of always
     * filling each row to its 7-item max before spilling into the next.
     */
    private fun getBalancedRowSizes(count: Int, rows: Int): List<Int> {
        if (rows <= 0) return emptyList()
        val base = count / rows
        val extra = count % rows
        return (0 until rows).map { i -> if (i < extra) base + 1 else base }
    }

    // --- Mass open ---

    private fun massOpen(player: Player, crateType: String, block: Block) {
        val crate = crates[crateType] ?: return

        if (crate.rewards.isEmpty()) {
            plugin.commsManager.send(player, Component.text("This crate has no rewards configured.", NamedTextColor.RED))
            return
        }

        if (activeAnimations.contains(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You already have a crate animation running.", NamedTextColor.RED))
            return
        }

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

        // Require at least one free slot before we even start, so the first reward has somewhere to land.
        if (player.inventory.firstEmpty() == -1) {
            plugin.commsManager.send(
                player,
                Component.text("Your inventory is full — clear some space before mass-opening.", NamedTextColor.RED)
            )
            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
            return
        }

        // Consume keys incrementally so we can refund any we can't use.
        player.inventory.setItemInMainHand(null)

        val rewardSummary = mutableMapOf<String, Int>()
        var keysUsed = 0
        for (i in 0 until keyCount) {
            // Stop if the inventory has no room for another reward.
            // (Partial stacks can still top up existing stacks, but firstEmpty == -1 is our hard stop.)
            if (player.inventory.firstEmpty() == -1) break

            val reward = selectWeightedReward(crate)
            val rewardItem = buildRewardItem(reward)
            val leftover = player.inventory.addItem(rewardItem)
            for ((_, item) in leftover) {
                player.world.dropItemNaturally(player.location, item)
            }
            rewardSummary[reward.displayName] = (rewardSummary[reward.displayName] ?: 0) + reward.amount
            keysUsed++
        }

        val keysRefunded = keyCount - keysUsed
        if (keysRefunded > 0) {
            // Main hand is empty after setItemInMainHand(null), so giveKey is guaranteed a slot.
            giveKey(player, crateType, keysRefunded)
        }

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
                Component.text("Your inventory filled up — ", NamedTextColor.YELLOW)
                    .append(Component.text("$keysRefunded ", NamedTextColor.WHITE))
                    .append(Component.text("key${if (keysRefunded == 1) "" else "s"} returned.", NamedTextColor.YELLOW))
            )
        }

        player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
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

        if (!isManualPickMode(crates[crateType]?.mode ?: CrateMode.RANDOM)) {
            consumeOneKey(player)
        }
        openCrate(player, crateType, block)
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

        if (activeAnimations.contains(player.uniqueId)) {
            plugin.commsManager.send(player, Component.text("You already have a crate animation running.", NamedTextColor.RED))
            return
        }

        val itemInHand = player.inventory.itemInMainHand

        // Sneak + right-click WITHOUT key = preview
        if (player.isSneaking && !isKey(itemInHand, crateType)) {
            openPreview(player, crateType)
            return
        }

        // Sneak + right-click WITH key = mass open
        if (player.isSneaking && isKey(itemInHand, crateType)) {
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

        // SELECT / SELECT_3 defer key consumption until the player actually finalizes a pick,
        // so closing the GUI without choosing does not cost a key.
        if (!isManualPickMode(crates[crateType]?.mode ?: CrateMode.RANDOM)) {
            consumeOneKey(player)
        }

        openCrate(player, crateType, block)
    }

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
    }
}
