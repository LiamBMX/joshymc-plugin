package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import com.liam.joshymc.manager.PunishmentManager
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class DiscordManager(private val plugin: Joshymc) {

    var jda: JDA? = null
        private set

    private sealed class QueuedAction {
        data class Text(val content: String) : QueuedAction()
        data class Embed(val embed: MessageEmbed, val channelId: String? = null) : QueuedAction()
    }

    private val messageQueue = ConcurrentLinkedQueue<QueuedAction>()

    val channelId: String get() =
        plugin.config.getString("discord.channel-id")
            ?: plugin.config.getString("discord.chat-channel-id")
            ?: ""
    val chatFormat: String get() = plugin.config.getString("discord.chat-format") ?: "**{player}** » {message}"
    val minecraftFormat: String get() = plugin.config.getString("discord.minecraft-format") ?: "&9[Discord] &f{name} &7» &f{message}"

    private val punishmentSyncEnabled: Boolean get() = plugin.config.getBoolean("discord.punishments.enabled", false)
    private val punishmentForumChannelId: String get() = plugin.config.getString("discord.punishments.forum-channel-id") ?: ""

    private val staffMonitoringEnabled: Boolean get() = plugin.config.getBoolean("discord.staff-monitoring.enabled", false)
    private val staffAnticheatChannelId: String get() = plugin.config.getString("discord.staff-monitoring.anticheat-channel-id") ?: ""
    private val staffReportsChannelId: String get() = plugin.config.getString("discord.staff-monitoring.reports-channel-id") ?: ""

    /** The chat bridge is optional: staff monitoring keeps working without it (issue #1068). */
    private val chatChannelConfigured: Boolean get() = channelId.isNotEmpty() && channelId != "000000000000000000"

    fun start() {
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

        plugin.logger.info("[Discord] Connecting bot...")

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            try {
                jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.DIRECT_MESSAGES)
                    .addEventListeners(DiscordChatListener(plugin))
                    .build()
                    .awaitReady()

                plugin.logger.info("[Discord] Bot connected as ${jda?.selfUser?.name}")

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

                plugin.server.scheduler.runTask(plugin, Runnable {
                    startFlushTask()
                    startStatusUpdater()
                    plugin.logger.info("[Discord] Message queue started.")
                })

                send(":green_circle: **Server started**")
            } catch (e: Exception) {
                plugin.logger.severe("[Discord] Failed to connect: ${e.message}")
                e.printStackTrace()
            }
        })
    }

    fun shutdown() {
        if (jda != null) {
            getChannel()?.sendMessage(":red_circle: **Server stopped**")?.complete()
        }
        jda?.shutdown()
        jda = null
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

    fun getChannel(): TextChannel? = if (chatChannelConfigured) textChannel(channelId) else null

    /** Channel lookup that treats a malformed id in config as "not found" instead of throwing. */
    private fun textChannel(id: String): TextChannel? =
        try { jda?.getTextChannelById(id) } catch (_: NumberFormatException) { null }

    private fun getPunishmentForumChannel(): ForumChannel? = jda?.getForumChannelById(punishmentForumChannelId)

    /**
     * Fired right after a punishment is persisted (never before) so a Discord failure can
     * never roll back or block the Minecraft-side punishment. Runs off the JDA gateway
     * thread via .queue() so the caller (main thread) never blocks on network I/O.
     */
    fun syncPunishmentCreated(
        punishmentId: Int,
        type: String,
        targetUuid: UUID,
        targetName: String,
        punisherName: String,
        reason: String?,
        durationMs: Long?,
        expiresAt: Long?,
        createdAt: Long
    ) {
        if (!punishmentSyncEnabled || jda == null) return

        // Duplicate-safety: a thread already stored for this id means it was already synced.
        val existingThread = plugin.databaseManager.queryFirst(
            "SELECT discord_thread_id FROM punishments WHERE id = ?", punishmentId
        ) { rs -> rs.getString("discord_thread_id") }
        if (existingThread != null) return

        val forum = getPunishmentForumChannel()
        if (forum == null) {
            plugin.logger.warning("[Discord] Punishment forum channel not found ($punishmentForumChannelId) — skipping sync for #$punishmentId.")
            return
        }

        val typeLabel = punishmentTypeLabel(type)
        val staff = if (punisherName.equals("CONSOLE", ignoreCase = true)) "Console" else punisherName
        val durationText = when {
            type == "WARN" -> "N/A"
            durationMs == null -> "Permanent"
            else -> PunishmentManager.formatDuration(durationMs)
        }

        val embed = EmbedBuilder()
            .setTitle("JoshyMC Punishment")
            .setColor(punishmentColor(type))
            .setThumbnail(headUrl(targetUuid.toString()))
            .addField("Player", targetName, true)
            .addField("Punishment", typeLabel, true)
            .addField("Duration", durationText, true)
            .addField("Punished By", staff, true)
            .addField("Reason", reason?.takeIf { it.isNotBlank() } ?: "No reason specified", false)
            .addField("Punishment ID", "#$punishmentId", true)
            .addField("UUID", targetUuid.toString(), true)
            .setTimestamp(Instant.ofEpochMilli(createdAt))
            .build()

        val postTitle = "#$punishmentId • $targetName • $typeLabel".take(100)
        val message = MessageCreateBuilder().setEmbeds(embed).build()

        forum.createForumPost(postTitle, message).queue(
            { post ->
                plugin.databaseManager.execute(
                    "UPDATE punishments SET discord_thread_id = ? WHERE id = ?",
                    post.threadChannel.id, punishmentId
                )
            },
            { err -> plugin.logger.warning("[Discord] Failed to create punishment forum post for #$punishmentId: ${err.message}") }
        )
    }

    /**
     * Replies inside the punishment's existing forum thread instead of creating a new post -
     * unban/unmute/unwarn are administrative removals, not new punishments.
     */
    fun syncPunishmentRevoked(threadId: String, revokerName: String, revokeReason: String?) {
        if (!punishmentSyncEnabled || jda == null) return

        val thread = jda?.getThreadChannelById(threadId)
        if (thread == null) {
            plugin.logger.warning("[Discord] Could not find punishment thread $threadId to post revocation.")
            return
        }

        val embed = EmbedBuilder()
            .setColor(0x57F287)
            .setDescription("**Punishment revoked by $revokerName**")
            .apply { if (!revokeReason.isNullOrBlank()) addField("Reason", revokeReason, false) }
            .setTimestamp(Instant.now())
            .build()

        thread.sendMessageEmbeds(embed).queue(
            null,
            { err -> plugin.logger.warning("[Discord] Failed to post revocation to thread $threadId: ${err.message}") }
        )
    }

    private fun punishmentTypeLabel(type: String): String = when (type) {
        "BAN" -> "Permanent Ban"
        "TEMPBAN" -> "Temporary Ban"
        "MUTE" -> "Permanent Mute"
        "TEMPMUTE" -> "Temporary Mute"
        "WARN" -> "Warning"
        else -> type.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun punishmentColor(type: String): Int = when (type) {
        "BAN" -> 0xED4245
        "TEMPBAN" -> 0xFF4500
        "MUTE", "TEMPMUTE" -> 0xFFA500
        "WARN" -> 0xFEE75C
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
        plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            updateStatus()
        }, 0L, 200L)
    }

    private fun startFlushTask() {
        plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            if (messageQueue.isEmpty()) return@Runnable

            // A missing chat channel only drops chat-bridge messages; embeds addressed to
            // their own channel (anti-cheat alerts, reports, ...) are still delivered.
            val channel = getChannel()
            var droppedChat = 0

            // Batch text messages together, but embeds must be sent individually
            val textBatch = mutableListOf<String>()

            while (messageQueue.isNotEmpty()) {
                when (val action = messageQueue.poll() ?: break) {
                    is QueuedAction.Text -> if (channel != null) textBatch.add(action.content) else droppedChat++
                    is QueuedAction.Embed -> {
                        // Flush any pending text first
                        if (channel != null) flushText(channel, textBatch)
                        val target = if (action.channelId != null) textChannel(action.channelId) else channel
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
        }, 2L, 2L)
    }

    private fun flushText(channel: TextChannel, batch: MutableList<String>) {
        if (batch.isEmpty()) return
        val combined = batch.joinToString("\n")
        for (chunk in combined.chunked(1990)) {
            try {
                channel.sendMessage(chunk).queue(
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
            .replace("@everyone", "@\u200Beveryone")
            .replace("@here", "@\u200Bhere")
            .replace(Regex("<@[!&]?\\d+>"), "")
            .replace(Regex("@(\\w)"), "@\u200B$1")
    }
}
