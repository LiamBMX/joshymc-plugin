package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gui.CustomGui
import com.liam.joshymc.manager.CommunicationsManager
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class RtpCommand(private val plugin: Joshymc) : CommandExecutor {

    private val cooldowns = ConcurrentHashMap<UUID, Long>()

    // Custom head textures (base64-encoded Mojang skin URLs)
    companion object {
        // Grass block head
        private const val OVERWORLD_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvYjJhNmIwNmRiMzRmYmI0NWE3NjhiNzE0ZjFiMTFkMWYzYzJhYjJiOGViNjk3YzdiMmI4NWQ3NDk5Y2NlZSJ9fX0="
        // Netherrack head
        private const val NETHER_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZGI2NTRkNTc2Y2I5MzEzZGRhOWEyYTM0MDhhNGUxOGVmYWQ4NTA1OGM5ZWI5NThkODdkNjdiYjljNWUwZjEifX19"
        // End stone head
        private const val END_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvN2E2OGMxZjdmZDBjMjY4YjM3MjFjNzJmNmFlOWI1OTQyMmFhODJmYzRlZTk1YjUyNjRlMWI1ZjQxMDk4NjQifX19"
        // Compass/globe head
        private const val RESOURCE_TEXTURE = "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDUyOGVkNDRmODhjZmI1ZjI0ZmM4NmE4NTVjOTQyODFhNGQzNGI2Njg1NjM2MzI1MjMxNGI2MTRiMjk4NWIxIn19fQ=="
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED))
            return true
        }

        if (TeleportChecks.checkAndApply(sender, plugin)) return true

        val cooldownSeconds = plugin.config.getInt("rtp.cooldown-seconds", 60)
        val now = System.currentTimeMillis()
        val lastUse = cooldowns[sender.uniqueId]
        if (lastUse != null) {
            val remaining = cooldownSeconds - ((now - lastUse) / 1000)
            if (remaining > 0) {
                plugin.commsManager.send(sender, Component.text("RTP is on cooldown. Wait ${remaining}s.", NamedTextColor.RED), CommunicationsManager.Category.TELEPORT)
                return true
            }
        }

        openWorldSelector(sender)
        return true
    }

    private fun openWorldSelector(player: Player) {
        val gui = CustomGui(
            Component.text("Random Teleport", NamedTextColor.GREEN)
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            27
        )

        val filler = ItemStack(Material.BLACK_STAINED_GLASS_PANE)
        filler.editMeta { it.displayName(Component.empty()) }
        gui.fill(filler)

        // Green glass border
        val border = ItemStack(Material.GREEN_STAINED_GLASS_PANE)
        border.editMeta { it.displayName(Component.empty()) }
        gui.border(border)

        // Overworld (slot 10)
        val overworldHead = createCustomHead(
            OVERWORLD_TEXTURE,
            Component.text("Overworld", TextColor.color(0x55FF55))
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            listOf(
                Component.empty(),
                Component.text("  Teleport to a random location", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  in the survival overworld.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to teleport!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            )
        )
        gui.setItem(10, overworldHead) { p, _ ->
            p.closeInventory()
            val resourceWorldName = plugin.config.getString("resource-world.world-name", "resource") ?: "resource"
            // Find the main survival overworld — NORMAL environment, not spawn, not resource
            val world = Bukkit.getWorlds().firstOrNull {
                it.environment == World.Environment.NORMAL &&
                it.name != "spawn" &&
                it.name != resourceWorldName &&
                it.name != "afk" &&
                it.name != "dungeon"
            }
            if (world == null) {
                plugin.commsManager.send(p, Component.text("No overworld found.", NamedTextColor.RED))
                return@setItem
            }
            startRtp(p, world)
        }

        // Nether (slot 12)
        val netherHead = createCustomHead(
            NETHER_TEXTURE,
            Component.text("The Nether", TextColor.color(0xFF5555))
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            listOf(
                Component.empty(),
                Component.text("  Teleport to a random location", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  in the Nether.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to teleport!", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            )
        )
        gui.setItem(12, netherHead) { p, _ ->
            p.closeInventory()
            val world = Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.NETHER }
            if (world == null) {
                plugin.commsManager.send(p, Component.text("No nether world found.", NamedTextColor.RED))
                return@setItem
            }
            startRtp(p, world)
        }

        // The End (slot 14)
        val endHead = createCustomHead(
            END_TEXTURE,
            Component.text("The End", TextColor.color(0xAA55FF))
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            listOf(
                Component.empty(),
                Component.text("  Teleport to a random location", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  in the End.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to teleport!", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            )
        )
        gui.setItem(14, endHead) { p, _ ->
            p.closeInventory()
            val world = Bukkit.getWorlds().firstOrNull { it.environment == World.Environment.THE_END }
            if (world == null) {
                plugin.commsManager.send(p, Component.text("No end world found.", NamedTextColor.RED))
                return@setItem
            }
            startRtp(p, world)
        }

        // Resource World (slot 16)
        val resourceHead = createCustomHead(
            RESOURCE_TEXTURE,
            Component.text("Resource World", TextColor.color(0xFFAA00))
                .decoration(TextDecoration.BOLD, true)
                .decoration(TextDecoration.ITALIC, false),
            listOf(
                Component.empty(),
                Component.text("  Teleport to a random location", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  in the resource world.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("  This world resets periodically!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("  Click to teleport!", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false),
                Component.empty()
            )
        )
        gui.setItem(16, resourceHead) { p, _ ->
            p.closeInventory()
            val resourceWorldName = plugin.config.getString("resource-world.world-name", "resource") ?: "resource"
            val world = Bukkit.getWorld(resourceWorldName)
            if (world == null) {
                plugin.commsManager.send(p, Component.text("Resource world is not loaded.", NamedTextColor.RED))
                return@setItem
            }
            startRtp(p, world)
        }

        plugin.guiManager.open(player, gui)
        player.playSound(player.location, Sound.BLOCK_CHEST_OPEN, 0.5f, 1.2f)
    }

    private fun startRtp(player: Player, world: World, skipWarmup: Boolean = false) {
        val minRange = plugin.config.getInt("rtp.min-range", 500)
        val maxRange = plugin.config.getInt("rtp.max-range", 5000)

        plugin.commsManager.sendActionBar(player, Component.text("Finding a safe location...", NamedTextColor.YELLOW))
        findSafeLocation(player, world, minRange, maxRange, 0, skipWarmup)
    }

    /**
     * Kick off RTP for [player] directly, bypassing the GUI and the /rtp cooldown.
     * If [world] is null, uses the main overworld.
     *
     * Set [skipWarmup] to true when the trigger context is short-lived and the
     * player may already be moving (e.g., walking into a portal) — the normal
     * "stand still for 3 seconds" check would immediately cancel.
     */
    fun startForPlayer(player: Player, world: World? = null, skipWarmup: Boolean = false) {
        val resourceWorldName = plugin.config.getString("resource-world.world-name", "resource") ?: "resource"
        val target = world ?: Bukkit.getWorlds().firstOrNull {
            it.environment == World.Environment.NORMAL &&
                it.name != "spawn" &&
                it.name != resourceWorldName &&
                it.name != "afk" &&
                it.name != "dungeon"
        }
        if (target == null) {
            plugin.commsManager.send(player, Component.text("No overworld found.", NamedTextColor.RED))
            return
        }
        startRtp(player, target, skipWarmup)
    }

    private fun findSafeLocation(player: Player, world: World, minRange: Int, maxRange: Int, attempt: Int, skipWarmup: Boolean = false) {
        if (attempt >= 15) {
            plugin.commsManager.send(player, Component.text("Could not find a safe location. Try again.", NamedTextColor.RED), CommunicationsManager.Category.TELEPORT)
            return
        }

        val x = randomCoord(minRange, maxRange)
        val z = randomCoord(minRange, maxRange)

        world.getChunkAtAsync(x shr 4, z shr 4).thenAccept { _ ->
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable

                if (world.environment == World.Environment.NETHER) {
                    // Nether: search for a safe spot between y=30 and y=100
                    val safeLoc = findNetherSafe(world, x, z)
                    if (safeLoc == null) {
                        findSafeLocation(player, world, minRange, maxRange, attempt + 1, skipWarmup)
                        return@Runnable
                    }
                    startWarmup(player, safeLoc, skipWarmup)
                    return@Runnable
                }

                if (world.environment == World.Environment.THE_END) {
                    // End: search from y=50 upward for end stone platform
                    val safeLoc = findEndSafe(world, x, z)
                    if (safeLoc == null) {
                        findSafeLocation(player, world, minRange, maxRange, attempt + 1, skipWarmup)
                        return@Runnable
                    }
                    startWarmup(player, safeLoc, skipWarmup)
                    return@Runnable
                }

                // Overworld / Resource: use highest block
                val highestY = world.getHighestBlockYAt(x, z)
                if (highestY < world.minHeight + 1) {
                    findSafeLocation(player, world, minRange, maxRange, attempt + 1, skipWarmup)
                    return@Runnable
                }

                val ground = world.getBlockAt(x, highestY, z)
                val above1 = world.getBlockAt(x, highestY + 1, z)
                val above2 = world.getBlockAt(x, highestY + 2, z)

                // Reject unsafe blocks
                val unsafeGround = setOf(
                    Material.LAVA, Material.WATER, Material.FIRE, Material.SOUL_FIRE,
                    Material.MAGMA_BLOCK, Material.CACTUS, Material.SWEET_BERRY_BUSH,
                    Material.POWDER_SNOW, Material.WITHER_ROSE
                )
                // Reject leaves (player would fall through)
                val isLeaves = ground.type.name.endsWith("_LEAVES")

                if (ground.type in unsafeGround || isLeaves ||
                    !ground.type.isSolid || above1.type.isSolid || above2.type.isSolid) {
                    findSafeLocation(player, world, minRange, maxRange, attempt + 1, skipWarmup)
                    return@Runnable
                }

                val safeLoc = Location(world, x + 0.5, highestY + 1.0, z + 0.5,
                    player.location.yaw, player.location.pitch)

                startWarmup(player, safeLoc, skipWarmup)
            })
        }
    }

    private fun findNetherSafe(world: World, x: Int, z: Int): Location? {
        // Scan from y=30 to y=100 for a 2-high air pocket with solid floor
        for (y in 30..100) {
            val ground = world.getBlockAt(x, y, z)
            val above1 = world.getBlockAt(x, y + 1, z)
            val above2 = world.getBlockAt(x, y + 2, z)
            if (ground.type.isSolid && ground.type != Material.LAVA &&
                !above1.type.isSolid && above1.type != Material.LAVA &&
                !above2.type.isSolid && above2.type != Material.LAVA) {
                return Location(world, x + 0.5, y + 1.0, z + 0.5)
            }
        }
        return null
    }

    private fun findEndSafe(world: World, x: Int, z: Int): Location? {
        // Scan from y=0 to y=100 for end stone or obsidian with air above
        for (y in 0..100) {
            val ground = world.getBlockAt(x, y, z)
            val above1 = world.getBlockAt(x, y + 1, z)
            val above2 = world.getBlockAt(x, y + 2, z)
            if (ground.type.isSolid &&
                !above1.type.isSolid && !above2.type.isSolid) {
                return Location(world, x + 0.5, y + 1.0, z + 0.5)
            }
        }
        return null
    }

    private fun startWarmup(player: Player, destination: Location, skipWarmup: Boolean = false) {
        // Portals (and other auto-triggered contexts) can't require stand-still,
        // since the player is already moving through the trigger region.
        if (skipWarmup) {
            player.teleport(destination)
            plugin.commsManager.sendActionBar(player, Component.text("Teleported!", NamedTextColor.GREEN))
            cooldowns[player.uniqueId] = System.currentTimeMillis()
            return
        }

        val startLoc = player.location.clone()

        object : BukkitRunnable() {
            var ticks = 0

            override fun run() {
                if (!player.isOnline) {
                    cancel()
                    return
                }

                if (player.location.blockX != startLoc.blockX ||
                    player.location.blockY != startLoc.blockY ||
                    player.location.blockZ != startLoc.blockZ) {
                    plugin.commsManager.sendActionBar(player, Component.text("Teleportation cancelled. You moved.", NamedTextColor.RED))
                    cancel()
                    return
                }

                val secondsLeft = 3 - (ticks / 20)
                if (secondsLeft > 0) {
                    plugin.commsManager.sendActionBar(player, Component.text("Teleporting in ${secondsLeft}s...", NamedTextColor.YELLOW))
                }

                if (ticks >= 60) {
                    player.teleport(destination)
                    plugin.commsManager.sendActionBar(player, Component.text("Teleported!", NamedTextColor.GREEN))
                    cooldowns[player.uniqueId] = System.currentTimeMillis()
                    cancel()
                    return
                }

                ticks++
            }
        }.runTaskTimer(plugin, 0L, 1L)
    }

    private fun randomCoord(minRange: Int, maxRange: Int): Int {
        val distance = Random.nextInt(minRange, maxRange + 1)
        return if (Random.nextBoolean()) distance else -distance
    }

    private fun createCustomHead(texture: String, name: Component, lore: List<Component>): ItemStack {
        val head = ItemStack(Material.PLAYER_HEAD)
        head.editMeta(SkullMeta::class.java) { meta ->
            // Apply texture via Paper's PlayerProfile API
            try {
                val profile = Bukkit.createProfile(UUID.randomUUID(), "")
                profile.properties.add(com.destroystokyo.paper.profile.ProfileProperty("textures", texture))
                meta.playerProfile = profile
            } catch (_: Exception) {
                // Fallback: just use a regular player head
            }

            meta.displayName(name)
            meta.lore(lore)
        }
        return head
    }
}
