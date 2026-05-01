package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.CreatureSpawner
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.SpawnerSpawnEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SpawnerManager(private val plugin: Joshymc) : Listener {

    // ── Data Classes ────────────────────────────────────────

    data class SpawnerDrop(
        val material: Material,
        val amount: Int,
        val sellPrice: Double,
        val chance: Double
    )

    data class SpawnerType(
        val id: String,
        val displayName: String,
        val mob: EntityType,
        val intervalSeconds: Int,
        val minDropsPerInterval: Int,
        val maxDropsPerInterval: Int,
        val drops: List<SpawnerDrop>,
        /** Cost to buy from /spawner shop. Negative or zero hides it from the shop. */
        val buyPrice: Double
    )

    data class BlockKey(val world: String, val x: Int, val y: Int, val z: Int) {
        fun toLocation(): Location? {
            val w = Bukkit.getWorld(world) ?: return null
            return Location(w, x.toDouble(), y.toDouble(), z.toDouble())
        }
    }

    /** A placed custom spawner with full state. */
    data class SpawnerBlock(
        val key: BlockKey,
        val spawnerId: String,
        val ownerUuid: UUID,
        var stackCount: Int,
        var enabled: Boolean,
        val storage: MutableList<ItemStack>,
        var lastTickMs: Long
    )

    // ── State ───────────────────────────────────────────────

    private val types = mutableMapOf<String, SpawnerType>()
    private val blocks = ConcurrentHashMap<BlockKey, SpawnerBlock>()
    private val pdcSpawnerId = NamespacedKey(plugin, "custom_spawner_id")
    private val pdcSpawnerOwner = NamespacedKey(plugin, "custom_spawner_owner")
    private val legacy = LegacyComponentSerializer.legacyAmpersand()

    private var tickTask: BukkitTask? = null
    private var hopperTask: BukkitTask? = null
    private var countdownTask: BukkitTask? = null

    /** Players currently viewing a spawner main GUI: UUID -> the spawner block they're viewing */
    private val openMainGuis = ConcurrentHashMap<UUID, BlockKey>()

    /** Base storage rows per stack count. Each stack adds this many rows (max 6 per page). */
    private val rowsPerStack = 1

    // ── Lifecycle ───────────────────────────────────────────

    fun start() {
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

        loadTypes()
        loadBlocks()

        // Drop generation tick — runs every second, processes spawners whose interval has elapsed
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickSpawners() }, 20L, 20L)

        // Hopper extraction — every 2 seconds
        hopperTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickHoppers() }, 40L, 40L)

        // Live countdown updater for any open spawner GUIs — every second
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tickOpenGuis() }, 20L, 20L)

        plugin.logger.info("[Spawners] Loaded ${types.size} spawner type(s) and ${blocks.size} placed spawner(s).")
    }

    fun stop() {
        tickTask?.cancel(); tickTask = null
        hopperTask?.cancel(); hopperTask = null
        countdownTask?.cancel(); countdownTask = null
        openMainGuis.clear()
        // Save all storage to DB
        for (block in blocks.values) saveBlock(block)
    }

    private fun loadTypes() {
        types.clear()
        val file = plugin.configFile("spawners.yml")
        if (!file.exists()) {
            plugin.saveResource("spawners.yml", false)
        }
        val cfg = YamlConfiguration.loadConfiguration(file)
        val section = cfg.getConfigurationSection("spawners") ?: return

        for (id in section.getKeys(false)) {
            val s = section.getConfigurationSection(id) ?: continue
            try {
                val displayName = s.getString("display-name", "&f$id Spawner") ?: id
                val mob = EntityType.valueOf(s.getString("mob", "ZOMBIE")?.uppercase() ?: "ZOMBIE")
                val interval = s.getInt("interval-seconds", 30)
                val min = s.getInt("drops-per-interval.min", 1)
                val max = s.getInt("drops-per-interval.max", min)
                val buyPrice = s.getDouble("buy-price", -1.0)

                val drops = mutableListOf<SpawnerDrop>()
                val dropsList = s.getMapList("drops")
                for (dropMap in dropsList) {
                    val matName = dropMap["material"]?.toString() ?: continue
                    val mat = Material.matchMaterial(matName) ?: continue
                    val amount = (dropMap["amount"] as? Number)?.toInt() ?: 1
                    val price = (dropMap["sell-price"] as? Number)?.toDouble() ?: 0.0
                    val chance = (dropMap["chance"] as? Number)?.toDouble() ?: 1.0
                    drops.add(SpawnerDrop(mat, amount, price, chance))
                }

                types[id] = SpawnerType(id, displayName, mob, interval, min, max, drops, buyPrice)
            } catch (e: Exception) {
                plugin.logger.warning("[Spawners] Failed to load spawner type '$id': ${e.message}")
            }
        }
    }

    private fun loadBlocks() {
        blocks.clear()
        val rows = plugin.databaseManager.query(
            "SELECT world, x, y, z, spawner_id, owner_uuid, stack_count, enabled, storage_b64 FROM custom_spawners"
        ) { rs ->
            val key = BlockKey(rs.getString("world"), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"))
            SpawnerBlock(
                key = key,
                spawnerId = rs.getString("spawner_id"),
                ownerUuid = UUID.fromString(rs.getString("owner_uuid")),
                stackCount = rs.getInt("stack_count"),
                enabled = rs.getInt("enabled") == 1,
                storage = deserializeStorage(rs.getString("storage_b64")),
                lastTickMs = System.currentTimeMillis()
            )
        }
        for (block in rows) blocks[block.key] = block
    }

    private fun saveBlock(block: SpawnerBlock) {
        plugin.databaseManager.execute(
            """INSERT INTO custom_spawners (world, x, y, z, spawner_id, owner_uuid, stack_count, enabled, storage_b64)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(world, x, y, z) DO UPDATE SET
                 spawner_id = excluded.spawner_id,
                 owner_uuid = excluded.owner_uuid,
                 stack_count = excluded.stack_count,
                 enabled = excluded.enabled,
                 storage_b64 = excluded.storage_b64""",
            block.key.world, block.key.x, block.key.y, block.key.z,
            block.spawnerId, block.ownerUuid.toString(), block.stackCount,
            if (block.enabled) 1 else 0, serializeStorage(block.storage)
        )
    }

    private fun deleteBlock(key: BlockKey) {
        blocks.remove(key)
        plugin.databaseManager.execute(
            "DELETE FROM custom_spawners WHERE world = ? AND x = ? AND y = ? AND z = ?",
            key.world, key.x, key.y, key.z
        )
    }

    // ── Public API ──────────────────────────────────────────

    fun getTypes(): Collection<SpawnerType> = types.values
    fun getType(id: String): SpawnerType? = types[id]

    /** Create a custom spawner ItemStack with PDC-tagged spawner id. */
    fun createSpawnerItem(typeId: String, amount: Int = 1): ItemStack? {
        val type = types[typeId] ?: return null
        val item = ItemStack(Material.SPAWNER, amount)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize(type.displayName).decoration(TextDecoration.ITALIC, false))
            val lore = mutableListOf<Component>()
            lore.add(Component.empty())
            lore.add(Component.text("  Custom Spawner", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("  Mob: ", NamedTextColor.GRAY)
                .append(Component.text(formatMobName(type.mob), NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("  Interval: ", NamedTextColor.GRAY)
                .append(Component.text("${type.intervalSeconds}s", NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false))
            lore.add(Component.text("  Drops/cycle: ", NamedTextColor.GRAY)
                .append(Component.text("${type.minDropsPerInterval}-${type.maxDropsPerInterval}", NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            lore.add(Component.text("  Drops:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
            for (drop in type.drops) {
                val chanceText = if (drop.chance < 1.0) " &8(&7${(drop.chance * 100).toInt()}%&8)" else ""
                lore.add(legacy.deserialize("  &7- &f${drop.amount}x ${formatMaterialName(drop.material)} &8(&6$${"%.0f".format(drop.sellPrice)}&8)$chanceText")
                    .decoration(TextDecoration.ITALIC, false))
            }
            lore.add(Component.empty())
            lore.add(Component.text("  Owner-locked once placed", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
            lore.add(Component.empty())
            meta.lore(lore)

            meta.persistentDataContainer.set(pdcSpawnerId, PersistentDataType.STRING, typeId)
        }
        return item
    }

    /** Check if an item is one of OUR custom spawners (vs vanilla). */
    fun isCustomSpawnerItem(item: ItemStack?): Boolean {
        if (item == null || item.type != Material.SPAWNER) return false
        val meta = item.itemMeta ?: return false
        return meta.persistentDataContainer.has(pdcSpawnerId, PersistentDataType.STRING)
    }

    fun getSpawnerIdFromItem(item: ItemStack): String? {
        val meta = item.itemMeta ?: return null
        return meta.persistentDataContainer.get(pdcSpawnerId, PersistentDataType.STRING)
    }

    fun getSpawnerAt(block: Block): SpawnerBlock? = blocks[block.toKey()]

    // ── Events ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val item = event.itemInHand
        if (!isCustomSpawnerItem(item)) return

        val block = event.blockPlaced
        val spawnerId = getSpawnerIdFromItem(item) ?: return
        val type = types[spawnerId] ?: return
        val player = event.player

        // STACKING RULE: if NOT sneaking, look for an adjacent custom spawner of the
        // same type owned by the player. If found, stack into it instead of placing.
        // Sneaking bypasses stacking so players can deliberately place side-by-side.
        if (!player.isSneaking) {
            val adjacent = findAdjacentSameTypeSpawner(block, spawnerId, player.uniqueId)
            if (adjacent != null) {
                event.isCancelled = true
                stackInto(player, adjacent, event.hand)
                return
            }
        }

        // New placement — register in DB with owner = placer
        val key = block.toKey()
        val spawnerBlock = SpawnerBlock(
            key = key,
            spawnerId = spawnerId,
            ownerUuid = player.uniqueId,
            stackCount = 1,
            enabled = true,
            storage = mutableListOf(),
            lastTickMs = System.currentTimeMillis()
        )
        blocks[key] = spawnerBlock
        saveBlock(spawnerBlock)

        // Apply visual state — shows the spinning mob + golden particles when enabled
        applyVisualState(block, type, true)

        plugin.commsManager.send(player,
            Component.text("Custom spawner placed! Right-click to manage.", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 0.5f, 1.5f)
    }

    /** Find an adjacent (6-face) custom spawner of the same type owned by the player. */
    private fun findAdjacentSameTypeSpawner(block: Block, spawnerId: String, ownerUuid: UUID): SpawnerBlock? {
        val faces = listOf(
            org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN,
            org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
            org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST
        )
        for (face in faces) {
            val neighbor = block.getRelative(face)
            val existing = blocks[neighbor.toKey()] ?: continue
            if (existing.spawnerId != spawnerId) continue
            if (existing.ownerUuid != ownerUuid) continue
            return existing
        }
        return null
    }

    /** Stack the held item into an existing spawner: increment count, consume one item. */
    private fun stackInto(player: Player, target: SpawnerBlock, hand: org.bukkit.inventory.EquipmentSlot) {
        target.stackCount += 1
        saveBlock(target)
        // Consume one item from hand
        val handItem = player.inventory.getItem(hand)
        if (handItem != null && handItem.amount > 1) {
            handItem.amount -= 1
        } else {
            player.inventory.setItem(hand, null)
        }
        plugin.commsManager.send(player,
            Component.text("Spawner stacked! (x${target.stackCount})", NamedTextColor.GREEN))
        player.playSound(player.location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.5f)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val block = event.block
        if (block.type != Material.SPAWNER) return

        val key = block.toKey()
        val spawnerBlock = blocks[key]

        // VANILLA spawner: do NOT allow pickup (even with silk touch). Players
        // were silk-touching dungeon-loot spawners (blaze, zombie, spider) for
        // free farms — only JoshyMC-owned custom spawners are pickable.
        // Players still get standard XP/dropless behaviour from breaking it.
        if (spawnerBlock == null) {
            event.isDropItems = false
            return
        }

        val player = event.player

        // Only the owner can pick it up
        if (spawnerBlock.ownerUuid != player.uniqueId) {
            event.isCancelled = true
            plugin.commsManager.send(player,
                Component.text("This spawner is owner-locked. Only the owner can pick it up.", NamedTextColor.RED))
            return
        }

        // Must use a pickaxe
        val tool = player.inventory.itemInMainHand
        if (!tool.type.name.endsWith("_PICKAXE")) {
            event.isCancelled = true
            plugin.commsManager.send(player,
                Component.text("Use a pickaxe to pick up your spawner.", NamedTextColor.RED))
            return
        }

        // Cancel default drops
        event.isDropItems = false
        event.expToDrop = 0

        // Drop the custom spawner item × stack count
        val item = createSpawnerItem(spawnerBlock.spawnerId, spawnerBlock.stackCount)
        if (item != null) {
            block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), item)
        }

        // Drop any storage contents at the block too (so the player can collect them)
        for (stack in spawnerBlock.storage) {
            if (stack.type != Material.AIR && stack.amount > 0) {
                block.world.dropItemNaturally(block.location.add(0.5, 0.5, 0.5), stack)
            }
        }

        deleteBlock(key)
    }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != org.bukkit.inventory.EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return
        if (block.type != Material.SPAWNER) return

        val spawnerBlock = blocks[block.toKey()] ?: return
        val player = event.player

        // SHIFT-RIGHT-CLICK with custom spawner item of same type → stack
        val held = player.inventory.itemInMainHand
        if (player.isSneaking && isCustomSpawnerItem(held) && getSpawnerIdFromItem(held) == spawnerBlock.spawnerId) {
            event.isCancelled = true
            // Only owner (or admin) can stack
            if (spawnerBlock.ownerUuid != player.uniqueId && !player.hasPermission("joshymc.spawners.admin")) {
                plugin.commsManager.send(player, Component.text("That spawner belongs to someone else.", NamedTextColor.RED))
                return
            }
            stackInto(player, spawnerBlock, org.bukkit.inventory.EquipmentSlot.HAND)
            return
        }

        // Don't let players sneak-place items onto custom spawners
        event.isCancelled = true

        // Owner: full access
        // Trusted (claim): view-only
        // OPs/admins: view-only override
        // Others: blocked
        val isOwner = spawnerBlock.ownerUuid == player.uniqueId
        val canView = isOwner ||
                player.hasPermission("joshymc.spawners.admin") ||
                plugin.claimManager.canAccess(player, block.location)

        if (!canView) {
            plugin.commsManager.send(player,
                Component.text("You don't have access to this spawner.", NamedTextColor.RED))
            return
        }

        openMainGui(player, spawnerBlock, isOwner)
    }

    /** Cancel vanilla mob spawning for our custom spawners. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawn(event: SpawnerSpawnEvent) {
        val spawner = event.spawner ?: return
        val key = spawner.block.toKey()
        if (blocks.containsKey(key)) {
            event.isCancelled = true
        }
    }

    // ── Tick Loop ───────────────────────────────────────────

    private fun tickSpawners() {
        val now = System.currentTimeMillis()
        for (block in blocks.values) {
            if (!block.enabled) continue
            val type = types[block.spawnerId] ?: continue
            val intervalMs = type.intervalSeconds * 1000L
            if (now - block.lastTickMs < intervalMs) continue
            block.lastTickMs = now
            generateDrops(block, type)
        }
    }

    private fun generateDrops(block: SpawnerBlock, type: SpawnerType) {
        // Number of drop rolls per interval, per spawner in the stack
        val rolls = (type.minDropsPerInterval..type.maxDropsPerInterval).random() * block.stackCount

        for (i in 0 until rolls) {
            for (drop in type.drops) {
                if (Math.random() > drop.chance) continue
                val item = ItemStack(drop.material, drop.amount)
                addToStorage(block, item)
            }
        }
        saveBlock(block)
    }

    private fun addToStorage(block: SpawnerBlock, item: ItemStack) {
        val maxSlots = maxStorageSlots(block.stackCount)

        // Try to merge into existing stacks
        for (existing in block.storage) {
            if (existing.isSimilar(item)) {
                val space = existing.maxStackSize - existing.amount
                if (space >= item.amount) {
                    existing.amount += item.amount
                    return
                } else if (space > 0) {
                    existing.amount = existing.maxStackSize
                    item.amount -= space
                }
            }
        }

        // Add new stack if there's room
        if (block.storage.size < maxSlots) {
            block.storage.add(item)
        }
        // Otherwise: storage is full, items are lost
    }

    private fun maxStorageSlots(stackCount: Int): Int {
        // 9 slots base, +9 per additional spawner stacked, capped at 6 pages × 27 slots = 162
        return (9 * stackCount).coerceAtMost(162)
    }

    // ── Hopper Extraction ───────────────────────────────────

    private fun tickHoppers() {
        for (block in blocks.values) {
            val loc = block.key.toLocation() ?: continue
            val world = loc.world ?: continue
            val below = world.getBlockAt(loc.blockX, loc.blockY - 1, loc.blockZ)
            if (below.type != Material.HOPPER) continue
            val state = below.state as? org.bukkit.block.Hopper ?: continue
            val hopperInv = state.inventory

            // Try to move one item from spawner storage to hopper
            val iter = block.storage.iterator()
            var moved = false
            while (iter.hasNext()) {
                val stack = iter.next()
                if (stack.amount <= 0) { iter.remove(); continue }

                val one = stack.clone()
                one.amount = 1
                val leftover = hopperInv.addItem(one)
                if (leftover.isEmpty()) {
                    stack.amount -= 1
                    if (stack.amount <= 0) iter.remove()
                    moved = true
                    break
                }
            }
            if (moved) saveBlock(block)
        }
    }

    // ── Main GUI ────────────────────────────────────────────

    private fun openMainGui(player: Player, block: SpawnerBlock, isOwner: Boolean) {
        val type = types[block.spawnerId] ?: return
        val title = legacy.deserialize(type.displayName).decoration(TextDecoration.ITALIC, false)
        val gui = CustomGui(title, 27)

        // Register so the countdown task can update slot 13 every second
        openMainGuis[player.uniqueId] = block.key
        gui.onClose = { openMainGuis.remove(it.uniqueId) }

        // Background
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 0 until 27) gui.inventory.setItem(i, filler.clone())

        val border = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        gui.border(border)

        // LEFT: Storage chest icon (slot 11)
        val chestItem = ItemStack(Material.CHEST)
        chestItem.editMeta { meta ->
            meta.displayName(Component.text("Storage", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
            val itemCount = block.storage.sumOf { it.amount }
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Items: $itemCount", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  Slots: ${block.storage.size}/${maxStorageSlots(block.stackCount)}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to open", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(11, chestItem) { p, _ ->
            openStorageGui(p, block, isOwner, 0)
        }

        // CENTER: Spawner display (slot 13) — spawn egg of the mob
        gui.inventory.setItem(13, buildCenterItem(block, type))

        // RIGHT: Disable / enable toggle (slot 15)
        val toggleMat = if (block.enabled) Material.LIME_DYE else Material.GRAY_DYE
        val toggleItem = ItemStack(toggleMat)
        toggleItem.editMeta { meta ->
            meta.displayName(
                if (block.enabled) Component.text("Disable Spawner", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
                else Component.text("Enable Spawner", NamedTextColor.GREEN)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true)
            )
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Click to toggle", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(if (block.enabled) "  Currently producing drops" else "  Currently paused",
                    if (block.enabled) NamedTextColor.GREEN else NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(15, toggleItem) { p, _ ->
            if (!isOwner && !p.hasPermission("joshymc.spawners.admin")) {
                plugin.commsManager.send(p, Component.text("Only the owner can toggle this spawner.", NamedTextColor.RED))
                return@setItem
            }
            block.enabled = !block.enabled
            saveBlock(block)
            // Update visual state
            val loc = block.key.toLocation()
            if (loc != null) applyVisualState(loc.block, types[block.spawnerId]!!, block.enabled)
            p.playSound(p.location, Sound.UI_BUTTON_CLICK, 0.5f, if (block.enabled) 1.5f else 0.7f)
            openMainGui(p, block, isOwner) // refresh
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    /** Build the center display item with live countdown for slot 13. */
    private fun buildCenterItem(block: SpawnerBlock, type: SpawnerType): ItemStack {
        val eggMaterial = mobToSpawnEgg(type.mob) ?: Material.SPAWNER
        val item = ItemStack(eggMaterial)
        item.editMeta { meta ->
            meta.displayName(legacy.deserialize(type.displayName)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
            val ownerName = Bukkit.getOfflinePlayer(block.ownerUuid).name ?: "Unknown"

            // Live countdown
            val intervalMs = type.intervalSeconds * 1000L
            val elapsedMs = System.currentTimeMillis() - block.lastTickMs
            val remainingSec = ((intervalMs - elapsedMs) / 1000L).coerceAtLeast(0L)

            val countdownText = if (block.enabled)
                "  Next drop in: ${remainingSec}s"
            else
                "  Paused"
            val countdownColor = if (block.enabled) NamedTextColor.GOLD else NamedTextColor.GRAY

            meta.lore(listOf(
                Component.empty(),
                Component.text("  Owner: ", NamedTextColor.GRAY)
                    .append(Component.text(ownerName, NamedTextColor.WHITE))
                    .decoration(TextDecoration.ITALIC, false),
                Component.text("  Stack: x${block.stackCount}", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  Interval: ${type.intervalSeconds}s", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  Drops/cycle: ${type.minDropsPerInterval}-${type.maxDropsPerInterval}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text(countdownText, countdownColor).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Status: ", NamedTextColor.GRAY)
                    .append(if (block.enabled) Component.text("ENABLED", NamedTextColor.GREEN)
                            else Component.text("DISABLED", NamedTextColor.RED))
                    .decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        return item
    }

    /** Update slot 13 of every open spawner GUI with the latest countdown. */
    private fun tickOpenGuis() {
        if (openMainGuis.isEmpty()) return
        for ((uuid, key) in openMainGuis.toMap()) {
            val player = Bukkit.getPlayer(uuid) ?: run { openMainGuis.remove(uuid); continue }
            val block = blocks[key] ?: continue
            val type = types[block.spawnerId] ?: continue
            // Replace slot 13 in the player's open inventory
            val openInv = player.openInventory.topInventory
            if (openInv.size != 27) {
                openMainGuis.remove(uuid)
                continue
            }
            openInv.setItem(13, buildCenterItem(block, type))
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
                lore.add(Component.text("  Interval: ${type.intervalSeconds}s", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))
                lore.add(Component.text("  Drops/cycle: ${type.minDropsPerInterval}-${type.maxDropsPerInterval}", NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false))
                lore.add(Component.empty())
                lore.add(Component.text("  Drops:", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
                for (drop in type.drops) {
                    val chanceText = if (drop.chance < 1.0) " &8(&7${(drop.chance * 100).toInt()}%&8)" else ""
                    lore.add(legacy.deserialize("  &7- &f${drop.amount}x ${formatMaterialName(drop.material)} &8(&6$${"%.0f".format(drop.sellPrice)}&8)$chanceText")
                        .decoration(TextDecoration.ITALIC, false))
                }
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

    // ── Storage GUI ─────────────────────────────────────────

    private fun openStorageGui(player: Player, block: SpawnerBlock, isOwner: Boolean, page: Int) {
        val type = types[block.spawnerId] ?: return
        val maxSlots = maxStorageSlots(block.stackCount)
        // 27 content slots per page (3 rows), 9 button slots at the bottom
        val perPage = 27
        val totalPages = ((maxSlots - 1) / perPage).coerceAtLeast(0) + 1
        val safePage = page.coerceIn(0, totalPages - 1)

        val title = Component.text("Storage: ", NamedTextColor.GOLD)
            .append(legacy.deserialize(type.displayName))
            .append(Component.text(" (Page ${safePage + 1}/$totalPages)", NamedTextColor.GRAY))
            .decoration(TextDecoration.ITALIC, false)

        val gui = CustomGui(title, 36) // 27 content + 9 buttons

        // Show items on this page
        val startIdx = safePage * perPage
        val endIdx = (startIdx + perPage).coerceAtMost(block.storage.size)
        for (i in 0 until perPage) {
            val storageIdx = startIdx + i
            if (storageIdx < endIdx) {
                val item = block.storage[storageIdx]
                if (item.amount > 0) {
                    gui.setItem(i, item.clone()) { p, _ ->
                        if (!isOwner && !p.hasPermission("joshymc.spawners.admin")) {
                            plugin.commsManager.send(p, Component.text("Only the owner can withdraw items.", NamedTextColor.RED))
                            return@setItem
                        }
                        // Click to take this stack
                        if (storageIdx < block.storage.size) {
                            val stack = block.storage[storageIdx]
                            val leftover = p.inventory.addItem(stack.clone())
                            if (leftover.isEmpty()) {
                                block.storage.removeAt(storageIdx)
                            } else {
                                stack.amount = leftover.values.sumOf { it.amount }
                            }
                            saveBlock(block)
                            openStorageGui(p, block, isOwner, safePage)
                        }
                    }
                }
            }
        }

        // Bottom row buttons (slots 27-35)
        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        for (i in 27 until 36) gui.inventory.setItem(i, filler.clone())

        // Previous page (slot 27)
        if (safePage > 0) {
            val prev = ItemStack(Material.ARROW)
            prev.editMeta { meta ->
                meta.displayName(Component.text("Previous Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
            gui.setItem(27, prev) { p, _ -> openStorageGui(p, block, isOwner, safePage - 1) }
        }

        // Withdraw All (slot 30) | Back (slot 31) | Sell All (slot 32)
        // Withdraw All
        val withdrawAll = ItemStack(Material.HOPPER)
        withdrawAll.editMeta { meta ->
            meta.displayName(Component.text("Withdraw All", NamedTextColor.YELLOW)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Take all items into your inventory", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            ))
        }
        gui.setItem(30, withdrawAll) { p, _ ->
            if (!isOwner && !p.hasPermission("joshymc.spawners.admin")) {
                plugin.commsManager.send(p, Component.text("Only the owner can withdraw items.", NamedTextColor.RED))
                return@setItem
            }
            var taken = 0
            val iter = block.storage.iterator()
            while (iter.hasNext()) {
                val stack = iter.next()
                val leftover = p.inventory.addItem(stack.clone())
                if (leftover.isEmpty()) {
                    taken += stack.amount
                    iter.remove()
                } else {
                    val leftoverAmount = leftover.values.sumOf { it.amount }
                    taken += stack.amount - leftoverAmount
                    stack.amount = leftoverAmount
                    break // inventory full
                }
            }
            saveBlock(block)
            plugin.commsManager.send(p, Component.text("Withdrew $taken items.", NamedTextColor.GREEN))
            openStorageGui(p, block, isOwner, safePage)
        }

        // Sell All (slot 32)
        val sellAll = ItemStack(Material.GOLD_INGOT)
        sellAll.editMeta { meta ->
            meta.displayName(Component.text("Sell All", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
            // Calculate total value
            val totalValue = block.storage.sumOf { stack ->
                val drop = type.drops.firstOrNull { it.material == stack.type }
                (drop?.sellPrice ?: 0.0) * stack.amount
            }
            meta.lore(listOf(
                Component.empty(),
                Component.text("  Sell all stored items for", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  ${plugin.economyManager.format(totalValue)}", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, true),
                Component.empty()
            ))
        }
        gui.setItem(32, sellAll) { p, _ ->
            if (!isOwner && !p.hasPermission("joshymc.spawners.admin")) {
                plugin.commsManager.send(p, Component.text("Only the owner can sell items.", NamedTextColor.RED))
                return@setItem
            }
            var totalValue = 0.0
            val iter = block.storage.iterator()
            while (iter.hasNext()) {
                val stack = iter.next()
                val drop = type.drops.firstOrNull { it.material == stack.type }
                if (drop != null) {
                    totalValue += drop.sellPrice * stack.amount
                    iter.remove()
                }
            }
            if (totalValue > 0) {
                plugin.economyManager.deposit(p.uniqueId, totalValue)
                plugin.commsManager.send(p, Component.text("Sold for ${plugin.economyManager.format(totalValue)}.", NamedTextColor.GREEN))
                p.playSound(p.location, Sound.ENTITY_VILLAGER_YES, 1.0f, 1.0f)
            } else {
                plugin.commsManager.send(p, Component.text("Nothing to sell.", NamedTextColor.GRAY))
            }
            saveBlock(block)
            openStorageGui(p, block, isOwner, safePage)
        }

        // Next page (slot 35)
        if (safePage < totalPages - 1) {
            val next = ItemStack(Material.ARROW)
            next.editMeta { meta ->
                meta.displayName(Component.text("Next Page", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false))
            }
            gui.setItem(35, next) { p, _ -> openStorageGui(p, block, isOwner, safePage + 1) }
        }

        // Back (slot 31 — between Withdraw All and Sell All)
        val back = ItemStack(Material.OAK_DOOR)
        back.editMeta { meta ->
            meta.displayName(Component.text("Back", NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true))
        }
        gui.setItem(31, back) { p, _ -> openMainGui(p, block, isOwner) }

        plugin.guiManager.open(player, gui)
    }

    // ── Visual State ────────────────────────────────────────

    /**
     * Set the spawner block's visual state.
     *
     * When enabled: spawnedType is the configured mob and requiredPlayerRange = 16,
     * so vanilla renders the spinning mob and the golden flame particles. Actual
     * spawning is cancelled in [onSpawn], so no real mobs are produced.
     *
     * When disabled: spawnedType is AREA_EFFECT_CLOUD and requiredPlayerRange = 0,
     * which removes the spinning mob/particles entirely.
     */
    private fun applyVisualState(block: Block, type: SpawnerType, enabled: Boolean) {
        if (block.type != Material.SPAWNER) {
            block.type = Material.SPAWNER
        }
        val state = block.state as? CreatureSpawner ?: return
        try {
            if (enabled) {
                state.spawnedType = type.mob
                state.requiredPlayerRange = 16   // needed for vanilla to render spinner + particles
                state.spawnRange = 4
                state.minSpawnDelay = 200        // 10s — vanilla cycle (cancelled in onSpawn)
                state.maxSpawnDelay = 800        // 40s
            } else {
                state.spawnedType = EntityType.AREA_EFFECT_CLOUD
                state.requiredPlayerRange = 0    // no spinner / no particles
                state.minSpawnDelay = Int.MAX_VALUE
                state.maxSpawnDelay = Int.MAX_VALUE
            }
            state.update(true, false)
        } catch (_: Exception) {}
    }

    // ── Storage Serialization ───────────────────────────────

    private fun serializeStorage(items: List<ItemStack>): String {
        if (items.isEmpty()) return ""
        val nonEmpty = items.filter { it.amount > 0 && it.type != Material.AIR }
        if (nonEmpty.isEmpty()) return ""
        // Use Bukkit's per-item serialization
        val parts = nonEmpty.map { Base64.getEncoder().encodeToString(it.serializeAsBytes()) }
        return parts.joinToString(";")
    }

    private fun deserializeStorage(b64: String?): MutableList<ItemStack> {
        if (b64.isNullOrBlank()) return mutableListOf()
        return b64.split(";").mapNotNull { part ->
            try {
                ItemStack.deserializeBytes(Base64.getDecoder().decode(part))
            } catch (_: Exception) { null }
        }.toMutableList()
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun Block.toKey(): BlockKey = BlockKey(world.name, x, y, z)

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

    private fun formatMaterialName(mat: Material): String {
        return mat.name.split("_").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercase() }
        }
    }
}
