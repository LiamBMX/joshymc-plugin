package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import java.util.UUID

class HologramManager(private val plugin: Joshymc) {

    private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()

    /** hologram id -> list of TextDisplay entity UUIDs (top line first) */
    private val entities = mutableMapOf<String, MutableList<UUID>>()

    private companion object {
        const val LINE_SPACING = 0.3
    }

    // ── Lifecycle ───────────────────────────────────────────

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS holograms (
                id TEXT PRIMARY KEY,
                world TEXT NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                lines TEXT NOT NULL,
                scale REAL DEFAULT 1.0,
                yaw REAL DEFAULT 0.0,
                locked INTEGER DEFAULT 0
            )
        """.trimIndent())

        // Add new columns for upgrades from older schema
        try { plugin.databaseManager.execute("ALTER TABLE holograms ADD COLUMN scale REAL DEFAULT 1.0") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE holograms ADD COLUMN yaw REAL DEFAULT 0.0") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE holograms ADD COLUMN locked INTEGER DEFAULT 0") } catch (_: Exception) {}

        // Spawn all saved holograms once the server is ready
        plugin.server.scheduler.runTaskLater(plugin, Runnable { loadAll() }, 1L)

        plugin.logger.info("[Holograms] Manager started.")
    }

    fun stop() {
        // Remove all spawned entities
        for ((_, uuids) in entities) {
            for (uuid in uuids) {
                Bukkit.getEntity(uuid)?.remove()
            }
        }
        entities.clear()
        plugin.logger.info("[Holograms] Manager stopped.")
    }

    // ── Public API ──────────────────────────────────────────

    fun createHologram(id: String, location: Location, lines: List<String>): Boolean {
        if (exists(id)) return false

        plugin.databaseManager.execute(
            "INSERT INTO holograms (id, world, x, y, z, lines) VALUES (?, ?, ?, ?, ?, ?)",
            id, location.world.name, location.x, location.y, location.z, lines.joinToString("\n")
        )

        spawnEntities(id, location, lines)
        return true
    }

    fun deleteHologram(id: String): Boolean {
        if (!exists(id)) return false

        despawnEntities(id)
        // Fallback: scan every loaded world for any TextDisplay tagged with this
        // hologram id. This catches entities that were orphaned from in-memory
        // tracking (reload, prior run, or entities spawned outside this manager
        // such as crate holograms) so delete actually removes them from the world.
        val tag = "joshymc_holo_$id"
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity is TextDisplay && entity.scoreboardTags.contains(tag)) {
                    entity.remove()
                }
            }
        }
        plugin.databaseManager.execute("DELETE FROM holograms WHERE id = ?", id)
        return true
    }

    fun moveHologram(id: String, location: Location): Boolean {
        val lines = getLines(id) ?: return false

        plugin.databaseManager.execute(
            "UPDATE holograms SET world = ?, x = ?, y = ?, z = ? WHERE id = ?",
            location.world.name, location.x, location.y, location.z, id
        )

        despawnEntities(id)
        spawnEntities(id, location, lines)
        return true
    }

    fun addLine(id: String, text: String): Boolean {
        val lines = getLines(id)?.toMutableList() ?: return false
        lines.add(text)
        updateLines(id, lines)
        return true
    }

    fun removeLine(id: String, lineNumber: Int): Boolean {
        val lines = getLines(id)?.toMutableList() ?: return false
        if (lineNumber < 1 || lineNumber > lines.size) return false

        lines.removeAt(lineNumber - 1)
        updateLines(id, lines)
        return true
    }

    fun setLine(id: String, lineNumber: Int, text: String): Boolean {
        val lines = getLines(id)?.toMutableList() ?: return false
        if (lineNumber < 1 || lineNumber > lines.size) return false

        lines[lineNumber - 1] = text
        updateLines(id, lines)
        return true
    }

    fun getHologramIds(): List<String> {
        return plugin.databaseManager.query(
            "SELECT id FROM holograms ORDER BY id"
        ) { rs -> rs.getString("id") }
    }

    /**
     * Find every entity in the player's current world that has a "joshymc_holo_*"
     * scoreboard tag whose id is NOT in the database, and remove them.
     *
     * This safely targets ONLY orphaned hologram entities — it does not touch
     * NPCs, mobs, ducks, or any other plugin entities.
     *
     * Returns the number of entities removed.
     */
    fun cleanupOrphans(world: World): Int {
        val knownIds = getHologramIds().toSet()
        var removed = 0

        for (entity in world.entities) {
            // Only TextDisplay entities can be holograms
            if (entity !is TextDisplay) continue

            // Find a tag that starts with "joshymc_holo_"
            val holoTag = entity.scoreboardTags.firstOrNull { it.startsWith("joshymc_holo_") } ?: continue
            val id = holoTag.removePrefix("joshymc_holo_")

            // If this id is unknown to the DB, it's orphaned — remove it
            if (id !in knownIds) {
                entity.remove()
                removed++
            }
        }
        return removed
    }

    /**
     * Aggressive cleanup: removes ALL TextDisplay entities with the "joshymc_holo_"
     * tag in the world, regardless of whether they're known. Then re-spawns the
     * tracked holograms cleanly. Use when even known holograms are duplicated.
     */
    fun cleanupAll(world: World): Int {
        var removed = 0

        // Snapshot the entity tracking so we know what to re-spawn
        val toRespawn = entities.toMap()

        for (entity in world.entities) {
            if (entity !is TextDisplay) continue
            if (entity.scoreboardTags.any { it.startsWith("joshymc_holo_") }) {
                entity.remove()
                removed++
            }
        }

        // Clear in-memory tracking for this world's holograms
        entities.clear()

        // Re-spawn from DB so legitimate holograms come back
        loadAll()

        return removed
    }

    // ── Internal ────────────────────────────────────────────

    private fun exists(id: String): Boolean {
        return plugin.databaseManager.queryFirst(
            "SELECT id FROM holograms WHERE id = ?", id
        ) { true } ?: false
    }

    private fun getLines(id: String): List<String>? {
        return plugin.databaseManager.queryFirst(
            "SELECT lines FROM holograms WHERE id = ?", id
        ) { rs ->
            val raw = rs.getString("lines")
            if (raw.isEmpty()) emptyList() else raw.split("\n")
        }
    }

    private fun getLocation(id: String): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z FROM holograms WHERE id = ?", id
        ) { rs ->
            val world = Bukkit.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"))
        }
    }

    fun updateLines(id: String, lines: List<String>) {
        plugin.databaseManager.execute(
            "UPDATE holograms SET lines = ? WHERE id = ?",
            lines.joinToString("\n"), id
        )

        val existing = entities[id]
        if (existing != null && existing.size == lines.size) {
            // Fast path: same line count — update text in-place, no entity churn.
            // This is the common case for leaderboard refreshes.
            var allAlive = true
            for ((i, uuid) in existing.withIndex()) {
                val display = Bukkit.getEntity(uuid) as? TextDisplay
                if (display != null) {
                    display.text(legacySerializer.deserialize(lines[i]))
                } else {
                    allAlive = false
                    break
                }
            }
            if (allAlive) return
        }

        // Full respawn: line count changed or an entity went missing
        val location = getLocation(id) ?: return
        despawnEntities(id)
        spawnEntities(id, location, lines)
    }

    private data class HoloStyle(val scale: Float, val yaw: Float, val locked: Boolean)

    private fun loadStyle(id: String): HoloStyle {
        return plugin.databaseManager.queryFirst(
            "SELECT scale, yaw, locked FROM holograms WHERE id = ?", id
        ) { rs ->
            HoloStyle(
                scale = rs.getFloat("scale").let { if (it <= 0f) 1f else it },
                yaw = rs.getFloat("yaw"),
                locked = rs.getInt("locked") == 1
            )
        } ?: HoloStyle(1f, 0f, false)
    }

    private fun loadAll() {
        // Remove any hologram entities that survived from a prior run (persistent=true
        // before this fix, or a crash before despawn). Without this, loadAll spawns
        // duplicates on top of the still-loaded entities.
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities.toList()) {
                if (entity is TextDisplay && entity.scoreboardTags.any { it.startsWith("joshymc_holo_") }) {
                    entity.remove()
                }
            }
        }
        entities.clear()

        data class HoloRow(val id: String, val location: Location, val lines: List<String>, val style: HoloStyle)

        val holograms = plugin.databaseManager.query<HoloRow?>(
            "SELECT id, world, x, y, z, lines, scale, yaw, locked FROM holograms"
        ) { rs ->
            val world = Bukkit.getWorld(rs.getString("world"))
            if (world != null) {
                HoloRow(
                    id = rs.getString("id"),
                    location = Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z")),
                    lines = rs.getString("lines").let { if (it.isEmpty()) emptyList() else it.split("\n") },
                    style = HoloStyle(
                        scale = rs.getFloat("scale").let { if (it <= 0f) 1f else it },
                        yaw = rs.getFloat("yaw"),
                        locked = rs.getInt("locked") == 1
                    )
                )
            } else null
        }.filterNotNull()

        for (row in holograms) {
            spawnEntities(row.id, row.location, row.lines, row.style)
        }

        if (holograms.isNotEmpty()) {
            plugin.logger.info("[Holograms] Loaded ${holograms.size} hologram(s).")
        }
    }

    private fun spawnEntities(id: String, origin: Location, lines: List<String>, style: HoloStyle = loadStyle(id)) {
        val uuids = mutableListOf<UUID>()
        val effectiveSpacing = LINE_SPACING * style.scale

        for ((index, line) in lines.withIndex()) {
            val loc = origin.clone().add(0.0, -index * effectiveSpacing, 0.0)
            // Apply yaw if locked, otherwise leave default
            if (style.locked) loc.yaw = style.yaw
            val world = loc.world

            val display = world.spawn(loc, TextDisplay::class.java) { entity ->
                entity.text(legacySerializer.deserialize(line))
                // Locked = fixed direction (no billboard); otherwise face the player
                entity.billboard = if (style.locked) Display.Billboard.FIXED else Display.Billboard.CENTER
                entity.backgroundColor = org.bukkit.Color.fromARGB(0, 0, 0, 0)
                entity.isShadowed = true
                entity.addScoreboardTag("joshymc_holo_$id")

                // Don't persist — the DB is the source of truth; loadAll() re-spawns on restart.
                // Persistent entities would double-spawn on top of the freshly-spawned ones.
                entity.isPersistent = false

                // Apply scale via Display transformation
                if (style.scale != 1f) {
                    val t = entity.transformation
                    val scaleVec = org.joml.Vector3f(style.scale, style.scale, style.scale)
                    entity.transformation = org.bukkit.util.Transformation(
                        t.translation, t.leftRotation, scaleVec, t.rightRotation
                    )
                }
            }

            uuids.add(display.uniqueId)
        }

        entities[id] = uuids
    }

    // ── Style API ──────────────────────────────────────────

    /** Set the visual scale of a hologram (e.g., 0.5 = half size, 2.0 = double). */
    fun setScale(id: String, scale: Float): Boolean {
        if (!exists(id)) return false
        if (scale <= 0f) return false
        plugin.databaseManager.execute("UPDATE holograms SET scale = ? WHERE id = ?", scale, id)
        respawn(id)
        return true
    }

    /**
     * Lock the hologram in place facing a fixed direction. Yaw is in degrees:
     * 0 = south, 90 = west, 180 = north, 270 = east. Pass null to unlock.
     */
    fun setRotation(id: String, yawDegrees: Float?): Boolean {
        if (!exists(id)) return false
        if (yawDegrees == null) {
            plugin.databaseManager.execute("UPDATE holograms SET locked = 0 WHERE id = ?", id)
        } else {
            plugin.databaseManager.execute("UPDATE holograms SET yaw = ?, locked = 1 WHERE id = ?", yawDegrees, id)
        }
        respawn(id)
        return true
    }

    /** Re-spawn the hologram entities with the latest style/lines from the DB. */
    private fun respawn(id: String) {
        val lines = getLines(id) ?: return
        val location = getLocation(id) ?: return
        despawnEntities(id)
        spawnEntities(id, location, lines)
    }

    private fun despawnEntities(id: String) {
        entities.remove(id)?.forEach { uuid ->
            Bukkit.getEntity(uuid)?.remove()
        }
    }
}
