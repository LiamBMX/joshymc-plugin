package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.PunishmentManager
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel
import net.dv8tion.jda.api.entities.channel.forums.ForumTag
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import net.dv8tion.jda.api.utils.messages.MessageRequest
import java.time.Duration
import java.time.Instant
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    /** Bumped by every [start] and [shutdown]; a connect attempt whose generation is stale discards its JDA. */
    private var generation = 0L
    private val scheduledTaskIds = mutableListOf<Int>()

    private sealed class QueuedAction {
        data class Text(val content: String) : QueuedAction()
        data class Embed(val embed: MessageEmbed, val channelId: String? = null) : QueuedAction()
    }

    private val messageQueue = ConcurrentLinkedQueue<QueuedAction>()

    private val updateHighlights = UpdateHighlightsAnnouncer(plugin)

    val channelId: String get() =
        plugin.config.getString("discord.channel-id")
            ?: plugin.config.getString("discord.chat-channel-id")
            ?: ""
    val chatFormat: String get() = plugin.config.getString("discord.chat-format") ?: "**{player}** » {message}"
    val minecraftFormat: String get() = plugin.config.getString("discord.minecraft-format") ?: "&9[Discord] &f{name} &7» &f{message}"

    private val punishmentSyncEnabled: Boolean get() = plugin.config.getBoolean("discord.punishments.enabled", false)
    /** `discord.channels.punishments-forum`, falling back to the pre-#1069 `discord.punishments.forum-channel-id`. */
    private val punishmentForumChannelId: String get() =
        plugin.config.getString("discord.channels.punishments-forum")?.takeIf { it.isNotBlank() }
            ?: plugin.config.getString("discord.punishments.forum-channel-id")
            ?: ""

    private val punishmentLogLock = Any()
    private var punishmentLogExecutor: ExecutorService? = null
    private val pendingPunishmentLogs = ConcurrentLinkedQueue<PunishmentManager.PunishmentLog>()
    /** True while the bot is connecting, so punishments issued meanwhile are held instead of dropped. */
    private var connecting = false

    private val staffMonitoringEnabled: Boolean get() = plugin.config.getBoolean("discord.staff-monitoring.enabled", false)
    private val staffAnticheatChannelId: String get() = plugin.config.getString("discord.staff-monitoring.anticheat-channel-id") ?: ""
    private val staffReportsChannelId: String get() = plugin.config.getString("discord.staff-monitoring.reports-channel-id") ?: ""

    /** The chat bridge is optional: staff monitoring keeps working without it (issue #1068). */
    private val chatChannelConfigured: Boolean get() = channelId.isNotEmpty() && channelId != "000000000000000000"

    /** [announceStartup] is true only for a real server start, so /joshymc reload doesn't post "Server started". */
    fun start(announceStartup: Boolean = true) {
        val token = plugin.config.getString("discord.token") ?: ""
        if (token.isEmpty() || token == "YOUR_BOT_TOKEN_HERE") {
            plugin.logger.warning("[Discord] Bot token not set in config.yml — integration disabled.")
            return
        }

        val configuredChannelId = channelId
        if (!chatChannelConfigured) {
            if (!staffMonitoringEnabled) {
                plugin.logger.warning("[Discord] Channel ID not set in config.yml — integration disabled.")
                return
            }
            plugin.logger.warning("[Discord] Channel ID not set in config.yml — chat bridge disabled, staff monitoring only.")
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
        synchronized(punishmentLogLock) { connecting = true }

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            var built: JDA? = null
            try {
                built = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES)
                    .addEventListeners(DiscordChatListener(plugin))
                    .build()
                // Own the connecting instance before blocking on it, so a reload or shutdown
                // that lands mid-handshake shuts it down instead of leaking it.
                val owned = synchronized(lifecycleLock) {
                    if (generation == attempt) connectingJda = built
                    generation == attempt
                }
                if (!owned) {
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
                    built.shutdownNow()
                    return@Runnable
                }

                plugin.logger.info("[Discord] Bot connected as ${built.selfUser.name}")
                startPunishmentLogging()

                // A missing chat channel only disables the chat bridge; staff alerts and
                // reports still go to their own channels.
                val channel = getChannel()
                if (channel != null) {
                    plugin.logger.info("[Discord] Bound to channel #${channel.name} (${channel.id})")
                } else if (chatChannelConfigured) {
                    plugin.logger.severe("[Discord] Could not find channel with ID: $configuredChannelId — check your config!")
                }

                // Register slash commands for the guild
                val guild = jda?.getGuildById("1284630112234508330")
                if (guild != null) {
                    guild.updateCommands().addCommands(
                        Commands.slash("online", "Show who's online on the Minecraft server")
                    ).queue {
                        plugin.logger.info("[Discord] Slash commands registered for ${guild.name}")
                    }
                } else {
                    plugin.logger.warning("[Discord] Could not find guild to register slash commands.")
                }

                verifyStaffMonitoringChannels()
                jda?.let { updateHighlights.announce(it) }

                plugin.server.scheduler.runTask(plugin, Runnable {
                    if (!isCurrent(attempt)) return@Runnable
                    startFlushTask()
                    startStatusUpdater()
                    plugin.logger.info("[Discord] Message queue started.")
                })

                // Only a real server start announces itself; /joshymc reload reconnects quietly.
                if (announceStartup) send(":green_circle: **Server started**")
            } catch (e: Exception) {
                val current = synchronized(lifecycleLock) {
                    if (generation == attempt) {
                        connectingJda = null
                        jda = null
                        state = ConnectionState.STOPPED
                    }
                    generation == attempt
                }
                try { built?.shutdownNow() } catch (_: Exception) {}
                // A cancelled attempt was stopped by shutdown(), which already handled the punishment logs.
                if (!current) return@Runnable
                stopPunishmentLogging()
                plugin.logger.severe("[Discord] Failed to connect: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    private fun isCurrent(attempt: Long): Boolean =
        synchronized(lifecycleLock) { generation == attempt && state == ConnectionState.CONNECTED }

    /**
     * Disconnects the bot without ever waiting on Discord unbounded. Queued messages and the
     * "Server stopped" notice (only when [serverStopping], never on /joshymc reload) are handed
     * to JDA's async requester, then JDA gets a few seconds to drain before it is cut off.
     * A connection still handshaking is cancelled and can never publish itself afterwards.
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
        }
        try {
            cancelScheduledTasks()
            stopPunishmentLogging()

            try {
                pending?.shutdownNow()
            } catch (e: Exception) {
                plugin.logger.warning("[Discord] Failed to cancel the connecting bot: ${e.message}")
            }

            if (active != null) {
                if (serverStopping) {
                    try {
                        // Best effort: whatever was still queued, then the stop notice.
                        flushQueue(active)
                        chatChannel(active)?.sendMessage(":red_circle: **Server stopped**")?.queue(null) { }
                    } catch (e: Exception) {
                        plugin.logger.warning("[Discord] Could not send the shutdown notice: ${e.message}")
                    }
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
            synchronized(lifecycleLock) { state = ConnectionState.STOPPED }
        }
    }

    private fun cancelScheduledTasks() {
        val ids = synchronized(scheduledTaskIds) { scheduledTaskIds.toList().also { scheduledTaskIds.clear() } }
        for (id in ids) plugin.server.scheduler.cancelTask(id)
    }

    fun send(content: String) {
        if (jda == null || !chatChannelConfigured) return
        messageQueue.add(QueuedAction.Text(content))
    }

    fun sendEmbed(embed: MessageEmbed) {
        if (jda == null || !chatChannelConfigured) return
        messageQueue.add(QueuedAction.Embed(embed))
    }

    /**
     * Same as [sendEmbed] but targets an arbitrary channel instead of the default
     * chat-bridge channel (e.g. a dedicated anti-dupe alert channel). Queued and
     * flushed the same way, so a bad channel id can never block the main thread.
     */
    fun sendEmbedToChannel(targetChannelId: String, embed: MessageEmbed) {
        if (jda == null || targetChannelId.isEmpty()) return
        messageQueue.add(QueuedAction.Embed(embed, targetChannelId))
    }

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

    /** Logs a concise warning for each staff-monitoring channel the bot cannot post embeds in. */
    private fun verifyStaffMonitoringChannels() {
        if (!staffMonitoringEnabled) return
        for ((label, id) in listOf("anticheat-channel-id" to staffAnticheatChannelId, "reports-channel-id" to staffReportsChannelId)) {
            if (id.isEmpty()) {
                plugin.logger.warning("[Discord] staff-monitoring.$label is not set — those alerts will not be sent.")
                continue
            }
            val channel = textChannel(id)
            if (channel == null) {
                plugin.logger.warning("[Discord] staff-monitoring.$label ($id) not found — the bot is not in that server (authorize it with the bot + applications.commands scopes) or lacks View Channel.")
                continue
            }
            val missing = listOf(
                net.dv8tion.jda.api.Permission.VIEW_CHANNEL,
                net.dv8tion.jda.api.Permission.MESSAGE_SEND,
                net.dv8tion.jda.api.Permission.MESSAGE_EMBED_LINKS
            ).filterNot { channel.guild.selfMember.hasPermission(channel, it) }
            if (missing.isEmpty()) {
                plugin.logger.info("[Discord] Staff monitoring $label bound to #${channel.name} (${channel.guild.name}).")
            } else {
                plugin.logger.warning("[Discord] staff-monitoring.$label (#${channel.name}) is missing bot permissions: ${missing.joinToString { it.getName() }}.")
            }
        }
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
            .setFooter("play.joshymc.net")
            .setTimestamp(java.time.Instant.now())
            .build()
        sendEmbed(embed)
    }

    fun sendPlayerLeave(playerName: String, uuid: String) {
        val embed = EmbedBuilder()
            .setColor(0xFF5555)
            .setAuthor("$playerName left", null, headUrl(uuid))
            .setFooter("play.joshymc.net")
            .setTimestamp(java.time.Instant.now())
            .build()
        sendEmbed(embed)
    }

    fun getChannel(): TextChannel? = jda?.let { chatChannel(it) }

    private fun chatChannel(bot: JDA): TextChannel? = if (chatChannelConfigured) textChannel(channelId, bot) else null

    /** Channel lookup that treats a malformed id in config as "not found" instead of throwing. */
    private fun textChannel(id: String, bot: JDA? = jda): TextChannel? =
        try { bot?.getTextChannelById(id) } catch (_: NumberFormatException) { null }

    private fun getPunishmentForumChannel(): ForumChannel? = jda?.getForumChannelById(punishmentForumChannelId)

    /**
     * Logs a moderation action into the punished player's own forum thread (issue #1069).
     * Called right after the punishment is stored, so a Discord failure can never roll it back.
     * Delivery runs on a single background thread: the main thread never waits on Discord, and
     * two quick punishments for the same player can never both decide to create a thread.
     * Entries issued while the bot is still connecting wait in memory and go out once it is ready.
     */
    fun logPunishment(entry: PunishmentManager.PunishmentLog) {
        if (!punishmentSyncEnabled || punishmentForumChannelId.isEmpty()) return
        synchronized(punishmentLogLock) {
            val executor = punishmentLogExecutor
            when {
                jda != null && executor != null -> executor.execute { deliverPunishmentLog(entry) }
                connecting -> pendingPunishmentLogs.add(entry)
                else -> plugin.logger.severe("[Discord] Bot is not connected - ${actionLabel(entry.action)} for ${entry.targetName} (${entry.targetUuid}) was not logged to Discord.")
            }
        }
    }

    /** Called once the bot is ready: opens the logging thread and sends anything issued while connecting. */
    private fun startPunishmentLogging() {
        synchronized(punishmentLogLock) {
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "JoshyMC-PunishmentLog").apply { isDaemon = true } }
            punishmentLogExecutor = executor
            connecting = false
            while (pendingPunishmentLogs.isNotEmpty()) {
                val entry = pendingPunishmentLogs.poll() ?: break
                executor.execute { deliverPunishmentLog(entry) }
            }
        }
    }

    private fun stopPunishmentLogging() {
        val executor = synchronized(punishmentLogLock) {
            connecting = false
            if (pendingPunishmentLogs.isNotEmpty()) {
                plugin.logger.severe("[Discord] ${pendingPunishmentLogs.size} punishment log(s) were issued before the bot connected and were not sent to Discord.")
                pendingPunishmentLogs.clear()
            }
            punishmentLogExecutor.also { punishmentLogExecutor = null }
        } ?: return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.logger.warning("[Discord] Timed out sending queued punishment logs to Discord during shutdown.")
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    /** Runs on the punishment-log thread only, so blocking .complete() calls are fine here. */
    private fun deliverPunishmentLog(entry: PunishmentManager.PunishmentLog) {
        val who = "${entry.targetName} (${entry.targetUuid})"
        try {
            val forum = getPunishmentForumChannel()
            if (forum == null) {
                plugin.logger.severe("[Discord] Punishment forum channel $punishmentForumChannelId not found (or the bot lacks View Channel) - ${actionLabel(entry.action)} for $who was not logged.")
                return
            }

            val embed = buildPunishmentEmbed(entry)
            val title = "${entry.targetName} - Punishment History".take(100)

            val mappedId = plugin.databaseManager.queryFirst(
                "SELECT thread_id FROM punishment_discord_threads WHERE player_uuid = ?", entry.targetUuid.toString()
            ) { rs -> rs.getString("thread_id") }

            if (mappedId != null) {
                val thread = findPunishmentThread(forum, mappedId)
                if (thread != null) {
                    reopenPunishmentThread(thread, title, who)
                    thread.sendMessageEmbeds(embed).complete()
                    if (thread.name != title) {
                        plugin.databaseManager.execute(
                            "UPDATE punishment_discord_threads SET player_name = ? WHERE player_uuid = ?",
                            entry.targetName, entry.targetUuid.toString()
                        )
                    }
                    return
                }
                plugin.logger.warning("[Discord] Punishment thread $mappedId for $who no longer exists in the forum - creating a new one.")
            }

            val action = forum.createForumPost(title, MessageCreateBuilder().setEmbeds(embed).build())
            val tags = punishmentForumTags(forum)
            if (tags.isNotEmpty()) action.setTags(tags)
            val post = action.complete()

            // Stored straight away so the next punishment for this player reuses the thread.
            plugin.databaseManager.execute(
                "INSERT OR REPLACE INTO punishment_discord_threads (player_uuid, thread_id, player_name, created_at) VALUES (?, ?, ?, ?)",
                entry.targetUuid.toString(), post.threadChannel.id, entry.targetName, System.currentTimeMillis()
            )
        } catch (e: Exception) {
            plugin.logger.severe("[Discord] Failed to log ${actionLabel(entry.action)} for $who to the punishment forum: ${e.message}")
        }
    }

    /**
     * The mapped thread, whether active (cached) or archived (looked up in the forum's archive).
     * Returns null only when the thread is really gone from this forum; lookup errors propagate so
     * a Discord hiccup never leads to a duplicate thread.
     */
    private fun findPunishmentThread(forum: ForumChannel, threadId: String): ThreadChannel? {
        jda?.getThreadChannelById(threadId)?.let { return it.takeIf { t -> t.parentChannel.id == forum.id } }
        for (thread in forum.retrieveArchivedPublicThreadChannels()) {
            if (thread.id == threadId) return thread
        }
        return null
    }

    /** Unarchives the thread and follows a username change. Failures are logged; the entry is still posted. */
    private fun reopenPunishmentThread(thread: ThreadChannel, title: String, who: String) {
        if (thread.isArchived) {
            try {
                thread.manager.setArchived(false).complete()
            } catch (e: Exception) {
                plugin.logger.warning("[Discord] Could not unarchive punishment thread ${thread.id} for $who (needs Manage Threads if it is locked): ${e.message}")
            }
        }
        if (thread.name != title) {
            try {
                thread.manager.setName(title).complete()
            } catch (e: Exception) {
                plugin.logger.warning("[Discord] Could not rename punishment thread ${thread.id} for $who: ${e.message}")
            }
        }
    }

    /**
     * Tags for a new post: the configured `discord.punishments.forum-tag` if it exists, otherwise
     * none - unless the forum requires a tag, then one named like "punish" or else the first one.
     */
    private fun punishmentForumTags(forum: ForumChannel): List<ForumTag> {
        val available = forum.availableTags
        val configured = plugin.config.getString("discord.punishments.forum-tag")?.trim().orEmpty()
        if (configured.isNotEmpty()) {
            available.firstOrNull { it.name.equals(configured, ignoreCase = true) }?.let { return listOf(it) }
            plugin.logger.warning("[Discord] discord.punishments.forum-tag \"$configured\" is not a tag on #${forum.name}.")
        }
        if (!forum.isTagRequired) return emptyList()
        val fallback = available.firstOrNull { it.name.contains("punish", ignoreCase = true) } ?: available.firstOrNull()
        if (fallback == null) {
            plugin.logger.warning("[Discord] #${forum.name} requires a tag but has none - post creation will likely fail.")
            return emptyList()
        }
        return listOf(fallback)
    }

    private fun buildPunishmentEmbed(entry: PunishmentManager.PunishmentLog): MessageEmbed {
        val staff = if (entry.staffName.equals("CONSOLE", ignoreCase = true)) "Console" else entry.staffName
        val builder = EmbedBuilder()
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
    private fun discordTime(epochMs: Long, style: String): String = "<t:${epochMs / 1000}:$style>"

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

    private fun avatarUrl(uuid: String): String =
        "https://mc-heads.net/avatar/$uuid/64"

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

    /** Hands every queued message to JDA's async requester; never waits on Discord. */
    private fun flushQueue(bot: JDA) {
        run {
            if (messageQueue.isEmpty()) return

            // A missing chat channel only drops chat-bridge messages; embeds addressed to
            // their own channel (anti-cheat alerts, reports, ...) are still delivered.
            val channel = chatChannel(bot)
            var droppedChat = 0

            // Batch text messages together, but embeds must be sent individually
            val textBatch = mutableListOf<String>()

            while (messageQueue.isNotEmpty()) {
                when (val action = messageQueue.poll() ?: break) {
                    is QueuedAction.Text -> if (channel != null) textBatch.add(action.content) else droppedChat++
                    is QueuedAction.Embed -> {
                        // Flush any pending text first
                        if (channel != null) flushText(channel, textBatch)
                        val target = if (action.channelId != null) textChannel(action.channelId, bot) else channel
                        if (target == null) {
                            if (action.channelId != null) {
                                plugin.logger.warning("[Discord] Target channel not found for embed (${action.channelId}).")
                            } else {
                                droppedChat++
                            }
                        } else {
                            // JDA throws synchronously on missing permissions; catch it so one
                            // bad channel can't cost the rest of this batch.
                            try {
                                target.sendMessageEmbeds(action.embed).queue(
                                    null,
                                    { err -> plugin.logger.warning("[Discord] Failed to send embed to #${target.name} (${target.id}): ${err.message}") }
                                )
                            } catch (e: Exception) {
                                plugin.logger.warning("[Discord] Failed to send embed to #${target.name} (${target.id}): ${e.message}")
                            }
                        }
                    }
                }
            }

            if (channel != null) flushText(channel, textBatch)
            if (droppedChat > 0) plugin.logger.warning("[Discord] Chat channel not found, dropped $droppedChat message(s).")
        }
    }

    private fun flushText(channel: TextChannel, batch: MutableList<String>) {
        if (batch.isEmpty()) return
        val combined = batch.joinToString("\n")
        for (chunk in combined.chunked(1990)) {
            try {
                channel.sendMessage(chunk).setAllowedMentions(EnumSet.noneOf(Message.MentionType::class.java)).queue(
                    null,
                    { err -> plugin.logger.warning("[Discord] Failed to send message: ${err.message}") }
                )
            } catch (e: Exception) {
                plugin.logger.warning("[Discord] Failed to send message: ${e.message}")
            }
        }
        batch.clear()
    }

    private fun escapeMarkdown(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("*", "\\*")
            .replace("_", "\\_")
            .replace("~", "\\~")
            .replace("`", "\\`")
            .replace("|", "\\|")
            .replace(">", "\\>")
            .let(::neutralizeMentions)
    }

    companion object {
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
