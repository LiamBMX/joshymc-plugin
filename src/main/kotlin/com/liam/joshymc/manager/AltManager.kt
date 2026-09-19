package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Tracks hashed connection identifiers for /alts lookup (issue #862). Only a salted
 * HMAC of the connection address is ever persisted or displayed - never the raw
 * address, hostname, or any device/geo data. The salt/pepper lives in a per-server
 * file outside version control so hashes can't be reproduced off-server.
 */
class AltManager(private val plugin: Joshymc) : Listener {

    data class AltMatch(val uuid: UUID, val name: String, val lastSeen: Long)

    private lateinit var salt: ByteArray

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_connections (
                uuid TEXT NOT NULL,
                connection_hash TEXT NOT NULL,
                last_seen INTEGER NOT NULL,
                PRIMARY KEY (uuid, connection_hash)
            )
        """.trimIndent())

        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS alt_lookup_audit (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                staff_uuid TEXT,
                staff_name TEXT NOT NULL,
                target_uuid TEXT NOT NULL,
                target_name TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
        """.trimIndent())

        salt = loadOrCreateSalt()

        plugin.logger.info("[Alts] Alt manager started.")
    }

    private fun loadOrCreateSalt(): ByteArray {
        plugin.dataFolder.mkdirs()
        val file = File(plugin.dataFolder, "alt-salt.txt")
        if (file.exists()) {
            return Base64.getDecoder().decode(file.readText().trim())
        }
        val generated = ByteArray(32)
        SecureRandom().nextBytes(generated)
        file.writeText(Base64.getEncoder().encodeToString(generated))
        return generated
    }

    private fun normalize(address: String): String {
        // Strip an IPv6 zone id (e.g. "fe80::1%eth0") - not otherwise meaningful for matching.
        return address.trim().lowercase().substringBefore('%')
    }

    private fun hash(address: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val digest = mac.doFinal(normalize(address).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Store/update a UUID <-> hashed-connection association. The raw address is never persisted. */
    fun recordConnection(uuid: UUID, address: String) {
        val hashed = hash(address)
        plugin.databaseManager.execute(
            """
            INSERT INTO player_connections (uuid, connection_hash, last_seen) VALUES (?, ?, ?)
            ON CONFLICT(uuid, connection_hash) DO UPDATE SET last_seen = excluded.last_seen
            """.trimIndent(),
            uuid.toString(), hashed, System.currentTimeMillis()
        )
    }

    /** Other accounts that have shared a connection hash with [targetUuid], most recently seen first. Excludes [targetUuid] itself. */
    fun findAlts(targetUuid: UUID): List<AltMatch> {
        val hashes = plugin.databaseManager.query(
            "SELECT DISTINCT connection_hash FROM player_connections WHERE uuid = ?",
            targetUuid.toString()
        ) { rs -> rs.getString("connection_hash") }

        if (hashes.isEmpty()) return emptyList()

        val lastSeenByUuid = mutableMapOf<UUID, Long>()
        for (hashValue in hashes) {
            plugin.databaseManager.query(
                "SELECT uuid, last_seen FROM player_connections WHERE connection_hash = ? AND uuid != ?",
                hashValue, targetUuid.toString()
            ) { rs -> rs.getString("uuid") to rs.getLong("last_seen") }
                .forEach { (uuidStr, lastSeen) ->
                    val uuid = runCatching { UUID.fromString(uuidStr) }.getOrNull() ?: return@forEach
                    lastSeenByUuid[uuid] = maxOf(lastSeenByUuid[uuid] ?: 0L, lastSeen)
                }
        }

        return lastSeenByUuid.entries
            .sortedByDescending { it.value }
            .mapNotNull { (uuid, lastSeen) ->
                val name = Bukkit.getOfflinePlayer(uuid).name ?: return@mapNotNull null
                AltMatch(uuid, name, lastSeen)
            }
    }

    /** Staff audit trail for /alts lookups - usernames/UUIDs only, never the underlying hashes. */
    fun logLookup(staffUuid: UUID?, staffName: String, targetUuid: UUID, targetName: String) {
        plugin.databaseManager.execute(
            "INSERT INTO alt_lookup_audit (staff_uuid, staff_name, target_uuid, target_name, timestamp) VALUES (?, ?, ?, ?, ?)",
            staffUuid?.toString(), staffName, targetUuid.toString(), targetName, System.currentTimeMillis()
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val address = event.player.address?.address?.hostAddress ?: return
        recordConnection(event.player.uniqueId, address)
    }
}
