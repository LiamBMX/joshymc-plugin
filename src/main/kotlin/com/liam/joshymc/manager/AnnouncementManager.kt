package com.liam.joshymc.manager

import com.liam.joshymc.Joshymc
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Sound

class AnnouncementManager(private val plugin: Joshymc) {

    /** A configured announcement: a message and an optional per-message sound. */
    data class AnnouncementEntry(val message: String, val sound: Sound?, val volume: Float, val pitch: Float)

    private var taskId: Int = -1
    private var currentIndex: Int = 0
    private var entries: List<AnnouncementEntry> = emptyList()

    /** Default sound used when an entry doesn't define one. */
    private val defaultSound: Sound get() = parseSound(plugin.config.getString("announcements.default-sound", "BLOCK_NOTE_BLOCK_BELL"))
        ?: Sound.BLOCK_NOTE_BLOCK_BELL

    fun start() {
        val enabled = plugin.config.getBoolean("announcements.enabled", true)
        if (!enabled) return

        val intervalSeconds = plugin.config.getInt("announcements.interval-seconds", 300)
        entries = loadEntries()

        if (entries.isEmpty()) {
            plugin.logger.info("[Announcements] No messages configured, skipping.")
            return
        }

        currentIndex = 0
        val intervalTicks = intervalSeconds * 20L

        taskId = plugin.server.scheduler.scheduleSyncRepeatingTask(plugin, Runnable {
            val entry = entries[currentIndex % entries.size]
            broadcastEntry(entry)
            currentIndex = (currentIndex + 1) % entries.size
        }, intervalTicks, intervalTicks)

        plugin.logger.info("[Announcements] Broadcasting ${entries.size} messages every ${intervalSeconds}s.")
    }

    /**
     * Load announcements supporting two formats:
     *
     *   announcements.messages: ["plain text", ...]   (legacy — uses default sound)
     *
     *   announcements.messages:
     *     - text: "&fHello!"
     *       sound: ENTITY_EXPERIENCE_ORB_PICKUP
     *       volume: 1.0
     *       pitch: 1.5
     *     - text: "&fAnother!"            (no sound block — uses default)
     */
    private fun loadEntries(): List<AnnouncementEntry> {
        val raw = plugin.config.getList("announcements.messages") ?: return emptyList()
        val list = mutableListOf<AnnouncementEntry>()
        for (item in raw) {
            when (item) {
                is String -> list.add(AnnouncementEntry(item, null, 0.7f, 1.0f))
                is Map<*, *> -> {
                    val text = item["text"]?.toString() ?: continue
                    val soundName = item["sound"]?.toString()
                    val sound = soundName?.let { parseSound(it) }
                    val volume = (item["volume"] as? Number)?.toFloat() ?: 0.7f
                    val pitch = (item["pitch"] as? Number)?.toFloat() ?: 1.0f
                    list.add(AnnouncementEntry(text, sound, volume, pitch))
                }
            }
        }
        return list
    }

    private fun parseSound(name: String?): Sound? {
        if (name.isNullOrBlank()) return null
        return try {
            Sound.valueOf(name.uppercase())
        } catch (_: Exception) {
            plugin.logger.warning("[Announcements] Unknown sound: $name")
            null
        }
    }

    fun stop() {
        if (taskId != -1) {
            plugin.server.scheduler.cancelTask(taskId)
            taskId = -1
        }
        currentIndex = 0
    }

    /**
     * Broadcast a plain string announcement (legacy entry point).
     * Plays the default sound. Use [broadcastEntry] to play per-message sound.
     */
    fun broadcast(message: String) {
        broadcastEntry(AnnouncementEntry(message, null, 0.7f, 1.0f))
    }

    /** Broadcast an entry with its configured sound (or the default if none set). */
    fun broadcastEntry(entry: AnnouncementEntry) {
        // All lines go out as one message so they always arrive together.
        val component = format(entry.message, plugin.commsManager::parseLegacy) ?: return
        Bukkit.broadcast(component)

        val sound = entry.sound ?: defaultSound
        for (player in Bukkit.getOnlinePlayers()) {
            player.playSound(player.location, sound, entry.volume, entry.pitch)
        }
    }

    companion object {
        /**
         * The announcement as one component: each non-blank line of [message]
         * (split on `\n`) parsed with [parse], white unless the text colours
         * itself, joined by newlines. No header above it (issue #1276). Lines
         * are siblings, so `&` codes in one line can't restyle the next. Null
         * when the message has no visible text.
         */
        fun format(message: String, parse: (String) -> Component): Component? {
            val lines = message.split("\\n", "\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            return Component.join(
                JoinConfiguration.newlines(),
                lines.map { parse(it).colorIfAbsent(NamedTextColor.WHITE) }
            )
        }
    }

    /**
     * Get all configured template messages with their index as a key.
     * Returns map of template name (derived from content) to the raw message.
     */
    fun getTemplates(): Map<String, String> {
        val messages = plugin.config.getStringList("announcements.messages")
        val templates = mutableMapOf<String, String>()
        for ((index, msg) in messages.withIndex()) {
            // Strip color codes to create a clean template name
            val clean = msg.replace("&[0-9a-fk-or]".toRegex(), "")
                .trim()
                .lowercase()
                .replace(" ", "_")
                .replace("[^a-z0-9_]".toRegex(), "")
                .take(30)
            templates[clean.ifEmpty { "template_$index" }] = msg
        }
        return templates
    }
}
