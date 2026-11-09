package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import com.liam.joshymc.staff.AdminAudit
import com.liam.joshymc.staff.WorldAdminRules
import com.liam.joshymc.util.Msg
import com.liam.joshymc.util.WorldGuardBuild
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * `/gencave [radius]` then `/gencave confirm` (issue #1353, audit finding P5-H1). The first
 * step checks the area and shows what will be overwritten; nothing changes until the same
 * player confirms within [WorldAdminRules.CONFIRM_SECONDS]. Refused: blocked worlds, any claim
 * overlapping the area, WorldGuard build denied (or not checkable) at sample points, any block
 * entity (chest, shulker, spawner, sign, ...) the cave would overwrite, and a second cave while
 * one is running. Every confirmed run writes a `GENCAVE` audit row.
 */
class GenCaveCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    private data class Pending(val center: Location, val radius: Int, val createdAt: Long)

    private val pending = mutableMapOf<UUID, Pending>()

    companion object {
        /** One cave at a time server-wide: each one fills up to ~33 million blocks. */
        @Volatile private var running = false
        private val ALWAYS_BLOCKED = setOf("spawn", "afk", "pvp")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { Msg.playersOnly(sender); return true }
        if (!sender.hasPermission("joshymc.gencave")) {
            Msg.noPermission(sender)
            return true
        }

        if (args.getOrNull(0).equals(WorldAdminRules.CONFIRM, ignoreCase = true)) {
            confirm(sender)
            return true
        }

        val radius = args.getOrNull(0)?.toIntOrNull() ?: 50
        WorldAdminRules.radiusProblem(radius)?.let { Msg.error(sender, it.message); return true }
        if (running) { Msg.error(sender, WorldAdminRules.CaveProblem.RUNNING.message); return true }

        val center = sender.location.clone()
        areaProblem(sender, center, radius)?.let { Msg.error(sender, it.message); return true }

        pending[sender.uniqueId] = Pending(center, radius, System.currentTimeMillis())
        Msg.reply(sender, Component.text(
            "This overwrites about ${"%,d".format(WorldAdminRules.approximateBlocks(radius))} blocks in a sphere of radius $radius " +
                "around ${center.blockX}, ${center.blockY}, ${center.blockZ} in '${center.world.name}'. It can't be undone. " +
                "Run /gencave confirm within ${WorldAdminRules.CONFIRM_SECONDS}s to generate it.",
            NamedTextColor.YELLOW,
        ))
        return true
    }

    /** World, claim and WorldGuard checks (no chunk loads). Block entities are checked on confirm. */
    private fun areaProblem(player: Player, center: Location, radius: Int): WorldAdminRules.CaveProblem? {
        val world = center.world
        val blocked = ALWAYS_BLOCKED.toMutableSet()
        blocked += try { plugin.eventManager.eventWorldName } catch (_: UninitializedPropertyAccessException) { "event" }
        blocked += plugin.config.getString("afk.world") ?: "afk"
        blocked += plugin.config.getStringList("gencave.blocked-worlds")
        if (blocked.any { it.equals(world.name, ignoreCase = true) }) return WorldAdminRules.CaveProblem.WORLD

        val area = WorldAdminRules.area(center.blockX, center.blockZ, radius)
        val claims = try { plugin.claimManager.getClaimsInWorld(world.name) } catch (_: UninitializedPropertyAccessException) { emptyList() }
        if (claims.any { WorldAdminRules.overlaps(area, it.x1, it.z1, it.x2, it.z2) }) return WorldAdminRules.CaveProblem.CLAIM

        val d = (radius * 0.7).toInt()
        val offsets = listOf(
            Triple(0, 0, 0), Triple(radius, 0, 0), Triple(-radius, 0, 0), Triple(0, 0, radius), Triple(0, 0, -radius),
            Triple(0, radius, 0), Triple(0, -radius, 0), Triple(d, 0, d), Triple(d, 0, -d), Triple(-d, 0, d), Triple(-d, 0, -d),
        )
        for ((dx, dy, dz) in offsets) {
            val y = (center.blockY + dy).coerceIn(world.minHeight, world.maxHeight - 1)
            val point = Location(world, center.blockX + dx + 0.5, y.toDouble(), center.blockZ + dz + 0.5)
            when (WorldGuardBuild.canBuild(player, point, plugin.logger)) {
                WorldGuardBuild.Result.DENIED, WorldGuardBuild.Result.UNKNOWN -> return WorldAdminRules.CaveProblem.WORLDGUARD
                else -> {}
            }
        }
        return null
    }

    private fun confirm(player: Player) {
        val request = pending.remove(player.uniqueId)
            ?: run { Msg.error(player, WorldAdminRules.CaveProblem.NO_PENDING.message); return }
        if (WorldAdminRules.expired(request.createdAt, System.currentTimeMillis())) {
            Msg.error(player, WorldAdminRules.CaveProblem.EXPIRED.message); return
        }
        if (running) { Msg.error(player, WorldAdminRules.CaveProblem.RUNNING.message); return }
        // Claims or regions may have changed since the first step.
        areaProblem(player, request.center, request.radius)?.let { Msg.error(player, it.message); return }

        running = true
        val world = request.center.world
        val cx = request.center.blockX
        val cy = request.center.blockY
        val cz = request.center.blockZ
        val area = WorldAdminRules.area(cx, cz, request.radius)
        val futures = mutableListOf<CompletableFuture<Chunk>>()
        for (chunkX in (area.minX shr 4)..(area.maxX shr 4)) {
            for (chunkZ in (area.minZ shr 4)..(area.maxZ shr 4)) futures += world.getChunkAtAsync(chunkX, chunkZ)
        }
        Msg.reply(player, Component.text("Checking ${futures.size} chunks for containers...", NamedTextColor.GRAY))
        CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, error ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                val online = Bukkit.getPlayer(player.uniqueId)
                if (error != null || online == null) {
                    running = false
                    online?.let { Msg.error(it, "Couldn't load the area: ${error?.message ?: "you left"}.") }
                    return@Runnable
                }
                val blockEntities = futures.sumOf { future ->
                    future.join().getTileEntities(false).count { state ->
                        WorldAdminRules.touches(state.x - cx, state.y - cy, state.z - cz, request.radius)
                    }
                }
                if (blockEntities > 0) {
                    running = false
                    Msg.error(online, "${WorldAdminRules.CaveProblem.CONTAINER.message} ($blockEntities found)")
                    return@Runnable
                }
                AdminAudit.log(plugin, online, "GENCAVE",
                    details = "world=${world.name} x=$cx y=$cy z=$cz radius=${request.radius}")
                generate(online, request.center, request.radius)
            })
        }
    }

    private fun generate(sender: Player, centerLocation: Location, radius: Int) {
        sender.sendMessage(Component.text("Generating cave (radius $radius)... This may take a moment.", NamedTextColor.YELLOW))

        val center = centerLocation.clone()
        val world = center.world
        val cx = center.blockX
        val cy = center.blockY
        val cz = center.blockZ
        val seed = (cx * 73856093L) xor (cy * 19349663L) xor (cz * 83492791L)

        object : BukkitRunnable() {
            var pass = 1 // 1=fill, 2=carve
            var currentY = cy + radius
            var totalFilled = 0
            var totalCarved = 0

            override fun run() {
                val startTime = System.currentTimeMillis()

                while (currentY >= cy - radius) {
                    val y = currentY; currentY--

                    for (x in (cx - radius)..(cx + radius)) {
                        for (z in (cz - radius)..(cz + radius)) {
                            val dx = (x - cx).toDouble()
                            val dy = (y - cy).toDouble()
                            val dz = (z - cz).toDouble()
                            val dist = sqrt(dx * dx + dy * dy + dz * dz)
                            if (dist > radius) continue

                            val edgeFalloff = 1.0 - (dist / radius)
                            val block = world.getBlockAt(x, y, z)

                            if (pass == 1) {
                                // Pass 1: fill sphere with stone + ores
                                block.setType(Material.STONE, false)
                                totalFilled++

                                if (edgeFalloff > 0.05) {
                                    val oreCluster = noise3d(x * 0.2, y * 0.2, z * 0.2, seed + 9000)
                                    if (oreCluster > 0.3) {
                                        val roll = (noise3d(x * 0.7, y * 0.7, z * 0.7, seed + 9500) + 1.0) / 2.0
                                        when {
                                            roll < 0.002 -> block.setType(Material.DIAMOND_ORE, false)
                                            roll < 0.005 -> block.setType(Material.GOLD_ORE, false)
                                            roll < 0.008 -> block.setType(Material.LAPIS_ORE, false)
                                            roll < 0.012 -> block.setType(Material.REDSTONE_ORE, false)
                                            roll < 0.025 -> block.setType(Material.IRON_ORE, false)
                                            roll < 0.045 -> block.setType(Material.COAL_ORE, false)
                                        }
                                    }
                                }
                            } else {
                                // Pass 2: carve caves + decorate floors
                                if (edgeFalloff < 0.05) continue // Keep outer shell

                                val n1 = noise3d(x * 0.035, y * 0.035, z * 0.035, seed)
                                val n2 = noise3d(x * 0.08, y * 0.08, z * 0.08, seed + 1000) * 0.5
                                val n3 = noise3d(x * 0.15, y * 0.15, z * 0.15, seed + 2000) * 0.25
                                val n4 = noise3d(x * 0.02, y * 0.025, z * 0.02, seed + 3000) * 0.7

                                val noiseVal = n1 + n2 + n3 + n4
                                val threshold = 0.15 + (1.0 - edgeFalloff) * 0.5

                                if (noiseVal > threshold) {
                                    if (block.type != Material.BEDROCK) {
                                        block.setType(Material.CAVE_AIR, false)
                                        totalCarved++
                                    }

                                        val belowBlock = world.getBlockAt(x, y - 1, z)
                                    val aboveBlock = world.getBlockAt(x, y + 1, z)

                                    // Walls + floor + ceiling — all same treatment
                                    for (adj in listOf(
                                        belowBlock, aboveBlock,
                                        world.getBlockAt(x+1,y,z), world.getBlockAt(x-1,y,z),
                                        world.getBlockAt(x,y,z+1), world.getBlockAt(x,y,z-1)
                                    )) {
                                        if (adj.type == Material.STONE) {
                                            val wn = noise3d(adj.x * 0.12, adj.y * 0.12, adj.z * 0.12, seed + 4000)
                                            when {
                                                wn > 0.5 -> adj.setType(Material.GRAVEL, false)
                                                wn > 0.35 -> adj.setType(Material.COBBLESTONE, false)
                                                wn > 0.15 -> adj.setType(Material.ANDESITE, false)
                                                wn > -0.1 -> adj.setType(Material.GRANITE, false)
                                                wn > -0.35 -> adj.setType(Material.DIORITE, false)
                                            }

                                            // Ore veins
                                            val oreVein = noise3d(adj.x * 0.25, adj.y * 0.25, adj.z * 0.25, seed + 4500)
                                            if (oreVein > 0.5) {
                                                val oreRoll = (noise3d(adj.x * 0.8, adj.y * 0.8, adj.z * 0.8, seed + 4600) + 1.0) / 2.0
                                                when {
                                                    oreRoll < 0.03 -> adj.setType(Material.DIAMOND_ORE, false)
                                                    oreRoll < 0.08 -> adj.setType(Material.GOLD_ORE, false)
                                                    oreRoll < 0.15 -> adj.setType(Material.IRON_ORE, false)
                                                    oreRoll < 0.22 -> adj.setType(Material.COAL_ORE, false)
                                                    oreRoll < 0.27 -> adj.setType(Material.REDSTONE_ORE, false)
                                                    oreRoll < 0.32 -> adj.setType(Material.LAPIS_ORE, false)
                                                    oreRoll < 0.36 -> adj.setType(Material.COPPER_ORE, false)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (System.currentTimeMillis() - startTime > 45) return
                }

                if (pass == 1) {
                    pass = 2
                    currentY = cy + radius
                    return
                }

                if (pass == 2) {
                    // Pass 3: water pools — only on solid floors at low Y levels
                    pass = 3
                    currentY = cy - radius / 4 // Start from low point
                    return
                }

                if (pass == 3) {
                    // Water pass — scan bottom portion only, place water where it makes sense
                    while (currentY >= cy - radius) {
                        val y = currentY; currentY--
                        // Only place water in the bottom third
                        if (y > cy - radius / 4) { currentY = cy - radius; continue }

                        for (x in (cx - radius)..(cx + radius)) {
                            for (z in (cz - radius)..(cz + radius)) {
                                val block = world.getBlockAt(x, y, z)
                                if (block.type != Material.CAVE_AIR) continue

                                val below = world.getBlockAt(x, y - 1, z)
                                if (!below.type.isSolid) continue // Must have solid floor

                                // Use large-scale noise so water forms natural pools, not scattered dots
                                val poolNoise = noise3d(x * 0.04, y * 0.02, z * 0.04, seed + 7000)
                                if (poolNoise > 0.55) {
                                    // Only place if surrounded by solid/water on at least 2 sides (basin check)
                                    var solidSides = 0
                                    if (world.getBlockAt(x+1, y, z).type.isSolid || world.getBlockAt(x+1, y, z).type == Material.WATER) solidSides++
                                    if (world.getBlockAt(x-1, y, z).type.isSolid || world.getBlockAt(x-1, y, z).type == Material.WATER) solidSides++
                                    if (world.getBlockAt(x, y, z+1).type.isSolid || world.getBlockAt(x, y, z+1).type == Material.WATER) solidSides++
                                    if (world.getBlockAt(x, y, z-1).type.isSolid || world.getBlockAt(x, y, z-1).type == Material.WATER) solidSides++

                                    if (solidSides >= 2) {
                                        block.setType(Material.WATER, false)
                                    }
                                }
                            }
                        }
                        if (System.currentTimeMillis() - startTime > 45) return
                    }

                    // Done
                    running = false
                    cancel()
                    val player = Bukkit.getPlayer(sender.uniqueId) ?: return
                    player.sendMessage(
                        Component.text("Cave generated! ", NamedTextColor.GREEN)
                            .append(Component.text("$totalFilled filled, $totalCarved carved.", NamedTextColor.GRAY))
                    )
                    return
                }

                running = false
                cancel()
            }
        }.runTaskTimer(plugin, 1L, 1L)
    }

    private fun noise3d(x: Double, y: Double, z: Double, seed: Long): Double {
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val xf = x - xi; val yf = y - yi; val zf = z - zi
        val u = xf * xf * (3 - 2 * xf); val v = yf * yf * (3 - 2 * yf); val w = zf * zf * (3 - 2 * zf)
        return lerp(
            lerp(lerp(hash3d(xi,yi,zi,seed), hash3d(xi+1,yi,zi,seed), u),
                lerp(hash3d(xi,yi+1,zi,seed), hash3d(xi+1,yi+1,zi,seed), u), v),
            lerp(lerp(hash3d(xi,yi,zi+1,seed), hash3d(xi+1,yi,zi+1,seed), u),
                lerp(hash3d(xi,yi+1,zi+1,seed), hash3d(xi+1,yi+1,zi+1,seed), u), v), w
        )
    }

    private fun hash3d(x: Int, y: Int, z: Int, seed: Long): Double {
        var h = seed xor (x.toLong()*73856093L) xor (y.toLong()*19349663L) xor (z.toLong()*83492791L)
        h = (h xor (h shr 13)) * 0x5bd1e995L; h = h xor (h shr 15)
        return (h and 0xFFFFL).toDouble() / 0xFFFFL * 2.0 - 1.0
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        return when (args.size) {
            1 -> listOf("confirm", "25", "50", "75", "100", "150").filter { it.startsWith(args[0], ignoreCase = true) }
            else -> emptyList()
        }
    }
}
