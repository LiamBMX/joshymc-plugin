package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.generator.ChunkGenerator
import org.bukkit.inventory.ItemStack
import java.time.Duration
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AFKManager(private val plugin: Joshymc) {

    private val afkPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val preAfkLocations = ConcurrentHashMap<UUID, Location>()
    private val teleporting = ConcurrentHashMap.newKeySet<UUID>()

    private var worldName = "afk"
    private var rewardEnabled = true
    private var rewardMaterial = Material.DIRT
    private var rewardAmount = 1
    private var rewardIntervalTicks = 200L
    private var rewardIntervalSeconds = 10L

    private var rewardTaskId = -1
    private var countdownTaskId = -1

    // Track per-player time until next reward for title display
    private val nextRewardTime = ConcurrentHashMap<UUID, Long>()
    // Track when player went AFK
    private val afkStartTime = ConcurrentHashMap<UUID, Long>()

    fun start() {
        worldName = plugin.config.getString("afk.world", "afk") ?: "afk"
        rewardEnabled = plugin.config.getBoolean("afk.reward.enabled", true)

        val materialName = plugin.config.getString("afk.reward.item", "DIRT") ?: "DIRT"
        rewardMaterial = Material.matchMaterial(materialName) ?: Material.DIRT

        rewardAmount = plugin.config.getInt("afk.reward.amount", 1)

        val interval = plugin.config.getLong("afk.reward.interval", 10)
        val unit = plugin.config.getString("afk.reward.unit", "seconds") ?: "seconds"
        rewardIntervalSeconds = when (unit.lowercase()) {
            "minutes" -> interval * 60
            else -> interval
        }
        rewardIntervalTicks = rewardIntervalSeconds * 20

        // Create AFK void world if it doesn't exist
        ensureAfkWorld()

        if (rewardEnabled) {
            // Reward task
            rewardTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
                giveRewards()
            }, rewardIntervalTicks, rewardIntervalTicks)

            // Countdown title task — runs every second
            countdownTaskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
                updateCountdownTitles()
            }, 20L, 20L)
        }

        plugin.logger.info("[AFK] Manager started (world=$worldName, reward=${if (rewardEnabled) "$rewardAmount ${rewardMaterial.name} every ${rewardIntervalSeconds}s" else "disabled"}).")
    }

    fun stop() {
        if (rewardTaskId != -1) {
            plugin.server.scheduler.cancelTask(rewardTaskId)
            rewardTaskId = -1
        }
        if (countdownTaskId != -1) {
            plugin.server.scheduler.cancelTask(countdownTaskId)
            countdownTaskId = -1
        }
        afkPlayers.clear()
        preAfkLocations.clear()
        teleporting.clear()
        nextRewardTime.clear()
        afkStartTime.clear()
    }

    fun toggleAfk(player: Player): Boolean {
        return if (isAfk(player)) {
            setAfk(player, false)
            false
        } else {
            setAfk(player, true)
            true
        }
    }

    fun setAfk(player: Player, afk: Boolean) {
        if (afk) {
            afkPlayers.add(player.uniqueId)
            preAfkLocations[player.uniqueId] = player.location.clone()
            afkStartTime[player.uniqueId] = System.currentTimeMillis()
            nextRewardTime[player.uniqueId] = System.currentTimeMillis() + (rewardIntervalSeconds * 1000)

            // Update display name with [AFK] tag
            player.displayName(
                Component.text("[AFK] ", NamedTextColor.GRAY)
                    .append(Component.text(player.name))
            )

            // Teleport to AFK world
            val afkWorld = Bukkit.getWorld(worldName)
            if (afkWorld != null) {
                teleporting.add(player.uniqueId)
                val spawnLoc = Location(afkWorld, 0.5, 65.0, 0.5, 0f, 0f)
                player.teleport(spawnLoc)
                // Remove teleport flag after a tick
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    teleporting.remove(player.uniqueId)
                }, 3L)
            } else {
                plugin.logger.warning("[AFK] World '$worldName' not found — skipping teleportation.")
            }

            plugin.commsManager.send(player,
                Component.text("You are now AFK.", NamedTextColor.GRAY),
                CommunicationsManager.Category.AFK
            )
        } else {
            afkPlayers.remove(player.uniqueId)
            nextRewardTime.remove(player.uniqueId)

            // Calculate AFK duration
            val startTime = afkStartTime.remove(player.uniqueId)
            val durationText = if (startTime != null) {
                val totalSeconds = (System.currentTimeMillis() - startTime) / 1000
                formatDuration(totalSeconds)
            } else "unknown"

            // Restore display name
            player.displayName(Component.text(player.name))

            // Clear title
            player.clearTitle()

            // Teleport back
            val previousLocation = preAfkLocations.remove(player.uniqueId)
            if (previousLocation != null) {
                teleporting.add(player.uniqueId)
                player.teleport(previousLocation)
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    teleporting.remove(player.uniqueId)
                }, 3L)
            }

            plugin.commsManager.send(player,
                Component.text("You are no longer AFK. You were AFK for ", NamedTextColor.GRAY)
                    .append(Component.text(durationText, NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                CommunicationsManager.Category.AFK
            )
        }
    }

    fun isAfk(player: Player): Boolean = afkPlayers.contains(player.uniqueId)
    fun isAfk(uuid: UUID): Boolean = afkPlayers.contains(uuid)
    fun isTeleporting(player: Player): Boolean = teleporting.contains(player.uniqueId)

    fun handleQuit(player: Player) {
        if (isAfk(player)) {
            // Teleport back before quit data is saved so they rejoin at their original location
            val prev = preAfkLocations.remove(player.uniqueId)
            if (prev != null) {
                player.teleport(prev)
            }
            afkPlayers.remove(player.uniqueId)
            nextRewardTime.remove(player.uniqueId)
            afkStartTime.remove(player.uniqueId)
            teleporting.remove(player.uniqueId)
        }
    }

    private fun giveRewards() {
        val now = System.currentTimeMillis()
        for (uuid in afkPlayers) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val item = ItemStack(rewardMaterial, rewardAmount)
            val leftover = player.inventory.addItem(item)
            if (leftover.isNotEmpty()) {
                leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            }

            // Reset countdown
            nextRewardTime[uuid] = now + (rewardIntervalSeconds * 1000)

            // Flash reward notification
            plugin.commsManager.sendActionBar(player,
                Component.text("+$rewardAmount ${rewardMaterial.name.lowercase().replace('_', ' ')}", NamedTextColor.GREEN)
                    .decoration(TextDecoration.BOLD, true)
            )
        }
    }

    private fun updateCountdownTitles() {
        val now = System.currentTimeMillis()
        for (uuid in afkPlayers) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            val nextTime = nextRewardTime[uuid] ?: continue
            val remaining = ((nextTime - now) / 1000).coerceAtLeast(0)

            val title = Title.title(
                Component.text("AFK", TextColor.color(0x55FFFF))
                    .decoration(TextDecoration.BOLD, true),
                Component.text("Next reward in ", NamedTextColor.GRAY)
                    .append(Component.text("${remaining}s", NamedTextColor.WHITE).decoration(TextDecoration.BOLD, true)),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO)
            )
            player.showTitle(title)
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m ${seconds}s"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /**
     * Creates a void world with a single bedrock block at 0, 64, 0.
     */
    private fun ensureAfkWorld() {
        if (Bukkit.getWorld(worldName) != null) return

        plugin.logger.info("[AFK] Creating void world '$worldName'...")

        val creator = WorldCreator(worldName)
            .type(WorldType.FLAT)
            .environment(World.Environment.NORMAL)
            .generator(object : ChunkGenerator() {
                // Empty generator — void world
            })
            .generateStructures(false)

        val world = creator.createWorld()
        if (world != null) {
            world.setSpawnLocation(0, 65, 0)
            world.setGameRuleValue("doDaylightCycle", "false")
            world.setGameRuleValue("doWeatherCycle", "false")
            world.setGameRuleValue("doMobSpawning", "false")
            world.setGameRuleValue("doFireTick", "false")
            world.time = 6000 // Noon

            // Place a single bedrock block
            world.getBlockAt(0, 64, 0).type = Material.BEDROCK

            plugin.logger.info("[AFK] Void world '$worldName' created.")
        } else {
            plugin.logger.severe("[AFK] Failed to create world '$worldName'!")
        }
    }
}
