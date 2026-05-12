package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Location

class WarpManager(private val plugin: Joshymc) {

    data class WarpInfo(val name: String, val ownerUuid: String, val location: Location, val icon: String = "ENDER_PEARL")

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS spawn (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                world TEXT NOT NULL,
                x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
                yaw REAL NOT NULL, pitch REAL NOT NULL
            )
        """.trimIndent())

        // Server warps (admin-created)
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS warps (
                name TEXT PRIMARY KEY,
                owner TEXT NOT NULL,
                world TEXT NOT NULL,
                x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
                yaw REAL NOT NULL, pitch REAL NOT NULL,
                icon TEXT NOT NULL DEFAULT 'ENDER_PEARL'
            )
        """.trimIndent())

        // Player warps (player-created)
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_warps (
                name TEXT PRIMARY KEY,
                owner TEXT NOT NULL,
                owner_name TEXT NOT NULL,
                world TEXT NOT NULL,
                x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
                yaw REAL NOT NULL, pitch REAL NOT NULL,
                icon TEXT NOT NULL DEFAULT 'OAK_SIGN'
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS homes (
                uuid TEXT NOT NULL,
                name TEXT NOT NULL,
                world TEXT NOT NULL,
                x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL,
                yaw REAL NOT NULL, pitch REAL NOT NULL,
                PRIMARY KEY (uuid, name)
            )
        """.trimIndent())

        // Migrate: add icon column if missing
        try {
            plugin.databaseManager.execute("ALTER TABLE warps ADD COLUMN icon TEXT NOT NULL DEFAULT 'ENDER_PEARL'")
        } catch (_: Exception) {}
        try {
            plugin.databaseManager.execute("ALTER TABLE player_warps ADD COLUMN icon TEXT NOT NULL DEFAULT 'OAK_SIGN'")
        } catch (_: Exception) {}

        plugin.logger.info("[WarpManager] Tables initialized.")
    }

    // ── Spawn ──────────────────────────────────────────────

    fun setSpawn(location: Location) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO spawn (id, world, x, y, z, yaw, pitch) VALUES (1, ?, ?, ?, ?, ?, ?)",
            location.world.name, location.x, location.y, location.z, location.yaw, location.pitch
        )
    }

    fun getSpawn(): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z, yaw, pitch FROM spawn WHERE id = 1"
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"))
        }
    }

    // ── Warps ──────────────────────────────────────────────

    fun setWarp(name: String, owner: String, location: Location, icon: String = "ENDER_PEARL") {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO warps (name, owner, world, x, y, z, yaw, pitch, icon) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            name, owner, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch, icon
        )
    }

    fun setWarpIcon(name: String, icon: String): Boolean {
        val exists = plugin.databaseManager.queryFirst(
            "SELECT name FROM warps WHERE name = ?", name
        ) { true } ?: false
        if (!exists) return false
        plugin.databaseManager.execute("UPDATE warps SET icon = ? WHERE name = ?", icon, name)
        return true
    }

    fun getWarp(name: String): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z, yaw, pitch FROM warps WHERE name = ?", name
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"))
        }
    }

    fun deleteWarp(name: String, playerUuid: String): Boolean {
        val existing = plugin.databaseManager.queryFirst(
            "SELECT owner FROM warps WHERE name = ?", name
        ) { rs -> rs.getString("owner") } ?: return false

        if (existing != playerUuid) return false

        plugin.databaseManager.execute("DELETE FROM warps WHERE name = ?", name)
        return true
    }

    fun forceDeleteWarp(name: String): Boolean {
        val count = plugin.databaseManager.query(
            "SELECT name FROM warps WHERE name = ?", name
        ) { it.getString("name") }
        if (count.isEmpty()) return false
        plugin.databaseManager.execute("DELETE FROM warps WHERE name = ?", name)
        return true
    }

    fun getAllWarps(): List<WarpInfo> {
        return plugin.databaseManager.query(
            "SELECT name, owner, world, x, y, z, yaw, pitch, icon FROM warps ORDER BY name"
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world"))
            if (world != null) {
                WarpInfo(
                    rs.getString("name"), rs.getString("owner"),
                    Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch")),
                    rs.getString("icon") ?: "ENDER_PEARL"
                )
            } else null
        }.filterNotNull()
    }

    fun getPlayerWarps(uuid: String): List<WarpInfo> {
        return plugin.databaseManager.query(
            "SELECT name, owner, world, x, y, z, yaw, pitch FROM warps WHERE owner = ? ORDER BY name", uuid
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world"))
            if (world != null) {
                WarpInfo(
                    rs.getString("name"), rs.getString("owner"),
                    Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch"))
                )
            } else null
        }.filterNotNull()
    }

    // ── Player Warps ────────────────────────────────────────

    fun setPlayerWarp(name: String, ownerUuid: String, ownerName: String, location: Location) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_warps (name, owner, owner_name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            name, ownerUuid, ownerName, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch
        )
    }

    fun getPlayerWarp(name: String): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z, yaw, pitch FROM player_warps WHERE name = ?", name
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"))
        }
    }

    fun deletePlayerWarp(name: String, playerUuid: String): Boolean {
        val existing = plugin.databaseManager.queryFirst(
            "SELECT owner FROM player_warps WHERE name = ?", name
        ) { rs -> rs.getString("owner") } ?: return false

        if (existing != playerUuid) return false

        plugin.databaseManager.execute("DELETE FROM player_warps WHERE name = ?", name)
        return true
    }

    fun forceDeletePlayerWarp(name: String): Boolean {
        val count = plugin.databaseManager.query(
            "SELECT name FROM player_warps WHERE name = ?", name
        ) { it.getString("name") }
        if (count.isEmpty()) return false
        plugin.databaseManager.execute("DELETE FROM player_warps WHERE name = ?", name)
        return true
    }

    fun getAllPlayerWarps(): List<WarpInfo> {
        return plugin.databaseManager.query(
            "SELECT name, owner, world, x, y, z, yaw, pitch, icon FROM player_warps ORDER BY name"
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world"))
            if (world != null) {
                WarpInfo(
                    rs.getString("name"), rs.getString("owner"),
                    Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch")),
                    rs.getString("icon") ?: "OAK_SIGN"
                )
            } else null
        }.filterNotNull()
    }

    fun getPlayerWarpsByOwner(uuid: String): List<WarpInfo> {
        return plugin.databaseManager.query(
            "SELECT name, owner, world, x, y, z, yaw, pitch, icon FROM player_warps WHERE owner = ? ORDER BY name", uuid
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world"))
            if (world != null) {
                WarpInfo(
                    rs.getString("name"), rs.getString("owner"),
                    Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                        rs.getFloat("yaw"), rs.getFloat("pitch")),
                    rs.getString("icon") ?: "OAK_SIGN"
                )
            } else null
        }.filterNotNull()
    }

    fun setPlayerWarpIcon(name: String, icon: String, ownerUuid: String): Boolean {
        val owner = plugin.databaseManager.queryFirst(
            "SELECT owner FROM player_warps WHERE name = ?", name
        ) { rs -> rs.getString("owner") } ?: return false
        if (owner != ownerUuid) return false
        plugin.databaseManager.execute("UPDATE player_warps SET icon = ? WHERE name = ?", icon, name)
        return true
    }

    fun getPlayerWarpCount(uuid: String): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM player_warps WHERE owner = ?", uuid
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun getPlayerWarpOwnerName(name: String): String? {
        return plugin.databaseManager.queryFirst(
            "SELECT owner_name FROM player_warps WHERE name = ?", name
        ) { rs -> rs.getString("owner_name") }
    }

    // ── Homes ──────────────────────────────────────────────

    fun setHome(uuid: String, name: String, location: Location) {
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO homes (uuid, name, world, x, y, z, yaw, pitch) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            uuid, name, location.world.name, location.x, location.y, location.z, location.yaw, location.pitch
        )
    }

    fun getHome(uuid: String, name: String): Location? {
        return plugin.databaseManager.queryFirst(
            "SELECT world, x, y, z, yaw, pitch FROM homes WHERE uuid = ? AND name = ?", uuid, name
        ) { rs ->
            val world = plugin.server.getWorld(rs.getString("world")) ?: return@queryFirst null
            Location(world, rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("z"),
                rs.getFloat("yaw"), rs.getFloat("pitch"))
        }
    }

    fun deleteHome(uuid: String, name: String): Boolean {
        val exists = plugin.databaseManager.queryFirst(
            "SELECT name FROM homes WHERE uuid = ? AND name = ?", uuid, name
        ) { true } ?: false
        if (!exists) return false
        plugin.databaseManager.execute("DELETE FROM homes WHERE uuid = ? AND name = ?", uuid, name)
        return true
    }

    fun getHomes(uuid: String): List<String> {
        return plugin.databaseManager.query(
            "SELECT name FROM homes WHERE uuid = ? ORDER BY name", uuid
        ) { rs -> rs.getString("name") }
    }

    fun getHomeCount(uuid: String): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM homes WHERE uuid = ?", uuid
        ) { rs -> rs.getInt("cnt") } ?: 0
    }

    fun getWarpCount(uuid: String): Int {
        return plugin.databaseManager.queryFirst(
            "SELECT COUNT(*) as cnt FROM warps WHERE owner = ?", uuid
        ) { rs -> rs.getInt("cnt") } ?: 0
    }
}
