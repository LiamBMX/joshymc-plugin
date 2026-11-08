package com.liam.joshymc.lifesteal.social

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.IgnoreCommand
import com.liam.joshymc.gamemode.ServerGamemode
import com.liam.joshymc.lifesteal.social.SocialRules.Audience
import com.liam.joshymc.lifesteal.social.SocialRules.Channel
import com.liam.joshymc.lifesteal.social.SocialRules.Prefs
import com.liam.joshymc.lifesteal.social.SocialRules.Settings
import com.liam.joshymc.manager.ChatManager
import com.liam.joshymc.manager.ChatSpamDetector
import com.liam.joshymc.util.Msg
import com.liam.joshymc.util.ProfanityFilter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lifesteal friends, social privacy, private-message privacy and chat channels (issue #1430).
 * Lifesteal only: on a server that doesn't host Lifesteal it never creates tables, registers no
 * commands or listeners, and [appliesToMessage] is always false, so Survival `/msg` is unchanged
 * (apart from the mute check every gamemode now has, see `PrivateMessages`).
 *
 * Every action re-checks the feature switch, the player's world, the permission and the stored
 * rows when it runs (commands and GUI buttons alike); the GUI is never trusted. Friendship is a
 * social relationship only and is never read by teams, claims, teleports, storage or combat.
 */
class LifestealSocialManager(private val plugin: Joshymc) {

    val store = FriendStore(plugin.databaseManager)

    @Volatile var settings: Settings? = null
        private set
    @Volatile private var tablesReady = false
    private var sweepTask: BukkitTask? = null

    @Volatile private var throttle = SocialRules.NotificationThrottle(60_000)
    /** Last friend request per sender (memory only; a restart just lets one more through). */
    private val lastRequest = ConcurrentHashMap<UUID, Long>()
    /** Chat channel of online players, so the async chat event never touches the database. */
    private val channels = ConcurrentHashMap<UUID, Channel>()

    private val spam: ChatSpamDetector by lazy {
        val config = plugin.config
        ChatSpamDetector(
            ChatSpamDetector.settingsOf(
                enabled = config.getBoolean("chat.anti-spam.enabled", true),
                repeatThreshold = config.getInt("chat.anti-spam.repeat-threshold", ChatSpamDetector.DEFAULT_THRESHOLD),
                windowTicks = config.getInt("chat.anti-spam.window-ticks", ChatSpamDetector.DEFAULT_WINDOW_TICKS),
                muteDurationSeconds = config.getLong("chat.anti-spam.mute-duration-seconds", ChatSpamDetector.DEFAULT_MUTE_SECONDS),
                muteReason = config.getString("chat.anti-spam.mute-reason", ChatSpamDetector.DEFAULT_REASON),
            ) { plugin.logger.warning("[Social] config.yml chat.anti-spam.$it") }
        )
    }

    /** This server runs Lifesteal (fixed per reload). */
    val hosted: Boolean get() = ServerGamemode.LIFESTEAL in plugin.networkIdentityManager.hostedGamemodes

    /** Hosted, enabled in config.yml and the tables are ready. */
    val active: Boolean get() = hosted && tablesReady && settings?.enabled == true

    val friendsActive: Boolean get() = active && settings?.friendsEnabled == true

    fun isLifesteal(player: Player): Boolean = plugin.gamemodeLockManager.gamemodeOf(player.world) == ServerGamemode.LIFESTEAL

    // ── Lifecycle ───────────────────────────────────────────

    fun refresh() {
        stop()
        if (!hosted) {
            settings = null
            return
        }
        val parsed = SocialRules.parseSettings({ plugin.config.get("lifesteal-social.$it") }) {
            plugin.logger.warning("[Social] config.yml lifesteal-social.$it")
        }
        settings = parsed
        throttle = SocialRules.NotificationThrottle(parsed.notificationCooldownSeconds * 1000)
        if (!parsed.enabled) {
            plugin.logger.info("[Social] Lifesteal social features are turned off (lifesteal-social.enabled: false); stored friendships are kept.")
            return
        }
        try {
            store.createTables()
            val expired = store.expire(System.currentTimeMillis())
            if (expired > 0) plugin.logger.info("[Social] $expired friend request(s) expired while the server was down.")
            tablesReady = true
        } catch (e: Exception) {
            tablesReady = false
            plugin.logger.severe("[Social] Could not prepare the friends tables, so Lifesteal social features are paused: ${e.message}")
            return
        }
        for (player in Bukkit.getOnlinePlayers()) loadChannel(player)
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            try { store.expire(System.currentTimeMillis()) } catch (e: Exception) { plugin.logger.warning("[Social] Request expiry failed: ${e.message}") }
        }, 1200L, 1200L)
        plugin.logger.info("[Social] Lifesteal friends on: up to ${parsed.maxFriends} friends, requests last ${parsed.requestExpirationSeconds}s.")
    }

    fun stop() {
        sweepTask?.cancel()
        sweepTask = null
        tablesReady = false
        channels.clear()
        lastRequest.clear()
    }

    // ── Shared checks ───────────────────────────────────────

    fun unavailableReason(): String? = when {
        !hosted -> "Friends only exist on the Lifesteal server."
        settings?.enabled != true -> "Social features are turned off right now."
        !tablesReady -> "Social features are unavailable right now. Please try again later."
        else -> null
    }

    /** Null when [player] may use the friends system right now (checked on every action and click). */
    fun friendProblem(player: Player): String? = unavailableReason() ?: when {
        settings?.friendsEnabled != true -> "Friends are turned off right now."
        !isLifesteal(player) -> "Friends only work in Lifesteal."
        !player.hasPermission(PERMISSION_FRIENDS) -> Msg.NO_PERMISSION
        else -> null
    }

    fun refuse(sender: CommandSender, message: String) = Msg.error(sender, message)

    fun send(player: Player, message: Component) = plugin.commsManager.send(player, message)

    fun defaults(): Prefs = settings?.defaults ?: SocialRules.DEFAULT_PREFS

    fun prefs(uuid: UUID): Prefs = store.prefs(uuid, defaults())

    fun nameOf(uuid: UUID): String =
        Bukkit.getPlayer(uuid)?.name ?: plugin.playerResolver.lastKnownName(uuid) ?: Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString().take(8)

    /** Vanished (or otherwise hidden) from [viewer]: staff with vanish see through it, others don't. */
    fun hiddenFrom(viewer: Player, subject: Player): Boolean =
        viewer != subject && (plugin.vanishCommand.isVanished(subject) && !viewer.hasPermission(VANISH_SEE) || !viewer.canSee(subject))

    private fun viewerSeesHidden(viewer: Player): Boolean = viewer.hasPermission(VANISH_SEE)

    /** Whether [viewer] sees [friend] as online in their friend list (vanish and privacy respected). */
    fun showsOnline(viewer: Player, friend: UUID, friendPrefs: Prefs = prefs(friend)): Boolean {
        val online = Bukkit.getPlayer(friend)
        val subjectOnline = online != null && online.isOnline && isLifesteal(online)
        return SocialRules.showsOnline(
            subjectOnline = subjectOnline,
            subjectHidden = online != null && hiddenFrom(viewer, online),
            viewerSeesHidden = viewerSeesHidden(viewer),
            friends = true,
            subjectPrefs = friendPrefs,
        )
    }

    private fun ignoredEitherWay(a: UUID, b: UUID): Boolean = IgnoreCommand.isIgnoring(a, b) || IgnoreCommand.isIgnoring(b, a)

    /** Resolves a player name to someone this server has seen (online first). Never asks Mojang. */
    fun resolve(name: String): OfflinePlayer? = plugin.playerResolver.resolve(name)

    // ── Friend actions (commands and GUI) ───────────────────

    fun addFriend(player: Player, targetName: String) {
        friendProblem(player)?.let { return refuse(player, it) }
        val config = settings ?: return
        val target = resolve(targetName)
        if (target == null) return Msg.playerNotFound(player, targetName)
        val now = System.currentTimeMillis()
        val cooldownMs = config.requestCooldownSeconds * 1000
        val onCooldown = lastRequest[player.uniqueId]?.let { now - it < cooldownMs } == true
        val result = try {
            store.send(
                sender = player.uniqueId,
                target = target.uniqueId,
                now = now,
                expirationMillis = config.requestExpirationMillis,
                maxFriends = config.maxFriends,
                denyCooldownMillis = config.denyCooldownSeconds * 1000,
                defaults = defaults(),
                ignoredEitherWay = ignoredEitherWay(player.uniqueId, target.uniqueId),
                onCooldown = onCooldown,
            )
        } catch (e: Exception) {
            plugin.logger.warning("[Social] Friend request ${player.name} -> ${target.uniqueId} failed: ${e.message}")
            return refuse(player, "Something went wrong. Please try again.")
        }
        val targetLabel = nameOf(target.uniqueId)
        when (result) {
            is FriendStore.SendResult.Refused -> refuse(player, result.refusal.message)
            is FriendStore.SendResult.BecameFriends -> {
                lastRequest[player.uniqueId] = now
                send(player, Component.text("You and $targetLabel are now friends!", NamedTextColor.GREEN))
                notifyAccepted(target.uniqueId, player)
                plugin.logger.info("[Social] ${player.name} and $targetLabel are now friends (mutual request).")
            }
            is FriendStore.SendResult.Sent -> {
                lastRequest[player.uniqueId] = now
                send(player, Component.text("Friend request sent to $targetLabel. It expires in ${formatSeconds(config.requestExpirationSeconds)}.", NamedTextColor.GREEN))
                notifyRequest(target.uniqueId, player)
            }
        }
    }

    fun accept(player: Player, senderName: String) {
        val sender = resolve(senderName) ?: return Msg.playerNotFound(player, senderName)
        accept(player, sender.uniqueId, null)
    }

    /** [expectedCreatedAt] is set by GUI buttons: only the request that button showed can be accepted. */
    fun accept(player: Player, sender: UUID, expectedCreatedAt: Long?): Boolean {
        friendProblem(player)?.let { refuse(player, it); return false }
        val config = settings ?: return false
        val result = try {
            store.accept(player.uniqueId, sender, System.currentTimeMillis(), config.maxFriends, expectedCreatedAt)
        } catch (e: Exception) {
            plugin.logger.warning("[Social] Accept ${sender} -> ${player.name} failed: ${e.message}")
            refuse(player, "Something went wrong. Please try again.")
            return false
        }
        val name = nameOf(sender)
        return when (result) {
            FriendStore.AcceptResult.Accepted -> {
                send(player, Component.text("You and $name are now friends!", NamedTextColor.GREEN))
                notifyAccepted(sender, player)
                plugin.logger.info("[Social] ${player.name} accepted $name's friend request.")
                true
            }
            FriendStore.AcceptResult.Stale -> { refuse(player, "There's no pending friend request from $name (it may have expired)."); false }
            is FriendStore.AcceptResult.Full -> { refuse(player, result.refusal.message); false }
        }
    }

    fun deny(player: Player, senderName: String) {
        val sender = resolve(senderName) ?: return Msg.playerNotFound(player, senderName)
        deny(player, sender.uniqueId, null)
    }

    fun deny(player: Player, sender: UUID, expectedCreatedAt: Long?): Boolean {
        friendProblem(player)?.let { refuse(player, it); return false }
        val done = try { store.deny(player.uniqueId, sender, System.currentTimeMillis(), expectedCreatedAt) } catch (e: Exception) {
            plugin.logger.warning("[Social] Deny failed: ${e.message}"); false
        }
        // The sender isn't told: a denial looks the same as an expired request to them.
        if (done) send(player, Component.text("Friend request from ${nameOf(sender)} denied.", NamedTextColor.YELLOW))
        else refuse(player, "There's no pending friend request from ${nameOf(sender)}.")
        return done
    }

    fun cancel(player: Player, targetName: String) {
        val target = resolve(targetName) ?: return Msg.playerNotFound(player, targetName)
        cancel(player, target.uniqueId)
    }

    fun cancel(player: Player, target: UUID): Boolean {
        friendProblem(player)?.let { refuse(player, it); return false }
        val done = try { store.cancel(player.uniqueId, target, System.currentTimeMillis()) } catch (e: Exception) {
            plugin.logger.warning("[Social] Cancel failed: ${e.message}"); false
        }
        if (done) send(player, Component.text("Friend request to ${nameOf(target)} cancelled.", NamedTextColor.YELLOW))
        else refuse(player, "You have no pending friend request to ${nameOf(target)}.")
        return done
    }

    fun remove(player: Player, friendName: String) {
        val friend = resolve(friendName) ?: return Msg.playerNotFound(player, friendName)
        remove(player, friend.uniqueId)
    }

    /** Ends [player]'s own friendship with [friend]; nobody can remove someone else's friendship. */
    fun remove(player: Player, friend: UUID): Boolean {
        friendProblem(player)?.let { refuse(player, it); return false }
        val done = try { store.remove(player.uniqueId, friend) } catch (e: Exception) {
            plugin.logger.warning("[Social] Remove failed: ${e.message}"); false
        }
        if (done) {
            send(player, Component.text("You are no longer friends with ${nameOf(friend)}.", NamedTextColor.YELLOW))
            plugin.logger.info("[Social] ${player.name} removed ${nameOf(friend)} as a friend.")
        } else refuse(player, "${nameOf(friend)} isn't on your friend list.")
        return done
    }

    fun list(player: Player) {
        friendProblem(player)?.let { return refuse(player, it) }
        val friends = store.friendsOf(player.uniqueId)
        val max = settings?.maxFriends ?: 0
        if (friends.isEmpty()) {
            return send(player, Component.text("You have no friends yet. Add one with /friend add <player>.", NamedTextColor.GRAY))
        }
        val (online, offline) = friends.partition { showsOnline(player, it.friend) }
        send(player, Component.text("Friends (${friends.size}/$max):", NamedTextColor.GOLD))
        if (online.isNotEmpty()) {
            player.sendMessage(Component.text("  Online: ", NamedTextColor.GREEN).append(Component.text(online.joinToString(", ") { nameOf(it.friend) }, NamedTextColor.WHITE)))
        }
        if (offline.isNotEmpty()) {
            player.sendMessage(Component.text("  Offline: ", NamedTextColor.GRAY).append(Component.text(offline.joinToString(", ") { nameOf(it.friend) }, NamedTextColor.WHITE)))
        }
    }

    fun requests(player: Player) {
        friendProblem(player)?.let { return refuse(player, it) }
        val now = System.currentTimeMillis()
        val incoming = store.incoming(player.uniqueId, now)
        val outgoing = store.outgoing(player.uniqueId, now)
        if (incoming.isEmpty() && outgoing.isEmpty()) return send(player, Component.text("You have no pending friend requests.", NamedTextColor.GRAY))
        if (incoming.isNotEmpty()) {
            send(player, Component.text("Incoming friend requests:", NamedTextColor.GOLD))
            for (r in incoming) {
                val name = nameOf(r.sender)
                player.sendMessage(Component.text("  $name ", NamedTextColor.WHITE)
                    .append(button("[Accept]", NamedTextColor.GREEN, "/friend accept $name"))
                    .append(Component.text(" "))
                    .append(button("[Deny]", NamedTextColor.RED, "/friend deny $name")))
            }
        }
        if (outgoing.isNotEmpty()) {
            send(player, Component.text("Outgoing friend requests:", NamedTextColor.GOLD))
            for (r in outgoing) {
                val name = nameOf(r.target)
                player.sendMessage(Component.text("  $name ", NamedTextColor.WHITE).append(button("[Cancel]", NamedTextColor.YELLOW, "/friend cancel $name")))
            }
        }
    }

    /** Changes [player]'s own preferences only (there is no way to change someone else's). */
    fun updatePrefs(player: Player, change: (Prefs) -> Prefs): Prefs? {
        friendProblem(player)?.let { refuse(player, it); return null }
        return try {
            val updated = change(prefs(player.uniqueId)).normalized()
            store.savePrefs(player.uniqueId, updated, System.currentTimeMillis())
            updated
        } catch (e: Exception) {
            plugin.logger.warning("[Social] Saving ${player.name}'s preferences failed: ${e.message}")
            refuse(player, "Something went wrong. Please try again.")
            null
        }
    }

    // ── Notifications ───────────────────────────────────────

    private fun notifyRequest(target: UUID, from: Player) {
        val online = Bukkit.getPlayer(target) ?: return
        if (!online.isOnline || !isLifesteal(online)) return
        if (!prefs(target).requestNotifications) return
        if (!throttle.allow(target, from.uniqueId, "request", System.currentTimeMillis())) return
        send(online, Component.text("${from.name} sent you a friend request. ", NamedTextColor.GOLD)
            .append(button("[Accept]", NamedTextColor.GREEN, "/friend accept ${from.name}"))
            .append(Component.text(" "))
            .append(button("[Deny]", NamedTextColor.RED, "/friend deny ${from.name}")))
    }

    private fun notifyAccepted(requester: UUID, by: Player) {
        val online = Bukkit.getPlayer(requester) ?: return
        if (!online.isOnline || !isLifesteal(online)) return
        if (!prefs(requester).requestNotifications) return
        if (!throttle.allow(requester, by.uniqueId, "accepted", System.currentTimeMillis())) return
        send(online, Component.text("${by.name} accepted your friend request. You're now friends!", NamedTextColor.GREEN))
    }

    fun onJoin(player: Player) {
        loadChannel(player)
        if (!friendsActive || !isLifesteal(player)) return
        try {
            val incoming = store.incoming(player.uniqueId, System.currentTimeMillis())
            if (incoming.isNotEmpty() && prefs(player.uniqueId).requestNotifications) {
                send(player, Component.text("You have ${incoming.size} pending friend request(s). ", NamedTextColor.GRAY).append(button("[View]", NamedTextColor.YELLOW, "/friend requests")))
            }
            announcePresence(player, joined = true)
        } catch (e: Exception) {
            plugin.logger.warning("[Social] Join notices for ${player.name} failed: ${e.message}")
        }
    }

    fun onQuit(player: Player) {
        spam.forget(player.uniqueId)
        channels.remove(player.uniqueId)
        lastRequest.remove(player.uniqueId)
        if (!friendsActive || !isLifesteal(player)) return
        try { announcePresence(player, joined = false) } catch (e: Exception) {
            plugin.logger.warning("[Social] Quit notices for ${player.name} failed: ${e.message}")
        }
    }

    /** Tells online friends that [player] joined/left, never for a hidden join, throttled per friend. */
    private fun announcePresence(player: Player, joined: Boolean) {
        val subjectPrefs = prefs(player.uniqueId)
        val hidesJoin = plugin.vanishCommand.hidesJoinLeave(player)
        val now = System.currentTimeMillis()
        for (friendship in store.friendsOf(player.uniqueId)) {
            val friend = Bukkit.getPlayer(friendship.friend) ?: continue
            if (!friend.isOnline || friend == player || !isLifesteal(friend)) continue
            if (!SocialRules.notifiesLogin(hidesJoin, subjectPrefs, prefs(friend.uniqueId))) continue
            if (!throttle.allow(friend.uniqueId, player.uniqueId, if (joined) "join" else "quit", now)) continue
            val line = if (joined) Component.text("Friend ", NamedTextColor.GREEN).append(Component.text(player.name, NamedTextColor.WHITE)).append(Component.text(" joined.", NamedTextColor.GREEN))
            else Component.text("Friend ", NamedTextColor.GRAY).append(Component.text(player.name, NamedTextColor.WHITE)).append(Component.text(" left.", NamedTextColor.GRAY))
            send(friend, line)
        }
    }

    // ── Private messages (called by PrivateMessages for /msg, /tell, /w, /r and every alias) ──

    /** Lifesteal privacy applies when either player is in a Lifesteal world here. */
    fun appliesToMessage(sender: Player, target: Player): Boolean =
        active && settings?.messagingEnabled == true && (isLifesteal(sender) || isLifesteal(target))

    /** The Lifesteal privacy verdict for a private message, or null when it may be delivered. */
    fun messageProblem(sender: Player, target: Player, answering: Boolean = false): SocialRules.MessageRefusal? {
        val friends = try { store.areFriends(sender.uniqueId, target.uniqueId) } catch (_: Exception) { false }
        val targetPrefs = try { prefs(target.uniqueId) } catch (_: Exception) { defaults() }
        return SocialRules.messageProblem(
            sender = sender.uniqueId,
            target = target.uniqueId,
            targetHiddenFromSender = hiddenFrom(sender, target),
            targetIgnoresSender = IgnoreCommand.isIgnoring(target.uniqueId, sender.uniqueId),
            senderIgnoresTarget = IgnoreCommand.isIgnoring(sender.uniqueId, target.uniqueId),
            friends = friends,
            targetPrefs = targetPrefs,
            bypassPrivacy = sender.hasPermission(PERMISSION_BYPASS_PRIVACY),
            answering = answering,
        )
    }

    /**
     * The repeat rule and profanity filter public chat uses, for private messages and the channel
     * commands. Null = refused (the player was told).
     */
    fun moderate(player: Player, message: String, checkSpam: Boolean = true): String? {
        if (checkSpam && !player.hasPermission(SPAM_BYPASS) && spam.record(player.uniqueId, message, Bukkit.getCurrentTick())) {
            refuse(player, "Please don't repeat the same message.")
            return null
        }
        if (!player.hasPermission(PROFANITY_BYPASS) && ProfanityFilter.contains(message)) {
            send(player, Component.text("Your message contained banned words and was censored.", NamedTextColor.RED))
            return ProfanityFilter.censor(message)
        }
        return message
    }

    // ── Chat channels ───────────────────────────────────────

    fun channelOf(uuid: UUID): Channel = channels[uuid] ?: Channel.GLOBAL

    private fun loadChannel(player: Player) {
        if (!active) return
        try {
            val stored = store.channel(player.uniqueId)
            if (stored == Channel.GLOBAL) channels.remove(player.uniqueId) else channels[player.uniqueId] = stored
        } catch (e: Exception) {
            channels.remove(player.uniqueId)
        }
    }

    fun channelProblem(player: Player, channel: Channel): String? {
        unavailableReason()?.let { return it }
        val config = settings ?: return "Chat channels are unavailable right now."
        if (!isLifesteal(player)) return "Chat channels only work in Lifesteal."
        if (!player.hasPermission(PERMISSION_CHANNEL)) return Msg.NO_PERMISSION
        val teams = plugin.lifestealTeamManager
        val team = teams.teamOf(player.uniqueId)
        val allied = team != null && teams.relations.hasAlliance(team.id)
        return SocialRules.channelProblem(channel, config.channels, inTeam = team != null && teams.active, teamAllied = allied)
    }

    fun setChannel(player: Player, channel: Channel) {
        channelProblem(player, channel)?.let { return refuse(player, it) }
        try {
            store.saveChannel(player.uniqueId, channel, defaults(), System.currentTimeMillis())
        } catch (e: Exception) {
            plugin.logger.warning("[Social] Saving ${player.name}'s chat channel failed: ${e.message}")
            return refuse(player, "Something went wrong. Please try again.")
        }
        if (channel == Channel.GLOBAL) channels.remove(player.uniqueId) else channels[player.uniqueId] = channel
        send(player, Component.text("You're now talking in ${channel.label} chat.", NamedTextColor.GREEN))
    }

    /**
     * Whether the async chat event of [player] belongs to a private channel (so it must be cancelled
     * and never reach public chat or Discord). Memory only.
     */
    fun routesChat(player: Player): Boolean = active && channelOf(player.uniqueId) != Channel.GLOBAL && isLifesteal(player)

    /**
     * Delivers [rawMessage] in [channel] (main thread). [fromChat] = typed in chat with a channel
     * selected: mutes, the repeat rule and the profanity filter already ran on the chat event, so
     * only the mute is checked again. A message is never sent to another channel as a fallback.
     */
    fun speak(player: Player, channel: Channel, rawMessage: String, fromChat: Boolean) {
        if (!player.isOnline) return
        val message = rawMessage.trim()
        if (message.isEmpty()) return
        if (channel == Channel.GLOBAL) {
            // Public chat has its own pipeline; it is reached by switching back, never forwarded from here.
            return refuse(player, "Switch to Global chat with /channel global, then type in chat.")
        }
        channelProblem(player, channel)?.let {
            refuse(player, "$it Your message wasn't sent.")
            if (fromChat) send(player, Component.text("Switch back with /channel global.", NamedTextColor.GRAY))
            return
        }
        if (channel == Channel.ALLIANCE) {
            // The existing /ac path: alliance checks, mutes, filter, repeat rule and ignore lists.
            plugin.lifestealTeamManager.relations.allianceChat(player, message)
            return
        }
        plugin.punishmentManager.isMutedIn(player.uniqueId, ServerGamemode.LIFESTEAL)?.let {
            return refuse(player, "You are muted" + (it.reason?.let { reason -> ": $reason" } ?: "."))
        }
        if (plugin.chatManager.isMuted && !player.hasPermission(ChatManager.PERM_MUTE_BYPASS)) return refuse(player, "Chat is currently muted.")
        val clean = if (fromChat) message else moderate(player, message) ?: return
        val config = settings?.channels ?: return
        val teams = plugin.lifestealTeamManager
        val ignores: (UUID, UUID) -> Boolean = { a, b -> IgnoreCommand.isIgnoring(a, b) }
        val (recipients, format, tag) = when (channel) {
            Channel.TEAM -> {
                val team = teams.teamOf(player.uniqueId) ?: return refuse(player, "You're not in a Lifesteal team. Your message wasn't sent.")
                val ids = SocialRules.teamRecipients(player.uniqueId, teams.roster(team.id), { uuid -> Bukkit.getPlayer(uuid)?.let { isLifesteal(it) } == true }, ignores)
                Triple(ids, SocialRules.fill(config.teamFormat, team.name, player.name), "TeamChat/${team.name}")
            }
            Channel.LOCAL -> {
                val worldName = player.world.name
                val nearby = player.world.players.filter { it == player || isLifesteal(it) }.map {
                    val l = it.location
                    SocialRules.Nearby(it.uniqueId, worldName, l.x, l.y, l.z)
                }
                val ids = SocialRules.localRecipients(player.uniqueId, nearby, config.localRadius, ignores)
                Triple(ids, SocialRules.fill(config.localFormat, "", player.name), "LocalChat")
            }
            else -> return
        }
        val line = plugin.commsManager.parseLegacy(format.first)
            .append(Component.text(clean))
            .append(plugin.commsManager.parseLegacy(format.second))
        for (uuid in recipients) {
            val target = Bukkit.getPlayer(uuid) ?: continue
            if (!target.isOnline) continue
            target.sendMessage(line)
        }
        if (channel == Channel.LOCAL && recipients.size <= 1) {
            send(player, Component.text("Nobody is close enough to hear you.", NamedTextColor.GRAY))
        }
        plugin.logger.info("[$tag] ${player.name}: $clean")
    }

    // ── Helpers ─────────────────────────────────────────────

    private fun button(label: String, color: NamedTextColor, command: String): Component =
        Component.text(label, color)
            .clickEvent(ClickEvent.runCommand(command))
            .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)))

    companion object {
        const val PERMISSION_FRIENDS = "joshymc.lifesteal.friends.use"
        const val PERMISSION_CHANNEL = "joshymc.lifesteal.channel.use"
        /** Staff only (op): may message players whose privacy setting would refuse them (ignores still apply). */
        const val PERMISSION_BYPASS_PRIVACY = "joshymc.lifesteal.social.bypass-privacy"
        private const val VANISH_SEE = "joshymc.vanish"
        private const val PROFANITY_BYPASS = "joshymc.chat.profanity.bypass"
        private const val SPAM_BYPASS = "joshymc.antispam.bypass"

        fun formatSeconds(seconds: Long): String = when {
            seconds % 3600 == 0L && seconds >= 3600 -> "${seconds / 3600}h"
            seconds % 60 == 0L && seconds >= 60 -> "${seconds / 60}m"
            else -> "${seconds}s"
        }
    }
}
