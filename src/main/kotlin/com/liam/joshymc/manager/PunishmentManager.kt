package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class PunishmentManager(private val plugin: Joshymc) : Listener {

    data class ActivePunishment(
        val reason: String?,
        val expiresAt: Long?,
        val punisherName: String,
        val id: Int = 0,
        val durationMs: Long? = null
    )

    /** Result of inserting a new punishment row - callers need the id + resolved expiry to build the branded disconnect message. */
    data class InsertedPunishment(val id: Int, val expiresAt: Long?)

    data class PunishmentRecord(
        val id: Int,
        val type: String,
        val reason: String?,
        val punisherName: String,
        val createdAt: Long,
        val expiresAt: Long?,
        val active: Boolean
    )

    fun start() {
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS punishments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                target_uuid TEXT NOT NULL,
                target_name TEXT NOT NULL,
                punisher_uuid TEXT,
                punisher_name TEXT NOT NULL,
                type TEXT NOT NULL,
                reason TEXT,
                duration_ms INTEGER,
                created_at INTEGER NOT NULL,
                expires_at INTEGER,
                active INTEGER DEFAULT 1
            )
        """.trimIndent())

        // Migrations: revoke audit trail for unban/unmute/unwarn (added for /punish)
        try { plugin.databaseManager.execute("ALTER TABLE punishments ADD COLUMN revoked_by TEXT") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE punishments ADD COLUMN revoked_by_uuid TEXT") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE punishments ADD COLUMN revoked_reason TEXT") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE punishments ADD COLUMN revoked_at INTEGER") } catch (_: Exception) {}

        plugin.server.pluginManager.registerEvents(this, plugin)

        // Periodic expiry check every 60 seconds (1200 ticks)
        plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable { checkExpired() }, 1200L, 1200L)

        plugin.logger.info("[Punishment] Punishment manager started.")
    }

    // ── Bans ────────────────────────────────────────────────

    fun ban(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null): InsertedPunishment {
        return insert(targetUuid, targetName, punisherName, punisherUuid, "BAN", reason, null)
    }

    fun tempban(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null, durationMs: Long): InsertedPunishment {
        return insert(targetUuid, targetName, punisherName, punisherUuid, "TEMPBAN", reason, durationMs)
    }

    fun unban(targetUuid: UUID, revokerName: String? = null, revokerUuid: UUID? = null, revokeReason: String? = null) {
        plugin.databaseManager.execute(
            "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE target_uuid = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1",
            revokerName, revokerUuid?.toString(), revokeReason, System.currentTimeMillis(), targetUuid.toString()
        )
    }

    fun isBanned(targetUuid: UUID): ActivePunishment? {
        val now = System.currentTimeMillis()
        return plugin.databaseManager.queryFirst(
            "SELECT id, reason, expires_at, punisher_name, duration_ms FROM punishments WHERE target_uuid = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1 ORDER BY created_at DESC LIMIT 1",
            targetUuid.toString()
        ) { rs ->
            val expiresAt = rs.getLong("expires_at").takeIf { !rs.wasNull() }
            // If it's a temp ban and expired, it's not active
            if (expiresAt != null && expiresAt <= now) return@queryFirst null
            ActivePunishment(
                id = rs.getInt("id"),
                reason = rs.getString("reason"),
                expiresAt = expiresAt,
                punisherName = rs.getString("punisher_name"),
                durationMs = rs.getLong("duration_ms").takeIf { !rs.wasNull() }
            )
        }
    }

    // ── Mutes ───────────────────────────────────────────────

    fun mute(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null) {
        insert(targetUuid, targetName, punisherName, punisherUuid, "MUTE", reason, null)
    }

    fun tempmute(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null, durationMs: Long) {
        insert(targetUuid, targetName, punisherName, punisherUuid, "TEMPMUTE", reason, durationMs)
    }

    fun unmute(targetUuid: UUID, revokerName: String? = null, revokerUuid: UUID? = null, revokeReason: String? = null) {
        plugin.databaseManager.execute(
            "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE target_uuid = ? AND type IN ('MUTE', 'TEMPMUTE') AND active = 1",
            revokerName, revokerUuid?.toString(), revokeReason, System.currentTimeMillis(), targetUuid.toString()
        )
    }

    fun isMuted(targetUuid: UUID): ActivePunishment? {
        val now = System.currentTimeMillis()
        return plugin.databaseManager.queryFirst(
            "SELECT reason, expires_at, punisher_name FROM punishments WHERE target_uuid = ? AND type IN ('MUTE', 'TEMPMUTE') AND active = 1 ORDER BY created_at DESC LIMIT 1",
            targetUuid.toString()
        ) { rs ->
            val expiresAt = rs.getLong("expires_at").takeIf { !rs.wasNull() }
            if (expiresAt != null && expiresAt <= now) return@queryFirst null
            ActivePunishment(
                reason = rs.getString("reason"),
                expiresAt = expiresAt,
                punisherName = rs.getString("punisher_name")
            )
        }
    }

    // ── Warns ───────────────────────────────────────────────

    fun warn(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null) {
        insert(targetUuid, targetName, punisherName, punisherUuid, "WARN", reason, null)
    }

    fun getWarnings(targetUuid: UUID): List<PunishmentRecord> {
        return plugin.databaseManager.query(
            "SELECT id, type, reason, punisher_name, created_at, expires_at, active FROM punishments WHERE target_uuid = ? AND type = 'WARN' ORDER BY created_at DESC",
            targetUuid.toString()
        ) { rs -> mapRecord(rs) }
    }

    fun getActiveWarnings(targetUuid: UUID): List<PunishmentRecord> = getWarnings(targetUuid).filter { it.active }

    /** Remove the most recent active warning, or a specific one by [warnId]. Returns true if a row was updated. */
    fun unwarn(targetUuid: UUID, warnId: Int? = null, revokerName: String? = null, revokerUuid: UUID? = null, revokeReason: String? = null): Boolean {
        val now = System.currentTimeMillis()
        return if (warnId != null) {
            plugin.databaseManager.executeUpdate(
                "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE id = ? AND target_uuid = ? AND type = 'WARN' AND active = 1",
                revokerName, revokerUuid?.toString(), revokeReason, now, warnId, targetUuid.toString()
            ) > 0
        } else {
            val record = plugin.databaseManager.queryFirst(
                "SELECT id FROM punishments WHERE target_uuid = ? AND type = 'WARN' AND active = 1 ORDER BY created_at DESC LIMIT 1",
                targetUuid.toString()
            ) { rs -> rs.getInt("id") } ?: return false
            plugin.databaseManager.execute(
                "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE id = ?",
                revokerName, revokerUuid?.toString(), revokeReason, now, record
            )
            true
        }
    }

    // ── Kicks ───────────────────────────────────────────────

    fun kick(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null) {
        insert(targetUuid, targetName, punisherName, punisherUuid, "KICK", reason, null)
    }

    // ── History ─────────────────────────────────────────────

    fun getHistory(targetUuid: UUID): List<PunishmentRecord> {
        return plugin.databaseManager.query(
            "SELECT id, type, reason, punisher_name, created_at, expires_at, active FROM punishments WHERE target_uuid = ? ORDER BY created_at DESC",
            targetUuid.toString()
        ) { rs -> mapRecord(rs) }
    }

    // ── Expiry ──────────────────────────────────────────────

    fun checkExpired() {
        val now = System.currentTimeMillis()
        plugin.databaseManager.execute(
            "UPDATE punishments SET active = 0 WHERE active = 1 AND expires_at IS NOT NULL AND expires_at <= ?",
            now
        )
    }

    // ── Events ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        // Flip any punishment that has naturally expired before checking - a tempban
        // that lapsed since the last periodic sweep should let the player back in.
        checkExpired()

        val ban = isBanned(event.uniqueId) ?: return
        val message = buildBanMessage(ban.id, event.uniqueId, ban.reason, ban.punisherName, ban.durationMs, ban.expiresAt)
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, message)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val mute = isMuted(event.player.uniqueId) ?: return
        event.isCancelled = true

        val msg = Component.text()
            .append(Component.text("You are muted!", NamedTextColor.RED))

        if (mute.reason != null) {
            msg.append(Component.text(" Reason: ", NamedTextColor.GRAY))
                .append(Component.text(mute.reason, NamedTextColor.WHITE))
        }

        if (mute.expiresAt != null) {
            val remaining = mute.expiresAt - System.currentTimeMillis()
            msg.append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(formatDuration(remaining), NamedTextColor.WHITE))
                .append(Component.text(" remaining)", NamedTextColor.GRAY))
        }

        plugin.commsManager.send(event.player, msg.build(), CommunicationsManager.Category.ADMIN)
    }

    // ── Helpers ──────────────────────────────────────────────

    private fun insert(
        targetUuid: UUID,
        targetName: String,
        punisherName: String,
        punisherUuid: UUID?,
        type: String,
        reason: String?,
        durationMs: Long?
    ): InsertedPunishment {
        val now = System.currentTimeMillis()
        val expiresAt = if (durationMs != null) now + durationMs else null

        plugin.databaseManager.execute(
            "INSERT INTO punishments (target_uuid, target_name, punisher_uuid, punisher_name, type, reason, duration_ms, created_at, expires_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)",
            targetUuid.toString(),
            targetName,
            punisherUuid?.toString(),
            punisherName,
            type,
            reason,
            durationMs,
            now,
            expiresAt
        )

        val id = plugin.databaseManager.queryFirst("SELECT last_insert_rowid() AS id") { rs -> rs.getInt("id") } ?: -1
        return InsertedPunishment(id, expiresAt)
    }

    /**
     * Central formatter for permanent/temporary ban disconnect + rejoin-denial screens.
     * Used both by /punish (and /ban, /tempban) at the moment of the kick, and by
     * [onPreLogin] when a banned player tries to reconnect - same lines, same
     * placeholders, so the two paths can never drift apart.
     *
     * Falls back to a safe, hardcoded message if `punishments.ban-message` /
     * `punishments.tempban-message` is missing or fails to format - a banned
     * player must never slip through because of a config typo.
     */
    fun buildBanMessage(
        punishmentId: Int,
        targetUuid: UUID,
        reason: String?,
        punisherName: String,
        durationMs: Long?,
        expiresAt: Long?
    ): Component {
        val configKey = if (expiresAt == null) "punishments.ban-message" else "punishments.tempban-message"
        val lines = try {
            plugin.config.getStringList(configKey)
        } catch (e: Exception) {
            plugin.logger.warning("[Punishment] Failed to read $configKey from config.yml: ${e.message}")
            emptyList()
        }

        if (lines.isEmpty()) {
            plugin.logger.warning("[Punishment] $configKey is missing or empty in config.yml - using fallback ban message.")
            return fallbackBanMessage(reason)
        }

        return try {
            val now = System.currentTimeMillis()
            val staff = if (punisherName.equals("CONSOLE", ignoreCase = true)) "Console" else punisherName
            val replacements = listOf(
                "%player%" to (Bukkit.getOfflinePlayer(targetUuid).name ?: "Unknown"),
                "%reason%" to (reason?.takeIf { it.isNotBlank() } ?: "No reason specified"),
                "%staff%" to staff,
                "%duration%" to (durationMs?.let { formatDuration(it) } ?: "Permanent"),
                "%remaining%" to (expiresAt?.let { formatDuration(it - now) } ?: ""),
                "%expires%" to (expiresAt?.let { formatTimestamp(it, plugin.timezoneManager.zoneFor(targetUuid)) } ?: ""),
                "%punishment_id%" to punishmentId.toString()
            )

            val builder = Component.text()
            lines.forEachIndexed { index, rawLine ->
                var line = rawLine
                for ((key, value) in replacements) line = line.replace(key, value)
                if (index > 0) builder.append(Component.newline())
                builder.append(plugin.commsManager.parseLegacy(line))
            }
            builder.build()
        } catch (e: Exception) {
            plugin.logger.warning("[Punishment] Failed to format $configKey: ${e.message}")
            fallbackBanMessage(reason)
        }
    }

    private fun fallbackBanMessage(reason: String?): Component {
        return Component.text()
            .append(Component.text("You are banned from JoshyMC.", NamedTextColor.RED).decoration(TextDecoration.BOLD, true))
            .append(Component.newline())
            .append(Component.text("Reason: ${reason?.takeIf { it.isNotBlank() } ?: "No reason specified"}", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("Appeal: discord.gg/joshymc", NamedTextColor.GRAY))
            .build()
    }

    private fun formatTimestamp(epochMs: Long, zone: ZoneId): String {
        return Instant.ofEpochMilli(epochMs).atZone(zone).format(TIMESTAMP_FORMAT)
    }

    private fun mapRecord(rs: java.sql.ResultSet): PunishmentRecord {
        return PunishmentRecord(
            id = rs.getInt("id"),
            type = rs.getString("type"),
            reason = rs.getString("reason"),
            punisherName = rs.getString("punisher_name"),
            createdAt = rs.getLong("created_at"),
            expiresAt = rs.getLong("expires_at").takeIf { !rs.wasNull() },
            active = rs.getInt("active") == 1
        )
    }

    companion object {

        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")

        /**
         * Format a duration in milliseconds to a human-readable string.
         * e.g. 90061000 -> "1d 1h 1m 1s"
         */
        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "expired"

            val seconds = ms / 1000
            val days = seconds / 86400
            val hours = (seconds % 86400) / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60

            val parts = mutableListOf<String>()
            if (days > 0) parts.add("${days}d")
            if (hours > 0) parts.add("${hours}h")
            if (minutes > 0) parts.add("${minutes}m")
            if (secs > 0 || parts.isEmpty()) parts.add("${secs}s")

            return parts.joinToString(" ")
        }

        /**
         * Parse a duration string like "1d2h30m", "7d", "1w" or "1mo" into milliseconds.
         * Supports s (seconds), m (minutes), h (hours), d (days), w (weeks), mo (months, 30d).
         * Rejects malformed/zero/negative/overflowing input by returning null.
         */
        fun parseDuration(input: String): Long? {
            val trimmed = input.trim().lowercase()
            if (trimmed.isBlank()) return null

            val regex = Regex("(\\d+)(mo|[smhdw])")
            val matches = regex.findAll(trimmed).toList()
            if (matches.isEmpty()) return null

            // Reject trailing/interleaved garbage - every character must belong to a matched token.
            val matchedLength = matches.sumOf { it.value.length }
            if (matchedLength != trimmed.length) return null

            val total = try {
                var sum = 0L
                for (match in matches) {
                    val value = match.groupValues[1].toLongOrNull() ?: return null
                    val unitMs = when (match.groupValues[2]) {
                        "mo" -> 2_592_000_000L
                        "w" -> 604_800_000L
                        "d" -> 86_400_000L
                        "h" -> 3_600_000L
                        "m" -> 60_000L
                        "s" -> 1_000L
                        else -> return null
                    }
                    sum = Math.addExact(sum, Math.multiplyExact(value, unitMs))
                }
                sum
            } catch (_: ArithmeticException) {
                return null
            }

            if (total <= 0L) return null

            val maxDuration = 100L * 365 * 86_400_000L // ~100 years - reject absurd overflow values
            if (total > maxDuration) return null

            return total
        }
    }
}
