package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.Enderman
import org.bukkit.entity.Entity
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.Cancellable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.AreaEffectCloudApplyEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityPortalEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.LingeringPotionSplashEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class WorldFlagManager(private val plugin: Joshymc) : Listener {

    // ──────────────────────────────────────────────
    //  Flags
    // ──────────────────────────────────────────────

    enum class WorldFlag(val displayName: String, val description: String) {
        PVP("PvP", "Allow player combat"),
        BLOCK_BREAK("Block Break", "Allow breaking blocks"),
        BLOCK_PLACE("Block Place", "Allow placing blocks"),
        BLOCK_BREAK_PLAYER_PLACED_ONLY("Player-Placed Only", "Only allow breaking blocks placed by players after region setup"),
        INTERACT("Interact", "Allow interacting with blocks (doors, chests, buttons)"),
        CONTAINER("Containers", "Allow opening containers (chests, barrels, ender chests)"),
        MOB_DAMAGE("Mob Damage", "Allow mobs to damage players"),
        HUNGER("Hunger", "Allow hunger drain"),
        FALL_DAMAGE("Fall Damage", "Allow fall damage"),
        EXPLOSIONS("Explosions", "Allow explosions to damage terrain"),
        ITEM_DROP("Item Drop", "Allow dropping items"),
        ITEM_PICKUP("Item Pickup", "Allow picking up items"),
        MOB_SPAWN("Mob Spawn", "Allow mob spawning"),
        FIRE_SPREAD("Fire Spread", "Allow fire to spread"),
        LEAF_DECAY("Leaf Decay", "Allow leaf decay"),
        ENDERMAN_GRIEF("Enderman Grief", "Allow enderman to pick up blocks"),
        ENDER_PEARL("Ender Pearls", "Allow ender pearl teleportation"),
        ELYTRA("Elytra", "Allow elytra gliding"),
        COMMAND_USE("Command Use", "Allow command usage (non-staff)"),
        SPLASH_POTIONS("Splash Potions", "Allow splash & lingering potion effects"),
    }

    /**
     * Flags whose "unset" resolution is NOT the usual allow-by-default. A
     * region/world that never mentions [WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY]
     * must behave as if it were off — treating an unset value as "true" here
     * would silently lock down block-breaking everywhere.
     */
    private val defaultDenyFlags = setOf(WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY)

    /** Flags surfaced in the region editor GUI, in display order. */
    val editorFlags = listOf(
        WorldFlag.PVP,
        WorldFlag.BLOCK_PLACE,
        WorldFlag.BLOCK_BREAK,
        WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY,
        WorldFlag.EXPLOSIONS,
        WorldFlag.FIRE_SPREAD,
        WorldFlag.MOB_DAMAGE,
        WorldFlag.ENDER_PEARL,
        WorldFlag.ELYTRA,
        WorldFlag.ITEM_DROP,
        WorldFlag.ITEM_PICKUP,
        WorldFlag.INTERACT,
        WorldFlag.SPLASH_POTIONS,
    )

    // ──────────────────────────────────────────────
    //  Container materials
    // ──────────────────────────────────────────────

    private val containerMaterials = setOf(
        Material.CHEST,
        Material.TRAPPED_CHEST,
        Material.BARREL,
        Material.ENDER_CHEST,
    )

    private fun isContainer(material: Material): Boolean {
        if (material in containerMaterials) return true
        if (material.name.endsWith("_SHULKER_BOX")) return true
        return false
    }

    // ──────────────────────────────────────────────
    //  Cache & cooldowns
    // ──────────────────────────────────────────────

    /** world name -> (flag -> value) */
    private val cache = ConcurrentHashMap<String, MutableMap<WorldFlag, Boolean>>()

    /** player UUID + flag -> last deny message timestamp */
    private val denyCooldowns = ConcurrentHashMap<Pair<UUID, WorldFlag>, Long>()

    private val cooldownMs = 2_000L

    // ──────────────────────────────────────────────
    //  Regions
    // ──────────────────────────────────────────────

    data class WorldFlagRegion(
        val name: String,
        val world: String,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
        var priority: Int,
        val flags: MutableMap<WorldFlag, Boolean> = mutableMapOf(),
        /** Name of the WorldFlag region this is a Subflag of, or null for a top-level region. */
        val parent: String? = null,
    ) {
        fun contains(x: Int, y: Int, z: Int): Boolean =
            x in minX..maxX && y in minY..maxY && z in minZ..maxZ

        fun contains(loc: Location): Boolean =
            loc.world?.name == world && contains(loc.blockX, loc.blockY, loc.blockZ)

        /**
         * WorldFlag regions protect a horizontal area, not a specific
         * vertical slice — a Subflag's Y range commonly differs from the
         * parent's original wand selection (build height, cave depth, etc).
         * Containment for Subflags is therefore X/Z (+ same world) only.
         */
        fun containsRegion(other: WorldFlagRegion): Boolean =
            world == other.world &&
                other.minX >= minX && other.maxX <= maxX &&
                other.minZ >= minZ && other.maxZ <= maxZ
    }

    /** Result of attempting to create a Subflag under a parent WorldFlag. */
    sealed class SubflagCreateResult {
        object ParentNotFound : SubflagCreateResult()
        object ParentIsSubflag : SubflagCreateResult()
        object NameTaken : SubflagCreateResult()
        object WrongWorld : SubflagCreateResult()
        object OutsideParent : SubflagCreateResult()
        data class Created(val region: WorldFlagRegion) : SubflagCreateResult()
    }

    /** Result of attempting to delete a region that may have Subflags depending on it. */
    sealed class DeleteResult {
        object NotFound : DeleteResult()
        data class HasSubflags(val names: List<String>) : DeleteResult()
        data class Deleted(val cascaded: List<String>) : DeleteResult()
    }

    private data class BlockPos(val x: Int, val y: Int, val z: Int)

    /** region name (lowercase) -> region */
    private val regions = ConcurrentHashMap<String, WorldFlagRegion>()

    /** world name -> set of player-placed block positions */
    private val placedBlocks = ConcurrentHashMap<String, MutableSet<BlockPos>>()

    private val wandKey = NamespacedKey(plugin, "worldflags_wand")
    private val pos1 = ConcurrentHashMap<UUID, Location>()
    private val pos2 = ConcurrentHashMap<UUID, Location>()

    companion object {
        const val BYPASS_PERMISSION = "joshymc.worldflags.bypass"
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    fun start() {
        plugin.databaseManager.execute(
            """
            CREATE TABLE IF NOT EXISTS world_flags (
                world_name TEXT NOT NULL,
                flag       TEXT NOT NULL,
                value      INTEGER NOT NULL,
                PRIMARY KEY (world_name, flag)
            )
            """.trimIndent()
        )

        plugin.databaseManager.execute(
            """
            CREATE TABLE IF NOT EXISTS world_flag_regions (
                name       TEXT PRIMARY KEY,
                world      TEXT NOT NULL,
                min_x      INTEGER NOT NULL,
                min_y      INTEGER NOT NULL,
                min_z      INTEGER NOT NULL,
                max_x      INTEGER NOT NULL,
                max_y      INTEGER NOT NULL,
                max_z      INTEGER NOT NULL,
                priority   INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        try {
            plugin.databaseManager.execute("ALTER TABLE world_flag_regions ADD COLUMN parent TEXT")
        } catch (_: Exception) {
            // Column already exists.
        }

        plugin.databaseManager.execute(
            """
            CREATE TABLE IF NOT EXISTS world_flag_region_flags (
                region_name TEXT NOT NULL,
                flag        TEXT NOT NULL,
                value       INTEGER NOT NULL,
                PRIMARY KEY (region_name, flag)
            )
            """.trimIndent()
        )

        plugin.databaseManager.execute(
            """
            CREATE TABLE IF NOT EXISTS world_flag_placed_blocks (
                world      TEXT NOT NULL,
                x          INTEGER NOT NULL,
                y          INTEGER NOT NULL,
                z          INTEGER NOT NULL,
                placer     TEXT,
                placed_at  INTEGER NOT NULL,
                PRIMARY KEY (world, x, y, z)
            )
            """.trimIndent()
        )

        loadCache()
        applyDefaults()
        loadRegions()
        loadPlacedBlocks()

        Bukkit.getPluginManager().registerEvents(this, plugin)
        plugin.logger.info("[WorldFlags] Loaded flags for ${cache.size} world(s), ${regions.size} region(s).")
    }

    private fun loadCache() {
        cache.clear()
        plugin.databaseManager.query(
            "SELECT world_name, flag, value FROM world_flags"
        ) { rs ->
            Triple(rs.getString("world_name"), rs.getString("flag"), rs.getInt("value"))
        }.forEach { (world, flagName, value) ->
            val flag = WorldFlag.entries.find { it.name == flagName } ?: return@forEach
            cache.getOrPut(world) { mutableMapOf() }[flag] = value == 1
        }
    }

    private fun loadRegions() {
        regions.clear()
        plugin.databaseManager.query("SELECT * FROM world_flag_regions") { rs ->
            WorldFlagRegion(
                name = rs.getString("name"),
                world = rs.getString("world"),
                minX = rs.getInt("min_x"), minY = rs.getInt("min_y"), minZ = rs.getInt("min_z"),
                maxX = rs.getInt("max_x"), maxY = rs.getInt("max_y"), maxZ = rs.getInt("max_z"),
                priority = rs.getInt("priority"),
                parent = rs.getString("parent"),
            )
        }.forEach { regions[it.name.lowercase()] = it }

        plugin.databaseManager.query(
            "SELECT region_name, flag, value FROM world_flag_region_flags"
        ) { rs ->
            Triple(rs.getString("region_name"), rs.getString("flag"), rs.getInt("value"))
        }.forEach { (regionName, flagName, value) ->
            val region = regions[regionName.lowercase()] ?: return@forEach
            val flag = WorldFlag.entries.find { it.name == flagName } ?: return@forEach
            region.flags[flag] = value == 1
        }
    }

    private fun loadPlacedBlocks() {
        placedBlocks.clear()
        plugin.databaseManager.query(
            "SELECT world, x, y, z FROM world_flag_placed_blocks"
        ) { rs ->
            Triple(rs.getString("world"), rs.getInt("x"), rs.getInt("y") to rs.getInt("z"))
        }.forEach { (world, x, yz) ->
            placedBlocks.getOrPut(world) { ConcurrentHashMap.newKeySet() }.add(BlockPos(x, yz.first, yz.second))
        }
    }

    /** Reload regions + placed-block tracking from the database. */
    fun reloadRegions() {
        loadRegions()
        loadPlacedBlocks()
    }

    private fun applyDefaults() {
        if (!cache.containsKey("spawn")) {
            val spawnDefaults = mapOf(
                WorldFlag.PVP to false,
                WorldFlag.BLOCK_BREAK to false,
                WorldFlag.BLOCK_PLACE to false,
                WorldFlag.INTERACT to true,
                WorldFlag.CONTAINER to true,
                WorldFlag.MOB_DAMAGE to false,
                WorldFlag.HUNGER to false,
                WorldFlag.FALL_DAMAGE to false,
                WorldFlag.EXPLOSIONS to false,
                WorldFlag.ITEM_DROP to false,
                WorldFlag.ITEM_PICKUP to true,
                WorldFlag.MOB_SPAWN to false,
                WorldFlag.FIRE_SPREAD to false,
                WorldFlag.LEAF_DECAY to true,
                WorldFlag.ENDERMAN_GRIEF to true,
                WorldFlag.COMMAND_USE to true,
            )
            spawnDefaults.forEach { (flag, value) -> setFlag("spawn", flag, value) }
            plugin.logger.info("[WorldFlags] Applied default flags for 'spawn'.")
        }

        if (!cache.containsKey("resource")) {
            val resourceDefaults = mapOf(
                WorldFlag.PVP to true,
            )
            resourceDefaults.forEach { (flag, value) -> setFlag("resource", flag, value) }
            plugin.logger.info("[WorldFlags] Applied default flags for 'resource'.")
        }

        if (!cache.containsKey("afk")) {
            val afkDefaults = WorldFlag.entries.associateWith { flag ->
                flag == WorldFlag.COMMAND_USE
            }
            afkDefaults.forEach { (flag, value) -> setFlag("afk", flag, value) }
            plugin.logger.info("[WorldFlags] Applied default flags for 'afk'.")
        }
    }

    // ──────────────────────────────────────────────
    //  World-level flag API (unchanged behavior, back-compat)
    // ──────────────────────────────────────────────

    fun getFlag(worldName: String, flag: WorldFlag): Boolean {
        val stored = cache[worldName]?.get(flag)
        if (stored != null) return stored
        return flag !in defaultDenyFlags
    }

    fun setFlag(worldName: String, flag: WorldFlag, value: Boolean) {
        cache.getOrPut(worldName) { mutableMapOf() }[flag] = value
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO world_flags (world_name, flag, value) VALUES (?, ?, ?)",
            worldName, flag.name, if (value) 1 else 0
        )
    }

    fun getFlags(worldName: String): Map<WorldFlag, Boolean> {
        return WorldFlag.entries.associateWith { getFlag(worldName, it) }
    }

    fun resetFlags(worldName: String) {
        cache.remove(worldName)
        plugin.databaseManager.execute("DELETE FROM world_flags WHERE world_name = ?", worldName)
    }

    // ──────────────────────────────────────────────
    //  Region-aware resolution
    // ──────────────────────────────────────────────

    private fun hasBypass(player: Player) =
        player.hasPermission("joshymc.worldflag.bypass") || player.hasPermission(BYPASS_PERMISSION)

    /**
     * Regions containing [loc], Subflags first (highest priority first
     * within each tier), then top-level regions (highest priority first).
     * A Subflag always takes precedence over its own parent — and any other
     * top-level region — regardless of numeric priority; priority only
     * breaks ties between regions of the same tier (e.g. two overlapping
     * Subflags, or two overlapping top-level regions).
     */
    fun regionsAt(loc: Location): List<WorldFlagRegion> {
        val worldName = loc.world?.name ?: return emptyList()
        val matching = regions.values.filter { it.world == worldName && it.contains(loc) }
        val (subflags, parents) = matching.partition { it.parent != null }
        return subflags.sortedByDescending { it.priority } + parents.sortedByDescending { it.priority }
    }

    /**
     * Resolve [flag] using the highest-priority region containing [loc] that
     * explicitly defines it, falling through lower-priority regions for
     * flags they don't define. Returns null if no overlapping region defines
     * the flag at all, meaning callers should fall back to world-level/normal
     * behavior.
     */
    fun resolveFlag(loc: Location, flag: WorldFlag): Boolean? {
        for (region in regionsAt(loc)) {
            region.flags[flag]?.let { return it }
        }
        return null
    }

    /** True if a region explicitly defines [flag] at [loc] (used for player-facing messaging). */
    fun isRegionDefined(loc: Location, flag: WorldFlag): Boolean = resolveFlag(loc, flag) != null

    fun isAllowed(player: Player, flag: WorldFlag): Boolean {
        if (hasBypass(player)) return true
        return isAllowedAt(player.location, flag)
    }

    fun isAllowedAt(location: Location, flag: WorldFlag): Boolean {
        val worldName = location.world?.name ?: return true
        return resolveFlag(location, flag) ?: getFlag(worldName, flag)
    }

    // ──────────────────────────────────────────────
    //  Region CRUD
    // ──────────────────────────────────────────────

    fun getRegion(name: String): WorldFlagRegion? = regions[name.lowercase()]

    fun allRegions(): List<WorldFlagRegion> =
        regions.values.sortedWith(compareBy({ it.world }, { -it.priority }))

    fun regionsInWorld(world: String): List<WorldFlagRegion> =
        regions.values.filter { it.world == world }.sortedByDescending { it.priority }

    /** Top-level regions only (excludes Subflags), for parent-argument tab completion and listings. */
    fun topLevelRegions(): List<WorldFlagRegion> =
        regions.values.filter { it.parent == null }.sortedWith(compareBy({ it.world }, { -it.priority }))

    /** Subflags belonging to [parentName], highest priority first. */
    fun subflagsOf(parentName: String): List<WorldFlagRegion> =
        regions.values.filter { it.parent?.equals(parentName, ignoreCase = true) == true }
            .sortedByDescending { it.priority }

    fun createRegion(name: String, world: String, min: Triple<Int, Int, Int>, max: Triple<Int, Int, Int>): Boolean {
        val key = name.lowercase()
        if (regions.containsKey(key)) return false

        val region = WorldFlagRegion(
            name, world,
            min.first, min.second, min.third,
            max.first, max.second, max.third,
            priority = 0,
        )
        regions[key] = region
        persistRegion(region)
        return true
    }

    /**
     * Create a Subflag inside [parentName] — a region whose bounds must be
     * fully contained within the parent's bounds. A parent may not itself be
     * a Subflag (one level of nesting only).
     */
    fun createSubflag(
        parentName: String,
        name: String,
        world: String,
        min: Triple<Int, Int, Int>,
        max: Triple<Int, Int, Int>,
    ): SubflagCreateResult {
        val parent = regions[parentName.lowercase()] ?: return SubflagCreateResult.ParentNotFound
        if (parent.parent != null) return SubflagCreateResult.ParentIsSubflag
        if (world != parent.world) return SubflagCreateResult.WrongWorld

        val fullName = "${parent.name}.$name"
        if (regions.containsKey(fullName.lowercase())) return SubflagCreateResult.NameTaken

        val candidate = WorldFlagRegion(
            fullName, world,
            min.first, min.second, min.third,
            max.first, max.second, max.third,
            priority = 0,
        )
        if (!parent.containsRegion(candidate)) return SubflagCreateResult.OutsideParent

        val region = candidate.copy(parent = parent.name)
        regions[fullName.lowercase()] = region
        persistRegion(region)
        return SubflagCreateResult.Created(region)
    }

    private fun persistRegion(region: WorldFlagRegion) {
        plugin.databaseManager.execute(
            """
            INSERT INTO world_flag_regions (name, world, min_x, min_y, min_z, max_x, max_y, max_z, priority, parent)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            region.name, region.world, region.minX, region.minY, region.minZ,
            region.maxX, region.maxY, region.maxZ, region.priority, region.parent
        )
    }

    /**
     * Redefine a region's bounds. For a Subflag, the new bounds must remain
     * fully inside its parent's bounds — returns null if they don't.
     */
    fun redefineRegion(name: String, min: Triple<Int, Int, Int>, max: Triple<Int, Int, Int>): WorldFlagRegion? {
        val key = name.lowercase()
        val existing = regions[key] ?: return null
        val updated = existing.copy(
            minX = min.first, minY = min.second, minZ = min.third,
            maxX = max.first, maxY = max.second, maxZ = max.third,
        )
        if (existing.parent != null) {
            val parent = regions[existing.parent.lowercase()] ?: return null
            if (!parent.containsRegion(updated)) return null
        }
        regions[key] = updated
        plugin.databaseManager.execute(
            "UPDATE world_flag_regions SET min_x = ?, min_y = ?, min_z = ?, max_x = ?, max_y = ?, max_z = ? WHERE name = ?",
            min.first, min.second, min.third, max.first, max.second, max.third, existing.name
        )
        return updated
    }

    /**
     * Delete a region. Deleting a top-level region that still has Subflags
     * is refused unless [cascade] is set, in which case its Subflags are
     * deleted along with it.
     */
    fun deleteRegion(name: String, cascade: Boolean = false): DeleteResult {
        val region = regions[name.lowercase()] ?: return DeleteResult.NotFound
        val children = subflagsOf(region.name)
        if (children.isNotEmpty() && !cascade) {
            return DeleteResult.HasSubflags(children.map { it.name })
        }
        children.forEach { deleteRegionRaw(it.name) }
        deleteRegionRaw(region.name)
        return DeleteResult.Deleted(children.map { it.name })
    }

    private fun deleteRegionRaw(name: String) {
        val region = regions.remove(name.lowercase()) ?: return
        plugin.databaseManager.execute("DELETE FROM world_flag_regions WHERE name = ?", region.name)
        plugin.databaseManager.execute("DELETE FROM world_flag_region_flags WHERE region_name = ?", region.name)
    }

    fun setPriority(name: String, priority: Int): Boolean {
        val region = regions[name.lowercase()] ?: return false
        region.priority = priority
        plugin.databaseManager.execute(
            "UPDATE world_flag_regions SET priority = ? WHERE name = ?", priority, region.name
        )
        return true
    }

    /** Set a region's flag; passing null clears it back to unset/inherit. */
    fun setRegionFlag(name: String, flag: WorldFlag, value: Boolean?): Boolean {
        val region = regions[name.lowercase()] ?: return false
        if (value == null) {
            region.flags.remove(flag)
            plugin.databaseManager.execute(
                "DELETE FROM world_flag_region_flags WHERE region_name = ? AND flag = ?", region.name, flag.name
            )
        } else {
            region.flags[flag] = value
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO world_flag_region_flags (region_name, flag, value) VALUES (?, ?, ?)",
                region.name, flag.name, if (value) 1 else 0
            )
        }
        return true
    }

    /** Clears tracked player-placed blocks within a region (does not touch physical blocks). */
    fun clearPlacedBlocks(name: String): Int {
        val region = regions[name.lowercase()] ?: return 0
        val set = placedBlocks[region.world] ?: return 0
        val toRemove = set.filter { region.contains(it.x, it.y, it.z) }
        toRemove.forEach { set.remove(it) }
        plugin.databaseManager.execute(
            """
            DELETE FROM world_flag_placed_blocks
            WHERE world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?
            """.trimIndent(),
            region.world, region.minX, region.maxX, region.minY, region.maxY, region.minZ, region.maxZ
        )
        return toRemove.size
    }

    // ──────────────────────────────────────────────
    //  Player-placed block tracking
    // ──────────────────────────────────────────────

    fun isPlayerPlaced(loc: Location): Boolean {
        val world = loc.world?.name ?: return false
        val set = placedBlocks[world] ?: return false
        return BlockPos(loc.blockX, loc.blockY, loc.blockZ) in set
    }

    fun markPlaced(loc: Location, placer: UUID?) {
        val world = loc.world?.name ?: return
        placedBlocks.getOrPut(world) { ConcurrentHashMap.newKeySet() }
            .add(BlockPos(loc.blockX, loc.blockY, loc.blockZ))
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO world_flag_placed_blocks (world, x, y, z, placer, placed_at) VALUES (?, ?, ?, ?, ?, ?)",
            world, loc.blockX, loc.blockY, loc.blockZ, placer?.toString(), System.currentTimeMillis()
        )
    }

    fun unmarkPlaced(loc: Location) {
        val world = loc.world?.name ?: return
        placedBlocks[world]?.remove(BlockPos(loc.blockX, loc.blockY, loc.blockZ))
        plugin.databaseManager.execute(
            "DELETE FROM world_flag_placed_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?",
            world, loc.blockX, loc.blockY, loc.blockZ
        )
    }

    /**
     * Blocks that physically move/break together with [block] — doors, beds,
     * and tall plants — so player-placed tracking (and the "only break
     * player-placed blocks" check) can't be bypassed by targeting one half.
     */
    private fun multiBlockGroup(block: Block): List<Block> {
        val data = block.blockData
        if (data is Bed) {
            val other = if (data.part == Bed.Part.HEAD) block.getRelative(data.facing.oppositeFace) else block.getRelative(data.facing)
            return listOf(block, other)
        }
        if (data is Bisected) {
            val other = if (data.half == Bisected.Half.TOP) block.getRelative(BlockFace.DOWN) else block.getRelative(BlockFace.UP)
            return listOf(block, other)
        }
        return listOf(block)
    }

    // ──────────────────────────────────────────────
    //  Wand + region selection
    // ──────────────────────────────────────────────

    fun createWand(): ItemStack {
        val item = ItemStack(Material.BLAZE_ROD)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text("WorldFlags Wand", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )
        meta.lore(listOf(
            Component.empty(),
            Component.text("Left-click a block to set Position 1.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Right-click a block to set Position 2.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Then: /worldflag create <name>", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Or: /worldflag subflag create <parent> <name>", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
        ))
        meta.persistentDataContainer.set(wandKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    /** Both corners selected by a player with the wand, or null if incomplete/cross-world. */
    fun getSelection(player: Player): Pair<Location, Location>? {
        val p1 = pos1[player.uniqueId] ?: return null
        val p2 = pos2[player.uniqueId] ?: return null
        if (p1.world?.name != p2.world?.name) return null
        return p1 to p2
    }

    fun clearSelection(player: Player) {
        pos1.remove(player.uniqueId)
        pos2.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onWandUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.LEFT_CLICK_BLOCK && event.action != Action.RIGHT_CLICK_BLOCK) return

        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(wandKey, PersistentDataType.INTEGER)) return

        val block = event.clickedBlock ?: return
        val player = event.player
        event.isCancelled = true

        if (event.action == Action.LEFT_CLICK_BLOCK) {
            pos1[player.uniqueId] = block.location
            plugin.commsManager.send(
                player,
                Component.text("Position 1 set at (${block.x}, ${block.y}, ${block.z}). Right-click to set Position 2.", NamedTextColor.GREEN)
            )
        } else {
            pos2[player.uniqueId] = block.location
            plugin.commsManager.send(
                player,
                Component.text("Position 2 set at (${block.x}, ${block.y}, ${block.z}). Use /worldflag subflag create <parent> <name>.", NamedTextColor.GREEN)
            )
        }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.0f)
    }

    // ──────────────────────────────────────────────
    //  Deny message (with cooldown)
    // ──────────────────────────────────────────────

    private fun denyMessage(player: Player, flag: WorldFlag, area: Boolean = false) {
        if (!consumeCooldown(player, flag)) return
        val scope = if (area) "area" else "world"
        plugin.commsManager.send(
            player,
            Component.text("${flag.displayName} is disabled in this $scope.", NamedTextColor.RED)
        )
    }

    private fun denyPlacedOnlyMessage(player: Player) {
        if (!consumeCooldown(player, WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY)) return
        plugin.commsManager.send(
            player,
            Component.text("You can only break blocks placed by players in this area.", NamedTextColor.RED)
        )
    }

    private fun consumeCooldown(player: Player, flag: WorldFlag): Boolean {
        val key = player.uniqueId to flag
        val now = System.currentTimeMillis()
        val last = denyCooldowns[key] ?: 0L
        if (now - last < cooldownMs) return false
        denyCooldowns[key] = now
        return true
    }

    // ──────────────────────────────────────────────
    //  Event Listeners
    // ──────────────────────────────────────────────

    // — PVP & Mob Damage —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        val attacker = event.damager

        // PvP check — covers melee (damager is the attacking Player directly)
        // and indirect damage (arrows, tridents, thrown potions — damager is a
        // Projectile whose shooter is a Player). The victim's own location is
        // the authoritative one to resolve: a Subflag override only applies
        // to players actually standing inside it, regardless of where the
        // attacker/shooter was standing. Skip world flag entirely when either
        // player is in an arena; ArenaManager handles allow/deny at HIGHEST
        // priority for arena fights.
        val shooter = resolvePlayerSource(attacker)
        if (victim is Player && shooter != null && shooter != victim) {
            if (!hasBypass(shooter) && !isAllowedAt(victim.location, WorldFlag.PVP)) {
                if (plugin.arenaManager.isInArena(shooter) || plugin.arenaManager.isInArena(victim)) return
                event.isCancelled = true
                denyMessage(shooter, WorldFlag.PVP, isRegionDefined(victim.location, WorldFlag.PVP))
                return
            }
        }

        // Mob damage check
        if (victim is Player && attacker is Monster) {
            if (!isAllowedAt(victim.location, WorldFlag.MOB_DAMAGE)) {
                event.isCancelled = true
            }
        }
    }

    /** Resolves the Player responsible for [damager] — itself, or the shooter of a projectile. */
    private fun resolvePlayerSource(damager: Entity): Player? {
        if (damager is Player) return damager
        if (damager is Projectile) return damager.shooter as? Player
        return null
    }

    // — Splash / Lingering Potions —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPotionSplash(event: PotionSplashEvent) {
        if (!isAllowedAt(event.entity.location, WorldFlag.SPLASH_POTIONS)) {
            event.isCancelled = true
            return
        }
        event.affectedEntities.forEach { affected ->
            if (!isAllowedAt(affected.location, WorldFlag.SPLASH_POTIONS)) {
                event.setIntensity(affected, 0.0)
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLingeringPotionSplash(event: LingeringPotionSplashEvent) {
        if (!isAllowedAt(event.entity.location, WorldFlag.SPLASH_POTIONS)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onAreaEffectCloudApply(event: AreaEffectCloudApplyEvent) {
        event.affectedEntities.removeIf { !isAllowedAt(it.location, WorldFlag.SPLASH_POTIONS) }
    }

    // — Block Break —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val player = event.player
        val loc = event.block.location

        if (!isAllowed(player, WorldFlag.BLOCK_BREAK)) {
            event.isCancelled = true
            denyMessage(player, WorldFlag.BLOCK_BREAK, isRegionDefined(loc, WorldFlag.BLOCK_BREAK))
            return
        }

        if (hasBypass(player)) return

        if (resolveFlag(loc, WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY) == true) {
            val group = multiBlockGroup(event.block)
            if (group.any { !isPlayerPlaced(it.location) }) {
                event.isCancelled = true
                denyPlacedOnlyMessage(player)
                return
            }
            group.forEach { unmarkPlaced(it.location) }
        }
    }

    // — Block Place —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val player = event.player
        val block = event.blockPlaced
        val loc = block.location

        if (!isAllowed(player, WorldFlag.BLOCK_PLACE)) {
            event.isCancelled = true
            denyMessage(player, WorldFlag.BLOCK_PLACE, isRegionDefined(loc, WorldFlag.BLOCK_PLACE))
            return
        }

        if (resolveFlag(loc, WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY) == true) {
            multiBlockGroup(block).forEach { markPlaced(it.location, player.uniqueId) }
        }
    }

    /**
     * Block bucket empties (water, lava, powder snow) where BLOCK_PLACE is
     * disabled. PlayerBucketEmptyEvent is a separate event from
     * BlockPlaceEvent, so without this handler players could place water in
     * spawn even with BLOCK_PLACE off.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!isAllowed(event.player, WorldFlag.BLOCK_PLACE)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.BLOCK_PLACE, isRegionDefined(event.player.location, WorldFlag.BLOCK_PLACE))
        }
    }

    /**
     * Cancel any sign edits (right-click on un-waxed sign opens the editor)
     * in worlds where BLOCK_BREAK is denied. Backstop for the interact
     * handler in case some other plugin opens the sign editor directly.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (!isAllowed(event.player, WorldFlag.BLOCK_BREAK)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.BLOCK_BREAK, isRegionDefined(event.player.location, WorldFlag.BLOCK_BREAK))
        }
    }

    /**
     * Block projectile launches (arrows, crossbow bolts, fishing-rod bobbers,
     * snowballs, eggs, splash potions, tridents) in worlds where BLOCK_BREAK
     * is denied. Otherwise spawn-region griefing via bows / fishing rods is
     * possible even with PVP off.
     *
     * Arenas are exempt — they have BLOCK_BREAK off too (so players can't
     * mine the floor), but bows / rods are core PvP weapons inside them.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onProjectileLaunch(event: ProjectileLaunchEvent) {
        val shooter = event.entity.shooter as? Player ?: return
        if (plugin.arenaManager.isInArena(shooter)) return
        if (!isAllowed(shooter, WorldFlag.BLOCK_BREAK)) {
            event.isCancelled = true
            denyMessage(shooter, WorldFlag.BLOCK_BREAK, isRegionDefined(shooter.location, WorldFlag.BLOCK_BREAK))
        }
    }

    // — Interact —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        // Only block interactions with blocks, not general item use
        val block = event.clickedBlock ?: return
        if (!event.action.isRightClick && !event.action.isLeftClick) return

        // Dragon eggs teleport on left-click and drop on punch — both bypass
        // BlockBreakEvent. In worlds where BLOCK_BREAK is denied (e.g. spawn),
        // any interact with a dragon egg should be cancelled outright so
        // players can't snag it from spawn decor.
        if (block.type == Material.DRAGON_EGG &&
            !isAllowed(event.player, WorldFlag.BLOCK_BREAK)
        ) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.BLOCK_BREAK, isRegionDefined(block.location, WorldFlag.BLOCK_BREAK))
            return
        }

        // Doors / trapdoors / fence gates / signs in build-protected worlds
        // (e.g. spawn) — players were popping these open / editing them even
        // with INTERACT=true because INTERACT is meant for buttons / clicks.
        // Block them when BLOCK_BREAK is denied so spawn stays clean.
        val type = block.type
        val typeName = type.name
        val isDoorLike =
            typeName.endsWith("_DOOR") ||
            typeName.endsWith("_TRAPDOOR") ||
            typeName.endsWith("_FENCE_GATE")
        val isSign =
            typeName.endsWith("_SIGN") ||
            typeName.endsWith("_HANGING_SIGN") ||
            typeName.endsWith("_WALL_SIGN") ||
            typeName.endsWith("_WALL_HANGING_SIGN")
        if ((isDoorLike || isSign) && !isAllowed(event.player, WorldFlag.BLOCK_BREAK)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.BLOCK_BREAK, isRegionDefined(block.location, WorldFlag.BLOCK_BREAK))
            return
        }

        // Server-managed interactables (crates) always work, even in worlds
        // where INTERACT is denied — otherwise spawn-region crates would be
        // unreachable for everyone without bypass.
        if (plugin.crateManager.getCrateTypeAt(block) != null) return

        // Container flag — blocks chest/barrel/shulker access independently
        // of the broader INTERACT flag. Ender Chests are exempt: their
        // contents are personal to the player, not a shared/world inventory,
        // so they stay usable even when Containers = DENY.
        if (isContainer(block.type) && block.type != Material.ENDER_CHEST &&
            !isAllowed(event.player, WorldFlag.CONTAINER)
        ) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.CONTAINER, isRegionDefined(block.location, WorldFlag.CONTAINER))
            return
        }

        if (!isAllowed(event.player, WorldFlag.INTERACT)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.INTERACT, isRegionDefined(block.location, WorldFlag.INTERACT))
        }
    }

    // — Ender Pearls —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerTeleport(event: PlayerTeleportEvent) {
        if (event.cause != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return
        val player = event.player
        if (!isAllowed(player, WorldFlag.ENDER_PEARL)) {
            event.isCancelled = true
            denyMessage(player, WorldFlag.ENDER_PEARL, isRegionDefined(player.location, WorldFlag.ENDER_PEARL))
        }
    }

    // — Elytra —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onToggleGlide(event: EntityToggleGlideEvent) {
        if (!event.isGliding) return
        val player = event.entity as? Player ?: return
        if (!isAllowed(player, WorldFlag.ELYTRA)) {
            event.isCancelled = true
            denyMessage(player, WorldFlag.ELYTRA, isRegionDefined(player.location, WorldFlag.ELYTRA))
        }
    }

    // — Hunger —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (isAllowedAt(player.location, WorldFlag.HUNGER)) return

        // HUNGER flag means "allow hunger drain". When it's off we should
        // only cancel DECREASES — eating (which raises foodLevel) and
        // saturation buffs from food still need to go through, otherwise
        // food in spawn does nothing.
        if (event.foodLevel < player.foodLevel) {
            event.isCancelled = true
        }
    }

    // — Fall Damage —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFallDamage(event: EntityDamageEvent) {
        if (event.cause != EntityDamageEvent.DamageCause.FALL) return
        val player = event.entity as? Player ?: return
        if (!isAllowedAt(player.location, WorldFlag.FALL_DAMAGE)) {
            event.isCancelled = true
        }
    }

    // — Explosions —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        filterExplosion(event.blockList())
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        filterExplosion(event.blockList())
    }

    /**
     * Region-aware, per-block explosion filtering. Removes blocks whose own
     * region/world denies EXPLOSIONS, and — independently — removes
     * "original" protected blocks under active player-placed-only
     * protection so TNT/creepers can't be used to bypass it. Any
     * player-placed block that does get destroyed is un-tracked.
     */
    private fun filterExplosion(blocks: MutableList<Block>) {
        blocks.removeIf { block ->
            val loc = block.location
            if (!isAllowedAt(loc, WorldFlag.EXPLOSIONS)) return@removeIf true
            resolveFlag(loc, WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY) == true && !isPlayerPlaced(loc)
        }
        blocks.forEach { unmarkPlaced(it.location) }
    }

    // — Pistons —

    /**
     * Prevent pistons from being used to bypass player-placed-only
     * protection: pushing/pulling an "original" protected block cancels the
     * whole move, while a moved player-placed block keeps its tracking by
     * following it to the new location.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        handlePistonMove(event, event.blocks, event.direction)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        handlePistonMove(event, event.blocks, event.direction)
    }

    private fun handlePistonMove(event: Cancellable, blocks: List<Block>, direction: BlockFace) {
        if (blocks.isEmpty()) return

        for (block in blocks) {
            if (resolveFlag(block.location, WorldFlag.BLOCK_BREAK_PLAYER_PLACED_ONLY) == true &&
                !isPlayerPlaced(block.location)
            ) {
                event.isCancelled = true
                return
            }
        }

        val moved = blocks.filter { isPlayerPlaced(it.location) }
            .map { it.location to it.getRelative(direction).location }
        if (moved.isEmpty()) return

        // Run next tick — the blocks haven't physically moved yet while this
        // event is being processed.
        Bukkit.getScheduler().runTask(plugin, Runnable {
            moved.forEach { (from, to) ->
                unmarkPlaced(from)
                markPlaced(to, null)
            }
        })
    }

    // — Item Drop —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (!isAllowed(event.player, WorldFlag.ITEM_DROP)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.ITEM_DROP, isRegionDefined(event.player.location, WorldFlag.ITEM_DROP))
        }
    }

    // — Item Pickup —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        if (!isAllowed(player, WorldFlag.ITEM_PICKUP)) {
            event.isCancelled = true
        }
    }

    // — Mob Spawn —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason != CreatureSpawnEvent.SpawnReason.NATURAL &&
            event.spawnReason != CreatureSpawnEvent.SpawnReason.SPAWNER
        ) return

        if (!isAllowedAt(event.location, WorldFlag.MOB_SPAWN)) {
            event.isCancelled = true
        }
    }

    // Block mobs from entering worlds with MOB_SPAWN disabled via portals (end portals, nether portals, etc.)
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityPortal(event: EntityPortalEvent) {
        if (event.entity is Player) return
        val dest = event.to ?: return
        if (!isAllowedAt(dest, WorldFlag.MOB_SPAWN)) {
            event.isCancelled = true
        }
    }

    // — Fire Spread —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (!isAllowedAt(event.block.location, WorldFlag.FIRE_SPREAD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (event.source.type.name.contains("FIRE", ignoreCase = true)) {
            if (!isAllowedAt(event.block.location, WorldFlag.FIRE_SPREAD)) {
                event.isCancelled = true
            }
        }
    }

    // — Leaf Decay —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        if (!isAllowedAt(event.block.location, WorldFlag.LEAF_DECAY)) {
            event.isCancelled = true
        }
    }

    // — Enderman Grief / Crop Trampling —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity is Enderman) {
            if (!isAllowedAt(event.block.location, WorldFlag.ENDERMAN_GRIEF)) {
                event.isCancelled = true
                return
            }
        }

        // Players trampling farmland fires EntityChangeBlockEvent with the
        // FARMLAND block. Block it in worlds where BLOCK_BREAK is disabled
        // so crops at spawn can't be destroyed by jumping.
        if (event.entity is Player && event.block.type == Material.FARMLAND) {
            if (!isAllowed(event.entity as Player, WorldFlag.BLOCK_BREAK)) {
                event.isCancelled = true
            }
        }
    }
}
