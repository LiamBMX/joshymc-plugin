package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.block.CreatureSpawner
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Spawners are plain vanilla Minecraft mob spawners. JoshyMC only adds a
 * `/spawner shop` convenience storefront (spawners.yml defines mob + price)
 * and Silk Touch pickup that preserves the spawner's mob type — there is no
 * custom GUI, storage, or production system (removed in issue #772).
 */
class SpawnerManager(private val plugin: Joshymc) : Listener {

    data class SpawnerType(
        val id: String,
        val displayName: String,
        val mob: EntityType,
        /** Cost to buy from /spawner shop. Negative or zero hides it from the shop. */
        val buyPrice: Double
    )

    data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int)

    /** A row from the old custom_spawners table (pre-issue-772) that hasn't been
     *  converted to a plain vanilla spawner block yet — conversion needs the
     *  chunk loaded so we can touch the block state. */
    private data class LegacySpawner(val key: BlockKey, val spawnerId: String, val storage: List<ItemStack>)

    private val types = mutableMapOf<String, SpawnerType>()
    /** Tags a Silk Touch–harvested (or shop-bought) spawner item with the entity
     *  type it should spawn once placed, since embedding it via ItemMeta's
     *  block-state alone isn't reliably picked up by vanilla placement. */
    private val pdcMobType = NamespacedKey(plugin, "silk_spawner_mob")
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    private val pendingMigration = ConcurrentHashMap<BlockKey, LegacySpawner>()

    // ── Lifecycle ───────────────────────────────────────────

    fun start() {
        // Legacy table from the old custom spawner GUI/storage/production system.
        // Kept (not dropped) so already-placed spawners can be converted to plain
        // vanilla spawners and any stored items safely returned to players. The
        // runtime stops reading/writing this table once a row is migrated.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS custom_spawners (
                world TEXT NOT NULL,
                x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                spawner_id TEXT NOT NULL,
                owner_uuid TEXT NOT NULL,
                stack_count INTEGER NOT NULL DEFAULT 1,
                enabled INTEGER NOT NULL DEFAULT 1,
                storage_b64 TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (world, x, y, z)
            )
        """.trimIndent())
        try {
            plugin.databaseManager.execute(
                "ALTER TABLE custom_spawners ADD COLUMN migrated INTEGER NOT NULL DEFAULT 0"
            )
        } catch (_: Exception) { /* already exists */ }

        loadTypes()
        loadPendingMigrations()
        migrateLoadedChunks()

        plugin.logger.info("[Spawners] Loaded ${types.size} spawner type(s); ${pendingMigration.size} legacy custom spawner(s) pending migration to vanilla.")
    }

    fun stop() {
        pendingMigration.clear()
    }

    private fun loadTypes() {
        types.clear()
        val file = plugin.configFile("spawners.yml")
        if (!file.exists()) {
            plugin.saveResource("spawners.yml", false)
        } else {
            // Merge in any new spawner types added to the bundled defaults
            // since the user last regenerated their file. Existing entries
            // are never overwritten — admin tweaks (prices) survive.
            mergeMissingFromDefaults(file)
        }
        val cfg = YamlConfiguration.loadConfiguration(file)
        val section = cfg.getConfigurationSection("spawners") ?: return

        for (id in section.getKeys(false)) {
            val s = section.getConfigurationSection(id) ?: continue
            try {
                val displayName = s.getString("display-name", "&f$id Spawner") ?: id
                val mob = EntityType.valueOf(s.getString("mob", "ZOMBIE")?.uppercase() ?: "ZOMBIE")
                val buyPrice = s.getDouble("buy-price", -1.0)
                types[id] = SpawnerType(id, displayName, mob, buyPrice)
            } catch (e: Exception) {
                plugin.logger.warning("[Spawners] Failed to load spawner type '$id': ${e.message}")
            }
        }
    }

    /**
     * Merge any spawner-type entries that exist in the bundled
     * `spawners.yml` resource but are missing from the user's saved file.
     * Existing entries (with admin tweaks) are left untouched. Only adds
     * new top-level keys under `spawners:`.
     */
    private fun mergeMissingFromDefaults(userFile: java.io.File) {
        val defaultStream = plugin.getResource("spawners.yml") ?: return
        val defaults = YamlConfiguration.loadConfiguration(defaultStream.bufferedReader())
        val userCfg = YamlConfiguration.loadConfiguration(userFile)

        if (com.liam.joshymc.util.ConfigUtil.looksLikeParseFailure(userFile, userCfg)) {
            plugin.logger.severe("[Spawners] spawners.yml failed to load (invalid YAML) — the existing file has been preserved and was NOT overwritten. Fix the syntax error and reload.")
            return
        }

        val defaultsSection = defaults.getConfigurationSection("spawners") ?: return
        val userSection = userCfg.getConfigurationSection("spawners")
            ?: userCfg.createSection("spawners")

        var added = 0
        for (id in defaultsSection.getKeys(false)) {
            if (userSection.contains(id)) continue
            userSection.set(id, defaultsSection.get(id))
            added++
        }
        if (added > 0) {
            try {
                com.liam.joshymc.util.ConfigUtil.backup(userFile, plugin.logger, "Spawners")
                userCfg.save(userFile)
                plugin.logger.info("[Spawners] Merged $added new spawner type(s) from bundled defaults.")
            } catch (e: Exception) {
                plugin.logger.warning("[Spawners] Failed to save merged spawners.yml: ${e.message}")
            }
        }
    }

    // ── Public API ──────────────────────────────────────────

    fun getTypes(): Collection<SpawnerType> = types.values
    fun getType(id: String): SpawnerType? = types[id]

    /** Create a plain spawner item for a known spawner type (e.g. "zombie"). Used by
     *  /spawner give and the /spawner shop — the delivered item is a normal spawner
     *  that spawns [SpawnerType.mob] once placed, nothing more. */
    fun createSpawnerItem(typeId: String, amount: Int = 1): ItemStack? {
        val type = types[typeId] ?: return null
        return createSpawnerItem(type.mob, amount, type.displayName)
    }

    /** Build a plain spawner item that remembers the entity type it should spawn once
     *  placed. Used both for shop/admin-given spawners and Silk Touch pickups of
     *  natural/vanilla spawners — there's no distinction between them anymore. */
    fun createSpawnerItem(mobType: EntityType, amount: Int = 1, displayName: String? = null): ItemStack {
        val item = ItemStack(Material.SPAWNER, amount)
        item.editMeta { meta ->
            val name = displayName?.let { legacy.deserialize(it) }
                ?: Component.text("${formatMobName(mobType)} Spawner", NamedTextColor.WHITE)
            meta.displayName(name.decoration(TextDecoration.ITALIC, false))
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Mob: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMobName(mobType), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
            meta.persistentDataContainer.set(pdcMobType, PersistentDataType.STRING, mobType.name)
            if (meta is BlockStateMeta) {
                val state = meta.blockState as? CreatureSpawner
                if (state != null) {
                    state.spawnedType = mobType
                    meta.blockState = state
                }
            }
        }
        return item
    }

    /** Reads the entity type stashed on a spawner item, if any. */
    private fun getSpawnerMobType(item: ItemStack): EntityType? {
        val meta = item.itemMeta ?: return null
        val stored = meta.persistentDataContainer.get(pdcMobType, PersistentDataType.STRING)
        if (stored != null) {
            runCatching { EntityType.valueOf(stored) }.getOrNull()?.let { return it }
        }
        val stateMeta = meta as? BlockStateMeta ?: return null
        return (stateMeta.blockState as? CreatureSpawner)?.spawnedType
    }

    // ── Events ──────────────────────────────────────────────

    /** Restore the placed spawner's mob type from the item, so vanilla spawning
     *  takes over immediately with the correct mob. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (item.type != Material.SPAWNER) return
        val mobType = getSpawnerMobType(item) ?: return
        val state = event.blockPlaced.state as? CreatureSpawner ?: return
        state.spawnedType = mobType
        state.update(true, false)
    }

    /** Silk Touch pickup preserves the mob type as exactly one item; without Silk
     *  Touch the block just breaks (XP only), matching vanilla. Runs at HIGHEST
     *  with ignoreCancelled=true, so anything that cancels the break earlier
     *  (WorldGuard, claim protection, spawn protection) is respected as-is. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.SPAWNER) return

        event.isDropItems = false
        val mainHand = event.player.inventory.itemInMainHand
        if (mainHand.containsEnchantment(Enchantment.SILK_TOUCH)) {
            val currentType = (block.state as? CreatureSpawner)?.spawnedType
            if (currentType != null) {
                val item = createSpawnerItem(currentType)
                block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), item)
            }
        }
    }

    /** Converts any not-yet-migrated legacy custom spawner in a newly loaded chunk
     *  into a plain vanilla spawner. */
    @EventHandler
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (pendingMigration.isEmpty()) return
        val chunk = event.chunk
        val worldName = chunk.world.name
        for (legacySpawner in pendingMigration.values.toList()) {
            val key = legacySpawner.key
            if (key.world != worldName) continue
            if ((key.x shr 4) != chunk.x || (key.z shr 4) != chunk.z) continue
            migrateBlock(chunk.world, legacySpawner)
        }
    }

    // ── Legacy Migration ────────────────────────────────────

    private fun loadPendingMigrations() {
        pendingMigration.clear()
        val rows = plugin.databaseManager.query(
            "SELECT world, x, y, z, spawner_id, storage_b64 FROM custom_spawners WHERE migrated = 0"
        ) { rs ->
            val key = BlockKey(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"))
            LegacySpawner(key, rs.getString("spawner_id"), deserializeStorage(rs.getString("storage_b64")))
        }
        for (row in rows) pendingMigration[row.key] = row
    }

    private fun migrateLoadedChunks() {
        for (legacySpawner in pendingMigration.values.toList()) {
            val world = Bukkit.getWorld(legacySpawner.key.world) ?: continue
            if (world.isChunkLoaded(legacySpawner.key.x shr 4, legacySpawner.key.z shr 4)) {
                migrateBlock(world, legacySpawner)
            }
        }
    }

    /** Converts a single legacy custom spawner block into a plain vanilla spawner
     *  (preserving its mob type) and drops any leftover stored items at the block
     *  instead of deleting them, then marks the row migrated. */
    private fun migrateBlock(world: World, legacySpawner: LegacySpawner) {
        val key = legacySpawner.key
        val block = world.getBlockAt(key.x, key.y, key.z)
        val state = block.state as? CreatureSpawner
        if (state != null) {
            state.spawnedType = types[legacySpawner.spawnerId]?.mob ?: state.spawnedType ?: EntityType.ZOMBIE
            state.requiredPlayerRange = 16
            state.spawnRange = 4
            state.minSpawnDelay = 200
            state.maxSpawnDelay = 800
            state.update(true, false)
        }

        for (stack in legacySpawner.storage) {
            if (stack.type != Material.AIR && stack.amount > 0) {
                world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), stack)
            }
        }

        pendingMigration.remove(key)
        plugin.databaseManager.execute(
            "UPDATE custom_spawners SET migrated = 1 WHERE world = ? AND x = ? AND y = ? AND z = ?",
            key.world, key.x, key.y, key.z
        )
        plugin.logger.info("[Spawners] Migrated legacy custom spawner at ${key.world} ${key.x},${key.y},${key.z} to a normal vanilla spawner.")
    }

    private fun deserializeStorage(b64: String?): List<ItemStack> {
        if (b64.isNullOrBlank()) return emptyList()
        return b64.split(";").mapNotNull { part ->
            try {
                ItemStack.deserializeBytes(Base64.getDecoder().decode(part))
            } catch (_: Exception) { null }
        }
    }

    // ── Shop GUI ────────────────────────────────────────────

    fun openShop(player: Player) {
        val purchasable = types.values.filter { it.buyPrice > 0.0 }
        if (purchasable.isEmpty()) {
            plugin.commsManager.send(player, Component.text("No spawners are available for purchase.", NamedTextColor.RED))
            return
        }

        val rows = ((purchasable.size - 1) / 7 + 3).coerceIn(3, 6)
        val size = rows * 9

        val gui = CustomGui(
            Component.text("Spawner Shop", NamedTextColor.GOLD)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            size
        )

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until size) gui.inventory.setItem(i, filler.clone())

        val border = ItemStack(Material.YELLOW_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        gui.border(border)

        // Place spawners in the inner area, 7 per row
        var idx = 0
        for (type in purchasable) {
            val row = (idx / 7) + 1
            val col = (idx % 7) + 1
            if (row >= rows - 1) break
            val slot = row * 9 + col

            val eggMat = mobToSpawnEgg(type.mob) ?: Material.SPAWNER
            val item = ItemStack(eggMat)
            item.editMeta { meta ->
                meta.displayName(legacy.deserialize(type.displayName)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true))
                val lore = mutableListOf<Component>()
                lore.add(Component.empty())
                lore.add(Component.text("  Mob: ", NamedTextColor.GRAY)
                    .append(Component.text(formatMobName(type.mob), NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
                lore.add(Component.text("  Price: ", NamedTextColor.GRAY)
                    .append(Component.text(plugin.economyManager.format(type.buyPrice), NamedTextColor.GOLD))
                    .decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
                lore.add(Component.text("  Click to buy", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true))
                lore.add(Component.empty())
                meta.lore(lore)
            }

            val typeRef = type
            gui.setItem(slot, item) { p, _ ->
                val balance = plugin.economyManager.getBalance(p.uniqueId)
                if (balance < typeRef.buyPrice) {
                    plugin.commsManager.send(p, Component.text("You can't afford this spawner. (${plugin.economyManager.format(typeRef.buyPrice)})", NamedTextColor.RED))
                    p.playSound(p.location, Sound.ENTITY_VILLAGER_NO, 0.7f, 1.0f)
                    return@setItem
                }
                if (!plugin.economyManager.withdraw(p.uniqueId, typeRef.buyPrice)) {
                    plugin.commsManager.send(p, Component.text("Purchase failed.", NamedTextColor.RED))
                    return@setItem
                }
                val spawnerItem = createSpawnerItem(typeRef.id, 1)
                if (spawnerItem != null) {
                    val leftover = p.inventory.addItem(spawnerItem)
                    for ((_, drop) in leftover) {
                        p.world.dropItemNaturally(p.location, drop)
                    }
                }
                plugin.commsManager.send(p,
                    Component.text("Purchased ", NamedTextColor.GREEN)
                        .append(legacy.deserialize(typeRef.displayName))
                        .append(Component.text(" for ${plugin.economyManager.format(typeRef.buyPrice)}.", NamedTextColor.GREEN)))
                p.playSound(p.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.5f)
            }
            idx++
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun mobToSpawnEgg(mob: EntityType): Material? {
        return try {
            Material.valueOf("${mob.name}_SPAWN_EGG")
        } catch (_: Exception) { null }
    }

    private fun formatMobName(type: EntityType): String {
        return type.name.split("_").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
