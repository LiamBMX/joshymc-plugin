package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BuildPvpManager(private val plugin: Joshymc) : Listener {

    data class BuildRegion(
        val world: String,
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int
    )

    var region: BuildRegion? = null
        private set

    private val wandKey = NamespacedKey(plugin, "build_pvp_wand")

    // Per-player first corner; second click finalizes and clears
    private val pos1 = mutableMapOf<UUID, Triple<Int, Int, Int>>()

    private var bossBar: BossBar? = null
    private var bossBarTaskId = -1
    private var resetTaskId = -1

    private val RESET_INTERVAL_MS = 30L * 60L * 1000L
    private var nextResetMs = 0L

    private lateinit var configFile: File
    private lateinit var snapshotFile: File

    private val comms get() = plugin.commsManager

    // ── Lifecycle ─────────────────────────────────────────

    fun start() {
        configFile = plugin.configFile("buildpvp.yml")
        snapshotFile = File(plugin.dataFolder, "buildpvp_snapshot.gz")

        loadConfig()
        Bukkit.getPluginManager().registerEvents(this, plugin)

        if (region != null) {
            startBossBar()
            scheduleReset()
        }
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

    // ── Config ────────────────────────────────────────────

    private fun loadConfig() {
        if (!configFile.exists()) return
        val cfg = YamlConfiguration.loadConfiguration(configFile)
        val world = cfg.getString("region.world") ?: return
        region = BuildRegion(
            world,
            cfg.getInt("region.min-x"), cfg.getInt("region.min-y"), cfg.getInt("region.min-z"),
            cfg.getInt("region.max-x"), cfg.getInt("region.max-y"), cfg.getInt("region.max-z")
        )
        nextResetMs = cfg.getLong("next-reset-ms", System.currentTimeMillis() + RESET_INTERVAL_MS)
    }

    private fun saveConfig() {
        val cfg = YamlConfiguration()
        val r = region
        if (r != null) {
            cfg.set("region.world", r.world)
            cfg.set("region.min-x", r.minX); cfg.set("region.min-y", r.minY); cfg.set("region.min-z", r.minZ)
            cfg.set("region.max-x", r.maxX); cfg.set("region.max-y", r.maxY); cfg.set("region.max-z", r.maxZ)
        }
        cfg.set("next-reset-ms", nextResetMs)
        try { cfg.save(configFile) } catch (e: Exception) {
            plugin.logger.warning("[BuildPvp] Failed to save config: ${e.message}")
        }
    }

    // ── Snapshot ──────────────────────────────────────────

    fun takeSnapshot() {
        val r = region ?: return
        val world = Bukkit.getWorld(r.world) ?: return
        try {
            GZIPOutputStream(FileOutputStream(snapshotFile)).bufferedWriter().use { writer ->
                for (x in r.minX..r.maxX) {
                    for (y in r.minY..r.maxY) {
                        for (z in r.minZ..r.maxZ) {
                            val block = world.getBlockAt(x, y, z)
                            writer.write("$x,$y,$z,${block.blockData.asString}")
                            writer.newLine()
                        }
                    }
                }
            }
            val volume = (r.maxX - r.minX + 1) * (r.maxY - r.minY + 1) * (r.maxZ - r.minZ + 1)
            plugin.logger.info("[BuildPvp] Snapshot saved ($volume blocks).")
        } catch (e: Exception) {
            plugin.logger.warning("[BuildPvp] Failed to save snapshot: ${e.message}")
        }
    }

    private fun restoreSnapshot() {
        val r = region ?: return
        val world = Bukkit.getWorld(r.world) ?: return
        if (!snapshotFile.exists()) {
            plugin.logger.warning("[BuildPvp] No snapshot file found, cannot restore.")
            return
        }
        try {
            // Clear the region top-down so falling blocks don't cascade
            for (x in r.minX..r.maxX) {
                for (z in r.minZ..r.maxZ) {
                    for (y in r.maxY downTo r.minY) {
                        world.getBlockAt(x, y, z).setType(Material.AIR, false)
                    }
                }
            }
            GZIPInputStream(FileInputStream(snapshotFile)).bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val parts = line.split(",", limit = 4)
                    if (parts.size < 4) return@forEachLine
                    val x = parts[0].toIntOrNull() ?: return@forEachLine
                    val y = parts[1].toIntOrNull() ?: return@forEachLine
                    val z = parts[2].toIntOrNull() ?: return@forEachLine
                    world.getBlockAt(x, y, z).blockData = Bukkit.createBlockData(parts[3])
                }
            }
            plugin.logger.info("[BuildPvp] Arena restored to snapshot.")
        } catch (e: Exception) {
            plugin.logger.warning("[BuildPvp] Failed to restore snapshot: ${e.message}")
        }
    }

    // ── Reset cycle ───────────────────────────────────────

    fun resetArena() {
        Bukkit.getOnlinePlayers().forEach { p ->
            comms.send(p, Component.text("The Build PvP arena is resetting!", NamedTextColor.YELLOW))
        }
        restoreSnapshot()
        nextResetMs = System.currentTimeMillis() + RESET_INTERVAL_MS
        saveConfig()
        if (resetTaskId != -1) { Bukkit.getScheduler().cancelTask(resetTaskId); resetTaskId = -1 }
        scheduleReset()
        updateBossBar()
    }

    private fun scheduleReset() {
        val delayMs = (nextResetMs - System.currentTimeMillis()).coerceAtLeast(0)
        val delayTicks = (delayMs / 50L).coerceAtLeast(1)
        resetTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, Runnable { resetArena() }, delayTicks)
    }

    // ── Boss bar ──────────────────────────────────────────

    private fun startBossBar() {
        bossBar?.removeAll()
        bossBar = Bukkit.createBossBar("Build PvP", BarColor.GREEN, BarStyle.SEGMENTED_10)
        val worldName = region?.world
        Bukkit.getOnlinePlayers().filter { it.world.name == worldName }.forEach { bossBar?.addPlayer(it) }
        updateBossBar()
        bossBarTaskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(plugin, Runnable { updateBossBar() }, 0L, 20L)
    }

    private fun updateBossBarVisibility(player: Player) {
        val bar = bossBar ?: return
        if (player.world.name == region?.world) bar.addPlayer(player) else bar.removePlayer(player)
    }

    private fun updateBossBar() {
        val bar = bossBar ?: return
        val remaining = (nextResetMs - System.currentTimeMillis()).coerceAtLeast(0)
        val progress = (remaining.toDouble() / RESET_INTERVAL_MS).coerceIn(0.0, 1.0)
        val minutes = (remaining / 60000L).toInt()
        val seconds = ((remaining % 60000L) / 1000L).toInt()
        bar.setTitle("Build PvP resets in ${minutes}m ${seconds}s")
        bar.progress = progress
        bar.color = when {
            progress > 0.50 -> BarColor.GREEN
            progress > 0.15 -> BarColor.YELLOW
            else -> BarColor.RED
        }
    }

    // ── Region helpers ────────────────────────────────────

    fun isInRegion(x: Int, y: Int, z: Int): Boolean {
        val r = region ?: return false
        return x in r.minX..r.maxX && y in r.minY..r.maxY && z in r.minZ..r.maxZ
    }

    // ── Wand item ─────────────────────────────────────────

    fun createWand(): ItemStack {
        val item = ItemStack(Material.BLAZE_ROD)
        val meta = item.itemMeta!!
        meta.displayName(
            Component.text("Build PvP Wand", NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false)
                .decoration(TextDecoration.BOLD, true)
        )
        meta.lore(listOf(
            Component.empty(),
            Component.text("Right-click to set corner 1.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false),
            Component.text("Right-click again to set corner 2 and save.", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)
        ))
        meta.persistentDataContainer.set(wandKey, PersistentDataType.INTEGER, 1)
        item.itemMeta = meta
        return item
    }

    // ── Events ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    fun onWandUse(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return
        val item = event.item ?: return
        val meta = item.itemMeta ?: return
        if (!meta.persistentDataContainer.has(wandKey, PersistentDataType.INTEGER)) return

        event.isCancelled = true
        val player = event.player
        val block = event.clickedBlock ?: return
        val uuid = player.uniqueId

        if (!pos1.containsKey(uuid)) {
            pos1[uuid] = Triple(block.x, block.y, block.z)
            comms.send(player, Component.text("Corner 1 set at (${block.x}, ${block.y}, ${block.z}). Right-click to set corner 2.", NamedTextColor.GREEN))
            player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.7f, 1.0f)
        } else {
            val p1 = pos1.remove(uuid)!!
            val newRegion = BuildRegion(
                player.world.name,
                minOf(p1.first, block.x), minOf(p1.second, block.y), minOf(p1.third, block.z),
                maxOf(p1.first, block.x), maxOf(p1.second, block.y), maxOf(p1.third, block.z)
            )
            setRegion(newRegion, player)
        }
    }

    private fun setRegion(newRegion: BuildRegion, notifyPlayer: Player? = null) {
        region = newRegion
        nextResetMs = System.currentTimeMillis() + RESET_INTERVAL_MS
        saveConfig()
        takeSnapshot()

        if (resetTaskId != -1) { Bukkit.getScheduler().cancelTask(resetTaskId); resetTaskId = -1 }
        if (bossBarTaskId != -1) { Bukkit.getScheduler().cancelTask(bossBarTaskId); bossBarTaskId = -1 }
        startBossBar()
        scheduleReset()

        if (notifyPlayer != null) {
            val r = newRegion
            val dx = r.maxX - r.minX + 1
            val dy = r.maxY - r.minY + 1
            val dz = r.maxZ - r.minZ + 1
            comms.send(
                notifyPlayer,
                Component.text("Build PvP arena set! Region: ${dx}x${dy}x${dz} in ${r.world}. Snapshot saved. Resets every 30 minutes.", NamedTextColor.GREEN)
            )
            notifyPlayer.playSound(notifyPlayer.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.0f)
        }
        plugin.logger.info("[BuildPvp] Region set: ${newRegion.world} (${newRegion.minX},${newRegion.minY},${newRegion.minZ}) to (${newRegion.maxX},${newRegion.maxY},${newRegion.maxZ})")
    }

    fun clearRegion(notifyPlayer: Player? = null) {
        region = null
        if (resetTaskId != -1) { Bukkit.getScheduler().cancelTask(resetTaskId); resetTaskId = -1 }
        if (bossBarTaskId != -1) { Bukkit.getScheduler().cancelTask(bossBarTaskId); bossBarTaskId = -1 }
        bossBar?.removeAll()
        bossBar = null
        saveConfig()
        if (notifyPlayer != null) {
            comms.send(notifyPlayer, Component.text("Build PvP arena cleared.", NamedTextColor.YELLOW))
        }
    }

    // Allow all players to build inside the region, overriding claim protection.
    // Running at HIGHEST so claims (HIGH) have already decided, then we override.

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockBreak(event: BlockBreakEvent) {
        val r = region ?: return
        val b = event.block
        if (b.world.name != r.world) return
        if (!isInRegion(b.x, b.y, b.z)) return
        // Inside the build pvp region — allow it regardless of claims
        event.isCancelled = false
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val r = region ?: return
        val b = event.blockPlaced
        if (b.world.name != r.world) return
        if (!isInRegion(b.x, b.y, b.z)) return
        event.isCancelled = false
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        updateBossBarVisibility(event.player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        bossBar?.removePlayer(event.player)
        pos1.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        updateBossBarVisibility(event.player)
    }
}
