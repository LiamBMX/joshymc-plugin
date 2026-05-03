package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.entity.Player
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-player timezone persistence. The Minecraft client doesn't transmit
 * its timezone, so the player has to opt-in via /timezone <zone>. Defaults
 * to the server's system zone, which is the same behaviour as before — no
 * regression for players who don't bother setting it.
 *
 * Storage: `player_timezones(uuid PK, zone_id)`. In-memory cache keeps the
 * sidebar tick from hitting SQLite every 2 seconds per player.
 */
class TimezoneManager(private val plugin: Joshymc) {

    private val cache = ConcurrentHashMap<UUID, ZoneId>()

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS player_timezones (
                uuid TEXT PRIMARY KEY,
                zone_id TEXT NOT NULL
            )
        """.trimIndent())

        plugin.databaseManager.query("SELECT uuid, zone_id FROM player_timezones") { rs ->
            try {
                val uuid = UUID.fromString(rs.getString("uuid"))
                val zone = ZoneId.of(rs.getString("zone_id"))
                uuid to zone
            } catch (e: Exception) {
                null
            }
        }.filterNotNull().forEach { (uuid, zone) -> cache[uuid] = zone }

        plugin.logger.info("[Timezone] Loaded ${cache.size} player timezone(s).")
    }

    /** Player's chosen timezone, falling back to EST (America/New_York). */
    fun zoneFor(player: Player): ZoneId =
        cache[player.uniqueId] ?: DEFAULT_ZONE

    /** Returns null when nothing's been set; useful for /timezone with no args. */
    fun explicitZoneFor(player: Player): ZoneId? = cache[player.uniqueId]

    fun setZone(uuid: UUID, zone: ZoneId) {
        cache[uuid] = zone
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO player_timezones (uuid, zone_id) VALUES (?, ?)",
            uuid.toString(), zone.id
        )
    }

    fun clearZone(uuid: UUID) {
        cache.remove(uuid)
        plugin.databaseManager.execute(
            "DELETE FROM player_timezones WHERE uuid = ?",
            uuid.toString()
        )
    }

    /**
     * Resolve a user-supplied zone id with some friendliness:
     *   - exact ZoneId names — `America/Los_Angeles`, `Europe/London`
     *   - common shorthand   — `EST`, `PST`, `CST`, `MST`, `UTC`, `GMT`
     *   - case-insensitive prefix match — `america/los_angeles` works
     */
    fun parseZone(input: String): ZoneId? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        SHORT_FORMS[trimmed.uppercase()]?.let { return ZoneId.of(it) }
        // Try exact / canonical
        try { return ZoneId.of(trimmed) } catch (_: Exception) {}
        // Case-insensitive scan over all available zones
        val lower = trimmed.lowercase()
        return ZoneId.getAvailableZoneIds()
            .firstOrNull { it.equals(trimmed, ignoreCase = true) }
            ?.let { ZoneId.of(it) }
            ?: ZoneId.getAvailableZoneIds()
                .firstOrNull { it.lowercase() == lower }
                ?.let { ZoneId.of(it) }
    }

    companion object {
        /** Server-wide default when a player hasn't picked one. EST (with DST → EDT). */
        val DEFAULT_ZONE: ZoneId = ZoneId.of("America/New_York")

        /** Common abbreviations players will type. Mapped to canonical
         *  IANA zones (with DST where applicable). */
        private val SHORT_FORMS = mapOf(
            "EST" to "America/New_York",
            "EDT" to "America/New_York",
            "CST" to "America/Chicago",
            "CDT" to "America/Chicago",
            "MST" to "America/Denver",
            "MDT" to "America/Denver",
            "PST" to "America/Los_Angeles",
            "PDT" to "America/Los_Angeles",
            "AKST" to "America/Anchorage",
            "HST" to "Pacific/Honolulu",
            "UTC" to "UTC",
            "GMT" to "Etc/GMT",
            "BST" to "Europe/London",
            "CET" to "Europe/Berlin",
            "CEST" to "Europe/Berlin",
            "EET" to "Europe/Athens",
            "JST" to "Asia/Tokyo",
            "KST" to "Asia/Seoul",
            "IST" to "Asia/Kolkata",
            "AEST" to "Australia/Sydney",
            "AEDT" to "Australia/Sydney",
        )

        /** Suggested completions surfaced by the /timezone tab-complete. */
        val SUGGESTED_ZONES: List<String> = listOf(
            "America/New_York", "America/Chicago", "America/Denver",
            "America/Los_Angeles", "America/Anchorage", "Pacific/Honolulu",
            "America/Toronto", "America/Mexico_City", "America/Sao_Paulo",
            "Europe/London", "Europe/Berlin", "Europe/Paris", "Europe/Madrid",
            "Europe/Athens", "Europe/Moscow",
            "Africa/Cairo", "Africa/Johannesburg",
            "Asia/Dubai", "Asia/Kolkata", "Asia/Shanghai", "Asia/Tokyo",
            "Asia/Seoul", "Asia/Singapore",
            "Australia/Sydney", "Pacific/Auckland",
            "UTC", "EST", "CST", "MST", "PST", "GMT",
        )
    }
}
