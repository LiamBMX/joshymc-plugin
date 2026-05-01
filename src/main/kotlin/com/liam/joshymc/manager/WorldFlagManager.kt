package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Enderman
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.Material
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
        INTERACT("Interact", "Allow interacting with blocks (doors, chests, buttons)"),
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
        COMMAND_USE("Command Use", "Allow command usage (non-staff)"),
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

        loadCache()
        applyDefaults()

        Bukkit.getPluginManager().registerEvents(this, plugin)
        plugin.logger.info("[WorldFlags] Loaded flags for ${cache.size} world(s).")
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

    private fun applyDefaults() {
        if (!cache.containsKey("spawn")) {
            val spawnDefaults = mapOf(
                WorldFlag.PVP to false,
                WorldFlag.BLOCK_BREAK to false,
                WorldFlag.BLOCK_PLACE to false,
                WorldFlag.INTERACT to true,
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
    //  Public API
    // ──────────────────────────────────────────────

    fun getFlag(worldName: String, flag: WorldFlag): Boolean {
        return cache[worldName]?.get(flag) ?: true
    }

    fun setFlag(worldName: String, flag: WorldFlag, value: Boolean) {
        cache.getOrPut(worldName) { mutableMapOf() }[flag] = value
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO world_flags (world_name, flag, value) VALUES (?, ?, ?)",
            worldName, flag.name, if (value) 1 else 0
        )
    }

    fun getFlags(worldName: String): Map<WorldFlag, Boolean> {
        val stored = cache[worldName] ?: emptyMap()
        return WorldFlag.entries.associateWith { stored[it] ?: true }
    }

    fun isAllowed(player: Player, flag: WorldFlag): Boolean {
        if (player.hasPermission("joshymc.worldflag.bypass")) return true
        return getFlag(player.world.name, flag)
    }

    fun resetFlags(worldName: String) {
        cache.remove(worldName)
        plugin.databaseManager.execute("DELETE FROM world_flags WHERE world_name = ?", worldName)
    }

    // ──────────────────────────────────────────────
    //  Deny message (with cooldown)
    // ──────────────────────────────────────────────

    private fun denyMessage(player: Player, flag: WorldFlag) {
        val key = player.uniqueId to flag
        val now = System.currentTimeMillis()
        val last = denyCooldowns[key] ?: 0L
        if (now - last < cooldownMs) return
        denyCooldowns[key] = now

        plugin.commsManager.send(
            player,
            Component.text("${flag.displayName} is disabled in this world.", NamedTextColor.RED)
        )
    }

    // ──────────────────────────────────────────────
    //  Event Listeners
    // ──────────────────────────────────────────────

    // — PVP & Mob Damage —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity
        val attacker = event.damager

        // PvP check
        if (victim is Player && attacker is Player) {
            if (!isAllowed(attacker, WorldFlag.PVP)) {
                event.isCancelled = true
                denyMessage(attacker, WorldFlag.PVP)
                return
            }
        }

        // Mob damage check
        if (victim is Player && attacker is Monster) {
            if (!getFlag(victim.world.name, WorldFlag.MOB_DAMAGE)) {
                event.isCancelled = true
            }
        }
    }

    // — Block Break —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!isAllowed(event.player, WorldFlag.BLOCK_BREAK)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.BLOCK_BREAK)
        }
    }

    // — Block Place —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!isAllowed(event.player, WorldFlag.BLOCK_PLACE)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.BLOCK_PLACE)
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
            denyMessage(event.player, WorldFlag.BLOCK_PLACE)
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
            denyMessage(event.player, WorldFlag.BLOCK_BREAK)
            return
        }

        // Server-managed interactables (crates) always work, even in worlds
        // where INTERACT is denied — otherwise spawn-region crates would be
        // unreachable for everyone without bypass.
        if (plugin.crateManager.getCrateTypeAt(block) != null) return

        if (!isAllowed(event.player, WorldFlag.INTERACT)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.INTERACT)
        }
    }

    // — Hunger —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onFoodLevelChange(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (getFlag(player.world.name, WorldFlag.HUNGER)) return

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
        if (!getFlag(player.world.name, WorldFlag.FALL_DAMAGE)) {
            event.isCancelled = true
        }
    }

    // — Explosions —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val worldName = event.location.world.name
        if (!getFlag(worldName, WorldFlag.EXPLOSIONS)) {
            event.blockList().clear()
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val worldName = event.block.world.name
        if (!getFlag(worldName, WorldFlag.EXPLOSIONS)) {
            event.blockList().clear()
        }
    }

    // — Item Drop —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        if (!isAllowed(event.player, WorldFlag.ITEM_DROP)) {
            event.isCancelled = true
            denyMessage(event.player, WorldFlag.ITEM_DROP)
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

        val worldName = event.entity.world.name
        if (!getFlag(worldName, WorldFlag.MOB_SPAWN)) {
            event.isCancelled = true
        }
    }

    // — Fire Spread —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        if (!getFlag(event.block.world.name, WorldFlag.FIRE_SPREAD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (event.source.type.name.contains("FIRE", ignoreCase = true)) {
            if (!getFlag(event.block.world.name, WorldFlag.FIRE_SPREAD)) {
                event.isCancelled = true
            }
        }
    }

    // — Leaf Decay —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        if (!getFlag(event.block.world.name, WorldFlag.LEAF_DECAY)) {
            event.isCancelled = true
        }
    }

    // — Enderman Grief —

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity is Enderman) {
            if (!getFlag(event.entity.world.name, WorldFlag.ENDERMAN_GRIEF)) {
                event.isCancelled = true
            }
        }
    }
}
