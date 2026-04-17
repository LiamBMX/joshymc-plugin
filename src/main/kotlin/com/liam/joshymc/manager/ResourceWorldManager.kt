package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.entity.Player
import java.io.File
import kotlin.random.Random

class ResourceWorldManager(private val plugin: Joshymc) : Listener {

    private val commsManager get() = plugin.commsManager
    private val db get() = plugin.databaseManager

    private var enabled = true
    private var resetIntervalHours = 24
    private var worldName = "resource"
    private var borderSize = 5000.0

    private var nextResetTime = 0L
    private var bossBar: BossBar? = null
    private var bossBarTaskId = -1
    private var resetTaskId = -1

    fun start() {
        loadConfig()
        if (!enabled) return

        initDatabase()
        loadNextResetTime()

        // Create the world if it doesn't exist
        if (Bukkit.getWorld(worldName) == null) {
            createResourceWorld()
        }

        // If reset is overdue, reset immediately
        if (System.currentTimeMillis() >= nextResetTime) {
            plugin.logger.info("[ResourceWorld] Reset is overdue, resetting now.")
            resetWorld()
        }

        // Create boss bar
        bossBar = Bukkit.createBossBar("Resource World", BarColor.GREEN, BarStyle.SEGMENTED_6)

        // Add players already in the resource world
        Bukkit.getOnlinePlayers().forEach { updateBossBarVisibility(it) }

        // Register events
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // Boss bar update task (every minute = 1200 ticks)
        bossBarTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable {
            updateBossBar()
        }, 0L, 1200L)

        // Schedule the next reset
        scheduleReset()

        plugin.logger.info("[ResourceWorld] Started. Next reset in ${formatTimeRemaining()}.")
    }

    fun stop() {
        if (bossBarTaskId != -1) {
            Bukkit.getScheduler().cancelTask(bossBarTaskId)
            bossBarTaskId = -1
        }
        if (resetTaskId != -1) {
            Bukkit.getScheduler().cancelTask(resetTaskId)
            resetTaskId = -1
        }
        bossBar?.removeAll()
        bossBar = null
    }

    private fun loadConfig() {
        enabled = plugin.config.getBoolean("resource-world.enabled", true)
        resetIntervalHours = plugin.config.getInt("resource-world.reset-interval-hours", 24)
        worldName = plugin.config.getString("resource-world.world-name") ?: "resource"
        // Prefer the unified world-borders.resource setting; fall back to the legacy field
        borderSize = plugin.config.getDouble(
            "world-borders.resource",
            plugin.config.getDouble("resource-world.border-size", 10000.0)
        )
    }

    private fun initDatabase() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS resource_world_data (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """.trimIndent())
    }

    private fun loadNextResetTime() {
        val stored = db.queryFirst(
            "SELECT value FROM resource_world_data WHERE key = ?",
            "next_reset_time"
        ) { rs -> rs.getString("value").toLongOrNull() }

        nextResetTime = if (stored != null && stored > 0) {
            stored
        } else {
            val time = System.currentTimeMillis() + (resetIntervalHours * 3600000L)
            saveNextResetTime(time)
            time
        }
    }

    private fun saveNextResetTime(time: Long = nextResetTime) {
        db.execute(
            "INSERT OR REPLACE INTO resource_world_data (key, value) VALUES (?, ?)",
            "next_reset_time", time.toString()
        )
    }

    fun resetWorld() {
        val world = Bukkit.getWorld(worldName)

        if (world != null) {
            // Teleport all players to main world spawn
            val mainSpawn = Bukkit.getWorlds()[0].spawnLocation
            for (player in world.players) {
                player.teleport(mainSpawn)
                commsManager.send(
                    player,
                    Component.text("Resource world is resetting!", NamedTextColor.YELLOW)
                )
            }

            // Unload and delete
            Bukkit.unloadWorld(world, false)
            deleteWorldFolder(world.worldFolder)
        }

        // Recreate
        createResourceWorld()

        // Update timer
        nextResetTime = System.currentTimeMillis() + (resetIntervalHours * 3600000L)
        saveNextResetTime()

        // Reschedule
        if (resetTaskId != -1) {
            Bukkit.getScheduler().cancelTask(resetTaskId)
            resetTaskId = -1
        }
        scheduleReset()

        // Update boss bar immediately
        updateBossBar()

        plugin.logger.info("[ResourceWorld] World has been reset. Next reset in ${formatTimeRemaining()}.")
    }

    private fun createResourceWorld() {
        val world = WorldCreator(worldName)
            .environment(World.Environment.NORMAL)
            .createWorld()

        if (world != null) {
            world.worldBorder.center = Location(world, 0.0, 0.0, 0.0)
            world.worldBorder.size = borderSize
            plugin.logger.info("[ResourceWorld] Created world '$worldName' with border size $borderSize.")
        } else {
            plugin.logger.warning("[ResourceWorld] Failed to create world '$worldName'!")
        }
    }

    private fun deleteWorldFolder(folder: File) {
        if (folder.exists()) {
            folder.deleteRecursively()
            plugin.logger.info("[ResourceWorld] Deleted world folder: ${folder.path}")
        }
    }

    private fun scheduleReset() {
        val delayMs = (nextResetTime - System.currentTimeMillis()).coerceAtLeast(0)
        val delayTicks = (delayMs / 50L).coerceAtLeast(1)

        resetTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable {
            resetWorld()
        }, delayTicks)
    }

    // ── Boss Bar ──────────────────────────────────────────────

    private fun updateBossBar() {
        val bar = bossBar ?: return
        val intervalMs = resetIntervalHours * 3600000L
        val remainingMs = (nextResetTime - System.currentTimeMillis()).coerceAtLeast(0)
        val progress = (remainingMs.toDouble() / intervalMs).coerceIn(0.0, 1.0)

        val hours = (remainingMs / 3600000L).toInt()
        val minutes = ((remainingMs % 3600000L) / 60000L).toInt()

        bar.setTitle("Resource World resets in ${hours}h ${minutes}m")
        bar.progress = progress
        bar.color = when {
            progress > 0.50 -> BarColor.GREEN
            progress > 0.25 -> BarColor.YELLOW
            else -> BarColor.RED
        }
    }

    private fun updateBossBarVisibility(player: Player) {
        val bar = bossBar ?: return
        val inResourceWorld = player.world.name == worldName
        if (inResourceWorld) {
            bar.addPlayer(player)
        } else {
            bar.removePlayer(player)
        }
    }

    // ── Events ────────────────────────────────────────────────

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        updateBossBarVisibility(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        bossBar?.removePlayer(event.player)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        updateBossBarVisibility(event.player)
    }

    // ── Teleport ──────────────────────────────────────────────

    fun teleportToResource(player: Player) {
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            commsManager.send(player, Component.text("Resource world is not available right now.", NamedTextColor.RED))
            return
        }

        commsManager.send(player, Component.text("Finding a safe location...", NamedTextColor.GRAY))
        findSafeSpot(player, world, 0)
    }

    private fun findSafeSpot(player: Player, world: org.bukkit.World, attempt: Int) {
        if (attempt >= 15) {
            // Fallback: teleport to world spawn
            player.teleport(world.spawnLocation)
            commsManager.send(player, Component.text("Teleported to resource world spawn.", NamedTextColor.GREEN))
            return
        }

        val maxRange = (borderSize / 2.0 - 50).coerceAtLeast(100.0).toInt()
        val x = Random.nextInt(-maxRange, maxRange + 1)
        val z = Random.nextInt(-maxRange, maxRange + 1)

        // Async chunk load then check safety on main thread
        world.getChunkAtAsync(x shr 4, z shr 4).thenAccept { _ ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable

                val highestY = world.getHighestBlockYAt(x, z)
                if (highestY < world.minHeight + 1) {
                    findSafeSpot(player, world, attempt + 1)
                    return@Runnable
                }

                val ground = world.getBlockAt(x, highestY, z)
                val above1 = world.getBlockAt(x, highestY + 1, z)
                val above2 = world.getBlockAt(x, highestY + 2, z)

                // Reject: lava, water, non-solid ground, leaves/trees, solid blocks above
                val unsafeGroundTypes = setOf(
                    Material.LAVA, Material.WATER, Material.MAGMA_BLOCK,
                    Material.CACTUS, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW,
                    Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.FIRE, Material.SOUL_FIRE
                )

                if (ground.type in unsafeGroundTypes
                    || !ground.type.isSolid
                    || ground.type.name.contains("LEAVES")
                    || above1.type.isSolid
                    || above2.type.isSolid
                    || above1.isLiquid
                    || above2.isLiquid
                ) {
                    findSafeSpot(player, world, attempt + 1)
                    return@Runnable
                }

                val safeLoc = Location(world, x + 0.5, highestY + 1.0, z + 0.5,
                    player.location.yaw, player.location.pitch)
                player.teleport(safeLoc)
                commsManager.send(player, Component.text("Teleported to the resource world!", NamedTextColor.GREEN))
            })
        }
    }

    // ── Util ──────────────────────────────────────────────────

    private fun formatTimeRemaining(): String {
        val remainingMs = (nextResetTime - System.currentTimeMillis()).coerceAtLeast(0)
        val hours = (remainingMs / 3600000L).toInt()
        val minutes = ((remainingMs % 3600000L) / 60000L).toInt()
        return "${hours}h ${minutes}m"
    }
}
