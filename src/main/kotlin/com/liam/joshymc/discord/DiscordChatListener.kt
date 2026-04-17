package com.liam.joshymc.discord

import com.liam.joshymc.Joshymc
import com.liam.joshymc.link.LinkGui
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

class DiscordChatListener(private val plugin: Joshymc) : ListenerAdapter() {

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        // Handle DMs — link codes
        if (!event.isFromGuild) {
            handleDm(event)
            return
        }

        val expectedChannel = plugin.discordManager.channelId
        if (event.channel.id != expectedChannel) return

        val message = event.message.contentStripped.take(256)
        if (message.isBlank()) return

        if (message.equals("!online", ignoreCase = true)) {
            replyOnlineEmbed { embed -> event.channel.sendMessageEmbeds(embed).queue() }
            return
        }

        if (message.startsWith("/")) return

        val name = event.member?.effectiveName ?: event.author.effectiveName
        plugin.logger.info("[Discord → MC] $name: $message")

        val formatted = plugin.discordManager.minecraftFormat
            .replace("{name}", name)
            .replace("{message}", message)

        val component = LegacyComponentSerializer.legacyAmpersand().deserialize(formatted)

        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.server.broadcast(component)
        })
    }

    private fun handleDm(event: MessageReceivedEvent) {
        val message = event.message.contentStripped.trim()
        val dm = event.channel

        if (!message.matches(Regex("\\d{4}"))) {
            dm.sendMessageEmbeds(EmbedBuilder()
                .setColor(0x55FFFF)
                .setDescription("Send me a **4-digit code** from `/link` in-game.")
                .build()
            ).queue()
            return
        }

        val pending = plugin.linkManager.findPendingByCode(message)
        if (pending == null) {
            dm.sendMessageEmbeds(EmbedBuilder()
                .setColor(0xFF5555)
                .setDescription("Invalid or expired code.\nRun `/link` in-game to get a new one.")
                .build()
            ).queue()
            return
        }

        if (pending.discordId != null && pending.discordId != event.author.id) {
            dm.sendMessageEmbeds(EmbedBuilder()
                .setColor(0xFF5555)
                .setDescription("This code is already being used.")
                .build()
            ).queue()
            return
        }

        pending.discordId = event.author.id
        pending.discordName = event.author.effectiveName

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

        // Update the GUI on the main thread
        plugin.server.scheduler.runTask(plugin, Runnable {
            val player = plugin.server.getPlayer(pending.playerUuid)
            if (player != null && player.isOnline) {
                LinkGui.updateGuiWithConfirmation(player, event.author.effectiveName, message)
            }
        })
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
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
