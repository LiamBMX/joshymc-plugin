package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.PunishmentManager
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.events.session.SessionRecreateEvent
import net.dv8tion.jda.api.events.session.SessionResumeEvent
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.messages.MessageRequest
import java.time.Duration
import java.time.Instant
import java.util.EnumSet

class DiscordManager(private val plugin: Joshymc) {

    /** Connection lifecycle (issue #1153). Only one bot connection may exist per plugin instance. */
    enum class ConnectionState { STOPPED, CONNECTING, CONNECTED, STOPPING }

    /** The connected bot. Null until the connection is ready, and again once [shutdown] starts. */
    @Volatile
    var jda: JDA? = null
        private set

    @Volatile
    var state: ConnectionState = ConnectionState.STOPPED
        private set

    /** Guards [state], [connectingJda] and [generation]. */
    private val lifecycleLock = Any()
    /** The JDA instance still connecting, owned here so [shutdown] can stop it mid-handshake. */
    private var connectingJda: JDA? = null
    /** The connection [shutdown] is flushing, so its send callbacks still report until it closes. */
    @Volatile private var drainingJda: JDA? = null
    /** Bumped by every [start] and [shutdown]; a connect attempt whose generation is stale discards its JDA. */
    private var generation = 0L
    private val scheduledTaskIds = mutableListOf<Int>()

    private sealed class QueuedAction {
        data class Text(val content: String) : QueuedAction()
        data class Embed(val embed: MessageEmbed, val channelId: String? = null) : QueuedAction()
    }

    /**
     * Outgoing messages (issue #1159). Bounded, and filled while the bot is CONNECTING as well
     * as CONNECTED, so alerts and reports raised during startup or a reconnect are delivered
     * once it is ready instead of being dropped. Size and chat expiry: `discord.delivery-queue`.
     */
    private val messageQueue = DiscordDeliveryQueue<QueuedAction>(
        capacity = { plugin.config.getInt("discord.delivery-queue.max-size", 500).coerceIn(10, 10_000) },
        routineMaxAgeMs = { plugin.config.getLong("discord.delivery-queue.chat-max-age-seconds", 300L).coerceAtLeast(0L) * 1000L }
    )
    /** When the last queue-discard warning was logged, so a long outage logs once a minute at most. */
    @Volatile private var lastDiscardLog = 0L

    private val updateHighlights = UpdateHighlightsAnnouncer(plugin)

    /** `discord.channels.chat` (issue #1161; see [DiscordConfig] for the deprecated keys it replaced). */
    val channelId: String get() = DiscordConfig.channelId(plugin.config, DiscordConfig.Channel.CHAT)
    val chatFormat: String get() = plugin.config.getString("discord.chat-format") ?: "**{player}** » {message}"
    val minecraftFormat: String get() = plugin.config.getString("discord.minecraft-format") ?: "&9[Discord] &f{name} &7» &f{message}"

    private val punishmentSyncEnabled: Boolean get() = plugin.config.getBoolean("discord.punishments.enabled", false)
    private val punishmentForumChannelId: String get() =
        DiscordConfig.channelId(plugin.config, DiscordConfig.Channel.PUNISHMENTS_FORUM)

    /** Missing/unusable channel warnings, once per channel and problem (issue #1161). */
    private val channelHealth = DiscordChannelHealth(
        warn = { plugin.logger.warning(it) },
        info = { plugin.logger.info(it) }
    )

    /** Durable punishment log delivery (issue #1157); its table is created by PunishmentManager.start(). */
    val punishmentLogs = PunishmentLogOutbox(
        plugin,
        forumChannelId = { punishmentForumChannelId },
        buildEmbed = ::buildPunishmentEmbed,
        describe = { "${actionLabel(it.action)} for ${it.targetName} (${it.targetUuid})" }
    )

    /** Wakes punishment delivery when JDA's own gateway reconnect brings the bot back. */
    private val sessionListener = object : ListenerAdapter() {
        override fun onSessionResume(event: SessionResumeEvent) = punishmentLogs.onReconnected()
        override fun onSessionRecreate(event: SessionRecreateEvent) = punishmentLogs.onReconnected()
    }

    /** When the last failed-connection line was logged, so a long outage doesn't flood the console. */
    @Volatile private var lastConnectFailureLog = 0L

    private val staffMonitoringEnabled: Boolean get() = plugin.config.getBoolean("discord.staff-monitoring.enabled", false)
    private val staffAnticheatChannelId: String get() =
        DiscordConfig.channelId(plugin.config, DiscordConfig.Channel.STAFF_ANTICHEAT).takeUnless(::isPlaceholder).orEmpty()
    private val staffReportsChannelId: String get() =
        DiscordConfig.channelId(plugin.config, DiscordConfig.Channel.STAFF_REPORTS).takeUnless(::isPlaceholder).orEmpty()

    /** The chat bridge is optional: staff monitoring keeps working without it (issue #1068). */
    private val chatChannelConfigured: Boolean get() = !isPlaceholder(channelId)

    /** Empty or the all-zero id from the bundled config: that destination is off. */
    private fun isPlaceholder(id: String): Boolean = id.isEmpty() || id.all { it == '0' }

    /**
     * The Discord features that are switched on with a channel to post to (issue #1159). The
     * bot connects when any of them is, so punishment logs, update highlights or alert feeds
     * work without the public chat bridge or staff monitoring. Each feature still checks its
     * own toggles when it sends; this only decides whether connecting is worth it.
     */
    private fun enabledFeatures(): List<String> {
        val cfg = plugin.config
        return buildList {
            if (chatChannelConfigured) add("chat bridge")
            if (staffMonitoringEnabled && (channelSet(DiscordConfig.Channel.STAFF_ANTICHEAT.key, staffAnticheatChannelId) or
                    channelSet(DiscordConfig.Channel.STAFF_REPORTS.key, staffReportsChannelId))) add("staff monitoring")
            if (punishmentSyncEnabled && channelSet("discord.channels.punishments-forum", punishmentForumChannelId)) add("punishment logs")
            if (updateHighlights.enabled && plugin.networkIdentityManager.isPrimary && channelSet("update-highlights.discord-channel", updateHighlights.channelId)) add("update highlights")
            if (cfg.getBoolean("anti-dupe.enabled", true) && cfg.getBoolean("anti-dupe.discord.enabled", true) &&
                channelSet("anti-dupe.discord.channel-id", cfg.getString("anti-dupe.discord.channel-id"))) add("anti-dupe alerts")
            if (cfg.getBoolean("combat-alerts.enabled", true) && cfg.getBoolean("combat-alerts.discord.enabled", true) &&
                channelSet("combat-alerts.discord.channel-id", cfg.getString("combat-alerts.discord.channel-id"))) add("combat alerts")
            if (cfg.getBoolean("pvp-kill-logs.enabled", true) &&
                channelSet("pvp-kill-logs.discord.channel-id", cfg.getString("pvp-kill-logs.discord.channel-id"))) add("PvP kill logs")
            if (cfg.getBoolean("ancient-debris-monitor.enabled", true) &&
                channelSet("ancient-debris-monitor.discord-channel-id", cfg.getString("ancient-debris-monitor.discord-channel-id"))) add("Ancient Debris monitor")
        }
    }

    /** A real channel id: set, not the all-zero placeholder, and numeric (warned about otherwise). */
    private fun channelSet(key: String, id: String?): Boolean {
        val value = id?.trim().orEmpty()
        if (value.isEmpty() || value.all { it == '0' }) return false
        if (!value.all { it.isDigit() }) {
            plugin.logger.warning("[Discord] $key \"$value\" is not a channel id (expected digits only) - that feature won't post.")
            return false
        }
        return true
    }

    /** [announceStartup] is true only for a real server start, so /joshymc reload doesn't post "Server started". */
    fun start(announceStartup: Boolean = true) {
        // Issue #1323: one bot per network. A secondary connects only with its own bot token,
        // opted into via network.secondary-discord-bot; its punishment logs stay in its database.
        if (!plugin.networkIdentityManager.discordBotAllowed) {
            plugin.logger.info("[Discord] Not connecting: this is a secondary network instance (network.primary: false). Set network.secondary-discord-bot: true only if discord.token is this server's own bot.")
            return
        }
        val token = plugin.config.getString("discord.token") ?: ""
        if (token.isEmpty() || token == "YOUR_BOT_TOKEN_HERE") {
            plugin.logger.warning("[Discord] Bot token not set in config.yml — integration disabled.")
            return
        }

        // Any feature that posts to its own channel is reason enough to connect; the chat
        // bridge is just one of them (issue #1159).
        val features = enabledFeatures()
        if (features.isEmpty()) {
            plugin.logger.warning("[Discord] No Discord feature is enabled with a channel set in config.yml — integration disabled.")
            discardQueued("no Discord feature is enabled")
            return
        }
        if (!chatChannelConfigured) {
            plugin.logger.warning("[Discord] Channel ID not set in config.yml — chat bridge disabled; connecting for: ${features.joinToString()}.")
        }

        val attempt = synchronized(lifecycleLock) {
            if (state != ConnectionState.STOPPED) {
                plugin.logger.warning("[Discord] start() ignored: the bot is already $state.")
                return
            }
            state = ConnectionState.CONNECTING
            ++generation
        }

        // Outgoing messages never ping anyone unless a request opts in explicitly, so
        // player-controlled text (chat, death messages, item and mob names) can't reach
        // @everyone, roles or users.
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType::class.java))
        MessageRequest.setDefaultMentionRepliedUser(false)

        plugin.logger.info("[Discord] Connecting bot...")
        connect(token, attempt, announceStartup, failures = 0)
    }

    /**
     * One connection attempt for the [start] call identified by [attempt]. A failed attempt is
     * retried with bounded exponential backoff (issue #1157) while state stays CONNECTING, so
     * there is never a second attempt or JDA running alongside it. Once connected, gateway
     * drops are left to JDA's own reconnect.
     */
    private fun connect(token: String, attempt: Long, announceStartup: Boolean, failures: Int) {
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            if (!isConnecting(attempt)) return@Runnable
            var built: JDA? = null
            try {
                built = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES)
                    .addEventListeners(DiscordChatListener(plugin), sessionListener)
                    .build()
                // Own the connecting instance before blocking on it, so a reload or shutdown
                // that lands mid-handshake shuts it down instead of leaking it.
                val owned = synchronized(lifecycleLock) {
                    if (generation == attempt) connectingJda = built
                    generation == attempt
                }
                if (!owned) {
                    detachListeners(built)
                    built.shutdownNow()
                    return@Runnable
                }

                built.awaitReady()

                // Publish only if no shutdown/restart happened while we waited.
                val published = synchronized(lifecycleLock) {
                    if (generation == attempt) {
                        connectingJda = null
                        jda = built
                        state = ConnectionState.CONNECTED
                    }
                    generation == attempt
                }
                if (!published) {
                    detachListeners(built)
                    built.shutdownNow()
                    return@Runnable
                }

                plugin.logger.info("[Discord] Bot connected as ${built.selfUser.name}" + if (failures > 0) " after ${failures + 1} attempts" else "")
                // Under the lifecycle lock: a shutdown() racing this either stops the worker or prevents it.
                synchronized(lifecycleLock) { if (generation == attempt) punishmentLogs.start(built) }

                // A new connection reports each channel's problems afresh.
                channelHealth.clear()

                // A missing chat channel only disables the chat bridge; staff alerts and
                // reports still go to their own channels.
                if (chatChannelConfigured) {
                    val channel = resolveChannel(built, channelId, CHAT_LABEL)
                    if (channel != null) plugin.logger.info("[Discord] Bound to channel #${channel.name} (${channel.id})")
                }

                registerSlashCommands(built)

                verifyStaffMonitoringChannels(built)
                if (isCurrent(attempt)) updateHighlights.announce(built)

                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (!isCurrent(attempt)) return@Runnable
                    startFlushTask()
                    startStatusUpdater()
                    plugin.logger.info("[Discord] Message queue started.")
                })

                // Only a real server start announces itself; /joshymc reload reconnects quietly.
                if (announceStartup) send(":green_circle: **Server started**")
            } catch (e: Exception) {
                val retry = e !is InvalidTokenException
                val current = synchronized(lifecycleLock) {
                    if (generation == attempt) {
                        connectingJda = null
                        jda = null
                        // Stays CONNECTING while a retry is pending, so start() can't open a second connection.
                        state = if (retry) ConnectionState.CONNECTING else ConnectionState.STOPPED
                    }
                    generation == attempt
                }
                try { built?.shutdownNow() } catch (_: Exception) {}
                // A cancelled attempt was stopped by shutdown().
                if (!current) return@Runnable
                // A connection that got as far as starting delivery must not keep its worker.
                punishmentLogs.stop()
                if (!retry) {
                    plugin.logger.severe("[Discord] The bot token was rejected - fix discord.token and run /joshymc reload. Punishment logs stay queued.")
                    discardQueued("the bot token was rejected")
                    return@Runnable
                }
                scheduleReconnect(token, attempt, announceStartup, failures + 1, e)
            }
        })
    }

    private fun scheduleReconnect(token: String, attempt: Long, announceStartup: Boolean, failures: Int, error: Exception) {
        val delaySeconds = (RECONNECT_BASE_SECONDS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(RECONNECT_MAX_SECONDS)
        val now = System.currentTimeMillis()
        // Every early failure is logged (the first with its stack trace); at the cap, only every 30 minutes.
        if (delaySeconds < RECONNECT_MAX_SECONDS || now - lastConnectFailureLog >= 30 * 60_000L) {
            lastConnectFailureLog = now
            plugin.logger.severe("[Discord] Failed to connect (attempt $failures): ${error.message} - retrying in ${delaySeconds}s.")
            if (failures == 1) error.printStackTrace()
        }
        try {
            val task = plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
                connect(token, attempt, announceStartup, failures)
            }, delaySeconds * 20L)
            synchronized(scheduledTaskIds) { scheduledTaskIds.add(task.taskId) }
        } catch (_: Exception) {
            // The plugin is disabling: nothing to retry into.
            synchronized(lifecycleLock) { if (generation == attempt) state = ConnectionState.STOPPED }
        }
    }

    private fun isConnecting(attempt: Long): Boolean =
        synchronized(lifecycleLock) { generation == attempt && state == ConnectionState.CONNECTING }

    private fun isCurrent(attempt: Long): Boolean =
        synchronized(lifecycleLock) { generation == attempt && state == ConnectionState.CONNECTED }

    /**
     * Disconnects the bot without ever waiting on Discord unbounded. Queued messages, plus the
     * "Server stopped" notice when [serverStopping] (never on /joshymc reload), are handed to
     * JDA's async requester, then JDA gets a few seconds to drain before it is cut off. If the
     * bot never connected, a reload keeps the queue for the next connection and a server stop
     * discards it with a log line. A connection still handshaking is cancelled and can never
     * publish itself afterwards.
     */
    fun shutdown(serverStopping: Boolean = true) {
        val active: JDA?
        val pending: JDA?
        synchronized(lifecycleLock) {
            generation++
            state = ConnectionState.STOPPING
            active = jda
            pending = connectingJda
            jda = null
            connectingJda = null
            drainingJda = active
        }
        try {
            cancelScheduledTasks()
            // Unsent punishment logs stay in the database for the next connection.
            punishmentLogs.stop()

            try {
                pending?.let { detachListeners(it); it.shutdownNow() }
            } catch (e: Exception) {
                plugin.logger.warning("[Discord] Failed to cancel the connecting bot: ${e.message}")
            }

            if (active == null && serverStopping) {
                // Never connected: nothing can deliver what's queued. A reload keeps it for the
                // next connection instead.
                discardQueued("the server stopped before the bot connected")
            }

            if (active != null) {
                // No gateway event (chat, DMs, slash commands, session resumes) reaches plugin
                // code from here on, even while JDA drains its last requests (issue #1161).
                detachListeners(active)
                try {
                    // Best effort: whatever was still queued (on reload too, so pending alerts
                    // aren't lost), then the stop notice on a real server stop. New messages
                    // were refused from the moment state became STOPPING.
                    flushQueue(active)
                    if (serverStopping) chatChannel(active)?.sendMessage(":red_circle: **Server stopped**")?.queue(null) { }
                } catch (e: Exception) {
                    plugin.logger.warning("[Discord] Could not send the shutdown notice: ${e.message}")
                }
                try {
                    // shutdown() lets already-queued requests finish; shutdownNow() cancels them.
                    active.shutdown()
                    if (!active.awaitShutdown(Duration.ofSeconds(if (serverStopping) 5L else 2L))) {
                        plugin.logger.warning("[Discord] Discord did not respond in time - closing the bot connection now.")
                        active.shutdownNow()
                    }
                } catch (_: InterruptedException) {
                    active.shutdownNow()
                    Thread.currentThread().interrupt()
                } catch (e: Exception) {
                    plugin.logger.warning("[Discord] Error while disconnecting the bot: ${e.message}")
                    try { active.shutdownNow() } catch (_: Exception) {}
                }
            }
        } finally {
            drainingJda = null
            synchronized(lifecycleLock) { state = ConnectionState.STOPPED }
        }
    }

    private fun detachListeners(bot: JDA) {
        try {
            val listeners = bot.registeredListeners
            if (listeners.isNotEmpty()) bot.removeEventListener(*listeners.toTypedArray())
        } catch (e: Exception) {
            plugin.logger.warning("[Discord] Could not detach the bot's event listeners: ${e.message}")
        }
    }

    private fun cancelScheduledTasks() {
        val ids = synchronized(scheduledTaskIds) { scheduledTaskIds.toList().also { scheduledTaskIds.clear() } }
        for (id in ids) plugin.server.scheduler.cancelTask(id)
    }

    fun send(content: String) {
        if (!chatChannelConfigured) return
        enqueue(QueuedAction.Text(content), alert = false)
    }

    fun sendEmbed(embed: MessageEmbed) {
        if (!chatChannelConfigured) return
        enqueue(QueuedAction.Embed(embed), alert = false)
    }

    /**
     * Same as [sendEmbed] but targets an arbitrary channel instead of the default
     * chat-bridge channel (e.g. a dedicated anti-dupe alert channel). Queued and
     * flushed the same way, so a bad channel id can never block the main thread.
     */
    fun sendEmbedToChannel(targetChannelId: String, embed: MessageEmbed) {
        if (targetChannelId.isEmpty()) return
        enqueue(QueuedAction.Embed(embed, targetChannelId), alert = true)
    }

    /**
     * Queues [action] while the bot is connected or still connecting (issue #1159); a stopped
     * bot (disabled, token rejected, shutting down) takes nothing, so the queue can't fill
     * up with messages nobody will send.
     */
    private fun enqueue(action: QueuedAction, alert: Boolean) {
        val current = state
        if (current != ConnectionState.CONNECTING && current != ConnectionState.CONNECTED) return
        messageQueue.offer(action, alert)
        reportDiscards()
    }

    /** One warning a minute at most for messages the queue had to throw away. */
    private fun reportDiscards() {
        val now = System.currentTimeMillis()
        if (now - lastDiscardLog < 60_000L) return
        val discards = messageQueue.takeDiscards() ?: return
        lastDiscardLog = now
        val parts = buildList {
            if (discards.alertOverflow > 0) add("${discards.alertOverflow} alert(s) (queue full)")
            if (discards.routineOverflow > 0) add("${discards.routineOverflow} chat message(s) (queue full)")
            if (discards.routineExpired > 0) add("${discards.routineExpired} chat message(s) older than the chat age limit")
        }
        plugin.logger.warning("[Discord] Dropped ${parts.joinToString(", ")} while the bot was unavailable (discord.delivery-queue).")
    }

    /** Drops everything queued, logging what was lost and why. */
    private fun discardQueued(reason: String) {
        val (alerts, routine) = messageQueue.clear()
        messageQueue.takeDiscards()
        if (alerts + routine > 0) plugin.logger.warning("[Discord] Discarded $alerts queued alert(s) and $routine chat message(s): $reason.")
    }

    /**
     * True when [id] is the staff anti-cheat channel and staff monitoring will post there,
     * so a feature mirroring the same detection can skip its own copy into it (issue #1159).
     */
    fun isStaffAlertChannel(id: String): Boolean =
        staffMonitoringEnabled && id.isNotEmpty() && id == staffAnticheatChannelId

    /**
     * Staff-monitoring alert (anti-cheat, dupe, mining). Goes to the dedicated staff guild's
     * anti-cheat channel, addressed by exact channel id so it works across guilds on the one
     * bot connection. [alreadySentTo] is the channel the caller's own feature already posted
     * this embed to; if it matches, the staff copy is skipped so nothing posts twice.
     */
    fun sendStaffAlert(embed: MessageEmbed, alreadySentTo: String? = null) {
        val id = staffAnticheatChannelId
        if (!staffMonitoringEnabled || id.isEmpty() || id == alreadySentTo) return
        sendEmbedToChannel(id, embed)
    }

    /** Staff-monitoring player report embed, sent to the dedicated reports channel only. */
    fun sendStaffReport(embed: MessageEmbed) {
        val id = staffReportsChannelId
        if (!staffMonitoringEnabled || id.isEmpty()) return
        sendEmbedToChannel(id, embed)
    }

    /** Logs where each staff-monitoring channel is bound, or (once) why the bot can't post there. */
    private fun verifyStaffMonitoringChannels(bot: JDA) {
        if (!staffMonitoringEnabled) return
        for (channel in listOf(DiscordConfig.Channel.STAFF_ANTICHEAT, DiscordConfig.Channel.STAFF_REPORTS)) {
            val id = if (channel == DiscordConfig.Channel.STAFF_ANTICHEAT) staffAnticheatChannelId else staffReportsChannelId
            if (id.isEmpty()) {
                plugin.logger.warning("[Discord] ${channel.key} is not set — those alerts will not be sent.")
                continue
            }
            val resolved = resolveChannel(bot, id, channel.key) ?: continue
            plugin.logger.info("[Discord] ${channel.key} bound to #${resolved.name} (${resolved.guild.name}).")
        }
    }

    /** Registers /online in `discord.guild-id` (issue #1161: no longer hardcoded). */
    private fun registerSlashCommands(bot: JDA) {
        val guildId = DiscordConfig.guildId(plugin.config)
        if (guildId.isEmpty()) {
            plugin.logger.info("[Discord] ${DiscordConfig.GUILD_ID_KEY} is empty - slash commands are not registered.")
            return
        }
        val guild = try { bot.getGuildById(guildId) } catch (_: NumberFormatException) { null }
        if (guild == null) {
            plugin.logger.warning("[Discord] Guild $guildId (${DiscordConfig.GUILD_ID_KEY}) not found - the bot is not in that server, so slash commands are not registered.")
            return
        }
        guild.updateCommands().addCommands(
            Commands.slash("online", "Show who's online on the Minecraft server")
        ).queue(
            { if (bot === jda) plugin.logger.info("[Discord] Slash commands registered for ${guild.name}") },
            { err -> if (bot === jda) plugin.logger.warning("[Discord] Could not register slash commands in ${guild.name}: ${err.message}") }
        )
    }

    fun sendChat(playerName: String, message: String) {
        val formatted = chatFormat
            .replace("{player}", escapeMarkdown(playerName))
            .replace("{message}", escapeMarkdown(message))
        send(formatted)
    }

    /**
     * Death message (issue #1153). Weapon names, mob name tags and player names inside it are
     * player-controlled, so markdown is escaped and mention syntax broken before it is sent.
     */
    fun sendDeath(deathMessage: String) {
        send(":skull: ${escapeMarkdown(deathMessage)}")
    }

    fun sendPlayerJoin(playerName: String, uuid: String) {
        val embed = EmbedBuilder()
            .setColor(0x55FF55)
            .setAuthor("$playerName joined", null, headUrl(uuid))
            .setFooter(DiscordEmbeds.PUBLIC_FOOTER)
            .setTimestamp(java.time.Instant.now())
            .build()
        sendEmbed(embed)
    }

    fun sendPlayerLeave(playerName: String, uuid: String) {
        val embed = EmbedBuilder()
            .setColor(0xFF5555)
            .setAuthor("$playerName left", null, headUrl(uuid))
            .setFooter(DiscordEmbeds.PUBLIC_FOOTER)
            .setTimestamp(java.time.Instant.now())
            .build()
        sendEmbed(embed)
    }

    fun getChannel(): GuildMessageChannel? = jda?.let { chatChannel(it) }

    private fun chatChannel(bot: JDA): GuildMessageChannel? =
        if (chatChannelConfigured) resolveChannel(bot, channelId, CHAT_LABEL) else null

    /**
     * The channel [id] names if the bot can post embeds there, else null with the reason logged
     * once per channel and problem (issue #1161). Any channel that takes messages works: text,
     * announcement, thread, or a voice channel's text chat. Never throws, so a bad destination
     * can't surface in gameplay code; nothing is ever redirected elsewhere.
     */
    private fun resolveChannel(bot: JDA, id: String, label: String): GuildMessageChannel? {
        val problem: String
        if (!id.all { it.isDigit() }) {
            problem = "\"$id\" is not a valid channel id (expected digits only)"
        } else {
            val channel = try { bot.getGuildChannelById(id) } catch (_: NumberFormatException) { null }
            problem = when {
                channel == null -> "channel not found - it was deleted, or the bot is not in that server or lacks View Channel"
                channel !is GuildMessageChannel -> "#${channel.name} is a ${channel.type} channel, which can't receive messages"
                else -> {
                    val self = channel.guild.selfMember
                    val missing = POST_PERMISSIONS.filterNot { self.hasPermission(channel, it) }
                    when {
                        missing.isNotEmpty() -> "the bot is missing ${missing.joinToString { it.getName() }} in #${channel.name}"
                        !channel.canTalk() -> "the bot can't send messages in #${channel.name}"
                        else -> {
                            channelHealth.ok(id, label)
                            return channel
                        }
                    }
                }
            }
        }
        channelHealth.problem(id, label, problem)
        return null
    }

    /** Callbacks for one message to [target]: track the channel's health, but never for an old connection. */
    private fun sendCallbacks(bot: JDA, target: GuildMessageChannel, label: String): Pair<(Any?) -> Unit, (Throwable) -> Unit> {
        val onSuccess: (Any?) -> Unit = { if (isLive(bot)) channelHealth.ok(target.id, label) }
        val onFailure: (Throwable) -> Unit = failure@{ err ->
            if (!isLive(bot)) return@failure
            val problem = when ((err as? ErrorResponseException)?.errorResponse) {
                ErrorResponse.UNKNOWN_CHANNEL -> "#${target.name} was deleted"
                ErrorResponse.MISSING_ACCESS, ErrorResponse.MISSING_PERMISSIONS -> "the bot lacks permission to post in #${target.name}"
                else -> "sending to #${target.name} failed: ${err.message}"
            }
            channelHealth.problem(target.id, label, problem, dropped = 1)
        }
        return onSuccess to onFailure
    }

    /** The config key a channel id came from, for log lines; feature feeds share a generic label. */
    private fun channelLabel(id: String): String = when (id) {
        channelId -> CHAT_LABEL
        staffAnticheatChannelId -> DiscordConfig.Channel.STAFF_ANTICHEAT.key
        staffReportsChannelId -> DiscordConfig.Channel.STAFF_REPORTS.key
        else -> ALERT_LABEL
    }

    /** True while [bot] is this manager's connection, or the one [shutdown] is draining. */
    private fun isLive(bot: JDA): Boolean = bot === jda || bot === drainingJda

    /**
     * Queues a moderation action for the punished player's own forum post (issue #1069).
     * Called inside the punishment's own transaction, so the delivery intent is stored exactly
     * when the punishment is (issue #1157); posting happens later on the outbox worker, so the
     * main thread never waits on Discord and an outage or restart only delays the log.
     */
    fun logPunishment(entry: PunishmentManager.PunishmentLog) {
        if (!punishmentSyncEnabled || punishmentForumChannelId.isEmpty()) return
        punishmentLogs.enqueue(entry)
    }

    internal fun buildPunishmentEmbed(entry: PunishmentManager.PunishmentLog): MessageEmbed {
        val staff = if (entry.staffName.equals("CONSOLE", ignoreCase = true)) "Console" else entry.staffName
        val builder = SafeEmbedBuilder()
            .setTitle("${actionEmoji(entry.action)} ${actionLabel(entry.action)}")
            .setColor(actionColor(entry.action))
            .setThumbnail(headUrl(entry.targetUuid.toString()))
            .addField("Player", entry.targetName, true)
            .addField("Staff", staff, true)

        entry.punishmentId?.let { builder.addField("Punishment ID", "#$it", true) }

        builder.addField("Reason", (entry.reason?.takeIf { it.isNotBlank() } ?: "No reason provided").take(1024), false)

        when (entry.action) {
            "BAN", "MUTE" -> builder.addField("Duration", "Permanent", true)
            "TEMPBAN", "TEMPMUTE" -> {
                builder.addField("Duration", entry.durationMs?.let { PunishmentManager.formatDuration(it) } ?: "Unknown", true)
                entry.expiresAt?.let { builder.addField("Expires", discordTime(it, "F"), true) }
            }
        }

        if (entry.reversed.isNotEmpty()) {
            val lines = entry.reversed.joinToString("\n") { record ->
                val reason = record.reason?.takeIf { it.isNotBlank() } ?: "No reason provided"
                "#${record.id} ${actionLabel(record.type)} by ${record.punisherName} on ${discordTime(record.createdAt, "d")} - $reason"
            }
            builder.addField("Reverses", lines.take(1024), false)
        }

        builder.addField("Issued", discordTime(entry.timestamp, "F"), true)
        entry.previousCount?.let { builder.addField("Previous Punishments", it.toString(), true) }

        return builder
            .setFooter("UUID: ${entry.targetUuid}")
            .setTimestamp(Instant.ofEpochMilli(entry.timestamp))
            .build()
    }

    /** Discord renders `<t:..>` in each viewer's own timezone. */
    private fun discordTime(epochMs: Long, style: String): String = DiscordEmbeds.time(epochMs, style)

    private fun actionLabel(action: String): String = when (action) {
        "WARN" -> "Warn"
        "UNWARN" -> "Unwarn"
        "MUTE" -> "Permanent Mute"
        "TEMPMUTE" -> "Temp Mute"
        "UNMUTE" -> "Unmute"
        "KICK" -> "Kick"
        "BAN" -> "Permanent Ban"
        "TEMPBAN" -> "Temp Ban"
        "UNBAN" -> "Unban"
        else -> action.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun actionEmoji(action: String): String = when (action) {
        "WARN" -> "⚠️"
        "MUTE", "TEMPMUTE" -> "🔇"
        "KICK" -> "👢"
        "BAN", "TEMPBAN" -> "⛔"
        else -> "✅"
    }

    private fun actionColor(action: String): Int = when (action) {
        "WARN" -> 0xF1C40F
        "MUTE", "TEMPMUTE" -> 0xE67E22
        "KICK" -> 0xF0785A
        "TEMPBAN" -> 0xED4245
        "BAN" -> 0x992D22
        "UNWARN", "UNMUTE", "UNBAN" -> 0x57F287
        else -> 0x5865F2
    }

    private fun headUrl(uuid: String): String =
        "https://mc-heads.net/head/$uuid/128"

    fun updateStatus() {
        val online = plugin.server.onlinePlayers.size
        val max = plugin.server.maxPlayers
        jda?.presence?.activity = Activity.playing("$online/$max players | play.joshymc.net")
    }

    private fun startStatusUpdater() {
        // Update every 10 seconds (200 ticks)
        val id = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            updateStatus()
        }, 0L, 200L)
        synchronized(scheduledTaskIds) { scheduledTaskIds.add(id) }
    }

    private fun startFlushTask() {
        val id = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            jda?.let { flushQueue(it) }
        }, 2L, 2L)
        synchronized(scheduledTaskIds) { scheduledTaskIds.add(id) }
    }

    /**
     * Hands every queued message to JDA's async requester; never waits on Discord. Each
     * destination is resolved once per flush; messages for a channel that can't take them are
     * dropped and counted against that channel's (already logged) problem (issue #1161).
     */
    private fun flushQueue(bot: JDA) {
        if (messageQueue.isEmpty()) return
        val queued = ArrayDeque(messageQueue.drain())
        reportDiscards()

        // A missing chat channel only drops chat-bridge messages; embeds addressed to
        // their own channel (anti-cheat alerts, reports, ...) are still delivered.
        val channel = chatChannel(bot)
        val targets = HashMap<String, GuildMessageChannel?>()
        val dropped = HashMap<String, Int>()

        // Batch text messages together, but embeds must be sent individually
        val textBatch = mutableListOf<String>()

        while (queued.isNotEmpty()) {
            when (val action = queued.removeFirst()) {
                is QueuedAction.Text -> if (channel != null) textBatch.add(action.content) else dropped.merge(channelId, 1, Int::plus)
                is QueuedAction.Embed -> {
                    // Flush any pending text first
                    if (channel != null) flushText(bot, channel, textBatch)
                    val targetId = action.channelId ?: channelId
                    val label = channelLabel(targetId)
                    val target = if (action.channelId == null) channel
                        else targets.getOrPut(targetId) { resolveChannel(bot, targetId, label) }
                    if (target == null) {
                        dropped.merge(targetId, 1, Int::plus)
                        continue
                    }
                    // JDA throws synchronously on missing permissions; catch it so one
                    // bad channel can't cost the rest of this batch.
                    val (onSuccess, onFailure) = sendCallbacks(bot, target, label)
                    try {
                        target.sendMessageEmbeds(action.embed).queue(onSuccess, onFailure)
                    } catch (e: Exception) {
                        onFailure(e)
                    }
                }
            }
        }

        if (channel != null) flushText(bot, channel, textBatch)
        for ((id, count) in dropped) channelHealth.dropped(id, count)
    }

    private fun flushText(bot: JDA, channel: GuildMessageChannel, batch: MutableList<String>) {
        if (batch.isEmpty()) return
        val combined = batch.joinToString("\n")
        val (onSuccess, onFailure) = sendCallbacks(bot, channel, CHAT_LABEL)
        for (chunk in combined.chunked(1990)) {
            try {
                channel.sendMessage(chunk).setAllowedMentions(EnumSet.noneOf(Message.MentionType::class.java)).queue(onSuccess, onFailure)
            } catch (e: Exception) {
                onFailure(e)
            }
        }
        batch.clear()
    }

    private fun escapeMarkdown(text: String): String = DiscordEmbeds.escape(text)

    companion object {
        private const val RECONNECT_BASE_SECONDS = 5L
        private const val RECONNECT_MAX_SECONDS = 300L
        private const val CHAT_LABEL = "discord.channels.chat"
        private const val ALERT_LABEL = "Discord alert channel"

        /** Needed to post an embed anywhere; sending itself is checked with canTalk(), which knows threads need Send Messages in Threads. */
        private val POST_PERMISSIONS = listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_EMBED_LINKS)

        /**
         * Breaks every Discord mention form in untrusted text while keeping it readable:
         * `@everyone`, `@here` and `@name` get a zero-width space after the `@`, and
         * `<@id>` / `<@!id>` / `<@&id>` user and role mentions get one after the `<`.
         * Defence in depth on top of the disabled default mentions.
         */
        fun neutralizeMentions(text: String): String = text
            .replace("<@", "<\u200B@")
            .replace(Regex("@(\\w)"), "@\u200B$1")
    }
}
