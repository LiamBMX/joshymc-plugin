// Checks the Lifesteal friends system, social privacy, private messaging and chat channels
// (issue #1430): the settings parser, the Bukkit-free friend store against real SQLite (fresh temp
// directories, deleted afterwards), privacy/vanish/message/channel rules, concurrency, restarts,
// the vanilla-command mute guard, and the Lifesteal-only wiring. The Gradle build has no test
// source set, so it runs through an init script, from the repo root:
//
//   ./gradlew -I scripts/lifesteal-social-check.init.gradle.kts lifestealSocialCheck
//
// Exits non-zero on the first failed check. Real chat, GUIs and vanish on a Paper server are NOT
// covered here (see docs/lifesteal-social.md, "Manual tests").

import com.liam.joshymc.lifesteal.social.FriendStore
import com.liam.joshymc.lifesteal.social.FriendStore.AcceptResult
import com.liam.joshymc.lifesteal.social.FriendStore.SendResult
import com.liam.joshymc.lifesteal.social.SocialRules
import com.liam.joshymc.lifesteal.social.SocialRules.Audience
import com.liam.joshymc.lifesteal.social.SocialRules.Channel
import com.liam.joshymc.lifesteal.social.SocialRules.MessageRefusal
import com.liam.joshymc.lifesteal.social.SocialRules.Prefs
import com.liam.joshymc.lifesteal.social.SocialRules.RequestRefusal
import com.liam.joshymc.listener.MutedCommands
import com.liam.joshymc.manager.DatabaseManager
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.system.exitProcess

private var checks = 0

private fun check(name: String, condition: Boolean) {
    checks++
    if (!condition) {
        System.err.println("FAIL: $name")
        exitProcess(1)
    }
}

private fun quietLogger(): Logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; level = Level.OFF }
private fun source(path: String): String = File("src/main/kotlin/com/liam/joshymc/$path").readText()
private fun resourceText(name: String): String = File("src/main/resources/$name").readText()

private const val T0 = 1_800_000_000_000L
private const val MIN = 60_000L
private val DEFAULTS = SocialRules.DEFAULT_PREFS
private const val MAX = 3

fun main() {
    settings()
    requestRules()
    privacyAndVanish()
    messages()
    channels()
    mutedCommands()
    storeRequests()
    storeAcceptDeny()
    storeLimits()
    storeRemoval()
    storePrefs()
    restarts()
    concurrency()
    wiring()
    println("OK: $checks Lifesteal social checks passed.")
}

// ── Settings ─────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun bundledSection(): Map<String, Any?> {
    val root = Yaml().load<Map<String, Any?>>(resourceText("config.yml"))
    return root["lifesteal-social"] as Map<String, Any?>
}

private fun lookup(section: Map<String, Any?>): (String) -> Any? = { key ->
    var node: Any? = section
    for (part in key.split('.')) node = (node as? Map<*, *>)?.get(part)
    node
}

private fun settings() {
    val problems = mutableListOf<String>()
    val s = SocialRules.parseSettings(lookup(bundledSection())) { problems += it }
    check("bundled config.yml lifesteal-social parses without problems: $problems", problems.isEmpty())
    check("bundled defaults: enabled, 50 friends, 300s requests", s.enabled && s.friendsEnabled && s.maxFriends == 50 && s.requestExpirationSeconds == 300L)
    check("bundled privacy defaults match the code defaults", s.defaults == DEFAULTS)
    check("bundled channels on, local radius 100", s.channels.enabled && s.channels.team && s.channels.alliance && s.channels.local && s.channels.localRadius == 100)
    check("bundled team format has the placeholders", "{player}" in s.channels.teamFormat && "{message}" in s.channels.teamFormat && "{team}" in s.channels.teamFormat)

    val missing = SocialRules.parseSettings({ null }) { problems += it }
    check("a missing section uses the defaults silently", problems.isEmpty() && missing.maxFriends == SocialRules.DEFAULT_MAX_FRIENDS && missing.defaults == DEFAULTS)

    val bad = mapOf(
        "friends" to mapOf("max-friends" to 0, "request-expiration-seconds" to "soon"),
        "privacy-defaults" to mapOf("friend-requests" to "friends", "private-messages" to "nobody", "online-status" to "everyone"),
        "enabled" to "yes",
    )
    val reported = mutableListOf<String>()
    val parsed = SocialRules.parseSettings(lookup(bad)) { reported += it }
    check("out-of-range limits fall back and are reported", parsed.maxFriends == SocialRules.DEFAULT_MAX_FRIENDS && parsed.requestExpirationSeconds == 300L)
    check("an audience an option doesn't offer falls back (requests can't be friends-only, status can't be everyone)",
        parsed.defaults.requestsFrom == Audience.EVERYONE && parsed.defaults.statusVisibleTo == Audience.FRIENDS)
    check("a valid audience is kept", parsed.defaults.messagesFrom == Audience.NOBODY)
    check("a non-boolean switch falls back to on and is reported", parsed.enabled && reported.size >= 5)
    check("the friend limit has a hard cap", SocialRules.parseSettings(lookup(mapOf("friends" to mapOf("max-friends" to 100_000)))) {}.maxFriends == SocialRules.DEFAULT_MAX_FRIENDS)

    check("audience parsing accepts readable forms", Audience.parse("friends-only") == Audience.FRIENDS && Audience.parse("none") == Audience.NOBODY && Audience.parse("ALL") == Audience.EVERYONE)
    check("normalize snaps a stored value to the option's choices", Prefs(Audience.FRIENDS, Audience.EVERYONE, Audience.NOBODY, true, true).normalized().let {
        it.requestsFrom == Audience.NOBODY && it.statusVisibleTo == Audience.NOBODY && it.messagesFrom == Audience.NOBODY
    })
    check("GUI cycling walks every choice and wraps", SocialRules.next(Audience.EVERYONE, SocialRules.MESSAGE_CHOICES) == Audience.FRIENDS &&
        SocialRules.next(Audience.NOBODY, SocialRules.MESSAGE_CHOICES) == Audience.EVERYONE &&
        SocialRules.next(Audience.FRIENDS, SocialRules.REQUEST_CHOICES) == Audience.EVERYONE)
}

// ── Request rules ────────────────────────────────────────────

private fun refusal(
    sender: UUID = A, target: UUID = B, friends: Boolean = false, pending: Boolean = false, prefs: Prefs = DEFAULTS,
    senderCount: Int = 0, targetCount: Int = 0, ignored: Boolean = false, denied: Boolean = false, cooldown: Boolean = false,
) = SocialRules.requestProblem(sender, target, friends, pending, prefs, senderCount, targetCount, MAX, ignored, denied, cooldown)

private val A = UUID.fromString("00000000-0000-0000-0000-00000000000a")
private val B = UUID.fromString("00000000-0000-0000-0000-00000000000b")
private val C = UUID.fromString("00000000-0000-0000-0000-00000000000c")
private val D = UUID.fromString("00000000-0000-0000-0000-00000000000d")

private fun requestRules() {
    check("a normal request is allowed", refusal() == null)
    check("self-friending is refused", refusal(target = A) == RequestRefusal.SELF)
    check("already friends is refused", refusal(friends = true) == RequestRefusal.ALREADY_FRIENDS)
    check("a duplicate request is refused", refusal(pending = true) == RequestRefusal.DUPLICATE)
    check("the per-sender cooldown is enforced", refusal(cooldown = true) == RequestRefusal.COOLDOWN)
    check("ignoring (either way) refuses with the not-accepting wording", refusal(ignored = true)?.message == RequestRefusal.NOT_ACCEPTING.message)
    check("'nobody' refuses requests", refusal(prefs = DEFAULTS.copy(requestsFrom = Audience.NOBODY)) == RequestRefusal.NOT_ACCEPTING)
    check("a recent denial looks like not accepting (no hint)", refusal(denied = true) == RequestRefusal.NOT_ACCEPTING)
    check("a full sender is refused", refusal(senderCount = MAX) == RequestRefusal.SENDER_FULL)
    check("a full target is refused", refusal(targetCount = MAX) == RequestRefusal.TARGET_FULL)
}

// ── Privacy, notifications, vanish ───────────────────────────

private fun privacyAndVanish() {
    val visible = DEFAULTS
    val hidden = DEFAULTS.copy(statusVisibleTo = Audience.NOBODY)
    check("a friend sees an online friend", SocialRules.showsOnline(true, false, false, true, visible))
    check("non-friends never see online status", !SocialRules.showsOnline(true, false, false, false, visible))
    check("status 'nobody' hides from friends", !SocialRules.showsOnline(true, false, false, true, hidden))
    check("a vanished friend looks offline to players", !SocialRules.showsOnline(true, true, false, true, visible))
    check("staff who see vanish see a vanished friend online", SocialRules.showsOnline(true, true, true, true, visible))
    check("offline is offline", !SocialRules.showsOnline(false, false, true, true, visible))

    check("a friend's join is announced", SocialRules.notifiesLogin(false, visible, DEFAULTS))
    check("a hidden (vanished / silent) join is never announced", !SocialRules.notifiesLogin(true, visible, DEFAULTS))
    check("status 'nobody' means no join notices", !SocialRules.notifiesLogin(false, hidden, DEFAULTS))
    check("receivers can turn join notices off", !SocialRules.notifiesLogin(false, visible, DEFAULTS.copy(loginNotifications = false)))

    val throttle = SocialRules.NotificationThrottle(MIN, maxEntries = 4)
    check("first notice goes out", throttle.allow(A, B, "join", T0))
    check("a relog inside the cooldown is suppressed", !throttle.allow(A, B, "join", T0 + 1_000))
    check("another kind is separate", throttle.allow(A, B, "quit", T0 + 1_000))
    check("another receiver is separate", throttle.allow(C, B, "join", T0 + 1_000))
    check("after the cooldown it goes out again", throttle.allow(A, B, "join", T0 + MIN))
    repeat(20) { throttle.allow(UUID.randomUUID(), B, "join", T0 + MIN) }
    check("the throttle stays bounded", throttle.size() <= 4)
    check("cooldown 0 never throttles", SocialRules.NotificationThrottle(0).let { it.allow(A, B, "x", T0) && it.allow(A, B, "x", T0) })
}

// ── Private messages ─────────────────────────────────────────

private fun msg(
    hidden: Boolean = false, ignores: Boolean = false, iIgnore: Boolean = false, friends: Boolean = false,
    prefs: Prefs = DEFAULTS, bypass: Boolean = false, answering: Boolean = false, target: UUID = B,
) = SocialRules.messageProblem(A, target, hidden, ignores, iIgnore, friends, prefs, bypass, answering)

private fun messages() {
    check("everyone: a stranger may message", msg() == null)
    check("self is refused", msg(target = A) == MessageRefusal.SELF)
    check("a hidden (vanished) target looks unavailable, not 'ignoring you'", msg(hidden = true, ignores = true) == MessageRefusal.UNAVAILABLE)
    check("an ignore is respected", msg(ignores = true) == MessageRefusal.IGNORED)
    check("you can't message someone you ignore", msg(iIgnore = true) == MessageRefusal.YOU_IGNORE)
    val friendsOnly = DEFAULTS.copy(messagesFrom = Audience.FRIENDS)
    val nobody = DEFAULTS.copy(messagesFrom = Audience.NOBODY)
    check("friends-only refuses strangers", msg(prefs = friendsOnly) == MessageRefusal.FRIENDS_ONLY)
    check("friends-only allows friends", msg(prefs = friendsOnly, friends = true) == null)
    check("nobody refuses even friends", msg(prefs = nobody, friends = true) == MessageRefusal.NOBODY)
    check("staff privacy bypass skips the audience", msg(prefs = nobody, bypass = true) == null)
    check("staff privacy bypass never skips an ignore", msg(prefs = nobody, bypass = true, ignores = true) == MessageRefusal.IGNORED)
    check("answering the player who messaged you skips their audience/vanish", msg(prefs = nobody, hidden = true, answering = true) == null)
    check("answering never skips an ignore", msg(answering = true, ignores = true) == MessageRefusal.IGNORED)

    val pm = source("command/PrivateMessages.kt")
    val muteAt = pm.indexOf("isMutedIn(")
    check("private messages check the mute first, before any other rule", muteAt > 0 && muteAt < pm.indexOf("appliesToMessage") && muteAt < pm.indexOf("IgnoreCommand.isIgnoring"))
    check("private messages apply the Lifesteal filters", "social.moderate(" in pm)
    val ess = source("command/EssentialsCommands.kt")
    val msgBody = ess.substringAfter("class MsgCommand").substringBefore("class ReplyCommand")
    val replyBody = ess.substringAfter("class ReplyCommand").substringBefore("object TeleportChecks")
    check("/msg delivers only through PrivateMessages", "PrivateMessages.send(" in msgBody && "target.sendMessage" !in msgBody)
    check("/r delivers only through PrivateMessages", "PrivateMessages.send(" in replyBody && "target.sendMessage" !in replyBody)
    val yml = resourceText("plugin.yml")
    check("/tell, /w and /whisper are aliases of the one /msg", Regex("""\n  msg:\n(?:    .*\n)*?    aliases: \[tell, w, whisper, pm, dm]""").containsMatchIn(yml))
    check("/r is the alias of /reply", Regex("""\n  reply:\n(?:    .*\n)*?    aliases: \[r]""").containsMatchIn(yml))
    val menu = source("lifesteal/social/FriendsMenu.kt")
    check("the Friends menu's Send Message only suggests /msg (never delivers itself)", "suggestCommand(\"/msg" in menu && "sendMessage(" !in menu)
}

// ── Chat channels ────────────────────────────────────────────

private fun channels() {
    val on = SocialRules.parseSettings({ null }) {}.channels
    check("global needs nothing", SocialRules.channelProblem(Channel.GLOBAL, on, inTeam = false, teamAllied = false) == null)
    check("team chat needs a team", SocialRules.channelProblem(Channel.TEAM, on, inTeam = false, teamAllied = false) != null)
    check("team chat works in a team", SocialRules.channelProblem(Channel.TEAM, on, inTeam = true, teamAllied = false) == null)
    check("alliance chat needs a team", SocialRules.channelProblem(Channel.ALLIANCE, on, inTeam = false, teamAllied = true) != null)
    check("alliance chat needs an alliance", SocialRules.channelProblem(Channel.ALLIANCE, on, inTeam = true, teamAllied = false) != null)
    check("alliance chat works for allied teams", SocialRules.channelProblem(Channel.ALLIANCE, on, inTeam = true, teamAllied = true) == null)
    check("local needs nothing but the switch", SocialRules.channelProblem(Channel.LOCAL, on, inTeam = false, teamAllied = false) == null)
    val off = on.copy(team = false, local = false)
    check("a channel turned off is refused", SocialRules.channelProblem(Channel.TEAM, off, true, true) != null && SocialRules.channelProblem(Channel.LOCAL, off, true, true) != null)
    check("all channels off leaves only global", SocialRules.channelProblem(Channel.ALLIANCE, on.copy(enabled = false), true, true) != null &&
        SocialRules.channelProblem(Channel.GLOBAL, on.copy(enabled = false), false, false) == null)
    check("channel names parse", Channel.parse("tc") == Channel.TEAM && Channel.parse("ally") == Channel.ALLIANCE && Channel.parse("l") == Channel.LOCAL && Channel.parse("x") == null)

    val noIgnore: (UUID, UUID) -> Boolean = { _, _ -> false }
    val team = SocialRules.teamRecipients(A, listOf(A, B, C), { it != C }, noIgnore)
    check("team chat reaches only the roster in Lifesteal", team.toSet() == setOf(A, B))
    check("team chat skips anyone ignoring the sender", SocialRules.teamRecipients(A, listOf(A, B), { true }) { r, s -> r == B && s == A } == listOf(A))
    check("team chat never reaches a non-member (a friend outside the team)", D !in SocialRules.teamRecipients(A, listOf(A, B), { true }, noIgnore))

    val here = listOf(
        SocialRules.Nearby(A, "ls", 0.0, 64.0, 0.0),
        SocialRules.Nearby(B, "ls", 50.0, 64.0, 0.0),
        SocialRules.Nearby(C, "ls", 150.0, 64.0, 0.0),
        SocialRules.Nearby(D, "other", 0.0, 64.0, 0.0),
    )
    val local = SocialRules.localRecipients(A, here, 100, noIgnore)
    check("local chat reaches players in range in the same world only", local.toSet() == setOf(A, B))
    check("local chat skips anyone ignoring the sender", SocialRules.localRecipients(A, here, 100) { r, _ -> r == B } == listOf(A))

    val (before, after) = SocialRules.fill("&b[Team] {team} {player}: {message}!", "Wolves", "Steve")
    check("formats fill team and player around the message", before == "&b[Team] Wolves Steve: " && after == "!")
    val listener = source("lifesteal/social/LifestealSocialListener.kt")
    check("channel routing cancels at HIGH with ignoreCancelled (after mutes/spam/filter, before format and Discord)",
        "priority = EventPriority.HIGH, ignoreCancelled = true" in listener && "event.isCancelled = true" in listener)
    val mgr = source("lifesteal/social/LifestealSocialManager.kt")
    check("channel delivery re-checks the Lifesteal mute", mgr.substringAfter("fun speak(").contains("isMutedIn(player.uniqueId, ServerGamemode.LIFESTEAL)"))
    check("alliance channel goes through the existing /ac path", "relations.allianceChat(player, message)" in mgr)
    check("a refused channel message is never sent to public chat", !mgr.substringAfter("fun speak(").contains("player.chat("))
}

// ── Vanilla chat commands while muted ────────────────────────

private fun mutedCommands() {
    fun blocked(cmd: String) = MutedCommands.label(cmd)?.let { MutedCommands.isChatCommand(it) } == true
    check("/me is guarded", blocked("/me waves"))
    check("/minecraft:msg, tell and w are guarded", blocked("/minecraft:msg Bob hi") && blocked("/minecraft:tell Bob hi") && blocked("/MINECRAFT:W Bob hi"))
    check("/teammsg and /tm are guarded", blocked("/teammsg hi") && blocked("/minecraft:tm hi"))
    check("JoshyMC's own /msg labels are left to its executor (which checks the mute)", !blocked("/msg Bob hi") && !blocked("/tell Bob hi") && !blocked("/joshymc:msg Bob hi"))
    check("unrelated commands are untouched", !blocked("/spawn") && !blocked("/menu") && !blocked("/meet"))
}

// ── Store ────────────────────────────────────────────────────

private fun <T> withDb(name: String, block: (DatabaseManager, FriendStore) -> T): T {
    val dir = Files.createTempDirectory("joshymc-social-$name").toFile()
    try {
        val db = DatabaseManager(dir, quietLogger())
        db.start()
        try {
            val store = FriendStore(db)
            store.createTables()
            return block(db, store)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun FriendStore.ask(from: UUID, to: UUID, now: Long = T0, ignored: Boolean = false, cooldown: Boolean = false, max: Int = MAX) =
    send(from, to, now, 5 * MIN, max, 5 * MIN, DEFAULTS, ignored, cooldown)

private fun storeRequests() = withDb("requests") { _, store ->
    val sent = store.ask(A, B)
    check("a request is created", sent is SendResult.Sent && store.pending(A, B, T0 + 1) != null)
    check("it shows as incoming and outgoing", store.incoming(B, T0 + 1).map { it.sender } == listOf(A) && store.outgoing(A, T0 + 1).map { it.target } == listOf(B))
    check("a duplicate request is refused", (store.ask(A, B, T0 + 10) as? SendResult.Refused)?.refusal == RequestRefusal.DUPLICATE)
    check("the duplicate didn't reset the request", store.pending(A, B, T0 + 11)?.createdAt == T0)
    check("self requests are refused without touching the table", (store.ask(A, A) as? SendResult.Refused)?.refusal == RequestRefusal.SELF)
    check("ignored players can't send", (store.ask(C, B, ignored = true) as? SendResult.Refused) != null && store.pending(C, B, T0 + 1) == null)
    check("requests to offline players work (UUIDs only)", store.ask(A, D) is SendResult.Sent)

    // Expiry.
    check("a request expires after its time", store.pending(A, B, T0 + 5 * MIN) == null && store.incoming(B, T0 + 5 * MIN).isEmpty())
    check("an expired request can't be accepted", store.accept(B, A, T0 + 5 * MIN, MAX) == AcceptResult.Stale)
    check("the sweep marks expired requests", store.expire(T0 + 5 * MIN) == 2 && store.status(A, B) == FriendStore.Status.EXPIRED)
    check("after expiry a new request can be sent", store.ask(A, B, T0 + 6 * MIN) is SendResult.Sent)

    // Mutual requests settle into a friendship.
    store.ask(C, D, T0 + 6 * MIN)
    check("a request back to someone who asked you makes you friends", store.ask(D, C, T0 + 6 * MIN + 1) == SendResult.BecameFriends && store.areFriends(C, D))
    check("the mutual request settled the original one", store.pending(C, D, T0 + 6 * MIN + 2) == null)
    check("a mutual answer works even with requests set to nobody", withDb("mutual") { _, s2 ->
        s2.savePrefs(C, DEFAULTS.copy(requestsFrom = Audience.NOBODY), T0)
        s2.ask(C, D) is SendResult.Sent && (s2.ask(D, C) == SendResult.BecameFriends)
    })
    check("nobody refuses ordinary requests", withDb("nobody") { _, s2 ->
        s2.savePrefs(B, DEFAULTS.copy(requestsFrom = Audience.NOBODY), T0)
        (s2.ask(A, B) as? SendResult.Refused)?.refusal == RequestRefusal.NOT_ACCEPTING
    })
}

private fun storeAcceptDeny() = withDb("accept") { _, store ->
    store.ask(A, B)
    check("only the target can accept (the sender's 'accept' finds nothing)", store.accept(A, B, T0 + 1, MAX) == AcceptResult.Stale && !store.areFriends(A, B))
    check("a stale GUI button (other creation time) does nothing", store.accept(B, A, T0 + 1, MAX, expectedCreatedAt = T0 - 1) == AcceptResult.Stale && !store.areFriends(A, B))
    check("the target accepts", store.accept(B, A, T0 + 1, MAX, expectedCreatedAt = T0) == AcceptResult.Accepted && store.areFriends(A, B) && store.areFriends(B, A))
    check("accepting twice does nothing more", store.accept(B, A, T0 + 2, MAX) == AcceptResult.Stale && store.friendCount(A) == 1)
    check("already friends refuses a new request", (store.ask(B, A, T0 + 3) as? SendResult.Refused)?.refusal == RequestRefusal.ALREADY_FRIENDS)

    store.ask(C, B, T0)
    check("a stale deny button does nothing", !store.deny(B, C, T0 + 1, expectedCreatedAt = T0 + 99) && store.pending(C, B, T0 + 1) != null)
    check("the target denies", store.deny(B, C, T0 + 1) && store.status(C, B) == FriendStore.Status.DENIED)
    check("denying twice does nothing", !store.deny(B, C, T0 + 2))
    check("a denied player can't ask again during the wait", (store.ask(C, B, T0 + MIN) as? SendResult.Refused)?.refusal == RequestRefusal.NOT_ACCEPTING)
    check("after the deny wait they can ask again", store.ask(C, B, T0 + 6 * MIN) is SendResult.Sent)

    store.ask(D, C, T0)
    check("only the sender can cancel (the target can't 'cancel' it)", !store.cancel(C, D, T0 + 1) && store.pending(D, C, T0 + 1) != null)
    check("the sender cancels", store.cancel(D, C, T0 + 1) && store.status(D, C) == FriendStore.Status.CANCELLED)
}

private fun storeLimits() = withDb("limits") { _, store ->
    val others = List(MAX) { UUID.randomUUID() }
    for (o in others) { store.ask(A, o); store.accept(o, A, T0 + 1, MAX) }
    check("friends up to the limit", store.friendCount(A) == MAX)
    check("a full player can't send", (store.ask(A, B, T0 + 2) as? SendResult.Refused)?.refusal == RequestRefusal.SENDER_FULL)
    check("nobody can send to a full player", (store.ask(B, A, T0 + 2) as? SendResult.Refused)?.refusal == RequestRefusal.TARGET_FULL)
    // A request sent before the list filled is re-checked on accept.
    store.ask(C, D, T0)
    val fill = List(MAX) { UUID.randomUUID() }
    for (o in fill) { store.ask(D, o, T0 + 1); store.accept(o, D, T0 + 2, MAX) }
    check("the limit is re-checked when accepting", store.accept(D, C, T0 + 3, MAX) is AcceptResult.Full && !store.areFriends(C, D))
    check("the request stays pending after a full accept", store.pending(C, D, T0 + 3) != null)
}

private fun storeRemoval() = withDb("removal") { _, store ->
    store.ask(A, B); store.accept(B, A, T0 + 1, MAX)
    store.ask(C, D); store.accept(D, C, T0 + 1, MAX)
    check("a player can only remove their own friendship", !store.remove(A, C) && !store.remove(A, D) && store.areFriends(C, D))
    check("removing yourself is a no-op", !store.remove(A, A))
    check("either friend can remove it (offline friend untouched otherwise)", store.remove(B, A) && !store.areFriends(A, B))
    check("removing twice does nothing", !store.remove(A, B))
    check("a pair is stored once whoever added whom", withDb("order") { db, s2 ->
        s2.ask(B, A); s2.accept(A, B, T0 + 1, MAX)
        val rows = db.queryFirst("SELECT COUNT(*) AS n FROM lifesteal_friends") { it.getInt("n") }
        val low = db.queryFirst("SELECT uuid_low FROM lifesteal_friends") { it.getString("uuid_low") }
        rows == 1 && low == A.toString()
    })
    check("the table refuses a reversed or self pair", withDb("constraint") { db, _ ->
        val reversed = runCatching { db.execute("INSERT INTO lifesteal_friends (uuid_low, uuid_high, created_at) VALUES (?, ?, 0)", B.toString(), A.toString()) }.isFailure
        val self = runCatching { db.execute("INSERT INTO lifesteal_friend_requests (sender, target, status, created_at, expires_at) VALUES (?, ?, 'PENDING', 0, 1)", A.toString(), A.toString()) }.isFailure
        reversed && self
    })
}

private fun storePrefs() = withDb("prefs") { db, store ->
    check("missing prefs = defaults", store.prefs(A, DEFAULTS) == DEFAULTS)
    val mine = DEFAULTS.copy(messagesFrom = Audience.FRIENDS, loginNotifications = false)
    store.savePrefs(A, mine, T0)
    check("prefs are saved per player", store.prefs(A, DEFAULTS) == mine && store.prefs(B, DEFAULTS) == DEFAULTS)
    db.execute("UPDATE lifesteal_social_prefs SET status_visible = 'EVERYONE', requests_from = 'garbage' WHERE uuid = ?", A.toString())
    val read = store.prefs(A, DEFAULTS)
    check("a hand-edited row can't widen an option", read.statusVisibleTo == Audience.NOBODY && read.requestsFrom == Audience.EVERYONE)
    check("the chat channel defaults to global", store.channel(B) == Channel.GLOBAL)
    store.saveChannel(B, Channel.TEAM, DEFAULTS, T0)
    check("the chat channel is saved without changing prefs", store.channel(B) == Channel.TEAM && store.prefs(B, DEFAULTS) == DEFAULTS)
}

private fun restarts() {
    val dir = Files.createTempDirectory("joshymc-social-restart").toFile()
    try {
        run {
            val db = DatabaseManager(dir, quietLogger()); db.start()
            val store = FriendStore(db); store.createTables()
            store.ask(A, B); store.accept(B, A, T0 + 1, MAX)
            store.ask(C, A, T0)
            store.savePrefs(A, DEFAULTS.copy(messagesFrom = Audience.NOBODY), T0)
            store.saveChannel(A, Channel.LOCAL, DEFAULTS, T0)
            db.shutdown()
        }
        val db = DatabaseManager(dir, quietLogger()); db.start()
        try {
            val store = FriendStore(db); store.createTables() // IF NOT EXISTS: nothing duplicated
            check("friendships survive a restart", store.areFriends(A, B) && store.friendCount(A) == 1)
            check("requests survive a restart with their original deadline", store.pending(C, A, T0 + 1)?.expiresAt == T0 + 5 * MIN)
            check("requests keep expiring across downtime", store.pending(C, A, T0 + 10 * MIN) == null && store.expire(T0 + 10 * MIN) == 1)
            check("prefs and channel survive a restart", store.prefs(A, DEFAULTS).messagesFrom == Audience.NOBODY && store.channel(A) == Channel.LOCAL)
        } finally {
            db.shutdown()
        }
    } finally {
        dir.deleteRecursively()
    }
}

private fun concurrency() = withDb("concurrency") { _, store ->
    store.ask(A, B)
    val pool = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    val accepted = AtomicInteger()
    repeat(16) {
        pool.submit {
            start.await()
            if (store.accept(B, A, T0 + 1, MAX) == AcceptResult.Accepted) accepted.incrementAndGet()
        }
    }
    start.countDown(); pool.shutdown(); pool.awaitTermination(30, TimeUnit.SECONDS)
    check("concurrent accepts settle exactly once", accepted.get() == 1 && store.friendCount(A) == 1)

    // Both players request each other at the same moment: one friendship, no stray pending rows.
    val pool2 = Executors.newFixedThreadPool(2)
    val go = CountDownLatch(1)
    pool2.submit { go.await(); store.ask(C, D) }
    pool2.submit { go.await(); store.ask(D, C) }
    go.countDown(); pool2.shutdown(); pool2.awaitTermination(30, TimeUnit.SECONDS)
    check("simultaneous mutual requests make one friendship", store.areFriends(C, D) && store.pending(C, D, T0 + 1) == null && store.pending(D, C, T0 + 1) == null)

    // Many senders racing for the last slot: the limit holds.
    val target = UUID.randomUUID()
    val senders = List(10) { UUID.randomUUID() }
    for (s in senders) store.ask(s, target)
    val pool3 = Executors.newFixedThreadPool(8)
    val go3 = CountDownLatch(1)
    for (s in senders) pool3.submit { go3.await(); store.accept(target, s, T0 + 1, MAX) }
    go3.countDown(); pool3.shutdown(); pool3.awaitTermination(30, TimeUnit.SECONDS)
    check("racing accepts never pass the friend limit", store.friendCount(target) == MAX)
}

// ── Wiring ───────────────────────────────────────────────────

private fun wiring() {
    val commands = source("manager/CommandManager.kt")
    val block = commands.substringAfter("if (plugin.lifestealSocialManager.hosted) {").substringBefore("// Issue #1331")
    check("/friend and /channel are only registered where Lifesteal is hosted, released elsewhere",
        "getCommand(\"friend\")" in block && "getCommand(\"channel\")" in block && "unregisterCommand(\"friend\")" in block && "unregisterCommand(\"channel\")" in block)
    val scopes = source("gamemode/GamemodeAssignments.kt")
    val lifesteal = scopes.substringAfter("LIFESTEAL_COMMANDS").substringBefore("val COMMANDS")
    check("/friend and /channel are LIFESTEAL-scoped", "\"friend\", \"channel\"" in lifesteal)
    val yml = resourceText("plugin.yml")
    check("plugin.yml declares /friend (alias friends) and /channel once", Regex("""\n  friend:\n""").findAll(yml).count() == 1 &&
        Regex("""\n  channel:\n""").findAll(yml).count() == 1 && "aliases: [friends]" in yml)
    check("no other command already claims friend/friends/channel/ch", Regex("""aliases: \[[^\]]*\b(friends|ch|channel)\b""").findAll(yml).count() == 2)
    check("permissions are declared", listOf("joshymc.lifesteal.friends.use:", "joshymc.lifesteal.channel.use:", "joshymc.lifesteal.social.bypass-privacy:").all { it in yml })
    check("the privacy bypass is op only", yml.substringAfter("joshymc.lifesteal.social.bypass-privacy:").substringBefore("\n  joshymc.").contains("default: op"))
    val listeners = source("manager/ListenerManager.kt")
    check("the social listener is only registered where Lifesteal is hosted",
        listeners.substringAfter("if (plugin.lifestealSocialManager.hosted) {").substringBefore("}").contains("LifestealSocialListener"))
    val main = source("Joshymc.kt")
    check("the manager refreshes on start and reload and stops at shutdown",
        Regex("lifestealSocialManager.refresh\\(\\)").findAll(main).count() == 2 && "shutdown.step(\"lifestealSocialManager.stop\", { ::lifestealSocialManager.isInitialized })" in main)
    val mgr = source("lifesteal/social/LifestealSocialManager.kt")
    check("nothing is created or read on a server without Lifesteal",
        mgr.substringAfter("fun refresh()").substringBefore("createTables").contains("if (!hosted)"))
    check("every friend action re-checks access when it runs",
        Regex("""fun (addFriend|accept|deny|cancel|remove|list|requests|updatePrefs)\([^)]*\)[^{]*\{\s*\n\s*(friendProblem|val \w+ = resolve)""").findAll(mgr).count() >= 8)
    val menu = source("lifesteal/social/FriendsMenu.kt")
    check("every menu page starts with the access guard", Regex("""fun open\w+\([^)]*\) \{\s*\n\s*if \(!guard\(player\)\) return""").findAll(menu).count() == 5)
    check("request buttons pass the creation time they showed", "social.accept(p, other, request.createdAt)" in menu && "social.deny(p, other, request.createdAt)" in menu)

    // Friends are social only: no other system reads friendships.
    val root = File("src/main/kotlin/com/liam/joshymc")
    val readers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
        .filter { f -> f.readText().let { "lifestealSocialManager" in it || "FriendStore" in it } }
        .map { it.relativeTo(root).path.replace('\\', '/') }
        .filterNot { it.startsWith("lifesteal/social/") }
        .toSet()
    check("only lifecycle, wiring and private messages use the social system: $readers",
        readers == setOf("Joshymc.kt", "manager/CommandManager.kt", "manager/ListenerManager.kt", "command/PrivateMessages.kt", "command/EssentialsCommands.kt"))
    val ess = source("command/EssentialsCommands.kt")
    check("TPA/teleport code never consults friendships", !ess.substringAfter("object TeleportChecks").contains("lifestealSocialManager"))
    val claude = File("CLAUDE.md").readText()
    check("CLAUDE.md documents the manager", "LifestealSocialManager" in claude)
    check("docs exist", File("docs/lifesteal-social.md").isFile)
}
