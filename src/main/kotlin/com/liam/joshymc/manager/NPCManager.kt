package com.liam.joshymc.manager

import com.destroystokyo.paper.SkinParts
import com.liam.joshymc.Joshymc
import io.papermc.paper.datacomponent.item.ResolvableProfile
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Mannequin
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import java.util.UUID

/**
 * Command NPCs. Each NPC is a Mannequin: a real player-model entity that shows any
 * player's skin, so clicks, moves, renames and deletes all work on a normal entity.
 */
class NPCManager(private val plugin: Joshymc) : Listener {

    companion object {
        private const val TAG_PREFIX = "joshymc_npc_"
        private const val COOLDOWN_MS = 1000L
    }

    /** NPC id -> entity UUID of the spawned entity */
    private val npcs = mutableMapOf<String, UUID>()

    /** Player UUID -> last interaction timestamp (for cooldown) */
    private val cooldowns = mutableMapOf<UUID, Long>()

    private val legacySerializer = LegacyComponentSerializer.legacyAmpersand()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS npcs (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                world TEXT NOT NULL,
                x DOUBLE NOT NULL,
                y DOUBLE NOT NULL,
                z DOUBLE NOT NULL,
                yaw REAL NOT NULL,
                pitch REAL NOT NULL,
                command TEXT NOT NULL DEFAULT '',
                skin TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())

        // Add skin column if upgrading from older schema
        try {
            plugin.databaseManager.execute("ALTER TABLE npcs ADD COLUMN skin TEXT NOT NULL DEFAULT ''")
        } catch (_: Exception) {
            // Column already exists
        }

        // Defer the actual NPC respawning until ALL worlds are loaded.
        // The 'spawn' world (and other plugin-managed worlds) are created after
        // NPCManager.start() runs, so loading immediately would skip every NPC
        // in those worlds. A 1-tick delay is enough — by then onEnable has
        // finished and every world is registered.
        plugin.server.scheduler.runTaskLater(plugin, Runnable { loadFromDatabase() }, 1L)
    }

    /** Load every saved NPC from the database and spawn it. Called after worlds load. */
    private fun loadFromDatabase() {
        // Clean up any orphaned NPC entities from previous runs first
        cleanupOrphanedNpcEntities()

        var loaded = 0
        var deferred = 0
        plugin.databaseManager.query("SELECT * FROM npcs") { rs ->
            NPCData(
                id = rs.getString("id"),
                name = rs.getString("name"),
                world = rs.getString("world"),
                x = rs.getDouble("x"),
                y = rs.getDouble("y"),
                z = rs.getDouble("z"),
                yaw = rs.getFloat("yaw"),
                pitch = rs.getFloat("pitch"),
                command = rs.getString("command"),
                skin = rs.getString("skin") ?: ""
            )
        }.forEach { data ->
            val world = Bukkit.getWorld(data.world)
            if (world != null) {
                val loc = Location(world, data.x, data.y, data.z, data.yaw, data.pitch)
                spawnNpc(data.id, data.name, loc, data.skin)
                loaded++
            } else {
                // World still isn't loaded yet — defer further. Try again in 60 ticks.
                deferred++
                val saved = data
                plugin.server.scheduler.runTaskLater(plugin, Runnable {
                    val w = Bukkit.getWorld(saved.world)
                    if (w != null) {
                        val loc = Location(w, saved.x, saved.y, saved.z, saved.yaw, saved.pitch)
                        spawnNpc(saved.id, saved.name, loc, saved.skin)
                        plugin.logger.info("[NPC] Late-spawned NPC '${saved.id}' in world '${saved.world}'.")
                    } else {
                        plugin.logger.warning("[NPC] World '${saved.world}' is still not loaded — NPC '${saved.id}' was kept in database but not spawned.")
                    }
                }, 60L)
            }
        }

        plugin.logger.info("[NPC] Loaded $loaded NPC(s)" + if (deferred > 0) " ($deferred deferred for late world load)" else "" + ".")
    }

    /** Remove any NPC entity left over from previous runs (including old zombie NPCs). */
    private fun cleanupOrphanedNpcEntities() {
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.entities) {
                if (entity.scoreboardTags.any { it.startsWith(TAG_PREFIX) }) {
                    entity.remove()
                    removed++
                }
            }
        }
        if (removed > 0) plugin.logger.info("[NPC] Cleaned up $removed orphaned NPC entit${if (removed != 1) "ies" else "y"}.")
    }

    fun stop() {
        // Remove all spawned NPC entities
        for ((_, entityUuid) in npcs) {
            Bukkit.getEntity(entityUuid)?.remove()
        }
        npcs.clear()
        cooldowns.clear()
    }

    fun createNPC(id: String, name: String, location: Location) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO npcs (id, name, world, x, y, z, yaw, pitch, command, skin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, name, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, "", ""
        )
        spawnNpc(id, name, location, "")
    }

    fun deleteNPC(id: String): Boolean {
        val entityUuid = npcs.remove(id) ?: return false
        Bukkit.getEntity(entityUuid)?.remove()
        plugin.databaseManager.execute("DELETE FROM npcs WHERE id = ?", id)
        return true
    }

    fun getNPCIds(): List<String> {
        return npcs.keys.toList()
    }

    fun moveNPC(id: String, location: Location): Boolean {
        val entityUuid = npcs[id] ?: return false
        val entity = Bukkit.getEntity(entityUuid) ?: return false

        entity.teleport(location)
        plugin.databaseManager.execute(
            "UPDATE npcs SET world = ?, x = ?, y = ?, z = ?, yaw = ?, pitch = ? WHERE id = ?",
            location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, id
        )
        return true
    }

    fun setCommand(id: String, command: String): Boolean {
        if (id !in npcs) return false
        plugin.databaseManager.execute("UPDATE npcs SET command = ? WHERE id = ?", command, id)
        return true
    }

    fun setName(id: String, name: String): Boolean {
        val entityUuid = npcs[id] ?: return false
        val entity = Bukkit.getEntity(entityUuid) ?: return false

        entity.customName(legacySerializer.deserialize(name))
        plugin.databaseManager.execute("UPDATE npcs SET name = ? WHERE id = ?", name, id)
        return true
    }

    /**
     * Set the NPC's skin to that of the named player (online or offline).
     * Returns true if the NPC exists and the skin was applied.
     */
    fun setSkin(id: String, playerName: String): Boolean {
        val entityUuid = npcs[id] ?: return false
        val mannequin = Bukkit.getEntity(entityUuid) as? Mannequin ?: return false

        plugin.databaseManager.execute("UPDATE npcs SET skin = ? WHERE id = ?", playerName, id)
        mannequin.profile = skinProfile(playerName)
        return true
    }

    // ── Events ──────────────────────────────────────────────

    @EventHandler
    fun onInteract(event: PlayerInteractEntityEvent) {
        val npcId = getNPCId(event.rightClicked) ?: return
        event.isCancelled = true

        val player = event.player
        val now = System.currentTimeMillis()
        val last = cooldowns[player.uniqueId] ?: 0L
        if (now - last < COOLDOWN_MS) return
        cooldowns[player.uniqueId] = now

        val command = plugin.databaseManager.queryFirst(
            "SELECT command FROM npcs WHERE id = ?", npcId
        ) { rs -> rs.getString("command") } ?: return

        if (command.isBlank()) return

        val resolved = command.replace("{player}", player.name)
        player.performCommand(resolved)
    }

    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        if (getNPCId(event.entity) != null) {
            event.isCancelled = true
        }
    }

    // ── Internal ────────────────────────────────────────────

    private fun spawnNpc(id: String, name: String, location: Location, skinPlayerName: String) {
        // Remove old entity if one exists
        npcs[id]?.let { Bukkit.getEntity(it)?.remove() }

        val mannequin = location.world.spawn(location, Mannequin::class.java) { m ->
            m.customName(legacySerializer.deserialize(name))
            m.isCustomNameVisible = true
            m.description = null // hide the default "NPC" line under the name
            m.isImmovable = true
            m.setAI(false)
            m.isInvulnerable = true
            m.isSilent = true
            m.setGravity(false)
            m.isPersistent = true
            m.setSkinParts(SkinParts.allParts())
            if (skinPlayerName.isNotBlank()) m.profile = skinProfile(skinPlayerName)
            m.addScoreboardTag("${TAG_PREFIX}$id")
        }

        npcs[id] = mannequin.uniqueId
    }

    /** A profile by name only; the server resolves its skin textures from Mojang. */
    private fun skinProfile(playerName: String): ResolvableProfile =
        ResolvableProfile.resolvableProfile().name(playerName).build()

    private fun getNPCId(entity: Entity): String? {
        return entity.scoreboardTags
            .firstOrNull { it.startsWith(TAG_PREFIX) }
            ?.removePrefix(TAG_PREFIX)
    }

    private data class NPCData(
        val id: String,
        val name: String,
        val world: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val command: String,
        val skin: String
    )
}
