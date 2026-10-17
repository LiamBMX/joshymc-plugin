package com.liam.joshymc.communityevent.arena

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Two-corner block selections made with the event wand or `/event arena pos1|pos2`.
 * Shared by the event-world zone (`/event save`) and arena regions (issue #1183).
 * Selecting only remembers block coordinates; it never changes a block.
 */
class RegionSelections(private val plugin: Joshymc) {

    private val pos1 = HashMap<UUID, Location>()
    private val pos2 = HashMap<UUID, Location>()
    private val previews = HashMap<UUID, BukkitTask>()

    fun pos1(uuid: UUID): Location? = pos1[uuid]
    fun pos2(uuid: UUID): Location? = pos2[uuid]

    fun setPos1(player: Player, location: Location) { pos1[player.uniqueId] = location.block.location; previewIfComplete(player) }
    fun setPos2(player: Player, location: Location) { pos2[player.uniqueId] = location.block.location; previewIfComplete(player) }

    fun clear(uuid: UUID) {
        pos1.remove(uuid)
        pos2.remove(uuid)
        previews.remove(uuid)?.cancel()
    }

    /** The selection as a region, or the reason it can't be one. */
    sealed interface Selection {
        data class Ok(val region: ArenaRegion) : Selection
        data class Problem(val message: String) : Selection
    }

    fun selection(player: Player): Selection {
        val a = pos1[player.uniqueId]
        val b = pos2[player.uniqueId]
        if (a == null || b == null) {
            return Selection.Problem("Select two corners first: /event arena wand (left/right-click blocks) or /event arena pos1 and pos2.")
        }
        val wa = a.world?.name
        val wb = b.world?.name
        if (wa == null || wb == null) return Selection.Problem("One of your corners is in a world that's no longer loaded. Select again.")
        if (wa != wb) return Selection.Problem("Your corners are in different worlds ($wa and $wb). Select both in the same world.")
        return Selection.Ok(ArenaRegion.of(wa, a.blockX, a.blockY, a.blockZ, b.blockX, b.blockY, b.blockZ))
    }

    private fun previewIfComplete(player: Player) {
        val sel = selection(player) as? Selection.Ok ?: return
        if (ArenaValidator.checkRegion(sel.region, sel.region.world, null) == null) show(player, listOf(sel.region), emptyList())
    }

    /**
     * Outlines [regions] and marks [points] with particles only [player] can see, for a few
     * seconds. Purely visual: no blocks, no entities.
     */
    fun show(player: Player, regions: List<ArenaRegion>, points: List<ArenaPoint>) {
        previews.remove(player.uniqueId)?.cancel()
        var runs = 0
        lateinit var task: BukkitTask
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (!player.isOnline || runs++ >= PREVIEW_RUNS) {
                task.cancel()
                if (previews[player.uniqueId] === task) previews.remove(player.uniqueId)
                return@Runnable
            }
            val world = player.world
            regions.filter { it.world == world.name }.forEach { outline(player, it) }
            points.filter { it.world == world.name }.forEach { p ->
                player.spawnParticle(Particle.DUST, Location(world, p.x, p.y + 1.0, p.z), 6, 0.15, 0.6, 0.15, 0.0, POINT_DUST)
            }
        }, 0L, 10L)
        previews[player.uniqueId] = task
    }

    private fun outline(player: Player, r: ArenaRegion) {
        val world = player.world
        val x0 = r.minX.toDouble(); val y0 = r.minY.toDouble(); val z0 = r.minZ.toDouble()
        val x1 = r.maxX + 1.0; val y1 = r.maxY + 1.0; val z1 = r.maxZ + 1.0
        // Keep each outline to a bounded number of particles, whatever the region size.
        val step = maxOf(1.0, (r.sizeX + r.sizeY + r.sizeZ) * 4.0 / MAX_PARTICLES)
        fun edge(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) {
            val len = maxOf(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay), kotlin.math.abs(bz - az))
            var t = 0.0
            while (t <= len) {
                val f = if (len == 0.0) 0.0 else t / len
                player.spawnParticle(Particle.DUST, Location(world, ax + (bx - ax) * f, ay + (by - ay) * f, az + (bz - az) * f), 1, 0.0, 0.0, 0.0, 0.0, EDGE_DUST)
                t += step
            }
        }
        for (y in listOf(y0, y1)) {
            edge(x0, y, z0, x1, y, z0); edge(x0, y, z1, x1, y, z1)
            edge(x0, y, z0, x0, y, z1); edge(x1, y, z0, x1, y, z1)
        }
        edge(x0, y0, z0, x0, y1, z0); edge(x1, y0, z0, x1, y1, z0)
        edge(x0, y0, z1, x0, y1, z1); edge(x1, y0, z1, x1, y1, z1)
    }

    fun shutdown() {
        previews.values.forEach { it.cancel() }
        previews.clear()
    }

    private companion object {
        const val PREVIEW_RUNS = 16
        const val MAX_PARTICLES = 480
        val EDGE_DUST = Particle.DustOptions(Color.fromRGB(0xFFAA00), 1.0f)
        val POINT_DUST = Particle.DustOptions(Color.fromRGB(0x55FFFF), 1.2f)
    }
}
