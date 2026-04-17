package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
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

    enum class CrateMode { RANDOM, SELECT }

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
        val winParticle: Particle = Particle.FIREWORK
    )

    data class CrateLocation(
        val world: String,
        val x: Int,
        val y: Int,
        val z: Int,
        val crateType: String
    )

    // --- State ---

    private val crateKeyKey = NamespacedKey(plugin, "crate_key")
    private val crates = mutableMapOf<String, CrateDef>()
    private val crateLocations = mutableListOf<CrateLocation>()
    private val activeAnimations = mutableSetOf<UUID>()
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
    }

    fun stop() {
        particleTask?.cancel()
        particleTask = null
        activeAnimations.clear()
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

            crates[id] = CrateDef(id, displayName, keyMaterial, keyName, animationGlass, rewards, mode, animationType, idleParticle, winParticle)
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

    fun setCrateMode(crateId: String, mode: CrateMode): Boolean {
        val crate = crates[crateId] ?: return false
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
        return crateLocations.firstOrNull {
            it.world == block.world.name && it.x == block.x && it.y == block.y && it.z == block.z
        }?.crateType
    }

    fun giveKey(player: Player, crateType: String, amount: Int = 1): Boolean {
        val crate = crates[crateType] ?: return false

        val key = ItemStack(crate.keyMaterial, amount)
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

    private fun getKeyType(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(crateKeyKey, PersistentDataType.STRING)
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

        activeAnimations.add(player.uniqueId)

        when (crate.animationType) {
            AnimationType.INSTANT -> openInstant(player, crate)
            AnimationType.PULSE -> openPulse(player, crate)
            AnimationType.SPIN -> openSpin(player, crate)
        }
    }

    // --- SELECT mode GUI ---

    private fun openSelectGui(player: Player, crate: CrateDef) {
        val title = Component.text("Pick a Reward: ")
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val size = when {
            crate.rewards.size <= 7 -> 27
            crate.rewards.size <= 21 -> 45
            else -> 54
        }

        val gui = CustomGui(title, size)

        // Fill with glass border
        val filler = ItemStack(crate.animationGlass)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.border(filler)

        // Place rewards in the center area
        val slots = mutableListOf<Int>()
        val startRow = 1
        val endRow = (size / 9) - 2
        for (row in startRow..endRow) {
            for (col in 1..7) {
                slots.add(row * 9 + col)
            }
        }

        for ((idx, reward) in crate.rewards.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]

            // Use the actual stored item (preserves trims, custom items)
            val item = deserializeItem(reward.itemBase64)
                ?: ItemStack(reward.material, reward.amount.coerceIn(1, 64))
            item.amount = reward.amount.coerceIn(1, 64)
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
                if (reward.enchantments.isNotEmpty()) {
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
            gui.setItem(slot, item) { p, _ ->
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

        base.editMeta { meta ->
            meta.displayName(
                Component.text(reward.displayName, TextColor.color(0xFFAA00))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  x${reward.amount}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
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
                player.world.spawnParticle(crate.winParticle, loc, 30, 0.5, 0.5, 0.5, 0.1)
            }
        }
    }

    fun openPreview(player: Player, crateType: String) {
        val crate = crates[crateType] ?: return

        val title = Component.text(PREVIEW_GUI_TITLE_PREFIX)
            .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
            .decoration(TextDecoration.ITALIC, false)

        val size = when {
            crate.rewards.size <= 7 -> 27
            crate.rewards.size <= 21 -> 45
            else -> 54
        }

        val inv = Bukkit.createInventory(null, size, title)

        // Fill with black glass
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until size) inv.setItem(i, filler.clone())

        // Place rewards in the middle area
        val totalWeight = crate.rewards.sumOf { it.weight }
        val slots = mutableListOf<Int>()
        val startRow = 1
        val endRow = (size / 9) - 2
        for (row in startRow..endRow) {
            for (col in 1..7) {
                slots.add(row * 9 + col)
            }
        }

        for ((idx, reward) in crate.rewards.withIndex()) {
            if (idx >= slots.size) break
            val slot = slots[idx]
            val percentage = (reward.weight.toDouble() / totalWeight * 100).let { "%.1f".format(it) }

            // Use serialized item if present (preserves trims, custom items)
            val item = deserializeItem(reward.itemBase64) ?: ItemStack(reward.material, reward.amount)
            item.amount = reward.amount.coerceIn(1, 64)
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

                if (reward.enchantments.isNotEmpty()) {
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

            inv.setItem(slot, item)
        }

        val gui = CustomGui(title, size, inv)
        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
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

        // Consume all keys
        player.inventory.setItemInMainHand(null)

        // Give rewards directly (skip animation for mass open)
        val rewardSummary = mutableMapOf<String, Int>()
        for (i in 0 until keyCount) {
            val reward = selectWeightedReward(crate)
            val rewardItem = buildRewardItem(reward)
            val leftover = player.inventory.addItem(rewardItem)
            for ((_, item) in leftover) {
                player.world.dropItemNaturally(player.location, item)
            }
            rewardSummary[reward.displayName] = (rewardSummary[reward.displayName] ?: 0) + reward.amount
        }

        // Send summary message
        plugin.commsManager.send(
            player,
            Component.text("Opened $keyCount ", NamedTextColor.GREEN)
                .append(Component.text(crate.displayName, TextColor.color(0x55FFFF)))
                .append(Component.text(" crates!", NamedTextColor.GREEN))
        )

        for ((rewardName, totalAmount) in rewardSummary) {
            plugin.commsManager.send(
                player,
                Component.text("  - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(rewardName, TextColor.color(0xFFAA00)))
                    .append(Component.text(" x$totalAmount", NamedTextColor.GRAY))
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
                world.spawnParticle(particle, particleLoc, 3, 0.3, 0.5, 0.3, 0.02)
            }
        }, 10L, 10L)
    }

    // --- Event handlers ---

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        val block = event.clickedBlock ?: return
        val player = event.player
        val crateType = getCrateTypeAt(block) ?: return

        // Anti-dupe: only process main hand to prevent double-fire
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        // Left-click crate = preview rewards GUI
        if (event.action == Action.LEFT_CLICK_BLOCK) {
            event.isCancelled = true
            openPreview(player, crateType)
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        event.isCancelled = true

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

        // Consume one key
        if (itemInHand.amount > 1) {
            itemInHand.amount -= 1
        } else {
            player.inventory.setItemInMainHand(null)
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
}
