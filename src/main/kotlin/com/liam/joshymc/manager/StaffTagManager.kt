package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Temporary staff chat tags (currently only SOTM — Staff of the Month).
 *
 * A staff tag is NOT a normal [ChatTagManager] tag: it is never unlockable, purchasable or
 * selectable. It simply takes display priority over the player's regular tag while active,
 * so the regular selection (player_tags) is never touched and is "restored" automatically
 * the moment the staff tag expires or is removed.
 *
 * Expiry is an absolute epoch-millis timestamp, so time passes while offline / across restarts.
 * Active records are cached in memory; chat and TAB never hit the database.
 */
class StaffTagManager(private val plugin: Joshymc) {

    private data class Record(val awardedAt: Long, val expiresAt: Long)

    private val active = mutableMapOf<UUID, Record>() // SOTM records
    private var taskId = -1

    private var enabled = true
    private var display = DEFAULT_DISPLAY
    private var defaultDuration = "30d"
    private var allowedDurations = DEFAULT_DURATIONS
    private var showInChat = true
    private var showInTab = true

    fun start() {
        if (taskId != -1) plugin.server.scheduler.cancelTask(taskId)

        val section = plugin.config.getConfigurationSection("staff-tags.sotm")
        enabled = section?.getBoolean("enabled", true) ?: true
        display = section?.getString("display", DEFAULT_DISPLAY) ?: DEFAULT_DISPLAY
        allowedDurations = section?.getStringList("allowed-durations")?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_DURATIONS
        defaultDuration = section?.getString("default-duration", "30d")?.takeIf { it in allowedDurations }
            ?: allowedDurations.last()
        showInChat = section?.getBoolean("show-in-chat", true) ?: true
        showInTab = section?.getBoolean("show-in-tab", true) ?: true

        // Primary key => no duplicate active records per player.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS staff_tags (
                uuid TEXT NOT NULL,
                tag TEXT NOT NULL,
                awarded_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                PRIMARY KEY (uuid, tag)
            )
        """.trimIndent())

        // Purge anything already expired so it can never reappear after a restart.
        plugin.databaseManager.execute(
            "DELETE FROM staff_tags WHERE tag = ? AND expires_at <= ?", SOTM, System.currentTimeMillis()
        )

        active.clear()
        val rows = plugin.databaseManager.query(
            "SELECT uuid, awarded_at, expires_at FROM staff_tags WHERE tag = 'SOTM'"
        ) { rs -> Triple(rs.getString("uuid"), rs.getLong("awarded_at"), rs.getLong("expires_at")) }
        for ((uuid, awarded, expires) in rows) {
            active[UUID.fromString(uuid)] = Record(awarded, expires)
        }

        // Prompt expiry for online players (every 10s).
        taskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable { expireDue() }, 200L, 200L)
        plugin.logger.info("[StaffTags] Loaded ${active.size} active SOTM tag(s).")
    }

    fun stop() {
        if (taskId != -1) plugin.server.scheduler.cancelTask(taskId)
        taskId = -1
    }

    fun getAllowedDurations(): List<String> = allowedDurations
    fun getDefaultDuration(): String = defaultDuration
    fun isEnabled(): Boolean = enabled

    /** Parses "7d" style durations to millis, or null if it isn't an allowed duration. */
    fun parseDuration(input: String): Long? {
        val lower = input.lowercase()
        if (lower !in allowedDurations) return null
        val days = lower.removeSuffix("d").toLongOrNull() ?: return null
        return days * 24L * 60L * 60L * 1000L
    }

    fun hasSotm(uuid: UUID): Boolean {
        val rec = active[uuid] ?: return false
        if (rec.expiresAt > System.currentTimeMillis()) return true
        expire(uuid)
        return false
    }

    /** Awards (or re-awards, resetting the countdown from now) SOTM. Returns the expiry millis. */
    fun awardSotm(uuid: UUID, durationMs: Long): Long {
        val now = System.currentTimeMillis()
        val rec = Record(now, now + durationMs)
        active[uuid] = rec
        plugin.databaseManager.execute(
            "INSERT OR REPLACE INTO staff_tags (uuid, tag, awarded_at, expires_at) VALUES (?, ?, ?, ?)",
            uuid.toString(), SOTM, rec.awardedAt, rec.expiresAt
        )
        refreshTab(uuid)
        return rec.expiresAt
    }

    /** Removes SOTM immediately. Returns false if the player had none. */
    fun removeSotm(uuid: UUID): Boolean {
        if (active.remove(uuid) == null) return false
        plugin.databaseManager.execute("DELETE FROM staff_tags WHERE uuid = ? AND tag = ?", uuid.toString(), SOTM)
        refreshTab(uuid)
        return true
    }

    /** Chat tag text (legacy codes) to use in place of the regular tag, or null if none applies. */
    fun getChatDisplay(player: Player): String? =
        if (enabled && showInChat && hasSotm(player.uniqueId)) "$display " else null

    /** TAB text (legacy codes) to place between rank prefix and name; empty if none applies. */
    fun getTabDisplay(player: Player): String =
        if (enabled && showInTab && hasSotm(player.uniqueId)) "$display " else ""

    private fun expire(uuid: UUID) {
        if (active.remove(uuid) == null) return
        plugin.databaseManager.execute("DELETE FROM staff_tags WHERE uuid = ? AND tag = ?", uuid.toString(), SOTM)
        refreshTab(uuid)
    }

    private fun expireDue() {
        val now = System.currentTimeMillis()
        for (uuid in active.filterValues { it.expiresAt <= now }.keys.toList()) expire(uuid)
    }

    private fun refreshTab(uuid: UUID) {
        val player = Bukkit.getPlayer(uuid) ?: return
        plugin.scoreboardManager.updateTabName(player)
    }

    companion object {
        const val SOTM = "SOTM"
        const val DEFAULT_DISPLAY = "&7[&#FFF08DS&#F5DF68O&#ECCF42T&#F5DF68M&7]&r"
        private val DEFAULT_DURATIONS = listOf("1d", "7d", "14d", "21d", "30d")
    }
}
