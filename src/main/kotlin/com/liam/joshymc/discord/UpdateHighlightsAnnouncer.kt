package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import org.bukkit.configuration.file.YamlConfiguration
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Posts the release's update highlights to Discord (issue #1121) on the existing bot
 * connection. The highlights come only from `update-highlights.yml` bundled in the JAR;
 * nothing is guessed. A set of highlights posts once: its content hash is stored after
 * Discord accepts the message, so restarts, reloads and releases that leave the file
 * unchanged never repost it, while a failed post is retried on the next startup.
 */
class UpdateHighlightsAnnouncer(private val plugin: Joshymc) {

    val enabled: Boolean get() = plugin.config.getBoolean("update-highlights.enabled", false)
    val channelId: String get() = plugin.config.getString("update-highlights.discord-channel")?.trim().orEmpty()

    /** Hashes being sent right now, so a reload during the post can't send it twice. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Called on the Discord connect thread once [jda] is ready. Never throws. */
    fun announce(jda: JDA) {
        try {
            if (!enabled) return
            val version = plugin.pluginMeta.version
            // Dev builds share the bot token in testing; only release builds announce.
            if (version.endsWith("-SNAPSHOT")) return

            val bundled = plugin.getResource("update-highlights.yml")?.reader(Charsets.UTF_8)?.use {
                YamlConfiguration.loadConfiguration(it)
            } ?: return
            val summary = bundled.getString("summary")?.trim().orEmpty()
            val highlights = bundled.getStringList("highlights").map { it.trim() }.filter { it.isNotEmpty() }
            if (highlights.isEmpty()) return

            val hash = sha256(summary + "\n" + highlights.joinToString("\n"))
            plugin.databaseManager.createTable("""
                CREATE TABLE IF NOT EXISTS update_highlights_posted (
                    content_hash TEXT PRIMARY KEY,
                    version TEXT NOT NULL,
                    posted_at INTEGER NOT NULL
                )
            """.trimIndent())
            val alreadyPosted = plugin.databaseManager.queryFirst(
                "SELECT version FROM update_highlights_posted WHERE content_hash = ?", hash
            ) { rs -> rs.getString("version") }
            if (alreadyPosted != null) return

            if (channelId.isEmpty()) {
                plugin.logger.warning("[Discord] update-highlights.discord-channel is not set - update highlights for v$version were not posted.")
                return
            }
            val channel = try { jda.getChannelById(GuildMessageChannel::class.java, channelId) } catch (_: NumberFormatException) { null }
            if (channel == null) {
                plugin.logger.warning("[Discord] Update highlights channel $channelId not found (the bot is not in that server or lacks View Channel) - v$version highlights were not posted.")
                return
            }
            val missing = listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS)
                .filterNot { channel.guild.selfMember.hasPermission(channel, it) }
            if (missing.isNotEmpty()) {
                plugin.logger.warning("[Discord] Update highlights channel #${channel.name} is missing bot permissions: ${missing.joinToString { it.getName() }} - v$version highlights were not posted.")
                return
            }

            if (!inFlight.add(hash)) return
            try {
                channel.sendMessageEmbeds(buildEmbed(version, summary, highlights)).queue(
                    {
                        try {
                            plugin.databaseManager.execute(
                                "INSERT OR REPLACE INTO update_highlights_posted (content_hash, version, posted_at) VALUES (?, ?, ?)",
                                hash, version, System.currentTimeMillis()
                            )
                            plugin.logger.info("[Discord] Posted v$version update highlights to #${channel.name}.")
                        } catch (e: Exception) {
                            plugin.logger.warning("[Discord] Posted v$version update highlights but could not record it, so they may post again: ${e.message}")
                        }
                    },
                    { err ->
                        inFlight.remove(hash)
                        plugin.logger.warning("[Discord] Failed to post update highlights to #${channel.name}: ${err.message}")
                    }
                )
            } catch (e: Exception) {
                inFlight.remove(hash)
                throw e
            }
        } catch (e: Exception) {
            plugin.logger.warning("[Discord] Failed to post update highlights: ${e.message}")
        }
    }

    private fun buildEmbed(version: String, summary: String, highlights: List<String>): MessageEmbed {
        val builder = SafeEmbedBuilder()
            .setTitle("JoshyMC Update v$version")
            .setColor(0x5865F2)
            .setFooter(DiscordEmbeds.PUBLIC_FOOTER)
            .setTimestamp(Instant.now())
        if (summary.isNotEmpty()) builder.setDescription(summary.take(MessageEmbed.DESCRIPTION_MAX_LENGTH))

        // An embed field holds 1024 characters; extra bullets go into a continuation field.
        val fields = mutableListOf<StringBuilder>()
        for (line in highlights.map { "• ${it.take(MessageEmbed.VALUE_MAX_LENGTH - 2)}" }) {
            val current = fields.lastOrNull()
            if (current == null || current.length + 1 + line.length > MessageEmbed.VALUE_MAX_LENGTH) {
                fields.add(StringBuilder(line))
            } else {
                current.append('\n').append(line)
            }
        }
        fields.forEachIndexed { i, value -> builder.addField(if (i == 0) "✨ Highlights" else "​", value.toString(), false) }
        return builder.build()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
