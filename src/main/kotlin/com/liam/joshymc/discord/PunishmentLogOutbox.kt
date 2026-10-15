package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.PunishmentManager.PunishmentLog
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.forums.ForumTag
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.PermissionException
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.random.Random

/**
 * Durable delivery of punishment logs to each player's punishment forum post (issue #1157).
 *
 * A log is written to `punishment_discord_outbox` inside the same transaction as the
 * punishment (or revocation) it describes, then one background worker posts it once the bot
 * is ready. Rows are only marked DELIVERED after Discord accepted the message; any failure
 * just schedules a retry, so a Discord outage, a restart or a locked post delays a log
 * instead of losing it.
 *
 * Ordering and concurrency: there is one worker thread per bot connection, and it only ever
 * picks the oldest pending row of each player, so one player's logs stay in order while a
 * player whose post is stuck never holds up anyone else.
 *
 * Duplicates: Discord message creation and the DELIVERED update can't be one transaction.
 * `in_flight_since` is set right before a message-creating request and cleared once its
 * outcome is known. A retry of a row that is still in flight (timeout, crash, shutdown mid
 * request) first looks for the message in the post's latest [RECONCILE_HISTORY] messages
 * (or, for a brand new post, in the forum for a post the bot made for that UUID) and marks
 * it delivered instead of posting again. Messages also carry a stable nonce, so Discord
 * itself drops a quick resend. A duplicate is still possible if the earlier copy is older
 * than that history window or the lookup itself fails and the resend goes through.
 */
class PunishmentLogOutbox(
    private val plugin: Joshymc,
    private val forumChannelId: () -> String,
    private val buildEmbed: (PunishmentLog) -> MessageEmbed,
    private val describe: (PunishmentLog) -> String
) {

    /** TRANSIENT failures back off exponentially; BLOCKED ones (permissions, locked post) wait for staff. */
    private enum class FailureKind { TRANSIENT, BLOCKED }

    private class DeliveryFailure(val kind: FailureKind, message: String, val ambiguous: Boolean = false) : Exception(message)

    private class Row(
        val id: Long,
        val playerUuid: String,
        val payload: String,
        val attempts: Int,
        val inFlightSince: Long?
    )

    private class Delivered(val threadId: String, val messageId: String)

    private val db get() = plugin.databaseManager

    private val workerLock = Any()
    @Volatile private var worker: Thread? = null
    @Volatile private var running = false
    private val wakeup = Semaphore(0)
    /** Set when the gateway resumes or reconnects, so transient backoffs are cut short. */
    @Volatile private var retryTransientNow = false

    /** Last forum problem logged, so a misconfigured forum is reported once per change. */
    private var forumProblem: String? = null
    private var forumBoundLogged = false
    private val lastLogged = ConcurrentHashMap<String, Long>()

    fun ensureSchema() {
        db.createTable("""
            CREATE TABLE IF NOT EXISTS punishment_discord_outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                event_key TEXT NOT NULL UNIQUE,
                player_uuid TEXT NOT NULL,
                action TEXT NOT NULL,
                punishment_id INTEGER,
                payload TEXT NOT NULL,
                payload_version INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'PENDING',
                attempts INTEGER NOT NULL DEFAULT 0,
                next_attempt_at INTEGER NOT NULL DEFAULT 0,
                error_kind TEXT,
                last_error TEXT,
                in_flight_since INTEGER,
                thread_id TEXT,
                message_id TEXT,
                event_at INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                delivered_at INTEGER
            )
        """.trimIndent())
        db.createTable("CREATE INDEX IF NOT EXISTS idx_punishment_discord_outbox_pending ON punishment_discord_outbox (status, player_uuid, id)")
    }

    /**
     * Records the delivery intent. Runs inside the caller's punishment transaction, so the log
     * exists exactly when the punishment does. The same event is never queued twice.
     */
    fun enqueue(entry: PunishmentLog) {
        val now = System.currentTimeMillis()
        val inserted = db.executeUpdate(
            "INSERT OR IGNORE INTO punishment_discord_outbox (event_key, player_uuid, action, punishment_id, payload, payload_version, status, next_attempt_at, event_at, created_at) VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)",
            PunishmentLogCodec.eventKey(entry), entry.targetUuid.toString(), entry.action, entry.punishmentId,
            PunishmentLogCodec.encode(entry), PunishmentLogCodec.VERSION, entry.timestamp, now
        ) > 0
        if (!inserted) return
        if (running) {
            wakeup.release()
        } else {
            logThrottled("not-connected", Level.WARNING, "[Discord] Bot is not connected - punishment logs are queued and will be posted once it connects.", 60 * 60_000L)
        }
    }

    /** Starts the delivery worker for a ready bot connection. */
    fun start(jda: JDA) {
        synchronized(workerLock) {
            if (worker != null) stopLocked()
            running = true
            forumProblem = null
            forumBoundLogged = false
            // Logs restored from the database go out now instead of waiting out an old backoff.
            retryTransientNow = true
            // Published before it starts, so its first active() check already sees itself.
            val thread = Thread({ workerLoop(jda) }, "JoshyMC-PunishmentLog").apply { isDaemon = true }
            worker = thread
            thread.start()
        }
    }

    /** Stops the worker. Rows it didn't finish stay pending (in flight ones get reconciled next time). */
    fun stop() {
        synchronized(workerLock) { stopLocked() }
    }

    private fun stopLocked() {
        val thread = worker ?: return
        running = false
        worker = null
        wakeup.release()
        try {
            thread.join(5_000L)
            if (thread.isAlive) {
                plugin.logger.warning("[Discord] Punishment log delivery still busy after 5s - interrupting it; unsent logs stay queued.")
                thread.interrupt()
                thread.join(2_000L)
            }
        } catch (_: InterruptedException) {
            thread.interrupt()
            Thread.currentThread().interrupt()
        }
    }

    /** Gateway resumed or reconnected: Discord is reachable again, so retry transient failures now. */
    fun onReconnected() {
        retryTransientNow = true
        wakeup.release()
    }

    // ── Worker ─────────────────────────────────────────────

    /** False for a worker that was stopped (or replaced), even if it outlived its join. */
    private fun active(): Boolean = running && worker === Thread.currentThread()

    private fun workerLoop(jda: JDA) {
        try {
            pruneDelivered()
            val pending = db.queryFirst("SELECT COUNT(*) AS n FROM punishment_discord_outbox WHERE status = 'PENDING'") { it.getInt("n") } ?: 0
            if (pending > 0) plugin.logger.info("[Discord] $pending punishment log(s) waiting to be posted to Discord.")
        } catch (e: Exception) {
            plugin.logger.warning("[Discord] Could not read the punishment log queue: ${e.message}")
        }

        while (active()) {
            val waitMs = try {
                runCycle(jda)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                logThrottled("cycle", Level.WARNING, "[Discord] Punishment log delivery error: ${e.message}")
                ERROR_WAIT_MS
            }
            if (!active()) return
            try {
                wakeup.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
                wakeup.drainPermits()
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Posts what is due, in bounded batches. Returns how long to sleep before the next look. */
    private fun runCycle(jda: JDA): Long {
        if (retryTransientNow) {
            retryTransientNow = false
            db.execute("UPDATE punishment_discord_outbox SET next_attempt_at = 0 WHERE status = 'PENDING' AND error_kind = 'TRANSIENT'")
        }

        val forum = usableForum(jda) ?: return FORUM_RECHECK_MS

        var processed = 0
        while (active() && processed < MAX_PER_CYCLE) {
            val due = dueHeads(System.currentTimeMillis())
            if (due.isEmpty()) break
            for (row in due) {
                if (!active()) return 0L
                deliver(jda, forum, row)
                processed++
                // Spaces out a large backlog; JDA still enforces Discord's own rate limits on top.
                Thread.sleep(SEND_SPACING_MS)
            }
        }
        if (processed >= MAX_PER_CYCLE) return SEND_SPACING_MS

        val nextDue = db.queryFirst(
            "SELECT MIN(o.next_attempt_at) AS next FROM punishment_discord_outbox o WHERE o.status = 'PENDING' AND $HEAD_CONDITION"
        ) { rs -> rs.getLong("next").takeIf { !rs.wasNull() } } ?: return IDLE_WAIT_MS
        return (nextDue - System.currentTimeMillis()).coerceIn(MIN_WAIT_MS, IDLE_WAIT_MS)
    }

    /** The oldest pending row of each player that is due now. */
    private fun dueHeads(now: Long): List<Row> = db.query(
        "SELECT o.id, o.player_uuid, o.payload, o.attempts, o.in_flight_since FROM punishment_discord_outbox o " +
            "WHERE o.status = 'PENDING' AND o.next_attempt_at <= ? AND $HEAD_CONDITION ORDER BY o.id LIMIT ?",
        now, BATCH_SIZE
    ) { rs ->
        Row(
            id = rs.getLong("id"),
            playerUuid = rs.getString("player_uuid"),
            payload = rs.getString("payload"),
            attempts = rs.getInt("attempts"),
            inFlightSince = rs.getLong("in_flight_since").takeIf { !rs.wasNull() }
        )
    }

    private fun pruneDelivered() {
        db.execute(
            "DELETE FROM punishment_discord_outbox WHERE status = 'DELIVERED' AND delivered_at < ?",
            System.currentTimeMillis() - DELIVERED_RETENTION_MS
        )
    }

    /**
     * Checks the configured forum on every cycle (cheap: JDA keeps channels and permissions
     * cached). Problems are logged once each time they change; logs stay queued meanwhile.
     */
    private fun usableForum(jda: JDA): ForumChannel? {
        val id = forumChannelId()
        val channel = try { jda.getGuildChannelById(id) } catch (_: NumberFormatException) { null }
        val problem = when {
            id.isEmpty() -> "discord.channels.punishments-forum is not set"
            channel == null -> "punishment forum $id was not found - the bot is not in that server or lacks View Channel there"
            channel !is ForumChannel -> "punishment forum $id (#${channel.name}) is a ${channel.type} channel, not a forum"
            else -> {
                val self = channel.guild.selfMember
                val missing = REQUIRED_PERMISSIONS.filterNot { self.hasPermission(channel, it) }
                if (missing.isEmpty()) null
                else "the bot is missing ${missing.joinToString { it.getName() }} in punishment forum #${channel.name}"
            }
        }

        if (problem != null) {
            if (problem != forumProblem) {
                forumProblem = problem
                plugin.logger.warning("[Discord] Punishment logging paused: $problem. Logs stay queued and post once this is fixed.")
            }
            return null
        }

        val forum = channel as ForumChannel
        if (forumProblem != null) {
            forumProblem = null
            plugin.logger.info("[Discord] Punishment forum #${forum.name} is usable again - resuming queued logs.")
        }
        if (!forumBoundLogged) {
            forumBoundLogged = true
            plugin.logger.info("[Discord] Punishment logs bound to forum #${forum.name} (${forum.guild.name}).")
            if (!forum.guild.selfMember.hasPermission(forum, Permission.MANAGE_THREADS)) {
                plugin.logger.warning("[Discord] The bot lacks Manage Threads in #${forum.name}: logs for players whose post is locked wait until it is unlocked.")
            }
        }
        return forum
    }

    private fun deliver(jda: JDA, forum: ForumChannel, row: Row) {
        val entry = PunishmentLogCodec.decode(row.payload)
        if (entry == null) {
            // Not a Discord failure: this row can never be rebuilt, so park it instead of blocking the player's queue.
            db.execute("UPDATE punishment_discord_outbox SET status = 'FAILED', last_error = ? WHERE id = ?", "Unreadable payload", row.id)
            plugin.logger.severe("[Discord] Punishment log #${row.id} for ${row.playerUuid} could not be read and will not be posted.")
            return
        }

        try {
            val done = send(jda, forum, row, entry)
            db.execute(
                "UPDATE punishment_discord_outbox SET status = 'DELIVERED', delivered_at = ?, thread_id = ?, message_id = ?, in_flight_since = NULL, error_kind = NULL, last_error = NULL WHERE id = ?",
                System.currentTimeMillis(), done.threadId, done.messageId, row.id
            )
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            val failure = e as? DeliveryFailure ?: classify(e)
            scheduleRetry(row, entry, failure)
        }
    }

    private fun scheduleRetry(row: Row, entry: PunishmentLog, failure: DeliveryFailure) {
        val attempts = row.attempts + 1
        val delay = when (failure.kind) {
            FailureKind.BLOCKED -> BLOCKED_RETRY_MS
            FailureKind.TRANSIENT -> {
                val base = (TRANSIENT_BASE_MS shl (attempts - 1).coerceIn(0, 16)).coerceAtMost(TRANSIENT_MAX_MS)
                (base * Random.nextDouble(0.8, 1.2)).toLong()
            }
        }
        // A request that may have reached Discord keeps its in-flight marker so the retry reconciles first.
        val clearInFlight = if (failure.ambiguous) "" else ", in_flight_since = NULL"
        db.execute(
            "UPDATE punishment_discord_outbox SET attempts = ?, next_attempt_at = ?, error_kind = ?, last_error = ?$clearInFlight WHERE id = ?",
            attempts, System.currentTimeMillis() + delay, failure.kind.name, failure.message?.take(500), row.id
        )
        val key = "fail:${failure.kind}:${failure.message?.take(80)}"
        logThrottled(
            key, Level.WARNING,
            "[Discord] ${describe(entry)} is not posted to Discord yet: ${failure.message} - retrying in ${delay / 1000}s (attempt $attempts). Similar failures are not repeated for 10 minutes."
        )
    }

    /** Sorts a Discord error into wait-and-retry or needs-staff, and whether it may have been posted anyway. */
    private fun classify(e: Exception): DeliveryFailure {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is PermissionException -> return DeliveryFailure(FailureKind.BLOCKED, "missing permission ${cause.permission.getName()}")
                is ErrorResponseException -> return when {
                    cause.errorResponse in BLOCKED_ERRORS ->
                        DeliveryFailure(FailureKind.BLOCKED, "${cause.meaning} (${cause.errorCode})")
                    // 5xx: Discord may or may not have created the message.
                    cause.isServerError -> DeliveryFailure(FailureKind.TRANSIENT, "Discord error ${cause.errorCode}: ${cause.meaning}", ambiguous = true)
                    // Any other rejection means nothing was created.
                    else -> DeliveryFailure(FailureKind.TRANSIENT, "${cause.meaning} (${cause.errorCode})")
                }
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        // Timeouts, dropped connections, a bot that is reconnecting: the request may have landed.
        return DeliveryFailure(FailureKind.TRANSIENT, e.message ?: e.javaClass.simpleName, ambiguous = true)
    }

    // ── Discord ────────────────────────────────────────────

    /** Runs on the worker thread only, so blocking .complete() calls (always with a timeout) are fine. */
    private fun send(jda: JDA, forum: ForumChannel, row: Row, entry: PunishmentLog): Delivered {
        val embed = buildEmbed(entry)
        val title = "${entry.targetName} - Punishment History".take(100)
        val uuid = entry.targetUuid.toString()

        val mappedId = db.queryFirst(
            "SELECT thread_id FROM punishment_discord_threads WHERE player_uuid = ?", uuid
        ) { rs -> rs.getString("thread_id") }

        if (mappedId != null) {
            val thread = findThread(jda, forum, mappedId)
            if (thread != null) return postInThread(forum, thread, row, entry, embed, title)
            // The lookup above succeeded and the post is in neither the active nor the archived
            // list, so it was really deleted: only then may a new post replace it.
            plugin.logger.warning("[Discord] Punishment post $mappedId for ${entry.targetName} ($uuid) no longer exists in the forum - starting a new one.")
            db.execute("DELETE FROM punishment_discord_threads WHERE player_uuid = ? AND thread_id = ?", uuid, mappedId)
        } else if (row.inFlightSince != null) {
            // A crash right after creating the post but before saving the mapping: adopt that post.
            val adopted = findPostForPlayer(jda, forum, uuid)
            if (adopted != null) {
                saveMapping(uuid, adopted.id, entry.targetName)
                val start = adopted.retrieveStartMessage().timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()
                if (isSameLog(start, embed, jda)) return Delivered(adopted.id, start.id)
                return postInThread(forum, adopted, row, entry, embed, title)
            }
        }

        markInFlight(row)
        val action = forum.createForumPost(title, MessageCreateBuilder().setEmbeds(embed).build())
        val tags = forumTags(forum)
        if (tags.isNotEmpty()) action.setTags(tags)
        val post = action.timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()

        // Stored straight away so the next punishment for this player reuses the post.
        saveMapping(uuid, post.threadChannel.id, entry.targetName)
        return Delivered(post.threadChannel.id, post.message.id)
    }

    private fun postInThread(forum: ForumChannel, thread: ThreadChannel, row: Row, entry: PunishmentLog, embed: MessageEmbed, title: String): Delivered {
        val who = "${entry.targetName} (${entry.targetUuid})"
        val canManage = forum.guild.selfMember.hasPermission(thread, Permission.MANAGE_THREADS)
        if (thread.isLocked && !canManage) {
            // Known to fail, so no request is made until someone unlocks the post or grants the permission.
            logThrottled(
                "locked:${thread.id}", Level.WARNING,
                "[Discord] Punishment post ${thread.jumpUrl} for $who is locked. Unlock it or give the bot Manage Threads in #${forum.name}; its logs stay queued.",
                BLOCKED_RETRY_MS
            )
            throw DeliveryFailure(FailureKind.BLOCKED, "punishment post ${thread.id} is locked")
        }

        if (thread.isArchived) {
            try {
                thread.manager.setArchived(false).timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()
            } catch (e: Exception) {
                // Sending into an unlocked archived post reopens it anyway; a locked one is caught below.
                logThrottled("unarchive:${thread.id}", Level.WARNING, "[Discord] Could not unarchive punishment post ${thread.id} for $who: ${e.message}")
            }
        }
        if (thread.name != title) {
            try {
                thread.manager.setName(title).timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()
            } catch (e: Exception) {
                logThrottled("rename:${thread.id}", Level.WARNING, "[Discord] Could not rename punishment post ${thread.id} for $who: ${e.message}")
            }
        }

        if (row.inFlightSince != null) {
            val existing = thread.history.retrievePast(RECONCILE_HISTORY).timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()
                .firstOrNull { isSameLog(it, embed, thread.jda) }
            if (existing != null) return Delivered(thread.id, existing.id)
        }

        markInFlight(row)
        val message = try {
            thread.sendMessageEmbeds(embed).setNonce(nonce(row)).timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()
        } catch (e: ErrorResponseException) {
            if (e.errorResponse == ErrorResponse.UNKNOWN_CHANNEL) {
                // Deleted between the lookup and the send: drop the mapping so the next try starts a new post.
                db.execute("DELETE FROM punishment_discord_threads WHERE player_uuid = ? AND thread_id = ?", entry.targetUuid.toString(), thread.id)
                throw DeliveryFailure(FailureKind.TRANSIENT, "punishment post ${thread.id} was deleted")
            }
            throw e
        }

        if (thread.name != title) {
            db.execute(
                "UPDATE punishment_discord_threads SET player_name = ? WHERE player_uuid = ?",
                entry.targetName, entry.targetUuid.toString()
            )
        }
        return Delivered(thread.id, message.id)
    }

    /**
     * The mapped post, whether active (cached) or archived (looked up in the forum's archive).
     * Returns null only when the post is really gone from this forum; lookup errors propagate so
     * a Discord hiccup never leads to a duplicate post.
     */
    private fun findThread(jda: JDA, forum: ForumChannel, threadId: String): ThreadChannel? {
        val cached = try { jda.getThreadChannelById(threadId) } catch (_: NumberFormatException) { return null }
        cached?.let { return it.takeIf { t -> t.parentChannel.id == forum.id } }
        for (thread in forum.retrieveArchivedPublicThreadChannels()) {
            if (thread.id == threadId) return thread
        }
        return null
    }

    /** A post the bot created for this UUID (matched by the "UUID: ..." footer of its first message). */
    private fun findPostForPlayer(jda: JDA, forum: ForumChannel, uuid: String): ThreadChannel? {
        val selfId = jda.selfUser.idLong
        // The post was made moments before the crash, so only the newest few bot posts are checked.
        val candidates = forum.threadChannels.filter { it.ownerIdLong == selfId }.toMutableList()
        for (thread in forum.retrieveArchivedPublicThreadChannels()) {
            if (candidates.size >= ADOPT_CANDIDATES * 2) break
            if (thread.ownerIdLong == selfId) candidates.add(thread)
        }
        for (thread in candidates.sortedByDescending { it.timeCreated }.take(ADOPT_CANDIDATES)) {
            val start = thread.retrieveStartMessage().timeout(REQUEST_TIMEOUT_S, TimeUnit.SECONDS).complete()
            if (start.embeds.any { it.footer?.text == "UUID: $uuid" }) return thread
        }
        return null
    }

    /** True when [message] is this bot's copy of [embed]: same title, footer, timestamp and punishment id. */
    private fun isSameLog(message: Message, embed: MessageEmbed, jda: JDA): Boolean {
        if (message.author.idLong != jda.selfUser.idLong) return false
        val expectedSecond = embed.timestamp?.toEpochSecond()
        val expectedId = embed.fields.firstOrNull { it.name == "Punishment ID" }?.value
        return message.embeds.any { other ->
            other.title == embed.title &&
                other.footer?.text == embed.footer?.text &&
                other.timestamp?.toEpochSecond() == expectedSecond &&
                other.fields.firstOrNull { it.name == "Punishment ID" }?.value == expectedId
        }
    }

    private fun markInFlight(row: Row) {
        db.execute("UPDATE punishment_discord_outbox SET in_flight_since = ? WHERE id = ?", System.currentTimeMillis(), row.id)
    }

    private fun saveMapping(uuid: String, threadId: String, name: String) {
        db.execute(
            "INSERT OR REPLACE INTO punishment_discord_threads (player_uuid, thread_id, player_name, created_at) VALUES (?, ?, ?, ?)",
            uuid, threadId, name, System.currentTimeMillis()
        )
    }

    /** Stable per log (Discord nonces are at most 25 characters), so Discord drops a quick resend. */
    private fun nonce(row: Row): String = "jmcpl${row.id}"

    /**
     * Tags for a new post: the configured `discord.punishments.forum-tag` if it exists, otherwise
     * none - unless the forum requires a tag, then one named like "punish" or else the first one.
     */
    private fun forumTags(forum: ForumChannel): List<ForumTag> {
        val available = forum.availableTags
        val configured = plugin.config.getString("discord.punishments.forum-tag")?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            available.firstOrNull { it.name.equals(configured, ignoreCase = true) }?.let { return listOf(it) }
            logThrottled("forum-tag", Level.WARNING, "[Discord] discord.punishments.forum-tag \"$configured\" is not a tag on #${forum.name}.")
        }
        if (!forum.isTagRequired) return emptyList()
        val fallback = available.firstOrNull { it.name.contains("punish", ignoreCase = true) } ?: available.firstOrNull()
        if (fallback == null) {
            logThrottled("forum-no-tags", Level.WARNING, "[Discord] #${forum.name} requires a tag but has none - post creation will likely fail.")
            return emptyList()
        }
        return listOf(fallback)
    }

    private fun logThrottled(key: String, level: Level, message: String, intervalMs: Long = LOG_INTERVAL_MS) {
        val now = System.currentTimeMillis()
        val last = lastLogged[key]
        if (last != null && now - last < intervalMs) return
        lastLogged[key] = now
        plugin.logger.log(level, message)
    }

    companion object {
        /** A row is the oldest pending row of its player. */
        private const val HEAD_CONDITION =
            "o.id = (SELECT MIN(i.id) FROM punishment_discord_outbox i WHERE i.status = 'PENDING' AND i.player_uuid = o.player_uuid)"

        private const val BATCH_SIZE = 20
        private const val MAX_PER_CYCLE = 100
        private const val SEND_SPACING_MS = 250L
        private const val MIN_WAIT_MS = 1_000L
        private const val IDLE_WAIT_MS = 60_000L
        private const val FORUM_RECHECK_MS = 60_000L
        private const val ERROR_WAIT_MS = 30_000L
        private const val TRANSIENT_BASE_MS = 10_000L
        private const val TRANSIENT_MAX_MS = 15 * 60_000L
        private const val BLOCKED_RETRY_MS = 15 * 60_000L
        private const val LOG_INTERVAL_MS = 10 * 60_000L
        private const val REQUEST_TIMEOUT_S = 30L
        private const val RECONCILE_HISTORY = 50
        private const val ADOPT_CANDIDATES = 25
        private const val DELIVERED_RETENTION_MS = 30L * 24 * 60 * 60_000L

        /** Without these nothing can be posted. Manage Threads is optional (only locked posts need it). */
        private val REQUIRED_PERMISSIONS = listOf(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.MESSAGE_EMBED_LINKS,
            Permission.MESSAGE_HISTORY
        )

        /** Rejections that need staff to change something; retried slowly, never in a tight loop. */
        private val BLOCKED_ERRORS = setOf(
            ErrorResponse.MISSING_ACCESS,
            ErrorResponse.MISSING_PERMISSIONS,
            ErrorResponse.THREAD_LOCKED,
            ErrorResponse.ILLEGAL_OPERATION_ARCHIVED_THREAD,
            ErrorResponse.MISSING_AVAILABLE_TAGS,
            ErrorResponse.MESSAGE_BLOCKED_BY_AUTOMOD,
            ErrorResponse.TITLE_BLOCKED_BY_AUTOMOD
        )
    }
}
