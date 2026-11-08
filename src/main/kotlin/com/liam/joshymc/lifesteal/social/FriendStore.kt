package com.liam.joshymc.lifesteal.social

import com.liam.joshymc.lifesteal.social.SocialRules.Audience
import com.liam.joshymc.lifesteal.social.SocialRules.Channel
import com.liam.joshymc.lifesteal.social.SocialRules.Prefs
import com.liam.joshymc.lifesteal.social.SocialRules.RequestRefusal
import com.liam.joshymc.manager.SqliteDatabase
import java.util.UUID

/**
 * Lifesteal friendships, friend requests and social preferences (issue #1430), in the Lifesteal
 * server's own data.db (the network shares no database, so this server is the one authority for
 * its friendships). Bukkit-free: talks only to [SqliteDatabase].
 *
 * - `lifesteal_friends`: one row per pair, stored as (lower UUID, higher UUID) so a pair can only
 *   exist once whoever added whom. Keyed by UUID, so name changes never matter.
 * - `lifesteal_friend_requests`: one row per (sender, target) with a status. Only PENDING rows that
 *   haven't expired count; every transition is a conditional UPDATE inside a transaction, so two
 *   accepts (two clicks, a command and a GUI, both players at once) settle exactly once.
 * - `lifesteal_social_prefs`: privacy, notification and chat-channel choices (missing = defaults).
 *
 * Turning the feature off never touches these tables.
 */
class FriendStore(private val db: SqliteDatabase) {

    enum class Status { PENDING, ACCEPTED, DENIED, CANCELLED, EXPIRED }

    data class Request(val sender: UUID, val target: UUID, val createdAt: Long, val expiresAt: Long)
    data class Friendship(val friend: UUID, val since: Long)

    sealed interface SendResult {
        data class Sent(val request: Request) : SendResult
        /** The target had already asked the sender: both requests settle and they're friends. */
        data object BecameFriends : SendResult
        data class Refused(val refusal: RequestRefusal) : SendResult
    }

    sealed interface AcceptResult {
        data object Accepted : AcceptResult
        /** No pending, unexpired request (already handled, cancelled, expired, or a stale button). */
        data object Stale : AcceptResult
        data class Full(val refusal: RequestRefusal) : AcceptResult
    }

    fun createTables() {
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_friends (
                uuid_low TEXT NOT NULL,
                uuid_high TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                PRIMARY KEY (uuid_low, uuid_high),
                CHECK (uuid_low < uuid_high)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_friends_high ON lifesteal_friends (uuid_high)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_friend_requests (
                sender TEXT NOT NULL,
                target TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                resolved_at INTEGER,
                PRIMARY KEY (sender, target),
                CHECK (sender <> target)
            )
            """.trimIndent()
        )
        db.createTable("CREATE INDEX IF NOT EXISTS idx_lifesteal_friend_requests_target ON lifesteal_friend_requests (target, status)")
        db.createTable(
            """
            CREATE TABLE IF NOT EXISTS lifesteal_social_prefs (
                uuid TEXT PRIMARY KEY,
                requests_from TEXT NOT NULL,
                status_visible TEXT NOT NULL,
                messages_from TEXT NOT NULL,
                login_notify INTEGER NOT NULL,
                request_notify INTEGER NOT NULL,
                chat_channel TEXT NOT NULL DEFAULT 'GLOBAL',
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    // ── Friendships ─────────────────────────────────────────

    fun areFriends(a: UUID, b: UUID): Boolean {
        if (a == b) return false
        val (low, high) = ordered(a, b)
        return db.queryFirst("SELECT 1 FROM lifesteal_friends WHERE uuid_low = ? AND uuid_high = ?", low, high) { true } == true
    }

    fun friendsOf(uuid: UUID): List<Friendship> {
        val id = uuid.toString()
        return db.query(
            "SELECT uuid_low, uuid_high, created_at FROM lifesteal_friends WHERE uuid_low = ? OR uuid_high = ? ORDER BY created_at",
            id, id
        ) { rs ->
            val other = if (rs.getString("uuid_low") == id) rs.getString("uuid_high") else rs.getString("uuid_low")
            Friendship(UUID.fromString(other), rs.getLong("created_at"))
        }
    }

    fun friendCount(uuid: UUID): Int {
        val id = uuid.toString()
        return db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_friends WHERE uuid_low = ? OR uuid_high = ?", id, id) { it.getInt("n") } ?: 0
    }

    /** Ends the friendship between [actor] and [friend]. Only ever touches the actor's own pair. */
    fun remove(actor: UUID, friend: UUID): Boolean {
        if (actor == friend) return false
        val (low, high) = ordered(actor, friend)
        return db.executeUpdate("DELETE FROM lifesteal_friends WHERE uuid_low = ? AND uuid_high = ?", low, high) == 1
    }

    // ── Requests ────────────────────────────────────────────

    fun pending(sender: UUID, target: UUID, now: Long): Request? =
        db.queryFirst(
            "SELECT created_at, expires_at FROM lifesteal_friend_requests WHERE sender = ? AND target = ? AND status = ? AND expires_at > ?",
            sender.toString(), target.toString(), Status.PENDING.name, now
        ) { Request(sender, target, it.getLong("created_at"), it.getLong("expires_at")) }

    fun incoming(target: UUID, now: Long): List<Request> =
        db.query(
            "SELECT sender, created_at, expires_at FROM lifesteal_friend_requests WHERE target = ? AND status = ? AND expires_at > ? ORDER BY created_at",
            target.toString(), Status.PENDING.name, now
        ) { Request(UUID.fromString(it.getString("sender")), target, it.getLong("created_at"), it.getLong("expires_at")) }

    fun outgoing(sender: UUID, now: Long): List<Request> =
        db.query(
            "SELECT target, created_at, expires_at FROM lifesteal_friend_requests WHERE sender = ? AND status = ? AND expires_at > ? ORDER BY created_at",
            sender.toString(), Status.PENDING.name, now
        ) { Request(sender, UUID.fromString(it.getString("target")), it.getLong("created_at"), it.getLong("expires_at")) }

    private fun deniedAt(sender: UUID, target: UUID): Long? =
        db.queryFirst(
            "SELECT resolved_at FROM lifesteal_friend_requests WHERE sender = ? AND target = ? AND status = ?",
            sender.toString(), target.toString(), Status.DENIED.name
        ) { it.getLong("resolved_at") }

    /**
     * Sends a request from [sender] to [target], deciding with [SocialRules.requestProblem] on the
     * rows as they are inside the transaction. If [target] already has a pending request to
     * [sender], both settle and they become friends instead (mutual requests).
     */
    fun send(
        sender: UUID,
        target: UUID,
        now: Long,
        expirationMillis: Long,
        maxFriends: Int,
        denyCooldownMillis: Long,
        defaults: Prefs,
        ignoredEitherWay: Boolean,
        onCooldown: Boolean,
    ): SendResult {
        var result: SendResult = SendResult.Refused(RequestRefusal.SELF)
        if (sender == target) return result
        db.transaction {
            val reverse = pending(target, sender, now)
            val refusal = SocialRules.requestProblem(
                sender = sender,
                target = target,
                alreadyFriends = areFriends(sender, target),
                pendingFromSender = pending(sender, target, now) != null,
                // A player who asked you first is always accepting your answer.
                targetPrefs = if (reverse != null) prefs(target, defaults).copy(requestsFrom = Audience.EVERYONE) else prefs(target, defaults),
                senderFriendCount = friendCount(sender),
                targetFriendCount = friendCount(target),
                maxFriends = maxFriends,
                ignoredEitherWay = ignoredEitherWay,
                deniedRecently = reverse == null && deniedAt(sender, target)?.let { now - it < denyCooldownMillis } == true,
                onCooldown = onCooldown,
            )
            if (refusal != null) {
                result = SendResult.Refused(refusal)
                return@transaction
            }
            if (reverse != null) {
                resolve(target, sender, Status.ACCEPTED, now)
                insertFriendship(sender, target, now)
                result = SendResult.BecameFriends
                return@transaction
            }
            val expiresAt = now + expirationMillis
            db.executeUpdate(
                """
                INSERT INTO lifesteal_friend_requests (sender, target, status, created_at, expires_at, resolved_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                ON CONFLICT (sender, target) DO UPDATE SET status = excluded.status, created_at = excluded.created_at,
                    expires_at = excluded.expires_at, resolved_at = NULL
                """.trimIndent(),
                sender.toString(), target.toString(), Status.PENDING.name, now, expiresAt
            )
            result = SendResult.Sent(Request(sender, target, now, expiresAt))
        }
        return result
    }

    /**
     * [target] accepts [sender]'s request. [expectedCreatedAt] (from a GUI button) must match the
     * request that is pending now, so a button for an older, already-answered request does nothing.
     * Friend limits are re-checked here, at the moment it happens.
     */
    fun accept(target: UUID, sender: UUID, now: Long, maxFriends: Int, expectedCreatedAt: Long? = null): AcceptResult {
        var result: AcceptResult = AcceptResult.Stale
        db.transaction {
            val request = pending(sender, target, now) ?: return@transaction
            if (expectedCreatedAt != null && request.createdAt != expectedCreatedAt) return@transaction
            if (areFriends(sender, target)) {
                resolve(sender, target, Status.ACCEPTED, now)
                result = AcceptResult.Stale
                return@transaction
            }
            if (friendCount(target) >= maxFriends) { result = AcceptResult.Full(RequestRefusal.SENDER_FULL); return@transaction }
            if (friendCount(sender) >= maxFriends) { result = AcceptResult.Full(RequestRefusal.TARGET_FULL); return@transaction }
            if (!resolve(sender, target, Status.ACCEPTED, now, request.createdAt)) return@transaction
            // A request the other way (if any) is answered by this too.
            resolve(target, sender, Status.ACCEPTED, now)
            insertFriendship(sender, target, now)
            result = AcceptResult.Accepted
        }
        return result
    }

    /** [target] turns down [sender]'s pending request. False when there was none (or the button was stale). */
    fun deny(target: UUID, sender: UUID, now: Long, expectedCreatedAt: Long? = null): Boolean {
        var done = false
        db.transaction {
            val request = pending(sender, target, now) ?: return@transaction
            if (expectedCreatedAt != null && request.createdAt != expectedCreatedAt) return@transaction
            done = resolve(sender, target, Status.DENIED, now, request.createdAt)
        }
        return done
    }

    /** [sender] withdraws their own pending request to [target]. */
    fun cancel(sender: UUID, target: UUID, now: Long): Boolean =
        pending(sender, target, now)?.let { resolve(sender, target, Status.CANCELLED, now, it.createdAt) } == true

    /** Marks every pending request past its expiry as EXPIRED. Returns how many. */
    fun expire(now: Long): Int =
        db.executeUpdate(
            "UPDATE lifesteal_friend_requests SET status = ?, resolved_at = ? WHERE status = ? AND expires_at <= ?",
            Status.EXPIRED.name, now, Status.PENDING.name, now
        )

    fun status(sender: UUID, target: UUID): Status? =
        db.queryFirst(
            "SELECT status FROM lifesteal_friend_requests WHERE sender = ? AND target = ?",
            sender.toString(), target.toString()
        ) { rs -> Status.entries.firstOrNull { it.name == rs.getString("status") } }

    private fun resolve(sender: UUID, target: UUID, status: Status, now: Long, createdAt: Long? = null): Boolean =
        if (createdAt == null) {
            db.executeUpdate(
                "UPDATE lifesteal_friend_requests SET status = ?, resolved_at = ? WHERE sender = ? AND target = ? AND status = ?",
                status.name, now, sender.toString(), target.toString(), Status.PENDING.name
            ) == 1
        } else {
            db.executeUpdate(
                "UPDATE lifesteal_friend_requests SET status = ?, resolved_at = ? WHERE sender = ? AND target = ? AND status = ? AND created_at = ?",
                status.name, now, sender.toString(), target.toString(), Status.PENDING.name, createdAt
            ) == 1
        }

    private fun insertFriendship(a: UUID, b: UUID, now: Long) {
        val (low, high) = ordered(a, b)
        db.executeUpdate("INSERT OR IGNORE INTO lifesteal_friends (uuid_low, uuid_high, created_at) VALUES (?, ?, ?)", low, high, now)
    }

    // ── Preferences ─────────────────────────────────────────

    fun prefs(uuid: UUID, defaults: Prefs): Prefs =
        db.queryFirst(
            "SELECT requests_from, status_visible, messages_from, login_notify, request_notify FROM lifesteal_social_prefs WHERE uuid = ?",
            uuid.toString()
        ) { rs ->
            Prefs(
                requestsFrom = Audience.parse(rs.getString("requests_from")) ?: defaults.requestsFrom,
                statusVisibleTo = Audience.parse(rs.getString("status_visible")) ?: defaults.statusVisibleTo,
                messagesFrom = Audience.parse(rs.getString("messages_from")) ?: defaults.messagesFrom,
                loginNotifications = rs.getInt("login_notify") != 0,
                requestNotifications = rs.getInt("request_notify") != 0,
            ).normalized()
        } ?: defaults.normalized()

    /** Saves [prefs] for [uuid] (the caller has already checked that [uuid] is the player changing them). */
    fun savePrefs(uuid: UUID, prefs: Prefs, now: Long) {
        val p = prefs.normalized()
        db.executeUpdate(
            """
            INSERT INTO lifesteal_social_prefs (uuid, requests_from, status_visible, messages_from, login_notify, request_notify, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (uuid) DO UPDATE SET requests_from = excluded.requests_from, status_visible = excluded.status_visible,
                messages_from = excluded.messages_from, login_notify = excluded.login_notify,
                request_notify = excluded.request_notify, updated_at = excluded.updated_at
            """.trimIndent(),
            uuid.toString(), p.requestsFrom.name, p.statusVisibleTo.name, p.messagesFrom.name,
            if (p.loginNotifications) 1 else 0, if (p.requestNotifications) 1 else 0, now
        )
    }

    fun channel(uuid: UUID): Channel =
        db.queryFirst("SELECT chat_channel FROM lifesteal_social_prefs WHERE uuid = ?", uuid.toString()) { Channel.parse(it.getString("chat_channel")) }
            ?: Channel.GLOBAL

    fun saveChannel(uuid: UUID, channel: Channel, defaults: Prefs, now: Long) {
        db.transaction {
            val exists = db.queryFirst("SELECT 1 FROM lifesteal_social_prefs WHERE uuid = ?", uuid.toString()) { true } == true
            if (!exists) savePrefs(uuid, defaults, now)
            db.executeUpdate("UPDATE lifesteal_social_prefs SET chat_channel = ?, updated_at = ? WHERE uuid = ?", channel.name, now, uuid.toString())
        }
    }

    companion object {
        /** The stored order of a pair: (lower UUID string, higher UUID string). */
        fun ordered(a: UUID, b: UUID): Pair<String, String> {
            val x = a.toString(); val y = b.toString()
            return if (x < y) x to y else y to x
        }
    }
}
