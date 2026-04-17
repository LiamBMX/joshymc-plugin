package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import org.bukkit.Bukkit
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

class FishingManager(private val plugin: Joshymc) : Listener {

    // ── Rarity System ───────────────────────────────────────────────────

    enum class FishRarity(
        val displayName: String,
        val color: String,
        val weight: Double,
        val minWeight: Double,
        val maxWeight: Double
    ) {
        COMMON("Common", "&7", 60.0, 0.1, 5.0),
        UNCOMMON("Uncommon", "&a", 22.0, 1.0, 10.0),
        RARE("Rare", "&9", 10.0, 3.0, 20.0),
        EPIC("Epic", "&5", 5.0, 5.0, 35.0),
        LEGENDARY("Legendary", "&6", 2.5, 10.0, 50.0),
        MYTHICAL("Mythical", "&c&l", 0.5, 20.0, 100.0)
    }

    // ── Custom Fish Definition ──────────────────────────────────────────

    data class CustomFish(
        val id: String,
        val name: String,
        val rarity: FishRarity,
        val material: Material,
        val biomes: Set<String>? = null
    )

    // ── PDC Keys ────────────────────────────────────────────────────────

    private val keyFishId = NamespacedKey(plugin, "fish_id")
    private val keyFishWeight = NamespacedKey(plugin, "fish_weight")
    private val keyFishRarity = NamespacedKey(plugin, "fish_rarity")

    // ── Fish Registry ───────────────────────────────────────────────────

    private val allFish = mutableListOf<CustomFish>()
    private val fishById = mutableMapOf<String, CustomFish>()
    private val fishByRarity = mutableMapOf<FishRarity, MutableList<CustomFish>>()
    /** Cooldown to prevent double-fire on a single catch */
    private val lastCatchTime: MutableMap<UUID, Long> = java.util.concurrent.ConcurrentHashMap()

    // ── GUI Constants ───────────────────────────────────────────────────

    private val FILLER = ItemStack(Material.BLACK_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }
    private val BORDER = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply {
        editMeta { it.displayName(Component.empty()) }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS fish_collection (
                uuid TEXT NOT NULL,
                fish_id TEXT NOT NULL,
                best_weight REAL NOT NULL,
                total_caught INTEGER DEFAULT 1,
                PRIMARY KEY (uuid, fish_id)
            )
        """.trimIndent())

        registerFish()
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.logger.info("[Fishing] Registered ${allFish.size} custom fish across ${FishRarity.entries.size} rarities.")
    }

    // ── Fish Registration ───────────────────────────────────────────────

    private fun register(fish: CustomFish) {
        allFish.add(fish)
        fishById[fish.id] = fish
        fishByRarity.getOrPut(fish.rarity) { mutableListOf() }.add(fish)
    }

    private fun registerFish() {
        // ── Common (20) ─────────────────────────────────────────────────
        register(CustomFish("pond_minnow", "Pond Minnow", FishRarity.COMMON, Material.COD))
        register(CustomFish("creek_chub", "Creek Chub", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("mud_catfish", "Mud Catfish", FishRarity.COMMON, Material.COD))
        register(CustomFish("lake_perch", "Lake Perch", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("river_trout", "River Trout", FishRarity.COMMON, Material.COD, setOf("RIVER")))
        register(CustomFish("sunfish", "Sunfish", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("bluegill", "Bluegill", FishRarity.COMMON, Material.COD))
        register(CustomFish("carp", "Carp", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("shad", "Shad", FishRarity.COMMON, Material.COD))
        register(CustomFish("smelt", "Smelt", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("anchovy", "Anchovy", FishRarity.COMMON, Material.COD, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN", "COLD_OCEAN", "FROZEN_OCEAN")))
        register(CustomFish("sardine", "Sardine", FishRarity.COMMON, Material.SALMON, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN", "COLD_OCEAN", "FROZEN_OCEAN")))
        register(CustomFish("herring", "Herring", FishRarity.COMMON, Material.COD))
        register(CustomFish("whitefish", "Whitefish", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("dace", "Dace", FishRarity.COMMON, Material.COD))
        register(CustomFish("rudd", "Rudd", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("roach", "Roach", FishRarity.COMMON, Material.COD))
        register(CustomFish("bream", "Bream", FishRarity.COMMON, Material.SALMON))
        register(CustomFish("gudgeon", "Gudgeon", FishRarity.COMMON, Material.COD))
        register(CustomFish("minnow", "Minnow", FishRarity.COMMON, Material.SALMON))

        // ── Uncommon (15) ───────────────────────────────────────────────
        register(CustomFish("rainbow_trout", "Rainbow Trout", FishRarity.UNCOMMON, Material.SALMON, setOf("RIVER")))
        register(CustomFish("largemouth_bass", "Largemouth Bass", FishRarity.UNCOMMON, Material.TROPICAL_FISH))
        register(CustomFish("smallmouth_bass", "Smallmouth Bass", FishRarity.UNCOMMON, Material.SALMON))
        register(CustomFish("walleye", "Walleye", FishRarity.UNCOMMON, Material.TROPICAL_FISH))
        register(CustomFish("northern_pike", "Northern Pike", FishRarity.UNCOMMON, Material.SALMON))
        register(CustomFish("channel_catfish", "Channel Catfish", FishRarity.UNCOMMON, Material.TROPICAL_FISH, setOf("RIVER", "SWAMP")))
        register(CustomFish("crappie", "Crappie", FishRarity.UNCOMMON, Material.SALMON))
        register(CustomFish("tilapia", "Tilapia", FishRarity.UNCOMMON, Material.TROPICAL_FISH))
        register(CustomFish("red_snapper", "Red Snapper", FishRarity.UNCOMMON, Material.SALMON, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN")))
        register(CustomFish("grouper", "Grouper", FishRarity.UNCOMMON, Material.TROPICAL_FISH, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN")))
        register(CustomFish("yellowtail", "Yellowtail", FishRarity.UNCOMMON, Material.SALMON, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN")))
        register(CustomFish("mackerel", "Mackerel", FishRarity.UNCOMMON, Material.TROPICAL_FISH, setOf("OCEAN", "DEEP_OCEAN", "COLD_OCEAN")))
        register(CustomFish("sea_bass", "Sea Bass", FishRarity.UNCOMMON, Material.SALMON, setOf("OCEAN", "DEEP_OCEAN")))
        register(CustomFish("flounder", "Flounder", FishRarity.UNCOMMON, Material.TROPICAL_FISH, setOf("OCEAN", "DEEP_OCEAN")))
        register(CustomFish("halibut", "Halibut", FishRarity.UNCOMMON, Material.SALMON, setOf("OCEAN", "DEEP_OCEAN", "COLD_OCEAN", "FROZEN_OCEAN")))

        // ── Rare (15) ───────────────────────────────────────────────────
        register(CustomFish("golden_carp", "Golden Carp", FishRarity.RARE, Material.TROPICAL_FISH))
        register(CustomFish("crystal_bass", "Crystal Bass", FishRarity.RARE, Material.PRISMARINE_SHARD))
        register(CustomFish("moonfish", "Moonfish", FishRarity.RARE, Material.TROPICAL_FISH))
        register(CustomFish("shadow_trout", "Shadow Trout", FishRarity.RARE, Material.PRISMARINE_SHARD, setOf("SWAMP", "DEEP_DARK")))
        register(CustomFish("frost_pike", "Frost Pike", FishRarity.RARE, Material.TROPICAL_FISH, setOf("FROZEN_OCEAN", "COLD_OCEAN", "FROZEN_RIVER")))
        register(CustomFish("ember_catfish", "Ember Catfish", FishRarity.RARE, Material.PRISMARINE_SHARD))
        register(CustomFish("storm_eel", "Storm Eel", FishRarity.RARE, Material.TROPICAL_FISH, setOf("OCEAN", "DEEP_OCEAN")))
        register(CustomFish("coral_wrasse", "Coral Wrasse", FishRarity.RARE, Material.PRISMARINE_SHARD, setOf("WARM_OCEAN")))
        register(CustomFish("jade_perch", "Jade Perch", FishRarity.RARE, Material.TROPICAL_FISH))
        register(CustomFish("twilight_salmon", "Twilight Salmon", FishRarity.RARE, Material.PRISMARINE_SHARD))
        register(CustomFish("obsidian_cod", "Obsidian Cod", FishRarity.RARE, Material.TROPICAL_FISH))
        register(CustomFish("ancient_sturgeon", "Ancient Sturgeon", FishRarity.RARE, Material.PRISMARINE_SHARD, setOf("RIVER")))
        register(CustomFish("sapphire_marlin", "Sapphire Marlin", FishRarity.RARE, Material.TROPICAL_FISH, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN")))
        register(CustomFish("ruby_snapper", "Ruby Snapper", FishRarity.RARE, Material.PRISMARINE_SHARD, setOf("OCEAN", "DEEP_OCEAN")))
        register(CustomFish("phantom_shad", "Phantom Shad", FishRarity.RARE, Material.TROPICAL_FISH))

        // ── Epic (12) ───────────────────────────────────────────────────
        register(CustomFish("void_eel", "Void Eel", FishRarity.EPIC, Material.PRISMARINE_CRYSTALS, setOf("THE_END")))
        register(CustomFish("lava_serpent", "Lava Serpent", FishRarity.EPIC, Material.GLOW_INK_SAC, setOf("NETHER_WASTES", "CRIMSON_FOREST", "WARPED_FOREST", "SOUL_SAND_VALLEY", "BASALT_DELTAS")))
        register(CustomFish("thunder_barracuda", "Thunder Barracuda", FishRarity.EPIC, Material.PRISMARINE_CRYSTALS, setOf("OCEAN", "DEEP_OCEAN")))
        register(CustomFish("glacial_swordfish", "Glacial Swordfish", FishRarity.EPIC, Material.GLOW_INK_SAC, setOf("FROZEN_OCEAN", "COLD_OCEAN")))
        register(CustomFish("nether_pike", "Nether Pike", FishRarity.EPIC, Material.PRISMARINE_CRYSTALS, setOf("NETHER_WASTES", "CRIMSON_FOREST", "WARPED_FOREST", "SOUL_SAND_VALLEY", "BASALT_DELTAS")))
        register(CustomFish("end_bass", "End Bass", FishRarity.EPIC, Material.GLOW_INK_SAC, setOf("THE_END")))
        register(CustomFish("wardens_catch", "Warden's Catch", FishRarity.EPIC, Material.PRISMARINE_CRYSTALS, setOf("DEEP_DARK")))
        register(CustomFish("soul_salmon", "Soul Salmon", FishRarity.EPIC, Material.GLOW_INK_SAC, setOf("SOUL_SAND_VALLEY")))
        register(CustomFish("crimson_angler", "Crimson Angler", FishRarity.EPIC, Material.PRISMARINE_CRYSTALS, setOf("CRIMSON_FOREST")))
        register(CustomFish("abyssal_cod", "Abyssal Cod", FishRarity.EPIC, Material.GLOW_INK_SAC, setOf("DEEP_OCEAN")))
        register(CustomFish("spectral_trout", "Spectral Trout", FishRarity.EPIC, Material.PRISMARINE_CRYSTALS))
        register(CustomFish("enchanted_koi", "Enchanted Koi", FishRarity.EPIC, Material.GLOW_INK_SAC))

        // ── Legendary (10) ──────────────────────────────────────────────
        register(CustomFish("leviathans_scale", "Leviathan's Scale", FishRarity.LEGENDARY, Material.NAUTILUS_SHELL, setOf("DEEP_OCEAN")))
        register(CustomFish("kraken_tentacle", "Kraken Tentacle", FishRarity.LEGENDARY, Material.HEART_OF_THE_SEA, setOf("DEEP_OCEAN")))
        register(CustomFish("dragon_koi", "Dragon Koi", FishRarity.LEGENDARY, Material.NAUTILUS_SHELL, setOf("THE_END")))
        register(CustomFish("phoenix_fin", "Phoenix Fin", FishRarity.LEGENDARY, Material.HEART_OF_THE_SEA, setOf("NETHER_WASTES", "CRIMSON_FOREST", "WARPED_FOREST", "SOUL_SAND_VALLEY", "BASALT_DELTAS")))
        register(CustomFish("elder_guardians_eye", "Elder Guardian's Eye", FishRarity.LEGENDARY, Material.NAUTILUS_SHELL, setOf("DEEP_OCEAN", "OCEAN")))
        register(CustomFish("poseidons_trident_fish", "Poseidon's Trident Fish", FishRarity.LEGENDARY, Material.HEART_OF_THE_SEA, setOf("OCEAN", "DEEP_OCEAN", "WARM_OCEAN")))
        register(CustomFish("world_serpent_scale", "World Serpent Scale", FishRarity.LEGENDARY, Material.NAUTILUS_SHELL))
        register(CustomFish("titans_catch", "Titan's Catch", FishRarity.LEGENDARY, Material.HEART_OF_THE_SEA))
        register(CustomFish("celestial_marlin", "Celestial Marlin", FishRarity.LEGENDARY, Material.NAUTILUS_SHELL))
        register(CustomFish("abyssal_lord", "Abyssal Lord", FishRarity.LEGENDARY, Material.HEART_OF_THE_SEA, setOf("DEEP_OCEAN")))

        // ── Mythical (5) ────────────────────────────────────────────────
        register(CustomFish("the_one_fish", "The One Fish", FishRarity.MYTHICAL, Material.HEART_OF_THE_SEA))
        register(CustomFish("primordial_leviathan", "Primordial Leviathan", FishRarity.MYTHICAL, Material.NETHER_STAR, setOf("DEEP_OCEAN")))
        register(CustomFish("gods_bait", "God's Bait", FishRarity.MYTHICAL, Material.HEART_OF_THE_SEA))
        register(CustomFish("infinity_carp", "Infinity Carp", FishRarity.MYTHICAL, Material.NETHER_STAR))
        register(CustomFish("the_uncatchable", "The Uncatchable", FishRarity.MYTHICAL, Material.HEART_OF_THE_SEA))
    }

    // ── Fishing Event ───────────────────────────────────────────────────

    /** Players who already received a fish this catch cycle (cleared after a short delay) */
    private val recentlyCaught = mutableSetOf<UUID>()

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onFish(event: PlayerFishEvent) {
        val playerId = event.player.uniqueId

        // Track bites so we know when a real catch should happen
        if (event.state == PlayerFishEvent.State.BITE) {
            lastCatchTime[playerId] = System.currentTimeMillis()
            return
        }

        // Normal catch — vanilla produced loot
        if (event.state == PlayerFishEvent.State.CAUGHT_FISH) {
            // Anti double-fire: skip if we already processed a catch in the last 500ms
            if (recentlyCaught.contains(playerId)) return
            recentlyCaught.add(playerId)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                recentlyCaught.remove(playerId)
            }, 10L)

            lastCatchTime.remove(playerId)
            handleCatch(event.player, event)
            return
        }

        // High LoTS fallback: vanilla loot table was empty, but bobber had a bite
        // REEL_IN fires when player reels in — check if there was a recent bite
        if (event.state == PlayerFishEvent.State.REEL_IN) {
            // Anti double-fire: if CAUGHT_FISH already fired this cycle, skip
            if (recentlyCaught.contains(playerId)) return

            val lastBite = lastCatchTime.remove(playerId) ?: return
            // Only count if bite was recent (within 3s) AND the hook was in water
            if (System.currentTimeMillis() - lastBite > 3000L) return
            val hook = event.hook
            if (!hook.isInWater) return
            // No caught entity means vanilla couldn't generate loot (LoTS too high)
            if (event.caught != null) return

            // Mark as caught to prevent any subsequent double-fire
            recentlyCaught.add(playerId)
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                recentlyCaught.remove(playerId)
            }, 10L)

            // Generate our own fish
            giveCustomFish(event.player)
            return
        }
    }

    private fun handleCatch(player: Player, event: PlayerFishEvent) {
        // Roll our custom fish
        val rarity = rollRarity(player)
        val biomeName = player.location.block.biome.key.key.uppercase()

        val candidates = fishByRarity[rarity]?.filter { fish ->
            fish.biomes == null || fish.biomes.any { biomeName.contains(it, ignoreCase = true) }
        } ?: emptyList()

        val pool = candidates.ifEmpty { fishByRarity[rarity] ?: emptyList() }
        if (pool.isEmpty()) return

        val fish = pool[ThreadLocalRandom.current().nextInt(pool.size)]
        val weight = generateWeight(rarity)
        val fishItem = createFishItem(fish, weight, player)

        val caught = event.caught

        // Replace the caught entity's item, or give directly if no entity
        if (caught != null && caught is Item) {
            caught.itemStack = fishItem
        } else {
            // No caught entity or not an item — remove it and give directly
            caught?.remove()
            player.inventory.addItem(fishItem).values.forEach {
                player.world.dropItemNaturally(player.location, it)
            }
        }

        event.expToDrop = ThreadLocalRandom.current().nextInt(1, 7)

        // Send catch message
        val nameComponent = plugin.commsManager.parseLegacy("${rarity.color}${fish.name}")
        plugin.commsManager.send(player,
            Component.text("You caught a ", NamedTextColor.GRAY)
                .append(nameComponent)
                .append(Component.text("! ", NamedTextColor.GRAY))
                .append(Component.text("($weight lbs)", NamedTextColor.GRAY)),
            CommunicationsManager.Category.DEFAULT
        )

        // Broadcast for Legendary+
        if (rarity.ordinal >= FishRarity.LEGENDARY.ordinal) {
            plugin.commsManager.broadcast(
                Component.text(player.name, NamedTextColor.WHITE)
                    .append(Component.text(" caught a ", NamedTextColor.GRAY))
                    .append(plugin.commsManager.parseLegacy("${rarity.color}${fish.name}"))
                    .append(Component.text(" ($weight lbs)!", NamedTextColor.GRAY))
            )
        }

        // Sounds
        when {
            rarity.ordinal >= FishRarity.EPIC.ordinal -> player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            rarity == FishRarity.RARE -> player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
            else -> player.playSound(player.location, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f)
        }

        saveCatch(player, fish.id, weight)
    }

    /**
     * Give a custom fish directly to a player (used when vanilla loot table produces nothing,
     * e.g., with very high Luck of the Sea levels).
     */
    private fun giveCustomFish(player: Player) {
        val rarity = rollRarity(player)
        val biomeName = player.location.block.biome.key.key.uppercase()
        val candidates = fishByRarity[rarity]?.filter { fish ->
            fish.biomes == null || fish.biomes.any { biomeName.contains(it, ignoreCase = true) }
        } ?: emptyList()
        val pool = candidates.ifEmpty { fishByRarity[rarity] ?: emptyList() }
        if (pool.isEmpty()) return

        val fish = pool[ThreadLocalRandom.current().nextInt(pool.size)]
        val weight = generateWeight(rarity)
        val fishItem = createFishItem(fish, weight, player)

        player.inventory.addItem(fishItem).values.forEach {
            player.world.dropItemNaturally(player.location, it)
        }

        val nameComponent = plugin.commsManager.parseLegacy("${rarity.color}${fish.name}")
        plugin.commsManager.send(player,
            Component.text("You caught a ", NamedTextColor.GRAY).append(nameComponent)
                .append(Component.text("! ", NamedTextColor.GRAY))
                .append(Component.text("($weight lbs)", NamedTextColor.GRAY)),
            CommunicationsManager.Category.DEFAULT
        )

        if (rarity.ordinal >= FishRarity.LEGENDARY.ordinal) {
            plugin.commsManager.broadcast(
                Component.text(player.name, NamedTextColor.WHITE)
                    .append(Component.text(" caught a ", NamedTextColor.GRAY))
                    .append(plugin.commsManager.parseLegacy("${rarity.color}${fish.name}"))
                    .append(Component.text(" ($weight lbs)!", NamedTextColor.GRAY))
            )
        }

        when {
            rarity.ordinal >= FishRarity.EPIC.ordinal -> player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
            rarity == FishRarity.RARE -> player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f)
            else -> player.playSound(player.location, Sound.ENTITY_FISHING_BOBBER_SPLASH, 1.0f, 1.0f)
        }

        saveCatch(player, fish.id, weight)
    }

    // ── Rarity Rolling ──────────────────────────────────────────────────

    private fun rollRarity(player: Player): FishRarity {
        // Luck of the Sea boosts rare+ chances
        val rod = player.inventory.itemInMainHand
        val luckLevel = rod.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.LUCK_OF_THE_SEA).coerceAtMost(10)

        // Build adjusted weights: each luck level reduces common by 8% and boosts rare+ tiers
        val weights = mutableMapOf<FishRarity, Double>()
        for (rarity in FishRarity.entries) {
            var w = rarity.weight
            when (rarity) {
                FishRarity.COMMON -> w *= (1.0 - 0.08 * luckLevel).coerceAtLeast(0.2)
                FishRarity.UNCOMMON -> w *= (1.0 + 0.05 * luckLevel)
                FishRarity.RARE -> w *= (1.0 + 0.15 * luckLevel)
                FishRarity.EPIC -> w *= (1.0 + 0.25 * luckLevel)
                FishRarity.LEGENDARY -> w *= (1.0 + 0.40 * luckLevel)
                FishRarity.MYTHICAL -> w *= (1.0 + 0.50 * luckLevel)
            }
            weights[rarity] = w
        }

        val totalWeight = weights.values.sum()
        var roll = ThreadLocalRandom.current().nextDouble() * totalWeight
        for (rarity in FishRarity.entries) {
            roll -= (weights[rarity] ?: rarity.weight)
            if (roll <= 0.0) return rarity
        }
        return FishRarity.COMMON
    }

    // ── Weight Generation ───────────────────────────────────────────────

    private fun generateWeight(rarity: FishRarity): Double {
        val raw = rarity.minWeight + ThreadLocalRandom.current().nextDouble() * (rarity.maxWeight - rarity.minWeight)
        return Math.round(raw * 10.0) / 10.0
    }

    // ── Item Creation ───────────────────────────────────────────────────

    private fun createFishItem(fish: CustomFish, weight: Double, player: Player): ItemStack {
        val item = ItemStack(fish.material)
        item.editMeta { meta ->
            // Display name
            meta.displayName(
                plugin.commsManager.parseLegacy("${fish.rarity.color}${fish.name}")
                    .decoration(TextDecoration.ITALIC, false)
            )

            // Rarity odds display
            val oddsText = when (fish.rarity) {
                FishRarity.COMMON -> "1 in 2"
                FishRarity.UNCOMMON -> "1 in 5"
                FishRarity.RARE -> "1 in 10"
                FishRarity.EPIC -> "1 in 20"
                FishRarity.LEGENDARY -> "1 in 40"
                FishRarity.MYTHICAL -> "1 in 200"
            }

            // Lore
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Weight: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text("$weight lbs", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)),
                Component.text("  Rarity: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(plugin.commsManager.parseLegacy("${fish.rarity.color}${fish.rarity.displayName}")
                        .decoration(TextDecoration.ITALIC, false)),
                Component.text("  Odds: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                    .append(Component.text(oddsText, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)),
                Component.empty(),
                Component.text("  Caught by ${player.name}", NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            ))

            // Enchant glint for Epic+
            if (fish.rarity.ordinal >= FishRarity.EPIC.ordinal) {
                meta.setEnchantmentGlintOverride(true)
            }

            // Custom model for resource pack
            meta.setItemModel(NamespacedKey("joshymc", "fish/${fish.id}"))

            // PDC tags
            meta.persistentDataContainer.set(keyFishId, PersistentDataType.STRING, fish.id)
            meta.persistentDataContainer.set(keyFishWeight, PersistentDataType.DOUBLE, weight)
            meta.persistentDataContainer.set(keyFishRarity, PersistentDataType.STRING, fish.rarity.name)
        }
        return item
    }

    // ── Database ────────────────────────────────────────────────────────

    private fun saveCatch(player: Player, fishId: String, weight: Double) {
        val existing = plugin.databaseManager.queryFirst(
            "SELECT best_weight, total_caught FROM fish_collection WHERE uuid = ? AND fish_id = ?",
            player.uniqueId.toString(), fishId
        ) { rs -> Pair(rs.getDouble("best_weight"), rs.getInt("total_caught")) }

        if (existing == null) {
            plugin.databaseManager.execute(
                "INSERT INTO fish_collection (uuid, fish_id, best_weight, total_caught) VALUES (?, ?, ?, 1)",
                player.uniqueId.toString(), fishId, weight
            )
        } else {
            val newBest = maxOf(existing.first, weight)
            plugin.databaseManager.execute(
                "UPDATE fish_collection SET best_weight = ?, total_caught = ? WHERE uuid = ? AND fish_id = ?",
                newBest, existing.second + 1, player.uniqueId.toString(), fishId
            )
        }
    }

    // ── Collection GUI ──────────────────────────────────────────────────

    fun openCollection(player: Player) {
        openCollectionPage(player, 0)
    }

    private fun openCollectionPage(player: Player, page: Int) {
        // Load player's caught fish
        val caught = plugin.databaseManager.query(
            "SELECT fish_id, best_weight, total_caught FROM fish_collection WHERE uuid = ?",
            player.uniqueId.toString()
        ) { rs -> Triple(rs.getString("fish_id"), rs.getDouble("best_weight"), rs.getInt("total_caught")) }

        val caughtMap = caught.associate { it.first to Pair(it.second, it.third) }
        val caughtCount = caughtMap.size
        val totalFish = allFish.size

        // 54-slot GUI: top border (9), 4 rows of 7 items (28 usable), bottom border (9)
        // Usable slots: rows 1-4, columns 1-7 = 28 per page
        val slotsPerPage = 28
        val maxPages = (totalFish + slotsPerPage - 1) / slotsPerPage
        val safePage = page.coerceIn(0, (maxPages - 1).coerceAtLeast(0))

        val completionPct = if (totalFish > 0) (caughtCount * 100) / totalFish else 0
        val title = Component.text("Fish Collection ", NamedTextColor.DARK_AQUA)
            .decoration(TextDecoration.BOLD, true)
            .decoration(TextDecoration.ITALIC, false)
            .append(Component.text("($caughtCount/$totalFish - $completionPct%)", NamedTextColor.GRAY)
                .decoration(TextDecoration.BOLD, false)
                .decoration(TextDecoration.ITALIC, false))

        val gui = CustomGui(title, 54)
        gui.border(BORDER.clone())
        gui.fill(FILLER.clone())

        // Fill fish items in usable area
        val usableSlots = mutableListOf<Int>()
        for (row in 1..4) {
            for (col in 1..7) {
                usableSlots.add(row * 9 + col)
            }
        }

        val startIndex = safePage * slotsPerPage
        for (i in usableSlots.indices) {
            val fishIndex = startIndex + i
            if (fishIndex >= totalFish) break

            val fish = allFish[fishIndex]
            val slot = usableSlots[i]
            val data = caughtMap[fish.id]

            if (data != null) {
                // Caught: show the fish
                val bestWeight = data.first
                val timesCaught = data.second
                val item = ItemStack(fish.material)
                item.editMeta { meta ->
                    meta.displayName(
                        plugin.commsManager.parseLegacy("${fish.rarity.color}${fish.name}")
                            .decoration(TextDecoration.ITALIC, false)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("Rarity: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                            .append(plugin.commsManager.parseLegacy("${fish.rarity.color}${fish.rarity.displayName}")
                                .decoration(TextDecoration.ITALIC, false)),
                        Component.text("Best Weight: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("$bestWeight lbs", NamedTextColor.WHITE)
                                .decoration(TextDecoration.ITALIC, false)),
                        Component.text("Times Caught: ", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
                            .append(Component.text("$timesCaught", NamedTextColor.WHITE)
                                .decoration(TextDecoration.ITALIC, false)),
                        Component.empty()
                    ))
                    if (fish.rarity.ordinal >= FishRarity.EPIC.ordinal) {
                        meta.setEnchantmentGlintOverride(true)
                    }
                    meta.setItemModel(NamespacedKey("joshymc", "fish/${fish.id}"))
                }
                gui.setItem(slot, item)
            } else {
                // Not caught: gray pane
                val item = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
                item.editMeta { meta ->
                    meta.displayName(
                        Component.text("???", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)
                            .decoration(TextDecoration.BOLD, true)
                    )
                    meta.lore(listOf(
                        Component.empty(),
                        Component.text("Not yet caught", NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false),
                        Component.empty()
                    ))
                }
                gui.setItem(slot, item)
            }
        }

        // Navigation buttons in bottom row
        if (safePage > 0) {
            val prevItem = ItemStack(Material.ARROW)
            prevItem.editMeta { meta ->
                meta.displayName(
                    Component.text("Previous Page", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
            gui.setItem(48, prevItem) { p, _ -> openCollectionPage(p, safePage - 1) }
        }

        if (safePage < maxPages - 1) {
            val nextItem = ItemStack(Material.ARROW)
            nextItem.editMeta { meta ->
                meta.displayName(
                    Component.text("Next Page", NamedTextColor.YELLOW)
                        .decoration(TextDecoration.ITALIC, false)
                        .decoration(TextDecoration.BOLD, true)
                )
            }
            gui.setItem(50, nextItem) { p, _ -> openCollectionPage(p, safePage + 1) }
        }

        // Page indicator
        val pageIndicator = ItemStack(Material.PAPER)
        pageIndicator.editMeta { meta ->
            meta.displayName(
                Component.text("Page ${safePage + 1}/$maxPages", NamedTextColor.WHITE)
                    .decoration(TextDecoration.ITALIC, false)
            )
        }
        gui.setItem(49, pageIndicator)

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Stats ───────────────────────────────────────────────────────────

    fun showStats(player: Player) {
        val stats = plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as unique_fish, SUM(total_caught) as total, MAX(best_weight) as heaviest FROM fish_collection WHERE uuid = ?",
            player.uniqueId.toString()
        ) { rs -> Triple(rs.getInt("unique_fish"), rs.getInt("total"), rs.getDouble("heaviest")) }

        val uniqueFish = stats?.first ?: 0
        val totalCaught = stats?.second ?: 0
        val heaviest = stats?.third ?: 0.0

        // Find rarest fish caught
        val rarestCatch = plugin.databaseManager.queryFirst(
            """
            SELECT fish_id FROM fish_collection WHERE uuid = ?
            ORDER BY CASE
                WHEN fish_id IN (${allFish.filter { it.rarity == FishRarity.MYTHICAL }.joinToString(",") { "'${it.id}'" }}) THEN 6
                WHEN fish_id IN (${allFish.filter { it.rarity == FishRarity.LEGENDARY }.joinToString(",") { "'${it.id}'" }}) THEN 5
                WHEN fish_id IN (${allFish.filter { it.rarity == FishRarity.EPIC }.joinToString(",") { "'${it.id}'" }}) THEN 4
                WHEN fish_id IN (${allFish.filter { it.rarity == FishRarity.RARE }.joinToString(",") { "'${it.id}'" }}) THEN 3
                WHEN fish_id IN (${allFish.filter { it.rarity == FishRarity.UNCOMMON }.joinToString(",") { "'${it.id}'" }}) THEN 2
                ELSE 1
            END DESC
            LIMIT 1
            """.trimIndent(),
            player.uniqueId.toString()
        ) { rs -> rs.getString("fish_id") }

        val rarestFish = rarestCatch?.let { fishById[it] }

        plugin.commsManager.send(player,
            Component.text("Fishing Stats", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.DEFAULT
        )

        plugin.commsManager.sendRaw(player,
            Component.text("  Species Caught: ", NamedTextColor.GRAY)
                .append(Component.text("$uniqueFish/${allFish.size}", NamedTextColor.WHITE))
        )
        plugin.commsManager.sendRaw(player,
            Component.text("  Total Fish Caught: ", NamedTextColor.GRAY)
                .append(Component.text("$totalCaught", NamedTextColor.WHITE))
        )
        plugin.commsManager.sendRaw(player,
            Component.text("  Heaviest Catch: ", NamedTextColor.GRAY)
                .append(Component.text("$heaviest lbs", NamedTextColor.WHITE))
        )

        if (rarestFish != null) {
            plugin.commsManager.sendRaw(player,
                Component.text("  Rarest Catch: ", NamedTextColor.GRAY)
                    .append(plugin.commsManager.parseLegacy("${rarestFish.rarity.color}${rarestFish.name}"))
            )
        }
    }

    // ── Leaderboard ─────────────────────────────────────────────────────

    fun showLeaderboard(player: Player) {
        val top = plugin.databaseManager.query(
            "SELECT uuid, fish_id, best_weight FROM fish_collection ORDER BY best_weight DESC LIMIT 10"
        ) { rs -> Triple(rs.getString("uuid"), rs.getString("fish_id"), rs.getDouble("best_weight")) }

        plugin.commsManager.send(player,
            Component.text("Fishing Leaderboard - Heaviest Catches", NamedTextColor.DARK_AQUA)
                .decoration(TextDecoration.BOLD, true),
            CommunicationsManager.Category.DEFAULT
        )

        if (top.isEmpty()) {
            plugin.commsManager.sendRaw(player,
                Component.text("  No fish caught yet!", NamedTextColor.GRAY)
            )
            return
        }

        for ((index, entry) in top.withIndex()) {
            val name = plugin.server.getOfflinePlayer(java.util.UUID.fromString(entry.first)).name ?: "Unknown"
            val fish = fishById[entry.second]
            val fishName = fish?.let { plugin.commsManager.parseLegacy("${it.rarity.color}${it.name}") }
                ?: Component.text(entry.second, NamedTextColor.GRAY)

            val rankColor = when (index) {
                0 -> TextColor.color(0xFFD700) // Gold
                1 -> TextColor.color(0xC0C0C0) // Silver
                2 -> TextColor.color(0xCD7F32) // Bronze
                else -> NamedTextColor.GRAY
            }

            plugin.commsManager.sendRaw(player,
                Component.text("  ${index + 1}. ", rankColor)
                    .append(Component.text(name, NamedTextColor.WHITE))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(fishName)
                    .append(Component.text(" (${entry.third} lbs)", NamedTextColor.GRAY))
            )
        }
    }
}
