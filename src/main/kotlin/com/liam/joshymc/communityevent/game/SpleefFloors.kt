package com.liam.joshymc.communityevent.game

import com.liam.joshymc.Joshymc
import com.liam.joshymc.communityevent.arena.ArenaRegion
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask

/**
 * Spleef floor snapshots and restoration (issue #1187). Main thread only.
 *
 * Before a round a floor's blocks are captured and saved to [FloorSnapshotStore]; the row
 * stays until the floor is fully restored, so a restart mid-round restores it on the next
 * startup. Restoration only touches positions inside the saved region whose block differs
 * from the snapshot, a bounded number per tick, without physics, and moves anyone standing
 * in the floor out of the way first. Restore jobs are not session tasks: stopping an event
 * never leaves a floor half-restored.
 */
class SpleefFloors(private val plugin: Joshymc) {

    private var store: FloorSnapshotStore? = null

    /** Snapshots whose floor may be damaged (saved, not yet restored), by arena id. */
    private val pending = HashMap<String, FloorSnapshot>()

    private val jobs = HashMap<String, Job>()

    private inner class Job(val snapshot: FloorSnapshot, val world: World, val safeSpot: () -> Location?, val done: (Int) -> Unit) {
        var cursor = 0
        var changed = 0
        lateinit var task: BukkitTask
    }

    fun load() {
        try {
            val s = FloorSnapshotStore(plugin.databaseManager)
            s.createSchema()
            val warnings = mutableListOf<String>()
            pending.clear()
            s.loadAll(warnings).forEach { pending[it.arenaId] = it }
            warnings.forEach { plugin.logger.warning("[Event] $it") }
            store = s
            if (pending.isNotEmpty()) {
                plugin.logger.info("[Event] ${pending.size} Spleef floor(s) were left damaged by the last shutdown; restoring them.")
            }
        } catch (e: Exception) {
            store = null
            plugin.logger.severe("[Event] Couldn't load Spleef floor snapshots; Spleef can't start until the next restart: ${e.message}")
        }
    }

    val available: Boolean get() = store != null

    /** Restores every floor left damaged by a crash or restart, once its world is loaded. */
    fun recoverAll(safeSpot: (World) -> Location?) {
        for (snapshot in pending.values.toList()) {
            val world = Bukkit.getWorld(snapshot.region.world)
            if (world == null) {
                plugin.logger.warning("[Event] World '${snapshot.region.world}' isn't loaded, so arena '${snapshot.arenaId}''s Spleef floor wasn't restored. Run /event spleef reset ${snapshot.arenaId} once it is.")
                continue
            }
            restore(snapshot.arenaId, { safeSpot(world) }) { changed ->
                plugin.logger.info("[Event] Restored arena '${snapshot.arenaId}''s Spleef floor after restart ($changed block(s) changed).")
            }
        }
    }

    /** The floor may be damaged: a snapshot is waiting to be restored. */
    fun isDamaged(arenaId: String): Boolean = arenaId in pending

    fun isRestoring(arenaId: String): Boolean {
        val job = jobs[arenaId] ?: return false
        // A plugin reload cancels every task; let the restore be started again.
        if (job.task.isCancelled) { jobs.remove(arenaId); return false }
        return true
    }

    fun progress(arenaId: String): Pair<Int, Int>? = jobs[arenaId]?.let { it.cursor to it.snapshot.size }

    /**
     * Captures the floor and saves it before anything can break. Returns null on success, or
     * the reason the round can't start (storage down, floor already damaged, save failed).
     */
    fun capture(arenaId: String, region: ArenaRegion): String? {
        val s = store ?: return "Spleef floor storage failed to load at startup; check the console."
        if (isRestoring(arenaId)) return "The floor is still being restored. Wait for it to finish."
        if (isDamaged(arenaId)) return "The floor hasn't been restored since the last round. Run /event spleef reset first."
        val world = Bukkit.getWorld(region.world) ?: return "World '${region.world}' isn't loaded."
        val data = ArrayList<String>(region.volume.toInt())
        for (x in region.minX..region.maxX) for (y in region.minY..region.maxY) for (z in region.minZ..region.maxZ) {
            data += world.getBlockAt(x, y, z).blockData.asString
        }
        val snapshot = try {
            FloorSnapshot.of(arenaId, region, data, System.currentTimeMillis())
        } catch (e: IllegalArgumentException) {
            return e.message ?: "The floor couldn't be captured."
        }
        try {
            s.save(snapshot)
        } catch (e: Exception) {
            plugin.logger.warning("[Event] Failed to save the Spleef floor snapshot for '$arenaId': ${e.message}")
            return "Couldn't save the floor snapshot (${e.message}), so the round was not started."
        }
        pending[arenaId] = snapshot
        return null
    }

    /**
     * Starts restoring [arenaId]'s floor. Returns null when it started, or why not.
     * [safeSpot] is where anyone standing in the floor is moved; [done] gets the number of
     * blocks that were changed.
     */
    fun restore(arenaId: String, safeSpot: () -> Location?, done: (Int) -> Unit = {}): String? {
        val s = store ?: return "Spleef floor storage failed to load at startup; check the console."
        if (isRestoring(arenaId)) return "The floor is already being restored."
        val snapshot = pending[arenaId] ?: return "The floor is already intact; there's nothing to restore."
        val world = Bukkit.getWorld(snapshot.region.world) ?: return "World '${snapshot.region.world}' isn't loaded."
        val parsed = snapshot.palette.map { raw ->
            runCatching { Bukkit.createBlockData(raw) }.onFailure {
                plugin.logger.warning("[Event] Arena '$arenaId' floor: can't read block '$raw'; those blocks are left as they are.")
            }.getOrNull()
        }
        val job = Job(snapshot, world, safeSpot) { changed ->
            try { s.delete(arenaId) } catch (e: Exception) {
                plugin.logger.warning("[Event] Restored arena '$arenaId''s floor but couldn't clear its snapshot: ${e.message}")
            }
            pending.remove(arenaId)
            done(changed)
        }
        job.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { step(arenaId, job, parsed) }, 1L, 1L)
        jobs[arenaId] = job
        return null
    }

    private fun step(arenaId: String, job: Job, palette: List<BlockData?>) {
        val snapshot = job.snapshot
        moveOutOfFloor(job)
        val perTick = plugin.config.getInt("hosted-events.spleef.floor.blocks-per-tick", 1000).coerceIn(50, 20_000)
        val end = minOf(job.cursor + perTick, snapshot.size)
        while (job.cursor < end) {
            val i = job.cursor++
            val target = palette[snapshot.blocks[i]] ?: continue
            val (x, y, z) = snapshot.positionOf(i)
            val block = job.world.getBlockAt(x, y, z)
            if (block.blockData != target) {
                block.setBlockData(target, false)
                job.changed++
            }
        }
        if (job.cursor >= snapshot.size) {
            job.task.cancel()
            jobs.remove(arenaId)
            job.done(job.changed)
        }
    }

    /** Anyone inside the floor (or just above it) is moved to the safe spot so no block lands on them. */
    private fun moveOutOfFloor(job: Job) {
        val r = job.snapshot.region
        for (player in job.world.players) {
            if (!isInside(player, r)) continue
            val spot = job.safeSpot() ?: continue
            player.fallDistance = 0f
            player.teleport(spot)
        }
    }

    private fun isInside(player: Player, r: ArenaRegion): Boolean {
        val loc = player.location
        return loc.blockX in r.minX..r.maxX && loc.blockZ in r.minZ..r.maxZ && loc.y >= r.minY - 2 && loc.y < r.maxY + 2
    }

    /** Server stop: running restores are cancelled; their snapshots stay saved and finish on the next startup. */
    fun shutdown() {
        jobs.values.forEach { runCatching { it.task.cancel() } }
        jobs.clear()
    }
}
