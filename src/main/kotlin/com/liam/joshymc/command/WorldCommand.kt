package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.GameRule
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.generator.ChunkGenerator
import java.io.File

class WorldCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    companion object {
        private val PROTECTED_WORLDS = setOf("world", "world_nether", "world_the_end")

        /**
         * Ensures the "dungeon" void world exists. Called from Joshymc.onEnable().
         */
        fun ensureDungeonWorld(plugin: Joshymc) {
            val name = "dungeon"
            if (Bukkit.getWorld(name) != null) return

            // Check if folder exists on disk but isn't loaded
            val worldFolder = File(Bukkit.getWorldContainer(), name)
            val creator = WorldCreator(name)
                .generator(VoidGenerator())
                .type(WorldType.FLAT)
                .generateStructures(false)

            plugin.logger.info("Creating dungeon world...")
            val world = creator.createWorld() ?: run {
                plugin.logger.warning("Failed to create dungeon world!")
                return
            }

            // Configure dungeon world
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false)
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            world.setGameRule(GameRule.DO_WEATHER_CYCLE, false)
            world.setGameRule(GameRule.DO_FIRE_TICK, false)
            world.difficulty = org.bukkit.Difficulty.PEACEFUL

            // Place a bedrock platform at spawn
            world.getBlockAt(0, 64, 0).setType(Material.BEDROCK, false)
            world.setSpawnLocation(0, 65, 0)

            plugin.logger.info("Dungeon world created successfully.")
        }
    }

    /**
     * Void chunk generator — generates completely empty chunks.
     */
    class VoidGenerator : ChunkGenerator() {
        override fun shouldGenerateNoise() = false
        override fun shouldGenerateDecorations() = false
        override fun shouldGenerateMobs() = false
        override fun shouldGenerateStructures() = false
        override fun shouldGenerateSurface() = false
        override fun shouldGenerateBedrock() = false
        override fun shouldGenerateCaves() = false
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("joshymc.world")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        if (args.isEmpty()) {
            sendUsage(sender)
            return true
        }

        when (args[0].lowercase()) {
            "create" -> handleCreate(sender, args)
            "delete" -> handleDelete(sender, args)
            "list" -> handleList(sender)
            "tp" -> handleTp(sender, args)
            "setspawn" -> handleSetSpawn(sender)
            "import" -> handleImport(sender, args)
            "info" -> handleInfo(sender)
            else -> sendUsage(sender)
        }

        return true
    }

    private fun sendUsage(sender: CommandSender) {
        sender.sendMessage(Component.text("World Management", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        sender.sendMessage(Component.text("/world create <name> [type]", NamedTextColor.YELLOW)
            .append(Component.text(" — Create a world", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/world delete <name>", NamedTextColor.YELLOW)
            .append(Component.text(" — Delete a world", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/world list", NamedTextColor.YELLOW)
            .append(Component.text(" — List loaded worlds", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/world tp <name> [player]", NamedTextColor.YELLOW)
            .append(Component.text(" — Teleport to a world", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/world setspawn", NamedTextColor.YELLOW)
            .append(Component.text(" — Set world spawn to your location", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/world import <name>", NamedTextColor.YELLOW)
            .append(Component.text(" — Import an existing world folder", NamedTextColor.GRAY)))
        sender.sendMessage(Component.text("/world info", NamedTextColor.YELLOW)
            .append(Component.text(" — Info about current world", NamedTextColor.GRAY)))
    }

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /world create <name> [normal|flat|void|nether|end]", NamedTextColor.RED))
            return
        }

        val name = args[1].lowercase()
        val typeName = args.getOrNull(2)?.lowercase() ?: "normal"

        if (Bukkit.getWorld(name) != null) {
            sender.sendMessage(Component.text("World '$name' already exists and is loaded.", NamedTextColor.RED))
            return
        }

        val worldFolder = File(Bukkit.getWorldContainer(), name)
        if (worldFolder.exists()) {
            sender.sendMessage(Component.text("A world folder '$name' already exists. Use /world import to load it.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("Creating world '$name' (type: $typeName)...", NamedTextColor.YELLOW))

        val creator = WorldCreator(name)

        when (typeName) {
            "normal" -> {
                creator.type(WorldType.NORMAL)
                creator.environment(World.Environment.NORMAL)
            }
            "flat" -> {
                creator.type(WorldType.FLAT)
                creator.environment(World.Environment.NORMAL)
            }
            "void" -> {
                creator.type(WorldType.FLAT)
                creator.environment(World.Environment.NORMAL)
                creator.generator(VoidGenerator())
                creator.generateStructures(false)
            }
            "nether" -> {
                creator.type(WorldType.NORMAL)
                creator.environment(World.Environment.NETHER)
            }
            "end" -> {
                creator.type(WorldType.NORMAL)
                creator.environment(World.Environment.THE_END)
            }
            else -> {
                sender.sendMessage(Component.text("Unknown type '$typeName'. Use: normal, flat, void, nether, end", NamedTextColor.RED))
                return
            }
        }

        val world = creator.createWorld()
        if (world == null) {
            sender.sendMessage(Component.text("Failed to create world '$name'.", NamedTextColor.RED))
            return
        }

        // For void worlds, place a single bedrock block as a platform
        if (typeName == "void") {
            world.getBlockAt(0, 64, 0).setType(Material.BEDROCK, false)
            world.setSpawnLocation(0, 65, 0)
        }

        sender.sendMessage(Component.text("World '$name' created successfully!", NamedTextColor.GREEN))
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /world delete <name>", NamedTextColor.RED))
            return
        }

        val name = args[1].lowercase()

        if (name in PROTECTED_WORLDS) {
            sender.sendMessage(Component.text("Cannot delete default world '$name'.", NamedTextColor.RED))
            return
        }

        val world = Bukkit.getWorld(name)
        if (world == null) {
            sender.sendMessage(Component.text("World '$name' is not loaded.", NamedTextColor.RED))
            return
        }

        // Teleport all players in that world to the main world spawn
        val mainWorld = Bukkit.getWorlds().first()
        for (player in world.players) {
            player.teleport(mainWorld.spawnLocation)
            player.sendMessage(Component.text("You were moved to the main world because '$name' is being deleted.", NamedTextColor.YELLOW))
        }

        // Unload the world
        if (!Bukkit.unloadWorld(world, false)) {
            sender.sendMessage(Component.text("Failed to unload world '$name'.", NamedTextColor.RED))
            return
        }

        // Delete the world folder
        val worldFolder = File(Bukkit.getWorldContainer(), name)
        if (worldFolder.exists()) {
            worldFolder.deleteRecursively()
        }

        sender.sendMessage(Component.text("World '$name' deleted.", NamedTextColor.GREEN))
    }

    private fun handleList(sender: CommandSender) {
        val worlds = Bukkit.getWorlds()
        sender.sendMessage(Component.text("Loaded Worlds (${worlds.size}):", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))

        for (world in worlds) {
            val envName = when (world.environment) {
                World.Environment.NORMAL -> "Overworld"
                World.Environment.NETHER -> "Nether"
                World.Environment.THE_END -> "The End"
                else -> world.environment.name
            }
            val playerCount = world.players.size
            sender.sendMessage(
                Component.text(" - ", NamedTextColor.GRAY)
                    .append(Component.text(world.name, NamedTextColor.GREEN))
                    .append(Component.text(" [$envName]", NamedTextColor.YELLOW))
                    .append(Component.text(" ($playerCount players)", NamedTextColor.GRAY))
            )
        }
    }

    private fun handleTp(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /world tp <name> [player]", NamedTextColor.RED))
            return
        }

        val worldName = args[1].lowercase()
        val world = Bukkit.getWorld(worldName)
        if (world == null) {
            sender.sendMessage(Component.text("World '$worldName' is not loaded.", NamedTextColor.RED))
            return
        }

        val target: Player = if (args.size >= 3) {
            Bukkit.getPlayer(args[2]) ?: run {
                sender.sendMessage(Component.text("Player '${args[2]}' not found.", NamedTextColor.RED))
                return
            }
        } else {
            if (sender !is Player) {
                sender.sendMessage(Component.text("Specify a player when using from console.", NamedTextColor.RED))
                return
            }
            sender
        }

        target.teleport(world.spawnLocation)
        target.sendMessage(Component.text("Teleported to world '${world.name}'.", NamedTextColor.GREEN))
        if (sender != target) {
            sender.sendMessage(Component.text("Teleported ${target.name} to world '${world.name}'.", NamedTextColor.GREEN))
        }
    }

    private fun handleSetSpawn(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }

        val loc = sender.location
        val world = loc.world
        world.setSpawnLocation(loc.blockX, loc.blockY, loc.blockZ)
        sender.sendMessage(
            Component.text("Spawn for world '${world.name}' set to ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}.", NamedTextColor.GREEN)
        )
    }

    private fun handleImport(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            sender.sendMessage(Component.text("Usage: /world import <name>", NamedTextColor.RED))
            return
        }

        val name = args[1]

        if (Bukkit.getWorld(name) != null) {
            sender.sendMessage(Component.text("World '$name' is already loaded.", NamedTextColor.RED))
            return
        }

        val worldFolder = File(Bukkit.getWorldContainer(), name)
        if (!worldFolder.exists() || !worldFolder.isDirectory) {
            sender.sendMessage(Component.text("No world folder '$name' found.", NamedTextColor.RED))
            return
        }

        // Check for level.dat to confirm it's a valid world
        val levelDat = File(worldFolder, "level.dat")
        if (!levelDat.exists()) {
            sender.sendMessage(Component.text("Folder '$name' does not contain a level.dat — not a valid world.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("Importing world '$name'...", NamedTextColor.YELLOW))

        val creator = WorldCreator(name)
        val world = creator.createWorld()
        if (world == null) {
            sender.sendMessage(Component.text("Failed to import world '$name'.", NamedTextColor.RED))
            return
        }

        sender.sendMessage(Component.text("World '$name' imported and loaded!", NamedTextColor.GREEN))
    }

    private fun handleInfo(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return
        }

        val world = sender.world
        val envName = when (world.environment) {
            World.Environment.NORMAL -> "Overworld"
            World.Environment.NETHER -> "Nether"
            World.Environment.THE_END -> "The End"
            else -> world.environment.name
        }

        sender.sendMessage(Component.text("World Info", NamedTextColor.GOLD).decorate(TextDecoration.BOLD))
        sender.sendMessage(Component.text(" Name: ", NamedTextColor.GRAY).append(Component.text(world.name, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text(" Seed: ", NamedTextColor.GRAY).append(Component.text(world.seed.toString(), NamedTextColor.WHITE)))
        sender.sendMessage(Component.text(" Environment: ", NamedTextColor.GRAY).append(Component.text(envName, NamedTextColor.WHITE)))
        sender.sendMessage(Component.text(" Players: ", NamedTextColor.GRAY).append(Component.text(world.players.size.toString(), NamedTextColor.WHITE)))
        sender.sendMessage(Component.text(" Entities: ", NamedTextColor.GRAY).append(Component.text(world.entities.size.toString(), NamedTextColor.WHITE)))
        sender.sendMessage(Component.text(" Loaded Chunks: ", NamedTextColor.GRAY).append(Component.text(world.loadedChunks.size.toString(), NamedTextColor.WHITE)))
        sender.sendMessage(Component.text(" Spawn: ", NamedTextColor.GRAY).append(
            Component.text("${world.spawnLocation.blockX}, ${world.spawnLocation.blockY}, ${world.spawnLocation.blockZ}", NamedTextColor.WHITE)
        ))
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> {
                val subs = listOf("create", "delete", "list", "tp", "setspawn", "import", "info")
                subs.filter { it.startsWith(args[0], ignoreCase = true) }
            }
            2 -> when (args[0].lowercase()) {
                "create" -> emptyList() // World name — user types it
                "delete", "tp" -> {
                    Bukkit.getWorlds().map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                }
                "import" -> {
                    // Suggest world folders that aren't loaded
                    val loaded = Bukkit.getWorlds().map { it.name }.toSet()
                    val container = Bukkit.getWorldContainer()
                    container.listFiles()
                        ?.filter { it.isDirectory && File(it, "level.dat").exists() && it.name !in loaded }
                        ?.map { it.name }
                        ?.filter { it.startsWith(args[1], ignoreCase = true) }
                        ?: emptyList()
                }
                else -> emptyList()
            }
            3 -> when (args[0].lowercase()) {
                "create" -> {
                    listOf("normal", "flat", "void", "nether", "end").filter { it.startsWith(args[2], ignoreCase = true) }
                }
                "tp" -> {
                    Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[2], ignoreCase = true) }
                }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }
}
