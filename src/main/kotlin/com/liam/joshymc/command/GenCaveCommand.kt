package com.liam.joshymc.command

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import kotlin.math.floor
import kotlin.math.sqrt

class GenCaveCommand(private val plugin: Joshymc) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) { sender.sendMessage("Players only."); return true }
        if (!sender.hasPermission("joshymc.gencave")) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED))
            return true
        }

        val radius = args.getOrNull(0)?.toIntOrNull() ?: 50
        if (radius < 10 || radius > 200) {
            sender.sendMessage(Component.text("Radius must be 10-200.", NamedTextColor.RED))
            return true
        }

        sender.sendMessage(Component.text("Generating cave (radius $radius)... This may take a moment.", NamedTextColor.YELLOW))

        val center = sender.location.clone()
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
                    cancel()
                    val player = Bukkit.getPlayer(sender.uniqueId) ?: return
                    player.sendMessage(
                        Component.text("Cave generated! ", NamedTextColor.GREEN)
                            .append(Component.text("$totalFilled filled, $totalCarved carved.", NamedTextColor.GRAY))
                    )
                    return
                }

                cancel()
            }
        }.runTaskTimer(plugin, 1L, 1L)

        return true
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
            1 -> listOf("25", "50", "75", "100", "150").filter { it.startsWith(args[0]) }
            else -> emptyList()
        }
    }
}
