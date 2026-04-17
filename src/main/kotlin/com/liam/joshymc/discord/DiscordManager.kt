package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.requests.GatewayIntent
import java.util.concurrent.ConcurrentLinkedQueue

class DiscordManager(private val plugin: Joshymc) {

    var jda: JDA? = null
        private set

    private sealed class QueuedAction {
        data class Text(val content: String) : QueuedAction()
        data class Embed(val embed: MessageEmbed) : QueuedAction()
    }

    private val messageQueue = ConcurrentLinkedQueue<QueuedAction>()

    val channelId: String get() =
        plugin.config.getString("discord.channel-id")
            ?: plugin.config.getString("discord.chat-channel-id")
            ?: ""
    val chatFormat: String get() = plugin.config.getString("discord.chat-format") ?: "**{player}** » {message}"
    val minecraftFormat: String get() = plugin.config.getString("discord.minecraft-format") ?: "&9[Discord] &f{name} &7» &f{message}"

    fun start() {
        val token = plugin.config.getString("discord.token") ?: ""
        if (token.isEmpty() || token == "YOUR_BOT_TOKEN_HERE") {
            plugin.logger.warning("[Discord] Bot token not set in config.yml — integration disabled.")
            return
        }

        val configuredChannelId = channelId
        if (configuredChannelId.isEmpty() || configuredChannelId == "000000000000000000") {
            plugin.logger.warning("[Discord] Channel ID not set in config.yml — integration disabled.")
            return
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

                val channel = getChannel()
                if (channel == null) {
                    plugin.logger.severe("[Discord] Could not find channel with ID: $configuredChannelId — check your config!")
                    return@Runnable
                }

                plugin.logger.info("[Discord] Bound to channel #${channel.name} (${channel.id})")

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
        if (jda == null || channelId.isEmpty()) return
        messageQueue.add(QueuedAction.Text(content))
    }

    fun sendEmbed(embed: MessageEmbed) {
        if (jda == null || channelId.isEmpty()) return
        messageQueue.add(QueuedAction.Embed(embed))
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

    fun getChannel(): TextChannel? = jda?.getTextChannelById(channelId)

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

            val channel = getChannel()
            if (channel == null) {
                messageQueue.clear()
                plugin.logger.warning("[Discord] Channel not found, dropping messages.")
                return@Runnable
            }

            // Batch text messages together, but embeds must be sent individually
            val textBatch = mutableListOf<String>()

            while (messageQueue.isNotEmpty()) {
                when (val action = messageQueue.poll() ?: break) {
                    is QueuedAction.Text -> textBatch.add(action.content)
                    is QueuedAction.Embed -> {
                        // Flush any pending text first
                        flushText(channel, textBatch)
                        channel.sendMessageEmbeds(action.embed).queue(
                            null,
                            { err -> plugin.logger.warning("[Discord] Failed to send embed: ${err.message}") }
                        )
                    }
                }
            }

            flushText(channel, textBatch)
        }, 2L, 2L)
    }

    private fun flushText(channel: TextChannel, batch: MutableList<String>) {
        if (batch.isEmpty()) return
        val combined = batch.joinToString("\n")
        for (chunk in combined.chunked(1990)) {
            channel.sendMessage(chunk).queue(
                null,
                { err -> plugin.logger.warning("[Discord] Failed to send message: ${err.message}") }
            )
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
