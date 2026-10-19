package com.liam.joshymc.listener

import com.liam.joshymc.Joshymc
import com.liam.joshymc.command.notifyStaff
import com.liam.joshymc.manager.ChatSpamDetector
import com.liam.joshymc.manager.CommunicationsManager
import com.liam.joshymc.manager.PunishmentManager
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Automatic spam mute (issue #1268): the same public chat message repeated
 * `chat.anti-spam.repeat-threshold` times within `window-ticks` server ticks blocks that
 * message and temp-mutes the player through [PunishmentManager.autoTempmute].
 *
 * Runs at NORMAL with ignoreCancelled, so only messages still headed for public chat count:
 * existing mutes, the global chat mute, Staff Chat, team chat and chat-input prompts all
 * cancel earlier (LOWEST/LOW). Commands and private messages never fire AsyncChatEvent.
 */
class ChatSpamListener(private val plugin: Joshymc) : Listener {

    private val settings = loadSettings()
    private val detector = ChatSpamDetector(settings)

    /** Players whose mute is being written; their messages are dropped until it lands. */
    private val punishing: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!settings.enabled) return
        val player = event.player
        if (player.uniqueId in punishing) {
            event.isCancelled = true
            return
        }
        if (player.hasPermission(PERM_BYPASS)) return

        // The original text, before the profanity filter or [item] replacements touched it.
        val original = PlainTextComponentSerializer.plainText().serialize(event.originalMessage())
        if (!detector.record(player.uniqueId, original, Bukkit.getCurrentTick())) return

        event.isCancelled = true
        if (!punishing.add(player.uniqueId)) return
        val inserted = try {
            plugin.punishmentManager.autoTempmute(player.uniqueId, player.name, settings.muteReason, settings.muteDurationMs)
        } catch (e: Exception) {
            plugin.logger.severe("[AntiSpam] Could not mute ${player.name} (${player.uniqueId}) for spam: ${e.message}")
            null
        } finally {
            punishing.remove(player.uniqueId)
        }
        // Null: already muted (that mute stands) or the write failed. Either way no notices.
        inserted ?: return

        val duration = PunishmentManager.formatDuration(settings.muteDurationMs)
        plugin.logger.info("[AntiSpam] Auto-muted ${player.name} (${player.uniqueId}) for $duration, punishment #${inserted.id}. Repeated message: \"$original\"")

        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (player.isOnline) {
                plugin.commsManager.send(
                    player,
                    Component.text("You have been temporarily muted for $duration.", NamedTextColor.RED)
                        .append(Component.text(" Reason: ${settings.muteReason}", NamedTextColor.GRAY)),
                    CommunicationsManager.Category.ADMIN
                )
            }
            notifyStaff(
                Bukkit.getConsoleSender(),
                Component.text("${PunishmentManager.AUTOMOD_NAME} temporarily muted ${player.name} for $duration", NamedTextColor.RED)
                    .append(Component.text(" - ${settings.muteReason}", NamedTextColor.GRAY))
            )
        })
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        detector.forget(event.player.uniqueId)
    }

    private fun loadSettings(): ChatSpamDetector.Settings {
        val section = "chat.anti-spam"
        val config = plugin.config
        return ChatSpamDetector.settingsOf(
            enabled = config.getBoolean("$section.enabled", true),
            repeatThreshold = config.getInt("$section.repeat-threshold", ChatSpamDetector.DEFAULT_THRESHOLD),
            windowTicks = config.getInt("$section.window-ticks", ChatSpamDetector.DEFAULT_WINDOW_TICKS),
            muteDurationSeconds = config.getLong("$section.mute-duration-seconds", ChatSpamDetector.DEFAULT_MUTE_SECONDS),
            muteReason = config.getString("$section.mute-reason", ChatSpamDetector.DEFAULT_REASON)
        ) { plugin.logger.warning("[AntiSpam] config.yml $section.$it") }
    }

    companion object {
        const val PERM_BYPASS = "joshymc.antispam.bypass"
    }
}
