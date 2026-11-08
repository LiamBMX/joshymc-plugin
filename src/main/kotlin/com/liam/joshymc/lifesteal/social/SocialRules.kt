package com.liam.joshymc.lifesteal.social

import java.util.UUID

/**
 * Lifesteal friends, privacy, private-message and chat-channel rules (issue #1430). Bukkit-free:
 * every decision the commands, the GUI, the listeners and `/msg` make goes through here, so the
 * check script can prove them without a server.
 *
 * Friends are a social relationship only: nothing in here (or anywhere else) lets a friendship
 * grant team access, claim access, teleports, shared storage, PvP protection or alliance changes.
 */
object SocialRules {

    /** Who an option applies to. Not every option offers all three (see [Prefs]). */
    enum class Audience(val label: String) {
        EVERYONE("Everyone"),
        FRIENDS("Friends only"),
        NOBODY("Nobody");

        companion object {
            fun parse(raw: String?): Audience? =
                entries.firstOrNull { it.name.equals(raw?.trim()?.replace('-', '_'), ignoreCase = true) }
                    ?: when (raw?.trim()?.lowercase()) {
                        "all", "anyone" -> EVERYONE
                        "friend", "friends-only", "friends_only" -> FRIENDS
                        "none", "no-one", "noone" -> NOBODY
                        else -> null
                    }
        }
    }

    /** Which audiences each privacy option accepts. Friend requests from "friends only" make no sense. */
    val REQUEST_CHOICES = listOf(Audience.EVERYONE, Audience.NOBODY)
    val STATUS_CHOICES = listOf(Audience.FRIENDS, Audience.NOBODY)
    val MESSAGE_CHOICES = listOf(Audience.EVERYONE, Audience.FRIENDS, Audience.NOBODY)

    /**
     * One player's social preferences. [requestsFrom]: who may send them friend requests.
     * [statusVisibleTo]: whether friends see them online (never shown to non-friends).
     * [messagesFrom]: who may private-message them. The notify flags are what *they* receive.
     */
    data class Prefs(
        val requestsFrom: Audience,
        val statusVisibleTo: Audience,
        val messagesFrom: Audience,
        val loginNotifications: Boolean,
        val requestNotifications: Boolean,
    ) {
        /** Snaps every field to a value its option accepts (a hand-edited row can't widen anything). */
        fun normalized(): Prefs = copy(
            requestsFrom = requestsFrom.takeIf { it in REQUEST_CHOICES } ?: Audience.NOBODY,
            statusVisibleTo = statusVisibleTo.takeIf { it in STATUS_CHOICES } ?: Audience.NOBODY,
            messagesFrom = messagesFrom.takeIf { it in MESSAGE_CHOICES } ?: Audience.FRIENDS,
        )
    }

    /** The next value when a GUI toggle cycles through [choices]. */
    fun next(current: Audience, choices: List<Audience>): Audience {
        val index = choices.indexOf(current)
        return choices[if (index < 0) 0 else (index + 1) % choices.size]
    }

    // ── Settings ────────────────────────────────────────────

    data class Settings(
        val enabled: Boolean,
        val friendsEnabled: Boolean,
        val maxFriends: Int,
        val requestExpirationSeconds: Long,
        val requestCooldownSeconds: Long,
        val denyCooldownSeconds: Long,
        val notificationCooldownSeconds: Long,
        val defaults: Prefs,
        val messagingEnabled: Boolean,
        val channels: Channels,
    ) {
        val requestExpirationMillis: Long get() = requestExpirationSeconds * 1000
    }

    data class Channels(
        val enabled: Boolean,
        val team: Boolean,
        val alliance: Boolean,
        val local: Boolean,
        val localRadius: Int,
        val teamFormat: String,
        val localFormat: String,
    ) {
        fun available(channel: Channel): Boolean = when (channel) {
            Channel.GLOBAL -> true
            Channel.TEAM -> enabled && team
            Channel.ALLIANCE -> enabled && alliance
            Channel.LOCAL -> enabled && local
        }
    }

    const val MAX_FRIENDS_CAP = 500
    const val DEFAULT_MAX_FRIENDS = 50

    val DEFAULT_PREFS = Prefs(Audience.EVERYONE, Audience.FRIENDS, Audience.EVERYONE, loginNotifications = true, requestNotifications = true)

    /** Reads `config.yml > lifesteal-social` through [get]; every bad value falls back and is reported. */
    fun parseSettings(get: (String) -> Any?, problem: (String) -> Unit): Settings {
        fun bool(key: String, default: Boolean): Boolean = when (val v = get(key)) {
            null -> default
            is Boolean -> v
            else -> { problem("$key must be true or false; using $default"); default }
        }
        fun long(key: String, default: Long, min: Long, max: Long): Long {
            val v = get(key) ?: return default
            val n = (v as? Number)?.toLong() ?: v.toString().toLongOrNull()
            if (n == null || n < min || n > max) { problem("$key must be a number from $min to $max; using $default"); return default }
            return n
        }
        fun audience(key: String, default: Audience, choices: List<Audience>): Audience {
            val v = get(key) ?: return default
            val a = Audience.parse(v.toString())
            if (a == null || a !in choices) {
                problem("$key must be one of ${choices.joinToString { it.name.lowercase() }}; using ${default.name.lowercase()}")
                return default
            }
            return a
        }
        fun string(key: String, default: String): String =
            (get(key) as? String)?.takeIf { it.isNotBlank() } ?: default

        val d = DEFAULT_PREFS
        return Settings(
            enabled = bool("enabled", true),
            friendsEnabled = bool("friends.enabled", true),
            maxFriends = long("friends.max-friends", DEFAULT_MAX_FRIENDS.toLong(), 1, MAX_FRIENDS_CAP.toLong()).toInt(),
            requestExpirationSeconds = long("friends.request-expiration-seconds", 300, 30, 7L * 24 * 3600),
            requestCooldownSeconds = long("friends.request-cooldown-seconds", 10, 0, 3600),
            denyCooldownSeconds = long("friends.deny-cooldown-seconds", 300, 0, 7L * 24 * 3600),
            notificationCooldownSeconds = long("notifications.cooldown-seconds", 60, 0, 3600),
            defaults = Prefs(
                requestsFrom = audience("privacy-defaults.friend-requests", d.requestsFrom, REQUEST_CHOICES),
                statusVisibleTo = audience("privacy-defaults.online-status", d.statusVisibleTo, STATUS_CHOICES),
                messagesFrom = audience("privacy-defaults.private-messages", d.messagesFrom, MESSAGE_CHOICES),
                loginNotifications = bool("privacy-defaults.login-notifications", d.loginNotifications),
                requestNotifications = bool("privacy-defaults.request-notifications", d.requestNotifications),
            ),
            messagingEnabled = bool("messaging.enabled", true),
            channels = Channels(
                enabled = bool("channels.enabled", true),
                team = bool("channels.team.enabled", true),
                alliance = bool("channels.alliance.enabled", true),
                local = bool("channels.local.enabled", true),
                localRadius = long("channels.local.radius", 100, 8, 1000).toInt(),
                teamFormat = string("channels.team.format", DEFAULT_TEAM_FORMAT),
                localFormat = string("channels.local.format", DEFAULT_LOCAL_FORMAT),
            ),
        )
    }

    const val DEFAULT_TEAM_FORMAT = "&b&l[Team] &r&3{team} &f{player}&7: &f{message}"
    const val DEFAULT_LOCAL_FORMAT = "&e&l[Local] &r&f{player}&7: &f{message}"

    // ── Friend requests ─────────────────────────────────────

    enum class RequestRefusal(val message: String) {
        SELF("You can't add yourself as a friend."),
        ALREADY_FRIENDS("You're already friends."),
        DUPLICATE("You already sent them a friend request."),
        NOT_ACCEPTING("That player isn't accepting friend requests."),
        SENDER_FULL("Your friend list is full."),
        TARGET_FULL("Their friend list is full."),
        COOLDOWN("Please wait a moment before sending another friend request."),
        IGNORED("That player isn't accepting friend requests."),
    }

    /**
     * Whether [sender] may send [target] a friend request right now. Ignoring works both ways (a
     * player can't be pestered by someone they ignore, and can't request someone they ignore).
     * A recent denial looks exactly like "not accepting", so the denied player learns nothing.
     */
    fun requestProblem(
        sender: UUID,
        target: UUID,
        alreadyFriends: Boolean,
        pendingFromSender: Boolean,
        targetPrefs: Prefs,
        senderFriendCount: Int,
        targetFriendCount: Int,
        maxFriends: Int,
        ignoredEitherWay: Boolean,
        deniedRecently: Boolean,
        onCooldown: Boolean,
    ): RequestRefusal? = when {
        sender == target -> RequestRefusal.SELF
        alreadyFriends -> RequestRefusal.ALREADY_FRIENDS
        pendingFromSender -> RequestRefusal.DUPLICATE
        onCooldown -> RequestRefusal.COOLDOWN
        ignoredEitherWay -> RequestRefusal.IGNORED
        targetPrefs.requestsFrom == Audience.NOBODY -> RequestRefusal.NOT_ACCEPTING
        deniedRecently -> RequestRefusal.NOT_ACCEPTING
        senderFriendCount >= maxFriends -> RequestRefusal.SENDER_FULL
        targetFriendCount >= maxFriends -> RequestRefusal.TARGET_FULL
        else -> null
    }

    // ── Online status and notifications ─────────────────────

    /**
     * Whether [viewer] sees [subject] as online. Only friends ever see it, and only when the subject
     * allows it. A hidden (vanished) subject is offline to everyone who can't see vanished staff,
     * whatever their friendship.
     */
    fun showsOnline(
        subjectOnline: Boolean,
        subjectHidden: Boolean,
        viewerSeesHidden: Boolean,
        friends: Boolean,
        subjectPrefs: Prefs,
    ): Boolean = subjectOnline && friends &&
        (!subjectHidden || viewerSeesHidden) &&
        subjectPrefs.statusVisibleTo == Audience.FRIENDS

    /**
     * Whether a login/logout notice about [subject] goes to a friend. Never for a hidden join (vanish
     * or private silent join), not even to staff: the notice is a chat line others could screenshot.
     */
    fun notifiesLogin(subjectHidesJoin: Boolean, subjectPrefs: Prefs, receiverPrefs: Prefs): Boolean =
        !subjectHidesJoin && subjectPrefs.statusVisibleTo == Audience.FRIENDS && receiverPrefs.loginNotifications

    /**
     * Rate limit for notifications, keyed by receiver + subject + kind: one notice per key per
     * cooldown, so relogging can't spam friends. Bounded: old entries are pruned when it grows.
     */
    class NotificationThrottle(private val cooldownMillis: Long, private val maxEntries: Int = 10_000) {
        private val last = HashMap<String, Long>()

        @Synchronized
        fun allow(receiver: UUID, subject: UUID, kind: String, now: Long): Boolean {
            if (cooldownMillis <= 0) return true
            val key = "$receiver|$subject|$kind"
            val previous = last[key]
            if (previous != null && now - previous < cooldownMillis) return false
            if (last.size >= maxEntries) last.entries.removeIf { now - it.value >= cooldownMillis }
            if (last.size >= maxEntries) last.clear()
            last[key] = now
            return true
        }

        @Synchronized
        fun size(): Int = last.size
    }

    // ── Private messages ────────────────────────────────────

    enum class MessageRefusal(val message: String) {
        SELF("You can't message yourself."),
        UNAVAILABLE("That player isn't online."),
        IGNORED("That player is not accepting messages from you."),
        NOBODY("That player isn't accepting private messages."),
        FRIENDS_ONLY("That player only accepts private messages from friends."),
        YOU_IGNORE("You're ignoring that player. Unignore them first with /ignore."),
    }

    /**
     * The Lifesteal privacy part of a private message, after mutes (always first, never bypassed).
     * A target hidden from the sender looks offline. Staff with the privacy bypass skip only the
     * audience setting; ignores always apply. [answering] = a `/r` to the player who last messaged
     * the sender: they started the conversation, so their own audience/vanish doesn't refuse it.
     */
    fun messageProblem(
        sender: UUID,
        target: UUID,
        targetHiddenFromSender: Boolean,
        targetIgnoresSender: Boolean,
        senderIgnoresTarget: Boolean,
        friends: Boolean,
        targetPrefs: Prefs,
        bypassPrivacy: Boolean,
        answering: Boolean = false,
    ): MessageRefusal? = when {
        sender == target -> MessageRefusal.SELF
        targetHiddenFromSender && !answering -> MessageRefusal.UNAVAILABLE
        targetIgnoresSender -> MessageRefusal.IGNORED
        senderIgnoresTarget -> MessageRefusal.YOU_IGNORE
        bypassPrivacy || answering -> null
        targetPrefs.messagesFrom == Audience.NOBODY -> MessageRefusal.NOBODY
        targetPrefs.messagesFrom == Audience.FRIENDS && !friends -> MessageRefusal.FRIENDS_ONLY
        else -> null
    }

    // ── Chat channels ───────────────────────────────────────

    enum class Channel(val label: String) {
        GLOBAL("Global"),
        TEAM("Team"),
        ALLIANCE("Alliance"),
        LOCAL("Local");

        companion object {
            fun parse(raw: String?): Channel? = when (raw?.trim()?.lowercase()) {
                "global", "g", "all", "public" -> GLOBAL
                "team", "t", "tc" -> TEAM
                "alliance", "ally", "a", "ac" -> ALLIANCE
                "local", "l", "nearby" -> LOCAL
                else -> null
            }
        }
    }

    /**
     * Why [channel] can't carry a message from this player, or null. Membership is the team cache
     * (and the current alliance) read right before delivery; friendship never grants access.
     */
    fun channelProblem(channel: Channel, channels: Channels, inTeam: Boolean, teamAllied: Boolean): String? = when {
        !channels.available(channel) -> "${channel.label} chat is turned off right now."
        channel == Channel.TEAM && !inTeam -> "You're not in a Lifesteal team."
        channel == Channel.ALLIANCE && !inTeam -> "You're not in a team. Alliance chat is for teams in an alliance."
        channel == Channel.ALLIANCE && !teamAllied -> "Your team has no alliance."
        else -> null
    }

    /**
     * Team chat recipients: the sender's current roster only, minus anyone who ignores the sender
     * or isn't in a Lifesteal world (the sender always sees their own line).
     */
    fun teamRecipients(sender: UUID, roster: Collection<UUID>, inLifesteal: (UUID) -> Boolean, ignores: (UUID, UUID) -> Boolean): List<UUID> =
        roster.filter { it == sender || (inLifesteal(it) && !ignores(it, sender)) }.let { if (sender in it) it else it + sender }

    /** Local chat recipients: same world, within [radius] blocks (squared distance), not ignoring the sender. */
    fun localRecipients(
        sender: UUID,
        candidates: Collection<Nearby>,
        radius: Int,
        ignores: (UUID, UUID) -> Boolean,
    ): List<UUID> {
        val me = candidates.firstOrNull { it.uuid == sender }
        val r2 = radius.toDouble() * radius
        val out = candidates.filter { c ->
            c.uuid == sender || (me != null && c.world == me.world && c.distanceSquaredTo(me) <= r2 && !ignores(c.uuid, sender))
        }.map { it.uuid }
        return if (sender in out) out else out + sender
    }

    data class Nearby(val uuid: UUID, val world: String, val x: Double, val y: Double, val z: Double) {
        fun distanceSquaredTo(o: Nearby): Double {
            val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
            return dx * dx + dy * dy + dz * dz
        }
    }

    /** Fills a channel format (`{team}`, `{player}`, `{message}`); [message] is inserted literally by the caller. */
    fun fill(format: String, team: String, player: String): Pair<String, String> {
        val withNames = format.replace("{team}", team).replace("{player}", player)
        val at = withNames.indexOf("{message}")
        return if (at < 0) withNames to "" else withNames.substring(0, at) to withNames.substring(at + "{message}".length)
    }

    // ── GUI safety ──────────────────────────────────────────

    /**
     * A GUI button is only acted on if what it showed is still true: the request it showed still
     * exists (same creation time) or the friendship still exists. Anything else is stale.
     */
    fun requestButtonValid(shownCreatedAt: Long, currentCreatedAt: Long?, currentPending: Boolean, now: Long, expiresAt: Long?): Boolean =
        currentPending && currentCreatedAt == shownCreatedAt && expiresAt != null && expiresAt > now
}
