package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import com.liam.joshymc.gamemode.ServerGamemode
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Player
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
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
        val durationMs: Long? = null,
        /** Gamemode id the punishment is limited to; null = every gamemode (all older rows). */
        val scope: String? = null,
        /** /punish rule offense number (issue #1298); null for punishments issued any other way. */
        val offense: Int? = null
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

    /**
     * One moderation action, handed to Discord after it is persisted. [action] is the stored
     * type (WARN, MUTE, TEMPMUTE, KICK, BAN, TEMPBAN) or UNWARN / UNMUTE / UNBAN for a removal,
     * in which case [reversed] holds the punishments it lifted.
     */
    data class PunishmentLog(
        val action: String,
        val targetUuid: UUID,
        val targetName: String,
        val staffName: String,
        val reason: String?,
        val timestamp: Long,
        val punishmentId: Int? = null,
        val durationMs: Long? = null,
        val expiresAt: Long? = null,
        val previousCount: Int? = null,
        val reversed: List<PunishmentRecord> = emptyList()
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
        // Issue #1298: rule-based /punish punishments can be limited to one gamemode (NULL = every
        // gamemode, which is what every older row means) and remember their offense number.
        try { plugin.databaseManager.execute("ALTER TABLE punishments ADD COLUMN scope TEXT") } catch (_: Exception) {}
        try { plugin.databaseManager.execute("ALTER TABLE punishments ADD COLUMN offense INTEGER") } catch (_: Exception) {}
        // Issue #1161: punishments.discord_thread_id is no longer added; the per-player thread
        // lives in punishment_discord_threads. Older databases keep the unused column (harmless).

        // Issue #1069: one Discord forum thread per punished player, keyed by UUID so name changes reuse it.
        plugin.databaseManager.createTable("""
            CREATE TABLE IF NOT EXISTS punishment_discord_threads (
                player_uuid TEXT PRIMARY KEY,
                thread_id TEXT NOT NULL,
                player_name TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
        """.trimIndent())

        // Issue #1157: durable queue of logs waiting to be posted to those threads.
        plugin.discordManager.punishmentLogs.ensureSchema()

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
        // One transaction, so the revocation and its Discord log are stored together (issue #1157).
        plugin.databaseManager.transaction {
            val revoked = plugin.databaseManager.query(
                "SELECT id, type, reason, punisher_name, created_at, expires_at, active FROM punishments WHERE target_uuid = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1",
                targetUuid.toString()
            ) { rs -> mapRecord(rs) }

            plugin.databaseManager.execute(
                "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE target_uuid = ? AND type IN ('BAN', 'TEMPBAN') AND active = 1",
                revokerName, revokerUuid?.toString(), revokeReason, System.currentTimeMillis(), targetUuid.toString()
            )

            logRevocation("UNBAN", targetUuid, revoked, revokerName, revokeReason)
        }
    }

    /** The player's longest active ban in any gamemode (permanent first). */
    fun isBanned(targetUuid: UUID): ActivePunishment? = activeOf(targetUuid, BAN_TYPES, null)

    /** The longest active ban that applies in [gamemode]: unscoped bans plus ones limited to it. */
    fun isBannedIn(targetUuid: UUID, gamemode: ServerGamemode): ActivePunishment? = activeOf(targetUuid, BAN_TYPES, gamemode)

    /**
     * The longest still-running punishment of [types] (permanent first, then latest expiry), so a
     * shorter punishment issued later never hides a longer one. [gamemode] limits it to rows that
     * apply there (unscoped or scoped to it); null means any scope.
     */
    private fun activeOf(targetUuid: UUID, types: String, gamemode: ServerGamemode?): ActivePunishment? {
        val now = System.currentTimeMillis()
        val scopeFilter = if (gamemode == null) "" else " AND (scope IS NULL OR scope = ?)"
        val params: Array<Any?> = if (gamemode == null) arrayOf(targetUuid.toString()) else arrayOf(targetUuid.toString(), gamemode.id)
        return plugin.databaseManager.queryFirst(
            "SELECT id, reason, expires_at, punisher_name, duration_ms, scope, offense FROM punishments WHERE target_uuid = ? AND type IN ($types) AND active = 1$scopeFilter ORDER BY (expires_at IS NULL) DESC, expires_at DESC, created_at DESC LIMIT 1",
            *params
        ) { rs ->
            val expiresAt = rs.getLong("expires_at").takeIf { !rs.wasNull() }
            // If it's temporary and expired, it's not active
            if (expiresAt != null && expiresAt <= now) return@queryFirst null
            ActivePunishment(
                id = rs.getInt("id"),
                reason = rs.getString("reason"),
                expiresAt = expiresAt,
                punisherName = rs.getString("punisher_name"),
                durationMs = rs.getLong("duration_ms").takeIf { !rs.wasNull() },
                scope = rs.getString("scope"),
                offense = rs.getInt("offense").takeIf { !rs.wasNull() }
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

    /**
     * Automatic temp-mute (issue #1268): a normal TEMPMUTE row by [AUTOMOD_NAME], inserted only
     * when the player has no active mute, checked in the same transaction so a permanent or
     * longer mute is never stacked on or shortened. Returns null when nothing was inserted.
     */
    fun autoTempmute(targetUuid: UUID, targetName: String, reason: String, durationMs: Long): InsertedPunishment? {
        var inserted: InsertedPunishment? = null
        plugin.databaseManager.transaction {
            if (isMuted(targetUuid) != null) return@transaction
            inserted = insert(targetUuid, targetName, AUTOMOD_NAME, null, "TEMPMUTE", reason, durationMs)
                .takeIf { it.id != -1 }
        }
        return inserted
    }

    fun unmute(targetUuid: UUID, revokerName: String? = null, revokerUuid: UUID? = null, revokeReason: String? = null) {
        // One transaction, so the revocation and its Discord log are stored together (issue #1157).
        plugin.databaseManager.transaction {
            val revoked = plugin.databaseManager.query(
                "SELECT id, type, reason, punisher_name, created_at, expires_at, active FROM punishments WHERE target_uuid = ? AND type IN ('MUTE', 'TEMPMUTE') AND active = 1",
                targetUuid.toString()
            ) { rs -> mapRecord(rs) }

            plugin.databaseManager.execute(
                "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE target_uuid = ? AND type IN ('MUTE', 'TEMPMUTE') AND active = 1",
                revokerName, revokerUuid?.toString(), revokeReason, System.currentTimeMillis(), targetUuid.toString()
            )

            logRevocation("UNMUTE", targetUuid, revoked, revokerName, revokeReason)
        }
    }

    /** The player's longest active mute in any gamemode. */
    fun isMuted(targetUuid: UUID): ActivePunishment? = activeOf(targetUuid, MUTE_TYPES, null)

    /** The longest active mute that applies in [gamemode]. */
    fun isMutedIn(targetUuid: UUID, gamemode: ServerGamemode): ActivePunishment? = activeOf(targetUuid, MUTE_TYPES, gamemode)

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
        var updated = false
        // One transaction, so the revocation and its Discord log are stored together (issue #1157).
        plugin.databaseManager.transaction {
            if (warnId != null) {
                val record = plugin.databaseManager.queryFirst(
                    "SELECT id, type, reason, punisher_name, created_at, expires_at, active FROM punishments WHERE id = ? AND target_uuid = ? AND type = 'WARN' AND active = 1",
                    warnId, targetUuid.toString()
                ) { rs -> mapRecord(rs) }
                updated = plugin.databaseManager.executeUpdate(
                    "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE id = ? AND target_uuid = ? AND type = 'WARN' AND active = 1",
                    revokerName, revokerUuid?.toString(), revokeReason, now, warnId, targetUuid.toString()
                ) > 0
                if (updated && record != null) logRevocation("UNWARN", targetUuid, listOf(record), revokerName, revokeReason)
            } else {
                val record = plugin.databaseManager.queryFirst(
                    "SELECT id, type, reason, punisher_name, created_at, expires_at, active FROM punishments WHERE target_uuid = ? AND type = 'WARN' AND active = 1 ORDER BY created_at DESC LIMIT 1",
                    targetUuid.toString()
                ) { rs -> mapRecord(rs) }
                if (record != null) {
                    plugin.databaseManager.execute(
                        "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE id = ?",
                        revokerName, revokerUuid?.toString(), revokeReason, now, record.id
                    )
                    logRevocation("UNWARN", targetUuid, listOf(record), revokerName, revokeReason)
                    updated = true
                }
            }
        }
        return updated
    }

    // ── Kicks ───────────────────────────────────────────────

    fun kick(targetUuid: UUID, targetName: String, punisherName: String, punisherUuid: UUID? = null, reason: String? = null) {
        insert(targetUuid, targetName, punisherName, punisherUuid, "KICK", reason, null)
    }

    // ── Rule-based punishments (issue #1298) ────────────────

    /**
     * Inserts one /punish rule punishment row ([type] WARN/TEMPMUTE/KICK/TEMPBAN/BAN) limited to
     * [scope] (a gamemode, or null for every gamemode). [expiresAt] overrides `now + durationMs`
     * (ban extensions). Joins the caller's transaction and throws rather than returning -1, so the
     * caller's incident row rolls back with it.
     */
    fun insertRulePunishment(
        targetUuid: UUID,
        targetName: String,
        punisherName: String,
        punisherUuid: UUID?,
        type: String,
        reason: String,
        durationMs: Long?,
        scope: ServerGamemode?,
        offense: Int,
        expiresAt: Long? = null
    ): InsertedPunishment {
        val inserted = insert(targetUuid, targetName, punisherName, punisherUuid, type, reason, durationMs, scope?.id, offense, expiresAt)
        check(inserted.id != -1) { "punishment row was not inserted" }
        return inserted
    }

    /**
     * Lifts one punishment row by id (an overturned /punish incident), recording who and why and
     * logging the removal to Discord. False when it was already inactive.
     */
    fun revokeById(punishmentId: Int, revokerName: String, revokerUuid: UUID?, revokeReason: String): Boolean {
        var revoked = false
        plugin.databaseManager.transaction {
            val row = plugin.databaseManager.queryFirst(
                "SELECT id, type, reason, punisher_name, created_at, expires_at, active, target_uuid FROM punishments WHERE id = ? AND active = 1",
                punishmentId
            ) { rs -> mapRecord(rs) to UUID.fromString(rs.getString("target_uuid")) } ?: return@transaction
            revoked = plugin.databaseManager.executeUpdate(
                "UPDATE punishments SET active = 0, revoked_by = ?, revoked_by_uuid = ?, revoked_reason = ?, revoked_at = ? WHERE id = ? AND active = 1",
                revokerName, revokerUuid?.toString(), revokeReason, System.currentTimeMillis(), punishmentId
            ) == 1
            val action = when (row.first.type) {
                "WARN" -> "UNWARN"
                "MUTE", "TEMPMUTE" -> "UNMUTE"
                "BAN", "TEMPBAN" -> "UNBAN"
                else -> null
            }
            if (revoked && action != null) logRevocation(action, row.second, listOf(row.first), revokerName, revokeReason)
        }
        return revoked
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

        // A ban limited to another gamemode (issue #1298) doesn't keep the player off this server.
        val ban = isBannedIn(event.uniqueId, plugin.gamemodeLockManager.serverGamemode) ?: return
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banMessageFor(event.uniqueId, ban))
    }

    /**
     * On a server whose worlds run different gamemodes, a gamemode-limited ban can't be checked
     * at login (the world isn't known yet), so it is checked once the player is in a world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoinScopedBan(event: PlayerJoinEvent) = enforceScopedBan(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldScopedBan(event: PlayerChangedWorldEvent) = enforceScopedBan(event.player)

    private fun enforceScopedBan(player: Player) {
        val locks = plugin.gamemodeLockManager
        if (!locks.hasWorldOverrides) return
        val gamemode = locks.gamemodeOf(player.world)
        if (gamemode == locks.serverGamemode) return
        val ban = isBannedIn(player.uniqueId, gamemode) ?: return
        val message = banMessageFor(player.uniqueId, ban)
        plugin.server.scheduler.runTask(plugin, Runnable { if (player.isOnline) player.kick(message) })
    }

    fun banMessageFor(targetUuid: UUID, ban: ActivePunishment): Component =
        buildBanMessage(ban.id, targetUuid, ban.reason, ban.punisherName, ban.durationMs, ban.expiresAt, ban.offense, ban.scope)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        // A mute limited to one gamemode (issue #1298) only silences the player there.
        val mute = isMutedIn(event.player.uniqueId, plugin.gamemodeLockManager.gamemodeOf(event.player.world)) ?: return
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
        durationMs: Long?,
        scope: String? = null,
        offense: Int? = null,
        expiresAtOverride: Long? = null
    ): InsertedPunishment {
        val now = System.currentTimeMillis()
        val expiresAt = expiresAtOverride ?: if (durationMs != null) now + durationMs else null
        var id = -1

        // One transaction, so the punishment and its Discord log are stored together (issue #1157).
        plugin.databaseManager.transaction {
            id = plugin.databaseManager.insertReturningId(
                "INSERT INTO punishments (target_uuid, target_name, punisher_uuid, punisher_name, type, reason, duration_ms, created_at, expires_at, active, scope, offense) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                targetUuid.toString(),
                targetName,
                punisherUuid?.toString(),
                punisherName,
                type,
                reason,
                durationMs,
                now,
                expiresAt,
                scope,
                offense
            ) ?: -1

            if (id != -1) {
                val previousCount = plugin.databaseManager.queryFirst(
                    "SELECT COUNT(*) AS n FROM punishments WHERE target_uuid = ? AND id < ?",
                    targetUuid.toString(), id
                ) { rs -> rs.getInt("n") }
                logToDiscord(PunishmentLog(
                    action = type,
                    targetUuid = targetUuid,
                    targetName = targetName,
                    staffName = punisherName,
                    reason = reason,
                    timestamp = now,
                    punishmentId = id,
                    durationMs = durationMs,
                    expiresAt = expiresAt,
                    previousCount = previousCount
                ))
            }
        }

        return InsertedPunishment(id, expiresAt)
    }

    /** Unban / unmute / unwarn: one Discord entry per action, naming the punishments it lifted. Nothing lifted, nothing logged. */
    private fun logRevocation(action: String, targetUuid: UUID, revoked: List<PunishmentRecord>, revokerName: String?, revokeReason: String?) {
        if (revoked.isEmpty()) return
        val targetName = Bukkit.getOfflinePlayer(targetUuid).name
            ?: plugin.databaseManager.queryFirst(
                "SELECT target_name FROM punishments WHERE id = ?", revoked.first().id
            ) { rs -> rs.getString("target_name") }
            ?: targetUuid.toString()
        logToDiscord(PunishmentLog(
            action = action,
            targetUuid = targetUuid,
            targetName = targetName,
            staffName = revokerName?.takeIf { it.isNotBlank() } ?: "CONSOLE",
            reason = revokeReason,
            timestamp = System.currentTimeMillis(),
            reversed = revoked
        ))
    }

    /**
     * Discord logging must never undo or block a punishment. Runs inside the punishment's
     * transaction; a failed outbox insert only fails that one statement, so the punishment
     * still commits and the failure is logged.
     */
    private fun logToDiscord(entry: PunishmentLog) {
        try {
            plugin.discordManager.logPunishment(entry)
        } catch (e: Exception) {
            plugin.logger.severe("[Discord] Could not queue ${entry.action} for ${entry.targetName} (${entry.targetUuid}) for Discord logging: ${e.message}")
        }
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
        expiresAt: Long?,
        offense: Int? = null,
        scope: String? = null
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
                "%punishment_id%" to punishmentId.toString(),
                "%offense%" to (offense?.toString() ?: ""),
                "%scope%" to (if (offense == null) "" else ServerGamemode.fromId(scope)?.displayName ?: "All Gamemodes")
            )

            val builder = Component.text()
            var first = true
            for (rawLine in lines) {
                // %offense% / %scope% only exist for /punish rule punishments (issue #1298);
                // their lines are left out of every other ban screen.
                if (offense == null && OPTIONAL_PLACEHOLDERS.any { rawLine.contains(it) }) continue
                var line = rawLine
                for ((key, value) in replacements) line = line.replace(key, value)
                if (!first) builder.append(Component.newline())
                first = false
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

        /** Punisher name on automatic punishments, shown in /history and the Discord log. */
        const val AUTOMOD_NAME = "AutoMod"

        private const val BAN_TYPES = "'BAN', 'TEMPBAN'"
        private const val MUTE_TYPES = "'MUTE', 'TEMPMUTE'"

        /** Ban-screen placeholders only rule punishments fill in. */
        private val OPTIONAL_PLACEHOLDERS = listOf("%offense%", "%scope%")

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
