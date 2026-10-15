package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import com.liam.joshymc.link.DiscordClaim
import com.liam.joshymc.link.LinkGui
import com.liam.joshymc.link.LinkSessions
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.GenericEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter

class DiscordChatListener(private val plugin: Joshymc) : ListenerAdapter() {

    /**
     * Only the bot connection DiscordManager currently owns may act on events, so an old
     * connection still draining after a reload can never double-post or double-reply.
     */
    private fun isActiveConnection(event: GenericEvent): Boolean = event.jda === plugin.discordManager.jda

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (!isActiveConnection(event)) return
        if (event.author.isBot) return

        // Handle DMs — link codes
        if (!event.isFromGuild) {
            handleDm(event)
            return
        }

        val expectedChannel = plugin.discordManager.channelId
        if (event.channel.id != expectedChannel) return

        val message = DiscordText.sanitize(event.message.contentStripped, DiscordText.MAX_MESSAGE_LENGTH)
        if (message.isBlank()) return

        if (message.equals("!online", ignoreCase = true)) {
            replyOnlineEmbed { embed -> event.channel.sendMessageEmbeds(embed).queue() }
            return
        }

        if (message.startsWith("/")) return

        val name = DiscordText.sanitize(event.member?.effectiveName ?: event.author.effectiveName, DiscordText.MAX_NAME_LENGTH)
            .ifBlank { DiscordText.sanitize(event.author.name, DiscordText.MAX_NAME_LENGTH) }
        plugin.logger.info("[Discord → MC] $name: $message")

        // Only the server's format is parsed for & codes; name and message stay literal text.
        val component = DiscordText.render(plugin.discordManager.minecraftFormat, name, message)

        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.server.broadcast(component)
        })
    }

    private fun handleDm(event: MessageReceivedEvent) {
        val dm = event.channel
        val author = event.author
        val claim = DiscordClaim(
            id = author.id,
            displayName = DiscordText.sanitize(author.effectiveName, DiscordText.MAX_NAME_LENGTH),
            username = DiscordText.sanitize(author.name, DiscordText.MAX_NAME_LENGTH),
        )

        val pending = when (val result = plugin.linkManager.sessions.submit(claim, event.message.contentStripped.trim())) {
            is LinkSessions.SubmitResult.Accepted -> result.pending
            LinkSessions.SubmitResult.NotACode -> {
                dmEmbed(event, 0x55FFFF, "Send me the **${plugin.linkManager.settings.codeLength}-character code** from `/link` in-game.")
                return
            }
            LinkSessions.SubmitResult.Invalid -> {
                dmEmbed(event, 0xFF5555, "Invalid or expired code.\nRun `/link` in-game to get a new one.")
                return
            }
            is LinkSessions.SubmitResult.LockedOut -> {
                dmEmbed(event, 0xFF5555, "Too many incorrect codes. Try again in ${formatWait(result.retryAfterMs)}.")
                return
            }
            is LinkSessions.SubmitResult.Busy -> {
                dmEmbed(event, 0xFF5555, "Linking is busy right now. Try again in ${formatWait(result.retryAfterMs)}.")
                return
            }
        }

        dm.sendMessageEmbeds(EmbedBuilder()
            .setColor(0x55FF55)
            .setTitle("Code Accepted")
            .setDescription("Now confirm the link in-game by clicking **Confirm** in the GUI.")
            .setThumbnail("https://mc-heads.net/head/${pending.playerUuid}/128")
            .addField("Minecraft Player", pending.playerName, true)
            .setFooter("play.joshymc.net")
            .setTimestamp(java.time.Instant.now())
            .build()
        ).queue()

        // Update the GUI on the main thread, unless the session ended in the meantime.
        plugin.server.scheduler.runTask(plugin, Runnable {
            val current = plugin.linkManager.sessions.current(pending.playerUuid) ?: return@Runnable
            if (current.code != pending.code || current.claim?.id != claim.id) return@Runnable
            val player = plugin.server.getPlayer(pending.playerUuid) ?: return@Runnable
            LinkGui.showConfirmation(player, current)
        })
    }

    private fun dmEmbed(event: MessageReceivedEvent, color: Int, description: String) {
        event.channel.sendMessageEmbeds(EmbedBuilder().setColor(color).setDescription(description).build()).queue()
    }

    private fun formatWait(ms: Long): String {
        val seconds = (ms + 999) / 1000
        if (seconds < 60) return if (seconds == 1L) "1 second" else "$seconds seconds"
        val minutes = (seconds + 59) / 60
        return if (minutes == 1L) "1 minute" else "$minutes minutes"
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (!isActiveConnection(event)) return
        when (event.name) {
            "online" -> {
                event.deferReply().queue()
                replyOnlineEmbed { embed ->
                    event.hook.sendMessageEmbeds(embed).queue()
                }
            }
        }
    }

    private fun replyOnlineEmbed(callback: (net.dv8tion.jda.api.entities.MessageEmbed) -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            val players = plugin.server.onlinePlayers
            val max = plugin.server.maxPlayers

            val embed = EmbedBuilder()
                .setColor(0x55FFFF)
                .setTitle("Online Players (${players.size}/$max)")

            if (players.isEmpty()) {
                embed.setDescription("No players online.")
            } else {
                val list = players.sortedBy { it.name }.joinToString("\n") { "• ${it.name}" }
                embed.setDescription(list)
            }

            embed.setFooter("play.joshymc.net")
            embed.setTimestamp(java.time.Instant.now())

            callback(embed.build())
        })
    }
}
