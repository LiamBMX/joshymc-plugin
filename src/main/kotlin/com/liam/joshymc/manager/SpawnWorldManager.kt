package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.util.UUID

class SpawnWorldManager(private val plugin: Joshymc) : Listener {

    /** Track players who had fly BEFORE entering spawn (so we don't take it from them when they leave) */
    private val hadFlyBefore = mutableSetOf<UUID>()

    private val worldName = "spawn"
    private val schematicName = plugin.config.getString("spawn-world.schematic", "GETAWXY_LOBBY.schem") ?: "GETAWXY_LOBBY.schem"
    private val pasteX = plugin.config.getInt("spawn-world.paste-x", 0)
    private val pasteY = plugin.config.getInt("spawn-world.paste-y", 64)
    private val pasteZ = plugin.config.getInt("spawn-world.paste-z", 0)

    fun start() {
        if (!plugin.config.getBoolean("spawn-world.enabled", true)) return

        val world = Bukkit.getWorld(worldName) ?: createSpawnWorld()
        if (world == null) {
            plugin.logger.warning("[SpawnWorld] Failed to create spawn world.")
            return
        }

        // Lobby game rules
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
        world.setGameRule(GameRule.DO_FIRE_TICK, false)
        world.setGameRule(GameRule.MOB_GRIEFING, false)
        world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0)
        world.setGameRule(GameRule.DO_TILE_DROPS, false)
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false)

        // Set spawn point
        world.spawnLocation = Location(world, -520.0, -8.0, 0.0)
        world.time = 6000
        world.setStorm(false)
        world.isThundering = false

        // Check if schematic needs pasting
        val markerFile = File(plugin.dataFolder, ".spawn_loaded")
        if (!markerFile.exists()) {
            plugin.logger.info("[SpawnWorld] Will load schematic '$schematicName' into spawn world...")
            // Long delay to ensure WorldEdit is fully loaded and ready
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                pasteSchematic(world)
                try { markerFile.createNewFile() } catch (_: Exception) {}
            }, 100L) // 5 second delay
        } else {
            plugin.logger.info("[SpawnWorld] Spawn world '$worldName' already loaded.")
        }
    }

    private fun createSpawnWorld(): World? {
        // Check if a world folder already exists (pre-built spawn map)
        val worldFolder = File(plugin.server.worldContainer, worldName)
        if (worldFolder.exists() && File(worldFolder, "level.dat").exists()) {
            plugin.logger.info("[SpawnWorld] Loading existing spawn world '$worldName'...")
            val world = WorldCreator(worldName).createWorld()
            if (world != null) {
                plugin.logger.info("[SpawnWorld] Loaded existing spawn world '$worldName'.")
            }
            return world
        }

        // No existing world — create a flat one
        plugin.logger.info("[SpawnWorld] Creating flat spawn world '$worldName'...")
        val world = WorldCreator(worldName)
            .type(WorldType.FLAT)
            .generateStructures(false)
            .createWorld()
        if (world != null) {
            plugin.logger.info("[SpawnWorld] World '$worldName' created.")
        }
        return world
    }

    private fun pasteSchematic(world: World) {
        val schemFile = File(plugin.server.pluginsFolder, "WorldEdit/schematics/$schematicName")
        if (!schemFile.exists()) {
            plugin.logger.warning("[SpawnWorld] Schematic not found: ${schemFile.absolutePath}")
            return
        }

        plugin.logger.info("[SpawnWorld] Pasting schematic at ($pasteX, $pasteY, $pasteZ) in '$worldName'...")
        plugin.logger.info("[SpawnWorld] This is a large schematic — it may take a while. Server may lag briefly.")

        // Use WorldEdit console commands — most reliable method
        val console = Bukkit.getConsoleSender()

        // Step 1: Set the world for WorldEdit operations
        // We'll use //world to switch, then paste
        try {
            // Load the schematic into clipboard
            Bukkit.dispatchCommand(console, "world $worldName")
        } catch (_: Exception) {}

        // Use the FAWE/WE async paste via console commands
        // This runs the paste operation through WorldEdit's own command system
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            try {
                // WorldEdit console commands need a player-like actor.
                // Instead, use the WorldEdit API directly but with the correct class loader
                pasteWithWorldEditAPI(world)
            } catch (e: Exception) {
                plugin.logger.warning("[SpawnWorld] WorldEdit API paste failed, trying command fallback...")
                plugin.logger.warning("[SpawnWorld] Error: ${e.message}")
                // Fallback: tell an admin to paste manually
                plugin.logger.warning("[SpawnWorld] To paste manually: stand in spawn world at $pasteX $pasteY $pasteZ and run:")
                plugin.logger.warning("[SpawnWorld]   //schematic load $schematicName")
                plugin.logger.warning("[SpawnWorld]   //paste")
            }
        }, 5L)
    }

    private fun pasteWithWorldEditAPI(world: World) {
        // Use WorldEdit's Java API via the BukkitAdapter
        val bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
        val weWorld = bukkitAdapter.getMethod("adapt", World::class.java).invoke(null, world)

        val schemFile = File(plugin.server.pluginsFolder, "WorldEdit/schematics/$schematicName")

        // ClipboardFormats.findByFile(file)
        val formatsClass = Class.forName("com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats")
        val format = formatsClass.getMethod("findByFile", File::class.java).invoke(null, schemFile)
            ?: throw RuntimeException("Unknown schematic format")

        // format.getReader(inputStream)
        val fis = java.io.FileInputStream(schemFile)
        val reader = format.javaClass.getMethod("getReader", java.io.InputStream::class.java).invoke(format, fis)

        // reader.read()
        val clipboard = reader.javaClass.getMethod("read").invoke(reader)
        reader.javaClass.getMethod("close").invoke(reader)
        fis.close()

        // WorldEdit.getInstance()
        val weClass = Class.forName("com.sk89q.worldedit.WorldEdit")
        val we = weClass.getMethod("getInstance").invoke(null)

        // EditSession via builder
        val builder = we.javaClass.getMethod("newEditSessionBuilder").invoke(we)
        val worldClass = Class.forName("com.sk89q.worldedit.world.World")
        builder.javaClass.getMethod("world", worldClass).invoke(builder, weWorld)

        // Set max blocks to unlimited for large schematics
        try {
            builder.javaClass.getMethod("maxBlocks", Int::class.java).invoke(builder, -1)
        } catch (_: Exception) {
            // older WE versions may not have this
        }

        val editSession = builder.javaClass.getMethod("build").invoke(builder)

        // BlockVector3.at(x, y, z)
        val bv3Class = Class.forName("com.sk89q.worldedit.math.BlockVector3")
        val pastePos = bv3Class.getMethod("at", Int::class.java, Int::class.java, Int::class.java)
            .invoke(null, pasteX, pasteY, pasteZ)

        // clipboard.createPaste(editSession).to(pos).ignoreAirBlocks(false).build()
        val extentClass = Class.forName("com.sk89q.worldedit.extent.Extent")
        val pasteOp = clipboard.javaClass.getMethod("createPaste", extentClass).invoke(clipboard, editSession)
        pasteOp.javaClass.getMethod("to", bv3Class).invoke(pasteOp, pastePos)
        pasteOp.javaClass.getMethod("ignoreAirBlocks", Boolean::class.java).invoke(pasteOp, false)
        val operation = pasteOp.javaClass.getMethod("build").invoke(pasteOp)

        // Operations.complete(operation)
        val opsClass = Class.forName("com.sk89q.worldedit.function.operation.Operations")
        val opClass = Class.forName("com.sk89q.worldedit.function.operation.Operation")
        opsClass.getMethod("complete", opClass).invoke(null, operation)

        // Close edit session
        editSession.javaClass.getMethod("close").invoke(editSession)

        world.spawnLocation = Location(world, pasteX.toDouble(), (pasteY + 5).toDouble(), pasteZ.toDouble())

        plugin.logger.info("[SpawnWorld] Schematic '$schematicName' pasted successfully at ($pasteX, $pasteY, $pasteZ)!")
        plugin.logger.info("[SpawnWorld] Spawn set to ($pasteX, ${pasteY + 5}, $pasteZ) in '$worldName'.")
    }

    fun reload() {
        val markerFile = File(plugin.dataFolder, ".spawn_loaded")
        markerFile.delete()
        val world = Bukkit.getWorld(worldName) ?: return
        pasteSchematic(world)
        try { markerFile.createNewFile() } catch (_: Exception) {}
    }

    // ── Spawn fly ──────────────────────────────────────────

    private fun enableSpawnFly(player: Player) {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return
        if (player.allowFlight) hadFlyBefore.add(player.uniqueId)
        player.allowFlight = true
    }

    private fun disableSpawnFly(player: Player) {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) return
        // Only remove fly if they didn't have it before (e.g. from /fly command)
        if (hadFlyBefore.remove(player.uniqueId)) return
        player.allowFlight = false
        player.isFlying = false
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        val player = event.player
        if (player.world.name == worldName) {
            enableSpawnFly(player)
        } else if (event.from.name == worldName) {
            disableSpawnFly(player)
        }
    }

    @EventHandler
    fun onJoinSpawn(event: PlayerJoinEvent) {
        val player = event.player
        if (player.world.name == worldName) {
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (player.isOnline && player.world.name == worldName) {
                    enableSpawnFly(player)
                }
            }, 5L)
        }
    }
}
